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
package org.apache.ibatis.datasource.pooled;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSourceFactory;

/**
 * [池化数据源工厂] 负责创建并配置具备连接池能力的 PooledDataSource 实例。
 * <p>
 * 继承体系说明：
 * 该类继承自 UnpooledDataSourceFactory，从而复用了父类中极其复杂的属性注入逻辑（MetaObject 映射）。
 * @author Clinton Begin
 */
public class PooledDataSourceFactory extends UnpooledDataSourceFactory {

  public PooledDataSourceFactory() {
    /*
     * 【核心实例化动作】：
     * 在构造函数中，将父类定义的 dataSource 成员变量初始化为 PooledDataSource 实例。
     *
     * 逻辑联动：
     * 由于父类的 setProperties 方法是基于 MetaObject（反射）工作的，
     * 所以当 MyBatis 解析 XML 中的 <property> 时，会自动调用 PooledDataSource
     * 特有的 Setter 方法（如 setPoolMaximumActiveConnections），从而完成池化参数的配置。
     */
    this.dataSource = new PooledDataSource();
  }

}
