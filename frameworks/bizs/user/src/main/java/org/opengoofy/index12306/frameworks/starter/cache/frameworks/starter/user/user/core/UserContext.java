package org.opengoofy.index12306.frameworks.starter.cache.frameworks.starter.user.user.core;

import com.alibaba.ttl.TransmittableThreadLocal;

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
        return userInfoDTO.getUserId();
    }

    /**
     * 获取上下文中用户名
     * @return 用户id
     */
    public static String getUsername(){
        UserInfoDTO userInfoDTO = USER_INFO_DTO_THREAD_LOCAL.get();
        return userInfoDTO.getUsername();
    }

    /**
     * 获取上下文中用户真实姓名
     * @return
     */
    public static String getUserRealName(){
        UserInfoDTO userInfoDTO = USER_INFO_DTO_THREAD_LOCAL.get();
        return userInfoDTO.getRealName();
    }
    /**
     * 获取上下文中用户token
     * @return
     */
    public static String getUserToken(){
        UserInfoDTO userInfoDTO = USER_INFO_DTO_THREAD_LOCAL.get();
        return userInfoDTO.getToken();
    }

    /**
     * 清理用户上下文
     */
    public static void removeUser(){
        USER_INFO_DTO_THREAD_LOCAL.remove();
    }
}
