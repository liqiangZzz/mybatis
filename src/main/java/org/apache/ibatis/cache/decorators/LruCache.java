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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.ibatis.cache.Cache;

/**
 * [LRU 缓存装饰器] 采用“最近最少使用”算法维护缓存容量。
 *
 * 核心原理：
 * 内部利用 LinkedHashMap 的“访问顺序”特性。每当一个 Key 被访问（获取或存入），
 * 它就会被移动到链表的末尾。当缓存达到上限时，链表头部的元素（最久未访问）将被移除。
 *
 * Lru (least recently used) cache decorator.
 * @author Clinton Begin
 */
public class LruCache implements Cache {

  // 被装饰的物理存储对象
  private final Cache delegate;
  // 辅助容器：仅用于记录 Key 的访问顺序，不存储真实的 Value
  private Map<Object, Object> keyMap;

  // 记录即将被剔除的“最老”Key
  // 最近最少被使用的key
  private Object eldestKey;

  public LruCache(Cache delegate) {
    this.delegate = delegate;
    // 默认缓存 1024 个对象
    setSize(1024);
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
   * 设置缓存上限，并初始化存储顺序逻辑。
   */
  public void setSize(final int size) {
    /*
     * 核心实现：LinkedHashMap
     * 参数含义：
     * 1. size: 初始容量
     * 2. .75F: 负载因子
     * 3. true: 【关键】开启“访问顺序”模式。调用 get 方法会把元素移动到链表末尾。
     */
    keyMap = new LinkedHashMap<Object, Object>(size, .75F, true) {
      private static final long serialVersionUID = 4267176411845948333L;

      /**
       * 钩子方法：当执行 put 操作后，判断是否需要移除最老的数据。
       */
      @Override
      protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
        boolean tooBig = size() > size;
        if (tooBig) {
          // 如果超出上限，将链表头部的 Key 暂存在 eldestKey 中，等待物理移除
          eldestKey = eldest.getKey();
        }
        return tooBig;
      }
    };
  }

  @Override
  public void putObject(Object key, Object value) {
    // 1. 在底层物理缓存中存入数据
    delegate.putObject(key, value);
    // 2. 更新 Key 访问记录，并执行淘汰逻辑
    cycleKeyList(key);
  }

  @Override
  public Object getObject(Object key) {

    // 1. 【关键】调用 keyMap.get 触发 LinkedHashMap 内部节点的排序移动
    // 调用get(key)方法时，会将key移动到链表的尾部，可以通过这个特性找到最近最少使用的key，应该是链表的head指向的key
    // 使当前被访问的 Key 成为“最新”元素
    keyMap.get(key);

    // 2. 从底层缓存获取真实数据
    return delegate.getObject(key);
  }

  @Override
  public Object removeObject(Object key) {
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    delegate.clear();
    keyMap.clear();
  }

  /**
   * 维护 Key 的淘汰逻辑。
   */
  private void cycleKeyList(Object key) {
    // 记录当前 Key 的存入动作
    keyMap.put(key, key);

    // 检查是否有由于达到上限而产生的待删除 Key
    if (eldestKey != null) {
      // 从物理缓存（PerpetualCache）中执行真正的删除
      delegate.removeObject(eldestKey);
      // 清空暂存标记
      eldestKey = null;
    }
  }

}
