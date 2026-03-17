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

import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.TransactionalCacheManager;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class CachingExecutor implements Executor {

  private final Executor delegate;
  private final TransactionalCacheManager tcm = new TransactionalCacheManager();

  public CachingExecutor(Executor delegate) {
    this.delegate = delegate;
    delegate.setExecutorWrapper(this);
  }

  @Override
  public Transaction getTransaction() {
    return delegate.getTransaction();
  }

  /**
   * [执行器关闭与缓存结算] 在会话结束时，决定暂存区数据的最终命运。
   *
   * 核心逻辑：
   * 1. 若为异常关闭（强制回滚），则丢弃所有暂存的查询结果。
   * 2. 若为正常关闭，则自动触发缓存提交，将查询结果同步至二级缓存。
   * 3. 无论缓存处理如何，最终必须释放底层的 JDBC 连接等物理资源。
   *
   * @param forceRollback 是否强制回滚（通常由程序异常或手动回滚触发）
   */
  @Override
  public void close(boolean forceRollback) {
    try {
      // 1. 【分支决策】：判断当前会话的健康状态
      if (forceRollback) {
        /*
         * 场景 A：强制回滚。
         * 逻辑：调用事务缓存管理器 (TCM) 的 rollback。
         * 结果：清空 TransactionalCache 中的暂存 Map，之前查询到的数据不会进入物理二级缓存。
         */
        tcm.rollback();
      } else {

        /*
         * 场景 B：正常结束（对应纯查询场景下的 sqlSession.close()）。
         * 逻辑：【核心入口】调用 tcm.commit() 执行最终结算。
         * 结果：将暂存区的数据物理写入二级缓存，并执行可能的缓存清空标记。
         */
        tcm.commit();
      }
    } finally {
      /*
       * 2. 【底层资源回收】：装饰器模式的链式调用。
       * 逻辑：委派给内部的 delegate（通常是 BaseExecutor 及其子类）。
       * 动作：物理关闭 JDBC 连接、清空一级缓存 (Local Cache)。
       */
      delegate.close(forceRollback);
    }
  }

  @Override
  public boolean isClosed() {
    return delegate.isClosed();
  }

  /**
   * [二级缓存更新逻辑] 确保在执行写操作前维护缓存的一致性。
   */
  @Override
  public int update(MappedStatement ms, Object parameterObject) throws SQLException {

    /*
     * 1. 【缓存清空触发】：
     * 逻辑：根据 MappedStatement 的配置（通常 DML 语句默认 flushCache="true"）
     * 物理清空当前 Namespace 下关联的所有二级缓存。
     * 目的：防止数据库数据变更后，缓存中仍残留旧（脏）数据，确保查询操作能实时获取最新值。
     */
    flushCacheIfRequired(ms);

    /*
     * 2. 【执行权下放】：
     * 逻辑：调用被装饰的内部执行器（delegate，通常是 BaseExecutor）执行真正的写操作。
     * 深度解析：进入 delegate.update 后，会进一步触发“一级缓存”的清空以及 JDBC 的物理执行。
     */
    return delegate.update(ms, parameterObject);
  }

  /**
   * [二级缓存入口] 执行查询前的准备工作：解析 SQL 并生成缓存标识。
   */
  @Override
  public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {

    // 1. 【动态 SQL 解析】：获取SQL，根据传入的参数对象，评估并生成最终的 BoundSql 实例。
    // BoundSql 包含了经过逻辑处理（如 <if>, <where>）后的 SQL 文本，以及绑定的参数映射。
    BoundSql boundSql = ms.getBoundSql(parameterObject);

    // 2. 【缓存标识构建】：为当前的查询操作创建唯一的 CacheKey。
    // MyBatis 通过 CacheKey 判断两次查询是否“完全一致”。
    // 决定 Key 唯一性的维度包括：MappedStatement ID、分页参数(RowBounds)、SQL 文本、以及每个参数的实际值。
    CacheKey key = createCacheKey(ms, parameterObject, rowBounds, boundSql);

    // 3. 进入重载方法执行二级缓存检索及后续查询逻辑
    return query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
  }

  @Override
  public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
    flushCacheIfRequired(ms);
    return delegate.queryCursor(ms, parameter, rowBounds);
  }

  /**
   * [二级缓存执行引擎] 处理二级缓存的检索、清空以及结果暂存逻辑。
   * @param ms MappedStatement 映射语句
   * @param parameterObject 查询参数
   * @param rowBounds 分页参数
   * @param resultHandler 查询结果处理器
   * @param key 缓存标识
   * @param boundSql SQL 语句
   * @return 查询结果
   */
  @Override
  public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql)
      throws SQLException {

    // 1. 【获取缓存实例】：从 MappedStatement 中获取当前命名空间（Namespace）对应的 Cache 对象。
    // 注意：Cache 对象是在解析 Mapper XML 中的 <cache> 标签时创建并存入 Configuration 的。
    Cache cache = ms.getCache();

    // 2. 【分流逻辑】：判断是否开启了二级缓存功能（即 MappedStatement 对象中是否配置了 cache 属性）
    if (cache != null) {

      // 2.1 根据配置触发缓存清空。
      // 逻辑：若该 SQL 标签配置了 flushCache="true"（通常 DML 默认为 true，SELECT 默认为 false），
      // 则在执行查询前物理清空该 Namespace 下的所有二级缓存。
      flushCacheIfRequired(ms);

      // 2.2 判断当前查询是否允许使用缓存且未指定外部 ResultHandler
      // 在 select 标签中 配置了 useCache 属性
      if (ms.isUseCache() && resultHandler == null) {
        // 存储过程安全检查：确保没有输出类型的参数（二级缓存不支持带 OUT 参数的调用）
        ensureNoOutParams(ms, boundSql);

        // 3. 【二级缓存检索】：通过事务缓存管理器 (TransactionalCacheManager) 获取数据。
        // 机制：为了保证事务隔离性，二级缓存的读写并非直接操作物理 Cache，
        // 而是经过 TransactionalCacheManager 进行事务暂存。
        @SuppressWarnings("unchecked")
        List<E> list = (List<E>) tcm.getObject(cache, key);
        if (list == null) {

          // 4. 【缓存未命中】：委派给内部执行器（Simple/Reuse/BatchExecutor）执行物理查询。
          // 此步骤会进入一级缓存（Local Cache）的检索流程。
          list = delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);

          // 5. 【暂存结果】：将从数据库/一级缓存拿到的结果放入暂存区。
          // 注意：此时数据并未真正进入物理二级缓存，只有在 sqlSession.commit() 时才会正式生效。
          tcm.putObject(cache, key, list); // issue #578 and #116
        }
        return list;
      }
    }

    // 6. 【直连模式】：若未配置 <cache> 或禁用了二级缓存，直接执行底层查询逻辑
    // 走到 SimpleExecutor | ReuseExecutor | BatchExecutor
    return delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
  }

  @Override
  public List<BatchResult> flushStatements() throws SQLException {
    return delegate.flushStatements();
  }

  @Override
  public void commit(boolean required) throws SQLException {
    delegate.commit(required);
    tcm.commit();
  }

  @Override
  public void rollback(boolean required) throws SQLException {
    try {
      delegate.rollback(required);
    } finally {
      if (required) {
        tcm.rollback();
      }
    }
  }

  private void ensureNoOutParams(MappedStatement ms, BoundSql boundSql) {
    if (ms.getStatementType() == StatementType.CALLABLE) {
      for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
        if (parameterMapping.getMode() != ParameterMode.IN) {
          throw new ExecutorException("Caching stored procedures with OUT params is not supported.  Please configure useCache=false in " + ms.getId() + " statement.");
        }
      }
    }
  }

  /**
   * CacheKey 缓存的主键 ： 要保证key的唯一性，避免冲突
   * @param ms
   * @param parameterObject
   * @param rowBounds
   * @param boundSql
   * @return
   */
  @Override
  public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
    CacheKey cacheKey = delegate.createCacheKey(ms, parameterObject, rowBounds, boundSql);
    return cacheKey;
  }

  @Override
  public boolean isCached(MappedStatement ms, CacheKey key) {
    return delegate.isCached(ms, key);
  }

  @Override
  public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
    delegate.deferLoad(ms, resultObject, property, key, targetType);
  }

  @Override
  public void clearLocalCache() {
    delegate.clearLocalCache();
  }


  /**
   * [缓存清空判定] 根据配置决定是否执行缓存失效操作。
   *
   * 此方法通过检查映射元数据中的属性，来控制二级缓存（TransactionalCache）的清理行为。
   */
  private void flushCacheIfRequired(MappedStatement ms) {
    // 1. 【获取缓存实例】：从 MappedStatement 中提取当前 Namespace 关联的 Cache 对象。
    Cache cache = ms.getCache();

    /*
     * 2. 【条件命中检查】：
     * - 条件 A: 当前 Namespace 配置了二级缓存 (<cache> 标签存在)。
     * - 条件 B: 当前 SQL 标签明确要求刷新缓存（flushCache="true"）。
     *
     * 默认约定：
     * - SELECT 标签该属性默认为 false。
     * - INSERT/UPDATE/DELETE 标签该属性默认为 true。
     */
    if (cache != null && ms.isFlushCacheRequired()) {

      /*
       * 3. 【执行事务清理】：
       * 逻辑：通过事务缓存管理器 (TCM) 执行 clear 动作。
       * 深度解析：此处并不是立刻物理擦除二级缓存，而是标记当前事务下的缓存为“待清理”状态。
       * 这种设计保证了事务的原子性——只有在 commit 时，清理动作才会同步到全局物理缓存。
       */
      tcm.clear(cache);
    }
  }

  @Override
  public void setExecutorWrapper(Executor executor) {
    throw new UnsupportedOperationException("This method should not be called");
  }

}
