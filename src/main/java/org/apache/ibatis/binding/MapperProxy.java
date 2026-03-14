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

import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.SqlSession;

/**
 * Mapper 代理对象
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class MapperProxy<T> implements InvocationHandler, Serializable {

  private static final long serialVersionUID = -4724728412955527868L;
  private static final int ALLOWED_MODES = MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PROTECTED
      | MethodHandles.Lookup.PACKAGE | MethodHandles.Lookup.PUBLIC;
  private static final Constructor<Lookup> lookupConstructor;
  private static final Method privateLookupInMethod;
  private final SqlSession sqlSession; // 记录关联的 SqlSession对象
  private final Class<T> mapperInterface; // Mapper接口对应的Class对象
  // 用于缓存MapperMethod对象，key是Mapper接口方法对应的Method对象，value是对应的MapperMethod对象。‘
  // MapperMethod对象会完成参数转换以及SQL语句的执行
  // 注意：MapperMethod中并不会记录任何状态信息，可以在多线程间共享
  private final Map<Method, MapperMethodInvoker> methodCache;

  public MapperProxy(SqlSession sqlSession, Class<T> mapperInterface, Map<Method, MapperMethodInvoker> methodCache) {
    this.sqlSession = sqlSession;
    this.mapperInterface = mapperInterface;
    this.methodCache = methodCache;
  }

  static {
    Method privateLookupIn;
    try {
      privateLookupIn = MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
    } catch (NoSuchMethodException e) {
      privateLookupIn = null;
    }
    privateLookupInMethod = privateLookupIn;

    Constructor<Lookup> lookup = null;
    if (privateLookupInMethod == null) {
      // JDK 1.8
      try {
        lookup = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
        lookup.setAccessible(true);
      } catch (NoSuchMethodException e) {
        throw new IllegalStateException(
            "There is neither 'privateLookupIn(Class, Lookup)' nor 'Lookup(Class, int)' method in java.lang.invoke.MethodHandles.",
            e);
      } catch (Exception e) {
        lookup = null;
      }
    }
    lookupConstructor = lookup;
  }

  /**
   * [拦截入口] 实现 InvocationHandler 接口的 invoke 方法。
   * 当开发者调用 Mapper 接口的任何方法时，都会触发此处的逻辑。
   * @param proxy 代理对象
   * @param method 被调用的方法
   * @param args 方法参数
   * @return 方法执行结果
   * @throws Throwable 方法执行异常
   */
  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      // 1. 【方法过滤】：检查当前调用的方法是否属于 Object 类（如 toString, hashCode, equals 等）
      // 逻辑：如果是 Object 定义的基础方法，直接通过反射执行原始逻辑，无需进行 SQL 解析与执行。
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, args);
      } else {

        // 2. 【分发执行】：核心业务转发。
        // 逻辑：从缓存中获取（或创建）一个方法调用者（Invoker），并执行其 invoke 方法。
        // 最终会进入 SQL 执行流程或 Java 8 默认方法执行流程。
        // - 支持两类方法：常规(普通)Mapper 方法(PlainMethodInvoker) 和接口默认方法(DefaultMethodInvoker)
        return cachedInvoker(method).invoke(proxy, method, args, sqlSession);
      }
    } catch (Throwable t) {
      // 异常剥离：获取最底层的业务异常，避免包装类干扰
      throw ExceptionUtil.unwrapThrowable(t);
    }
  }

  /**
   * [缓存与解析] 获取接口方法对应的调用者对象（Invoker）。
   * 采用懒加载与缓存策略，提升运行效率。
   * @param method 接口方法对象
   * @return 方法对应的调用器（用于执行普通 SQL 方法或接口默认方法）
   */
  private MapperMethodInvoker cachedInvoker(Method method) throws Throwable {
    try {

      // 1. 【性能优化】：利用 ConcurrentHashMap 的 computeIfAbsent 实现线程安全的缓存获取。
      // 如果 methodCache 中已存在该方法的解析结果，则直接返回；否则执行 Lambda 表达式中的解析逻辑。
      return methodCache.computeIfAbsent(method, m -> {

        // 2. 【类型判断 A】：处理接口默认方法（Java 8+）
        if (m.isDefault()) {
          // 接口的默认方法(Java8)，只要实现接口都会继承接口的默认方法，例如 List.sort()
          try {
            // 默认方法需通过 MethodHandle 调用，而非直接反射执行 SQL
            // 根据 JDK 版本差异选择不同的 MethodHandle 获取方式
            // 获取并返回一个执行接口默认逻辑的 DefaultMethodInvoker。
            if (privateLookupInMethod == null) {
              // JDK 8 及以下版本
              return new DefaultMethodInvoker(getMethodHandleJava8(method));
            } else {
              // JDK 9+ 使用更安全的 privateLookupIn 方法
              return new DefaultMethodInvoker(getMethodHandleJava9(method));
            }
          } catch (IllegalAccessException | InstantiationException | InvocationTargetException
              | NoSuchMethodException e) {
            throw new RuntimeException(e);
          }
        } else {
          // 3. 【类型判断 B】：处理标准的 Mapper SQL 方法
          // 实例化一个 MapperMethod 对象，该对象内部封装了 SQL 语句解析逻辑。
          // 包括：SQL 类型（SELECT/INSERT/UPDATE/DELETE）、参数映射、返回值处理等
          // 最终返回一个 PlainMethodInvoker，用于触发 SQL 的执行。
          return new PlainMethodInvoker(new MapperMethod(mapperInterface, method, sqlSession.getConfiguration()));
        }
      });
    } catch (RuntimeException re) {
      // 异常解包：Lambda 中抛出的 RuntimeException 可能包装了原始异常
      // 这里将包装的异常解包后抛出，保持异常链的清晰
      Throwable cause = re.getCause();
      throw cause == null ? re : cause;
    }
  }

  private MethodHandle getMethodHandleJava9(Method method)
      throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
    final Class<?> declaringClass = method.getDeclaringClass();
    return ((Lookup) privateLookupInMethod.invoke(null, declaringClass, MethodHandles.lookup())).findSpecial(
        declaringClass, method.getName(), MethodType.methodType(method.getReturnType(), method.getParameterTypes()),
        declaringClass);
  }

  private MethodHandle getMethodHandleJava8(Method method)
      throws IllegalAccessException, InstantiationException, InvocationTargetException {
    final Class<?> declaringClass = method.getDeclaringClass();
    return lookupConstructor.newInstance(declaringClass, ALLOWED_MODES).unreflectSpecial(method, declaringClass);
  }

  interface MapperMethodInvoker {
    Object invoke(Object proxy, Method method, Object[] args, SqlSession sqlSession) throws Throwable;
  }

  /**
   * [调用桥接器] MapperMethodInvoker 的标准实现类。
   * 职责：作为中间层，负责将代理对象的调用指令转发给具体的 MapperMethod 逻辑。
   */
  private static class PlainMethodInvoker implements MapperMethodInvoker {
    private final MapperMethod mapperMethod;

    public PlainMethodInvoker(MapperMethod mapperMethod) {
      super();
      this.mapperMethod = mapperMethod;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args, SqlSession sqlSession) throws Throwable {
      // 核心跳转点：正式进入 SQL 执行的逻辑封装体
      return mapperMethod.execute(sqlSession, args);
    }
  }

  private static class DefaultMethodInvoker implements MapperMethodInvoker {
    private final MethodHandle methodHandle;

    public DefaultMethodInvoker(MethodHandle methodHandle) {
      super();
      this.methodHandle = methodHandle;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args, SqlSession sqlSession) throws Throwable {
      return methodHandle.bindTo(proxy).invokeWithArguments(args);
    }
  }
}
