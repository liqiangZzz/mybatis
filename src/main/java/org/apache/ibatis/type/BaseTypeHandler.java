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

import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.session.Configuration;

/**
 * [类型处理器基类] 采用模板方法模式，统一处理 JDBC 交互中的 Null 值判定与异常包装。
 * <p>
 * 开发者在自定义类型处理器时，应继承此类并实现其四个抽象方法，从而避开繁琐的非业务逻辑。
 *
 * @param <T> 处理的目标 Java 类型
 * <p>
 * The base {@link TypeHandler} for references a generic type.
 * <p>
 * Important: Since 3.5.0, This class never call the {@link ResultSet#wasNull()} and
 * {@link CallableStatement#wasNull()} method for handling the SQL {@code NULL} value.
 * In other words, {@code null} value handling should be performed on subclass.
 * </p>
 *
 * @author Clinton Begin
 * @author Simone Tripodi
 * @author Kzuki Shimizu
 */
public abstract class BaseTypeHandler<T> extends TypeReference<T> implements TypeHandler<T> {

  /**
   * @deprecated Since 3.5.0 - See https://github.com/mybatis/mybatis-3/issues/1203. This field will remove future.
   */
  @Deprecated
  protected Configuration configuration;

  /**
   * @deprecated Since 3.5.0 - See https://github.com/mybatis/mybatis-3/issues/1203. This property will remove future.
   */
  @Deprecated
  public void setConfiguration(Configuration c) {
    this.configuration = c;
  }

  /**
   * [写操作：Java -> JDBC]
   * 负责将 Java 类型的参数设置到 SQL 预编译语句中。
   *
   * @param ps        [PreparedStatement] JDBC 预编译对象
   * @param i         [int] 参数在 SQL 语句中的索引位置（从 1 开始）
   * @param parameter [T] 传入的实参对象。若为 null，则由基类处理 SQL NULL
   * @param jdbcType  [JdbcType] 在 XML 中定义的 JDBC 类型（如 VARCHAR, INTEGER）
   */
  @Override
  public void setParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException {
    if (parameter == null) {
      // 1. 如果参数为空，且没有指定相应的 JDBC 类型，则无法正确执行 setNull 操作，抛出异常
      if (jdbcType == null) {
        throw new TypeException("JDBC requires that the JdbcType must be specified for all nullable parameters.");
      }
      try {
        // 2. 调用 JDBC 标准 API，向数据库写入 SQL NULL
        ps.setNull(i, jdbcType.TYPE_CODE);
      } catch (SQLException e) {
        throw new TypeException("Error setting null for parameter #" + i + " with JdbcType " + jdbcType + " . "
              + "Try setting a different JdbcType for this parameter or a different jdbcTypeForNull configuration property. "
              + "Cause: " + e, e);
      }
    } else {
      try {
        // 3. 参数非空：委派给具体的子类实现（如 ps.setInt 或 ps.setString）
        setNonNullParameter(ps, i, parameter, jdbcType);
      } catch (Exception e) {
        throw new TypeException("Error setting non null for parameter #" + i + " with JdbcType " + jdbcType + " . "
              + "Try setting a different JdbcType for this parameter or a different configuration property. "
              + "Cause: " + e, e);
      }
    }
  }

  /**
   * [读操作：JDBC -> Java]
   * 包装了通过【列名】获取结果集的逻辑，将底层 SQLException 转换为 MyBatis 映射异常。
   *
   * @param rs         [ResultSet] JDBC 结果集对象
   * @param columnName [String] 数据库列名或 SQL 别名
   */
  @Override
  public T getResult(ResultSet rs, String columnName) throws SQLException {
    try {
      // 委派给子类实现的具体的取值逻辑
      return getNullableResult(rs, columnName);
    } catch (Exception e) {
      throw new ResultMapException("Error attempting to get column '" + columnName + "' from result set.  Cause: " + e, e);
    }
  }

  /**
   * [读操作：JDBC -> Java]
   * 包装了通过【列索引】获取结果集的逻辑。
   *
   * @param rs          [ResultSet] JDBC 结果集对象
   * @param columnIndex [int] 列的物理下标
   */
  @Override
  public T getResult(ResultSet rs, int columnIndex) throws SQLException {
    try {
      return getNullableResult(rs, columnIndex);
    } catch (Exception e) {
      throw new ResultMapException("Error attempting to get column #" + columnIndex + " from result set.  Cause: " + e, e);
    }
  }


  /**
   * [读操作：JDBC -> Java]
   * 包装了从【存储过程】中提取输出参数的逻辑。
   *
   * @param cs          [CallableStatement] JDBC 存储过程执行对象
   * @param columnIndex [int] 输出参数的物理下标
   */
  @Override
  public T getResult(CallableStatement cs, int columnIndex) throws SQLException {
    try {
      return getNullableResult(cs, columnIndex);
    } catch (Exception e) {
      throw new ResultMapException("Error attempting to get column #" + columnIndex + " from callable statement.  Cause: " + e, e);
    }
  }

  // --- 抽象方法：由具体子类实现纯粹的业务转换逻辑 ---

  /**
   * 抽象方法：处理非空的 Java 参数设置。
   */
  public abstract void setNonNullParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException;

  /**
   * 抽象方法：通过列名获取结果，子类需自行处理可能的 SQL NULL 值。
   * @param columnName Colunm name, when configuration <code>useColumnLabel</code> is <code>false</code>
   */
  public abstract T getNullableResult(ResultSet rs, String columnName) throws SQLException;

  /**
   * 抽象方法：通过列索引获取结果。
   */
  public abstract T getNullableResult(ResultSet rs, int columnIndex) throws SQLException;

  /**
   * 抽象方法：处理存储过程的输出结果。
   */
  public abstract T getNullableResult(CallableStatement cs, int columnIndex) throws SQLException;

}
