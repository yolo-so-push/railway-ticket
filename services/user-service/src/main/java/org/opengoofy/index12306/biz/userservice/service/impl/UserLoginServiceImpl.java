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

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.biz.userservice.common.enums.UserChainMarkEnum;
import org.opengoofy.index12306.biz.userservice.common.enums.UserRegisterErrorCodeEnum;
import org.opengoofy.index12306.biz.userservice.dao.entity.*;
import org.opengoofy.index12306.biz.userservice.dao.mapper.*;
import org.opengoofy.index12306.biz.userservice.dto.req.UserDeletionReqDTO;
import org.opengoofy.index12306.biz.userservice.dto.req.UserLoginReqDTO;
import org.opengoofy.index12306.biz.userservice.dto.req.UserRegisterReqDTO;
import org.opengoofy.index12306.biz.userservice.dto.resp.UserLoginRespDTO;
import org.opengoofy.index12306.biz.userservice.dto.resp.UserQueryRespDTO;
import org.opengoofy.index12306.biz.userservice.dto.resp.UserRegisterRespDTO;
import org.opengoofy.index12306.biz.userservice.service.UserLoginService;
import org.opengoofy.index12306.biz.userservice.service.UserService;
import org.opengoofy.index12306.framework.starter.common.toolkit.BeanUtil;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.opengoofy.index12306.framework.starter.convention.exception.ServiceException;
import org.opengoofy.index12306.framework.starter.designpattern.chain.AbstractChainContext;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.frameworks.starter.user.core.UserContext;
import org.opengoofy.index12306.frameworks.starter.user.core.UserInfoDTO;
import org.opengoofy.index12306.frameworks.starter.user.toolkit.JWTUtil;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.opengoofy.index12306.biz.userservice.common.constant.RedisKeyConstant.*;
import static org.opengoofy.index12306.biz.userservice.common.enums.UserRegisterErrorCodeEnum.PHONE_REGISTERED;
import static org.opengoofy.index12306.biz.userservice.common.enums.UserRegisterErrorCodeEnum.USER_REGISTER_FAIL;
import static org.opengoofy.index12306.biz.userservice.toolkit.UserReuseUtil.hashShardingIdx;

