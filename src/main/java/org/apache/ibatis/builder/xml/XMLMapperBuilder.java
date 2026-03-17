/**
 *    Copyright 2009-2026 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * 负责解析映射文件
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLMapperBuilder extends BaseBuilder {

  private final XPathParser parser;
  private final MapperBuilderAssistant builderAssistant;
  private final Map<String, XNode> sqlFragments;
  private final String resource;

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(reader, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(inputStream, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    super(configuration);
    this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
    this.parser = parser;
    this.sqlFragments = sqlFragments;
    this.resource = resource;
  }

  /**
   * 解析 Mapper 映射文件（Mapper.xml）的核心入口。
   *
   * 总体职能分为三部分：
   * 1. 静态元数据解析（SQL 语句、结果集映射等）。
   * 2. 接口与 XML 的动态绑定。
   * 3. 解决跨文件引用的延迟处理。
   */
  public void parse() {
    // 1. 【幂等性检查】：判断该映射资源是否已经加载过。
    // 防止重复解析同一个 XML 文件导致的重复注册异常。
    if (!configuration.isResourceLoaded(resource)) {
      // 2. 【核心解析步骤】：解析 <mapper> 根节点及其内部所有子节点
      // 包括：<cache>, <parameterMap>, <resultMap>, <sql>, 以及增删改查标签
      // 该方法会将每一个 SQL 标签封装成一个 MappedStatement 对象
      configurationElement(parser.evalNode("/mapper"));

      // 3. 标记该资源为已加载状态
      configuration.addLoadedResource(resource);

      // 4. 【接口绑定】： 注册 Mapper 接口，将 XML 的 namespace 与 Java Mapper 接口进行关联
      // 如果 namespace 对应的是一个真实存在的类全限定名，则将其注册到 MapperRegistry 中。
      // 并同步为该接口创建一个专属的 MapperProxyFactory。
      // 这是 sqlSession.getMapper() 能够通过 JDK 动态代理生产接口实现类的核心前提。
      bindMapperForNamespace();
    }

    // 5. 【容错与延迟解析机制】：处理因“跨文件引用”导致暂时解析失败的节点
    // 场景：XML A 引用了 XML B 的 ResultMap，但解析 A 时 B 还没加载。
    // MyBatis 会将这些失败的节点存入“等待队列”，在此处尝试重新解析。

    // 处理 configurationElement 方法中解析失败的 <resultMap> 节点
    parsePendingResultMaps();
    // 处理 configurationElement 方法中解析失败的 <cache-ref> 节点（跨命名空间的缓存引用）
    parsePendingCacheRefs();
    // 处理 configurationElement 方法中解析失败的 SQL 语句节点（MappedStatement）
    parsePendingStatements();
  }

  public XNode getSqlFragment(String refid) {
    return sqlFragments.get(refid);
  }

  /**
   * 解析 Mapper XML 文件的根节点 <mapper> 及其内部所有子节点。
   * 这是解析过程的入口方法，负责将 XML 格式的配置转换为 Java 对象，并注册到 Configuration 全局配置对象中。
   */
  private void configurationElement(XNode context) {
    try {
      // 1. 解析命名空间 (namespace)
      // 获取 namespace 属性值，用于唯一标识该映射文件，并将其与对应的 Mapper 接口进行绑定。
      String namespace = context.getStringAttribute("namespace");
      if (namespace == null || namespace.equals("")) {
        throw new BuilderException("Mapper's namespace cannot be empty");
      }
      // 在构建助手 (MapperBuilderAssistant) 中保存当前命名空间，作为后续 SQL 语句 ID 的前缀。
      builderAssistant.setCurrentNamespace(namespace);

      // 2. 处理缓存引用 (cache-ref)
      // 解析 cache-ref 节点，实现多个命名空间（namespace）对同一个二级缓存实例的共享。
      cacheRefElement(context.evalNode("cache-ref"));

      // 3. 处理二级缓存配置 (cache)
      // 解析 cache 节点，为当前命名空间创建并初始化二级缓存对象。
      cacheElement(context.evalNode("cache"));

      // 4. 处理参数映射 (parameterMap) —— 已废弃
      // 解析 parameterMap 节点并注册。现代开发通常使用内联参数处理，此配置较少使用。
      parameterMapElement(context.evalNodes("/mapper/parameterMap"));

      // 5. 处理结果集映射 (resultMap)
      // 解析 resultMap 节点，定义数据库列名与 Java 实体类属性之间的映射规则，存入 Configuration。
      resultMapElements(context.evalNodes("/mapper/resultMap"));

      // 6. 处理可重用 SQL 片段 (sql)
      // 解析 sql 标签，将可复用的 SQL 代码块保存到全局配置对象的 sqlFragments 集合中。
      sqlElement(context.evalNodes("/mapper/sql"));

      // 7. 处理 SQL 执行语句 (select|insert|update|delete)
      // 解析具体的 CRUD 标签，每个标签都会被实例化为一个 MappedStatement 对象并执行注册。
      buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
    }
  }

  /**
   * 根据 XML 节点上下文列表构建 MappedStatement 实例。
   *
   * 此方法会根据全局配置的 databaseId 执行两轮解析：
   * 1. 第一轮：解析匹配当前数据库厂商标识的 SQL 节点。
   * 2. 第二轮：解析未指定 databaseId（通用）的 SQL 节点。
   *
   * @param list 包含增删改查标签的 XNode 节点集合
   */
  private void buildStatementFromContext(List<XNode> list) {
    // 1. 多数据库,如果全局配置中指定了数据库厂商 ID（如 mysql, oracle）
    if (configuration.getDatabaseId() != null) {
      // 优先解析带有对应 databaseId 属性的 SQL 标签
      buildStatementFromContext(list, configuration.getDatabaseId());
    }
    // 2. 统一解析没有设置 databaseId 属性的 SQL 标签
    buildStatementFromContext(list, null);
  }

  /**
   * 遍历节点列表，委派 XMLStatementBuilder 执行具体的节点解析。
   *
   * @param list               SQL 节点集合
   * @param requiredDatabaseId 当前环境要求的数据库标识
   */
  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
      // 为每个 SQL 标签实例化一个专门的语句解析器用来解析增删改查标签的 XMLStatementBuilder
      // 传入当前配置对象、构建助手、节点内容以及要求的数据库标识
      final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
      try {

        // 执行解析动作：将 XML 标签转化为 MappedStatement 对象并注册到 Configuration
        // 解析具体的 insert/update/delete/select
        statementParser.parseStatementNode();
      } catch (IncompleteElementException e) {
        // 异常补救机制：如果解析过程中发现依赖的资源（如 ResultMap）尚未加载完成，
        // 则将该解析对象存入 Configuration 的“不完整语句集合”中，待后续所有 XML 加载完毕后再尝试重新解析。
        configuration.addIncompleteStatement(statementParser);
      }
    }
  }

  private void parsePendingResultMaps() {
    Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
    synchronized (incompleteResultMaps) {
      Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // ResultMap is still missing a resource...
        }
      }
    }
  }

  private void parsePendingCacheRefs() {
    Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
    synchronized (incompleteCacheRefs) {
      Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolveCacheRef();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Cache ref is still missing a resource...
        }
      }
    }
  }

  private void parsePendingStatements() {
    Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
    synchronized (incompleteStatements) {
      Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().parseStatementNode();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Statement is still missing a resource...
        }
      }
    }
  }

  private void cacheRefElement(XNode context) {
    if (context != null) {
      // 将当前Mapper配置文件的 namespace 和被引用的 Cache 所在的 namespace 之间 建立映射关系
      configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
      // 创建 CacheRefResolver 对象
      CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
      try {
        cacheRefResolver.resolveCacheRef();
      } catch (IncompleteElementException e) {
        // 记录解析异常的 CacheRefResolver对象  稍后在解析
        configuration.addIncompleteCacheRef(cacheRefResolver);
      }
    }
  }

  private void cacheElement(XNode context) {
    // 只有 cache 标签不为空才解析
    if (context != null) {
      String type = context.getStringAttribute("type", "PERPETUAL");
      // 获取 type 属性指定的 Cache 类型
      Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
      // 获取 eviction 属性指定的值，默认是 LRU
      String eviction = context.getStringAttribute("eviction", "LRU");
      // 获取 Evication 属性指定 Cache 装饰器 类型
      Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
      // 获取 flushInterval 属性指定的值，默认是 0
      Long flushInterval = context.getLongAttribute("flushInterval");
      // 获取 size 属性指定的值，默认是 1024
      Integer size = context.getIntAttribute("size");
      boolean readWrite = !context.getBooleanAttribute("readOnly", false);
      boolean blocking = context.getBooleanAttribute("blocking", false);
      // 获取 <cache> 节点下的子节点，将用于初始化二级缓存
      Properties props = context.getChildrenAsProperties();
      // 通过 MapperBuilderAssistant 创建 Cache 对象，并添加到 Configuration.caches 集合中保存
      builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
    }
  }

  private void parameterMapElement(List<XNode> list) {
    for (XNode parameterMapNode : list) {
      String id = parameterMapNode.getStringAttribute("id");
      String type = parameterMapNode.getStringAttribute("type");
      Class<?> parameterClass = resolveClass(type);
      List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
      List<ParameterMapping> parameterMappings = new ArrayList<>();
      for (XNode parameterNode : parameterNodes) {
        String property = parameterNode.getStringAttribute("property");
        String javaType = parameterNode.getStringAttribute("javaType");
        String jdbcType = parameterNode.getStringAttribute("jdbcType");
        String resultMap = parameterNode.getStringAttribute("resultMap");
        String mode = parameterNode.getStringAttribute("mode");
        String typeHandler = parameterNode.getStringAttribute("typeHandler");
        Integer numericScale = parameterNode.getIntAttribute("numericScale");
        ParameterMode modeEnum = resolveParameterMode(mode);
        Class<?> javaTypeClass = resolveClass(javaType);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
        parameterMappings.add(parameterMapping);
      }
      builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
    }
  }

  private void resultMapElements(List<XNode> list) {
    for (XNode resultMapNode : list) {
      try {
        resultMapElement(resultMapNode);
      } catch (IncompleteElementException e) {
        // ignore, it will be retried
      }
    }
  }

  private ResultMap resultMapElement(XNode resultMapNode) {
    return resultMapElement(resultMapNode, Collections.emptyList(), null);
  }

  private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings, Class<?> enclosingType) {
    ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
    String type = resultMapNode.getStringAttribute("type",
        resultMapNode.getStringAttribute("ofType",
            resultMapNode.getStringAttribute("resultType",
                resultMapNode.getStringAttribute("javaType"))));
    Class<?> typeClass = resolveClass(type);
    if (typeClass == null) {
      typeClass = inheritEnclosingType(resultMapNode, enclosingType);
    }
    Discriminator discriminator = null;
    List<ResultMapping> resultMappings = new ArrayList<>(additionalResultMappings);
    List<XNode> resultChildren = resultMapNode.getChildren();
    for (XNode resultChild : resultChildren) {
      if ("constructor".equals(resultChild.getName())) {
        processConstructorElement(resultChild, typeClass, resultMappings);
      } else if ("discriminator".equals(resultChild.getName())) {
        discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
      } else {
        List<ResultFlag> flags = new ArrayList<>();
        if ("id".equals(resultChild.getName())) {
          flags.add(ResultFlag.ID);
        }
        resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
      }
    }
    String id = resultMapNode.getStringAttribute("id",
            resultMapNode.getValueBasedIdentifier());
    String extend = resultMapNode.getStringAttribute("extends");
    Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
    ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
    try {
      return resultMapResolver.resolve();
    } catch (IncompleteElementException  e) {
      configuration.addIncompleteResultMap(resultMapResolver);
      throw e;
    }
  }

  protected Class<?> inheritEnclosingType(XNode resultMapNode, Class<?> enclosingType) {
    if ("association".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
      String property = resultMapNode.getStringAttribute("property");
      if (property != null && enclosingType != null) {
        MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
        return metaResultType.getSetterType(property);
      }
    } else if ("case".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
      return enclosingType;
    }
    return null;
  }

  private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) {
    List<XNode> argChildren = resultChild.getChildren();
    for (XNode argChild : argChildren) {
      List<ResultFlag> flags = new ArrayList<>();
      flags.add(ResultFlag.CONSTRUCTOR);
      if ("idArg".equals(argChild.getName())) {
        flags.add(ResultFlag.ID);
      }
      resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
    }
  }

  private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) {
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String typeHandler = context.getStringAttribute("typeHandler");
    Class<?> javaTypeClass = resolveClass(javaType);
    Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    Map<String, String> discriminatorMap = new HashMap<>();
    for (XNode caseChild : context.getChildren()) {
      String value = caseChild.getStringAttribute("value");
      String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings, resultType));
      discriminatorMap.put(value, resultMap);
    }
    return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
  }

  private void sqlElement(List<XNode> list) {
    if (configuration.getDatabaseId() != null) {
      sqlElement(list, configuration.getDatabaseId());
    }
    sqlElement(list, null);
  }

  private void sqlElement(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
      String databaseId = context.getStringAttribute("databaseId");
      String id = context.getStringAttribute("id");
      id = builderAssistant.applyCurrentNamespace(id, false);
      if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
        sqlFragments.put(id, context);
      }
    }
  }

  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) {
      return requiredDatabaseId.equals(databaseId);
    }
    if (databaseId != null) {
      return false;
    }
    if (!this.sqlFragments.containsKey(id)) {
      return true;
    }
    // skip this fragment if there is a previous one with a not null databaseId
    XNode context = this.sqlFragments.get(id);
    return context.getStringAttribute("databaseId") == null;
  }

  private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) {
    String property;
    if (flags.contains(ResultFlag.CONSTRUCTOR)) {
      property = context.getStringAttribute("name");
    } else {
      property = context.getStringAttribute("property");
    }
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String nestedSelect = context.getStringAttribute("select");
    String nestedResultMap = context.getStringAttribute("resultMap", () ->
      processNestedResultMappings(context, Collections.emptyList(), resultType));
    String notNullColumn = context.getStringAttribute("notNullColumn");
    String columnPrefix = context.getStringAttribute("columnPrefix");
    String typeHandler = context.getStringAttribute("typeHandler");
    String resultSet = context.getStringAttribute("resultSet");
    String foreignColumn = context.getStringAttribute("foreignColumn");
    boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
    Class<?> javaTypeClass = resolveClass(javaType);
    Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
  }

  private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings, Class<?> enclosingType) {
    if ("association".equals(context.getName())
        || "collection".equals(context.getName())
        || "case".equals(context.getName())) {
      if (context.getStringAttribute("select") == null) {
        validateCollection(context, enclosingType);
        ResultMap resultMap = resultMapElement(context, resultMappings, enclosingType);
        return resultMap.getId();
      }
    }
    return null;
  }

  protected void validateCollection(XNode context, Class<?> enclosingType) {
    if ("collection".equals(context.getName()) && context.getStringAttribute("resultMap") == null
        && context.getStringAttribute("javaType") == null) {
      MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
      String property = context.getStringAttribute("property");
      if (!metaResultType.hasSetter(property)) {
        throw new BuilderException(
          "Ambiguous collection type for property '" + property + "'. You must specify 'javaType' or 'resultMap'.");
      }
    }
  }

  /**
   * 执行 XML 命名空间与 Java 接口的强制绑定。
   */
  private void bindMapperForNamespace() {
    // 约定 namespace 要和对应的接口的全类路径名称保持一致
    String namespace = builderAssistant.getCurrentNamespace();
    if (namespace != null) {
      Class<?> boundType = null;
      try {
        // 将 XML 中的 namespace 名称空间字符串解析为真实的 Java 接口类型
        boundType = Resources.classForName(namespace);
      } catch (ClassNotFoundException e) {
        //ignore, bound type is not required
        // 若 namespace 不是一个全限定类名，则忽略（可能仅作为逻辑标识）
      }
      if (boundType != null) {
        // 检查该接口是否已在注册表MapperRegistry中是否注册的有当前类型的 MapperProxyFactory对象，避免重复加载
        if (!configuration.hasMapper(boundType)) {
          // Spring may not know the real resource name so we set a flag
          // to prevent loading again this resource from the mapper interface
          // look at MapperAnnotationBuilder#loadXmlResource

          // 标记该命名空间资源已加载，防止在解析接口注解时触发循环加载 XML
          configuration.addLoadedResource("namespace:" + namespace);

          // 正式进入接口注册与注解解析流程
          // 添加到 MapperRegistry，本质是一个 map，里面也有 Configuration
          configuration.addMapper(boundType);
        }
      }
    }
  }

}
