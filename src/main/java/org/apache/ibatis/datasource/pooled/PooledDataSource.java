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

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * This is a simple, synchronous, thread-safe database connection pool.
 *
 * @author Clinton Begin
 */
public class PooledDataSource implements DataSource {

  private static final Log log = LogFactory.getLog(PooledDataSource.class);
  // 管理状态
  private final PoolState state = new PoolState(this);

  // 记录UnpooledDataSource，用于生成真实的数据库连接对象
  private final UnpooledDataSource dataSource;

  // OPTIONAL CONFIGURATION FIELDS
  // 最大活跃连接数
  protected int poolMaximumActiveConnections = 10;
  // 最大空闲连接数
  protected int poolMaximumIdleConnections = 5;
  // 最大 checkout 时间
  protected int poolMaximumCheckoutTime = 20000;
  // 无法获取连接的线程需要等待的时长
  protected int poolTimeToWait = 20000;
  // 最大本地坏连接容忍数
  protected int poolMaximumLocalBadConnectionTolerance = 3;
  // 测试的 SQL 语句
  protected String poolPingQuery = "NO PING QUERY SET";
  // 是否允许发送测试 SQL 语句
  protected boolean poolPingEnabled;
  // 当连接超过 poolPingConnectionsNotUsedFor 毫秒未使用时，会发送一次测试SQL语句，检测连接是否正常
  protected int poolPingConnectionsNotUsedFor;
 // 根据数据库URL，用户名和密码生成的一个hash值。  判断该连接是否是当前数据库的连接
  private int expectedConnectionTypeCode;

  public PooledDataSource() {
    dataSource = new UnpooledDataSource();
  }

  public PooledDataSource(UnpooledDataSource dataSource) {
    this.dataSource = dataSource;
  }

