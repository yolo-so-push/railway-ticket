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
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.common.enums.SeatStatusEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.SeatDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.SeatMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.RouteDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SeatTypeCountDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.biz.ticketservice.service.TrainStationService;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.TRAIN_STATION_CARRIAGE_REMAINING_TICKET;

/**
 * 座位接口实现层
 */
@RequiredArgsConstructor
@Service
public class SeatServiceImpl extends ServiceImpl<SeatMapper, SeatDO> implements SeatService {
    private final SeatMapper seatMapper;
    private final DistributedCache distributedCache;
    private final TrainStationService trainStationService;
    @Override
    public List<String> listAvailableSeat(String trainId, String carriageNumber, Integer seatType, String departure, String arrival) {
        LambdaQueryWrapper<SeatDO> queryWrapper = Wrappers.lambdaQuery(SeatDO.class)
                .eq(SeatDO::getTrainId, trainId)
                .eq(SeatDO::getCarriageNumber, carriageNumber)
                .eq(SeatDO::getSeatType, seatType)
                .eq(SeatDO::getStartStation, departure)
                .eq(SeatDO::getEndStation, arrival)
                .eq(SeatDO::getSeatStatus, SeatStatusEnum.AVAILABLE.getCode())
                .select(SeatDO::getSeatNumber);
        List<SeatDO> seatDOS = seatMapper.selectList(queryWrapper);
        return seatDOS.stream().map(SeatDO::getSeatNumber).map(Object::toString).collect(Collectors.toList());
    }

    @Override
    public List<Integer> listSeatRemainingTicket(String trainId, String departure, String arrival, List<String> trainCarriageList) {
        String keySuffix= StrUtil.join("_",trainId,departure,arrival);
        if (distributedCache.hasKey(TRAIN_STATION_CARRIAGE_REMAINING_TICKET+keySuffix)){
            StringRedisTemplate instance = (StringRedisTemplate) distributedCache.getInstance();
            List<Object> trainStationCarriageRemainingTicket = instance.opsForHash().multiGet(TRAIN_STATION_CARRIAGE_REMAINING_TICKET + keySuffix, Arrays.asList(trainCarriageList.toArray()));
            if (CollUtil.isNotEmpty(trainStationCarriageRemainingTicket)){
                return trainStationCarriageRemainingTicket.stream().map(Object::toString).map(Integer::parseInt).collect(Collectors.toList());
            }
        }
        //缓存中没有
        SeatDO seatDO=SeatDO.builder()
                .trainId(Long.valueOf(trainId))
                .startStation(departure)
                .endStation(arrival)
                .build();
        return seatMapper.listSeatRemainingTicket(seatDO,trainCarriageList);
    }

    @Override
    public List<String> listUsableCarriageNumber(String trainId, Integer carriageType, String departure, String arrival) {
        LambdaQueryWrapper<SeatDO> queryWrapper = Wrappers.lambdaQuery(SeatDO.class)
                .eq(SeatDO::getTrainId, trainId)
                .eq(SeatDO::getSeatType, carriageType)
                .eq(SeatDO::getStartStation, departure)
                .eq(SeatDO::getEndStation, arrival)
                .eq(SeatDO::getSeatStatus, SeatStatusEnum.AVAILABLE.getCode())
                .groupBy(SeatDO::getCarriageNumber)
                .select(SeatDO::getCarriageNumber);
        List<SeatDO> seatDOS = seatMapper.selectList(queryWrapper);
        return seatDOS.stream().map(SeatDO::getCarriageNumber).collect(Collectors.toList());
    }

    @Override
    public List<SeatTypeCountDTO> listSeatTypeCount(Long trainId, String startStation, String endStation, List<Integer> seatTypes) {
        return seatMapper.listSeatTypeCount(trainId,startStation,endStation,seatTypes);
    }

    @Override
    public void lockSeat(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> trainPurchaseTicketRespList) {
        List<RouteDTO> routeDTOS = trainStationService.listTakeoutTrainStationRoute(trainId, departure, arrival);
        trainPurchaseTicketRespList.forEach(e->routeDTOS.forEach(item->{
            LambdaUpdateWrapper<SeatDO> updateWrapper = Wrappers.lambdaUpdate(SeatDO.class)
                    .eq(SeatDO::getTrainId, trainId)
                    .eq(SeatDO::getStartStation, item.getStartStation())
                    .eq(SeatDO::getEndStation, item.getEndStation())
                    .eq(SeatDO::getCarriageNumber, e.getCarriageNumber())
                    .eq(SeatDO::getSeatNumber, e.getSeatNumber());
            SeatDO seatDO=SeatDO.builder()
                    .seatStatus(SeatStatusEnum.LOCKED.getCode())
                    .build();
            seatMapper.update(seatDO,updateWrapper);
        }));
    }

    @Override
    public void unlock(String trainId, String departure, String arrival, List<TrainPurchaseTicketRespDTO> trainPurchaseTicketResults) {
        List<RouteDTO> routeDTOS = trainStationService.listTakeoutTrainStationRoute(trainId, departure, arrival);
        trainPurchaseTicketResults.forEach(e->routeDTOS.forEach(item->{
            LambdaUpdateWrapper<SeatDO> updateWrapper = Wrappers.lambdaUpdate(SeatDO.class)
                    .eq(SeatDO::getTrainId, trainId)
                    .eq(SeatDO::getStartStation, item.getStartStation())
                    .eq(SeatDO::getEndStation, item.getEndStation())
                    .eq(SeatDO::getCarriageNumber, e.getCarriageNumber())
                    .eq(SeatDO::getSeatNumber, e.getSeatNumber());
            SeatDO seatDO=SeatDO.builder()
                    .seatStatus(SeatStatusEnum.AVAILABLE.getCode())
                    .build();
            seatMapper.update(seatDO,updateWrapper);
        }));
    }


}
