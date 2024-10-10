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

package org.opengoofy.index12306.biz.orderservice.service.impl;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.text.StrBuilder;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderCanalErrorCodeEnum;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderItemStatusEnum;
import org.opengoofy.index12306.biz.orderservice.common.enums.OrderStatusEnum;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemDO;
import org.opengoofy.index12306.biz.orderservice.dao.entity.OrderItemPassengerDO;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderItemMapper;
import org.opengoofy.index12306.biz.orderservice.dao.mapper.OrderMapper;
import org.opengoofy.index12306.biz.orderservice.dto.domain.OrderStatusReversalDTO;
import org.opengoofy.index12306.biz.orderservice.dto.req.*;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderDetailRespDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderDetailSelfRespDTO;
import org.opengoofy.index12306.biz.orderservice.dto.resp.TicketOrderPassengerDetailRespDTO;
import org.opengoofy.index12306.biz.orderservice.mq.event.DelayCloseOrderEvent;
import org.opengoofy.index12306.biz.orderservice.mq.event.PayResultCallbackOrderEvent;
import org.opengoofy.index12306.biz.orderservice.mq.producer.DelayCloseOrderSendProduce;
import org.opengoofy.index12306.biz.orderservice.remote.UserRemoteService;
import org.opengoofy.index12306.biz.orderservice.remote.dto.PassengerActualRespDTO;
import org.opengoofy.index12306.biz.orderservice.remote.dto.UserQueryActualRespDTO;
import org.opengoofy.index12306.biz.orderservice.service.OrderItemService;
import org.opengoofy.index12306.biz.orderservice.service.OrderPassengerRelationService;
import org.opengoofy.index12306.biz.orderservice.service.OrderService;
import org.opengoofy.index12306.biz.orderservice.service.orderid.OrderIdGeneratorManager;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.convention.page.PageResponse;
import org.opengoofy.index12306.framework.starter.convention.result.Result;
import org.opengoofy.index12306.framework.starter.database.toolkit.PageUtil;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 订单服务接口层实现
 */
