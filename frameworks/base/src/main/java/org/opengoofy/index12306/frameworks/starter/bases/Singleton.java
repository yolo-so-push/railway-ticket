package org.opengoofy.index12306.frameworks.starter.bases;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 单例对象容器
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Singleton {
    private static final ConcurrentHashMap<String,Object> SINGLETON_OBJECT_POOL=new ConcurrentHashMap<>();

    /**
     * 根据key获取单例对象
     */
    public static <T> T get(String key){
        Object result = SINGLETON_OBJECT_POOL.get(key);
        return result==null?null:(T)result;
    }

    /**
     * * 根据 key 获取单例对象
     * 为空时，通过 supplier 构建单例对象并放入容器
     */
    public static <T> T get(String key,Supplier<T> supplier){
        Object result = SINGLETON_OBJECT_POOL.get(key);
        if (result==null&&(result=supplier.get())!=null){
            SINGLETON_OBJECT_POOL.put(key,result);
        }
        return result!=null?(T)result:null;
    }

    /**
     * 将对象放入容器中
     */
    public static void put(Object value){
        put(value.getClass().getName(),value);
    }

    /**
     *将对象放入容器中
     */
    public static void put(String key,Object value){
        SINGLETON_OBJECT_POOL.put(key,value);
    }
}
