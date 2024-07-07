/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.frameworks.bases;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.annotation.Annotation;
import java.util.Map;

/**
 * Application context holder.
 */
public class ApplicationContextHolder implements ApplicationContextAware {
    private static ApplicationContext CONTEXT;
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        ApplicationContextHolder.CONTEXT=applicationContext;
    }

    /**
     * 根据类型获取容器中的bean
     */
    public static <T> T getBean(Class<T> clazz){
        return CONTEXT.getBean(clazz);
    }

    /**
     * 根据名称获取容器中的bean
     * @param name
     * @return
     */
    public static Object getBean(String name){
        return CONTEXT.getBean(name);
    }

    /**
     * 根据name和type获取bean
     */
    public static <T> T getBean(String name,Class<T> clazz){
        return CONTEXT.getBean(name,clazz);
    }

    /**
     *获取实现了指定接口或者继承了指定类的所有类的集合
     */
    public static <T>Map<String,T> getBeansOfType(Class<T> clazz){
        return CONTEXT.getBeansOfType(clazz);
    }

    /**
     *查找指定bean是否存在指定类型的注解
     */
    public static <A extends Annotation> A findAnnotationOnBean(String beanName,Class<A> annotationType){
        return CONTEXT.findAnnotationOnBean(beanName,annotationType);
    }

    /**
     * 获取application context
     */
    public static ApplicationContext getInstance(){
        return CONTEXT;
    }
}
