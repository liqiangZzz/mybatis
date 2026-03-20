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
package org.apache.ibatis.plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.reflection.ExceptionUtil;

/**
 * MyBatis 插件核心代理类
 *
 * <p>实现了 InvocationHandler 接口，是 JDK 动态代理的处理器。
 * 当通过 Plugin.wrap() 创建的代理对象的方法被调用时，都会进入此类的 invoke 方法。
 *
 * <p>核心职责：判断当前调用的方法是否需要被拦截，如果需要则交给拦截器的 intercept 方法处理，
 * 否则直接调用目标对象的原始方法。
 *
 * @author Clinton Begin
 */
public class Plugin implements InvocationHandler {

  /**
   * 目标对象（被代理的原始对象）
   * 例如：Executor、StatementHandler、ParameterHandler、ResultSetHandler 的实例
   */
  private final Object target;

  /**
   * 拦截器实例（用户自定义的插件）
   * 包含用户实现的 intercept 方法，用于执行自定义的拦截逻辑
   */
  private final Interceptor interceptor;

  /**
   * 签名映射表，记录 @Signature 注解的信息
   * 记录当前拦截器要拦截的所有类型及其对应的方法
   * Key: 要拦截的类型（如 Executor.class）
   * Value: 该类型中需要拦截的方法集合
   * 该数据来源于 @Signature 注解的解析结果
   */
  private final Map<Class<?>, Set<Method>> signatureMap;

  private Plugin(Object target, Interceptor interceptor, Map<Class<?>, Set<Method>> signatureMap) {
    this.target = target;
    this.interceptor = interceptor;
    this.signatureMap = signatureMap;
  }

  /**
   * MyBatis 插件核心包装方法
   *
   * <p>该方法根据拦截器配置的 @Signature 注解信息，动态决定是否为目标对象创建代理。
   * 只有当目标对象的接口匹配拦截器要拦截的类型时，才会创建代理对象。
   *
   * <p>支持拦截的四大对象：
   * <ul>
   *   <li>Executor - SQL 执行器</li>
   *   <li>ParameterHandler - 参数处理器</li>
   *   <li>ResultSetHandler - 结果集处理器</li>
   *   <li>StatementHandler - 语句处理器</li>
   * </ul>
   *
   * @param target 需要被代理的目标对象（可能是 Executor、ParameterHandler 等）
   * @param interceptor 自定义的拦截器实例（包含 @Signature 注解信息）
   * @return 如果目标对象的接口匹配拦截器配置，返回代理对象；否则返回原对象
   */
  public static Object wrap(Object target, Interceptor interceptor) {

    // 1. 解析拦截器中的 @Signature 注解信息
    //    获取该拦截器要拦截的所有类型及其对应的方法
    //    例如：@Signature(type = Executor.class, method = "query", args = {...})
    Map<Class<?>, Set<Method>> signatureMap = getSignatureMap(interceptor);

    // 2. 获取目标对象的实际类型
    Class<?> type = target.getClass();

    // 3. 获取目标对象实现的、且在 signatureMap 中有匹配的所有接口
    //    例如：如果 target 是 Executor，且 signatureMap 中包含 Executor.class
    //    则返回 new Class[]{Executor.class}
    Class<?>[] interfaces = getAllInterfaces(type, signatureMap);

    // 4. 判断是否需要创建代理
    if (interfaces.length > 0) {
      // 需要拦截：创建 JDK 动态代理
      return Proxy.newProxyInstance(
        // 使用目标对象的类加载器
          type.getClassLoader(),
        // 需要代理的接口数组
          interfaces,
        // InvocationHandler
          new Plugin(target, interceptor, signatureMap));
    }
    // 5. 不需要拦截：直接返回原对象
    return target;
  }

