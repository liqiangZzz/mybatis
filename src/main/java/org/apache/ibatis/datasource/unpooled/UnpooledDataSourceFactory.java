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
package org.apache.ibatis.datasource.unpooled;

import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.datasource.DataSourceException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

/**
 * @author Clinton Begin
 */
public class UnpooledDataSourceFactory implements DataSourceFactory {

  private static final String DRIVER_PROPERTY_PREFIX = "driver.";
  private static final int DRIVER_PROPERTY_PREFIX_LENGTH = DRIVER_PROPERTY_PREFIX.length();

  protected DataSource dataSource;

  public UnpooledDataSourceFactory() {
    this.dataSource = new UnpooledDataSource();
  }

  /**
   * [属性注入逻辑] 将外部配置参数映射并注入到 UnpooledDataSource 实例中。
   * <p>
   * 此方法体现了 MyBatis 利用反射工具箱（MetaObject）进行动态对象填充的设计思想。
   *
   * @param properties 包含在 XML 配置文件中定义的所有数据源属性键值对
   */
  @Override
  public void setProperties(Properties properties) {

    // 1. 创建专门存储 JDBC 驱动特定参数的容器（如：characterEncoding, socketTimeout 等）
    Properties driverProperties = new Properties();

    // 2. 【核心工具】：利用 MetaObject 包装当前的 dataSource 实例。
    // MetaObject 是 MyBatis 内部极其强大的反射工具，支持对对象属性的原子化读写。
    MetaObject metaDataSource = SystemMetaObject.forObject(dataSource);

    // 3. 遍历 Properties 集合，集合中配置了数据源需要的信息
    for (Object key : properties.keySet()) {
      // 获取属性名称
      String propertyName = (String) key;

      /*
       * 分支 A：处理 JDBC 驱动特有属性。
       * 规则：在 XML 中以 "driver." 开头的配置项（如 driver.encoding）。
       * 逻辑：剥离 "driver." 前缀后，统一存入独立的 Properties 对象，最终一次性传给驱动程序。
       */
      if (propertyName.startsWith(DRIVER_PROPERTY_PREFIX)) {
        // 以 "driver." 开头的配置项是对 DataSource 的配置
        String value = properties.getProperty(propertyName);
        driverProperties.setProperty(propertyName.substring(DRIVER_PROPERTY_PREFIX_LENGTH), value);
      }
      /*
       * 分支 B：处理数据源标准属性。
       * 逻辑：检查 UnpooledDataSource 类中是否定义了该属性的 Setter 方法（如 setUrl, setUsername）。
       */
      else if (metaDataSource.hasSetter(propertyName)) {
        // 有该属性的 setter 方法
        String value = (String) properties.get(propertyName);
        // 类型转换：将 XML 中的 String 类型转换为 Setter 方法所需的具体 Java 类型（如 int, boolean）
        Object convertedValue = convertValue(metaDataSource, propertyName, value);
        // 执行反射赋值
        metaDataSource.setValue(propertyName, convertedValue);
      }
      // 容错处理：若配置了无法识别的属性，直接抛出异常，防止连接异常
      else {
        throw new DataSourceException("Unknown DataSource property: " + propertyName);
      }
    }
    // 4. 【批量注入】：若存在驱动特有参数，将其通过 setter 方法注入到数据源对象的 driverProperties 字段中
    if (driverProperties.size() > 0) {
      // 设置 DataSource.driverProperties 的属性值
      metaDataSource.setValue("driverProperties", driverProperties);
    }
  }

  @Override
  public DataSource getDataSource() {
    return dataSource;
  }

  /**
   * 根据属性类型进行类型转换  主要是 Integer Long Boolean 类型
   * @param metaDataSource
   * @param propertyName
   * @param value
   * @return
   */
  private Object convertValue(MetaObject metaDataSource, String propertyName, String value) {
    Object convertedValue = value;
    Class<?> targetType = metaDataSource.getSetterType(propertyName);
    if (targetType == Integer.class || targetType == int.class) {
      convertedValue = Integer.valueOf(value);
    } else if (targetType == Long.class || targetType == long.class) {
      convertedValue = Long.valueOf(value);
    } else if (targetType == Boolean.class || targetType == boolean.class) {
      convertedValue = Boolean.valueOf(value);
    }
    return convertedValue;
  }

}
