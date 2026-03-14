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
import java.util.Properties;
import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {
  // 标识是否解析过
  private boolean parsed;
  // 用于解析mybatis-config.xml 配置文件的 XPathParser对象
  private final XPathParser parser;
  // 标识 <environment>
  private String environment;
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    // EntityResolver的实现类是XMLMapperEntityResolver 来完成配置文件的校验，根据对应的DTD文件来实现
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    super(new Configuration()); // 完成了Configuration的初始化  类型别名的注册
    ErrorContext.instance().resource("SQL Mapper Configuration");
    this.configuration.setVariables(props); // 设置对应的Properties属性
    this.parsed = false; // 设置 是否解析的标志为 false
    this.environment = environment; // 初始化environment
    this.parser = parser; // 初始化 解析器
  }

  /**
   * 启动全局配置文件的解析流程。
   *
   * <p>该方法是 {@link XMLConfigBuilder} 的核心主控逻辑，负责将原始的 XML
   * 转换成 MyBatis 运行时的核心大脑 —— {@link Configuration} 对象。
   *
   * @return 填充完毕的全局配置对象
   * @throws BuilderException 如果同一个解析器实例被重复使用，或解析过程出错
   */
  public Configuration parse() {
    // 1. 【幂等性检查】
    // 状态位检查：每个 XMLConfigBuilder 实例是一次性的，内部持有解析状态。
    // 确保同一个解析器不会解析多次，防止 Configuration 对象的数据被污染。
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    // 2. 标记解析状态
    parsed = true;

    // 3. 【核心入口：定位根节点】
    // 使用内置的 XPathParser 解析器，利用 XPath 表达式直接定位 XML 中的根节点 <configuration>。
    // evalNode 方法会将原生的 W3C DOM 节点包装成 MyBatis 专用的 XNode 对象。
    // 随后调用 parseConfiguration 方法开始 解析 <configuration> 下的所有子标签。
    parseConfiguration(parser.evalNode("/configuration"));

    // 4. 返回已经填充完毕的全局配置对象，其中包含各种配置、插件、映射器信息的全局对象
    return configuration;
  }

  /**
   * 解析全局配置文件的根节点 <configuration>
   * 所有的解析结果最终都会存储在 Configuration 对象中
   */
  private void parseConfiguration(XNode root) {
    try {
      //issue #117 read properties first

      // 1. 解析 <properties> 标签
      // 读取外部 properties 文件或内部定义的属性，后续标签可以使用 ${} 引用
      // 保存到了 Configuration 对象中
      propertiesElement(root.evalNode("properties"));

      // 2. 解析 <settings> 标签
      // 这一步只是先将配置读取为 Properties 对象，尚未应用到 Configuration 对象中
      Properties settings = settingsAsProperties(root.evalNode("settings"));

      // 3. 加载自定义虚拟文件系统 (VFS) 实现
      loadCustomVfs(settings);

      // 4. 加载自定义日志实现 (logImpl)
      loadCustomLogImpl(settings);

      // 5. 解析 <typeAliases> 标签
      // 注册类型别名，方便在映射文件中使用简写
      typeAliasesElement(root.evalNode("typeAliases"));

      // 6. 解析 <plugins> 标签
      // 注册 MyBatis 拦截器（如分页插件、性能分析插件）
      pluginElement(root.evalNode("plugins"));

      // 7. 解析 <objectFactory> 标签
      // MyBatis 创建结果对象实例时使用的工厂
      objectFactoryElement(root.evalNode("objectFactory"));

      // 8. 解析 <objectWrapperFactory> 标签
      // 用于对对象进行装饰，增强对象属性操作能力
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));

      // 9. 解析 <reflectorFactory> 标签
      // MyBatis 的反射缓存工厂，优化反射操作性能
      reflectorFactoryElement(root.evalNode("reflectorFactory"));

      // 10. 将第 2 步读取的 settings 配置应用到 Configuration 对象中
      // 此时会填充全局属性的默认值（如开启驼峰命名、延迟加载等）
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631

      // 11. 解析 <environments> 标签
      // 配置数据源 (DataSource) 和事务管理器 (TransactionManager)
      environmentsElement(root.evalNode("environments"));

      // 12. 解析 <databaseIdProvider> 标签
      // 用于支持多数据库厂商，根据不同的数据库执行不同的 SQL
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));

      // 13. 解析 <typeHandlers> 标签
      // 注册类型处理器，处理 Java 类型与 JDBC 类型之间的转换
      typeHandlerElement(root.evalNode("typeHandlers"));
      // 解析引用的Mapper映射器 ===》 映射文件的加载解析

      // 14. 解析 <mappers> 标签
      // 核心：加载 SQL 映射文件（XML）或 Mapper 接口类
      // 映射文件中的信息 加载解析出来后保存到了哪个对象中？
      // Configuration 对象中
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  /**
   * 将 <settings> 标签中的配置信息封装到 Properties 对象中并返回。
   *
   * 此方法通过反射机制校验 XML 中定义的每一个 setting 是否在 Configuration 类中有对应的 Setter 方法，
   * 从而在初始化阶段就能发现并拦截配置项拼写错误。
   */
  private Properties settingsAsProperties(XNode context) {
    // 1. 如果没有配置 <settings> 标签，直接返回空的 Properties 对象
    if (context == null) {
      return new Properties();
    }

    // 2. 将 <settings> 节点下的所有子节点 <setting name="..." value="..."/>
    // 转化为 Properties 对象（Key: name, Value: value）
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class

    // 3. 【核心校验逻辑】利用 MyBatis 的反射工具箱 MetaClass 检查配置的合法性
    // MetaClass 用于获取类（Configuration）的元数据，结合 localReflectorFactory 进行反射操作
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);

    // 4. 遍历用户在 XML 中写的所有配置项的 Key
    for (Object key : props.keySet()) {
      /*
       * 5. 校验逻辑：检查 Configuration 类中是否拥有该 Key 对应的 Setter 方法。
       *
       * 比如用户写了 <setting name="mapUnderscoreToCamelCase" value="true"/>
       * metaConfig 会去检查 Configuration 类中是否有 setMapUnderscoreToCamelCase 方法。
       * 如果写错了，校验就会失败。
       */
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        // 如果没找到对应的属性，直接抛出异常，防止程序带病运行
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    // 6. 返回经过合法性校验的配置集合
    return props;
  }

  /**
   * 加载自定义的 VFS (虚拟文件系统) 实现类。
   *
   * VFS 主要用于 MyBatis 在各种复杂的容器环境（如 Tomcat、JBoss、WebSphere）中，
   * 能够正确地扫描和读取资源文件（如 Mapper XML 或 Class 文件）。
   *
   * @param props 从 <settings> 标签中解析出的配置属性
   */
  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    // 1. 从配置项中获取键为 "vfsImpl" 的属性值
    // 对应配置：<setting name="vfsImpl" value="com.example.MyCustomVFS"/>
    String value = props.getProperty("vfsImpl");
    if (value != null) {
      // 2. 支持配置多个实现类，使用逗号分隔
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked")
          // 3. 利用 MyBatis 的 Resources 工具类加载对应的 Class 对象
          // 必须是 VFS 类的子类
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          // 4. 将自定义的 VFS 实现类注册到全局 Configuration 对象中
          // 后续 MyBatis 会优先使用这些自定义的 VFS 实现来进行路径扫描
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  /**
   * 加载自定义的日志实现类。
   *
   * MyBatis 内部并没有绑定死某一个日志框架，而是通过“适配器模式”支持多种日志库。
   * 用户可以通过 <setting name="logImpl" value="..."/> 显式指定使用的日志框架。
   *
   * @param props 从 <settings> 标签中解析出的配置属性
   */
  private void loadCustomLogImpl(Properties props) {

    // 1. 通过别名或全类名解析指定的日志实现类
    // resolveClass 是 BaseBuilder 里的方法，支持别名处理。
    // 常见的别名有：SLF4J, LOG4J, LOG4J2, JDK_LOGGING, COMMONS_LOGGING, STDOUT_LOGGING, NO_LOGGING
    Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));

    // 2. 将解析出来的日志类设置到 Configuration 全局配置对象中。
    // 在 Configuration 内部，setLogImpl 方法会被调用，
    // 紧接着会调用 LogFactory.useCustomLogging(logImpl) 来切换底层日志工厂。
    // 这是后续分析 MyBatis 日志拦截和 SQL 打印的核心入口。
    configuration.setLogImpl(logImpl);
  }


  /**
   * 解析 <typeAliases> 标签，将别名映射关系注册到 TypeAliasRegistry 中。
   */
  private void typeAliasesElement(XNode parent) {
    // 放入 TypeAliasRegistry
    if (parent != null) {

      // 1. 遍历 <typeAliases> 下的所有子标签（可以是 <package> 或 <typeAlias>）
      for (XNode child : parent.getChildren()) {

        // 2. 情况一：如果是 <package> 标签，执行自动包扫描
        if ("package".equals(child.getName())) {

          // 获取包名，例如：<package name="com.example.model"/>
          String typeAliasPackage = child.getStringAttribute("name");
          // 调用注册表，扫描该包下所有的类，并自动注册别名
          // 默认规则：类的简单名称（首字母小写），如 User.class -> user
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);

          // 3. 情况二：如果是具体的 <typeAlias> 标签，执行单条注册
        } else {
          // 获取配置的别名，例如：alias="User"
          String alias = child.getStringAttribute("alias");
          // 获取配置的完整类名，例如：type="com.example.model.User"
          String type = child.getStringAttribute("type");
          try {
            // 利用资源工具类加载该类的 Class 对象
            Class<?> clazz = Resources.classForName(type);
            if (alias == null) {

              // 3.1 如果 XML 中没有写 alias 属性：
              // 调用注册表，它会去类上找 @Alias("别名") 注解。
              // 如果连注解也没有，则使用类名的小写作为别名。
              typeAliasRegistry.registerAlias(clazz);
            } else {
              // 3.2 如果显式配置了 alias：
              // 直接以 XML 中配置的名字作为别名进行注册。
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  /**
   * 解析 <plugins> 标签，将自定义拦截器注册到 Configuration 对象中。
   *
   * MyBatis 插件通过“责任链模式”和“动态代理”实现，
   * 允许拦截 Executor、ParameterHandler、ResultSetHandler 和 StatementHandler 的核心方法。
   */
  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      // 1. 遍历 <plugins> 下的所有子标签 <plugin>
      for (XNode child : parent.getChildren()) {
        // 2. 获取 <plugin> 标签的 interceptor 属性
        // 例如：<plugin interceptor="com.github.pagehelper.PageInterceptor">
        String interceptor = child.getStringAttribute("interceptor");

        // 3. 解析该插件下的所有 <property> 子标签，封装为 Properties 对象
        // 这允许在 XML 中为插件传递配置参数
        Properties properties = child.getChildrenAsProperties();

        // 4. 通过别名或全类名解析获取 Class 对象，并利用反射创建 Interceptor 实例
        // resolveClass(interceptor) 会调用前面提到的别名注册表或类加载器
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).getDeclaredConstructor().newInstance();

        // 5. 调用拦截器的 setProperties 方法
        // 这就是为什么我们自定义插件时，可以重写 setProperties 方法来获取 XML 中的配置参数
        interceptorInstance.setProperties(properties);

        // 6. 将创建好的拦截器实例添加到全局 Configuration 对象的 interceptorChain（拦截器链）中
        // 所有的插件最终都会保存在 Configuration 内部的一个 List 集合里
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  /**
   * 解析 <objectFactory> 标签。
   *
   * ObjectFactory 是 MyBatis 用于创建结果对象（如 POJO、Map、List）实例的工厂。
   * 默认实现是 DefaultObjectFactory，通常不需要自定义，除非有特殊实例化需求。
   */
  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 1. 获取 <objectFactory> 标签的 type 属性
      // 例如：<objectFactory type="com.example.MyObjectFactory">
      String type = context.getStringAttribute("type");

      // 2. 解析该标签下的所有子标签 <property>，并封装为 Properties 对象
      // 允许通过配置文件向自定义的对象工厂传递初始化参数
      Properties properties = context.getChildrenAsProperties();

      // 3. 通过别名或全类名解析 Class 对象，并利用反射创建 ObjectFactory 实例
      // resolveClass(type) 同样使用了 MyBatis 的别名注册表
      ObjectFactory factory = (ObjectFactory) resolveClass(type).getDeclaredConstructor().newInstance();

      // 4. 将解析出的属性信息注入到对象工厂实例中
      // 自定义工厂可以根据这些属性来调整实例化逻辑
      factory.setProperties(properties);

      // 5. 将创建好的 ObjectFactory 对象设置到全局 Configuration 容器中
      // 之后 MyBatis 在处理结果集转换成 Java 对象时，都会通过这个工厂来创建实例
      configuration.setObjectFactory(factory);
    }
  }

  /**
   * 解析 <objectWrapperFactory> 标签。
   *
   * ObjectWrapperFactory 用于创建 ObjectWrapper 对象。
   * ObjectWrapper 的作用是：对对象进行“包装”，提供统一的接口来访问对象的属性（Getter/Setter）。
   */
  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 1. 获取 <objectWrapperFactory> 标签的 type 属性
      // 例如：<objectWrapperFactory type="com.example.MyObjectWrapperFactory"/>
      String type = context.getStringAttribute("type");

      // 2. 通过反射创建 ObjectWrapperFactory 实例
      // resolveClass(type) 负责处理别名或全类名
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).getDeclaredConstructor().newInstance();

      // 3. 将自定义的工厂实例注册到全局 Configuration 对象中
      // 默认情况下，MyBatis 使用 DefaultObjectWrapperFactory，它不提供任何包装功能
      configuration.setObjectWrapperFactory(factory);
    }
  }

  /**
   * 解析 <reflectorFactory> 标签。
   *
   * ReflectorFactory 是 MyBatis 的反射工厂，负责创建和缓存 Reflector 对象。
   * Reflector 是 MyBatis 内部对一个 Java 类的元数据封装，包含了该类的所有属性、Getter/Setter、构造方法等信息。
   */
  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 1. 获取 <reflectorFactory> 标签的 type 属性
      // 例如：<reflectorFactory type="com.example.MyReflectorFactory"/>
      String type = context.getStringAttribute("type");

      // 2. 通过别名或全类名解析 Class 对象，并利用反射创建 ReflectorFactory 实例
      // 默认实现类是 DefaultReflectorFactory
      ReflectorFactory factory = (ReflectorFactory) resolveClass(type).getDeclaredConstructor().newInstance();

      // 3. 将自定义的反射工厂实例注册到全局 Configuration 对象中
      // MyBatis 运行期间所有的反射需求（如结果集赋值、参数提取）都会经过这个工厂
      configuration.setReflectorFactory(factory);
    }
  }


  /**
   * 解析 <properties> 标签，将相关的属性信息更新保存到了 Configuration 对象中。
   *
   * 加载顺序与优先级（从低到高）：
   * 1. 在 <properties> 标签体内定义的子标签 <property>。
   * 2. 根据 resource 或 url 属性加载的外部 properties 文件（会覆盖第 1 步的同名属性）。
   * 3. 创建 SqlSessionFactory 时通过方法参数传入的 Properties（会覆盖前两步的同名属性）。
   */
  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {

      // 1. 提取 <properties> 标签体内定义的属性
      // 如：<property name="username" value="root"/>
      Properties defaults = context.getChildrenAsProperties();

      // 2. 读取 resource 和 url 属性
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");

      // 强制约束：resource 和 url 只能二选一
      if (resource != null && url != null) {
        // url 和 resource 不能同时存在
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }

      // 3. 加载外部定义的属性文件
      if (resource != null) {
        // 从类路径（classpath）加载
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        // 从文件系统或网络 URL 加载
        defaults.putAll(Resources.getUrlAsProperties(url));
      }

      // 4. 获取创建 SqlSessionFactory 时通过 SqlSessionFactoryBuilder 传入的外部变量
      // 这些变量具有最高优先级
      Properties vars = configuration.getVariables();
      if (vars != null) {
        // 和 Configuration中的 variables 属性合并
        defaults.putAll(vars);
      }

      // 5. 将最终合并后的所有属性设置到解析器中
      // 这一步非常关键：parser 会利用这些变量替换后续 XML 节点中的 ${key} 占位符
      parser.setVariables(defaults);

      // 6. 将属性保存到全局配置对象 Configuration中，方便程序运行期读取
      configuration.setVariables(defaults);
    }
  }

  /**
   * 将 <settings> 标签中的所有配置项填充到全局 Configuration 对象中。
   *
   * 此方法不仅负责赋值，还通过代码硬编码的方式定义了 MyBatis 全局属性的【默认值】。
   *
   * @param props 经过 settingsAsProperties 方法校验并提取后的配置集合
   */
  private void settingsElement(Properties props) {
    // 自动映射行为策略。默认 PARTIAL：只映射非嵌套的结果集
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));

    // 自动映射发现未知列时的处理行为。默认 NONE：不做任何处理
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));

    // 全局二级缓存总开关。默认开启：true
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));

    // 指定创建延迟加载代理对象的工厂实例
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));

    // 延迟加载全局开关。默认关闭：false
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));

    // 激进式延迟加载开关。默认 false：对象属性按需加载；若为 true，则调用任一属性都会加载对象全部属性
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));

    // 是否允许单一语句返回多个结果集（依赖驱动支持）。默认：true
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));

    // 使用列标签（Label）代替列名（Name）。默认：true
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));

    // 允许 JDBC 支持自动生成主键（通过 getGeneratedKeys 提取）。默认：false
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));

    // 默认执行器类型。SIMPLE：简单执行器；REUSE：重用预处理语句；BATCH：重用语句并执行批量更新
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));

    // 设置超时时间，决定驱动等待数据库响应的秒数
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));

    // 设置驱动程序批量抓取数据的大小（FetchSize）
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));

    // 设置默认结果集滚动类型（ResultSetType）
    configuration.setDefaultResultSetType(resolveResultSetType(props.getProperty("defaultResultSetType")));

    // 开启自动驼峰命名转换（数据库下划线字段 -> Java 驼峰属性）。默认：false
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));

    // 允许在嵌套语句中使用分页（RowBounds）。默认：false
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));

    // 一级缓存（本地缓存）作用域。SESSION：会话级缓存；STATEMENT：语句级缓存（可理解为关闭一级缓存）
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));

    // 当参数为 Null 时指定的 JDBC 类型。默认：OTHER
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));

    // 指定哪些方法调用会触发延迟加载。默认：equals, clone, hashCode, toString
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));

    // 允许使用 ResultHandler 时进行安全检查。默认：true
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));

    // 指定默认的动态 SQL 脚本语言驱动类
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));

    // 指定默认的枚举类型处理器类
    configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));

    // 当结果集列为空（Null）时，是否强制调用 Setter 方法填充。默认：false
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));

    // 允许使用方法签名的真实名称作为 SQL 参数名（需要 JDK8 及以上）。默认：true
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));

    // 当结果集所有列都为空时，是否返回一个空的对象实例。默认：false
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));

    // 设置 MyBatis 产生的日志信息前缀
    configuration.setLogPrefix(props.getProperty("logPrefix"));

    // 指定 Configuration 实例的工厂类
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
  }

  /**
   * 解析 <environments> 标签，配置数据源与事务管理器。
   *
   * MyBatis 支持配置多个环境（如 dev, test, prod），但每个 SqlSessionFactory
   * 实例只能对应一个具体的运行环境。
   *
   * @param context <environments> 节点对象
   */
  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {

      // 1. 【确定环境 ID】
      // 如果在 SqlSessionFactoryBuilder.build() 时未传入环境名，则读取 XML 中 default 属性指定的默认环境
      if (environment == null) {
        environment = context.getStringAttribute("default");
      }

      // 2. 遍历所有的 <environment> 子节点
      for (XNode child : context.getChildren()) {
        String id = child.getStringAttribute("id");

        // 3. 【环境匹配判定】
        // 只有当前节点的 id 与我们要激活的 environment 一致时，才执行解析逻辑。
        // 这种设计实现了不同环境（开发、生产）配置的物理隔离。
        if (isSpecifiedEnvironment(id)) {
          // 4. 解析 <transactionManager>：获取事务工厂实例
          // 常见类型：JDBC（使用 Connection 提交/回滚）、MANAGED（由容器如 Spring 管理事务）
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));

          // 5. 解析 <dataSource>：获取数据源工厂实例
          // 内部会根据 type 属性（如 POOLED, UNPOOLED）创建对应的 Factory（例如 DruidDataSourceFactory ）
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));

          // 6. 生产数据源对象：从工厂中获取真实的 DataSource 数据源实例（如 Druid, HikariCP 或 MyBatis 内置池）
          DataSource dataSource = dsFactory.getDataSource();

          // 7. 【对象构建】：使用建造者模式构建不可变的 Environment 对象
          // 该对象聚合了：环境唯一标识 ID、事务工厂、数据源
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);

          // 8. 【注册注册中心】：将环境信息存入全局 Configuration 对象
          // 之后 MyBatis 执行 SQL 时，将通过此环境获取数据库连接。
          configuration.setEnvironment(environmentBuilder.build());
        }
      }
    }
  }

  /**
   * 解析 <databaseIdProvider> 标签，确定当前运行环境的数据库厂商标识。
   *
   * 该功能允许开发者在 Mapper XML 中通过 databaseId 属性为不同的数据库编写特定的 SQL。
   *
   * @param context <databaseIdProvider> 节点对象
   */
  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      // 1. 获取解析器类型（通常配置为 "DB_VENDOR"）
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility

      // 2. 【兼容性处理】：将旧版的 "VENDOR" 修正为新版的 "DB_VENDOR"
      // 这是一段为了保持向后兼容的“硬编码补丁”
      if ("VENDOR".equals(type)) {
        type = "DB_VENDOR";
      }

      // 3. 解析标签下的子标签 <property>，获取数据库产品名与别名的映射关系
      // 例如：<property name="MySQL" value="mysql"/>
      Properties properties = context.getChildrenAsProperties();

      // 4. 【反射实例化】：创建具体的 DatabaseIdProvider 实例（默认是 VendorDatabaseIdProvider）
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).getDeclaredConstructor().newInstance();

      // 5. 将映射规则注入到实例中
      databaseIdProvider.setProperties(properties);
    }

    // 6. 【核心识别逻辑】：利用数据源识别当前数据库身份
    // 获取前面刚解析好的 Environment（环境信息）
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
      // 7. 真正通过 DataSource 获取数据库连接，并从 Connection 的 Metadata 中提取产品名称
      // 随后根据第 3 步定义的映射规则，返回对应的短名称（如 "mysql"）
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());

      // 8. 【全局存储】：将识别出的 databaseId 保存到 Configuration 对象中
      // 之后解析 Mapper XML 时，MyBatis 会优先匹配 databaseId 一致的 SQL 语句
      configuration.setDatabaseId(databaseId);
    }
  }

  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      TransactionFactory factory = (TransactionFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      // POOLED 和 UNPOOLED 默认的两类
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).getDeclaredConstructor().newInstance();
      // 设置 数据源的相关属性信息 url driverClassName userName password
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  /**
   * 解析 <typeHandlers> 标签，将自定义的类型处理器注册到 TypeHandlerRegistry 中。
   *
   * 类型处理器负责处理：
   * 1. PreparedStatement 设置参数时，将 Java 类型转换为 JDBC 类型。
   * 2. ResultSet 获取结果时，将 JDBC 类型转换为 Java 类型。
   */
  private void typeHandlerElement(XNode parent) {
    if (parent != null) {
      // 1. 遍历 <typeHandlers> 下的所有子标签（<package> 或 <typeHandler>）
      for (XNode child : parent.getChildren()) {
        // 2. 情况一：如果是 <package> 标签，执行自动包扫描
        if ("package".equals(child.getName())) {
          // 获取包名，例如：<package name="com.example.handlers"/>
          // 将某个包下的所有的 java 类 注册进去
          String typeHandlerPackage = child.getStringAttribute("name");
          // 调用注册表，扫描该包下所有标注了 @MappedTypes/@MappedJdbcTypes
          // 或继承了 BaseTypeHandler 的类，并自动注册。
          typeHandlerRegistry.register(typeHandlerPackage);

          // 3. 情况二：如果是具体的 <typeHandler> 标签，执行手动精准注册
        } else {
          // 获取 XML 配置的属性：Java类型、JDBC类型、处理器实现类
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");

          // 利用解析器将字符串别名或类名转换为真实的 Class 对象和枚举
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);

          // 4. 根据配置信息的完整度，采用不同的注册策略
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              // 策略 A：指定了 Java 类型和处理器，JDBC 类型通常通过 @MappedJdbcTypes 注解获取
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              // 策略 B：明确指定了 Java 类型、JDBC 类型以及对应的处理器
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            // 策略 C：仅指定了处理器实现类，自定义 类型处理器 的注册。
            // 此时解析器会尝试读取实现类上的 @MappedTypes 注解来确定它处理哪些 Java 类型
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  /**
   * 解析 <mappers> 标签，加载 SQL 映射文件或接口。
   *
   * MyBatis 支持四种配置方式来引入 Mapper，此方法根据标签属性自动选择加载策略。
   */
  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      // 1. 遍历 <mappers> 下的所有子节点
      for (XNode child : parent.getChildren()) {

        // 2. 情况一：如果是 <package> 标签，执行“包扫描”注册
        if ("package".equals(child.getName())) {
          // 获取包名，例如：<package name="com.example.mapper"/>
          String mapperPackage = child.getStringAttribute("name");

          // 扫描指定的包，并向MapperRegistry注册Mapper接口
          // 每一个类型 创建一个对应的 MapperProxyFactory 对象
          configuration.addMappers(mapperPackage);

          // 3. 情况二：如果是 <mapper> 标签，根据属性选择加载方式
        } else {
          // 获取属性：resource（相对路径）、url（绝对路径）、class（接口类）
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");

          // 3.1 方式 A：使用 resource (类路径相对路径 - 最常用)
          if (resource != null && url == null && mapperClass == null) {
            // resource	相对路径
            ErrorContext.instance().resource(resource);
            // 将 XML 文件转为输入流
            InputStream inputStream = Resources.getResourceAsStream(resource);
            // 创建 XMLMapperBuilder，专门用于解析具体的 Mapper.xml 文件
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
            // 【关键执行】：解析 XML 内部的 ResultMap、SQL 语句（MappedStatement）等
            mapperParser.parse();

            // 3.2 方式 B：使用 url (绝对路径，如 file:/// 或 http://)
          } else if (resource == null && url != null && mapperClass == null) {
            // url	绝对路径
            ErrorContext.instance().resource(url);
            InputStream inputStream = Resources.getUrlAsStream(url);
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
            mapperParser.parse();

            // 3.3 方式 C：使用 class (通过 Mapper 接口全类名注册)
          } else if (resource == null && url == null && mapperClass != null) {
            // 加载接口的 Class 对象
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            // 直接注册接口。MyBatis 会自动去寻找与该接口同名且同路径的 XML 文件
            configuration.addMapper(mapperInterface);
            // 3.4 约束校验：三者必须且只能存在一个
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  /**
   * 判断是否指定了 Environment 对象
   * @param id
   * @return
   */
  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    } else if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    } else if (environment.equals(id)) {
      return true;
    }
    return false;
  }

}
