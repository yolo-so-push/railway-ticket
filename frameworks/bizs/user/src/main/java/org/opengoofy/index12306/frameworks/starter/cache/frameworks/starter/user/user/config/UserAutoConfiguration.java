package org.opengoofy.index12306.frameworks.starter.cache.frameworks.starter.user.user.config;

import org.opengoofy.index12306.frameworks.starter.cache.frameworks.starter.user.bases.constant.FilterOrderConstant;
import org.opengoofy.index12306.frameworks.starter.cache.frameworks.starter.user.user.core.UserTransmitFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * 用户配置自动装配
 */
@ConditionalOnWebApplication
public class UserAutoConfiguration {

    /**
     * 用户信息传递
     */
    @Bean
    public FilterRegistrationBean<UserTransmitFilter> globalUserTransmitFilter(){
        FilterRegistrationBean<UserTransmitFilter> registrationBean=new FilterRegistrationBean<>();
        registrationBean.setFilter(new UserTransmitFilter());
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(FilterOrderConstant.USER_TRANSMIT_FILTER_ORDER);
        return registrationBean;
    }
}
