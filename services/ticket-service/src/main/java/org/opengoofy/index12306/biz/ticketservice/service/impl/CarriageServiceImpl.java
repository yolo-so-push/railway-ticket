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

package org.opengoofy.index12306.biz.ticketservice.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.CarriageDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.CarriageMapper;
import org.opengoofy.index12306.biz.ticketservice.service.CarriageService;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.cache.core.CacheLoader;
import org.opengoofy.index12306.framework.starter.cache.toolkit.CacheUtil;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_QUERY_CARRIAGE_NUMBER_LIST;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_CARRIAGE;

@Service
@RequiredArgsConstructor
public class CarriageServiceImpl implements CarriageService {

    private final DistributedCache distributedCache;
    private final CarriageMapper carriageMapper;
    private final RedissonClient redissonClient;

    @Override
    public List<String> listCarriageNumber(String trainId, Integer carriageType) {
        final String key=TRAIN_CARRIAGE+trainId;
        return safeGetCarriageNumber(
                key,
                trainId,
                carriageType,
                ()->{
                    LambdaQueryWrapper<CarriageDO> queryWrapper = Wrappers.lambdaQuery(CarriageDO.class)
                            .eq(CarriageDO::getTrainId, trainId)
                            .eq(CarriageDO::getCarriageType, carriageType);
                    List<CarriageDO> carriageDOS = carriageMapper.selectList(queryWrapper);
                    List<String> carriageNumber = carriageDOS.stream().map(e -> e.getCarriageNumber()).map(Object::toString).collect(Collectors.toList());
                    return StrUtil.join(StrUtil.COMMA,carriageNumber);
                }
        );
    }

    private List<String> safeGetCarriageNumber(String key, String trainId, Integer carriageType, CacheLoader<String> loader) {
        String result=getCarriageNumber(key,carriageType);
        if (!CacheUtil.isNullOrBlank(result)){
            return StrUtil.split(result,StrUtil.COMMA);
        }
        RLock lock = redissonClient.getLock(String.format(LOCK_QUERY_CARRIAGE_NUMBER_LIST, trainId));
        lock.lock();
        try {
            if (CacheUtil.isNullOrBlank(result=getCarriageNumber(key,carriageType))){
                if (CacheUtil.isNullOrBlank(result=loadAndSet(key,carriageType,loader))){
                    return Collections.emptyList();
                }
            }
        }finally {
            lock.unlock();
        }
        return StrUtil.split(result,StrUtil.COMMA);
    }

    private String loadAndSet(String key, Integer carriageType, CacheLoader<String> loader) {
        String res = loader.load();
        if (CacheUtil.isNullOrBlank(res)){
            return res;
        }
        getHashOperations().putIfAbsent(key,String.valueOf(carriageType),res);
        return res;
    }

    private String getCarriageNumber(String key, Integer carriageType) {
        HashOperations<String, Object, Object> hashOperations = getHashOperations();
        return Optional.ofNullable(hashOperations.get(key,String.valueOf(carriageType))).map(Object::toString).orElse("");
    }

    private HashOperations<String, Object, Object> getHashOperations() {
        StringRedisTemplate instance = (StringRedisTemplate) distributedCache.getInstance();
        HashOperations<String, Object, Object> hashOperations = instance.opsForHash();
        return hashOperations;
    }
}
