package org.opengoofy.index12306.frameworks.starter.cache.frameworks.starter.user.user.core;


import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.opengoofy.index12306.frameworks.starter.cache.frameworks.starter.user.bases.constant.UserConstant;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URLDecoder;

import static java.nio.charset.StandardCharsets.UTF_8;

public class UserTransmitFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        String userId = httpServletRequest.getHeader(UserConstant.USER_ID_KEY);
        if (StringUtils.hasText(userId)){
            String username = httpServletRequest.getHeader(UserConstant.USER_NAME_KEY);
            String realName = httpServletRequest.getHeader(UserConstant.REAL_NAME_KEY);
            if (StringUtils.hasText(username)){
                username= URLDecoder.decode(username,UTF_8);
            }
            if (StringUtils.hasText(realName)){
                realName=URLDecoder.decode(username,UTF_8);
            }
            String token = httpServletRequest.getHeader(UserConstant.USER_TOKEN_KEY);
            UserInfoDTO userInfoDTO = UserInfoDTO.builder()
                    .userId(userId)
                    .username(username)
                    .realName(realName)
                    .token(token)
                    .build();
            UserContext.setUser(userInfoDTO);
        }
        try {
            chain.doFilter(request,response);
        }finally {
            UserContext.removeUser();
        }
    }
}
