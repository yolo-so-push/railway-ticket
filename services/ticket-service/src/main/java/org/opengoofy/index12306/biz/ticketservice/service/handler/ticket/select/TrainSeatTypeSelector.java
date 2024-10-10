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

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.select;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleSeatTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.TrainStationPriceDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.TrainStationPriceMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.PurchaseTicketPassengerDetailDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.domain.SelectSeatDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.opengoofy.index12306.biz.ticketservice.dto.resp.TrainPurchaseTicketRespDTO;
import org.opengoofy.index12306.biz.ticketservice.remote.UserRemoteService;
import org.opengoofy.index12306.biz.ticketservice.remote.dto.PassengerRespDTO;
import org.opengoofy.index12306.biz.ticketservice.service.SeatService;
import org.opengoofy.index12306.framework.starter.convention.exception.RemoteException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.designpattern.strategy.AbstractStrategyChoose;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

/**
 * 座位选择器
 */
@RequiredArgsConstructor
@Component
@Slf4j
public final class TrainSeatTypeSelector {
    private final AbstractStrategyChoose abstractStrategyChoose;
    private final ThreadPoolExecutor selectSeatThreadPoolExecutor;
    private final UserRemoteService userRemoteService;
    private final TrainStationPriceMapper trainStationPriceMapper;
    private final SeatService seatService;
    public List<TrainPurchaseTicketRespDTO> select(Integer trainType, PurchaseTicketReqDTO requestParam) {
        List<PurchaseTicketPassengerDetailDTO> passengerDetailDTOList = requestParam.getPassengers();
        Map<Integer, List<PurchaseTicketPassengerDetailDTO>> seatTypeAllMap = passengerDetailDTOList.stream()
                .collect(Collectors.groupingBy(PurchaseTicketPassengerDetailDTO::getSeatType));
        List<TrainPurchaseTicketRespDTO> actualResult=new CopyOnWriteArrayList<>();
        if (seatTypeAllMap.size()>1){
              List<Future<List<TrainPurchaseTicketRespDTO>>> futureResult=new ArrayList<>();
              seatTypeAllMap.forEach((seatType,passengerDetails)->{
                  Future<List<TrainPurchaseTicketRespDTO>> completableFuture = selectSeatThreadPoolExecutor
                          .submit(() -> distributeSeats(seatType, trainType, requestParam, passengerDetails));
                  futureResult.add(completableFuture);
              });
             futureResult.parallelStream().forEach(completanleFuture->{
                 try{
                     actualResult.addAll(completanleFuture.get());
                 }catch (Exception ex){
                     throw new ServiceException("站点余票不足，请尝试更换座位类型或选择其它站点");
                 }
             });
        }else{
            //只有一个乘车人
            seatTypeAllMap.forEach((seatType,passengerDetails)->{
                List<TrainPurchaseTicketRespDTO> trainPurchaseTicketRespDTOS = distributeSeats(trainType, seatType, requestParam, passengerDetails);
                actualResult.addAll(trainPurchaseTicketRespDTOS);
            });
        }
        //实际分发的车票与乘车人数不同则说明站点余票不足
        if (CollUtil.isEmpty(actualResult)|| !Objects.equals(actualResult.size(),requestParam.getPassengers().size())){
            throw new ServiceException("站点余票不足，请尝试更换座位类型或选择其它站点");
        }
        //获得车票的乘车人id
        List<String> passengerIds = actualResult.stream()
                .map(TrainPurchaseTicketRespDTO::getPassengerId)
                .toList();
        //查询当前用户的乘车人列表
        Result<List<org.opengoofy.index12306.biz.ticketservice.remote.dto.PassengerRespDTO>> passengerRemoteResult;
        List<PassengerRespDTO> passengerRemoteResultList;
        try {
            passengerRemoteResult = userRemoteService.listPassengerQueryByIds(UserContext.getUsername(), passengerIds);
            if (!passengerRemoteResult.isSuccess() ||CollUtil.isEmpty(passengerRemoteResultList=passengerRemoteResult.getData())){
                throw new RemoteException("用户服务远程调用查询乘车人相关信息错误");
            }
        }catch (Throwable ex){
            if (ex instanceof RemoteException) {
                log.error("用户服务远程调用查询乘车人相关信息错误，当前用户：{}，请求参数：{}", UserContext.getUsername(), passengerIds);
            } else {
                log.error("用户服务远程调用查询乘车人相关信息错误，当前用户：{}，请求参数：{}", UserContext.getUsername(), passengerIds, ex);
            }
            throw ex;
        }
        actualResult.forEach(e->{
            String passengerId = e.getPassengerId();
            passengerRemoteResultList.stream()
                    .filter(item->Objects.equals(item.getId(),passengerId))
                    .findFirst()
                    .ifPresent(passenger->{
                        e.setIdCard(passenger.getIdCard());
                        e.setIdType(passenger.getIdType());
                        e.setUserType(passenger.getDiscountType());
                        e.setPhone(passenger.getPhone());
                        e.setRealName(passenger.getRealName());
                    });
            LambdaQueryWrapper<TrainStationPriceDO> queryWrapper = Wrappers.lambdaQuery(TrainStationPriceDO.class)
                    .eq(TrainStationPriceDO::getTrainId, requestParam.getTrainId())
                    .eq(TrainStationPriceDO::getDeparture, requestParam.getDeparture())
                    .eq(TrainStationPriceDO::getArrival, requestParam.getArrival())
                    .eq(TrainStationPriceDO::getSeatType, e.getSeatType())
                    .select(TrainStationPriceDO::getPrice);
            TrainStationPriceDO trainStationPriceDO = trainStationPriceMapper.selectOne(queryWrapper);
            e.setAmount(trainStationPriceDO.getPrice());
        });
        //锁定购买座位
        seatService.lockSeat(requestParam.getTrainId(),requestParam.getDeparture(),requestParam.getArrival(),actualResult);
        return actualResult;
    }

    private List<TrainPurchaseTicketRespDTO> distributeSeats(Integer trainType, Integer seatType, PurchaseTicketReqDTO requestParam, List<PurchaseTicketPassengerDetailDTO> passengersSeatDetails) {
        String strategyKey = VehicleTypeEnum.findNameByCode(trainType) + VehicleSeatTypeEnum.findNameByCode(seatType);
        SelectSeatDTO selectSeatDTO=SelectSeatDTO.builder()
                .seatType(seatType)
                .passengerSeatDetails(passengersSeatDetails)
                .requestParam(requestParam)
                .build();
        try {
            return abstractStrategyChoose.chooseAndExecuteResp(strategyKey, selectSeatDTO);
        }catch (ServiceException ex){
            throw new ServiceException("当前车次列车类型暂未适配，请购买G35或G39车次");
        }
    }
}
