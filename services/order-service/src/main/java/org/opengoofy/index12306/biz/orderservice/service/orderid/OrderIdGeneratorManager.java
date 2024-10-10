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

package org.opengoofy.index12306.biz.orderservice.service.orderid;

import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 订单id全局唯一生成器管理
 */
@RequiredArgsConstructor
@Component
public class OrderIdGeneratorManager implements InitializingBean {

    private final RedissonClient redissonClient;
    private final DistributedCache distributedCache;
    private static DistributedIdGenerator DISTRIBUTED_ID_GENERATOR;

    public static String generateId(long userId){
        return DISTRIBUTED_ID_GENERATOR.generateId()+String.valueOf(userId%1000000);
    }
    @Override
    public void afterPropertiesSet() throws Exception {
        String LOCK_KEY="distributed_id_generator_lock_key";
        RLock lock = redissonClient.getLock(LOCK_KEY);
        lock.lock();
        try {
            StringRedisTemplate instance = (StringRedisTemplate) distributedCache.getInstance();
            String DISTRIBUTED_ID_GENERATOR_KEY="distributed_id_generator_config";
            long incremented = Optional.ofNullable(instance.opsForValue().increment(DISTRIBUTED_ID_GENERATOR_KEY)).orElse(0L);
            int NODE_MAX=32;
            if (incremented>NODE_MAX){
                incremented=0;
                instance.opsForValue().set(DISTRIBUTED_ID_GENERATOR_KEY,"0");
            }
            DISTRIBUTED_ID_GENERATOR=new DistributedIdGenerator(incremented);
        }finally {
            lock.unlock();
        }
    }
}
