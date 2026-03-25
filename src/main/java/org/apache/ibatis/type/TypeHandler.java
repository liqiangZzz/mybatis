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
package org.apache.ibatis.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * [类型处理器接口] 负责 Java 类型与 JDBC 类型之间的双向转换。
 * <p>
 * 核心职能：
 * 1. 入参绑定：将 Java 对象的值安全地填充到 PreparedStatement 的 SQL 占位符（?）中。
 * 2. 结果映射：将 JDBC 返回的 ResultSet 或存储过程结果转换为对应的 Java 对象。
 *
 * @param <T> 处理的目标 Java 类型（如 String, Integer, UserStatusEnum 等）
 *
 * @author Clinton Begin
 */
public interface TypeHandler<T> {

  /**
   * [写操作：Java -> JDBC]
   * 负责将 Java 类型的参数转换为数据库需要的 JDBC 类型。
   * <p>
   * 底层对应 JDBC 的操作：
   * ps.setInt(index, value);
   * ps.setString(index, value);
   *
   * @param ps        当前的 PreparedStatement 对象
   * @param i         SQL 语句中对应的“?”占位符索引位置
   * @param parameter 开发者传入的 Java 实参对象
   * @param jdbcType  在 XML 或注解中指定的 JDBC 类型（可选）
   */
  void setParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException;

  /**
   * [读操作：JDBC -> Java]
   * 从结果集中根据【列名】获取数据，并将其由 JDBC 类型转换为 Java 类型。
   * <p>
   * 底层对应 JDBC 的操作：
   * String val = rs.getString("column_name");
   *
   * @param rs         当前的 JDBC 结果集对象
   * @param columnName 数据库中的列名（或 SQL 别名）
   * @return 转换后的 Java 对象
   */
  T getResult(ResultSet rs, String columnName) throws SQLException;


  /**
   * [读操作：JDBC -> Java]
   * 从结果集中根据【列索引】获取数据（通常用于提升访问性能或处理无名列）。
   *
   * @param rs          当前的 JDBC 结果集对象
   * @param columnIndex 列的物理下标
   */
  T getResult(ResultSet rs, int columnIndex) throws SQLException;


  /**
   * [读操作：JDBC -> Java]
   * 专门用于处理【存储过程】输出参数的转换。
   *
   * @param cs          当前的 CallableStatement 对象
   * @param columnIndex 输出参数的物理下标
   */
  T getResult(CallableStatement cs, int columnIndex) throws SQLException;

}
