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
package org.apache.ibatis.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

import org.apache.ibatis.builder.xml.XMLConfigBuilder;
import org.apache.ibatis.exceptions.ExceptionFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;

/**
 * Builds {@link SqlSession} instances.
 *   通过建造者模式创建 SqlSessionFactory 对象
 * @author Clinton Begin
 */
public class SqlSessionFactoryBuilder {

  public SqlSessionFactory build(Reader reader) {
    return build(reader, null, null);
  }

  public SqlSessionFactory build(Reader reader, String environment) {
    return build(reader, environment, null);
  }

  public SqlSessionFactory build(Reader reader, Properties properties) {
    return build(reader, null, properties);
  }

  public SqlSessionFactory build(Reader reader, String environment, Properties properties) {
    try {
      // 读取配置文件
      XMLConfigBuilder parser = new XMLConfigBuilder(reader, environment, properties);
      // 解析配置文件得到Configuration对象 创建DefaultSqlSessionFactory对象
      return build(parser.parse());
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error building SqlSession.", e);
    } finally {
      ErrorContext.instance().reset();
      try {
        reader.close();
      } catch (IOException e) {
        // Intentionally ignore. Prefer previous error.
      }
    }
  }

  public SqlSessionFactory build(InputStream inputStream) {
    return build(inputStream, null, null);
  }

  public SqlSessionFactory build(InputStream inputStream, String environment) {
    return build(inputStream, environment, null);
  }

  public SqlSessionFactory build(InputStream inputStream, Properties properties) {
    return build(inputStream, null, properties);
  }

  /**
   * 【MyBatis 启动核心入口】解析配置文件并构建 SqlSessionFactory。
   *
   * <p>该方法负责协调配置解析器与全局仓库，其严谨的执行链路如下：
   * <ol>
   *   <li><b>初始化解析环境：</b> 实例化 {@link XMLConfigBuilder}，同步触发核心配置类 {@link Configuration} 的预创建。</li>
   *   <li><b>全量元数据解析：</b> 执行 {@code parser.parse()}。该过程以 mybatis-config.xml 为根节点进行线性解析，
   *       并根据其中的 {@code <mappers>} 标签内容，<b>级联触发</b> 对所有关联 Mapper 映射文件的加载与解析。</li>
   *   <li><b>对象模型转化：</b> 将 XML 文本定义的 settings、typeAliases、plugins、以及 SQL 片段（MappedStatement）
   *       等信息，全部转化为内存中的 Java 对象，并统一填充至 Configuration 容器中。</li>
   *   <li><b>工厂实例装配：</b> 将承载了完整系统蓝图的 Configuration 对象交付给 {@link DefaultSqlSessionFactory} 进行最终封装。</li>
   * </ol>
   *
   * @param inputStream 全局配置文件（mybatis-config.xml）的字节输入流。
   * @param environment 指定的环境 ID（对应 XML 中的 {@code <environment id="...">}）。若为 null，则使用配置中的 default 环境。
   * @param properties  外部传入的属性变量。该属性具有<b>最高优先级</b>，加载时将覆盖 XML 内部定义的同名属性（${key}）。
   * @return 返回构建完毕且线程安全的 {@link SqlSessionFactory} 实例。
   */
  public SqlSessionFactory build(InputStream inputStream, String environment, Properties properties) {
    try {

      // 1. 【构建解析上下文】用于解析 mybatis-config.xml。
      // 创建 XMLConfigBuilder 实例。在此步骤中，MyBatis 已经在内存中创建了一个单例的 Configuration 对象（完成了很多的初始化操作）。
      // Configuration 对象是整个 MyBatis 运行期的全局配置中心，所有的配置信息最终都将汇聚于此。
      XMLConfigBuilder parser = new XMLConfigBuilder(inputStream, environment, properties);

      // 2. 【执行级联解析逻辑】
      // 调用解析器的核心入口。此操作会执行以下动作：
      //   - 线性解析：按 DTD 顺序读取全局配置（settings、plugins、environments 等）。
      //   - 关联加载：解析到 <mappers> 时，系统会跳转并读取外部所有的映射文件（Mapper.xml）。
      //   - 数据汇聚：将散落在多份 XML 里的元数据（SQL 语句、ResultMap 等）统一转化为 MappedStatement 等对象，
      //     并注入到 parser 持有的 Configuration 对象中。
      // 3. 【组装并产出工厂对象】
      // 此时 Configuration 已被填充完毕，基于这份完整的配置信息，实例化默认的工厂实现类。
      return build(parser.parse());
    } catch (Exception e) {
      // 4. 【异常体系包装】
      // 将底层产生的 XML 语法错误、IO 异常或类加载异常，统一包装为 MyBatis 体系的 PersistenceException。
      throw ExceptionFactory.wrapException("Error building SqlSession.", e);
    } finally {
      // 5. 【诊断上下文重置与资源释放】
      // ErrorContext 利用 ThreadLocal 记录解析过程中的错误细节。
      // 在构建结束（无论成功或失败）后必须重置，以防止在线程池复用环境下造成错误信息的跨线程污染。
      ErrorContext.instance().reset();

      // 物理释放：必须确保输入流关闭，防止文件句柄（File Descriptor）在操作系统层面泄露。
      try {
        inputStream.close();
      } catch (IOException e) {
        // 此处异常通常选择忽略，因为解析器主流程的异常对开发者而言更具诊断价值。
        // Intentionally ignore. Prefer previous error.
      }
    }
  }

  /**
   * 【MyBatis 启动核心入口】根据解析后的 Configuration 对象，构建 SqlSessionFactory。
   * @param config 解析后的 Configuration 对象。
   * @return 返回构建完毕且线程安全的 {@link SqlSessionFactory} 实例。
   */
  public SqlSessionFactory build(Configuration config) {
    // SessionFactory 的使命就是持有一份 Configuration，以便后续生产 Session
    return new DefaultSqlSessionFactory(config);
  }

}
