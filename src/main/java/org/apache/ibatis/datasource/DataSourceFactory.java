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
package org.apache.ibatis.datasource;

import java.util.Properties;
import javax.sql.DataSource;

/**
 * DataSource 工厂接口，用于创建和配置 javax.sql.DataSource 对象
 *
 * <p>MyBatis 通过该工厂接口实现对不同数据源实现（如连接池）的解耦，
 * 内置实现包括 UNPOOLED、POOLED、JNDI 三种数据源工厂。
 * <p>
 * DataSourceFactory 在全局配置文件加载解析到 environments 标签中的 dataSource 标签的时候会初始化，且完成 setProperties 的处理
 * @author Clinton Begin
 */
public interface DataSourceFactory {

  /**
   * 设置 DataSource 的相关属性
   *
   * <p>该方法在 DataSourceFactory 实现类被初始化后立即调用，
   * 用于将 MyBatis 配置文件中 <dataSource> 节点下的 <property> 配置
   * 传递给具体的 DataSource 实现。
   *
   * <p>常见属性包括：
   * <ul>
   *   <li>driver - 数据库驱动类名</li>
   *   <li>url - 数据库连接 URL</li>
   *   <li>username - 数据库用户名</li>
   *   <li>password - 数据库密码</li>
   *   <li>defaultAutoCommit - 是否自动提交</li>
   *   <li>maxActive - 最大活跃连接数（连接池）</li>
   * </ul>
   *
   * @param props 包含所有配置属性的 Properties 对象
   */
  void setProperties(Properties props);

  /**
   * 获取 DataSource 对象
   *
   * <p>在 setProperties 方法执行后调用，返回一个完全配置好的
   * javax.sql.DataSource 实例。该实例将被 MyBatis 用于获取数据库连接。
   *
   * <p>根据不同的实现类，返回的 DataSource 类型也不同：
   * <ul>
   *   <li>UnpooledDataSourceFactory - 返回每次请求都创建新连接的 UnpooledDataSource</li>
   *   <li>PooledDataSourceFactory - 返回带有连接池功能的 PooledDataSource</li>
   *   <li>JndiDataSourceFactory - 返回从 JNDI 容器中查找的 DataSource</li>
   * </ul>
   *
   * @return 配置完成的 DataSource 实例
   */
  DataSource getDataSource();

}
