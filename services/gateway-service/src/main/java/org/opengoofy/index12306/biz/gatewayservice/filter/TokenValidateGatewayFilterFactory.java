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

package org.opengoofy.index12306.biz.gatewayservice.filter;

import org.opengoofy.index12306.biz.gatewayservice.config.Config;
import org.opengoofy.index12306.biz.gatewayservice.toolkit.JWTUtil;
import org.opengoofy.index12306.biz.gatewayservice.toolkit.UserInfoDTO;
import org.opengoofy.index12306.frameworks.bases.constant.UserConstant;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Spring cloud gateway token 拦截器
 */
@Component
public class TokenValidateGatewayFilterFactory extends AbstractGatewayFilterFactory<Config> {
    //注销用户路径
    private static final String DELETE_PATH="/api/user-service/deletion";

    public TokenValidateGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String path = request.getPath().toString();
            if(isPathBlackListPreList(path,config.getBlackPathPre())){
                String token = request.getHeaders().getFirst("Authorization");
                //解析jwt令牌
                UserInfoDTO userInfoDTO = JWTUtil.parseJwtToken(token);
                if (!validateToken(userInfoDTO)){
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    return response.setComplete();
                }
                ServerHttpRequest.Builder builder = exchange.getRequest().mutate().headers(httpHeaders -> {
                    httpHeaders.set(UserConstant.USER_ID_KEY,userInfoDTO.getUserId());
                    httpHeaders.set(UserConstant.USER_NAME_KEY,userInfoDTO.getUsername());
                    httpHeaders.set(UserConstant.REAL_NAME_KEY,userInfoDTO.getRealName());
                    if (Objects.equals(path,DELETE_PATH)){
                        httpHeaders.set(UserConstant.USER_TOKEN_KEY,token);
                    }
                });
                return chain.filter(exchange.mutate().request(builder.build()).build());
            }
            return chain.filter(exchange);
        };
    }

    private boolean validateToken(UserInfoDTO userInfoDTO) {
        return userInfoDTO!=null;
    }

    private boolean isPathBlackListPreList(String path, List<String> blackPathPre) {
        if (CollectionUtils.isEmpty(blackPathPre)){
            return false;
        }
        return blackPathPre.stream().anyMatch(path::startsWith);
    }
}
