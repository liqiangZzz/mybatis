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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;

/**
 * [阻塞型缓存装饰器] 旨在解决“缓存击穿”问题的悲观锁实现。
 *
 * 核心原理：
 * 当一个线程在缓存中未获取到指定 Key 的数据时，会立即对该 Key 加锁。
 * 后续所有请求同一个 Key 的线程都将进入阻塞状态，直到第一个线程从数据库查到结果并回填到缓存为止。
 *
 * 注意：这是一种较重的同步机制，在高并发下可能会增加响应延迟。
 *
 * Simple blocking decorator
 * Simple and inefficient version of EhCache's BlockingCache decorator.
 * It sets a lock over a cache key when the element is not found in cache.
 * This way, other threads will wait until this element is filled instead of hitting the database.
 *
 * @author Eduardo Macarron
 *
 */
public class BlockingCache implements Cache {

  // 获取锁的超时时长（毫秒）
  private long timeout;

  // 被装饰的物理存储节点（如 PerpetualCache）
  private final Cache delegate;

  // 锁池：为每一个缓存 Key 维护一个独立的重入锁 (ReentrantLock)
  private final ConcurrentHashMap<Object, ReentrantLock> locks;

  public BlockingCache(Cache delegate) {
    // 被装饰的 Cache 对象
    this.delegate = delegate;
    this.locks = new ConcurrentHashMap<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }


  /**
   * 将数据写回缓存，并释放对应的 Key 锁。
   * 此方法通常在数据库查询结束、执行 tcm.putObject 时触发。
   */
  @Override
  public void putObject(Object key, Object value) {
    try {
      // 执行 被装饰的 Cache 中的方法
      delegate.putObject(key, value);
    } finally {
      // 无论写入成功与否，必须释放该 Key 的阻塞锁，唤醒其他等待线程
      releaseLock(key);
    }
  }

  /**
   * 获取缓存对象。
   * 逻辑：
   * 1. 尝试对 Key 加锁（若已被锁定则阻塞等待）。
   * 2. 检查底层缓存。
   * 3. 若命中缓存：立即释放锁，返回数据。
   * 4. 若未命中：【关键】继续持有锁，直到 putObject 或触发事务回滚。
   */
  @Override
  public Object getObject(Object key) {
    // 第一步：获取锁,竞争锁
    acquireLock(key);
    // 获取缓存数据
    Object value = delegate.getObject(key);
    // 有数据就释放掉锁，否则继续持有锁
    if (value != null) {
      // 场景 A：数据已存在，直接释放锁，不阻塞后续请求
      releaseLock(key);
    }
    // 场景 B：数据不存在，此时线程带着锁返回，后续线程将在此 Key 的锁上排队
    return value;
  }

  /**
   * [补偿操作] 释放锁。
   * 此方法在 Cache 接口中具有特殊地位，专门用于事务回滚阶段。
   * 如果数据库查询异常，MyBatis 会通过此方法释放 getObject 时产生的死锁。
   */
  @Override
  public Object removeObject(Object key) {
    // despite of its name, this method is called only to release locks
    releaseLock(key);
    return null;
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  /**
   * 从锁池中获取或创建一个 Key 级别的锁。
   */
  private ReentrantLock getLockForKey(Object key) {
    return locks.computeIfAbsent(key, k -> new ReentrantLock());
  }

  /**
   * 核心阻塞逻辑：获取锁。
   */
  private void acquireLock(Object key) {
    Lock lock = getLockForKey(key);
    if (timeout > 0) {
      try {
        // 带超时的获取，防止线程无限期死等
        boolean acquired = lock.tryLock(timeout, TimeUnit.MILLISECONDS);
        if (!acquired) {
          throw new CacheException("Couldn't get a lock in " + timeout + " for the key " +  key + " at the cache " + delegate.getId());
        }
      } catch (InterruptedException e) {
        throw new CacheException("Got interrupted while trying to acquire lock for key " + key, e);
      }
    } else {
      // 阻塞式获取
      lock.lock();
    }
  }

  /**
   * 释放锁：确保只有持有锁的线程才能执行解锁。
   */
  private void releaseLock(Object key) {
    ReentrantLock lock = locks.get(key);
    if (lock.isHeldByCurrentThread()) {
      lock.unlock();
    }
  }

  public long getTimeout() {
    return timeout;
  }

  public void setTimeout(long timeout) {
    this.timeout = timeout;
  }
}
