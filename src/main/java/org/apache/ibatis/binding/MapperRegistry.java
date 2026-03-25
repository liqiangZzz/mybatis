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
package org.apache.ibatis.binding;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

/**
 * [Mapper 接口注册表] 负责管理 Mapper 接口与对应代理工厂的映射关系。
 * <p>
 * 核心职能：
 * 1. 存储：维护一个 Map，记录接口类型与 MapperProxyFactory 的对应关系。
 * 2. 注册：提供单条或包扫描的方式，将接口及其对应的注解/XML 信息载入系统。
 * 3. 生产：在运行期根据接口类型，利用对应的工厂生产出具体的动态代理实例。
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 */
public class MapperRegistry {

  // 配置对象，记录了如 typeAliases、environments 等信息
  private final Configuration config;

  /**
   * 【核心账本】：存储所有已注册 Mapper 接口的元数据。
   * Key: 接口的 Class 对象 (如 UserMapper.class)
   * Value: 专门为该接口生产代理对象的工厂实例 (MapperProxyFactory)
   */
  private final Map<Class<?>, MapperProxyFactory<?>> knownMappers = new HashMap<>();

  public MapperRegistry(Configuration config) {
    this.config = config;
  }


  /**
   * [运行期：生产代理实例] 从注册表中获取 Mapper 接口的实现类（动态代理对象）。
   *
   * @param type       需要获取的接口类型
   * @param sqlSession 当前活跃的会话对象
   * @return 实现了该接口的代理对象（$ProxyN）
   */
  @SuppressWarnings("unchecked")
  public <T> T getMapper(Class<T> type, SqlSession sqlSession) {
    // 1. 快速寻址：根据接口类型在 knownMappers 中找到对应的专属工厂 MapperProxyFactory 对象
    // 该工厂在解析 XML/接口注解阶段就已经被创建并存入 Map
    final MapperProxyFactory<T> mapperProxyFactory = (MapperProxyFactory<T>) knownMappers.get(type);
    if (mapperProxyFactory == null) {
      throw new BindingException("Type " + type + " is not known to the MapperRegistry.");
    }
    try {

      // 2. 委派生产：利用工厂实例化一个全新的代理对象，并注入当前的 sqlSession
      // 这里的 newInstance 内部使用了 JDK 动态代理技术
      return mapperProxyFactory.newInstance(sqlSession);
    } catch (Exception e) {
      throw new BindingException("Error getting mapper instance. Cause: " + e, e);
    }
  }

  public <T> boolean hasMapper(Class<T> type) {
    return knownMappers.containsKey(type);
  }


  /**
   * [初始化期：注册单体接口] 将一个接口及其相关的映射信息存入注册表。
   */
  public <T> void addMapper(Class<T> type) {
    // 1. 严格校验：MyBatis 的 Mapper 驱动必须基于“接口”
    if (type.isInterface()) {
      // 2. 重复性检查：防止同一个接口被解析多次导致冲突
      if (hasMapper(type)) {
        throw new BindingException("Type " + type + " is already known to the MapperRegistry.");
      }
      boolean loadCompleted = false;
      try {

        // 3. 【核心动作】：为该接口创建一个专属的代理工厂，并存入 Map 容器。
        // 这个工厂后续会持续服务于 sqlSession.getMapper(type) 操作，提高代理对象生成的效率。
        knownMappers.put(type, new MapperProxyFactory<>(type));
        // It's important that the type is added before the parser is run
        // otherwise the binding may automatically be attempted by the
        // mapper parser. If the type is already known, it won't try.

        // 4. 【注解补全】：在注册接口的同时，立即启动注解解析器。
        // 解析方法上的 @Select, @Update 等注解，并将解析出的 SQL 注册到全局 Configuration 中。
        // 注意：先存入 knownMappers 再解析是为了防止在解析关联关系时发生死循环。
        MapperAnnotationBuilder parser = new MapperAnnotationBuilder(config, type);
        parser.parse();
        loadCompleted = true;
      } finally {
        // 5. 容错处理：如果解析过程中发生异常，从注册表中撤销该接口，保证环境纯净
        if (!loadCompleted) {
          knownMappers.remove(type);
        }
      }
    }
  }

  /**
   * @since 3.2.2
   */
  public Collection<Class<?>> getMappers() {
    return Collections.unmodifiableCollection(knownMappers.keySet());
  }

  /**
   * [初始化期：包扫描注册] 扫描指定包路径下的所有类，并自动注册符合条件的接口。
   * @since 3.2.2
   */
  public void addMappers(String packageName, Class<?> superType) {
    ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
    // 查找该包下所有的 Object 子类（即所有类）
    resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
    Set<Class<? extends Class<?>>> mapperSet = resolverUtil.getClasses();
    // 循环添加
    for (Class<?> mapperClass : mapperSet) {
      // 依次调用 addMapper 进行严谨注册
      addMapper(mapperClass);
    }
  }

  /**
   * @since 3.2.2
   */
  public void addMappers(String packageName) {
    addMappers(packageName, Object.class);
  }

}
