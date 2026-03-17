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
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * @author Clinton Begin
 * 实际上是用来选择具体的 StatementHandler 类型的
 * 持有了具体的 StatementHandler 的委托
 * 调用具体 StatementHandler 对象的方法
 */
public class RoutingStatementHandler implements StatementHandler {

  // 封装的有真正的 StatementHandler 对象
  private final StatementHandler delegate;


  /**
   * [策略分发中枢] 根据配置的 Statement 类型选择具体的处理器实现。
   *
   * RoutingStatementHandler 本身不执行任何实质性的物理操作，
   * 它通过内部持有的 delegate（委派对象）来实现具体的 JDBC 交互逻辑。
   */
  public RoutingStatementHandler(Executor executor, MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
    // 1. 【策略决策机制】：
    // 从 MappedStatement 获取指定的 Statement 类型（来源于 XML 标签中的 statementType 属性）。
    // STATEMENT：使用 Statement，不进行预编译。
    // PREPARED：使用 PreparedStatement，进行预编译。
    // CALLABLE：使用 CallableStatement，用于调用存储过程。
    // MyBatis 默认使用 PREPARED 类型。
    switch (ms.getStatementType()) {

      // 场景 A：对应 JDBC 的原始 java.sql.Statement
      // 逻辑：直接拼接 SQL 文本，不进行预编译处理，通常用于非参数化的简单查询。
      case STATEMENT:
        delegate = new SimpleStatementHandler(executor, ms, parameter, rowBounds, resultHandler, boundSql);
        break;

      // 场景 B：对应 JDBC 的 java.sql.PreparedStatement（最常用）
      // 逻辑：支持带 ? 占位符的 SQL 预编译，提供更高的安全性和执行性能。
      case PREPARED:
        delegate = new PreparedStatementHandler(executor, ms, parameter, rowBounds, resultHandler, boundSql);
        break;

      // 场景 C：对应 JDBC 的 java.sql.CallableStatement
      // 逻辑：专门用于处理数据库存储过程的调用。
      case CALLABLE:
        delegate = new CallableStatementHandler(executor, ms, parameter, rowBounds, resultHandler, boundSql);
        break;
      default:
        throw new ExecutorException("Unknown statement type: " + ms.getStatementType());
    }

  }

  @Override
  public Statement prepare(Connection connection, Integer transactionTimeout) throws SQLException {
    return delegate.prepare(connection, transactionTimeout);
  }

  @Override
  public void parameterize(Statement statement) throws SQLException {
    delegate.parameterize(statement);
  }

  @Override
  public void batch(Statement statement) throws SQLException {
    delegate.batch(statement);
  }

  @Override
  public int update(Statement statement) throws SQLException {
    return delegate.update(statement);
  }

  @Override
  public <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException {
    return delegate.query(statement, resultHandler);
  }

  @Override
  public <E> Cursor<E> queryCursor(Statement statement) throws SQLException {
    return delegate.queryCursor(statement);
  }

  @Override
  public BoundSql getBoundSql() {
    return delegate.getBoundSql();
  }

  @Override
  public ParameterHandler getParameterHandler() {
    return delegate.getParameterHandler();
  }
}
