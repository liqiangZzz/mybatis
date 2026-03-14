/**
 *    Copyright 2009-2021 the original author or authors.
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * @author Clinton Begin
 */
public class SimpleStatementHandler extends BaseStatementHandler {

  public SimpleStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
    // 通过父类 BaseStatementHandler 的构造函数创建
    super(executor, mappedStatement, parameter, rowBounds, resultHandler, boundSql);
  }

  /**
   * [物理更新执行] 执行写操作 SQL 并根据策略处理主键生成。
   *
   * 此方法涵盖了物理执行、结果计数获取以及主键自动回填三个核心步骤。
   */
  @Override
  public int update(Statement statement) throws SQLException {

    // 1. 【物料提取】：从执行上下文中获取物理 SQL 文本与实参对象
    String sql = boundSql.getSql();
    Object parameterObject = boundSql.getParameterObject();

    // 2. 【策略识别】：获取当前 SQL 标签配置的主键生成器
    KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
    int rows;
    // 3. 【分支决策】：根据主键生成策略执行不同的 JDBC 动作
    if (keyGenerator instanceof Jdbc3KeyGenerator) {

      /*
       * 场景 A：基于 JDBC 规范的自增主键（如 MySQL 的 AUTO_INCREMENT）
       * 逻辑：执行时显式告知驱动程序需要返回自动生成的键值。
       */
      statement.execute(sql, Statement.RETURN_GENERATED_KEYS);
      // 获取受影响的行数
      rows = statement.getUpdateCount();
      // 执行后置处理：从驱动中提取主键并注入到 parameterObject 的对应属性中
      keyGenerator.processAfter(executor, mappedStatement, statement, parameterObject);
    } else if (keyGenerator instanceof SelectKeyGenerator) {

      /*
       * 场景 B：基于特定的 SQL 查询获取主键（如 Oracle 的 Sequence）
       * 逻辑：执行标准的更新动作，随后由后置任务执行主键查询。
       */
      statement.execute(sql);
      rows = statement.getUpdateCount();
      // 执行后置处理：运行 <selectKey> 标签中定义的 SQL 并回填属性
      keyGenerator.processAfter(executor, mappedStatement, statement, parameterObject);
    } else {
      /*
       * 场景 C：无主键生成策略
       * 逻辑：直接执行 SQL 并记录受影响行数。
       */
      statement.execute(sql);
      rows = statement.getUpdateCount();
    }
    // 返回数据库操作影响的物理记录行数
    return rows;
  }

  @Override
  public void batch(Statement statement) throws SQLException {
    String sql = boundSql.getSql();
    statement.addBatch(sql);
  }

  @Override
  public <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException {
    String sql = boundSql.getSql();
    statement.execute(sql);
    return resultSetHandler.handleResultSets(statement);
  }

  @Override
  public <E> Cursor<E> queryCursor(Statement statement) throws SQLException {
    String sql = boundSql.getSql();
    statement.execute(sql);
    return resultSetHandler.handleCursorResultSets(statement);
  }

  @Override
  protected Statement instantiateStatement(Connection connection) throws SQLException {
    if (mappedStatement.getResultSetType() == ResultSetType.DEFAULT) {
      return connection.createStatement();
    } else {
      return connection.createStatement(mappedStatement.getResultSetType().getValue(), ResultSet.CONCUR_READ_ONLY);
    }
  }

  /**
   * select * from t_user where id = 1
   * @param statement
   */
  @Override
  public void parameterize(Statement statement) {
    // N/A
  }

}
