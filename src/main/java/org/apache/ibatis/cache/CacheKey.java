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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import org.apache.ibatis.reflection.ArrayUtil;

/**
 * @author Clinton Begin
 */
public class CacheKey implements Cloneable, Serializable {

  private static final long serialVersionUID = 1146682552656046210L;

  public static final CacheKey NULL_CACHE_KEY = new CacheKey(){
    @Override
    public void update(Object object) {
      throw new CacheException("Not allowed to update a null cache key instance.");
    }
    @Override
    public void updateAll(Object[] objects) {
      throw new CacheException("Not allowed to update a null cache key instance.");
    }
  };

  // 乘法因子（默认 37），用于在计算哈希值时增加离散度，减少碰撞风险。
  private static final int DEFAULT_MULTIPLIER = 37;
  // 默认的hash值
  private static final int DEFAULT_HASHCODE = 17;

  private final int multiplier; // 乘法因子
  private int hashcode; // hash值

  // 基于加法算法生成的校验和，作为哈希碰撞后的第二层校验依据。
  private long checksum;
  // 参与计算的要素个数。
  private int count;
  // 8/21/2017 - Sonarlint flags this as needing to be marked transient.  While true if content is not serializable, this is not always true and thus should not be marked transient.
  //有序存储所有参与计算的对象（如 SQL、参数值等），用于最后的强制相等性比较 (存放了更新的对象信息)。
  private List<Object> updateList;

  public CacheKey() {
    this.hashcode = DEFAULT_HASHCODE;
    this.multiplier = DEFAULT_MULTIPLIER;
    this.count = 0;
    this.updateList = new ArrayList<>();
  }

  public CacheKey(Object[] objects) {
    this();
    updateAll(objects);
  }

  public int getUpdateCount() {
    return updateList.size();
  }

  /**
   * [核心属性更新] 向 CacheKey 中注入新的判定要素（如 SQL、参数值等）。
   * 此方法通过累加计算哈希值、校验和以及记录要素，保证了 CacheKey 的唯一性。
   */
  public void update(Object object) {

    // 1. 获取要素的原始哈希值。若为 null 则取固定值 1；
    // 针对数组类型，使用 ArrayUtil 计算深度哈希。
    int baseHashCode = object == null ? 1 : ArrayUtil.hashCode(object);

    // 2. 更新要素计数器
    count++;

    // 3. 计算校验和 (checksum)：采用简单的加法算法，作为哈希判定的第二层保障
    checksum += baseHashCode;

    // 4. 权重处理：将原始哈希值与当前要素顺序（count）挂钩，防止由于参数顺序颠倒导致的哈希碰撞
    baseHashCode *= count;

    // 5. 计算最终哈希值 (hashcode)：采用乘法哈希算法。
    // 公式：新哈希 = 乘法因子(37) * 旧哈希 + 权重哈希
    // 初始 hashcode 值为 17。
    hashcode = multiplier * hashcode + baseHashCode;

    // 6. 将原始对象存入列表，用于后续发生哈希碰撞时的深度对比
    updateList.add(object);
  }

  public void updateAll(Object[] objects) {
    for (Object o : objects) {
      update(o);
    }
  }

  /**
   * [高效判等逻辑] 判断两个 CacheKey 是否指向同一个查询。
   *
   * 性能优化核心：采用了分层判断机制（Fail-Fast），先对比轻量级的数值，
   * 只有在数值完全一致（可能发生哈希碰撞）时才执行耗时的列表遍历。
   */
  @Override
  public boolean equals(Object object) {
    // 1. 内存地址检查：如果是同一个实例，直接返回 true
    if (this == object) {
      return true;
    }
    // 2. 类型检查：若目标对象不属于 CacheKey 类型，直接返回 false
    if (!(object instanceof CacheKey)) {
      return false;
    }

    final CacheKey cacheKey = (CacheKey) object;

    // 3. 【分层判定 A】：对比乘法哈希值。若不等，必定不是同一个查询
    if (hashcode != cacheKey.hashcode) {
      return false;
    }

    // 4. 【分层判定 B】：对比加法校验和。用于解决哈希碰撞带来的误判
    if (checksum != cacheKey.checksum) {
      return false;
    }

    // 5. 【分层判定 C】：对比要素个数。要素个数不等，必定不同
    if (count != cacheKey.count) {
      return false;
    }

    // 6. 【分层判定 D】：深度全量对比。
    // 如果上述数值属性完全相等，说明可能遇到了极低概率的哈希碰撞。
    // 此时遍历 updateList，逐一调用要素对象的 equals 方法进行物理对比，确保结果 100% 准确。
    for (int i = 0; i < updateList.size(); i++) {
      Object thisObject = updateList.get(i);
      Object thatObject = cacheKey.updateList.get(i);
      // 使用工具类执行相等性比较（支持数组深度比较）
      if (!ArrayUtil.equals(thisObject, thatObject)) {
        return false;
      }
    }
    // 所有层级校验全部通过
    return true;
  }

  @Override
  public int hashCode() {
    return hashcode;
  }

  @Override
  public String toString() {
    StringJoiner returnValue = new StringJoiner(":");
    returnValue.add(String.valueOf(hashcode));
    returnValue.add(String.valueOf(checksum));
    updateList.stream().map(ArrayUtil::toString).forEach(returnValue::add);
    return returnValue.toString();
  }

  @Override
  public CacheKey clone() throws CloneNotSupportedException {
    CacheKey clonedCacheKey = (CacheKey) super.clone();
    clonedCacheKey.updateList = new ArrayList<>(updateList);
    return clonedCacheKey;
  }

}
