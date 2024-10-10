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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.query;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.common.collect.Maps;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.RegionDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.StationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.RegionMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.StationMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_QUERY_ALL_REGION_LIST;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.QUERY_ALL_REGION_LIST;

/**
 *
 */
@RequiredArgsConstructor
@Component
public class TrainTicketQueryParamVerifyChainFilter implements TrainTicketQueryChainFilter<TicketPageQueryReqDTO> {
    private final DistributedCache distributedCache;
    private final RedissonClient redissonClient;
    private static boolean CACHE_DATA_ISNULL_AND_LOAD_FLAG=false;
    private final RegionMapper regionMapper;
    private final StationMapper stationMapper;
    @Override
    public void handler(TicketPageQueryReqDTO requestParam) {
        StringRedisTemplate stringRedisTemplate= (StringRedisTemplate) distributedCache.getInstance();
        HashOperations<String, Object, Object> hashOperations = stringRedisTemplate.opsForHash();
        List<Object> actualExistList = hashOperations.multiGet(QUERY_ALL_REGION_LIST, ListUtil.toList(requestParam.getFromStation(),requestParam.getToStation()));
        long count = actualExistList.stream().filter(Objects::isNull).count();
        if (count==0L){
            return;
        }
        //出发地为空或者目的地不存在（缓存数据为空并且已经加载）
        if (count==1L||(count==2L&&CACHE_DATA_ISNULL_AND_LOAD_FLAG&&distributedCache.hasKey(QUERY_ALL_REGION_LIST))){
            throw new ClientException("出发地或目的不存在");
        }
        RLock lock = redissonClient.getLock(LOCK_QUERY_ALL_REGION_LIST);
        lock.lock();
        try {
            if (distributedCache.hasKey(QUERY_ALL_REGION_LIST)){
                actualExistList=hashOperations.multiGet(QUERY_ALL_REGION_LIST,ListUtil.toList(requestParam.getFromStation(),requestParam.getToStation()));
                count=actualExistList.stream().filter(Objects::nonNull).count();
                if (count!=2L){
                    throw new ClientException("出发地或目的地不存在");
                }
                return;
            }
            List<RegionDO> regionDOS = regionMapper.selectList(Wrappers.emptyWrapper());
            List<StationDO> stationDOS = stationMapper.selectList(Wrappers.emptyWrapper());
            HashMap<Object, Object> regionValueMap = Maps.newHashMap();
            for (RegionDO regionDO : regionDOS) {
                regionValueMap.put(regionDO.getCode(),regionDO.getName());
            }
            for (StationDO stationDO : stationDOS) {
                regionValueMap.put(stationDO.getCode(),stationDO.getName());
            }
            hashOperations.putAll(QUERY_ALL_REGION_LIST,regionValueMap);
            //数据已经加载到了缓存中
            CACHE_DATA_ISNULL_AND_LOAD_FLAG=true;
            //存在数据中与请求参数相同的个数
            count=regionValueMap.keySet().stream()
                    .filter(e-> StrUtil.equalsAny(e.toString(),requestParam.getFromStation(),requestParam.getToStation()))
                    .count();
            if (count!=2L){
                throw new ClientException("出发地或者目的地不存在");
            }
        }finally {
            lock.unlock();
        }
    }

    @Override
    public int getOrder() {
        return 20;
    }
}
