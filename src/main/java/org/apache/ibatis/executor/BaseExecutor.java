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

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementUtil;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 */
public abstract class BaseExecutor implements Executor {

  private static final Log log = LogFactory.getLog(BaseExecutor.class);

  //  Transaction 对象，实现事务的提交，回滚和关闭操作
  protected Transaction transaction;
  // 其中封装的 Executor 对象
  protected Executor wrapper;
  // 延迟加载队列
  protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
  // 一级缓存 对象
  protected PerpetualCache localCache;
  // 一级缓存 用于缓存输出类型的参数
  protected PerpetualCache localOutputParameterCache;

  protected Configuration configuration;
  // 用于记录嵌套查询的层数
  protected int queryStack;
  // Executor是否关闭
  private boolean closed;

  protected BaseExecutor(Configuration configuration, Transaction transaction) {
    this.transaction = transaction;
    this.deferredLoads = new ConcurrentLinkedQueue<>();
    this.localCache = new PerpetualCache("LocalCache");
    this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
    this.closed = false;
    this.configuration = configuration;
    this.wrapper = this;
  }

  @Override
  public Transaction getTransaction() {
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    return transaction;
  }

  @Override
  public void close(boolean forceRollback) {
    try {
      try {
        rollback(forceRollback);
      } finally {
        if (transaction != null) {
          transaction.close();
        }
      }
    } catch (SQLException e) {
      // Ignore.  There's nothing that can be done at this point.
      log.warn("Unexpected exception on closing transaction.  Cause: " + e);
    } finally {
      transaction = null;
      deferredLoads = null;
      localCache = null;
      localOutputParameterCache = null;
      closed = true;
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }


  /**
   * [一级缓存失效与写操作分发] 执行写操作并强制同步一级缓存状态。
   *
   * 核心职能：
   * 1. 维护请求诊断上下文。
   * 2. 强制清空会话级的一级缓存。
   * 3. 委派具体的子类执行物理更新。
   */
  @Override
  public int update(MappedStatement ms, Object parameter) throws SQLException {
    // 1. 【诊断上下文配置】：利用 ThreadLocal 记录当前执行的 SQL 资源信息
    // 目的：若后续 JDBC 操作抛出异常，能精准指出是哪个 Mapper 的哪个 ID 出了问题。
    ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());

    // 2. 【生命周期检查】：确保执行器未被物理关闭
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }

    /*
     * 3. 【一级缓存强制清空】：核心逻辑。
     * 逻辑：调用 clearLocalCache() 物理擦除当前 SqlSession 内部的 HashMap。
     * 理由：MyBatis 规定任何增删改操作都会导致数据库状态改变，为了防止产生“数据幻觉”，
     *      必须使当前会话内所有已缓存的查询结果立即失效。
     */
    clearLocalCache();

