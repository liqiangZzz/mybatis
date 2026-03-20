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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.reflection.ExceptionUtil;

/**
 * [连接对象代理实现] 负责拦截 JDBC Connection 对象的方法调用。
 * <p>
 * 核心职能：
 * 1. 监控 SQL 语句的预编译过程（Prepare）。
 * 2. 打印即将执行的原始 SQL 文本（格式化处理）。
 * 3. 产生并传递后续代理对象：将生成的 Statement 包装为代理类，使日志能力向后续链路渗透。
 * <p>
 *
 * Connection proxy to add logging.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 *
 */
public final class ConnectionLogger extends BaseJdbcLogger implements InvocationHandler {

  // 物理连接对象（JDBC 驱动提供的真实实例）
  private final Connection connection;

  private ConnectionLogger(Connection conn, Log statementLog, int queryStack) {
    super(statementLog, queryStack);
    this.connection = conn;
  }

  /**
   * Connection 代理对象的 InvocationHandler 实现
   *
   * <p>核心职责：拦截 Connection 创建 Statement 的方法，在创建 Statement 的同时，
   * 使用对应的 Logger 代理对 Statement 进行增强，实现 SQL 执行过程的日志记录。
   *
   * <p>代理增强流程：
   * Connection (原始对象)
   *     → 调用 createStatement/prepareStatement/prepareCall
   *     → 创建原始 Statement 对象
   *     → 使用 StatementLogger/PreparedStatementLogger 创建代理对象
   *     → 返回增强后的 Statement 代理
   *
   * @param proxy  代理对象本身（此处未使用）
   * @param method 当前被调用的方法
   * @param params 方法参数
   * @return 方法执行结果，如果是创建 Statement 的方法则返回增强后的代理对象
   * @throws Throwable 可能抛出的异常
   */
  @Override
  public Object invoke(Object proxy, Method method, Object[] params)
      throws Throwable {
    try {
      // 1. 【基础方法过滤】：若是 Object 自带方法（如 toString, hashCode），直接在代理类上执行
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, params);
      }

      /*
       * 2. 【核心拦截点 A】：拦截 prepareStatement 和 prepareCall
       * 逻辑：
       * a. 提取参数中的 SQL 字符串并打印日志。
       * b. 反射调用物理连接创建真正的 PreparedStatement。
       * c. 【关键】：为结果对象创建 PreparedStatementLogger 代理并返回。
       */
      if ("prepareStatement".equals(method.getName())) {
        if (isDebugEnabled()) {
          // 打印准备状态的 SQL，并清洗换行符和多余空格
          debug(" Preparing: " + removeBreakingWhitespace((String) params[0]), true);
        }
        // 创建  PreparedStatement，调用物理驱动获取语句对象
        PreparedStatement stmt = (PreparedStatement) method.invoke(connection, params);
        // 对语句对象 PreparedStatement 进行日志功能增强（包装成代理对象）
        stmt = PreparedStatementLogger.newInstance(stmt, statementLog, queryStack);
        return stmt;
        // 同上
      } else if ("prepareCall".equals(method.getName())) {
        if (isDebugEnabled()) {
          debug(" Preparing: " + removeBreakingWhitespace((String) params[0]), true);
        }
        PreparedStatement stmt = (PreparedStatement) method.invoke(connection, params);
        stmt = PreparedStatementLogger.newInstance(stmt, statementLog, queryStack);
        return stmt;
        /*
         * 3. 【核心拦截点 B】：拦截 createStatement
         * 逻辑：将原始 Statement 包装为 StatementLogger 代理并返回。
         */
      } else if ("createStatement".equals(method.getName())) {
        Statement stmt = (Statement) method.invoke(connection, params);
        stmt = StatementLogger.newInstance(stmt, statementLog, queryStack);
        return stmt;
      } else {
        // 4. 【普通方法转发】：对于非核心方法（如 setAutoCommit, commit 等），直接透传给物理连接
        return method.invoke(connection, params);
      }
    } catch (Throwable t) {
      // 剥离反射调用中产生的包装异常
      throw ExceptionUtil.unwrapThrowable(t);
    }
  }

  /**
   * [工厂方法] 创建 Connection 的日志代理实例。
   * <p>
   * Creates a logging version of a connection.
   *
   * @param conn - the original connection  原始 JDBC 连接
   * @param statementLog 日志输出适配器
   * @param queryStack   嵌套层级计数
   * @return - the connection with logging  一个实现了 Connection 接口的 JDK 动态代理实例
   */
  public static Connection newInstance(Connection conn, Log statementLog, int queryStack) {
    // 创建 InvocationHandler 实例，用于处理代理对象的方法调用
    InvocationHandler handler = new ConnectionLogger(conn, statementLog, queryStack);
    ClassLoader cl = Connection.class.getClassLoader();

    // 利用 JDK 动态代理技术，将日志逻辑“织入”到 Connection 接口中
    // 创建了 Connection的 代理对象 目的是 增强 Connection对象 给他添加了日志功能
    return (Connection) Proxy.newProxyInstance(cl, new Class[]{Connection.class}, handler);
  }

  /**
   * return the wrapped connection.
   *
   * @return the connection
   */
  public Connection getConnection() {
    return connection;
  }

}
