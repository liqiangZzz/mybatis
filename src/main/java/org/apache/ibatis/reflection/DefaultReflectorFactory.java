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
package org.apache.ibatis.reflection;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MyBatis 反射器工厂默认实现类
 *
 * <p>提供 {@link Reflector} 对象的创建和缓存功能，是 MyBatis 反射模块的核心组件之一。
 * 通过缓存机制避免重复解析类的元数据信息，显著提升反射操作的性能。
 *
 * <p>Reflector 缓存的是类的以下元数据：
 * <ul>
 *   <li>可读属性列表（有 getter 方法的属性）</li>
 *   <li>可写属性列表（有 setter 方法的属性）</li>
 *   <li>属性名与 getter/setter 方法的映射关系</li>
 *   <li>类的默认构造器信息</li>
 * </ul>
 *
 * <p>线程安全说明：
 * <ul>
 *   <li>使用 {@link ConcurrentHashMap} 保证缓存的线程安全</li>
 *   <li>computeIfAbsent 操作是原子性的，避免重复创建</li>
 *   <li>多个线程同时请求同一个未缓存的类时，只有一个线程会执行创建操作</li>
 * </ul>
 *
 */
public class DefaultReflectorFactory implements ReflectorFactory {
  /**
   * 是否启用反射器缓存
   *
   * <p>默认值为 true，表示启用缓存。可通过 {@link #setClassCacheEnabled(boolean)} 动态修改。
   *
   * <p>启用缓存的优势：
   * <ul>
   *   <li>同一个类只解析一次，避免重复反射开销</li>
   *   <li>大幅提升 MyBatis 运行时性能，尤其是在大量实体类操作的场景</li>
   * </ul>
   *
   * <p>禁用缓存的场景：
   * <ul>
   *   <li>调试模式下需要观察反射器的实时状态</li>
   *   <li>类的结构在运行时发生动态变化（极少数场景）</li>
   *   <li>内存敏感型应用，需要减少缓存占用</li>
   * </ul>
   */
  private boolean classCacheEnabled = true;

  /**
   * Reflector 对象缓存容器
   *
   * <p>Key: Class 对象，代表需要反射操作的类
   * <br>Value: Reflector 对象，封装了该类的反射元数据
   *
   * <p>使用 ConcurrentHashMap 的原因：
   * <ul>
   *   <li>保证多线程环境下的线程安全</li>
   *   <li>读操作高并发时性能优于 Hashtable</li>
   *   <li>支持 computeIfAbsent 原子操作</li>
   * </ul>
   */
  private final ConcurrentMap<Class<?>, Reflector> reflectorMap = new ConcurrentHashMap<>();

  /**
   * 默认构造器
   *
   * <p>创建一个启用缓存（classCacheEnabled = true）的反射器工厂实例。
   * 也可通过 {@link #setClassCacheEnabled(boolean)} 在运行时修改缓存策略。
   */
  public DefaultReflectorFactory() {
  }

  /**
   * 判断当前是否启用了 Reflector 缓存
   *
   * @return true 表示启用缓存，false 表示禁用缓存
   */
  @Override
  public boolean isClassCacheEnabled() {
    return classCacheEnabled;
  }

  /**
   * 设置是否启用 Reflector 缓存
   *
   * <p>注意：该方法的调用时机通常应在 MyBatis 初始化阶段。
   * 在运行时频繁切换缓存状态可能导致不可预期的结果。
   *
   * @param classCacheEnabled true 表示启用缓存，false 表示禁用缓存
   */
  @Override
  public void setClassCacheEnabled(boolean classCacheEnabled) {
    this.classCacheEnabled = classCacheEnabled;
  }

  /**
   * 获取指定类的 Reflector 对象
   *
   * <p>核心逻辑：
   * <ul>
   *   <li>如果缓存已启用，从缓存中获取 Reflector 对象（不存在则创建并缓存）</li>
   *   <li>如果缓存未启用，直接创建新的 Reflector 对象（不存入缓存）</li>
   * </ul>
   *
   * <p>computeIfAbsent 的原子性保证：
   * <pre>
   * // 等价于以下操作，但保证了原子性
   * Reflector reflector = reflectorMap.get(type);
   * if (reflector == null) {
   *     reflector = new Reflector(type);
   *     reflectorMap.put(type, reflector);
   * }
   * return reflector;
   * </pre>
   *
   * @param type 需要反射操作的 Class 对象
   * @return 该 Class 对应的 Reflector 实例
   */
  @Override
  public Reflector findForClass(Class<?> type) {

    // 场景1：启用缓存 - 从缓存中获取，缓存未命中时创建并存入缓存
    if (classCacheEnabled) {
      // computeIfAbsent 方法：
      // - 如果缓存中已存在 type 对应的 Reflector，直接返回
      // - 如果不存在，执行 Reflector::new 创建新对象并存入缓存
      // - 整个过程是原子操作，线程安全
      return reflectorMap.computeIfAbsent(type, Reflector::new);
    } else {
      // 场景2：禁用缓存 - 直接创建新的 Reflector 对象
      return new Reflector(type);
    }
  }

}
