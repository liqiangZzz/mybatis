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
package org.apache.ibatis.reflection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * [类元数据控制中枢] 结合 Reflector 和 ReflectorFactory，提供对复杂属性表达式的递归解析。
 * <p>
 * 核心设计思想：
 * 1. 组合模式：内部持有一个 Reflector 负责当前类的解析。
 * 2. 递归调用：通过属性分词器（PropertyTokenizer）逐层向下钻取关联类的元数据。
 * @author Clinton Begin
 */
public class MetaClass {

  // 反射工厂：用于在递归解析路径时获取关联类的 Reflector 实例
  private final ReflectorFactory reflectorFactory;

  // 当前类的反射元数据：缓存了当前 Class 的所有属性、方法和 Invoker
  private final Reflector reflector;

  /**
   * 私有构造方法。
   * @param type 目标 Class 类型
   * @param reflectorFactory 全局反射工厂
   */
  private MetaClass(Class<?> type, ReflectorFactory reflectorFactory) {
    this.reflectorFactory = reflectorFactory;
    // 从工厂中获取（或创建）目标类的 Reflector
    this.reflector = reflectorFactory.findForClass(type);
  }

  /**
   * [静态工厂方法] 创建指定类的 MetaClass。
   */
  public static MetaClass forClass(Class<?> type, ReflectorFactory reflectorFactory) {
    return new MetaClass(type, reflectorFactory);
  }

  /**
   * 根据属性名称，获取该属性类型的 MetaClass 对象。
   * 实际上是向属性结构的深层迈进了一步。
   * metaClassForProperty("person") --》 Person
   */
  public MetaClass metaClassForProperty(String name) {
    // 1. 获取当前属性在 Reflector 中记录的 Java 类型
    Class<?> propType = reflector.getGetterType(name);
    // 2. 为该类型创建一个新的 MetaClass
    return MetaClass.forClass(propType, reflectorFactory);
  }

  /**
   * [属性查找] 根据传入的属性名（如 user_name），查找类中定义的最匹配的属性名（如 userName）。
   */
  public String findProperty(String name) {
    // 委托给内部递归逻辑执行路径构建
    StringBuilder prop = buildProperty(name, new StringBuilder());
    return prop.length() > 0 ? prop.toString() : null;
  }

  /**
   * [下划线转驼峰支持] 查找属性，支持可选的下划线自动映射。
   */
  public String findProperty(String name, boolean useCamelCaseMapping) {
    if (useCamelCaseMapping) {
      // 简单移除下划线后进行匹配（利用 Reflector 的不区分大小写 Map）
      name = name.replace("_", "");
    }
    return findProperty(name);
  }

  // --- 获取当前类全量属性名称的方法 ---
  public String[] getGetterNames() {
    return reflector.getGetablePropertyNames();
  }
  public String[] getSetterNames() {
    return reflector.getSetablePropertyNames();
  }


