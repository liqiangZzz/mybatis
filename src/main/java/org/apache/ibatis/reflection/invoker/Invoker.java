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
package org.apache.ibatis.reflection.invoker;

import java.lang.reflect.InvocationTargetException;

/**
 * [反射调用器接口] 统一封装了对 Java 类成员（字段或方法）的访问协议。
 * <p>
 * 在 MyBatis 的元数据解析系统中，Invoker 用于屏蔽底层反射细节。
 * 无论目标是直接访问 Field，还是通过 Getter/Setter 方
 * @author Clinton Begin
 */
public interface Invoker {
  /**
   * 执行反射调用。
   *
   * @param target 目标对象（即要在哪个实例上执行操作）
   * @param args   参数数组。
   *               - 若是读取操作（Getter/FieldGet），传 null。
   *               - 若是写入操作（Setter/FieldSet），传入对应的实参。
   * @return 执行结果。读取操作返回属性值，写入操作通常返回 null。
   * @throws IllegalAccessException    反射访问权限异常（如访问私有成员）
   * @throws InvocationTargetException 反射目标执行异常（如被调用的方法内部报错）
   */
  Object invoke(Object target, Object[] args) throws IllegalAccessException, InvocationTargetException;

  /**
   * 获取当前执行器关联的属性类型。
   *
   * @return 如果是字段，返回字段类型；如果是方法，返回返回类型或参数类型。
   */
  Class<?> getType();
}
