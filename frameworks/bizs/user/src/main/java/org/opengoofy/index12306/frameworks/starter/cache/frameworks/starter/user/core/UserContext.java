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

package org.opengoofy.index12306.frameworks.starter.cache.frameworks.starter.user.core;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.Optional;

/**
 * 用户上下文
 */
public final class UserContext {
    private static final ThreadLocal<UserInfoDTO> USER_INFO_DTO_THREAD_LOCAL = new TransmittableThreadLocal<>();

    /**
     * 设置用户上下文
     * @param user 用户详情信息
     */
    public static void setUser(UserInfoDTO user){
        USER_INFO_DTO_THREAD_LOCAL.set(user);
    }

    /**
     * 获取上下文中用户ID
     * @return 用户id
     */
    public static String getUserId(){
        UserInfoDTO userInfoDTO = USER_INFO_DTO_THREAD_LOCAL.get();
        return Optional.ofNullable(userInfoDTO).map(UserInfoDTO::getUserId).orElse(null);
    }

    /**
     * 获取上下文中用户名
     * @return 用户id
     */
    public static String getUsername(){
        UserInfoDTO userInfoDTO = USER_INFO_DTO_THREAD_LOCAL.get();
        return Optional.ofNullable(userInfoDTO).map(UserInfoDTO::getUsername).orElse(null);
    }

    /**
     * 获取上下文中用户真实姓名
     * @return
     */
    public static String getUserRealName(){
        UserInfoDTO userInfoDTO = USER_INFO_DTO_THREAD_LOCAL.get();
        return Optional.ofNullable(userInfoDTO).map(UserInfoDTO::getRealName).orElse(null);
    }
    /**
     * 获取上下文中用户token
     * @return
     */
    public static String getUserToken(){
        UserInfoDTO userInfoDTO = USER_INFO_DTO_THREAD_LOCAL.get();
        return Optional.ofNullable(userInfoDTO).map(UserInfoDTO::getToken).orElse(null);
    }

    /**
     * 清理用户上下文
     */
    public static void removeUser(){
        USER_INFO_DTO_THREAD_LOCAL.remove();
    }
}
