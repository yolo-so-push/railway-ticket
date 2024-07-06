package org.opengoofy.index12306.frameworks.starter.cache.frameworks.starter.user.user.toolkit;

import com.alibaba.fastjson2.JSON;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.opengoofy.index12306.frameworks.starter.cache.frameworks.starter.user.bases.constant.UserConstant;
import org.opengoofy.index12306.frameworks.starter.cache.frameworks.starter.user.user.core.UserInfoDTO;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.HashMap;

/**
 * JWT工具类
 */
@Slf4j
public final class JWTUtil {
    private static final long EXPIRATION = 86400L;
    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String ISS = "index12306";
    public static final String SECRET = "SecretKey039245678901232039487623456783092349288901402967890140939827";


    public static String generateAccessToken(UserInfoDTO userInfoDTO){
        HashMap<String, Object> customerUserMap = new HashMap<>();
        customerUserMap.put(UserConstant.USER_ID_KEY,userInfoDTO.getUserId());
        customerUserMap.put(UserConstant.USER_NAME_KEY,userInfoDTO.getUsername());
        customerUserMap.put(UserConstant.REAL_NAME_KEY,userInfoDTO.getRealName());
        customerUserMap.put(UserConstant.USER_TOKEN_KEY,userInfoDTO.getToken());
        String jwtToken = Jwts.builder()
                .signWith(SignatureAlgorithm.HS512, SECRET)
                .setIssuedAt(new Date())
                .setIssuer(ISS)
                .setSubject(JSON.toJSONString(customerUserMap))
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION * 1000))
                .compact();
        return TOKEN_PREFIX+jwtToken;
    }

    public static UserInfoDTO parseJwtToken(String jwtToken){
        if (StringUtils.hasText(jwtToken)){
            String actualToken = jwtToken.replace(TOKEN_PREFIX, "");
            try {
                Claims claims = Jwts.parser().setSigningKey(SECRET)
                        .parseClaimsJws(actualToken)
                        .getBody();
                Date expiration = claims.getExpiration();
                if (expiration.after(new Date())){
                    String subject = claims.getSubject();
                    return JSON.parseObject(subject, UserInfoDTO.class);
                }
            }catch (ExpiredJwtException ignored){
            }catch (Exception ex){
                log.error("jwt token 解析失败，请检查",ex);
            }
        }
        return null;
    }
}
