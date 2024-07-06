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

package org.opengoofy.index12306.frameworks.starter.cache.config;

import lombok.AllArgsConstructor;
import org.opengoofy.index12306.frameworks.starter.cache.RedisKeySerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 缓存配置自动配置
 */
@AllArgsConstructor
@EnableConfigurationProperties({BloomFilterPenetrateProperties.class, RedisDistributedProperties.class})
public class CacheAutoConfiguration {
    private final RedisDistributedProperties redisDistributedProperties;

    /**
     * 创建Redis key序列化器，可自定义key prefix
     */
    @Bean
    public RedisKeySerializer redisKeySerializer(){
        String prefix = redisDistributedProperties.getPrefix();
        String prefixCharset = redisDistributedProperties.getPrefixCharset();
        return new RedisKeySerializer(prefix,prefixCharset);
    }
}
