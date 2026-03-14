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

import java.util.List;
import java.util.Locale;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class XMLStatementBuilder extends BaseBuilder {

  private final MapperBuilderAssistant builderAssistant;
  private final XNode context;
  private final String requiredDatabaseId;

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context) {
    this(configuration, builderAssistant, context, null);
  }

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context, String databaseId) {
    super(configuration);
    this.builderAssistant = builderAssistant;
    this.context = context;
    this.requiredDatabaseId = databaseId;
  }

  /**
   * XMLMapperBuilder 中会将 mapper 映射文件中除 CRUD 外的标签解析验证，轮到CRUD标签的时候，是交给专门的类去做处理的，也就是XMLStatementBuilder。
   * XMLStatementBuilder的解析工作：
   * 第一步：解析一些较基本的属性，比如 id、databaseId(多数据库配置)、useCache(是否启动二级缓存）、flushCache(只要语句调用就刷新缓存)等。
   * 第二步；替换sql中的include标签，将复用其它地方的sql整合处理成为一个完整的sql
   * 第三步：处理主键生成策略，mybatis 默认使用的是 NoKeyGenerator，如果设置了 useGeneratedKeys 会使用 Jdbc3KeyGenerator
   * 第四步：mybatis到了这一步，会将sql处理成为sqlSource存储起来并且替换类似#{}这样的特殊字符，
   *  SqlSource是mybatis中比较重要的一个部分，所以这一步sql处理会比较复杂。
   *
   * <!ELEMENT select (#PCDATA | include | trim | where | set | foreach | choose | if | bind)*>
   * <!ATTLIST select
   * id CDATA #REQUIRED
   * parameterMap CDATA #IMPLIED
   * parameterType CDATA #IMPLIED
   * resultMap CDATA #IMPLIED
   * resultType CDATA #IMPLIED
   * resultSetType (FORWARD_ONLY | SCROLL_INSENSITIVE | SCROLL_SENSITIVE | DEFAULT) #IMPLIED
   * statementType (STATEMENT|PREPARED|CALLABLE) #IMPLIED
   * fetchSize CDATA #IMPLIED
   * timeout CDATA #IMPLIED
   * flushCache (true|false) #IMPLIED
   * useCache (true|false) #IMPLIED
   * databaseId CDATA #IMPLIED
   * lang CDATA #IMPLIED
   * resultOrdered (true|false) #IMPLIED
   * resultSets CDATA #IMPLIED
   * >
   *
   */
  public void parseStatementNode() {
    // 1. 【属性获取与环境匹配】：获取标签 ID 与数据库厂商标识
    String id = context.getStringAttribute("id");
    String databaseId = context.getStringAttribute("databaseId");

    // 验证当前节点的 databaseId 是否与全局配置匹配，不匹配则跳过解析
    if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
      return;
    }

    // 2. 获取节点的名称  select insert delete update
    String nodeName = context.getNode().getNodeName();

    // 获取到具体的 sql 命令 类型
    SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;

    // 确定缓存策略：默认情况下，SELECT 不刷新缓存且使用二级缓存；其他操作则相反
    boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);
    boolean useCache = context.getBooleanAttribute("useCache", isSelect);
    boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);

    // Include Fragments before parsing
    // 3. 【SQL 结构预处理】：处理 <include> 标签
    // 实例化 XMLIncludeTransformer，将 SQL 片段（SqlFragment）替换到当前语句中，并解析 ${} 变量
    XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
    includeParser.applyIncludes(context.getNode());

    // 4. 【参数与语言驱动解析】：获取入参类型与脚本语言驱动
    String parameterType = context.getStringAttribute("parameterType");
    Class<?> parameterTypeClass = resolveClass(parameterType);

    String lang = context.getStringAttribute("lang");
    LanguageDriver langDriver = getLanguageDriver(lang);

    // Parse selectKey after includes and remove them.
    // 5. 【辅助节点处理】：解析并移除 <selectKey> 节点
    // 处理如 Oracle 序列等主键生成逻辑
    processSelectKeyNodes(id, parameterTypeClass, langDriver);

    // Parse the SQL (pre: <selectKey> and <include> were parsed and removed)
    // 6. 【主键生成策略配置】：确定 KeyGenerator 实例
    KeyGenerator keyGenerator;
    String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);

    // 优先检查是否存在 <selectKey> 产生的 KeyGenerator
    if (configuration.hasKeyGenerator(keyStatementId)) {
      keyGenerator = configuration.getKeyGenerator(keyStatementId);
    } else {
      // 否则根据 useGeneratedKeys 属性决定使用 JDBC3KeyGenerator 还是 NoKeyGenerator
      keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
          configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
          ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
    }

    // 7. 【SQL 模型构建】：创建 SqlSource 对象
    // 调用语言驱动（如 XMLLanguageDriver）解析动态 SQL（if/where/foreach）
    // 此步骤会处理 SQL 中的占位符并生成对应的 ParameterMapping
    SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);

    // 8. 【执行上下文属性解析】：获取 Statement 类型、抓取大小、超时时间等
    StatementType statementType = StatementType.valueOf(context.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    Integer fetchSize = context.getIntAttribute("fetchSize");
    Integer timeout = context.getIntAttribute("timeout");
    String parameterMap = context.getStringAttribute("parameterMap");
    String resultType = context.getStringAttribute("resultType");
    Class<?> resultTypeClass = resolveClass(resultType);
    String resultMap = context.getStringAttribute("resultMap");
    String resultSetType = context.getStringAttribute("resultSetType");
    ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);
    if (resultSetTypeEnum == null) {
      resultSetTypeEnum = configuration.getDefaultResultSetType();
    }
    String keyProperty = context.getStringAttribute("keyProperty");
    String keyColumn = context.getStringAttribute("keyColumn");
    String resultSets = context.getStringAttribute("resultSets");

    // 9. 【最终注册】：关键的一步实例化 MappedStatement 并存入 Configuration 对象
    // 通过构建助手将上述所有元数据聚合为一个 MappedStatement 对象
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered,
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
  }

  private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
    List<XNode> selectKeyNodes = context.evalNodes("selectKey");
    if (configuration.getDatabaseId() != null) {
      parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
    }
    parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);
    removeSelectKeyNodes(selectKeyNodes);
  }

  private void parseSelectKeyNodes(String parentId, List<XNode> list, Class<?> parameterTypeClass, LanguageDriver langDriver, String skRequiredDatabaseId) {
    for (XNode nodeToHandle : list) {
      String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
      String databaseId = nodeToHandle.getStringAttribute("databaseId");
      if (databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
        parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
      }
    }
  }

  private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass, LanguageDriver langDriver, String databaseId) {
    String resultType = nodeToHandle.getStringAttribute("resultType");
    Class<?> resultTypeClass = resolveClass(resultType);
    StatementType statementType = StatementType.valueOf(nodeToHandle.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    String keyProperty = nodeToHandle.getStringAttribute("keyProperty");
    String keyColumn = nodeToHandle.getStringAttribute("keyColumn");
    boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER"));

    //defaults
    boolean useCache = false;
    boolean resultOrdered = false;
    KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
    Integer fetchSize = null;
    Integer timeout = null;
    boolean flushCache = false;
    String parameterMap = null;
    String resultMap = null;
    ResultSetType resultSetTypeEnum = null;

    SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
    SqlCommandType sqlCommandType = SqlCommandType.SELECT;

    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered,
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);

    id = builderAssistant.applyCurrentNamespace(id, false);

    MappedStatement keyStatement = configuration.getMappedStatement(id, false);
    configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
  }

  private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
    for (XNode nodeToHandle : selectKeyNodes) {
      nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
    }
  }

  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) {
      return requiredDatabaseId.equals(databaseId);
    }
    if (databaseId != null) {
      return false;
    }
    id = builderAssistant.applyCurrentNamespace(id, false);
    if (!this.configuration.hasStatement(id, false)) {
      return true;
    }
    // skip this statement if there is a previous one with a not null databaseId
    MappedStatement previous = this.configuration.getMappedStatement(id, false); // issue #2
    return previous.getDatabaseId() == null;
  }

  private LanguageDriver getLanguageDriver(String lang) {
    Class<? extends LanguageDriver> langClass = null;
    if (lang != null) {
      langClass = resolveClass(lang);
    }
    return configuration.getLanguageDriver(langClass);
  }

}
