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
package org.apache.ibatis.session.defaults;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.exceptions.ExceptionFactory;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.result.DefaultMapResultHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

/**
 * The default implementation for {@link SqlSession}.
 * Note that this class is not Thread-Safe.
 *
 * @author Clinton Begin
 */
public class DefaultSqlSession implements SqlSession {

  private final Configuration configuration;
  private final Executor executor;

  private final boolean autoCommit;
  private boolean dirty;
  private List<Cursor<?>> cursorList;

  public DefaultSqlSession(Configuration configuration, Executor executor, boolean autoCommit) {
    this.configuration = configuration;
    this.executor = executor;
    this.dirty = false;
    this.autoCommit = autoCommit;
  }

  public DefaultSqlSession(Configuration configuration, Executor executor) {
    this(configuration, executor, false);
  }

  @Override
  public <T> T selectOne(String statement) {
    return this.selectOne(statement, null);
  }

  @Override
  public <T> T selectOne(String statement, Object parameter) {
    // Popular vote was to return null on 0 results and throw exception on too many.
    // 1. 逻辑复用：selectOne 本质上是调用 selectList 获取结果集合
    List<T> list = this.selectList(statement, parameter);
    // 2. 结果集数量校验
    if (list.size() == 1) {
      // 集合中只有一条数据，符合预期，直接返回
      return list.get(0);
      // 预期返回单条记录，但数据库实际返回多条，抛出异常以防止业务逻辑错误
    } else if (list.size() > 1) {
      throw new TooManyResultsException("Expected one result (or null) to be returned by selectOne(), but found: " + list.size());
    } else {
      // 未查询到任何记录，返回 null
      return null;
    }
  }

  @Override
  public <K, V> Map<K, V> selectMap(String statement, String mapKey) {
    return this.selectMap(statement, null, mapKey, RowBounds.DEFAULT);
  }

