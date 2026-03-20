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

import java.lang.reflect.Constructor;

 /**
 * [日志工厂中心] 负责自动探测、加载并提供具体的日志适配器实例。
 * <p>
 * 设计模式：工厂模式 + 适配器模式。
 * 核心机制：在类加载时通过静态代码块按优先级自动寻找类路径（Classpath）下可用的日志库。
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public final class LogFactory {

  /**
   * 预定义的日志标记，部分支持 Marker 的日志框架（如 Slf4j）会使用。
   * <p>
   * Marker to be used by logging implementations that support markers.
   */
  public static final String MARKER = "MYBATIS";

  // 核心属性：记录最终选定的日志实现类的构造方法
  private static Constructor<? extends Log> logConstructor;

  static {
    /*
     * 【核心启动逻辑】：自动探测竞速
     *
     * 按照以下严格的优先级顺序尝试加载日志组件。
     * 逻辑原则：第一个被成功加载并实例化的框架将锁定 logConstructor，后续尝试将自动跳过。
     */
    tryImplementation(LogFactory::useSlf4jLogging);      // 优先级 1：Slf4j (现代 Java 首选)
    tryImplementation(LogFactory::useCommonsLogging);    // 优先级 2：Apache Commons Logging
    tryImplementation(LogFactory::useLog4J2Logging);     // 优先级 3：Log4j 2
    tryImplementation(LogFactory::useLog4JLogging);      // 优先级 4：Log4j (旧版)
    tryImplementation(LogFactory::useJdkLogging);        // 优先级 5：JDK 1.4 Logging
    tryImplementation(LogFactory::useNoLogging);         // 优先级 6：不打印日志 (保底方案)
  }

  private LogFactory() {
    // 静态工具类，禁止外部实例化
    // disable construction
  }

  /**
   * 根据指定的 Class 获取日志实例。
   */
  public static Log getLog(Class<?> aClass) {
    return getLog(aClass.getName());
  }

  /**
   * 根据指定的字符串名称获取日志实例。
   * 实际逻辑：通过反射调用选定适配器的构造函数：new XxxImpl(String name)
   */
  public static Log getLog(String logger) {
    try {
      return logConstructor.newInstance(logger);
    } catch (Throwable t) {
      throw new LogException("Error creating logger for logger " + logger + ".  Cause: " + t, t);
    }
  }

  /**
   * [手动干预点] 允许开发者在 XML 配置中通过 <setting name="logImpl"> 强制指定日志类。
   * 调用此方法会覆盖静态代码块自动探测的结果。
   */
  public static synchronized void useCustomLogging(Class<? extends Log> clazz) {
    setImplementation(clazz);
  }

  // --- 以下为各种日志框架的显式指定方法 ---

  public static synchronized void useSlf4jLogging() {
    setImplementation(org.apache.ibatis.logging.slf4j.Slf4jImpl.class);
  }

  public static synchronized void useCommonsLogging() {
    setImplementation(org.apache.ibatis.logging.commons.JakartaCommonsLoggingImpl.class);
  }

  public static synchronized void useLog4JLogging() {
    setImplementation(org.apache.ibatis.logging.log4j.Log4jImpl.class);
  }

  public static synchronized void useLog4J2Logging() {
    setImplementation(org.apache.ibatis.logging.log4j2.Log4j2Impl.class);
  }

  public static synchronized void useJdkLogging() {
    setImplementation(org.apache.ibatis.logging.jdk14.Jdk14LoggingImpl.class);
  }

  public static synchronized void useStdOutLogging() {
    setImplementation(org.apache.ibatis.logging.stdout.StdOutImpl.class);
  }

  public static synchronized void useNoLogging() {
    setImplementation(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
  }

  /**
   * [尝试加载逻辑] 只有在尚未确定日志框架时，才执行传入的加载动作。
   */
  private static void tryImplementation(Runnable runnable) {
    if (logConstructor == null) {
      try {
        runnable.run();
      } catch (Throwable t) {
        // ignore
        // 若当前环境没有对应的 jar 包，会抛出 ClassNotFoundException，此处捕获并忽略，继续尝试下一个。
      }
    }
  }

  /**
   * [物理赋值逻辑] 设置日志实现类，并执行可用性校验。
   */
  private static void setImplementation(Class<? extends Log> implClass) {
    try {

      // 1. 获取指定适配器类带 String 参数的构造方法（用于传入 Logger 的名称）
      Constructor<? extends Log> candidate = implClass.getConstructor(String.class);

      // 2. 【核心校验】：尝试实例化一个适配器。
      // 目的：确保虽然类存在，但其依赖的底层框架（如具体的 log4j.jar）也是完整可用的。
      Log log = candidate.newInstance(LogFactory.class.getName());
      if (log.isDebugEnabled()) {
        log.debug("Logging initialized using '" + implClass + "' adapter.");
      }
      // 3. 初始化 logConstructor 字段，
      // 校验通过，锁定全局构造器。后续所有 getLog 调用都将使用此实现。
      logConstructor = candidate;
    } catch (Throwable t) {
      throw new LogException("Error setting Log implementation.  Cause: " + t, t);
    }
  }

}
