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
package org.apache.ibatis.logging;

/**
 * [日志系统核心契约] MyBatis 内部定义的统一日志接口。
 * <p>
 * 作用：MyBatis 所有的类（如 Executor, StatementHandler）只面向此接口编程。
 * 通过该接口，MyBatis 实现了与底层具体日志框架（Slf4j, Log4j2, JDK等）的彻底解耦。
 * @author Clinton Begin
 */
public interface Log {

  /**
   * 判断当前是否允许输出 DEBUG 级别的日志。
   * 用于在高频执行的代码段前进行性能优化判断，避免不必要的字符串拼接。
   */
  boolean isDebugEnabled();

  /**
   * 判断当前是否允许输出 TRACE 级别的日志。
   * MyBatis 的 SQL 详细打印通常依赖此级别。
   */
  boolean isTraceEnabled();

  /**
   * 输出 ERROR 级别错误，并记录堆栈信息。
   */
  void error(String s, Throwable e);

  /**
   * 输出 ERROR 级别信息。
   */
  void error(String s);

  /**
   * 输出 DEBUG 级别信息。
   * 核心用途：打印最终执行的 SQL 语句。
   */
  void debug(String s);

  /**
   * 输出 TRACE 级别信息。
   * 核心用途：打印 SQL 的参数值、ResultSet 结果详情。
   */
  void trace(String s);

  /**
   * 输出 WARN 级别警告信息。
   */
  void warn(String s);

}
