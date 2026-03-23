/**
 * MyBatis 反射对象工厂接口
 *
 * <p>负责创建和缓存 {@link Reflector} 对象，Reflector 是 MyBatis 反射模块的核心类，
 * 用于封装类的元数据信息（属性、方法、构造器等），提供高效的属性访问能力。
 *
 * <p>Reflector 的作用：
 * <ul>
 *   <li>缓存类的所有属性名称（getter/setter 对应的属性）</li>
 *   <li>缓存属性对应的 getter/setter 方法</li>
 *   <li>缓存类的默认构造器</li>
 *   <li>提供通过属性名读写属性值的能力</li>
 * </ul>
 *
 * <p>设计目的：
 * <ul>
 *   <li>性能优化：反射操作开销较大，通过缓存复用 Reflector 对象避免重复解析类元数据</li>
 *   <li>统一管理：集中管理所有类的反射信息，便于维护和配置</li>
 * </ul>
 * <p>
 *    Copyright 2009-2026 the original author or authors.
 * <p>
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 * <p>
 *       http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection;

public interface ReflectorFactory {

  /**
   * 判断是否启用了 Reflector 缓存
   *
   * <p>当缓存启用时，同一个 Class 只会被解析一次，后续请求直接返回缓存的 Reflector 对象；
   * 当缓存禁用时，每次请求都会创建新的 Reflector 对象。
   *
   * <p>默认实现（DefaultReflectorFactory）中，此配置默认为 true（启用缓存）。
   *
   * @return true 表示启用缓存，false 表示禁用缓存
   */
  boolean isClassCacheEnabled();

  /**
   * 设置是否启用 Reflector 缓存
   *
   * <p>通常在 MyBatis 初始化时调用，通过配置文件或代码动态设置。
   *
   * <p>注意：修改缓存配置通常应在工厂初始化时进行，运行期间修改可能导致不可预期的行为。
   *
   * @param classCacheEnabled true 表示启用缓存，false 表示禁用缓存
   */
  void setClassCacheEnabled(boolean classCacheEnabled);

  /**
   * 获取指定 Class 对应的 Reflector 对象
   *
   * <p>该方法会先从缓存中查找，如果缓存存在则直接返回；如果缓存不存在或缓存被禁用，
   * 则会创建新的 Reflector 对象并可选地存入缓存。
   *
   * <p>创建 Reflector 的过程包括：
   * <ol>
   *   <li>解析类的所有 getter/setter 方法，提取属性名称</li>
   *   <li>缓存属性名与 getter/setter 方法的映射关系</li>
   *   <li>检查并缓存类的默认构造器</li>
   *   <li>处理属性类型、泛型信息等</li>
   * </ol>
   *
   * @param type 需要反射操作的 Class 对象
   * @return 该 Class 对应的 Reflector 实例
   */
  Reflector findForClass(Class<?> type);
}
