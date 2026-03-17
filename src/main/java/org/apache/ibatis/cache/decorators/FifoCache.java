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

import java.util.Deque;
import java.util.LinkedList;

import org.apache.ibatis.cache.Cache;

/**
 * [FIFO 缓存装饰器] 采用“先进先出”算法维护缓存容量。
 *
 * 核心原理：
 * 内部维护一个双端队列（Deque），记录 Key 存入的先后顺序。
 * 当缓存数量达到预设上限（Size）时，优先移除最早存入的 Key。
 *
 * FIFO (first in, first out) cache decorator.
 * @author Clinton Begin
 */
public class FifoCache implements Cache {

  // 被装饰的物理存储对象（通常是 PerpetualCache）
  private final Cache delegate;

  // 顺序记录容器：使用双端队列记录 Key 的存入顺序。
  // Deque 的实现类 LinkedList 支持高效的头尾操作。
  private final Deque<Object> keyList;

  // 缓存容量上限，默认为 1024
  private int size;

  public FifoCache(Cache delegate) {
    this.delegate = delegate;
    this.keyList = new LinkedList<>();
    this.size = 1024; // 默认是1024个
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  public void setSize(int size) {
    this.size = size;
  }

  /**
   * 存入对象。
   * 逻辑：先通过 cycleKeyList 维护存入顺序并执行必要的淘汰，再执行物理存储。
   */
  @Override
  public void putObject(Object key, Object value) {
    // 1. 维护 Key 队列，确保容量不超标
    cycleKeyList(key); // 检测并清理缓存
    // 2. 将数据写入物理缓存 （PerpetualCache ）
    delegate.putObject(key, value);
  }

  @Override
  public Object getObject(Object key) {
    // 直接委派，不涉及顺序变动（FIFO 只关心存入顺序，不关心访问顺序）
    return delegate.getObject(key);
  }

  @Override
  public Object removeObject(Object key) {
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    // 同步清空物理存储和顺序队列
    delegate.clear();
    keyList.clear();
  }

  /**
   * 核心淘汰算法：维护 Key 队列。
   */
  private void cycleKeyList(Object key) {
    // 1. 将新存入的 Key 添加到队列末尾
    keyList.addLast(key);

    // 2. 检查容量是否超过阈值
    if (keyList.size() > size) {

      // 3. 【先进先出逻辑】：移除队列头部的元素（即最早存入的 Key）
      Object oldestKey = keyList.removeFirst();
      // 4. 从物理缓存中执行真实的删除操作
      delegate.removeObject(oldestKey);
    }
  }

}
