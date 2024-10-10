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

package org.opengoofy.index12306.biz.userservice.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdcardUtil;
import cn.hutool.core.util.PhoneUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.userservice.common.enums.VerifyStatusEnum;
import org.opengoofy.index12306.biz.userservice.dao.entity.PassengerDO;
import org.opengoofy.index12306.biz.userservice.dao.mapper.PassengerMapper;
import org.opengoofy.index12306.biz.userservice.dto.req.PassengerRemoveReqDTO;
import org.opengoofy.index12306.biz.userservice.dto.req.PassengerReqDTO;
import org.opengoofy.index12306.biz.userservice.dto.resp.PassengerActualRespDTO;
import org.opengoofy.index12306.biz.userservice.dto.resp.PassengerRespDTO;
import org.opengoofy.index12306.biz.userservice.service.PassengerService;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.opengoofy.index12306.biz.userservice.common.constant.RedisKeyConstant.USER_PASSENGER_LIST;

@Service
@RequiredArgsConstructor
@Slf4j
public class PassengerServiceImpl implements PassengerService {
    private final PassengerMapper passengerMapper;
    private final DistributedCache distributedCache;
    @Override
    public void savePassenger(PassengerReqDTO requestParam) {
        verifyPassenger(requestParam);
        String username = UserContext.getUsername();
        try {
            PassengerDO passengerDO = BeanUtil.convert(requestParam, PassengerDO.class);
            passengerDO.setUsername(username);
            passengerDO.setCreateDate(new Date());
            passengerDO.setVerifyStatus(VerifyStatusEnum.REVIEWED.getCode());
            int insert = passengerMapper.insert(passengerDO);
            if (!SqlHelper.retBool(insert)){
                throw new ServiceException(String.format("%s乘车人添加失败",username));
            }
        }catch (Exception ex){
            if (ex instanceof ServiceException){
                log.error("{} 请求参数:{}",ex.getMessage(), JSON.toJSONString(requestParam));
            }else{
                log.error("{} 新增乘车人失败，请求参数为：{}",ex.getMessage(),JSON.toJSONString(requestParam));
            }
            throw ex;
        }
        delUserPassengerCache(username);
    }

    private void delUserPassengerCache(String username) {
        distributedCache.delete(USER_PASSENGER_LIST+username);
    }

    private void verifyPassenger(PassengerReqDTO requestParam) {
        int length = requestParam.getRealName().length();
        if (!(length>=2&&length<=16)){
            throw new ClientException("请设置乘车人名称长度为2-16");
        }
        if (!IdcardUtil.isValidCard(requestParam.getIdCard())){
            throw new ClientException("乘车人证件号错误");
        }
        if (!PhoneUtil.isMobile(requestParam.getPhone())){
            throw new ClientException("乘车人手机号错误");
        }
    }

    @Override
    public void updatePassenger(PassengerReqDTO requestParam) {
        verifyPassenger(requestParam);
        String username = UserContext.getUsername();
        try {
            PassengerDO passengerDO = BeanUtil.convert(requestParam, PassengerDO.class);
            passengerDO.setUsername(username);
            LambdaUpdateWrapper<PassengerDO> updateWrapper=Wrappers.lambdaUpdate(PassengerDO.class)
                    .eq(PassengerDO::getUsername,username)
                    .eq(PassengerDO::getId,requestParam.getId());
            int update = passengerMapper.update(passengerDO, updateWrapper);
            if (!SqlHelper.retBool(update)){
                throw new ServiceException(String.format("[%s] 修改乘车人失败",username));
            }
        }catch (Exception ex){
            if (ex instanceof ServiceException){
                log.error("{}请求参数：{}",ex.getMessage(),JSON.toJSONString(requestParam));
            }else{
                log.error("[{}]修改乘车人失败，请求参数：{}",username,JSON.toJSONString(username));
            }
            throw ex;
        }
        delUserPassengerCache(USER_PASSENGER_LIST+username);
    }

    @Override
    public List<PassengerRespDTO> listPassengerQueryByUsername(String username) {
        String actualUserPassengerListStr = getActualUserPassengerListStr(username);
        return Optional.ofNullable(actualUserPassengerListStr)
                .map(e->JSON.parseArray(actualUserPassengerListStr,PassengerDO.class))
                .map(e->BeanUtil.convert(e, PassengerRespDTO.class))
                .orElse(null);
    }

    private String getActualUserPassengerListStr(String username) {
       return distributedCache.safeGet(USER_PASSENGER_LIST+username,
                String.class,()->{
                    LambdaQueryWrapper<PassengerDO> eq = Wrappers.lambdaQuery(PassengerDO.class)
                            .eq(PassengerDO::getUsername, username);
                    List<PassengerDO> passengerDOS = passengerMapper.selectList(eq);
                    return CollUtil.isNotEmpty(passengerDOS)?JSON.toJSONString(passengerDOS):null;
                },1, TimeUnit.DAYS);
    }

    @Override
    public List<PassengerActualRespDTO> listPassengerQueryByIds(String username, List<Long> ids) {
        String actualUserPassengerListStr = getActualUserPassengerListStr(username);
        if (StrUtil.isEmpty(actualUserPassengerListStr)){
            return null;
        }
        return JSON.parseArray(actualUserPassengerListStr,PassengerDO.class)
                .stream().filter(e->ids.contains(e.getId()))
                .map(e->BeanUtil.convert(e, PassengerActualRespDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public void removePassenger(PassengerRemoveReqDTO requestParam) {
        String username = UserContext.getUsername();
        PassengerDO passengerDO=selectPassenger(username,requestParam.getId());
        if (Objects.isNull(passengerDO)){
            throw new ClientException("乘车人数据不存在");
        }
        try {
            LambdaUpdateWrapper<PassengerDO> wrapper=Wrappers.lambdaUpdate(PassengerDO.class)
                    .eq(PassengerDO::getUsername,username)
                    .eq(PassengerDO::getId,requestParam.getId());
            int delete = passengerMapper.delete(wrapper);
            if (!SqlHelper.retBool(delete)){
                throw new ServiceException(String.format("[%s] 删除乘车人失败",username));
            }
        }catch (Exception ex){
            if (ex instanceof ServiceException){
                log.error("{}乘车人删除失败，请求参数为：{}",ex.getMessage(),JSON.toJSONString(requestParam));
            }else{
                log.error("{}乘车人删除失败，请求参数：{}",ex.getMessage(),JSON.toJSONString(requestParam));
            }
            throw ex;
        }
        delUserPassengerCache(username);
    }

    private PassengerDO selectPassenger(String username, String id) {
        LambdaQueryWrapper<PassengerDO> eq = Wrappers.lambdaQuery(PassengerDO.class)
                .eq(PassengerDO::getUsername, username)
                .eq(PassengerDO::getId, id);
        return passengerMapper.selectOne(eq);
    }
}