  /**
   * [递归获取 Setter 类型]
   * 对于表达式 a.b.c，此方法将递归到最后一段 'c'，并返回 'c' 对应的参数类型。
   */
  public Class<?> getSetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      // 存在嵌套路径，获取当前属性的 MetaClass 并递归
      MetaClass metaProp = metaClassForProperty(prop.getName());
      return metaProp.getSetterType(prop.getChildren());
    } else {
      // 到达路径终点，直接从 Reflector 获取类型
      return reflector.getSetterType(prop.getName());
    }
  }

  /**
   * [递归获取 Getter 类型]
   * 处理逻辑同 Setter，但额外通过 getGetterType(PropertyTokenizer) 处理了集合泛型问题。
   */
  public Class<?> getGetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      MetaClass metaProp = metaClassForProperty(prop);
      return metaProp.getGetterType(prop.getChildren());
    }
    // issue #506. Resolve the type inside a Collection Object
    // 处理路径终点，特别关注集合索引场景 (issue #506)
    return getGetterType(prop);
  }

  /**
   * 创建嵌套属性的 MetaClass，支持索引符号处理。
   */
  private MetaClass metaClassForProperty(PropertyTokenizer prop) {
    Class<?> propType = getGetterType(prop);
    return MetaClass.forClass(propType, reflectorFactory);
  }

  /**
   * [核心：类型推断] 获取属性的返回类型，特别处理了泛型集合。
   * 如果表达式是 list[0]，且属性是 List<User>，它将推断出返回类型为 User.class。
   */
  private Class<?> getGetterType(PropertyTokenizer prop) {
    // 1. 先通过原始 Reflector 获取基础类型
    Class<?> type = reflector.getGetterType(prop.getName());
    // 2. 如果包含索引（如 [0]）且该属性是集合类型
    if (prop.getIndex() != null && Collection.class.isAssignableFrom(type)) {
      // 3. 利用 TypeParameterResolver 解析泛型参数的具体类型
      Type returnType = getGenericGetterType(prop.getName());
      if (returnType instanceof ParameterizedType) {
        Type[] actualTypeArguments = ((ParameterizedType) returnType).getActualTypeArguments();
        if (actualTypeArguments != null && actualTypeArguments.length == 1) {
          returnType = actualTypeArguments[0];
          if (returnType instanceof Class) {
            type = (Class<?>) returnType;
          } else if (returnType instanceof ParameterizedType) {
            // 处理多层泛型嵌套
            type = (Class<?>) ((ParameterizedType) returnType).getRawType();
          }
        }
      }
    }
    return type;
  }

  private Type getGenericGetterType(String propertyName) {
    try {
      Invoker invoker = reflector.getGetInvoker(propertyName);
      if (invoker instanceof MethodInvoker) {
        Field _method = MethodInvoker.class.getDeclaredField("method");
        _method.setAccessible(true);
        Method method = (Method) _method.get(invoker);
        return TypeParameterResolver.resolveReturnType(method, reflector.getType());
      } else if (invoker instanceof GetFieldInvoker) {
        Field _field = GetFieldInvoker.class.getDeclaredField("field");
        _field.setAccessible(true);
        Field field = (Field) _field.get(invoker);
        return TypeParameterResolver.resolveFieldType(field, reflector.getType());
      }
    } catch (NoSuchFieldException | IllegalAccessException ignored) {
    }
    return null;
  }

  /**
   * [递归校验 Setter 权限] 检查路径 a.b.c 是否全线具备写权限。
   */
  public boolean hasSetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      // 当前段必须有 Setter，才能继续递归
      if (reflector.hasSetter(prop.getName())) {
        MetaClass metaProp = metaClassForProperty(prop.getName());
        return metaProp.hasSetter(prop.getChildren());
      } else {
        return false;
      }
    } else {
      // 路径终点，直接检查 Reflector
      return reflector.hasSetter(prop.getName());
    }
  }

  /**
   * [递归校验 Getter 权限] 逻辑同 hasSetter。
   */
  public boolean hasGetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      if (reflector.hasGetter(prop.getName())) {
        MetaClass metaProp = metaClassForProperty(prop);
        return metaProp.hasGetter(prop.getChildren());
      } else {
        return false;
      }
    } else {
      return reflector.hasGetter(prop.getName());
    }
  }

  // --- 获取物理执行器 Invoker 的直接映射方法 ---
  public Invoker getGetInvoker(String name) {
    return reflector.getGetInvoker(name);
  }

  public Invoker getSetInvoker(String name) {
    return reflector.getSetInvoker(name);
  }


  /**
   * [递归构建属性路径]
   * 作用：将输入的模糊路径（如 dept.manager_name）
   * 纠正为 Java 类中的物理路径（如 department.managerName）。
   */
  private StringBuilder buildProperty(String name, StringBuilder builder) {
    PropertyTokenizer prop = new PropertyTokenizer(name);// 解析属性表达式 orders[0].items[0].name
    if (prop.hasNext()) { // 判断是否有子表达式  name=orders index=0 indexedName=orders[0]

      // 查找当前段的真实属性名
      String propertyName = reflector.findPropertyName(prop.getName());
      if (propertyName != null) {
        builder.append(propertyName);
        builder.append("."); // orders.
        // 递归处理子路径
        MetaClass metaProp = metaClassForProperty(propertyName); // orders --> 对应的类型
        metaProp.buildProperty(prop.getChildren(), builder); // 递归处理  并将结果保存到 builder 中
      }
    } else { // 递归的出口
      // 路径终点：追加最后一段属性名
      String propertyName = reflector.findPropertyName(name);
      if (propertyName != null) {
        builder.append(propertyName);
      }
    }
    return builder;
  }

  /**
   * 检查当前类是否包含默认的（无参）构造方法。
   */
  public boolean hasDefaultConstructor() {
    return reflector.hasDefaultConstructor();
  }

}
