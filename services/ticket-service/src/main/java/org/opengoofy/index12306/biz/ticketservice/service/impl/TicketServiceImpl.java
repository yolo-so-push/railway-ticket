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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.Lists;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.*;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.*;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.*;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.*;
import org.opengoofy.index12306.biz.ticketservice.dto.req.*;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.*;
import org.opengoofy.index12306.biz.ticketservice.remote.PayRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.TicketOrderRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.*;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TicketService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.biz.ticketservice.service.cache.SeatMarginCacheLoader;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.dto.TokenResultDTO;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select.TrainSeatTypeSelector;
import org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.tokenbucket.TicketAvailabilityTokenBucket;
import org.opengoofy.index12306.biz.ticketservice.toolkit.DateUtil;
import org.opengoofy.index12306.biz.ticketservice.toolkit.TimeStringComparator;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.cache.toolkit.CacheUtil;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.designpattern.chain.AbstractChainContext;
import org.opengoofy.index12306.frameworks.bases.ApplicationContextHolder;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.Index12306Constant.ADVANCE_TICKET_DAY;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.*;
import static org.opengoofy.index12306.biz.ticketservice.common.enums.TicketChainMarkEnum.TRAIN_QUERY_FILTER;
import static org.opengoofy.index12306.biz.ticketservice.toolkit.DateUtil.convertDateToLocalTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class TicketServiceImpl extends ServiceImpl<TicketMapper, TicketDO> implements TicketService, CommandLineRunner {

    private final AbstractChainContext<TicketPageQueryReqDTO> ticketPageQueryReqDTOAbstractChainContext;
    private final AbstractChainContext<PurchaseTicketReqDTO> ticketPurchaseRespDTOAbstractChainContext;
    private final AbstractChainContext<RefundTicketReqDTO> refundReqDTOAbstractChainContext;
    private final DistributedCache distributedCache;
    private final RedissonClient redissonClient;
    private final StationMapper stationMapper;
    private final TrainStationRelationMapper trainStationRelationMapper;
    private final TrainMapper trainMapper;
    private final TrainStationPriceMapper trainStationPriceMapper;
    private final SeatMarginCacheLoader seatMarginCacheLoader;
    private final ConfigurableEnvironment environment;
    private final TrainSeatTypeSelector trainSeatTypeSelector;
    @Value("${framework.cache.redis.prefix:}")
    private String cacheRedisPrefix;
    @Value("${ticket.availability.cache-update.type:}")
    private String ticketAvailabilityCacheUpdateType;
    private TicketService ticketService;
    private final TicketOrderRemoteService ticketOrderRemoteService;
    private final TicketAvailabilityTokenBucket ticketAvailabilityTokenBucket;
    private final SeatService seatService;
    private final TrainStationService trainStationService;
    private final PayRemoteService payRemoteService;
    @Override
    public TicketPageQueryRespDTO pageListTicketQueryV1(TicketPageQueryReqDTO requestParam) {
        ticketPageQueryReqDTOAbstractChainContext.handler(TRAIN_QUERY_FILTER.name(), requestParam);
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        //REGION_TRAIN_STATION_MAPPING station.code(车站编码) station.regionName(地区名称) 例如(REGION_TRAIN_STATION_MAPPING，VNP,北京)
        List<Object> stationDetails = stringRedisTemplate.opsForHash()
                .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
        long count = stationDetails.stream().filter(Objects::isNull).count();
        if (count>0){
            //这里对字符串加锁，性能很差
            RLock lock = redissonClient.getLock(LOCK_REGION_TRAIN_STATION_MAPPING);
            lock.lock();
            try {
                stationDetails = stringRedisTemplate.opsForHash()
                        .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
                count = stationDetails.stream().filter(Objects::isNull).count();
                if (count > 0) {
                    List<StationDO> stationDOList = stationMapper.selectList(Wrappers.emptyWrapper());
                    Map<String, String> regionTrainStationMap = new HashMap<>();
                    stationDOList.forEach(each -> regionTrainStationMap.put(each.getCode(), each.getRegionName()));
                    stringRedisTemplate.opsForHash().putAll(REGION_TRAIN_STATION_MAPPING, regionTrainStationMap);
                    stationDetails = new ArrayList<>();
                    stationDetails.add(regionTrainStationMap.get(requestParam.getFromStation()));
                    stationDetails.add(regionTrainStationMap.get(requestParam.getToStation()));
                }
            } finally {
                lock.unlock();
            }
        }
        //查询所有北京_杭州的车次
        List<TicketListDTO> seatResults = new ArrayList<>();
        //index12306-ticket-service:region_train_station:北京_杭州
        String buildRegionTrainStationHashKey = String.format(REGION_TRAIN_STATION, stationDetails.get(0), stationDetails.get(1));
        Map<Object, Object> regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);
        if (MapUtil.isEmpty(regionTrainStationAllMap)) {
            RLock lock = redissonClient.getLock(LOCK_REGION_TRAIN_STATION);
            lock.lock();
            try {
                regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);
                if (MapUtil.isEmpty(regionTrainStationAllMap)) {
                    LambdaQueryWrapper<TrainStationRelationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationRelationDO.class)
                            .eq(TrainStationRelationDO::getStartRegion, stationDetails.get(0))
                            .eq(TrainStationRelationDO::getEndRegion, stationDetails.get(1));
                    List<TrainStationRelationDO> trainStationRelationList = trainStationRelationMapper.selectList(queryWrapper);
                    for (TrainStationRelationDO each : trainStationRelationList) {
                        TrainDO trainDO = distributedCache.safeGet(
                                TRAIN_INFO + each.getTrainId(),
                                TrainDO.class,
                                () -> trainMapper.selectById(each.getTrainId()),
                                ADVANCE_TICKET_DAY,
                                TimeUnit.DAYS);
                        TicketListDTO result = new TicketListDTO();
                        result.setTrainId(String.valueOf(trainDO.getId()));
                        result.setTrainNumber(trainDO.getTrainNumber());
                        result.setDepartureTime(convertDateToLocalTime(each.getDepartureTime(), "HH:mm"));
                        result.setArrivalTime(convertDateToLocalTime(each.getArrivalTime(), "HH:mm"));
                        result.setDuration(DateUtil.calculateHourDifference(each.getDepartureTime(), each.getArrivalTime()));
                        result.setDeparture(each.getDeparture());
                        result.setArrival(each.getArrival());
                        result.setDepartureFlag(each.getDepartureFlag());
                        result.setArrivalFlag(each.getArrivalFlag());
                        result.setTrainType(trainDO.getTrainType());
                        result.setTrainBrand(trainDO.getTrainBrand());
                        if (StrUtil.isNotBlank(trainDO.getTrainTag())) {
                            result.setTrainTags(StrUtil.split(trainDO.getTrainTag(), ","));
                        }
                        long betweenDay = cn.hutool.core.date.DateUtil.betweenDay(each.getDepartureTime(), each.getArrivalTime(), false);
                        result.setDaysArrived((int) betweenDay);
                        result.setSaleStatus(new Date().after(trainDO.getSaleTime()) ? 0 : 1);
                        result.setSaleTime(convertDateToLocalTime(trainDO.getSaleTime(), "MM-dd HH:mm"));
                        seatResults.add(result);
                        regionTrainStationAllMap.put(CacheUtil.buildKey(String.valueOf(each.getTrainId()), each.getDeparture(), each.getArrival()), JSON.toJSONString(result));
                    }
                    //index12306-ticket-service:region_train_station:北京_杭州, 1_北京南_杭州西,TicketListDTO
                    stringRedisTemplate.opsForHash().putAll(buildRegionTrainStationHashKey, regionTrainStationAllMap);
                }
            } finally {
                lock.unlock();
            }
        }
        seatResults = CollUtil.isEmpty(seatResults)
                ? regionTrainStationAllMap.values().stream().map(each -> JSON.parseObject(each.toString(), TicketListDTO.class)).toList()
                : seatResults;
        seatResults = seatResults.stream().sorted(new TimeStringComparator()).toList();
        for (TicketListDTO each : seatResults) {
            String trainStationPriceStr = distributedCache.safeGet(
                    String.format(TRAIN_STATION_PRICE, each.getTrainId(), each.getDeparture(), each.getArrival()),
                    String.class,
                    () -> {
                        LambdaQueryWrapper<TrainStationPriceDO> trainStationPriceQueryWrapper = Wrappers.lambdaQuery(TrainStationPriceDO.class)
                                .eq(TrainStationPriceDO::getDeparture, each.getDeparture())
                                .eq(TrainStationPriceDO::getArrival, each.getArrival())
                                .eq(TrainStationPriceDO::getTrainId, each.getTrainId());
                        return JSON.toJSONString(trainStationPriceMapper.selectList(trainStationPriceQueryWrapper));
                    },
                    ADVANCE_TICKET_DAY,
                    TimeUnit.DAYS
            );
            List<TrainStationPriceDO> trainStationPriceDOList = JSON.parseArray(trainStationPriceStr, TrainStationPriceDO.class);
            List<SeatClassDTO> seatClassList = new ArrayList<>();
            trainStationPriceDOList.forEach(item -> {
                String seatType = String.valueOf(item.getSeatType());
                String keySuffix = StrUtil.join("_", each.getTrainId(), item.getDeparture(), item.getArrival());
                Object quantityObj = stringRedisTemplate.opsForHash().get(TRAIN_STATION_REMAINING_TICKET + keySuffix, seatType);
                int quantity = Optional.ofNullable(quantityObj)
                        .map(Object::toString)
                        .map(Integer::parseInt)
                        .orElseGet(() -> {
                            Map<String, String> seatMarginMap = seatMarginCacheLoader.load(String.valueOf(each.getTrainId()), seatType, item.getDeparture(), item.getArrival());
                            return Optional.ofNullable(seatMarginMap.get(String.valueOf(item.getSeatType()))).map(Integer::parseInt).orElse(0);
                        });
                seatClassList.add(new SeatClassDTO(item.getSeatType(), quantity, new BigDecimal(item.getPrice()).divide(new BigDecimal("100"), 1, RoundingMode.HALF_UP), false));
            });
            each.setSeatClassList(seatClassList);
        }
        return TicketPageQueryRespDTO.builder()
                .trainList(seatResults)
                .departureStationList(buildDepartureStationList(seatResults))
                .arrivalStationList(buildArrivalStationList(seatResults))
                .trainBrandList(buildTrainBrandList(seatResults))
                .seatClassTypeList(buildSeatClassList(seatResults))
                .build();
    }

    private List<Integer> buildTrainBrandList(List<TicketListDTO> seatResults) {
        Set<Integer> brandSet=new HashSet<>();
        for (TicketListDTO seatResult : seatResults) {
            String trainBrand = seatResult.getTrainBrand();
            if (StrUtil.isNotBlank(trainBrand)){
                brandSet.addAll(StrUtil.split(trainBrand,",").stream().map(Integer::parseInt).toList());
            }
        }
        return brandSet.stream().toList();
    }

    private List<String> buildArrivalStationList(List<TicketListDTO> seatResults) {
        return seatResults.stream().map(TicketListDTO::getArrival).collect(Collectors.toList());
    }

    private List<String> buildDepartureStationList(List<TicketListDTO> seatResults) {
        return seatResults.stream().map(TicketListDTO::getDeparture).collect(Collectors.toList());
    }


    private List<Integer> buildSeatClassList(List<TicketListDTO> seatResults) {
        Set<Integer> seatClassSet=new HashSet<>();
        for (TicketListDTO seatResult : seatResults) {
            List<SeatClassDTO> seatClassList = seatResult.getSeatClassList();
            for (SeatClassDTO seatClassDTO : seatClassList) {
                seatClassSet.add(seatClassDTO.getType());
            }
        }
        return seatClassSet.stream().toList();
    }

    @Override
    public TicketPageQueryRespDTO pageListTicketQueryV2(TicketPageQueryReqDTO requestParam) {
        // 责任链模式 验证城市名称是否存在、不存在加载缓存以及出发日期不能小于当前日期等等
        ticketPageQueryReqDTOAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_QUERY_FILTER.name(), requestParam);
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        // v2 版本更符合企业级高并发真实场景解决方案，完美解决了 v1 版本性能深渊问题。通过 Jmeter 压测聚合报告得知，性能提升在 300% - 500%+
        List<Object> stationDetails = stringRedisTemplate.opsForHash()
                .multiGet(REGION_TRAIN_STATION_MAPPING, Lists.newArrayList(requestParam.getFromStation(), requestParam.getToStation()));
        String buildRegionTrainStationHashKey = String.format(REGION_TRAIN_STATION, stationDetails.get(0), stationDetails.get(1));
        Map<Object, Object> regionTrainStationAllMap = stringRedisTemplate.opsForHash().entries(buildRegionTrainStationHashKey);
        List<TicketListDTO> seatResults = regionTrainStationAllMap.values().stream()
                .map(each -> JSON.parseObject(each.toString(), TicketListDTO.class))
                .sorted(new TimeStringComparator())
                .toList();
        List<String> trainStationPriceKeys = seatResults.stream()
                .map(each -> String.format(cacheRedisPrefix + TRAIN_STATION_PRICE, each.getTrainId(), each.getDeparture(), each.getArrival()))
                .toList();
        List<Object> trainStationPriceObjs = stringRedisTemplate.executePipelined((RedisCallback<String>) connection -> {
            trainStationPriceKeys.forEach(each -> connection.stringCommands().get(each.getBytes()));
            return null;
        });
        List<TrainStationPriceDO> trainStationPriceDOList = new ArrayList<>();
        List<String> trainStationRemainingKeyList = new ArrayList<>();
        for (Object each : trainStationPriceObjs) {
            List<TrainStationPriceDO> trainStationPriceList = JSON.parseArray(each.toString(), TrainStationPriceDO.class);
            trainStationPriceDOList.addAll(trainStationPriceList);
            for (TrainStationPriceDO item : trainStationPriceList) {
                String trainStationRemainingKey = cacheRedisPrefix + TRAIN_STATION_REMAINING_TICKET + StrUtil.join("_", item.getTrainId(), item.getDeparture(), item.getArrival());
                trainStationRemainingKeyList.add(trainStationRemainingKey);
            }
        }
        List<Object> trainStationRemainingObjs = stringRedisTemplate.executePipelined((RedisCallback<String>) connection -> {
            for (int i = 0; i < trainStationRemainingKeyList.size(); i++) {
                connection.hashCommands().hGet(trainStationRemainingKeyList.get(i).getBytes(), trainStationPriceDOList.get(i).getSeatType().toString().getBytes());
            }
            return null;
        });
        for (TicketListDTO each : seatResults) {
            //获取当前车辆的所有作为类型
            List<Integer> seatTypesByCode = VehicleTypeEnum.findSeatTypesByCode(each.getTrainType());
            List<Object> remainingTicket = new ArrayList<>(trainStationRemainingObjs.subList(0, seatTypesByCode.size()));
            List<TrainStationPriceDO> trainStationPriceDOSub = new ArrayList<>(trainStationPriceDOList.subList(0, seatTypesByCode.size()));
            trainStationRemainingObjs.subList(0, seatTypesByCode.size()).clear();
            trainStationPriceDOList.subList(0, seatTypesByCode.size()).clear();
            List<SeatClassDTO> seatClassList = new ArrayList<>();
            for (int i = 0; i < trainStationPriceDOSub.size(); i++) {
                TrainStationPriceDO trainStationPriceDO = trainStationPriceDOSub.get(i);
                SeatClassDTO seatClassDTO = SeatClassDTO.builder()
                        .type(trainStationPriceDO.getSeatType())
                        .quantity(Integer.parseInt(remainingTicket.get(i).toString()))
                        .price(new BigDecimal(trainStationPriceDO.getPrice()).divide(new BigDecimal("100"), 1, RoundingMode.HALF_UP))
                        .candidate(false)
                        .build();
                seatClassList.add(seatClassDTO);
            }
            each.setSeatClassList(seatClassList);
        }
        return TicketPageQueryRespDTO.builder()
                .trainList(seatResults)
                .departureStationList(buildDepartureStationList(seatResults))
                .arrivalStationList(buildArrivalStationList(seatResults))
                .trainBrandList(buildTrainBrandList(seatResults))
                .seatClassTypeList(buildSeatClassList(seatResults))
                .build();
    }

    @Override
    public TicketPurchaseRespDTO purchaseTicketsV1(PurchaseTicketReqDTO requestParam) {
        //参数校验
        ticketPurchaseRespDTOAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name(), requestParam);
        String lockKey = environment.resolvePlaceholders(String.format(LOCK_PURCHASE_TICKETS, requestParam.getTrainId()));
        RLock lock = redissonClient.getLock(lockKey);
        lock.lock();
        try{
            //TODO 为什么要用ticketService调用，不直接调用方法（避免executePurchaseTickets的事务失效，同一个类内部进行方法调用必须是代理对象）
            return ticketService.executePurchaseTickets(requestParam);
        }finally {
            lock.unlock();
        }
    }

    //localLockMap.put(lockKey, localLock);
    //lockKey:prefix+trainId,localLock本地公平锁
    private final Cache<String, ReentrantLock> localLockMap= Caffeine.newBuilder()
            .expireAfterWrite(1,TimeUnit.DAYS)
            .build();
    //tokenTicketsRefreshMap.put(requestParam.getTrainId(), ifPresentObj);
    private final Cache<String,Object> tokenTicketsRefreshMap=Caffeine.newBuilder()
            .expireAfterWrite(10,TimeUnit.MINUTES)
            .build();
    @Override
    public TicketPurchaseRespDTO purchaseTicketsV2(PurchaseTicketReqDTO requestParam) {
        ticketPurchaseRespDTOAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_PURCHASE_TICKET_FILTER.name(), requestParam);
        //获取令牌
        TokenResultDTO tokenResultDTO=ticketAvailabilityTokenBucket.takeTokenFromBucket(requestParam);
        //tokenResultDTO.tokenIsNull==false 获取令牌成功
        if (tokenResultDTO.getTokenIsNull()){
            //获取令牌失败
            Object ifPresent = tokenTicketsRefreshMap.getIfPresent(requestParam.getTrainId());
            if (ifPresent==null){
                synchronized (TicketService.class){
                    if (tokenTicketsRefreshMap.getIfPresent(requestParam.getTrainId())==null){
                        ifPresent=new Object();
                        tokenTicketsRefreshMap.put(requestParam.getTrainId(),ifPresent);
                       //可能存在令牌与数据库数据不一致。
                        tokenIsNullRefreshToken(requestParam,tokenResultDTO);
                    }
                }
            }
            throw new ServiceException("列车站点已无余票");
        }
        List<ReentrantLock> localLockList=new ArrayList<>();
        List<RLock> distributedLockList=new ArrayList<>();
        //根据座位类型对乘车人进行分组
        Map<Integer, List<PurchaseTicketPassengerDetailDTO>> seatTypeMap = requestParam.getPassengers()
                .stream().collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType));
        seatTypeMap.forEach((seatType,count)->{
            String lockKey = environment.resolvePlaceholders(String.format(LOCK_PURCHASE_TICKETS_V2, requestParam.getTrainId(), seatType));
            ReentrantLock localLock = localLockMap.getIfPresent(lockKey);
            if (localLock==null){
                synchronized (TicketService.class){
                    if ((localLock=localLockMap.getIfPresent(lockKey))==null){
                        localLock=new ReentrantLock(true);
                        localLockMap.put(lockKey,localLock);
                    }
                }
            }
            localLockList.add(localLock);
            RLock distributedLock = redissonClient.getFairLock(lockKey);
            distributedLockList.add(distributedLock);
        });
        try {
            localLockList.forEach(ReentrantLock::lock);
            distributedLockList.forEach(RLock::lock);
            return ticketService.executePurchaseTickets(requestParam);
        }finally {
            localLockList.forEach(localLock->{
                try {
                    localLock.unlock();
                }catch (Throwable ex){

                }
            });
            distributedLockList.forEach(distributedLock->{
                try {
                    distributedLock.unlock();
                }catch (Throwable ex){

                }
            });
        }
    }
    private final ScheduledExecutorService tokenIsNullRefreshExecutor= Executors.newScheduledThreadPool(1);
    private void tokenIsNullRefreshToken(PurchaseTicketReqDTO requestParam, TokenResultDTO tokenResultDTO) {
        RLock lock = redissonClient.getLock(String.format(LOCK_TOKEN_BUCKET_ISNULL, requestParam.getTrainId()));
        if (!lock.tryLock()){
            //获取分布式锁失败
            return;
        }
        tokenIsNullRefreshExecutor.schedule(()->{
            try{
                List<Integer> seatTypes=new ArrayList<>();
                Map<Integer,Integer> tokenCountMap=new HashMap<>();
                //没有获取到令牌的座位集合
                tokenResultDTO.getTokenIsNullSeatTypeCounts()
                        .stream()
                        .map(e->e.split("_"))
                        .forEach(split->{
                            int seatType = Integer.parseInt(split[0]);
                            seatTypes.add(seatType);
                            tokenCountMap.put(seatType,Integer.parseInt(split[1]));
                        });
                //指定区间内可用的座位集合
                List<SeatTypeCountDTO> seatTypeCountDTOList = seatService.listSeatTypeCount(Long.valueOf(requestParam.getTrainId()), requestParam.getDeparture(), requestParam.getArrival(), seatTypes);
                for (SeatTypeCountDTO each : seatTypeCountDTOList) {
                    //令牌中的座位个数
                    Integer tokenCount = tokenCountMap.get(each.getSeatType());
                    if (tokenCount<each.getSeatCount()){
                        //令牌座位个数小于数据库中的座位个数,令牌座位个数与数据库座位个数不同,令牌不够但是数据库中还有余票，存在少卖
                        ticketAvailabilityTokenBucket.delTokenInBucket(requestParam);
                        break;
                    }
                }
            }finally {
                lock.unlock();
            }
        },10,TimeUnit.SECONDS);

    }

    @Override
    @Transactional(rollbackFor = Throwable.class)
    public TicketPurchaseRespDTO executePurchaseTickets(PurchaseTicketReqDTO requestParam) {
        List<TicketOrderDetailRespDTO> ticketOrderDetailRespDTOList=new ArrayList<>();
        String trainId = requestParam.getTrainId();
        TrainDO trainDO = distributedCache.safeGet(
                TRAIN_INFO + trainId,
                TrainDO.class,
                () -> trainMapper.selectById(trainId),
                ADVANCE_TICKET_DAY,
                TimeUnit.DAYS
        );
        List<TrainPurchaseTicketRespDTO> trainPurchaseTicketResults =trainSeatTypeSelector.select(trainDO.getTrainType(),requestParam);
        List<TicketDO> ticketDOList = trainPurchaseTicketResults.stream()
                .map(each -> TicketDO.builder()
                        .username(UserContext.getUsername())
                        .carriageNumber(each.getCarriageNumber())
                        .trainId(Long.valueOf(requestParam.getTrainId()))
                        .seatNumber(each.getSeatNumber())
                        .passengerId(each.getPassengerId())
                        .ticketStatus(TicketStatusEnum.UNPAID.getCode())
                        .build()).toList();
        saveBatch(ticketDOList);
        Result<String> ticketOrderResult;
        try{
            List<TicketOrderItemCreateRemoteReqDTO> ticketOrderItemCreateRemoteReqDTOS=new ArrayList<>();
            trainPurchaseTicketResults.forEach(each->{
                TicketOrderItemCreateRemoteReqDTO ticketOrderItemCreateRemoteReqDTO=TicketOrderItemCreateRemoteReqDTO.builder()
                        .ticketType(each.getUserType())
                        .phone(each.getPhone())
                        .amount(each.getAmount())
                        .idType(each.getIdType())
                        .idCard(each.getIdCard())
                        .realName(each.getRealName())
                        .carriageNumber(each.getCarriageNumber())
                        .seatType(each.getSeatType())
                        .seatNumber(each.getSeatNumber())
                        .build();
                TicketOrderDetailRespDTO ticketOrderDetailRespDTO=TicketOrderDetailRespDTO.builder()
                        .idCard(each.getIdCard())
                        .ticketType(each.getUserType())
                        .amount(each.getAmount())
                        .idType(each.getIdType())
                        .seatNumber(each.getSeatNumber())
                        .carriageNumber(each.getCarriageNumber())
                        .realName(each.getRealName())
                        .seatType(each.getSeatType())
                        .build();
                ticketOrderItemCreateRemoteReqDTOS.add(ticketOrderItemCreateRemoteReqDTO);
                ticketOrderDetailRespDTOList.add(ticketOrderDetailRespDTO);
            });
            LambdaQueryWrapper<TrainStationRelationDO> queryWrapper = Wrappers.lambdaQuery(TrainStationRelationDO.class)
                    .eq(TrainStationRelationDO::getTrainId, trainId)
                    .eq(TrainStationRelationDO::getDeparture, requestParam.getDeparture())
                    .eq(TrainStationRelationDO::getArrival, requestParam.getArrival());
            TrainStationRelationDO trainStationRelationDO = trainStationRelationMapper.selectOne(queryWrapper);
            TicketOrderCreateRemoteReqDTO orderCreateRemoteReqDTO = TicketOrderCreateRemoteReqDTO.builder()
                    .departure(requestParam.getDeparture())
                    .arrival(requestParam.getArrival())
                    .orderTime(new Date())
                    .source(SourceEnum.INTERNET.getCode())
                    .trainNumber(trainDO.getTrainNumber())
                    .departureTime(trainStationRelationDO.getDepartureTime())
                    .arrivalTime(trainStationRelationDO.getArrivalTime())
                    .ridingDate(trainStationRelationDO.getDepartureTime())
                    .userId(UserContext.getUserId())
                    .username(UserContext.getUsername())
                    .trainId(Long.parseLong(requestParam.getTrainId()))
                    .ticketOrderItems(ticketOrderItemCreateRemoteReqDTOS)
                    .build();
            //远程调用创建订单接口
            ticketOrderResult = ticketOrderRemoteService.createTicketOrder(orderCreateRemoteReqDTO);
            if (!ticketOrderResult.isSuccess()||StrUtil.isBlank(ticketOrderResult.getData())){
                log.error("订单服务调用失败，返回结果：{}", ticketOrderResult.getMessage());
                throw new ServiceException("订单服务调用失败");
            }
        }catch (Throwable ex){
            log.error("远程调用订单服务创建错误，请求参数：{}", JSON.toJSONString(requestParam), ex);
            throw ex;
        }
        return new TicketPurchaseRespDTO(ticketOrderResult.getData(),ticketOrderDetailRespDTOList);
    }

    @Override
    public void cancelTicketOrder(CancelTicketOrderReqDTO requestParam) {
        Result<Void> cancelTicketOrder = ticketOrderRemoteService.cancelTicketOrder(requestParam);
        if (cancelTicketOrder.isSuccess()&&StrUtil.equals(ticketAvailabilityCacheUpdateType,"binlog")){
            Result<org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO> ticketOrderDetailResult = ticketOrderRemoteService.queryTicketOrderByOrderSn(requestParam.getOrderSn());
            org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO ticketOrderDetail = ticketOrderDetailResult.getData();
            String trainId = String.valueOf(ticketOrderDetail.getTrainId());
            String departure = ticketOrderDetail.getDeparture();
            String arrival = ticketOrderDetail.getArrival();
            List<TicketOrderPassengerDetailRespDTO> passengerDetails = ticketOrderDetail.getPassengerDetails();
            try{
                seatService.unlock(trainId,departure,arrival, BeanUtil.convert(passengerDetails,TrainPurchaseTicketRespDTO.class));
            }catch (Throwable ex){
                log.error("[取消订单] 订单号：{}解锁列车座位",requestParam.getOrderSn(),ex);
                throw ex;
            }
            ticketAvailabilityTokenBucket.rollbackInBucket(ticketOrderDetail);
            try{
                 StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
                Map<Integer, List<TicketOrderPassengerDetailRespDTO>> seatTypeMap = passengerDetails.stream()
                        .collect(Collectors.groupingBy(TicketOrderPassengerDetailRespDTO::getSeatType));
                List<RouteDTO> routeDTOList = trainStationService.listTakeoutTrainStationRoute(trainId, departure, arrival);
                routeDTOList.forEach(each->{
                    String keySuffix = StrUtil.join("_", trainId, departure, arrival);
                    seatTypeMap.forEach((seatType,count)->{
                        stringRedisTemplate.opsForHash()
                                .increment(TRAIN_STATION_REMAINING_TICKET+keySuffix,String.valueOf(seatType),passengerDetails.size());
                    });
                });
            }catch (Throwable ex){
                log.error("[取消关闭订单] 订单号：{} 回滚列车Cache余票失败", requestParam.getOrderSn(), ex);
                throw ex;
            }
        }

    }

    @Override
    public org.opengoofy.index12306.biz.ticketservice.remote.dto.PayInfoRespDTO getPayInfo(String orderSn) {
        return payRemoteService.getPayInfo(orderSn).getData();
    }

    @Override
    public RefundTicketRespDTO commonTicketRefund(RefundTicketReqDTO requestParam) {
        refundReqDTOAbstractChainContext.handler(TicketChainMarkEnum.TRAIN_REFUND_TICKET_FILTER.name(), requestParam);
        Result<org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO> ticketOrderDetailRespDTOResult = ticketOrderRemoteService.queryTicketOrderByOrderSn(requestParam.getOrderSn());
        if (!ticketOrderDetailRespDTOResult.isSuccess()&&Objects.isNull(ticketOrderDetailRespDTOResult.getData())){
            throw new ServiceException("车票订单不存在");
        }
        org.opengoofy.index12306.biz.ticketservice.remote.dto.TicketOrderDetailRespDTO ticketOrderDetailRespDTO = ticketOrderDetailRespDTOResult.getData();
        List<TicketOrderPassengerDetailRespDTO> passengerDetails = ticketOrderDetailRespDTO.getPassengerDetails();
        if (CollectionUtils.isEmpty(passengerDetails)){
            throw new ServiceException("车票订单项不存在");
        }
        RefundReqDTO refundReqDTO = new RefundReqDTO();
        //部分乘车人退款
        if (RefundTypeEnum.PARTIAL_REFUND.getType().equals(requestParam.getType())){
            TicketOrderItemQueryReqDTO ticketOrderItemQueryReqDTO = new TicketOrderItemQueryReqDTO();
            ticketOrderItemQueryReqDTO.setOrderSn(requestParam.getOrderSn());
            ticketOrderItemQueryReqDTO.setOrderItemRecordIds(requestParam.getSubOrderRecordIdReqList());
            Result<List<TicketOrderPassengerDetailRespDTO>> queryTicketItemOrderById = ticketOrderRemoteService.queryTicketItemOrderById(ticketOrderItemQueryReqDTO);
            List<TicketOrderPassengerDetailRespDTO> partialRefundPassengerDetails = passengerDetails.stream()
                    .filter(item -> queryTicketItemOrderById.getData().contains(item))
                    .toList();
            refundReqDTO.setRefundTypeEnum(RefundTypeEnum.PARTIAL_REFUND);
            refundReqDTO.setRefundDetailReqDTOList(partialRefundPassengerDetails);
            int amount = partialRefundPassengerDetails.stream()
                    .mapToInt(TicketOrderPassengerDetailRespDTO::getAmount)
                    .sum();
            refundReqDTO.setRefundAmount(amount);
        }else if (RefundTypeEnum.FULL_REFUND.getType().equals(requestParam.getType())){
            refundReqDTO.setRefundTypeEnum(RefundTypeEnum.FULL_REFUND);
            refundReqDTO.setRefundDetailReqDTOList(passengerDetails);
            int amount = passengerDetails.stream()
                    .mapToInt(TicketOrderPassengerDetailRespDTO::getAmount)
                    .sum();
            refundReqDTO.setRefundAmount(amount);
        }
        refundReqDTO.setOrderSn(requestParam.getOrderSn());
        Result<RefundRespDTO> refundRespDTOResult = payRemoteService.commonRefund(refundReqDTO);
        if (!refundRespDTOResult.isSuccess()&&Objects.isNull(refundRespDTOResult.getData())){
            throw new ServiceException("车票订单退款失败");
        }
        return new RefundTicketRespDTO();
    }

    @Override
    public void run(String... args) throws Exception {
        ticketService= ApplicationContextHolder.getBean(TicketService.class);
    }
}
