/**
 * Copyright 2009-2026 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.cache.impl;

import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;

/**
 * [基础缓存实现] 充当装饰器模式中的“被装饰对象”。
 *
 * 它是 MyBatis 缓存体系中唯一的物理存储节点，内部直接封装 HashMap
 * 提供基础的内存存取能力。其余所有的 Cache 实现类均为功能增强型的装饰器。
 * <p>
 * 我们还要考虑很多实际的缓存使用情况。
 *   1. 缓存占用了太多的内存之后的淘汰策略问题
 *   2. 缓存是否同步
 *   ...
 * <p>
 *   PerpetualCache 太简单了，我们需要对他做增强处理 --> 代理模式
 *   1.缓存数据淘汰机制
 *   2.缓存数据的存放机制
 *   3.缓存数据添加是否同步【阻塞】
 *   4.缓存对象是否做同步处理
 *   .....
 *   ---> 装饰者模式
 * @author Clinton Begin
 */
public class PerpetualCache implements Cache {

  // Cache 实例的唯一标识，通常对应 Mapper 的 Namespace
  private final String id;

  // 物理存储媒介：使用普通的 HashMap 存储缓存数据
  private final Map<Object, Object> cache = new HashMap<>();

  public PerpetualCache(String id) {
    this.id = id;
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public int getSize() {
    return cache.size();
  }

  /**
   * 将数据物理写入 HashMap。
   */
  @Override
  public void putObject(Object key, Object value) {
    cache.put(key, value);
  }

  /**
   * 从 HashMap 中读取数据。
   */
  @Override
  public Object getObject(Object key) {
    return cache.get(key);
  }

  /**
   * 从 HashMap 中物理移除指定键值。
   */
  @Override
  public Object removeObject(Object key) {
    return cache.remove(key);
  }

  /**
   * 清空内存中的所有数据。
   */
  @Override
  public void clear() {
    cache.clear();
  }


  /**
   * [判等逻辑] 只要两个 Cache 的 ID 相同，即认为它们是同一个缓存实例。
   */
  @Override
  public boolean equals(Object o) {
    if (getId() == null) {
      throw new CacheException("Cache instances require an ID.");
    }
    if (this == o) {
      return true;
    }
    if (!(o instanceof Cache)) {
      return false;
    }

    Cache otherCache = (Cache) o;
    // 只关心ID
    return getId().equals(otherCache.getId());
  }

  /**
   * [哈希计算] 基于 ID 计算哈希值，确保 ID 是唯一索引依据。
   */
  @Override
  public int hashCode() {
    if (getId() == null) {
      throw new CacheException("Cache instances require an ID.");
    }
    // 只关心ID
    return getId().hashCode();
  }

}
