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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MyBatis 拦截器链
 *
 * <p>核心职责：管理所有注册的拦截器（插件），并提供目标对象的层层代理能力。
 * 当需要对 Executor、StatementHandler、ParameterHandler、ResultSetHandler 等核心对象
 * 进行增强时，通过该链将所有拦截器依次应用到目标对象上。
 *
 * <p>工作流程：
 * 1. 通过 addInterceptor 注册所有拦截器
 * 2. 当创建核心对象时，调用 pluginAll 方法
 * 3. pluginAll 遍历所有拦截器，每个拦截器对目标对象进行包装（生成代理）
 * 4. 最终返回被所有拦截器层层代理后的对象
 *
 * @author Clinton Begin
 */
public class InterceptorChain {

  /**
   * 拦截器存储容器
   *
   * <p>保存所有通过配置文件或代码注册的拦截器实例。
   * 添加顺序决定了拦截器的执行顺序（先进先出的包装顺序，后进先出的执行顺序）。
   * <p>
   * 例如：MyInterceptor 和 PageHelper 都保存在这个 List 中
   */
  private final List<Interceptor> interceptors = new ArrayList<>();

  /**
   * 对目标对象应用所有拦截器
   *
   * <p>这是拦截器链的核心方法，实现了拦截器的层层包装。
   *
   * <p>执行过程：
   * 1. 遍历 interceptors 列表中的所有拦截器
   * 2. 对当前 target 调用 interceptor.plugin(target) 生成代理对象
   * 3. 将生成的代理对象作为新的 target，传递给下一个拦截器
   * 4. 所有拦截器处理完后，返回最终被层层代理的对象
   *
   * <p>包装顺序示例：
   * 假设有拦截器 A、B、C（按此顺序添加）
   * target = C.plugin(B.plugin(A.plugin(原始对象)))
   * 执行顺序：方法调用时，先经过 C，再经过 B，最后经过 A
   *
   * @param target 需要被拦截的目标对象（如 Executor、StatementHandler 等）
   * @return 被所有拦截器代理后的对象
   */
  public Object pluginAll(Object target) {
    // 遍历拦截器链中的所有拦截器
    for (Interceptor interceptor : interceptors) {
      // 每个拦截器对当前 target 进行包装，生成代理对象
      // 包装后的对象作为下一次循环的 target，实现层层代理
      target = interceptor.plugin(target);
    }
    return target;
  }

  /**
   * 添加拦截器到拦截器链
   *
   * <p>通常在 MyBatis 解析配置文件时调用，将配置的 {@code <plugin>} 节点
   * 解析成 Interceptor 实例后添加到链中。也可以通过代码手动添加。
   *
   * <p>添加顺序很重要，决定了拦截器的执行顺序。
   *
   * @param interceptor 要添加的拦截器实例
   */
  public void addInterceptor(Interceptor interceptor) {
    interceptors.add(interceptor);
  }

  /**
   * 获取所有拦截器的只读视图
   *
   * <p>返回不可修改的拦截器列表，防止外部代码修改拦截器链。
   * 主要用于查看当前注册了哪些拦截器。
   *
   * @return 不可修改的拦截器列表
   */
  public List<Interceptor> getInterceptors() {
    return Collections.unmodifiableList(interceptors);
  }

}