@RequiredArgsConstructor
@Service
@Slf4j
public class OrderServiceImpl extends ServiceImpl<OrderMapper, OrderDO> implements OrderService {
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderItemService orderItemService;
    private final OrderPassengerRelationService orderPassengerRelationService;
    private final DelayCloseOrderSendProduce delayCloseOrderSendProduce;
    private final RedissonClient redissonClient;
    private final UserRemoteService userRemoteService;
    @Override
    public TicketOrderDetailRespDTO queryTicketOrderByOrderSn(String orderSn) {
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn);
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        TicketOrderDetailRespDTO result = BeanUtil.convert(orderDO, TicketOrderDetailRespDTO.class);
        LambdaQueryWrapper<OrderItemDO> wrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                .eq(OrderItemDO::getOrderSn, orderSn);
        List<OrderItemDO> orderItemDOList = orderItemMapper.selectList(wrapper);
        List<TicketOrderPassengerDetailRespDTO> passengerDetailRespDTOS = BeanUtil.convert(orderItemDOList, TicketOrderPassengerDetailRespDTO.class);
        result.setPassengerDetails(passengerDetailRespDTOS);
        return result;
    }

    @Override
    public PageResponse<TicketOrderDetailRespDTO> pageTicketOrder(TicketOrderPageQueryReqDTO requestParam) {
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getUserId, requestParam.getUserId())
                .in(OrderDO::getStatus, buildOrderStatusList(requestParam))
                .orderByDesc(OrderDO::getOrderTime);
        IPage<OrderDO> orderPage = orderMapper.selectPage(PageUtil.convert(requestParam), queryWrapper);
        return PageUtil.convert(orderPage, each -> {
            TicketOrderDetailRespDTO result = BeanUtil.convert(each, TicketOrderDetailRespDTO.class);
            LambdaQueryWrapper<OrderItemDO> orderItemQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, each.getOrderSn());
            List<OrderItemDO> orderItemDOList = orderItemMapper.selectList(orderItemQueryWrapper);
            result.setPassengerDetails(BeanUtil.convert(orderItemDOList, TicketOrderPassengerDetailRespDTO.class));
            return result;
        });
    }



    private List<Integer> buildOrderStatusList(TicketOrderPageQueryReqDTO requestParam) {
        List<Integer> result=new ArrayList<>();
        switch (requestParam.getStatusType()){
            case 0:
                result= ListUtil.of(OrderStatusEnum.PENDING_PAYMENT.getStatus());
                break;
            case 1:
                result= ListUtil.of(OrderStatusEnum.ALREADY_PAID.getStatus(),
                        OrderStatusEnum.PARTIAL_REFUND.getStatus(),
                        OrderStatusEnum.FULL_REFUND.getStatus());
                break;
            case 2:
                result=ListUtil.of(OrderStatusEnum.CLOSED.getStatus());
        }
        return result;

    }

    @Override
    public PageResponse<TicketOrderDetailSelfRespDTO> pageSelfTicketOrder(TicketOrderSelfPageQueryReqDTO requestParam) {
        Result<UserQueryActualRespDTO> userQueryActualRespDTOResult = userRemoteService.queryActualUserByUsername(UserContext.getUsername());
        LambdaUpdateWrapper<OrderItemPassengerDO> wrapper = Wrappers.lambdaUpdate(OrderItemPassengerDO.class)
                .eq(OrderItemPassengerDO::getIdCard, userQueryActualRespDTOResult.getData().getIdCard())
                .orderByDesc(OrderItemPassengerDO::getCreateTime);
        IPage<OrderItemPassengerDO> orderItemPassengerPage = orderPassengerRelationService.page(PageUtil.convert(requestParam), wrapper);
        return PageUtil.convert(orderItemPassengerPage,e->{
            LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                    .eq(OrderDO::getOrderSn, e.getOrderSn());
            OrderDO orderDO = orderMapper.selectOne(queryWrapper);
            LambdaQueryWrapper<OrderItemDO> lambdaQueryWrapper = Wrappers.lambdaQuery(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, e.getOrderSn())
                    .eq(OrderItemDO::getIdCard, e.getIdCard())
                    .eq(OrderItemDO::getIdType, e.getIdType());
            OrderItemDO orderItemDO = orderItemMapper.selectOne(lambdaQueryWrapper);
            TicketOrderDetailSelfRespDTO actualResult = BeanUtil.convert(orderDO, TicketOrderDetailSelfRespDTO.class);
            BeanUtil.convertIgnoreNullAndBlank(orderItemDO,actualResult);
            return actualResult;
        });
    }

    @Override
    @Transactional(rollbackFor = Throwable.class)
    public String createTicketOrder(TicketOrderCreateReqDTO requestParam) {
        //通过基因法生成订单号
        String orderSn = OrderIdGeneratorManager.generateId(requestParam.getUserId());
        OrderDO orderDO = OrderDO.builder()
                .orderSn(orderSn)
                .orderTime(requestParam.getOrderTime())
                .arrival(requestParam.getArrival())
                .departure(requestParam.getDeparture())
                .arrivalTime(requestParam.getArrivalTime())
                .departureTime(requestParam.getDepartureTime())
                .ridingDate(requestParam.getRidingDate())
                .status(OrderStatusEnum.PENDING_PAYMENT.getStatus())
                .userId(String.valueOf(requestParam.getUserId()))
                .trainId(requestParam.getTrainId())
                .source(requestParam.getSource())
                .username(requestParam.getUsername())
                .trainNumber(requestParam.getTrainNumber())
                .build();
        orderMapper.insert(orderDO);
        List<TicketOrderItemCreateReqDTO> ticketOrderItems = requestParam.getTicketOrderItems();
        List<OrderItemDO> orderItemDOList=new ArrayList<>();
        List<OrderItemPassengerDO> orderItemPassengerDOList=new ArrayList<>();
        ticketOrderItems.forEach(e->{
            OrderItemDO orderItemDO = OrderItemDO.builder()
                    .orderSn(orderSn)
                    .phone(e.getPhone())
                    .amount(e.getAmount())
                    .carriageNumber(e.getCarriageNumber())
                    .idCard(e.getIdCard())
                    .seatType(e.getSeatType())
                    .ticketType(e.getTicketType())
                    .idType(e.getIdType())
                    .trainId(requestParam.getTrainId())
                    .realName(e.getRealName())
                    .seatNumber(e.getSeatNumber())
                    .username(requestParam.getUsername())
                    .status(0)
                    .build();
            orderItemDOList.add(orderItemDO);
            OrderItemPassengerDO itemPassengerDO = OrderItemPassengerDO.builder()
                    .orderSn(orderSn)
                    .idType(e.getIdType())
                    .idCard(e.getIdCard())
                    .build();
            orderItemPassengerDOList.add(itemPassengerDO);
        });
        orderItemService.saveBatch(orderItemDOList);
        orderPassengerRelationService.saveBatch(orderItemPassengerDOList);
        try {
            DelayCloseOrderEvent delayCloseOrderEvent = DelayCloseOrderEvent.builder()
                    .orderSn(orderSn)
                    .trainId(String.valueOf(requestParam.getTrainId()))
                    .arrival(requestParam.getArrival())
                    .departure(requestParam.getDeparture())
                    .trainPurchaseTicketResults(requestParam.getTicketOrderItems())
                    .build();
            SendResult sendResult = delayCloseOrderSendProduce.sendMessage(delayCloseOrderEvent);
            if (!Objects.equals(sendResult.getSendStatus(), SendStatus.SEND_OK)){
                throw new ServiceException("投递延迟关闭订单消息队列失败");
            }
        }catch (Throwable ex){
            log.error("延迟关闭订单消息发送错误，请求参数：{}", JSON.toJSONString(requestParam));
            throw ex;
        }
        return orderSn;
    }
    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean closeTickOrder(CancelTicketOrderReqDTO requestParam) {
        String orderSn = requestParam.getOrderSn();
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn)
                .select(OrderDO::getStatus);
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        if (Objects.isNull(orderDO)||orderDO.getStatus()!=OrderStatusEnum.PENDING_PAYMENT.getStatus()){
            return false;
        }
        return cancelTickOrder(requestParam);
    }

    @Override
    public boolean cancelTickOrder(CancelTicketOrderReqDTO requestParam) {
        String orderSn = requestParam.getOrderSn();
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, orderSn);
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        if (orderDO==null){
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_UNKNOWN_ERROR);
        }else if(orderDO.getStatus()!=OrderStatusEnum.PENDING_PAYMENT.getStatus()){
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_STATUS_ERROR);
        }
        RLock lock = redissonClient.getLock(StrBuilder.create("order:canal:order_sn_").append(orderSn).toString());
        if (!lock.tryLock()){
            throw new ClientException(OrderCanalErrorCodeEnum.ORDER_CANAL_REPETITION_ERROR);
        }
        try {
            OrderDO updateOrderDo = new OrderDO();
            updateOrderDo.setStatus(OrderStatusEnum.CLOSED.getStatus());
            LambdaUpdateWrapper<OrderDO> updateWrapper = Wrappers.lambdaUpdate(OrderDO.class)
                    .eq(OrderDO::getOrderSn, orderSn);
            int update = orderMapper.update(updateOrderDo, updateWrapper);
            if (update<=0){
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_ERROR);
            }
            OrderItemDO orderItemDO = new OrderItemDO();
            orderItemDO.setStatus(OrderItemStatusEnum.CLOSED.getStatus());
            LambdaUpdateWrapper<OrderItemDO> wrapper = Wrappers.lambdaUpdate(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, orderSn);
            int updateItemRes = orderItemMapper.update(orderItemDO, wrapper);
            if (update<=0){
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_ERROR);
            }
        }finally {
            lock.unlock();
        }
        return true;
    }

    @Override
    @Transactional
    public void statusReversal(OrderStatusReversalDTO requestParam) {
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getOrderSn, requestParam.getOrderSn());
        OrderDO orderDO = orderMapper.selectOne(queryWrapper);
        if (orderDO==null){
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_UNKNOWN_ERROR);
        }else if (orderDO.getStatus()!=OrderStatusEnum.PENDING_PAYMENT.getStatus()){
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_CANAL_STATUS_ERROR);
        }
        RLock lock = redissonClient.getLock(StrBuilder.create("order:status-reversal:order_sn_").append(requestParam.getOrderSn()).toString());
        if (!lock.tryLock()){
            log.error("订单状态重复修改，状态反转参数：{}",JSON.toJSONString(requestParam));
        }
        try {
            OrderDO updateOrder = new OrderDO();
            updateOrder.setStatus(requestParam.getOrderStatus());
            LambdaUpdateWrapper<OrderDO> updateWrapper = Wrappers.lambdaUpdate(OrderDO.class)
                    .eq(OrderDO::getOrderSn, requestParam.getOrderSn());
            int update = orderMapper.update(updateOrder, updateWrapper);
            if (update<=0){
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
            }
            OrderItemDO orderItemDO = new OrderItemDO();
            orderItemDO.setStatus(requestParam.getOrderItemStatus());
            LambdaUpdateWrapper<OrderItemDO> eq = Wrappers.lambdaUpdate(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, requestParam.getOrderSn());
            int update1 = orderItemMapper.update(orderItemDO, eq);
            if (update1<=0){
                throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_ITEM_STATUS_REVERSAL_ERROR);
            }
        }finally {
            lock.unlock();
        }

    }

    @Override
    public void payCallbackOrder(PayResultCallbackOrderEvent requestParam) {
        OrderDO orderDO = new OrderDO();
        orderDO.setPayTime(requestParam.getGmtPayment());
        orderDO.setPayType(requestParam.getChannel());
        LambdaUpdateWrapper<OrderDO> eq = Wrappers.lambdaUpdate(OrderDO.class)
                .eq(OrderDO::getOrderSn, requestParam.getOrderSn());
        int update = orderMapper.update(orderDO, eq);
        if (update<=0){
            throw new ServiceException(OrderCanalErrorCodeEnum.ORDER_STATUS_REVERSAL_ERROR);
        }
    }

    @Override
    public List<String> repeatTicketQuery(TicketPurchaseTicketRepeatQueryDTO requestParam) {
        LambdaQueryWrapper<OrderDO> queryWrapper = Wrappers.lambdaQuery(OrderDO.class)
                .eq(OrderDO::getUserId, requestParam.getUserId())
                .between(OrderDO::getDeparture, requestParam.getDeparture(), requestParam.getArrival())
                .or()
                .between(OrderDO::getArrival, requestParam.getDeparture(), requestParam.getArrival());
        List<OrderDO> orderDOList = orderMapper.selectList(queryWrapper);
        List<OrderItemDO> orderItemDOList=new ArrayList<>();
        orderDOList.forEach(e->{
            List<OrderItemDO> orderItemDOS = orderItemMapper.selectList(Wrappers.lambdaQuery(OrderItemDO.class)
                    .eq(OrderItemDO::getOrderSn, e.getOrderSn()));
            orderItemDOList.addAll(orderItemDOS);
        });
        List<Long> passengerIds = requestParam.getPassengerIds().stream().map(Long::valueOf).toList();
        Result<List<PassengerActualRespDTO>> listResult = userRemoteService.queryPassengerByPassengerIds(UserContext.getUsername(), passengerIds);
        List<PassengerActualRespDTO> passengerActualRespDTOList = listResult.getData();
        List<String> passengerRealNameList = passengerActualRespDTOList.stream().map(PassengerActualRespDTO::getRealName).toList();
        return orderItemDOList.stream().filter(e -> passengerRealNameList.contains(e.getRealName())).map(OrderItemDO::getRealName).toList();
    }
}
