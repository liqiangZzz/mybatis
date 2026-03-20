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
package org.apache.ibatis.datasource.pooled;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import org.apache.ibatis.reflection.ExceptionUtil;

/**
 * 数据库连接池返回给客户端的对象 Connection
 * @author Clinton Begin
 */
class PooledConnection implements InvocationHandler {

  private static final String CLOSE = "close";
  private static final Class<?>[] IFACES = new Class<?>[] { Connection.class };

  private final int hashCode;
  private final PooledDataSource dataSource;
  //  真正的数据库连接
  private final Connection realConnection;
  //  数据库连接的代理对象
  private final Connection proxyConnection;
  // 从连接池中取出该连接的时间戳
  private long checkoutTimestamp;
  // 该连接创建的时间戳
  private long createdTimestamp;
  // 最后一次被使用的时间戳
  private long lastUsedTimestamp;
  // 数据库URL、用户名和密码计算出来的hash值，可用于标识该连接所在的连接池
  private int connectionTypeCode;
  // 连接是否有效的标志
  private boolean valid;

  /**
   * Constructor for SimplePooledConnection that uses the Connection and PooledDataSource passed in.
   *  Connection的所有方法
   *        createStatement
   *        preparedStatement
   *        close
   *
   * @param connection - the connection that is to be presented as a pooled connection
   * @param dataSource - the dataSource that the connection is from
   */
  public PooledConnection(Connection connection, PooledDataSource dataSource) {
    this.hashCode = connection.hashCode();
    this.realConnection = connection;
    this.dataSource = dataSource;
    this.createdTimestamp = System.currentTimeMillis();
    this.lastUsedTimestamp = System.currentTimeMillis();
    this.valid = true;
    this.proxyConnection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), IFACES, this);
  }

  /**
   * Invalidates the connection.
   */
  public void invalidate() {
    valid = false;
  }

  /**
   * Method to see if the connection is usable.
   *
   * @return True if the connection is usable
   */
  public boolean isValid() {
    return valid && realConnection != null && dataSource.pingConnection(this);
  }

  /**
   * Getter for the *real* connection that this wraps.
   *
   * @return The connection
   */
  public Connection getRealConnection() {
    return realConnection;
  }

  /**
   * Getter for the proxy for the connection.
   *
   * @return The proxy
   */
  public Connection getProxyConnection() {
    return proxyConnection;
  }

  /**
   * Gets the hashcode of the real connection (or 0 if it is null).
   *
   * @return The hashcode of the real connection (or 0 if it is null)
   */
  public int getRealHashCode() {
    return realConnection == null ? 0 : realConnection.hashCode();
  }

  /**
   * Getter for the connection type (based on url + user + password).
   *
   * @return The connection type
   */
  public int getConnectionTypeCode() {
    return connectionTypeCode;
  }

  /**
   * Setter for the connection type.
   *
   * @param connectionTypeCode - the connection type
   */
  public void setConnectionTypeCode(int connectionTypeCode) {
    this.connectionTypeCode = connectionTypeCode;
  }

  /**
   * Getter for the time that the connection was created.
   *
   * @return The creation timestamp
   */
  public long getCreatedTimestamp() {
    return createdTimestamp;
  }

  /**
   * Setter for the time that the connection was created.
   *
   * @param createdTimestamp - the timestamp
   */
  public void setCreatedTimestamp(long createdTimestamp) {
    this.createdTimestamp = createdTimestamp;
  }

  /**
   * Getter for the time that the connection was last used.
   *
   * @return - the timestamp
   */
  public long getLastUsedTimestamp() {
    return lastUsedTimestamp;
  }

  /**
   * Setter for the time that the connection was last used.
   *
   * @param lastUsedTimestamp - the timestamp
   */
  public void setLastUsedTimestamp(long lastUsedTimestamp) {
    this.lastUsedTimestamp = lastUsedTimestamp;
  }

  /**
   * Getter for the time since this connection was last used.
   *
   * @return - the time since the last use
   */
  public long getTimeElapsedSinceLastUse() {
    return System.currentTimeMillis() - lastUsedTimestamp;
  }

  /**
   * Getter for the age of the connection.
   *
   * @return the age
   */
  public long getAge() {
    return System.currentTimeMillis() - createdTimestamp;
  }

  /**
   * Getter for the timestamp that this connection was checked out.
   *
   * @return the timestamp
   */
  public long getCheckoutTimestamp() {
    return checkoutTimestamp;
  }

  /**
   * Setter for the timestamp that this connection was checked out.
   *
   * @param timestamp the timestamp
   */
  public void setCheckoutTimestamp(long timestamp) {
    this.checkoutTimestamp = timestamp;
  }

  /**
   * Getter for the time that this connection has been checked out.
   *
   * @return the time
   */
  public long getCheckoutTime() {
    return System.currentTimeMillis() - checkoutTimestamp;
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  /**
   * Allows comparing this connection to another.
   *
   * @param obj - the other connection to test for equality
   * @see Object#equals(Object)
   */
  @Override
  public boolean equals(Object obj) {
    if (obj instanceof PooledConnection) {
      return realConnection.hashCode() == ((PooledConnection) obj).realConnection.hashCode();
    } else if (obj instanceof Connection) {
      return hashCode == obj.hashCode();
    } else {
      return false;
    }
  }

  /**
   * [连接代理执行器] 拦截并处理代理连接对象的所有方法调用。
   * <p>
   * 核心设计目标：
   * 1. 拦截 close() 方法，将其转化为“归一化回收”动作。
   * 2. 在执行业务指令前，强制校验连接的有效性。
   *
   * @param proxy  - not used
   * @param method - the method to be executed
   * @param args   - the parameters to be passed to the method
   * @see java.lang.reflect.InvocationHandler#invoke(Object, java.lang.reflect.Method, Object[])
   */
  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    String methodName = method.getName();

    /*
     * 1. 【核心拦截点】：拦截 close() 方法。
     *
     * 逻辑：当业务代码调用 connection.close() 时，并不会触发物理连接的断开。
     * 动作：调用数据源的 pushConnection(this) 方法，将物理连接重新放回空闲池中。
     * 返回：直接返回 null，不向下执行。
     */
    if (CLOSE.equals(methodName)) {
      // 如果是 close 方法被执行则将连接放回连接池中，而不是真正的关闭数据库连接
      dataSource.pushConnection(this);
      return null;
    }
    try {
      /*
       * 2. 【状态安全校验】：
       * 逻辑：若执行的方法不属于 Object 类（如 toString, hashCode 等），
       * 则必须检查当前连接在池中的状态。
       *
       * 目的：若连接已被池回收或标记为无效（isValid = false），此时调用其业务方法
       * 会直接抛出 SQLException，防止因使用失效连接导致程序逻辑混乱。
       */
      if (!Object.class.equals(method.getDeclaringClass())) {
        // issue #579 toString() should never fail
        // throw an SQLException instead of a Runtime
        // 通过上面的 valid 字段来检测 连接是否有效
        checkConnection();
      }

      /*
       * 3. 【执行权委派】：调用真正数据库连接对象的对应方法。
       * 逻辑：利用反射将请求转发给真实的物理连接对象（realConnection）。
       */
      return method.invoke(realConnection, args);
    } catch (Throwable t) {
      // 剥离反射层产生的多余异常包装，抛出真实的数据库驱动异常
      throw ExceptionUtil.unwrapThrowable(t);
    }

  }

  private void checkConnection() throws SQLException {
    if (!valid) {
      throw new SQLException("Error accessing PooledConnection. Connection is invalid.");
    }
  }

}
