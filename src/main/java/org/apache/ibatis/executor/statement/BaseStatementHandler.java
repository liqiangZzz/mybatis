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
package org.apache.ibatis.executor.statement;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 */
public abstract class BaseStatementHandler implements StatementHandler {

  protected final Configuration configuration;
  protected final ObjectFactory objectFactory;
  protected final TypeHandlerRegistry typeHandlerRegistry;
  protected final ResultSetHandler resultSetHandler;
  protected final ParameterHandler parameterHandler;

  protected final Executor executor;
  protected final MappedStatement mappedStatement;
  protected final RowBounds rowBounds;

  protected BoundSql boundSql;

  /**
   * [基础语句处理器构造] 初始化 JDBC 执行环境并组装关联组件。
   *
   * BaseStatementHandler 负责协调 MyBatis 执行链条中的“四大对象”：
   * 1. Executor (在此之前已创建)
   * 2. StatementHandler (当前对象)
   * 3. ParameterHandler (此处创建)
   * 4. ResultSetHandler (此处创建)
   */
  protected BaseStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {

    // 1. 【引用赋值】：存储全局配置、执行器引用及 SQL 映射元数据
    this.configuration = mappedStatement.getConfiguration();
    this.executor = executor;
    this.mappedStatement = mappedStatement;
    this.rowBounds = rowBounds;

    // 2. 【工具准备】：从全局配置中提取类型处理器注册表及对象实例化工厂
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.objectFactory = configuration.getObjectFactory();

    // 3. 【SQL 指令判定】：确保 BoundSql（物理 SQL 对象）已就绪
    // 若 boundSql 为空（通常发生在处理存储过程或特定主键生成场景时），
    // 则先执行主键生成逻辑，再通过解析引擎动态生成 BoundSql。
    if (boundSql == null) { // issue #435, get the key before calculating the statement
      generateKeys(parameterObject);
      boundSql = mappedStatement.getBoundSql(parameterObject);
    }

    this.boundSql = boundSql;

    /*
     * 4. 【核心组件组装】：通过 Configuration 工厂方法创建另外两大核心对象。
     *
     * 注意：这两个 new 方法内部不仅是简单的实例化，还会触发拦截器链（InterceptorChain），
     * 将插件（Plugin）包装在这些对象之上。
     */

    // 创建参数处理器：负责将 Java 实参映射到 JDBC PreparedStatement 的占位符（?）上。
    this.parameterHandler = configuration.newParameterHandler(mappedStatement, parameterObject, boundSql);

    // 创建结果集处理器：负责将 JDBC 返回的 ResultSet 行记录转换为 Java 结果对象（POJO/Map）。
    this.resultSetHandler = configuration.newResultSetHandler(executor, mappedStatement, rowBounds, parameterHandler, resultHandler, boundSql);
  }

  @Override
  public BoundSql getBoundSql() {
    return boundSql;
  }

  @Override
  public ParameterHandler getParameterHandler() {
    return parameterHandler;
  }

  /**
   * [语句预备] 负责 JDBC Statement 的实例化及基础属性配置。
   * <p>
   * 此方法遵循标准 JDBC 生命周期中的“创建与配置”阶段：
   * 1. 记录诊断信息。
   * 2. 物理实例化 Statement 对象。
   * 3. 注入超时时间与抓取大小等元数据。
   */
  @Override
  public Statement prepare(Connection connection, Integer transactionTimeout) throws SQLException {
    // 1. 【诊断记录】：将物理 SQL 文本注入 ErrorContext（ThreadLocal）
    // 目的：若接下来的实例化或配置过程报错，MyBatis 能准确报告出错的 SQL。
    ErrorContext.instance().sql(boundSql.getSql());
    Statement statement = null;
    try {
      /*
       * 2. 【具体实例化】：模板方法模式的应用。
       * 逻辑：调用抽象方法 instantiateStatement，由具体的子类实现。
       * - SimpleStatementHandler -> 创建普通的 java.sql.Statement
       * - PreparedStatementHandler -> 创建 java.sql.PreparedStatement
       *
       * 注意：若当前 connection 是由 ConnectionLogger 产生的代理对象，
       * 则此处返回的 statement 也会是一个带日志功能的代理对象。
       */
      statement = instantiateStatement(connection);

      // 3. 【执行元数据配置 A】：设置语句执行的超时时间
      // 优先级：由当前事务状态与 MappedStatement 共同决定
      setStatementTimeout(statement, transactionTimeout);

      // 4. 【执行元数据配置 B】：设置抓取大小 (FetchSize)
      // 作用：优化数据库网络交互频率，避免大数据量查询时频繁触发网络 IO
      setFetchSize(statement);
      return statement;
    } catch (SQLException e) {
      // 5. 【异常兜底】：物理释放资源，防止由于配置失败导致的 Statement 泄露
      closeStatement(statement);
      throw e;
    } catch (Exception e) {
      closeStatement(statement);
      throw new ExecutorException("Error preparing statement.  Cause: " + e, e);
    }
  }

  protected abstract Statement instantiateStatement(Connection connection) throws SQLException;

  protected void setStatementTimeout(Statement stmt, Integer transactionTimeout) throws SQLException {
    Integer queryTimeout = null;
    if (mappedStatement.getTimeout() != null) {
      queryTimeout = mappedStatement.getTimeout();
    } else if (configuration.getDefaultStatementTimeout() != null) {
      queryTimeout = configuration.getDefaultStatementTimeout();
    }
    if (queryTimeout != null) {
      stmt.setQueryTimeout(queryTimeout);
    }
    StatementUtil.applyTransactionTimeout(stmt, queryTimeout, transactionTimeout);
  }

  protected void setFetchSize(Statement stmt) throws SQLException {
    Integer fetchSize = mappedStatement.getFetchSize();
    if (fetchSize != null) {
      stmt.setFetchSize(fetchSize);
      return;
    }
    Integer defaultFetchSize = configuration.getDefaultFetchSize();
    if (defaultFetchSize != null) {
      stmt.setFetchSize(defaultFetchSize);
    }
  }

  protected void closeStatement(Statement statement) {
    try {
      if (statement != null) {
        statement.close();
      }
    } catch (SQLException e) {
      //ignore
    }
  }

  protected void generateKeys(Object parameter) {
    KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
    ErrorContext.instance().store();
    keyGenerator.processBefore(executor, mappedStatement, null, parameter);
    ErrorContext.instance().recall();
  }

}