/**
 * 用户登录业务实现层
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserLoginServiceImpl implements UserLoginService {

    private final UserMapper userMapper;
    private final UserMailMapper userMailMapper;
    private final UserPhoneMapper userPhoneMapper;
    private final DistributedCache distributedCache;
    private final RBloomFilter<String> userRegisterCachePenetrationBloomFilter;
    private final AbstractChainContext<UserRegisterReqDTO> abstractChainContext;
    private final RedissonClient redissonClient;
    private final UserReuseMapper userReuseMapper;
    private final UserService userService;
    private final UserDeletionMapper userDeletionMapper;
    @Override
    public UserLoginRespDTO login(UserLoginReqDTO requestParam) {
        String usernameOrMailOrPhone = requestParam.getUsernameOrMailOrPhone();
        boolean mailFlag=false;
        for (char c : usernameOrMailOrPhone.toCharArray()) {
            if (c=='@'){
                mailFlag=true;
                break;
            }
        }
        String username;
        if (mailFlag){
            LambdaQueryWrapper<UserMailDO> eq = Wrappers.lambdaQuery(UserMailDO.class)
                    .eq(UserMailDO::getMail,usernameOrMailOrPhone);
            username=Optional.ofNullable(userMailMapper.selectOne(eq))
                    .map(UserMailDO::getUsername)
                    .orElseThrow(()->new ClientException("用户名/手机号/邮箱不存在"));
        }else{
            LambdaQueryWrapper<UserPhoneDO> eq = Wrappers.lambdaQuery(UserPhoneDO.class)
                    .eq(UserPhoneDO::getPhone, usernameOrMailOrPhone);
            username=Optional.ofNullable(userPhoneMapper.selectOne(eq))
                    .map(UserPhoneDO::getUsername)
                    .orElse(null);
        }
        username=Optional.ofNullable(username).orElse(usernameOrMailOrPhone);
        LambdaQueryWrapper<UserDO> eq = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername,username)
                .eq(UserDO::getPassword,requestParam.getPassword());
        UserDO userDO = userMapper.selectOne(eq);
        if (userDO!=null){
            UserInfoDTO userInfoDTO = UserInfoDTO.builder()
                    .userId(String.valueOf(userDO.getId()))
                    .username(userDO.getUsername())
                    .realName(userDO.getRealName())
                    .build();
            String token = JWTUtil.generateAccessToken(userInfoDTO);
            userInfoDTO.setToken(token);
            //存储token到redis
            distributedCache.put(token,JSON.toJSONString(userInfoDTO),30, TimeUnit.MINUTES);
            return new UserLoginRespDTO(userDO.getId().toString(),username,userDO.getRealName(),token);
        }
        throw new ClientException("账号或密码错误");
    }

    @Override
    public UserLoginRespDTO checkLogin(String accessToken) {
        return distributedCache.get(accessToken,UserLoginRespDTO.class);
    }

    @Override
    public void logout(String accessToken) {
        if (StrUtil.isNotBlank(accessToken)){
            distributedCache.delete(accessToken);
        }
    }

    @Override
    public Boolean hasUsername(String username) {
        boolean contains = userRegisterCachePenetrationBloomFilter.contains(username);
        if (contains){
            StringRedisTemplate redisTemplate = (StringRedisTemplate) distributedCache.getInstance();
            return redisTemplate.opsForSet().isMember(USER_REGISTER_REUSE_SHARDING + hashShardingIdx(username),username);
        }
        return true;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserRegisterRespDTO register(UserRegisterReqDTO requestParam) {
        abstractChainContext.handler(UserChainMarkEnum.USER_REGISTER_FILTER.name(), requestParam);
        RLock lock = redissonClient.getLock(LOCK_USER_REGISTER + requestParam.getUsername());
        boolean isTryLock = lock.tryLock();
        if (!isTryLock){
            throw new ClientException(UserRegisterErrorCodeEnum.HAS_USERNAME_NOTNULL);
        }
        try {
            try {
                int insert = userMapper.insert(BeanUtil.convert(requestParam,UserDO.class));
                if (insert<1){
                    throw new ServiceException(USER_REGISTER_FAIL);
                }
            }catch (DuplicateKeyException ex){
                log.error("用户名[{}]重复注册",requestParam.getUsername());
                throw new ServiceException(USER_REGISTER_FAIL);
            }
            UserPhoneDO userPhoneDO = UserPhoneDO.builder()
                    .phone(requestParam.getPhone())
                    .username(requestParam.getUsername())
                    .build();
            try {
                int insert = userPhoneMapper.insert(userPhoneDO);
            }catch (DuplicateKeyException ex){
                log.error("当前用户【{}】手机号【{}】已存在",requestParam.getUsername(),requestParam.getPhone());
                throw new ServiceException(PHONE_REGISTERED);
            }
            if (StrUtil.isNotBlank(requestParam.getMail())){
                UserMailDO UserMailDO = org.opengoofy.index12306.biz.userservice.dao.entity.UserMailDO.builder()
                        .mail(requestParam.getMail())
                        .username(requestParam.getUsername())
                        .build();
                try {
                    int insert = userMailMapper.insert(UserMailDO);
                }catch (DuplicateKeyException ex){
                    log.error("当前用户【{}】注册邮箱【{}】被占用",requestParam.getUsername(),requestParam.getMail());
                    throw new ServiceException(UserRegisterErrorCodeEnum.MAIL_REGISTERED);
                }
            }
            String username = requestParam.getUsername();
            userReuseMapper.delete(Wrappers.lambdaQuery(UserReuseDO.class).eq(UserReuseDO::getUsername, username));
            StringRedisTemplate instance = (StringRedisTemplate) distributedCache.getInstance();
            instance.opsForSet().remove(USER_REGISTER_REUSE_SHARDING+hashShardingIdx(username),username);
            userRegisterCachePenetrationBloomFilter.add(username);
        }finally {
            lock.unlock();
        }
        return BeanUtil.convert(requestParam,UserRegisterRespDTO.class);
    }

    @Override
    public void deletion(UserDeletionReqDTO requestParam) {
        String username = UserContext.getUsername();
        if (!requestParam.getUsername().equals(username)){
            throw new ClientException("注销账号与登录账号不一致");
        }
        RLock lock = redissonClient.getLock(USER_DELETION + username);
        lock.lock();
        try {
            UserQueryRespDTO userQueryRespDTO = userService.queryUserByUsername(username);
            UserDeletionDO userDeletionDO = UserDeletionDO.builder()
                    .idCard(userQueryRespDTO.getIdCard())
                    .idType(userQueryRespDTO.getIdType())
                    .build();
            userDeletionMapper.insert(userDeletionDO);
            UserDO userDO=new UserDO();
            userDO.setDeletionTime(System.currentTimeMillis());
            userDO.setUsername(username);
            userMapper.deletionUser(userDO);
            UserPhoneDO userPhoneDO = UserPhoneDO.builder()
                    .phone(userQueryRespDTO.getPhone())
                    .deletionTime(System.currentTimeMillis())
                    .build();
            userPhoneMapper.deletionUser(userPhoneDO);
            if (StrUtil.isNotBlank(userQueryRespDTO.getMail())){
                UserMailDO UserMailDO=new UserMailDO();
                UserMailDO.setDeletionTime(System.currentTimeMillis());
                UserMailDO.setMail(userQueryRespDTO.getMail());
                userMailMapper.deletionUser(UserMailDO);
            }
            StringRedisTemplate instance = (StringRedisTemplate) distributedCache.getInstance();
            userReuseMapper.insert(new UserReuseDO(username));
            instance.opsForSet().add(USER_REGISTER_REUSE_SHARDING+hashShardingIdx(username),username);
            distributedCache.delete(UserContext.getToken());
        }finally {
            lock.unlock();
        }
    }
}
