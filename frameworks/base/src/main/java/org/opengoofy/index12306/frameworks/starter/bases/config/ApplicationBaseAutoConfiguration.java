package org.opengoofy.index12306.frameworks.starter.bases.config;

import org.opengoofy.index12306.frameworks.starter.bases.ApplicationContextHolder;
import org.opengoofy.index12306.frameworks.starter.bases.safe.FastJsonSafeMode;
import org.opengoofy.index12306.frameworks.starter.bases.init.ApplicationContentPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * 应用基础自动装配
 */
public class ApplicationBaseAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApplicationContextHolder congoApplicationContextHolder(){
        return new ApplicationContextHolder();
    }
    @Bean
    @ConditionalOnMissingBean
    public ApplicationContentPostProcessor congoApplicationContentPostProcessor(ApplicationContext applicationContext){
        return new ApplicationContentPostProcessor(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(value = "framework.fastjson.safa-mode", havingValue = "true")
    public FastJsonSafeMode congoFastJsonSafeMode(){
        return new FastJsonSafeMode();
    }
}
