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
package org.apache.ibatis.cache.decorators;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * The 2nd level cache transactional buffer.
 * <p>
 * This class holds all cache entries that are to be added to the 2nd level cache during a Session.
 * Entries are sent to the cache when commit is called or discarded if the Session is rolled back.
 * Blocking cache support has been added. Therefore any get() that returns a cache miss
 * will be followed by a put() so any lock associated with the key can be released.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class TransactionalCache implements Cache {

  private static final Log log = LogFactory.getLog(TransactionalCache.class);

  private final Cache delegate;
  private boolean clearOnCommit;
  private final Map<Object, Object> entriesToAddOnCommit;
  private final Set<Object> entriesMissedInCache;

  public TransactionalCache(Cache delegate) {
    this.delegate = delegate;
    this.clearOnCommit = false;
    this.entriesToAddOnCommit = new HashMap<>();
    this.entriesMissedInCache = new HashSet<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  @Override
  public Object getObject(Object key) {
    // issue #116
    Object object = delegate.getObject(key);
    if (object == null) {
      entriesMissedInCache.add(key);
    }
    // issue #146
    if (clearOnCommit) {
      return null;
    } else {
      return object;
    }
  }

  @Override
  public void putObject(Object key, Object object) {
    entriesToAddOnCommit.put(key, object);
  }

  @Override
  public Object removeObject(Object key) {
    return null;
  }

  @Override
  public void clear() {
    clearOnCommit = true;
    entriesToAddOnCommit.clear();
  }


  /**
   * [事务提交] 正式结算当前事务内的所有缓存变更。
   *
   * 此方法由 CachingExecutor 在执行 commit() 或 close() 时调用，
   * 实现了二级缓存与数据库事务的最终一致性。
   */
  public void commit() {
    // 1. 【执行物理清空】：若事务期间执行过 DML 操作（标记了 clearOnCommit=true）
    // 此时调用物理缓存对象（delegate）的 clear 方法，抹除该命名空间的所有旧缓存。
    if (clearOnCommit) {
      delegate.clear();
    }
    // 2. 【数据正式入库】：将当前事务内查询到的新结果，物理写入真正的二级缓存中。
    flushPendingEntries();
    // 3. 【状态复位】：清空内部暂存 Map，为该 SqlSession 的下一次事务（若有）做准备。
    reset();
  }

  /**
   * [事务回滚] 撤销当前事务在缓存层面的所有操作。
   *
   * 当数据库事务回滚或会话异常关闭时触发，确保缓存状态回归到事务开始前的状态。
   */
  public void rollback() {
    // 1. 【物理锁释放】：处理那些在缓存中未命中的记录
    // 目的：通知底层缓存实现类，放弃对这些 Key 的“占位”或锁定。
    unlockMissedEntries();
    // 2. 【状态重置】：清空所有暂存的中间数据，恢复初始环境
    reset();
  }

  /**
   * [环境复位] 彻底清空当前会话的事务缓存标志位与暂存容器。
   */
  private void reset() {
    // 1. 撤销写操作标记：该 Namespace 的二级缓存不再处于“待清空”状态
    clearOnCommit = false;

    // 2. 丢弃查询结果：清空本次事务中查询到的所有待提交数据
    entriesToAddOnCommit.clear();

    // 3. 丢弃未命中记录：清空本次事务中所有查询失败的记录统计
    entriesMissedInCache.clear();
  }


  /**
   * 将一级缓存的数据写入二级缓存
   */
  private void flushPendingEntries() {
    // 1. 【处理成功查询的结果】：
    // 遍历暂存区 entriesToAddOnCommit。
    // delegate 是真正的物理缓存存储（如 PerpetualCache 实例）。
    for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
      delegate.putObject(entry.getKey(), entry.getValue());
    }

    // 2. 【处理未命中的占位符】：
    // 遍历缓存未命中记录集 entriesMissedInCache。
    // 目的：为了防止由于缓存未命中导致的频繁数据库穿透。
    for (Object entry : entriesMissedInCache) {
      // 如果该未命中的 Key 并没有在刚才的 entriesToAddOnCommit 中产生有效结果
      if (!entriesToAddOnCommit.containsKey(entry)) {
        // 向物理缓存存入一个空值（Null）占位，
        // 这种设计能有效防止“缓存击穿”或“缓存穿透”问题。
        delegate.putObject(entry, null);
      }
    }
  }

  /**
   * [未命中项清理] 遍历 entriesMissedInCache，从物理缓存中移除这些 Key。
   */
  private void unlockMissedEntries() {
    for (Object entry : entriesMissedInCache) {
      try {
        /*
         * 核心逻辑：调用物理缓存对象（delegate）的 removeObject。
         *
         * 深度解析：
         * 某些高级缓存插件（如 Redis、Hazelcast）在发生 Cache Miss 时，可能会设置
         * 一个“锁”或“占位符”来防止缓存击穿。
         * 在回滚时，必须显式删除这些 Key，以释放潜在的资源锁定或错误的 Null 占位。
         */
        delegate.removeObject(entry);
      } catch (Exception e) {
        // 防御性编程：记录警告日志。
        // 理由：MyBatis 无法预知第三方缓存适配器对 remove 方法的兼容性，
        // 即使删除失败，也不应中断主流程。
        log.warn("Unexpected exception while notifiying a rollback to the cache adapter. "
            + "Consider upgrading your cache adapter to the latest version. Cause: " + e);
      }
    }
  }

}
