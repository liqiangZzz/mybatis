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

import java.util.concurrent.locks.ReadWriteLock;

/**
 * [缓存核心协议] MyBatis 缓存体系的顶层接口。
 *
 * 此接口定义了数据存储、检索及清理的标准行为。MyBatis 利用“装饰器模式”通过
 * 实现此接口来扩展缓存功能（如 LRU、FIFO、日志统计、事务暂存等）。
 *
 * <p>
 * SPI for cache providers.
 * <p>
 * One instance of cache will be created for each namespace.
 * <p>
 * The cache implementation must have a constructor that receives the cache id as an String parameter.
 * <p>
 * MyBatis will pass the namespace as id to the constructor.
 *
 * <pre>
 * public MyCache(final String id) {
 *  if (id == null) {
 *    throw new IllegalArgumentException("Cache instances require an ID");
 *  }
 *  this.id = id;
 *  initialize();
 * }
 * </pre>
 *
 * @author Clinton Begin
 */

public interface Cache {

  /**
   * 获取缓存实例的唯一标识符。
   * 对应 Mapper XML 的 namespace 全类名。
   * @return The identifier of this cache
   */
  String getId();

  /**
   * 将查询结果存入缓存。
   * @param key Can be any object but usually it is a {@link CacheKey} 缓存键，通常是一个生成的 {@link CacheKey} 对象。
   * @param value The result of a select.  查询结果对象（SELECT 语句的返回结果）。
   */
  void putObject(Object key, Object value);

  /**
   * 从缓存中检索指定的对象。
   * @param key The key 缓存键。
   * @return The object stored in the cache. 存储的对象；若未命中则返回 null。
   */
  Object getObject(Object key);

  /**
   * [特殊操作] 从物理缓存中移除指定的键值对。
   *
   * 核心逻辑：自 3.3.0 版本起，此方法主要在【事务回滚 (Rollback)】期间被调用。
   * 作用：用于释放“阻塞型缓存”在未命中时对该 Key 加的锁，防止死锁或错误的 Null 占位。
   *
   * As of 3.3.0 this method is only called during a rollback
   * for any previous value that was missing in the cache.
   * This lets any blocking cache to release the lock that
   * may have previously put on the key.
   * A blocking cache puts a lock when a value is null
   * and releases it when the value is back again.
   * This way other threads will wait for the value to be
   * available instead of hitting the database.
   *
   * @param key The key 缓存键。
   * @return Not used
   */
  Object removeObject(Object key);

  /**
   * 清空当前缓存实例中的所有数据（全量失效）。
   * Clears this cache instance.
   */
  void clear();

  /**
   * 获取当前缓存中存储的元素总数。
   * Optional. This method is not called by the core.
   * @return The number of elements stored in the cache (not its capacity).
   */
  int getSize();

  /**
   * [可选操作] 获取该缓存关联的读写锁。
   *
   * 注意：自 3.2.6 版本起，MyBatis 核心引擎不再主动调用此方法。
   * 缓存的线程安全逻辑应由缓存实现类内部自行维护。
   *
   * Optional. As of 3.2.6 this method is no longer called by the core.
   * <p>
   * Any locking needed by the cache must be provided internally by the cache provider.
   * @return A ReadWriteLock
   */
  default ReadWriteLock getReadWriteLock() {
    return null;
  }

}
