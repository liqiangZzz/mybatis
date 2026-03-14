/**
 *    Copyright 2009-2021 the original author or authors.
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

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.binding.MapperProxy.MapperMethodInvoker;
import org.apache.ibatis.session.SqlSession;

/**
 * 负责创建 MapperProxy 对象
 * @author Lasse Voss
 */
public class MapperProxyFactory<T> {

  /**
   * MapperProxyFactory 可以创建 mapperInterface 接口的代理对象
   *     创建的代理对象要实现的接口
   */
  private final Class<T> mapperInterface;
  // 缓存
  private final Map<Method, MapperMethodInvoker> methodCache = new ConcurrentHashMap<>();

  public MapperProxyFactory(Class<T> mapperInterface) {
    this.mapperInterface = mapperInterface;
  }

  public Class<T> getMapperInterface() {
    return mapperInterface;
  }

  public Map<Method, MapperMethodInvoker> getMethodCache() {
    return methodCache;
  }

  @SuppressWarnings("unchecked")
  /**
   * [物理创建] 调用 JDK 原生 API 生成代理类实例。
   * 创建实现了 mapperInterface 接口的代理对象
   */
  protected T newInstance(MapperProxy<T> mapperProxy) {

    // 参数含义：
    // 1. 接口的类加载器
    // 2. 被代理的接口数组（即 Mapper 接口本身）
    // 3. 拦截处理器：MapperProxy（它实现了 InvocationHandler 接口）
    return (T) Proxy.newProxyInstance(mapperInterface.getClassLoader(), new Class[] { mapperInterface }, mapperProxy);
  }

  /**
   * [实例化引擎] 构建具体的 InvocationHandler 并触发 JDK 代理创建。
   */
  public T newInstance(SqlSession sqlSession) {
    // 1. 创建 InvocationHandler 实例：MapperProxy
    // 它持有了 sqlSession 指针（用于后续执行 SQL）
    // 以及 methodCache（用于缓存解析过的方法元数据，提升性能）
    final MapperProxy<T> mapperProxy = new MapperProxy<>(sqlSession, mapperInterface, methodCache);

    // 2. 进入最终的代理创建步骤
    return newInstance(mapperProxy);
  }

}
