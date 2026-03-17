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
package org.apache.ibatis.cache;

import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.cache.decorators.TransactionalCache;

/**
 * @author Clinton Begin
 */
public class TransactionalCacheManager {

  private final Map<Cache, TransactionalCache> transactionalCaches = new HashMap<>();

  public void clear(Cache cache) {
    getTransactionalCache(cache).clear();
  }

  public Object getObject(Cache cache, CacheKey key) {
    Object object = getTransactionalCache(cache).getObject(key);
    return object;
  }

  public void putObject(Cache cache, CacheKey key, Object value) {
    getTransactionalCache(cache).putObject(key, value);
  }

  /**
   * [全局事务提交] 批量提交当前会话（SqlSession）涉及的所有命名空间的缓存变更。
   *
   * 在一次会话中，开发者可能操作了多个 Mapper（对应多个 Namespace）。
   * 每个 Namespace 都有自己独立的 TransactionalCache，此方法负责统一触发它们的结算逻辑。
   */
  public void commit() {
    // 1. 遍历内部维护的 Map 集合：Map<Cache, TransactionalCache>
    // transactionalCaches 存储了本次会话中所有“被触达过”的二级缓存暂存区。
    for (TransactionalCache txCache : transactionalCaches.values()) {
      /*
       * 2. 执行具体暂存区的提交：
       * 调用 TransactionalCache.commit()，将该命名空间下暂存的查询结果
       * 物理写入到底层的 Cache 对象中，并根据标记执行缓存清空。
       */
      txCache.commit();
    }
  }

  /**
   * [全局事务回滚] 批量撤回当前会话涉及的所有缓存操作。
   */
  public void rollback() {
    // 1. 遍历所有涉及的事务缓存暂存区
    for (TransactionalCache txCache : transactionalCaches.values()) {
      /*
       * 2. 执行回滚：
       * 调用 TransactionalCache.rollback()，丢弃本次事务中所有的查询暂存数据，
       * 并撤销之前的 clear 清空标记，确保物理缓存不受到任何污染。
       */
      txCache.rollback();
    }
  }

  private TransactionalCache getTransactionalCache(Cache cache) {
    return transactionalCaches.computeIfAbsent(cache, TransactionalCache::new);
  }

}