  public PooledDataSource(String driver, String url, String username, String password) {
    dataSource = new UnpooledDataSource(driver, url, username, password);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(String driver, String url, Properties driverProperties) {
    dataSource = new UnpooledDataSource(driver, url, driverProperties);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, String username, String password) {
    dataSource = new UnpooledDataSource(driverClassLoader, driver, url, username, password);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, Properties driverProperties) {
    dataSource = new UnpooledDataSource(driverClassLoader, driver, url, driverProperties);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  /**
   * 从数据库连接池中获取 Connection 对象
   *    返回的是 Connection的代理对象
   * @return
   * @throws SQLException
   */
  @Override
  public Connection getConnection() throws SQLException {
    return popConnection(dataSource.getUsername(), dataSource.getPassword()).getProxyConnection();
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return popConnection(username, password).getProxyConnection();
  }

  @Override
  public void setLoginTimeout(int loginTimeout) {
    DriverManager.setLoginTimeout(loginTimeout);
  }

  @Override
  public int getLoginTimeout() {
    return DriverManager.getLoginTimeout();
  }

  @Override
  public void setLogWriter(PrintWriter logWriter) {
    DriverManager.setLogWriter(logWriter);
  }

  @Override
  public PrintWriter getLogWriter() {
    return DriverManager.getLogWriter();
  }

  public void setDriver(String driver) {
    dataSource.setDriver(driver);
    forceCloseAll();
  }

  public void setUrl(String url) {
    dataSource.setUrl(url);
    forceCloseAll();
  }

  public void setUsername(String username) {
    dataSource.setUsername(username);
    forceCloseAll();
  }

  public void setPassword(String password) {
    dataSource.setPassword(password);
    forceCloseAll();
  }

  public void setDefaultAutoCommit(boolean defaultAutoCommit) {
    dataSource.setAutoCommit(defaultAutoCommit);
    forceCloseAll();
  }

  public void setDefaultTransactionIsolationLevel(Integer defaultTransactionIsolationLevel) {
    dataSource.setDefaultTransactionIsolationLevel(defaultTransactionIsolationLevel);
    forceCloseAll();
  }

  public void setDriverProperties(Properties driverProps) {
    dataSource.setDriverProperties(driverProps);
    forceCloseAll();
  }

  /**
   * Sets the default network timeout value to wait for the database operation to complete. See {@link Connection#setNetworkTimeout(java.util.concurrent.Executor, int)}
   *
   * @param milliseconds
   *          The time in milliseconds to wait for the database operation to complete.
   * @since 3.5.2
   */
  public void setDefaultNetworkTimeout(Integer milliseconds) {
    dataSource.setDefaultNetworkTimeout(milliseconds);
    forceCloseAll();
  }

  /**
   * The maximum number of active connections.
   *
   * @param poolMaximumActiveConnections The maximum number of active connections
   */
  public void setPoolMaximumActiveConnections(int poolMaximumActiveConnections) {
    this.poolMaximumActiveConnections = poolMaximumActiveConnections;
    forceCloseAll();
  }

  /**
   * The maximum number of idle connections.
   *
   * @param poolMaximumIdleConnections The maximum number of idle connections
   */
  public void setPoolMaximumIdleConnections(int poolMaximumIdleConnections) {
    this.poolMaximumIdleConnections = poolMaximumIdleConnections;
    forceCloseAll();
  }

  /**
   * The maximum number of tolerance for bad connection happens in one thread
   * which are applying for new {@link PooledConnection}.
   *
   * @param poolMaximumLocalBadConnectionTolerance
   * max tolerance for bad connection happens in one thread
   *
   * @since 3.4.5
   */
  public void setPoolMaximumLocalBadConnectionTolerance(
      int poolMaximumLocalBadConnectionTolerance) {
    this.poolMaximumLocalBadConnectionTolerance = poolMaximumLocalBadConnectionTolerance;
  }

  /**
   * The maximum time a connection can be used before it *may* be
   * given away again.
   *
   * @param poolMaximumCheckoutTime The maximum time
   */
  public void setPoolMaximumCheckoutTime(int poolMaximumCheckoutTime) {
    this.poolMaximumCheckoutTime = poolMaximumCheckoutTime;
    forceCloseAll();
  }

  /**
   * The time to wait before retrying to get a connection.
   *
   * @param poolTimeToWait The time to wait
   */
  public void setPoolTimeToWait(int poolTimeToWait) {
    this.poolTimeToWait = poolTimeToWait;
    forceCloseAll();
  }

  /**
   * The query to be used to check a connection.
   *
   * @param poolPingQuery The query
   */
  public void setPoolPingQuery(String poolPingQuery) {
    this.poolPingQuery = poolPingQuery;
    forceCloseAll();
  }

  /**
   * Determines if the ping query should be used.
   *
   * @param poolPingEnabled True if we need to check a connection before using it
   */
  public void setPoolPingEnabled(boolean poolPingEnabled) {
    this.poolPingEnabled = poolPingEnabled;
    forceCloseAll();
  }

  /**
   * If a connection has not been used in this many milliseconds, ping the
   * database to make sure the connection is still good.
   *
   * @param milliseconds the number of milliseconds of inactivity that will trigger a ping
   */
  public void setPoolPingConnectionsNotUsedFor(int milliseconds) {
    this.poolPingConnectionsNotUsedFor = milliseconds;
    forceCloseAll();
  }

  public String getDriver() {
    return dataSource.getDriver();
  }

  public String getUrl() {
    return dataSource.getUrl();
  }

  public String getUsername() {
    return dataSource.getUsername();
  }

  public String getPassword() {
    return dataSource.getPassword();
  }

  public boolean isAutoCommit() {
    return dataSource.isAutoCommit();
  }

  public Integer getDefaultTransactionIsolationLevel() {
    return dataSource.getDefaultTransactionIsolationLevel();
  }

  public Properties getDriverProperties() {
    return dataSource.getDriverProperties();
  }

  /**
   * @since 3.5.2
   */
  public Integer getDefaultNetworkTimeout() {
    return dataSource.getDefaultNetworkTimeout();
  }

  public int getPoolMaximumActiveConnections() {
    return poolMaximumActiveConnections;
  }

  public int getPoolMaximumIdleConnections() {
    return poolMaximumIdleConnections;
  }

  public int getPoolMaximumLocalBadConnectionTolerance() {
    return poolMaximumLocalBadConnectionTolerance;
  }

  public int getPoolMaximumCheckoutTime() {
    return poolMaximumCheckoutTime;
  }

  public int getPoolTimeToWait() {
    return poolTimeToWait;
  }

  public String getPoolPingQuery() {
    return poolPingQuery;
  }

  public boolean isPoolPingEnabled() {
    return poolPingEnabled;
  }

  public int getPoolPingConnectionsNotUsedFor() {
    return poolPingConnectionsNotUsedFor;
  }

  /**
   * [强制清空连接池] 关闭池中所有的活跃连接和空闲连接。
   * Closes all active and idle connections in the pool.
   */
  public void forceCloseAll() {
    // 1. 【同步加锁】：保证在清理过程中，没有其他线程能获取或归还连接
    synchronized (state) {
      // 2. 【身份重置】：根据最新的 URL、用户名和密码生成新的标识码。
      // 这能确保之后归还的老连接因标识符不匹配而无法再回到池中。
      expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());

      // 3. 【清理活跃连接】：遍历正在被业务代码使用的连接
      for (int i = state.activeConnections.size(); i > 0; i--) {
        try {
          // 获取 获取的连接
          PooledConnection conn = state.activeConnections.remove(i - 1);
          // 使该连接失效，防止业务代码继续使用
          conn.invalidate();
          // 获取真实的 数据库连接
          Connection realConn = conn.getRealConnection();

          // 如果该连接还有没提交的事务，先回滚掉
          if (!realConn.getAutoCommit()) {
            realConn.rollback();
          }
          // 真正断开与数据库的物理连接
          realConn.close();
        } catch (Exception e) {
          // ignore
        }
      }

      // 4. 【清理空闲连接】：遍历池中存好的空闲连接
      for (int i = state.idleConnections.size(); i > 0; i--) {
        try {
          PooledConnection conn = state.idleConnections.remove(i - 1);
          conn.invalidate();

          Connection realConn = conn.getRealConnection();
          if (!realConn.getAutoCommit()) {
            realConn.rollback();
          }
          realConn.close();
        } catch (Exception e) {
          // ignore
        }
      }
    }
    if (log.isDebugEnabled()) {
      log.debug("PooledDataSource forcefully closed/removed all connections.");
    }
  }

  public PoolState getPoolState() {
    return state;
  }

  private int assembleConnectionTypeCode(String url, String username, String password) {
    return ("" + url + username + password).hashCode();
  }

  /**
   * [连接回收中枢] 将使用完毕的连接放回池中或物理关闭。
   *
   * @param conn 即将归还的连接包装对象
   */
  protected void pushConnection(PooledConnection conn) throws SQLException {

    synchronized (state) {
      // 1. 【状态变更】：从 activeConnections 列表中移除该连接
      state.activeConnections.remove(conn);
      // 检测 连接是否有效
      if (conn.isValid()) {

        /*
         * 2. 【回收判定】：
         * - 空间检查：空闲池尚未达到预设的最大空闲上限。
         * - 身份校验：确保该连接的配置信息（URL/User）与当前池一致。
         */
        if (state.idleConnections.size() < poolMaximumIdleConnections
            && conn.getConnectionTypeCode() == expectedConnectionTypeCode
        ) {

          // 累计连接检出时长（用于监控统计）
          state.accumulatedCheckoutTime += conn.getCheckoutTime();

          // 3. 【数据安全】：强制回滚未提交的事务，确保连接安全地回到池中
          if (!conn.getRealConnection().getAutoCommit()) {
            conn.getRealConnection().rollback();
          }

          /*
           * 4. 【对象转换】：
           * 逻辑：取其物理连接，弃其旧包装，创其新包装。
           * 目的：重置连接的内部状态（如时间戳等元数据）。
           */
          PooledConnection newConn = new PooledConnection(conn.getRealConnection(), this);
          // 添加到 空闲连接集合中
          state.idleConnections.add(newConn);
          newConn.setCreatedTimestamp(conn.getCreatedTimestamp());
          newConn.setLastUsedTimestamp(conn.getLastUsedTimestamp());
          // 使旧的代理引用失效，防止原开发者在 close 后再次误用
          conn.invalidate();
          if (log.isDebugEnabled()) {
            log.debug("Returned connection " + newConn.getRealHashCode() + " to pool.");
          }
          // 5. 【资源发布】：唤醒所有正在 popConnection 中阻塞等待连接的线程
          state.notifyAll();
        } else {
          // 空闲连接达到上限或者 PooledConnection不属于当前的连接池
          state.accumulatedCheckoutTime += conn.getCheckoutTime();
          if (!conn.getRealConnection().getAutoCommit()) {
            conn.getRealConnection().rollback();
          }
          // 执行真正的物理 IO 关闭数据库连接
          conn.getRealConnection().close();
          if (log.isDebugEnabled()) {
            log.debug("Closed connection " + conn.getRealHashCode() + ".");
          }
          // 设置 PooledConnection 无效，防止被再次使用
          conn.invalidate();
        }
      } else {
        if (log.isDebugEnabled()) {
          log.debug("A bad connection (" + conn.getRealHashCode() + ") attempted to return to the pool, discarding connection.");
        }
        // 场景：连接已损坏（isValid = false）
        // 统计无效的 PooledConnection 对象个数
        state.badConnectionCount++;
      }
    }
  }

  /**
   * [核心调度方法] 从连接池中获取一个有效的 PooledConnection 代理对象。
   *
   * @param username 数据库账号
   * @param password 数据库密码
   * @return 具备物理连接能力的包装对象
   */
  private PooledConnection popConnection(String username, String password) throws SQLException {
    boolean countedWait = false;
    PooledConnection conn = null;
    long t = System.currentTimeMillis();
    int localBadConnectionCount = 0;

    // 采用自旋锁模式，直到获取有效连接或触发异常退出
    while (conn == null) {
      // 1. 【同步锁定】：必须竞争 PoolState 对象的监视器锁，确保池状态变更的线程安全性
      synchronized (state) {
        // 检测空闲连接
        if (!state.idleConnections.isEmpty()) {
          // Pool has available connection
          // 【优先级 1】：空闲池复用。移除并获取第一个空闲连接
          conn = state.idleConnections.remove(0);
          if (log.isDebugEnabled()) {
            log.debug("Checked out connection " + conn.getRealHashCode() + " from pool.");
          }
        } else {
          // Pool does not have available connection
          // 【优先级 2】：池未满则创建新连接
          if (state.activeConnections.size() < poolMaximumActiveConnections) {
            // Can create new connection
            // 调用底层的非池化数据源创建真实的物理连接
            conn = new PooledConnection(dataSource.getConnection(), this);
            if (log.isDebugEnabled()) {
              log.debug("Created connection " + conn.getRealHashCode() + ".");
            }
          } else {
            // Cannot create new connection
            // 【优先级 3】：池已满，检查是否存在执行超时的“僵尸连接”，先进先出结构，取最先创建的活跃连接
            PooledConnection oldestActiveConnection = state.activeConnections.get(0);
            // 获取该连接的超时时间
            long longestCheckoutTime = oldestActiveConnection.getCheckoutTime();
            // 检查是否超时，如果超时则处理
            if (longestCheckoutTime > poolMaximumCheckoutTime) {
              // Can claim overdue connection
              // 判定为超时占用，执行强制回收逻辑，对超时连接的信息进行统计
              state.claimedOverdueConnectionCount++;
              state.accumulatedCheckoutTimeOfOverdueConnections += longestCheckoutTime;
              state.accumulatedCheckoutTime += longestCheckoutTime;
              // 将超时连接移除 activeConnections
              state.activeConnections.remove(oldestActiveConnection);

              // 若该连接存在未提交事务，强制回滚以释放资源
              if (!oldestActiveConnection.getRealConnection().getAutoCommit()) {

                try {
                  // 如果超时连接没有提交 则自动回滚
                  oldestActiveConnection.getRealConnection().rollback();
                } catch (SQLException e) {
                  /*
                     Just log a message for debug and continue to execute the following
                     statement like nothing happened.
                     Wrap the bad connection with a new PooledConnection, this will help
                     to not interrupt current executing thread and give current thread a
                     chance to join the next competition for another valid/good database
                     connection. At the end of this loop, bad {@link @conn} will be set as null.
                   */
                  // 记录日志，但不中断主流程，尝试重用该物理连接
                  log.debug("Bad connection. Could not roll back");
                }
              }

              // 【重用核心】：创建 PooledConnection，复用物理连接，但重新封装包装对象，数据库中的真正连接并没有创建
              conn = new PooledConnection(oldestActiveConnection.getRealConnection(), this);
              conn.setCreatedTimestamp(oldestActiveConnection.getCreatedTimestamp());
              conn.setLastUsedTimestamp(oldestActiveConnection.getLastUsedTimestamp());
              // 使旧的PooledConnection包装对象失效，防止原持有者误操作
              oldestActiveConnection.invalidate();
              if (log.isDebugEnabled()) {
                log.debug("Claimed overdue connection " + conn.getRealHashCode() + ".");
              }
            } else {
              // Must wait
              // 【优先级 4】：无路可走，当前线程进入阻塞排队状态
              try {
                if (!countedWait) {
                  // 统计等待次数
                  state.hadToWaitCount++;
                  countedWait = true;
                }
                if (log.isDebugEnabled()) {
                  log.debug("Waiting as long as " + poolTimeToWait + " milliseconds for connection.");
                }
                long wt = System.currentTimeMillis();
                // 挂起线程，释放 state 锁，等待其他线程归还连接后的 notifyAll
                state.wait(poolTimeToWait);
                // 统计累计的等待时间
                state.accumulatedWaitTime += System.currentTimeMillis() - wt;
              } catch (InterruptedException e) {
                break; // 处理线程中断
              }
            }
          }
        }

        // 2. 【有效性检查】：对获取到的连接执行可用性校验
        if (conn != null) {
          // ping to server and check the connection is valid or not
          // 检查 PooledConnection 是否有效
          if (conn.isValid()) {
            // 正常逻辑：配置事务状态、时间戳，并登记入活跃列表
            if (!conn.getRealConnection().getAutoCommit()) {
              conn.getRealConnection().rollback();
            }
            // 配置 PooledConnection 的相关属性
            conn.setConnectionTypeCode(assembleConnectionTypeCode(dataSource.getUrl(), username, password));
            conn.setCheckoutTimestamp(System.currentTimeMillis());
            conn.setLastUsedTimestamp(System.currentTimeMillis());
            state.activeConnections.add(conn);
            state.requestCount++; // 进行相关的统计
            state.accumulatedRequestTime += System.currentTimeMillis() - t;
          } else {
            if (log.isDebugEnabled()) {
              log.debug("A bad connection (" + conn.getRealHashCode() + ") was returned from the pool, getting another connection.");
            }
            // 异常处理：连接已失效，执行重试逻辑
            state.badConnectionCount++;
            localBadConnectionCount++;
            conn = null;// 置为空以触发下一轮 while 循环

            // 【熔断处理】：防止数据库彻底瘫痪导致的无效循环
            if (localBadConnectionCount > (poolMaximumIdleConnections + poolMaximumLocalBadConnectionTolerance)) {
              if (log.isDebugEnabled()) {
                log.debug("PooledDataSource: Could not get a good connection to the database.");
              }
              throw new SQLException("PooledDataSource: Could not get a good connection to the database.");
            }
          }
        }
      }

    }

    // 3. 【异常处理】：如果最终获取到的连接为空，抛出异常
    if (conn == null) {
      if (log.isDebugEnabled()) {
        log.debug("PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
      }
      throw new SQLException("PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
    }

    return conn;
  }

  /**
   * [连接存活检测] 验证数据库连接的真实可用性。
   * <p>
   * 逻辑分为两层：
   * 1. 物理状态检查：通过 JDBC 驱动判断 Socket 是否已断开。
   * 2. 逻辑心跳检查：通过执行一个简单的 SQL 语句（Ping Query）确认数据库服务端响应正常。
   * <p>
   * Method to check to see if a connection is still usable
   *
   * @param conn - the connection to check  待检测的连接包装对象
   * @return True if the connection is still usable  true 表示连接健康可用，false 表示连接已损坏
   */
  protected boolean pingConnection(PooledConnection conn) {
    boolean result = true;

    try {
      // 1. 【基础物理检查】：调用驱动接口，判断底层物理连接是否已显式关闭
      result = !conn.getRealConnection().isClosed();
    } catch (SQLException e) {
      if (log.isDebugEnabled()) {
        log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
      }
      result = false;
    }

    // 2. 【高级逻辑检查】：若物理未关闭，则根据配置决定是否执行“心跳测试”
    if (result) {
      if (poolPingEnabled) {
        /*
         * 触发条件判定：
         * 为了保证性能，MyBatis 并不盲目执行 Ping 动作。
         * 只有当连接的“闲置时长”超过了 poolPingConnectionsNotUsedFor 阈值时，才会触发真实的 SQL 测试。
         */
        if (poolPingConnectionsNotUsedFor >= 0 && conn.getTimeElapsedSinceLastUse() > poolPingConnectionsNotUsedFor) {
          try {
            if (log.isDebugEnabled()) {
              log.debug("Testing connection " + conn.getRealHashCode() + " ...");
            }
            // 3. 【执行心跳 SQL】：从物理连接中创建一个语句对象
            Connection realConn = conn.getRealConnection();
            try (Statement statement = realConn.createStatement()) {
              // 执行用户配置的探测 SQL（如 SELECT 1），若数据库无响应则抛出异常
              statement.executeQuery(poolPingQuery).close();
            }

            // 4. 【环境清理】：若非自动提交模式，回滚探测 SQL 产生的潜在事务影响
            if (!realConn.getAutoCommit()) {
              realConn.rollback();
            }
            result = true;
            if (log.isDebugEnabled()) {
              log.debug("Connection " + conn.getRealHashCode() + " is GOOD!");
            }
          } catch (Exception e) {
            // 5. 【异常处理】：探测失败，判定为“坏连接”
            log.warn("Execution of ping query '" + poolPingQuery + "' failed: " + e.getMessage());
            try {
              // 强制物理关闭已损坏的连接，释放服务端资源
              conn.getRealConnection().close();
            } catch (Exception e2) {
              //ignore
              // 忽略关闭时的次生异常
            }
            result = false;
            if (log.isDebugEnabled()) {
              log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
            }
          }
        }
      }
    }
    return result;
  }

  /**
   * Unwraps a pooled connection to get to the 'real' connection
   *
   * @param conn - the pooled connection to unwrap
   * @return The 'real' connection
   */
  public static Connection unwrapConnection(Connection conn) {
    if (Proxy.isProxyClass(conn.getClass())) {
      InvocationHandler handler = Proxy.getInvocationHandler(conn);
      if (handler instanceof PooledConnection) {
        return ((PooledConnection) handler).getRealConnection();
      }
    }
    return conn;
  }

  @Override
  protected void finalize() throws Throwable {
    forceCloseAll();
    super.finalize();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    throw new SQLException(getClass().getName() + " is not a wrapper.");
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return false;
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
  }

}