    /*
     * 4. 【具体业务逻辑分发】：模板方法模式应用。
     * 逻辑：调用抽象方法 doUpdate，由子类（Simple/Reuse/BatchExecutor）实现 JDBC 的物理调用。
     * SimpleExecutor：直接调用 JDBC 执行 SQL，提交事务。
     * ReuseExecutor：使用 ReuseStatementHandler 重用 JDBC Statement，提交事务。
     * BatchExecutor：使用 BatchStatementHandler 批量执行 SQL，延迟提交事务。
     */
    return doUpdate(ms, parameter);
  }

  @Override
  public List<BatchResult> flushStatements() throws SQLException {
    return flushStatements(false);
  }

  public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    return doFlushStatements(isRollBack);
  }

  @Override
  public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
    BoundSql boundSql = ms.getBoundSql(parameter);
    // 一级缓存和二级缓存的CacheKey是同一个
    CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
    return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
  }

  /**
   * [一级缓存执行引擎] 处理一级缓存检索、递归查询深度管理及延迟加载任务分发。
   * @param ms MappedStatement  映射语句
   * @param parameter 参数对象
   * @param rowBounds 分页对象
   * @param resultHandler 查询结果处理器
   * @param key 缓存键
   * @param boundSql SQL 语句
   * @return 查询结果
   */
  @SuppressWarnings("unchecked")
  @Override
  public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
    // 1. 【诊断上下文】：配置 ErrorContext，用于在发生异常时精确定位 SQL 资源和当前活动
    ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());

    // 2. 【状态校验】：若执行器已关闭，禁止查询操作
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }

    // 3. 【缓存清空逻辑】：若为最外层查询且配置了 flushCache="true"，则强制清空一级缓存
    if (queryStack == 0 && ms.isFlushCacheRequired()) {
      // flushCache="true"时，即使是查询，也清空一级缓存
      clearLocalCache();
    }
    List<E> list;
    try {
      // 4. 【递归深度维护】：增加查询栈计数，防止嵌套/递归查询导致的缓存处理混乱
      queryStack++;

      // 5. 【一级缓存检索】：
      // 若未指定 ResultHandler，尝试从 localCache（一级缓存）中按 Key 获取结果
      list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
      if (list != null) {
        // 5.1 命中缓存：处理存储过程或带输出参数的本地缓存逻辑
        handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
      } else {
        // 5.2 缓存未命中：执行物理数据库查询，并随后存入一级缓存
        list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
      }
    } finally {
      // 计数回减
      queryStack--;
    }

    // 6. 【后置处理阶段】：仅在最外层查询执行完毕时触发
    if (queryStack == 0) {

      // 6.1 处理延迟加载：触发所有已注册的延迟加载任务（DeferredLoad）
      // 解决如“学校->学生->学校”这种循环引用或嵌套关联的数据填充
      for (DeferredLoad deferredLoad : deferredLoads) {
        deferredLoad.load();
      }
      // issue #601
      deferredLoads.clear();

      // 6.2 作用域强制约束：若一级缓存作用域设为 STATEMENT，则单条语句执行完立即销毁缓存
      // 这种模式下，一级缓存实际上失去了跨方法复用的能力
      if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
        // issue #482
        clearLocalCache();
      }
    }
    return list;
  }

  @Override
  public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
    BoundSql boundSql = ms.getBoundSql(parameter);
    return doQueryCursor(ms, parameter, rowBounds, boundSql);
  }

  @Override
  public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache, configuration, targetType);
    if (deferredLoad.canLoad()) {
      deferredLoad.load();
    } else {
      deferredLoads.add(new DeferredLoad(resultObject, property, key, localCache, configuration, targetType));
    }
  }

  /**
   * [唯一标识构建] 根据当前执行上下文创建缓存键（CacheKey）。
   *
   * CacheKey 用于在缓存（一级/二级）中作为索引。只有当两个查询的各项指标完全一致时，
   * 才会认为命中缓存。
   * @param ms MappedStatement  映射语句
   * @param parameterObject 参数对象
   * @param rowBounds 分页对象
   * @param boundSql SQL 语句
   * @return CacheKey 缓存键
   */
  @Override
  public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
    // 1. 状态校验：若执行器已关闭，禁止创建操作
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    // 2. 初始化缓存键对象。内部会累积计算哈希值及校验和
    CacheKey cacheKey = new CacheKey();
    // -1381545870:4796102018:com.bobo.mapper.BlogMapper.selectBlogById:0:2147483647:
    // select * from blog where bid = ?:1:development
    // select * from t_user
    // select id,user_name from t_user

    // 3. 【维度 1】：添加 MappedStatement 的唯一 ID (如 com.xxx.Mapper.selectById)
    cacheKey.update(ms.getId());
    // 4. 【维度 2】：添加分页参数（偏移量和限制条数）
    // 确保同一 SQL 在不同分页条件下的查询结果互不干扰
    cacheKey.update(rowBounds.getOffset()); // 0
    cacheKey.update(rowBounds.getLimit()); // 2147483647 = 2^31-1

    // 5. 【维度 3】：添加物理 SQL 文本
    // BoundSql 此时已解析完逻辑标签，返回的是带 ? 占位符的原始 SQL
    cacheKey.update(boundSql.getSql()); // 将 SQL 添加到 CacheKey中

    // 6. 【维度 4】：遍历并添加实参值（重点）
    List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();

    // mimic DefaultParameterHandler logic 获取用户传入的实参 并添加到CacheKey中
    for (ParameterMapping parameterMapping : parameterMappings) {
      // 过滤掉存储过程中的输出类型参数，仅保留输入参数
      if (parameterMapping.getMode() != ParameterMode.OUT) {
        Object value;
        String propertyName = parameterMapping.getProperty();
        // a. 优先从动态参数（如 foreach 产生的临时变量）中获取
        if (boundSql.hasAdditionalParameter(propertyName)) {
          value = boundSql.getAdditionalParameter(propertyName);
          // b. 参数为空场景处理
        } else if (parameterObject == null) {
          value = null;
          // c. 简单类型处理（如直接传 String, Integer 等，且有对应的 TypeHandler）
        } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
          value = parameterObject;
          // d. 复杂 POJO 或 Map 处理
        } else {
          // 利用 MyBatis 反射工具箱 MetaObject 精准提取对象的属性值
          MetaObject metaObject = configuration.newMetaObject(parameterObject);
          value = metaObject.getValue(propertyName);
        }
        // 将每一个真实的入参值注入 CacheKey
        cacheKey.update(value); // development
      }
    }

    // 7. 【维度 5】：添加数据库环境 ID（区分不同数据源环境）
    if (configuration.getEnvironment() != null) {
      // issue #176
      cacheKey.update(configuration.getEnvironment().getId());
    }
    return cacheKey;
  }

  @Override
  public boolean isCached(MappedStatement ms, CacheKey key) {
    return localCache.getObject(key) != null;
  }

  @Override
  public void commit(boolean required) throws SQLException {
    if (closed) {
      throw new ExecutorException("Cannot commit, transaction is already closed");
    }
    // 清空一级缓存
    clearLocalCache();
    flushStatements(); // 啥都没做
    if (required) {
      transaction.commit();
    }
  }

  @Override
  public void rollback(boolean required) throws SQLException {
    if (!closed) {
      try {
        clearLocalCache();
        flushStatements(true);
      } finally {
        if (required) {
          transaction.rollback();
        }
      }
    }
  }

  /**
   * [一级缓存清理] 物理擦除当前会话（SqlSession）内部的所有本地缓存数据。
   *
   * 核心职能：
   * 1. 清空常规 SQL 查询的结果集缓存。
   * 2. 清空存储过程调用产生的输出参数缓存。
   */
  @Override
  public void clearLocalCache() {
    // 1. 生命周期校验：仅在执行器未关闭的情况下执行清理动作
    if (!closed) {
      /*
       * 2. 【核心缓存擦除】：
       * 逻辑：调用 localCache (PerpetualCache 类型) 的 clear 方法。
       * 结果：移除该会话内部 HashMap 存储的所有结果对象，释放内存。
       */
      localCache.clear();

      /*
       * 3. 【存储过程缓存擦除】：
       * 逻辑：清空针对 CALLABLE 类型语句缓存的输出参数 (Output Parameters)。
       * 目的：确保下一次执行存储过程时，参数状态是全新的。
       */
      localOutputParameterCache.clear();
    }
  }

  protected abstract int doUpdate(MappedStatement ms, Object parameter)
      throws SQLException;

  protected abstract List<BatchResult> doFlushStatements(boolean isRollback)
      throws SQLException;

  /**
   *  CachingExecutor:二级缓存
   *  BaseExecutor：一级缓存
   *     具体：BatchExecutor ：批处理  具体完成数据库操作
   *          ReuseExecutor ：复用 Statement对象 具体完成数据库操作
   *          SimpleExecutor ： 不复用 Statement 对象 具体完成数据库操作
   * @param ms
   * @param parameter
   * @param rowBounds
   * @param resultHandler
   * @param boundSql
   * @param <E>
   * @return
   * @throws SQLException
   */
  protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
      throws SQLException;

  protected abstract <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
      throws SQLException;

  protected void closeStatement(Statement statement) {
    if (statement != null) {
      try {
        statement.close();
      } catch (SQLException e) {
        // ignore
      }
    }
  }

  /**
   * Apply a transaction timeout.
   * @param statement a current statement
   * @throws SQLException if a database access error occurs, this method is called on a closed <code>Statement</code>
   * @since 3.4.0
   * @see StatementUtil#applyTransactionTimeout(Statement, Integer, Integer)
   */
  protected void applyTransactionTimeout(Statement statement) throws SQLException {
    StatementUtil.applyTransactionTimeout(statement, statement.getQueryTimeout(), transaction.getTimeout());
  }

  private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key, Object parameter, BoundSql boundSql) {
    if (ms.getStatementType() == StatementType.CALLABLE) {
      final Object cachedParameter = localOutputParameterCache.getObject(key);
      if (cachedParameter != null && parameter != null) {
        final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
        final MetaObject metaParameter = configuration.newMetaObject(parameter);
        for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
          if (parameterMapping.getMode() != ParameterMode.IN) {
            final String parameterName = parameterMapping.getProperty();
            final Object cachedValue = metaCachedParameter.getValue(parameterName);
            metaParameter.setValue(parameterName, cachedValue);
          }
        }
      }
    }
  }

  /**
   * [物理查询与缓存同步] 从数据库执行查询，并将结果填充至一级缓存。
   * <p>
   * 此方法通过“占位符机制”解决了嵌套查询（如关联映射）中的循环引用问题。
   * @param ms MappedStatement  映射语句
   * @param parameter 参数对象
   * @param rowBounds 分页对象
   * @param resultHandler 查询结果处理器
   * @param key 缓存键
   * @param boundSql SQL 语句
   * @return 查询结果
   */
  private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
    List<E> list;

    // 1. 【防循环引用预处理】：在一级缓存中放入执行占位符 (EXECUTION_PLACEHOLDER)
    // 作用：当发生嵌套查询且存在循环关联时（如 A 关联 B，B 又回引 A），
    // 占位符能确保子查询能够识别出“主查询正在进行中”，从而避免死循环或重复发起物理查询。
    localCache.putObject(key, EXECUTION_PLACEHOLDER);
    try {

      // 2. 【物理查询执行】：委派给具体的子类执行器实现（Simple/Reuse/BatchExecutor）， 默认Simple
      // SimpleExecutor: 简单执行器，不复用数据库连接
      // ReuseExecutor: 重用执行器，复用数据库连接
      // BatchExecutor: 批量执行器，批量处理数据库操作
      // 这是真正发起 JDBC 请求、解析 ResultSet 并将其转换为 Java List 的地方。
      list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
    } finally {
      // 3. 【状态清理】：无论查询成功与否，必须移除该 Key 对应的执行占位符
      localCache.removeObject(key);
    }

    // 4. 【结果持久化】：将真实的数据库查询结果写入一级缓存 (Local Cache)
    // 后续在同一个 SqlSession 内部发起完全一致的查询时，将直接命中缓存返回。
    localCache.putObject(key, list);

    // 5. 【存储过程处理】：如果是存储过程调用 (CALLABLE)，需要额外缓存输出参数 (Output Parameters)
    if (ms.getStatementType() == StatementType.CALLABLE) {
      localOutputParameterCache.putObject(key, parameter);
    }
    return list;
  }

  protected Connection getConnection(Log statementLog) throws SQLException {
    // 获取到了真正的 Connection 对象 ? 如果有连接池管理 在此处获取的是PooledConnection 是Connection的代理对象
    Connection connection = transaction.getConnection();
    if (statementLog.isDebugEnabled()) {
      // 创建Connection的日志代理对象
      return ConnectionLogger.newInstance(connection, statementLog, queryStack);
    } else {
      // 返回的是真正的Connection 没有走代理的方式
      return connection;
    }
  }

  @Override
  public void setExecutorWrapper(Executor wrapper) {
    this.wrapper = wrapper;
  }

  private static class DeferredLoad {

    private final MetaObject resultObject;
    private final String property;
    private final Class<?> targetType;
    private final CacheKey key;
    private final PerpetualCache localCache;
    private final ObjectFactory objectFactory;
    private final ResultExtractor resultExtractor;

    // issue #781
    public DeferredLoad(MetaObject resultObject,
                        String property,
                        CacheKey key,
                        PerpetualCache localCache,
                        Configuration configuration,
                        Class<?> targetType) {
      this.resultObject = resultObject;
      this.property = property;
      this.key = key;
      this.localCache = localCache;
      this.objectFactory = configuration.getObjectFactory();
      this.resultExtractor = new ResultExtractor(configuration, objectFactory);
      this.targetType = targetType;
    }

    public boolean canLoad() {
      return localCache.getObject(key) != null && localCache.getObject(key) != EXECUTION_PLACEHOLDER;
    }

    public void load() {
      @SuppressWarnings("unchecked")
      // we suppose we get back a List
      List<Object> list = (List<Object>) localCache.getObject(key);
      Object value = resultExtractor.extractObjectFromList(list, targetType);
      resultObject.setValue(property, value);
    }

  }

}
