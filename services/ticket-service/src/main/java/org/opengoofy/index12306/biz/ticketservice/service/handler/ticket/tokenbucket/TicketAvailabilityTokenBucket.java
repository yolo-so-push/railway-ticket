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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.tokenbucket;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TokenResultDTO;
import org.opengoofy.index12306.framework.starter.common.toolkit.Assert;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.frameworks.bases.Singleton;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.*;

/**
 * 列车车票余量令牌桶，应对海量并发场景下满足并行、限流以及防超卖等场景
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class TicketAvailabilityTokenBucket {
    private final DistributedCache distributedCache;
    private final TrainMapper trainMapper;
    private final TrainStationService trainStationService;
    private final RedissonClient redissonClient;
    private final SeatService seatService;
    private static final String LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH = "lua/ticket_availability_token_bucket.lua";
    private static final String LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH = "lua/ticket_availability_rollback_token_bucket.lua";

    /**
     * 获取车站令牌
     * @param requestParam
     * @return
     */
    public TokenResultDTO takeTokenFromBucket(PurchaseTicketReqDTO requestParam) {
        TrainDO trainDO = distributedCache.safeGet(
                TRAIN_INFO + requestParam.getTrainId(),
                TrainDO.class,
                () -> trainMapper.selectById(requestParam.getTrainId()),
                ADVANCE_TICKET_DAY,
                TimeUnit.DAYS
        );
        List<RouteDTO> routeDTOList = trainStationService.listTrainStationRoute(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival());
        StringRedisTemplate instance = (StringRedisTemplate) distributedCache.getInstance();
        //index12306-ticket-service:ticket_availability_token_bucket:trainId
        String bucketHashKey=TICKET_AVAILABILITY_TOKEN_BUCKET+requestParam.getTrainId();
        Boolean hasKey = distributedCache.hasKey(bucketHashKey);
        if (!hasKey){
            RLock lock = redissonClient.getLock(String.format(LOCK_TICKET_AVAILABILITY_TOKEN_BUCKET, requestParam.getTrainId()));
            if (!lock.tryLock()) {
                throw new ServiceException("购票异常，请稍候再试");
            }
            try {
                hasKey=distributedCache.hasKey(bucketHashKey);
                if (!hasKey){
                    List<Integer> seatTypes = VehicleTypeEnum.findSeatTypesByCode(trainDO.getTrainType());
                    //北京_天津_2 20
                    //startStation_endStation_seatType,count
                    Map<String,String> ticketAvailabilityTokenMap=new HashMap<>();
                    for (RouteDTO routeDTO : routeDTOList) {
                        //每段路线对应座位数量
                        List<SeatTypeCountDTO> seatTypeCountDTOList = seatService.listSeatTypeCount(trainDO.getId(), routeDTO.getStartStation(), routeDTO.getEndStation(), seatTypes);
                        for (SeatTypeCountDTO seatTypeCountDTO : seatTypeCountDTOList) {
                            String cacheKey = StrUtil.join("_", routeDTO.getStartStation(), routeDTO.getEndStation(),seatTypeCountDTO.getSeatType());
                            ticketAvailabilityTokenMap.put(cacheKey, String.valueOf(seatTypeCountDTO.getSeatCount()));
                        }
                    }
                    //index12306-ticket-service:ticket_availability_token_bucket:trainId ,startStation_endStation_seatType,count
                    instance.opsForHash().putAll(bucketHashKey,ticketAvailabilityTokenMap);
                }
            }finally {
                lock.unlock();
            }
        }
        DefaultRedisScript<String> actual=Singleton.get(LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH,()->{
            DefaultRedisScript<String> redisScript=new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_TICKET_AVAILABILITY_TOKEN_BUCKET_PATH)));
            redisScript.setResultType(String.class);
            return redisScript;
        });
        Assert.notNull(actual);
        Map<Integer, Long> seatTypeAndCountMap = requestParam.getPassengers()
                .stream().collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType, Collectors.counting()));
        JSONArray seatTypeArray = seatTypeAndCountMap.entrySet().stream().map(entry -> {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("seatType", String.valueOf(entry.getKey()));
            jsonObject.put("count", String.valueOf(entry.getValue()));
            return jsonObject;
        }).collect(Collectors.toCollection(JSONArray::new));
        List<RouteDTO> takeoutTrainStationRoute = trainStationService
                .listTakeoutTrainStationRoute(requestParam.getTrainId(), requestParam.getDeparture(), requestParam.getArrival());
        String luaScriptKey=StrUtil.join("_",requestParam.getDeparture(),requestParam.getArrival());
        String resultStr=instance.execute(actual, Lists.newArrayList(bucketHashKey,luaScriptKey), JSON.toJSONString(seatTypeArray),JSON.toJSONString(takeoutTrainStationRoute));
        TokenResultDTO tokenResultDTO = JSON.parseObject(resultStr, TokenResultDTO.class);
        //tokenResultDTO.tokenIsNull=false获取令牌成功
        return tokenResultDTO==null?
                TokenResultDTO.builder()
                        .tokenIsNull(Boolean.TRUE)
                        .build() :tokenResultDTO;
    }

    /**
     * 删除令牌，令牌与数据库数据不一致情况下触发
     * @param requestParam
     */
    public void delTokenInBucket(PurchaseTicketReqDTO requestParam) {
        StringRedisTemplate instance = (StringRedisTemplate) distributedCache.getInstance();
        String tokenBucketHashKey=TICKET_AVAILABILITY_TOKEN_BUCKET+requestParam.getTrainId();
        instance.delete(tokenBucketHashKey);
    }

    /**
     * 回滚列车余量令牌，取消订单时触发
     * @param requestParam
     */
    public void rollbackInBucket(TicketOrderDetailRespDTO requestParam) {
        DefaultRedisScript<Long> actual = Singleton.get(LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH, () -> {
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(LUA_TICKET_AVAILABILITY_ROLLBACK_TOKEN_BUCKET_PATH)));
            redisScript.setResultType(Long.class);
            return redisScript;
        });
        Assert.notNull(actual);
        List<TicketOrderPassengerDetailRespDTO> passengerDetails = requestParam.getPassengerDetails();
        Map<Integer, Long> seatTypeCountMap = passengerDetails.stream().collect(Collectors.groupingBy(TicketOrderPassengerDetailRespDTO::getSeatType, Collectors.counting()));
        JSONArray seatTypeCountArray = seatTypeCountMap.entrySet()
                .stream().map(entry -> {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("seatType", entry.getKey());
                    jsonObject.put("count", entry.getValue());
                    return jsonObject;
                }).collect(Collectors.toCollection(JSONArray::new));
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        String hashKey=TICKET_AVAILABILITY_TOKEN_BUCKET+requestParam.getTrainId();
        String luaScriptKey=StrUtil.join("_",requestParam.getDeparture(),requestParam.getArrival());
        List<RouteDTO> takeoutTrainStationRouteList = trainStationService.listTakeoutTrainStationRoute(String.valueOf(requestParam.getTrainId()), requestParam.getDeparture(), requestParam.getArrival());
        Long result = stringRedisTemplate.execute(actual, Lists.newArrayList(hashKey, luaScriptKey), JSON.toJSONString(seatTypeCountArray), JSON.toJSONString(takeoutTrainStationRouteList));
        if (result==null||!Objects.equals(result,0L)){
            log.error("回滚列车余票令牌失败，订单信息：{}", com.alibaba.fastjson2.JSON.toJSONString(requestParam));
            throw new ServiceException("回滚列车余票令牌失败");
        }
    }
}