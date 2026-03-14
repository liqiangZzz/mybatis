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
package org.apache.ibatis.executor;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * @author Clinton Begin
 */
public class SimpleExecutor extends BaseExecutor {

  public SimpleExecutor(Configuration configuration, Transaction transaction) {
    super(configuration, transaction);
  }

  /**
   * [物理更新执行] 在 SimpleExecutor 中实现具体的 JDBC 写操作逻辑。
   *
   * 此方法涵盖了从语句处理器创建到 JDBC 资源关闭的完整生命周期。
   */
  @Override
  public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
    Statement stmt = null;
    try {
      // 1. 【组件实例化】：创建 StatementHandler 处理器。
      // 通过 configuration 工厂方法创建，会自动植入已配置的插件（拦截器）。
      // 注意：写操作通常不需要 RowBounds 分页和 ResultHandler 结果处理器。
      Configuration configuration = ms.getConfiguration();

      // 2. 【语句预备阶段】：获取连接并初始化 JDBC Statement。
      // 内部流程：
      //   a. 获取数据库物理连接。
      //   b. 物理实例化 Statement (如 PreparedStatement)。
      //   c. 触发 ParameterHandler 进行参数绑定（填充 ? 占位符）。
      StatementHandler handler = configuration.newStatementHandler(this, ms, parameter, RowBounds.DEFAULT, null, null);

      // 3. 【物理执行】：执行 JDBC 更新动作。
      // 委派给 StatementHandler 执行具体的 executeUpdate()，并返回受影响的行数。
      stmt = prepareStatement(handler, ms.getStatementLog());
      return handler.update(stmt);
    } finally {
      // 4. 【资源释放】：无论成功与否，必须物理关闭 Statement。
      // 理由：防止数据库游标和连接资源泄露。
      closeStatement(stmt);
    }
  }

  /**
   * [物理执行逻辑] 在 SimpleExecutor 中实现具体的数据库查询动作。
   * 此方法负责协调 Statement 的创建、参数化绑定以及结果集的最终提取。
   * @param ms MappedStatement  映射语句
   * @param parameter 参数
   * @param rowBounds 分页
   * @param resultHandler 处理结果
   * @param boundSql SQL语句
   * @param <E> 结果类型
   * @return 查询结果
   * @throws SQLException  SQL异常
   */
  @Override
  public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
    Statement stmt = null;
    try {
      Configuration configuration = ms.getConfiguration();

      // 1. 【实例化处理器】：创建 StatementHandler 实例。
      // StatementHandler 是 MyBatis 处理 JDBC 交互的核心组件。
      // 此处通过 configuration 对象创建，会自动应用所有配置好的拦截器（插件）。
      StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);

      // 2. 【初始化语句对象】：准备并设置 JDBC Statement。
      // 该方法内部流程：
      //   a. 获取数据库连接 (Connection)
      //   b. 物理实例化 Statement (或 PreparedStatement/CallableStatement)
      //   c. 执行参数绑定 (ParameterHandler 处理 ? 占位符)
      stmt = prepareStatement(handler, ms.getStatementLog());

      // 3. 【物理查询与映射】：执行 SQL 指令并处理结果集。
      // 调用底层 JDBC 驱动执行查询，并委派 ResultSetHandler 将行数据转化为 Java 对象（List）。
      return handler.query(stmt, resultHandler);
    } finally {
      // 4. 【资源释放】：确保 JDBC Statement 在执行完成后物理关闭，防止数据库游标泄露。
      closeStatement(stmt);
    }
  }

  @Override
  protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
    Configuration configuration = ms.getConfiguration();
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    Cursor<E> cursor = handler.queryCursor(stmt);
    stmt.closeOnCompletion();
    // Connection.close();
    return cursor;
  }

  @Override
  public List<BatchResult> doFlushStatements(boolean isRollback) {
    return Collections.emptyList();
  }

  /**
   * [语句初始化与参数化] 负责从连接获取到参数绑定的完整 JDBC 预备流程。
   *
   * 此方法遵循标准 JDBC 生命周期：
   * 1. 获取连接 (Connection)
   * 2. 预编译语句 (Prepare Statement)
   * 3. 填充参数 (Parameterize)
   */
  private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
    Statement stmt;

    /*
     * 1. 【物理连接获取】：
     * 逻辑：通过事务管理器获取底层的数据库连接。
     * 增强：若启用了日志打印，getConnection 会返回一个【ConnectionLogger 代理对象】。
     * 意义：任何在该连接上创建的 Statement 都会被自动织入日志监控能力。
     */
    Connection connection = getConnection(statementLog);

    /*
     * 2. 【语句物理实例化】：
     * 逻辑：调用 StatementHandler 的 prepare 方法创建具体的 JDBC Statement 实例。
     * 实现：根据配置决定是创建 PreparedStatement 还是普通的 Statement。
     * 代理：若第一步返回的是代理连接，此处生成的 stmt 也会是【PreparedStatementLogger 代理对象】。
     */
    stmt = handler.prepare(connection, transaction.getTimeout());

    /*
     * 3. 【参数化绑定】：
     * 职能：委派给四大对象中的 ParameterHandler 执行。
     * 逻辑：遍历 BoundSql 中的参数映射，利用 TypeHandler 将 Java 对象安全地注入到 SQL 的 ? 占位符中。
     * 意义：这是防止 SQL 注入并实现类型自动转换的关键环节。
     */
    handler.parameterize(stmt);
    return stmt;
  }

}
