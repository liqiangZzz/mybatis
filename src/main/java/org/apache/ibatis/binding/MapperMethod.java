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
package org.apache.ibatis.binding;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

/**
 * Mapper接口对应要执行的方法
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 * @author Kazuki Shimizu
 */
public class MapperMethod {

  // statement id （例如：com.bobo.mapper.BlogMapper.selectBlogById） 和 SQL 类型
  private final SqlCommand command;
  // 方法签名，主要是返回值的类型
  private final MethodSignature method;

  public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
    this.command = new SqlCommand(config, mapperInterface, method);
    this.method = new MethodSignature(config, mapperInterface, method);
  }


  /**
   * [核心分发引擎] 根据 SQL 指令类型与方法签名执行对应的 SqlSession 操作。
   * 该方法将 Java 方法调用“翻译”为 SqlSession 的 CRUD 操作。
   *
   * @param sqlSession 当前活跃的会话实例
   * @param args       外部传入的原始参数数组
   * @return 数据库操作结果（已转换为方法声明的返回类型）
   */
  public Object execute(SqlSession sqlSession, Object[] args) {
    Object result;
    // 1. 【路由决策】：通过 SqlCommand 确定的类型（INSERT/UPDATE/DELETE/SELECT）分流
    switch (command.getType()) {
      case INSERT: {
        // 转换参数：将 args[] 转换为 Map 或单个对象（由 MethodSignature/ParamNameResolver 完成）
        Object param = method.convertArgsToSqlCommandParam(args);
        // 执行并处理结果：rowCountResult 负责将受影响行数(int)转为方法定义的返回类型(如 boolean)
        result = rowCountResult(sqlSession.insert(command.getName(), param));
        break;
      }
      case UPDATE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.update(command.getName(), param));
        break;
      }
      case DELETE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.delete(command.getName(), param));
        break;
      }
      case SELECT:
        // 2. 【细分查询策略】：针对 SELECT，利用 MethodSignature 的解析结果选择返回容器

        // 情况 A：无返回值，但传入了 ResultHandler（常用于流式大数据处理，不占内存）
        if (method.returnsVoid() && method.hasResultHandler()) {
          // 情况 A：无返回值 + ResultHandler，使用自定义 ResultHandler 处理流式结果
          executeWithResultHandler(sqlSession, args);
          result = null;
        }
        // 情况 B：返回值是 List、Set 或数组（由 MethodSignature.returnsMany 判定）
        else if (method.returnsMany()) {
          result = executeForMany(sqlSession, args);
        }
        // 情况 C：返回值是 Map（使用了 @MapKey 注解）
        else if (method.returnsMap()) {
          result = executeForMap(sqlSession, args);
        }
        // 情况 D：返回值是 Cursor（游标查询，适用于延迟加载大量数据）
        else if (method.returnsCursor()) {
          result = executeForCursor(sqlSession, args);
        }
        // 情况 E：返回单条记录（最常见）
        else {
          Object param = method.convertArgsToSqlCommandParam(args);
          // 调用 SqlSession.selectOne 核心入口
          result = sqlSession.selectOne(command.getName(), param);

          // 特殊处理：如果接口定义返回 Optional<T> (Java 8+)，则进行包装
          if (method.returnsOptional()
            && (result == null || !method.getReturnType().equals(result.getClass()))) {
            result = Optional.ofNullable(result);
          }
        }
        break;
      case FLUSH:
        // 触发手动刷新批处理语句
        result = sqlSession.flushStatements();
        break;
      default:
        throw new BindingException("Unknown execution method for: " + command.getName());
    }
    // 3. 【健壮性校验】：防止 NPE（空指针异常）
    // 如果数据库查出 null，但接口定义返回的是基本类型（如 int, long），则抛出异常提醒开发者
    if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
      throw new BindingException("Mapper method '" + command.getName()
        + " attempted to return null from a method with a primitive return type (" + method.getReturnType() + ").");
    }
    return result;
  }

  /**
   * 根据方法的返回结果转换为对应的返回值
   * @param rowCount
   * @return
   */
  private Object rowCountResult(int rowCount) {
    final Object result;
    if (method.returnsVoid()) {
      result = null;
    } else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE.equals(method.getReturnType())) {
      result = rowCount;
    } else if (Long.class.equals(method.getReturnType()) || Long.TYPE.equals(method.getReturnType())) {
      result = (long) rowCount;
    } else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE.equals(method.getReturnType())) {
      result = rowCount > 0;
    } else {
      throw new BindingException("Mapper method '" + command.getName() + "' has an unsupported return type: " + method.getReturnType());
    }
    return result;
  }

  private void executeWithResultHandler(SqlSession sqlSession, Object[] args) {
    // 获取SQL语句对应的 MappedStatement 对象
    MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
    if (!StatementType.CALLABLE.equals(ms.getStatementType())
      && void.class.equals(ms.getResultMaps().get(0).getType())) {
      throw new BindingException("method " + command.getName()
        + " needs either a @ResultMap annotation, a @ResultType annotation,"
        + " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
    }
    // 转换实参 列表
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) { // 检测 参数 列表中是否包含 RowBounds 类型的参数
      RowBounds rowBounds = method.extractRowBounds(args);
      sqlSession.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
    } else {
      sqlSession.select(command.getName(), param, method.extractResultHandler(args));
    }
  }

  private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
    List<E> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectList(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.selectList(command.getName(), param);
    }
    // issue #510 Collections & arrays support
    if (!method.getReturnType().isAssignableFrom(result.getClass())) {
      if (method.getReturnType().isArray()) {
        return convertToArray(result);
      } else {
        return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
      }
    }
    return result;
  }

  private <T> Cursor<T> executeForCursor(SqlSession sqlSession, Object[] args) {
    Cursor<T> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectCursor(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.selectCursor(command.getName(), param);
    }
    return result;
  }

  private <E> Object convertToDeclaredCollection(Configuration config, List<E> list) {
    // 通过 ObjectFactory 来创建对象  反射的方式
    Object collection = config.getObjectFactory().create(method.getReturnType());
    // 创建 MetaObject 对象
    MetaObject metaObject = config.newMetaObject(collection);
    // 实际上就是调试 Collection.addAll方法
    metaObject.addAll(list);
    return collection;
  }

  @SuppressWarnings("unchecked")
  private <E> Object convertToArray(List<E> list) {
    Class<?> arrayComponentType = method.getReturnType().getComponentType();
    Object array = Array.newInstance(arrayComponentType, list.size());
    if (arrayComponentType.isPrimitive()) {
      // 如果是基本类型的包装类型
      for (int i = 0; i < list.size(); i++) {
        Array.set(array, i, list.get(i));
      }
      return array;
    } else {
      return list.toArray((E[]) array);
    }
  }

  private <K, V> Map<K, V> executeForMap(SqlSession sqlSession, Object[] args) {
    Map<K, V> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectMap(command.getName(), param, method.getMapKey(), rowBounds);
    } else {
      result = sqlSession.selectMap(command.getName(), param, method.getMapKey());
    }
    return result;
  }

  public static class ParamMap<V> extends HashMap<String, V> {

    private static final long serialVersionUID = -2212268410512043556L;

    @Override
    public V get(Object key) {
      if (!super.containsKey(key)) {
        throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + keySet());
      }
      return super.get(key);
    }

  }

  /**
   * SQL 命令封装类
   * 核心任务：根据 Mapper 接口的方法，确定对应的 SQL 语句 ID (name) 和 SQL 类型 (type)
   */
  public static class SqlCommand {

    // 对应 MappedStatement 的 ID，即：接口全路径.方法名
    private final String name;

    // SQL 语句的类型：UNKNOWN, INSERT, UPDATE, DELETE, SELECT, FLUSH
    private final SqlCommandType type;

    public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {
      // 获取当前调用的方法名
      final String methodName = method.getName();
      // 获取定义该方法的类（可能是父类接口）
      final Class<?> declaringClass = method.getDeclaringClass();

      // 1. 核心步骤：解析并找到对应的 MappedStatement 对象
      MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass, configuration);

      // 2. 如果没找到对应的 SQL 映射语句
      if (ms == null) {
        // 检查是否有 @Flush 注解（MyBatis 提供的清理缓存注解）
        if (method.getAnnotation(Flush.class) != null) {
          name = null;
          type = SqlCommandType.FLUSH;
        } else {
          // 报错：这就是我们最常见的 BindingException 报错来源
          throw new BindingException("Invalid bound statement (not found): "
            + mapperInterface.getName() + "." + methodName);
        }
      } else {
        // 3. 找到了映射语句，初始化 SQL ID 和类型
        name = ms.getId();  // 如：com.liqiang.mapper.UserMapper.getUserById
        type = ms.getSqlCommandType(); // 如：SELECT
        if (type == SqlCommandType.UNKNOWN) {
          throw new BindingException("Unknown execution method for: " + name);
        }
      }
    }

    public String getName() {
      return name;
    }

    public SqlCommandType getType() {
      return type;
    }


    /**
     * 递归查找 MappedStatement 对象
     * 规则：按照 "接口全限定名.方法名" 这种格式从 Configuration 中查找
     */
    private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName,
                                                   Class<?> declaringClass, Configuration configuration) {

      // 拼接 statementId，statementId = Mapper接口全路径 + 方法名称  例如: com.liqiang.mapper.UserMapper.selectList
      String statementId = mapperInterface.getName() + "." + methodName;

      // 检查 Configuration 的全局映射表里是否已经注册了这个 ID
      if (configuration.hasStatement(statementId)) {
        return configuration.getMappedStatement(statementId);
      }
      // 如果找遍了也没找到，且当前类就是定义方法的类，说明真的找不到了
      else if (mapperInterface.equals(declaringClass)) {
        return null;
      }

      // 关键逻辑：如果当前接口没有，就递归检查它继承的所有父接口
      for (Class<?> superInterface : mapperInterface.getInterfaces()) {
        // 只有当父接口确实和定义该方法的类有继承/实现关系时才继续找
        if (declaringClass.isAssignableFrom(superInterface)) {
          MappedStatement ms = resolveMappedStatement(superInterface, methodName,
            declaringClass, configuration);
          if (ms != null) {
            return ms;
          }
        }
      }
      return null;
    }
  }

  public static class MethodSignature {

    private final boolean returnsMany;    // 返回值是否为 Collection 类型或数组（针对多行查询）
    private final boolean returnsMap;     // 返回值是否为 Map 类型（配合 @MapKey 使用）
    private final boolean returnsVoid;    // 返回值是否为 void（针对增删改或无返回操作）
    private final boolean returnsCursor;  // 返回值是否为 Cursor 类型（用于流式查询，MyBatis 3.4.0+）
    private final boolean returnsOptional;// 返回值是否为 Optional 类型（Java 8+，避免空指针）
    private final Class<?> returnType;    // 最终解析出来的返回值 Class 类型
    private final String mapKey;          // 如果返回 Map，@MapKey 注解指定的列名将作为 Map 的 key
    private final Integer resultHandlerIndex; // 参数列表中 ResultHandler 参数的位置（用于自定义结果处理）
    private final Integer rowBoundsIndex;     // 参数列表中 RowBounds 参数的位置（用于逻辑分页）
    private final ParamNameResolver paramNameResolver; // 参数名解析器，负责处理 @Param 和参数到 Map 的转换

    /**
     * 构造函数：在 MapperMethod 初始化时被调用，通过反射解析方法的所有特征
     */
    public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {
      // 1. 解析返回值类型：处理泛型（例如 List<User> 中的 User 类型）
      Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);
      if (resolvedReturnType instanceof Class<?>) {
        this.returnType = (Class<?>) resolvedReturnType;
      } else if (resolvedReturnType instanceof ParameterizedType) {
        this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
      } else {
        this.returnType = method.getReturnType();
      }

      // 2. 设置各种返回类型的布尔标记
      this.returnsVoid = void.class.equals(this.returnType);
      // 判断是否是集合或数组
      this.returnsMany = configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray();
      // 判断是否为 Cursor 类型
      this.returnsCursor = Cursor.class.equals(this.returnType);
      // 判断是否为 Optional 类型
      this.returnsOptional = Optional.class.equals(this.returnType);

      // 3. 处理 Map 类型的返回（针对 @MapKey）
      this.mapKey = getMapKey(method);
      this.returnsMap = this.mapKey != null;

      // 4. 记录特殊参数在参数列表中的索引位置（RowBounds 分页、ResultHandler 结果处理器）
      this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);
      this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);

      // 5. 初始化参数名称解析器：用于将方法参数映射为 SQL 中的 #{name}
      this.paramNameResolver = new ParamNameResolver(configuration, method);
    }

    /**
     * 核心方法：将用户传入的实参数组 (Object[]) 转换为 SQL 执行所需的参数对象，是通过 ParamNameResolver 来实现的。
     * 例如：把 (1, "admin") 转换为 {id: 1, name: "admin"} 的 Map。
     */
    public Object convertArgsToSqlCommandParam(Object[] args) {
      return paramNameResolver.getNamedParams(args);
    }

    public boolean hasRowBounds() {
      return rowBoundsIndex != null;
    }


    /**
     * 从参数数组中提取 RowBounds 对象
     */
    public RowBounds extractRowBounds(Object[] args) {
      return hasRowBounds() ? (RowBounds) args[rowBoundsIndex] : null;
    }

    public boolean hasResultHandler() {
      return resultHandlerIndex != null;
    }

    /**
     * 从参数数组中提取 ResultHandler 对象处理结果
     */
    public ResultHandler extractResultHandler(Object[] args) {
      return hasResultHandler() ? (ResultHandler) args[resultHandlerIndex] : null;
    }

    public String getMapKey() {
      return mapKey;
    }

    public Class<?> getReturnType() {
      return returnType;
    }

    public boolean returnsMany() {
      return returnsMany;
    }

    public boolean returnsMap() {
      return returnsMap;
    }

    public boolean returnsVoid() {
      return returnsVoid;
    }

    public boolean returnsCursor() {
      return returnsCursor;
    }

    /**
     * return whether return type is {@code java.util.Optional}.
     * @return return {@code true}, if return type is {@code java.util.Optional}
     * @since 3.5.0
     */
    public boolean returnsOptional() {
      return returnsOptional;
    }

    /**
     * 查找指定类型的参数在参数列表中的位置，并确保该类型的参数只出现一次
     */
    private Integer getUniqueParamIndex(Method method, Class<?> paramType) {
      Integer index = null;
      // 获取对应方法的参数列表
      final Class<?>[] argTypes = method.getParameterTypes();
      // 遍历
      for (int i = 0; i < argTypes.length; i++) {
        // 判断是否是需要查找的类型
        if (paramType.isAssignableFrom(argTypes[i])) {
          // 记录对应类型在参数列表中的位置
          if (index == null) {
            index = i;
          } else {
            // MyBatis 规定 RowBounds 和 ResultHandler 参数在同一个方法中最多只能各出现一个
            throw new BindingException(method.getName() + " cannot have multiple " + paramType.getSimpleName() + " parameters");
          }
        }
      }
      return index;
    }

    /**
     * 获取 @MapKey 注解的值。如果方法返回 Map 且标注了 @MapKey("id")，
     * 则查询结果会封装成一个 Map，key 是每行记录的 id，value 是该记录的对象。
     */
    private String getMapKey(Method method) {
      String mapKey = null;
      if (Map.class.isAssignableFrom(method.getReturnType())) {
        // 有使用@MapKey 注解
        final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
        if (mapKeyAnnotation != null) {
          mapKey = mapKeyAnnotation.value();
        }
      }
      return mapKey;
    }
  }

}