  /**
   * 代理对象方法调用时的核心处理逻辑
   *
   * <p>当应用层通过代理对象调用方法时，此方法被触发。它决定：
   * 1. 该方法是否需要被拦截（根据 signatureMap 判断）
   * 2. 如果需要拦截，调用拦截器的 intercept 方法
   * 3. 如果不需要拦截，直接调用目标对象的原始方法
   *
   * @param proxy 代理对象本身（通常很少使用）
   * @param method 当前被调用的方法（如 Executor.query()）
   * @param args 方法参数
   * @return 方法执行结果
   * @throws Throwable 可能抛出的异常
   */
  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {

      // 1. 获取当前方法所属的接口（如 Executor.class）
      //    注意：method.getDeclaringClass() 返回声明该方法的接口或类
      // 2. 从签名映射表中获取该接口对应的可拦截方法集合
      //    例如：signatureMap.get(Executor.class) 返回 Executor 中需要拦截的方法
      Set<Method> methods = signatureMap.get(method.getDeclaringClass());

      // 3. 判断当前方法是否需要被拦截
      //    条件：该接口在签名映射表中 && 当前方法在可拦截方法集合中
      if (methods != null && methods.contains(method)) {

        // 3.1 需要拦截：创建 Invocation 对象并交给拦截器的 intercept 方法处理
        //     Invocation 包装了目标对象、方法和参数，便于在 intercept 中执行原方法
        return interceptor.intercept(new Invocation(target, method, args));
      }
      // 3.2 不需要拦截：直接通过反射调用目标对象的原始方法
      //     这里调用的是 target（原始对象）的方法，而不是代理对象
      return method.invoke(target, args);
    } catch (Exception e) {
      // 4. 统一异常处理，避免代理包装影响异常类型
      //    ExceptionUtil.unwrapThrowable 会剥去代理包装，返回原始异常
      throw ExceptionUtil.unwrapThrowable(e);
    }
  }

  /**
   * 解析拦截器中的 @Intercepts 注解，获取要拦截的类型和方法映射
   *
   * <p>该方法读取拦截器类上的 @Intercepts 注解，将其中的每个 @Signature
   * 解析为具体的类型和方法信息，用于后续判断哪些目标对象需要被代理。
   *
   * <p>例如，拦截器配置：
   * <pre>
   * {@code
   * @Intercepts({
   *     @Signature(type = Executor.class, method = "query",
   *                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
   *     @Signature(type = Executor.class, method = "update",
   *                args = {MappedStatement.class, Object.class})
   * })
   * }
   * </pre>
   * 会被解析为：
   * Executor.class → {Executor.query(), Executor.update()}
   *
   * @param interceptor 自定义的拦截器实例（必须标注 @Intercepts 注解）
   * @return 类型到方法集合的映射关系
   *         Key: 要拦截的类型（如 Executor.class）
   *         Value: 该类型中需要拦截的方法集合
   * @throws PluginException 如果拦截器没有 @Intercepts 注解，或配置的方法不存在
   */
  private static Map<Class<?>, Set<Method>> getSignatureMap(Interceptor interceptor) {
    // 1. 获取拦截器类上的 @Intercepts 注解
    Intercepts interceptsAnnotation = interceptor.getClass().getAnnotation(Intercepts.class);

    // 2. 检查注解是否存在（issue #251）
    if (interceptsAnnotation == null) {
      // 如果没有 @Intercepts 注解，抛出异常
      // 因为插件必须明确声明要拦截哪些类型和方法
      throw new PluginException("No @Intercepts annotation was found in interceptor " + interceptor.getClass().getName());
    }

    // 3. 获取 @Intercepts 注解中所有的 @Signature
    Signature[] sigs = interceptsAnnotation.value();

    // 4. 创建结果映射集合
    Map<Class<?>, Set<Method>> signatureMap = new HashMap<>();

    // 5. 遍历每个 @Signature 注解
    for (Signature sig : sigs) {
      // 5.1 根据类型获取对应的方法集合（如果不存在则创建新的 HashSet）
      Set<Method> methods = signatureMap.computeIfAbsent(sig.type(), k -> new HashSet<>());
      try {
        // 5.2 通过反射获取指定类型中的具体方法
        //    参数：方法名 + 参数类型数组
        Method method = sig.type().getMethod(sig.method(), sig.args());

        // 5.3 将方法添加到集合中
        methods.add(method);
      } catch (NoSuchMethodException e) {
        // 5.4 如果方法不存在，抛出异常
        //    常见原因：方法名写错、参数类型不匹配、方法不存在
        throw new PluginException("Could not find method on " + sig.type() + " named " + sig.method() + ". Cause: " + e, e);
      }
    }
    return signatureMap;
  }

  /**
   * 获取目标对象所有需要被代理的接口
   *
   * <p>该方法遍历目标对象的类继承层次，找出所有实现了的、且在 signatureMap 中
   * 有配置的接口。只有这些接口需要被 JDK 动态代理拦截。
   *
   * <p>为什么要遍历父类？
   * 因为目标对象可能是一个代理类或子类，真正的接口可能定义在父类中。
   * 例如：SimpleExecutor 的接口 Executor 定义在父类 BaseExecutor 中。
   *
   * @param type 目标对象的实际类型（如 SimpleExecutor.class）
   * @param signatureMap 拦截器配置的签名映射（包含要拦截的类型）
   * @return 需要被代理的接口数组（可能为空数组）
   */
  private static Class<?>[] getAllInterfaces(Class<?> type, Map<Class<?>, Set<Method>> signatureMap) {
    // 1. 使用 Set 去重，防止重复添加同一个接口
    Set<Class<?>> interfaces = new HashSet<>();

    // 2. 沿着类继承链向上遍历（包括当前类及其所有父类）
    while (type != null) {

      // 3. 获取当前类实现的所有接口
      for (Class<?> c : type.getInterfaces()) {
        // 4. 判断该接口是否在拦截器的配置中
        //    signatureMap 的 key 就是 @Signature 中配置的 type
        if (signatureMap.containsKey(c)) {
          // 5. 如果匹配，添加到结果集合中
          interfaces.add(c);
        }
      }
      // 6. 继续向上处理父类
      //    为什么要处理父类？
      //    例如：SimpleExecutor extends BaseExecutor implements Executor
      //    SimpleExecutor 本身可能没有直接实现接口，接口在父类 BaseExecutor 上
      type = type.getSuperclass();
    }

    // 7. 将 Set 转换为数组返回
    return interfaces.toArray(new Class<?>[interfaces.size()]);
  }

}
