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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * @author Clinton Begin
 */
public class PreparedStatementHandler extends BaseStatementHandler {

  public PreparedStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
    super(executor, mappedStatement, parameter, rowBounds, resultHandler, boundSql);
  }

  @Override
  public int update(Statement statement) throws SQLException {
    PreparedStatement ps = (PreparedStatement) statement;
    ps.execute();
    int rows = ps.getUpdateCount();
    Object parameterObject = boundSql.getParameterObject();
    KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
    keyGenerator.processAfter(executor, mappedStatement, ps, parameterObject);
    return rows;
  }

  @Override
  public void batch(Statement statement) throws SQLException {
    PreparedStatement ps = (PreparedStatement) statement;
    ps.addBatch();
  }


  /**
   * [JDBC 物理执行] 调用驱动程序执行 SQL，并触发结果集处理流程。
   */
  @Override
  public <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException {

    // 1. 类型强制转换：将通用的 Statement 转换为具备预编译能力的 PreparedStatement
    // 如果放开日志 PreparedStatement 其实是一个 PreparedStatementLogger 的代理对象
    PreparedStatement ps = (PreparedStatement) statement;

    // 2. 【物理执行阶段】：调用 JDBC 驱动执行 SQL。
    // 此时控制权移交给 JDBC 驱动程序，执行数据库 IO 操作。
    // 如果是代理对象的话 同样的会进入到 invoke 方法中
    ps.execute();

    // 3. 【处理结果集】：委派给四大对象中的 ResultSetHandler。
    // 负责将 JDBC 返回的原始数据流（ResultSet）解析并转化为 Java 结果对象（List/Map）。
    // 若 ResultSetHandler 被插件包装，此处会先进入拦截器逻辑。
    return resultSetHandler.handleResultSets(ps);
  }

  @Override
  public <E> Cursor<E> queryCursor(Statement statement) throws SQLException {
    PreparedStatement ps = (PreparedStatement) statement;
    ps.execute();
    return resultSetHandler.handleCursorResultSets(ps);
  }

  /**
   * [物理语句实例化] 委派 JDBC 连接创建具体的 PreparedStatement 实例。
   * <p>
   * 此方法根据 MappedStatement 的配置（如主键生成策略、结果集类型），
   * 选择最合适的 JDBC 构造方式。
   */
  @Override
  protected Statement instantiateStatement(Connection connection) throws SQLException {
    // 1. 获取经过动态解析后的最终 SQL 文本（带 ? 占位符）
    String sql = boundSql.getSql();

    // 2. 【分支 A】：处理自动生成主键的场景 (基于 JDBC3 标准)
    // 当配置了 useGeneratedKeys="true" 时触发。
    if (mappedStatement.getKeyGenerator() instanceof Jdbc3KeyGenerator) {
      String[] keyColumnNames = mappedStatement.getKeyColumns();

      /*
       * 逻辑：
       * - 若未指定主键列名：使用 RETURN_GENERATED_KEYS 常量，要求驱动自动返回生成的主键。
       * - 若指定了主键列名：通过列名数组要求驱动返回指定字段的值。
       *
       * 注意：此处调用的 connection.prepareStatement 动作会被 ConnectionLogger 拦截，
       * 从而触发日志打印并返回一个 PreparedStatementLogger 代理对象。
       */
      if (keyColumnNames == null) {
        // connection 是 日志的代理对象
        return connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS);
      } else {
        // 在执行 prepareStatement 方法的时候会进入进入到ConnectionLogger的invoker方法中
        return connection.prepareStatement(sql, keyColumnNames);
      }

      // 3. 【分支 B】：处理默认结果集类型的场景 (最常见)
      // 采用标准预编译模式，不开启主键回填。
    } else if (mappedStatement.getResultSetType() == ResultSetType.DEFAULT) {
      return connection.prepareStatement(sql);
    } else {

      // 4. 【分支 C】：处理自定义结果集行为的场景
      // 如：需要配置结果集的可滚动性 (Scrollable) 或并发控制 (ReadOnly)。
      return connection.prepareStatement(sql, mappedStatement.getResultSetType().getValue(), ResultSet.CONCUR_READ_ONLY);
    }
  }

  @Override
  public void parameterize(Statement statement) throws SQLException {
    parameterHandler.setParameters((PreparedStatement) statement);
  }

}
