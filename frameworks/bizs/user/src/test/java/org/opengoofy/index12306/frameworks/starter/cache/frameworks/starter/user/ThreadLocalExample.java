package org.opengoofy.index12306.frameworks.starter.cache.frameworks.starter.user;

public class ThreadLocalExample {
       private static ThreadLocal<String> threadLocal = new ThreadLocal<>();

       public static void main(String[] args) {
           threadLocal.set("Hello, World!");
           System.out.println("Thread 1: " + threadLocal.get());

           Thread thread = new Thread(() -> {
               System.out.println("Thread 2: " + threadLocal.get());
           });
           thread.start();
       }
   }