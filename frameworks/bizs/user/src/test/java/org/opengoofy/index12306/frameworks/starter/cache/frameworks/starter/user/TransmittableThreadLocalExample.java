
package org.opengoofy.index12306.frameworks.starter.cache.frameworks.starter.user;

import com.alibaba.ttl.TransmittableThreadLocal;

public class TransmittableThreadLocalExample {
    private static TransmittableThreadLocal<String> ttl = new TransmittableThreadLocal<>();

    public static void main(String[] args) {
        ttl.set("Hello, World!");
        System.out.println("Main thread: " + ttl.get());

        Thread thread = new Thread(() -> {
            System.out.println("Child thread: " + ttl.get());
        });
        thread.start();
    }
}