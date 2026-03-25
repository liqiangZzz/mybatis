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

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.binding.MapperProxy.MapperMethodInvoker;
import org.apache.ibatis.session.SqlSession;

/**
 * [代理对象工厂] 专门负责为指定的 Mapper 接口生产 JDK 动态代理实例(MapperProxy 对象)。
 * <p>
 * 核心设计：
 * 1. 实例隔离：每个工厂对象仅服务于一个特定的接口类型。
 * 2. 性能优化：内部维护方法缓存（methodCache），确保接口方法的反射解析结果在同一工厂产出的代理对象间共享。
 *
 * @param <T> 目标接口类型
 * @author Lasse Voss
 */
public class MapperProxyFactory<T> {


  /**
   * MapperProxyFactory 可以创建 mapperInterface 接口的代理对象
   * 目标接口的 Class 对象，代理对象将实现此接口。
   */
  private final Class<T> mapperInterface;

  /**
   * [方法执行器缓存]
   * Key: 接口中的 Method 对象。
   * Value: 封装了执行逻辑的调用器（MapperMethodInvoker）。
   * <p>
   * 意义：该缓存是线程安全的，且属于工厂成员，意味着由该工厂创建的所有代理对象（Proxy）
   * 都会复用这同一份解析结果，从而极大降低了在高并发下的反射性能损耗。
   */
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


  /**
   * [物理实例化] 封装 JDK 动态代理的底层调用。
   *
   * @param mapperProxy 实现了 InvocationHandler 接口的拦截处理器。
   * @return 动态生成的代理实例。
   */
  @SuppressWarnings("unchecked")
  protected T newInstance(MapperProxy<T> mapperProxy) {
    /*
     * JDK 动态代理核心参数：
     * 1. 接口的类加载器：用于定义代理类。
     * 2. 代理类需要实现的接口数组：在此固定为单接口 mapperInterface（即 Mapper 接口本身）。
     * 3. 拦截器实现类：MapperProxy，负责处理所有方法调用（它实现了 InvocationHandler 接口）。
     */
    return (T) Proxy.newProxyInstance(mapperInterface.getClassLoader(), new Class[] { mapperInterface }, mapperProxy);
  }

  /**
   * [业务实例化入口] 构建具体的 InvocationHandler 并触发 JDK 代理创建。 将 SQL 会话上下文注入到代理逻辑中。
   *
   * @param sqlSession 当前执行 SQL 所需的会话实例（非线程安全，需每次注入）。
   */
  public T newInstance(SqlSession sqlSession) {
    // 1.建 InvocationHandler 实例：MapperProxy
    // 它持有了 sqlSession 指针（用于后续执行 SQL）
    // 以及 methodCache（用于缓存解析过的方法元数据，提升性能）
    final MapperProxy<T> mapperProxy = new MapperProxy<>(sqlSession, mapperInterface, methodCache);

    // 2. 触发底层的代理对象构建逻辑。
    return newInstance(mapperProxy);
  }

}