  @Override
  public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey) {
    return this.selectMap(statement, parameter, mapKey, RowBounds.DEFAULT);
  }

  @Override
  public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey, RowBounds rowBounds) {
    final List<? extends V> list = selectList(statement, parameter, rowBounds);
    final DefaultMapResultHandler<K, V> mapResultHandler = new DefaultMapResultHandler<>(mapKey,
            configuration.getObjectFactory(), configuration.getObjectWrapperFactory(), configuration.getReflectorFactory());
    final DefaultResultContext<V> context = new DefaultResultContext<>();
    for (V o : list) {
      context.nextResultObject(o);
      mapResultHandler.handleResult(context);
    }
    return mapResultHandler.getMappedResults();
  }

  @Override
  public <T> Cursor<T> selectCursor(String statement) {
    return selectCursor(statement, null);
  }

  @Override
  public <T> Cursor<T> selectCursor(String statement, Object parameter) {
    return selectCursor(statement, parameter, RowBounds.DEFAULT);
  }

  @Override
  public <T> Cursor<T> selectCursor(String statement, Object parameter, RowBounds rowBounds) {
    try {
      MappedStatement ms = configuration.getMappedStatement(statement);
      Cursor<T> cursor = executor.queryCursor(ms, wrapCollection(parameter), rowBounds);
      registerCursor(cursor);
      return cursor;
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error querying database.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public <E> List<E> selectList(String statement) {
    return this.selectList(statement, null);
  }

  @Override
  public <E> List<E> selectList(String statement, Object parameter) {
    // 为了提供多种重载（简化方法使用），和默认值
    // 让参数少的调用参数多的方法，只实现一次
    return this.selectList(statement, parameter, RowBounds.DEFAULT);
  }

  /**
   *  SqlSession 是调用者完成数据库操作的 接口
   *      那么在SqlSession 内部其实是通过 Executor来完成具体的 数据库操作的
   * @param statement  com.bobo.mybati.dao.UserMapper.query 查询的全限定方法名
   * @param parameter 查询的参数
   * @param rowBounds 分页参数
   * @param <E> 查询结果的类型
   * @return 查询结果的集合
   */
  @Override
  public <E> List<E> selectList(String statement, Object parameter, RowBounds rowBounds) {
    try {

      // 1. 元数据检索：根据 Statement ID 从 Configuration（全局配置对象）中
      // 获取 MappedStatement 对象。该对象封装了 XML 标签中配置的所有静态属性（SQL、结果映射等）。
      MappedStatement ms = configuration.getMappedStatement(statement);

      // 2. 执行权委派：调用底层执行器 (Executor) 的查询方法
      // 如果 cacheEnabled = true（默认），Executor会被 CachingExecutor装饰
      // wrapCollection(parameter) 负责对传入的集合或数组进行参数包装，确保 SQL 能够正确解析
      // Executor.NO_RESULT_HANDLER 表示当前查询不使用外部的结果处理器
      return executor.query(ms, wrapCollection(parameter), rowBounds, Executor.NO_RESULT_HANDLER);
    } catch (Exception e) {
      // 异常包装：将底层异常统一转换为 MyBatis 的持久化异常体系
      throw ExceptionFactory.wrapException("Error querying database.  Cause: " + e, e);
    } finally {
      // 清理线程上下文：重置 ErrorContext 中的 ThreadLocal 信息
      ErrorContext.instance().reset();
    }
  }

  @Override
  public void select(String statement, Object parameter, ResultHandler handler) {
    select(statement, parameter, RowBounds.DEFAULT, handler);
  }

  @Override
  public void select(String statement, ResultHandler handler) {
    select(statement, null, RowBounds.DEFAULT, handler);
  }

  @Override
  public void select(String statement, Object parameter, RowBounds rowBounds, ResultHandler handler) {
    try {
      MappedStatement ms = configuration.getMappedStatement(statement);
      executor.query(ms, wrapCollection(parameter), rowBounds, handler);
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error querying database.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public int insert(String statement) {
    return insert(statement, null);
  }

  @Override
  public int insert(String statement, Object parameter) {
    return update(statement, parameter);
  }

  @Override
  public int update(String statement) {
    return update(statement, null);
  }

  /**
   * 【写操作核心入口】执行所有数据库更新操作（INSERT/UPDATE/DELETE）
   *
   * MyBatis 将所有的 DML（数据操作语言）操作统一收敛到该方法中，
   * 通过执行器（Executor）完成具体的 SQL 执行。
   *
   * @param statement  SQL映射ID（例如："namespace.id"）
   * @param parameter   SQL参数对象
   * @return           受影响的行数
   */
  @Override
  public int update(String statement, Object parameter) {
    try {

      /*
       * 1. 【会话脏状态标记】===========================================
       * dirty 标志位是 DefaultSqlSession 的内部状态变量，用于跟踪当前会话
       * 是否执行过写操作。此标志直接影响后续的事务管理行为：
       * - true：会话包含未提交的更改，commit() 时会真正提交事务
       * - false：会话是只读的或所有更改已提交，close() 时无需提交事务
       *
       * 该机制确保了事务的原子性：要么全部提交，要么全部回滚。
       */
      dirty = true;

      /*
       * 2. 【获取映射语句元数据】========================================
       * 从全局配置中根据 statement ID 获取对应的 MappedStatement 对象，
       * 该对象包含了：
       * - SQL 语句（已解析的动态SQL）
       * - 参数映射关系
       * - 结果映射配置
       * - 超时设置等元信息
       */
      MappedStatement ms = configuration.getMappedStatement(statement);

      /*
       * 3. 【委派执行器执行】============================================
       * 将执行权移交给执行器（Executor），Executor 负责：
       * - 获取数据库连接
       * - 预编译SQL并设置参数
       * - 执行SQL并处理结果
       * - 二级缓存的管理（如果开启）
       *
       * wrapCollection(parameter) 的作用：
       * - 如果参数是 Collection 类型，包装为 Map，键为 "collection" 或 "list"
       * - 如果参数是 Array 类型，包装为 Map，键为 "array"
       * - 确保 MyBatis 能够正确解析集合/数组类型的参数
       */
      return executor.update(ms, wrapCollection(parameter));
    } catch (Exception e) {
      // 4. 【异常包装】：将底层产生的 SQL 异常或连接异常统一包装为 MyBatis 持久化异常
      throw ExceptionFactory.wrapException("Error updating database.  Cause: " + e, e);
    } finally {
      // 5. 【清理线程上下文】：重置 ErrorContext，防止 ThreadLocal 信息残留
      ErrorContext.instance().reset();
    }
  }

  @Override
  public int delete(String statement) {
    return update(statement, null);
  }

  @Override
  public int delete(String statement, Object parameter) {
    return update(statement, parameter);
  }

  @Override
  public void commit() {
    commit(false);
  }

  @Override
  public void commit(boolean force) {
    try {
      executor.commit(isCommitOrRollbackRequired(force));
      dirty = false;
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error committing transaction.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public void rollback() {
    rollback(false);
  }

  @Override
  public void rollback(boolean force) {
    try {
      executor.rollback(isCommitOrRollbackRequired(force));
      dirty = false;
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error rolling back transaction.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public List<BatchResult> flushStatements() {
    try {
      return executor.flushStatements();
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error flushing statements.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public void close() {
    try {
      executor.close(isCommitOrRollbackRequired(false));
      closeCursors();
      dirty = false;
    } finally {
      ErrorContext.instance().reset();
    }
  }

  private void closeCursors() {
    if (cursorList != null && !cursorList.isEmpty()) {
      for (Cursor<?> cursor : cursorList) {
        try {
          cursor.close();
        } catch (IOException e) {
          throw ExceptionFactory.wrapException("Error closing cursor.  Cause: " + e, e);
        }
      }
      cursorList.clear();
    }
  }

  @Override
  public Configuration getConfiguration() {
    return configuration;
  }

  /**
   *  获取Mapper接口
   * @param type Mapper interface class  Mapper接口的类型
   * @return Mapper interface proxy object  Mapper接口的代理对象
   * @param <T> Mapper interface type  Mapper接口的类型
   */
  @Override
  public <T> T getMapper(Class<T> type) {
    return configuration.getMapper(type, this);
  }

  @Override
  public Connection getConnection() {
    try {
      return executor.getTransaction().getConnection();
    } catch (SQLException e) {
      throw ExceptionFactory.wrapException("Error getting a new connection.  Cause: " + e, e);
    }
  }

  @Override
  public void clearCache() {
    executor.clearLocalCache();
  }

  private <T> void registerCursor(Cursor<T> cursor) {
    if (cursorList == null) {
      cursorList = new ArrayList<>();
    }
    cursorList.add(cursor);
  }

  private boolean isCommitOrRollbackRequired(boolean force) {
    // 如果 force 为 true，或者 autoCommit = false && dirty = true
    // 返回 true
    return (!autoCommit && dirty) || force;
  }

  /**
   * 【单参数集合/数组包装器】将集合或数组类型的参数统一包装为 Map 类型
   *
   * 这是 MyBatis 参数处理的核心机制之一，主要解决以下问题：
   * 1. 当 Mapper 接口方法只有一个参数且为集合/数组时，MyBatis 无法直接通过 OGNL 访问其元素
   * 2. 为集合/数组类型提供默认的键名，使得在 XML 映射文件中可以通过预设名称引用参数
   * 3. 统一参数访问方式，简化动态 SQL 中 <foreach> 标签的使用
   *
   * 典型场景示例：
   * List<User> selectByIds(List<Long> ids);          // 集合参数
   * List<User> selectByIds(Long[] ids);               // 数组参数
   *
   * XML 中的使用：
   * <foreach collection="list" item="id">     // List 类型用 "list"
   * <foreach collection="array" item="id">    // 数组类型用 "array"
   *
   * @param object 原始参数（可能是 Collection、Array 或普通对象）
   * @return 包装后的对象：
   *         - 如果是 Collection/Array：返回 StrictMap，包含默认键名
   *         - 其他情况：返回原对象
   */
  private Object wrapCollection(final Object object) {

    // 1. 【处理集合类型】================================================
    if (object instanceof Collection) {
      StrictMap<Object> map = new StrictMap<>();

      // 通用集合键名：任何 Collection 子类都可以用 "collection" 访问
      map.put("collection", object);

      // 列表类型特化：如果是 List 类型，额外提供 "list" 键名
      // 这就是为什么在 <foreach> 中可以用 "list" 引用参数
      if (object instanceof List) {
        map.put("list", object);
      }

      return map;
    }

    // 2. 【处理数组类型】================================================
    // 包括基本类型数组 (int[]、long[]) 和对象数组 (Long[]、String[])
    if (object != null && object.getClass().isArray()) {
      StrictMap<Object> map = new StrictMap<>();

      // 数组类型默认键名：任何数组都可以用 "array" 访问
      // 注意：数组没有像 List 那样的特化键名，统一使用 "array"
      map.put("array", object);

      return map;
    }

    // 3. 【其他类型】==================================================
    // 普通对象（非集合、非数组）保持原样返回
    // 这些对象的属性可以通过 OGNL 直接访问，无需特殊处理
    return object;
  }

  public static class StrictMap<V> extends HashMap<String, V> {

    private static final long serialVersionUID = -5741767162221585340L;

    @Override
    public V get(Object key) {
      if (!super.containsKey(key)) {
        throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + this.keySet());
      }
      return super.get(key);
    }

  }

}
