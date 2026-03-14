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
 * Mapper注册器
 *   在解析的过程中会将对应的Mapper添加到该注册器中。
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 */
public class MapperRegistry {

  private final Configuration config;
  // 记录 Mapper 接口和 MapperProxyFactory 之间的关系
  private final Map<Class<?>, MapperProxyFactory<?>> knownMappers = new HashMap<>();

  public MapperRegistry(Configuration config) {
    this.config = config;
  }

  @SuppressWarnings("unchecked")
  /**
   * 获取Mapper接口对应的代理对象
   */
  public <T> T getMapper(Class<T> type, SqlSession sqlSession) {


    // 1. 从 knownMappers 中获取Mapper接口对应的专属的代理工厂 MapperProxyFactory 对象
    // 该工厂在解析 XML/接口注解阶段就已经被创建并存入 Map
    final MapperProxyFactory<T> mapperProxyFactory = (MapperProxyFactory<T>) knownMappers.get(type);
    if (mapperProxyFactory == null) {
      throw new BindingException("Type " + type + " is not known to the MapperRegistry.");
    }
    try {
      // 2. 委派给工厂实例，传入当前的 sqlSession 开启代理对象的实例化
      return mapperProxyFactory.newInstance(sqlSession);
    } catch (Exception e) {
      throw new BindingException("Error getting mapper instance. Cause: " + e, e);
    }
  }

  public <T> boolean hasMapper(Class<T> type) {
    return knownMappers.containsKey(type);
  }


  /**
   * 将接口类型注册到 MapperRegistry，并准备其代理工厂。
   */
  public <T> void addMapper(Class<T> type) {
    // 检测 type 是否为接口
    if (type.isInterface()) {
      // 检测是否已经加装过该接口
      if (hasMapper(type)) {
        throw new BindingException("Type " + type + " is already known to the MapperRegistry.");
      }
      boolean loadCompleted = false;
      try {

        // 【核心产物】：为该接口创建一个专属的代理工厂 MapperProxyFactory
        // 之后 sqlSession.getMapper(type) 实际上就是通过这个工厂创建 JDK 动态代理实例
        knownMappers.put(type, new MapperProxyFactory<>(type));
        // It's important that the type is added before the parser is run
        // otherwise the binding may automatically be attempted by the
        // mapper parser. If the type is already known, it won't try.

        // 【注解解析】：以接口为基准，解析其方法上标注的 SQL 注解（如 @Select, @Update）
        MapperAnnotationBuilder parser = new MapperAnnotationBuilder(config, type);
        parser.parse();
        loadCompleted = true;
      } finally {
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
   * @since 3.2.2
   */
  public void addMappers(String packageName, Class<?> superType) {
    ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
    resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
    Set<Class<? extends Class<?>>> mapperSet = resolverUtil.getClasses();
    // 循环添加
    for (Class<?> mapperClass : mapperSet) {
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
