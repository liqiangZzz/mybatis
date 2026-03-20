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
package org.apache.ibatis.logging.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.reflection.ExceptionUtil;

/**
 * [预编译语句代理实现] 负责拦截 PreparedStatement 和 CallableStatement 的方法调用。
 * <p>
 * 核心职能：
 * 1. 记录参数：拦截所有的 setXxx 方法，将 SQL 占位符对应的实参值缓存到基类中。
 * 2. 打印参数日志：在 SQL 执行前，输出格式化后的参数列表（如 Parameters: 1(Integer)）。
 * 3. 监控执行结果：打印受影响的行数（Updates）。
 * 4. 传递代理链：将返回的 ResultSet 包装为 ResultSetLogger 代理，使监控渗透到数据读取阶段。
 * <p>
 * PreparedStatement proxy to add logging.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 *
 */
public final class PreparedStatementLogger extends BaseJdbcLogger implements InvocationHandler {

  private final PreparedStatement statement;

  private PreparedStatementLogger(PreparedStatement stmt, Log statementLog, int queryStack) {
    super(statementLog, queryStack);
    this.statement = stmt;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] params) throws Throwable {
    try {
      // 1. 【基础方法过滤】：若是 Object 自带方法，直接执行
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, params);
      }

      /*
       * 2. 【核心拦截点 A】：拦截执行方法（execute, executeQuery, executeUpdate 等）
       */
      if (EXECUTE_METHODS.contains(method.getName())) {
        if (isDebugEnabled()) {
          // 在真正执行 SQL 之前，打印出刚才通过 setXxx 积累的所有参数值
          debug("Parameters: " + getParameterValueString(), true);
        }
        // 执行完本次打印后，清理参数缓存，为下一次可能的语句重用（ReuseExecutor）做准备
        clearColumnInfo();

        // 如果是查询操作，需要对返回的结果集进行代理增强
        if ("executeQuery".equals(method.getName())) {
          ResultSet rs = (ResultSet) method.invoke(statement, params);
          // 增强 创建了 ResultSet 的代理对象
          return rs == null ? null : ResultSetLogger.newInstance(rs, statementLog, queryStack);
        } else {
          return method.invoke(statement, params);
        }
      }
      /*
       * 3. 【核心拦截点 B】：拦截参数设置方法（setInt, setString, setNull 等）
       * 逻辑：并不直接打印，而是先将 Key(索引) 和 Value(实参) 记录在内存中。
       */
      else if (SET_METHODS.contains(method.getName())) {
        if ("setNull".equals(method.getName())) {
          setColumn(params[0], null);
        } else {
          // params[0] 通常是占位符下标，params[1] 是实际设置的值
          setColumn(params[0], params[1]);
        }
        return method.invoke(statement, params);
      }

      /*
       * 4. 【核心拦截点 C】：拦截获取结果集和更新计数
       */
      else if ("getResultSet".equals(method.getName())) {
        ResultSet rs = (ResultSet) method.invoke(statement, params);
        return rs == null ? null : ResultSetLogger.newInstance(rs, statementLog, queryStack);
      } else if ("getUpdateCount".equals(method.getName())) {
        // 获取 DML 语句受影响的行数
        int updateCount = (Integer) method.invoke(statement, params);
        if (updateCount != -1) {
          // 打印：Updates: n
          debug("   Updates: " + updateCount, false);
        }
        return updateCount;
      } else {
        return method.invoke(statement, params);
      }
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
  }

  /**
   /**
   * [工厂方法] 创建 PreparedStatement 的日志代理实例。
   * 同时支持 PreparedStatement 和 CallableStatement (存储过程)。
   * <p>
   * Creates a logging version of a PreparedStatement.
   *
   * @param stmt - the statement
   * @param statementLog - the statement log
   * @param queryStack - the query stack
   * @return - the proxy
   */
  public static PreparedStatement newInstance(PreparedStatement stmt, Log statementLog, int queryStack) {
    InvocationHandler handler = new PreparedStatementLogger(stmt, statementLog, queryStack);
    ClassLoader cl = PreparedStatement.class.getClassLoader();
    // 创建了 PreparedStatement和CallableStatement 的代理对象 为的是添加日志功能
    return (PreparedStatement) Proxy.newProxyInstance(cl, new Class[]{PreparedStatement.class, CallableStatement.class}, handler);
  }

  /**
   * Return the wrapped prepared statement.
   *
   * @return the PreparedStatement
   */
  public PreparedStatement getPreparedStatement() {
    return statement;
  }

}
