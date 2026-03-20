/**
 * Copyright 2009-2026 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.logging.jdbc;

import java.lang.reflect.Method;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.stream.Collectors;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.reflection.ArrayUtil;

/**
 * [JDBC 日志抽象基类] 为 JDBC 各大对象的代理类提供基础支撑。
 * <p>
 * 核心职能：
 * 1. 维护 PreparedStatement 的参数映射（参数名、参数值）。
 * 2. 识别并过滤需要拦截的 JDBC 方法（Set 方法与 Execute 方法）。
 * 3. 提供统一的日志格式化工具（如打印参数类型、SQL 换行符处理）。
 * Base class for proxies to do logging.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public abstract class BaseJdbcLogger {

  // 记录 PreparedStatement 接口中所有以 "set" 开头的参数设置方法名（如 setInt, setString）
  protected static final Set<String> SET_METHODS;

  // 记录 Statement 和 PreparedStatement 中触发 SQL 执行的方法名
  protected static final Set<String> EXECUTE_METHODS = new HashSet<>();

  // 存储 PreparedStatement.set*()  已设置的参数键值对，用于日志回显
  private final Map<Object, Object> columnMap = new HashMap<>();

  //记录了PreparedStatement.set*()  有序记录参数名称（或索引下标）
  private final List<Object> columnNames = new ArrayList<>();

  // 记录了PreparedStatement.set*() 有序记录传入的实际参数值
  private final List<Object> columnValues = new ArrayList<>();

  // 底层具体的日志适配器实例（如 Slf4jImpl）
  protected final Log statementLog;

  // 记录嵌套查询深度，用于生成不同长度的日志前缀（如 ==> 和 ====>）
  protected final int queryStack;

  /*
   * Default constructor
   */
  public BaseJdbcLogger(Log log, int queryStack) {
    this.statementLog = log;
    if (queryStack == 0) {
      this.queryStack = 1;
    } else {
      this.queryStack = queryStack;
    }
  }

  static {
    // 1. 【方法识别】：利用反射流获取 PreparedStatement 中所有的 set* 方法
    SET_METHODS = Arrays.stream(PreparedStatement.class.getDeclaredMethods())
      .filter(method -> method.getName().startsWith("set"))
      // 过滤掉只有 1 个参数的方法（JDBC 标准 set 方法通常是 Index + Value）
      .filter(method -> method.getParameterCount() > 1)
      .map(Method::getName)
      .collect(Collectors.toSet());

    // 2. 【执行动作识别】：记录能触发 SQL 打印的物理执行方法
    EXECUTE_METHODS.add("execute");
    EXECUTE_METHODS.add("executeUpdate");
    EXECUTE_METHODS.add("executeQuery");
    EXECUTE_METHODS.add("addBatch");
  }

  /**
   * 记录参数元数据。当代理拦截到 setXxx 方法时，会将参数存入此处。
   */
  protected void setColumn(Object key, Object value) {
    columnMap.put(key, value);
    columnNames.add(key);
    columnValues.add(value);
  }

  protected Object getColumn(Object key) {
    return columnMap.get(key);
  }


  /**
   * [格式化工具] 获取参数值的字符串表示。
   * 产出示例：1(Integer), "张三"(String), null
   */
  protected String getParameterValueString() {
    List<Object> typeList = new ArrayList<>(columnValues.size());
    for (Object value : columnValues) {
      if (value == null) {
        typeList.add("null");
      } else {
        // 拼接：物理值 + (类名)
        typeList.add(objectValueString(value) + "(" + value.getClass().getSimpleName() + ")");
      }
    }
    final String parameters = typeList.toString();
    // 剥离 List 的中括号 [ ]
    return parameters.substring(1, parameters.length() - 1);
  }

  /**
   * 处理数组类型的特殊打印格式。
   */
  protected String objectValueString(Object value) {
    if (value instanceof Array) {
      try {
        return ArrayUtil.toString(((Array) value).getArray());
      } catch (SQLException e) {
        return value.toString();
      }
    }
    return value.toString();
  }

  protected String getColumnString() {
    return columnNames.toString();
  }


  /**
   * 清理上一次执行留下的参数信息，防止在批处理或重用 Statement 时日志数据重叠。
   */
  protected void clearColumnInfo() {
    columnMap.clear();
    columnNames.clear();
    columnValues.clear();
  }

  /**
   * [文本清洗] 将 SQL 语句中的换行符、多余空格替换为标准单空格。
   * 目的：使日志输出在控制台保持整齐的单行排列。
   */
  protected String removeBreakingWhitespace(String original) {
    StringTokenizer whitespaceStripper = new StringTokenizer(original);
    StringBuilder builder = new StringBuilder();
    while (whitespaceStripper.hasMoreTokens()) {
      builder.append(whitespaceStripper.nextToken());
      builder.append(" ");
    }
    return builder.toString();
  }

  protected boolean isDebugEnabled() {
    return statementLog.isDebugEnabled();
  }

  protected boolean isTraceEnabled() {
    return statementLog.isTraceEnabled();
  }



  // --- 日志输出辅助方法 ---

  protected void debug(String text, boolean input) {
    if (statementLog.isDebugEnabled()) {
      statementLog.debug(prefix(input) + text);
    }
  }

  protected void trace(String text, boolean input) {
    if (statementLog.isTraceEnabled()) {
      statementLog.trace(prefix(input) + text);
    }
  }


  /**
   * [动态前缀生成]
   * 逻辑：根据 queryStack 的深度生成引导符。
   * 输入：input 为 true 输出 ==>，为 false 输出 <==。
   */
  private String prefix(boolean isInput) {
    char[] buffer = new char[queryStack * 2 + 2];
    Arrays.fill(buffer, '=');
    buffer[queryStack * 2 + 1] = ' ';
    if (isInput) {
      buffer[queryStack * 2] = '>';
    } else {
      buffer[0] = '<';
    }
    return new String(buffer);
  }

}
