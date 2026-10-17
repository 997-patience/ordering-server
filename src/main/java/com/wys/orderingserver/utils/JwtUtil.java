package com.wys.orderingserver.utils;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类（基于 auth0 java-jwt）
 *
 * 设计说明：
 * 1. 登录成功后，把用户信息放进 Map（claims），由本类签成 token 返回给前端；
 * 2. 前端后续请求把 token 放在 Authorization 请求头；
 * 3. LoginInterceptor 调用 parseToken 解析并校验签名与有效期，
 *    解析出的 Map 存入 ThreadLocalUtil，供 Service 层取当前登录人；
 * 4. token 同时存入 Redis（key = token），拦截器会二次校验，
 *    这样改密码时可以主动删除 token 实现"强制下线"。
 *
 * 面试考点：JWT 无状态、天然支持多端；代价是无法主动失效，
 * 所以额外用 Redis 存一份来实现主动失效。
 */
public class JwtUtil {

    /** 签名密钥（生产环境应从配置文件读取，不要硬编码在代码里） */
    private static final String KEY = "ordering";

    /** 有效期：12 小时 */
    public static final long EXPIRE_MILLIS = 1000L * 60 * 60 * 12;

    /** 令牌在 Redis 中的有效期（秒），与 token 本身的有效期保持一致 */
    public static final long EXPIRE_SECONDS = EXPIRE_MILLIS / 1000;

    /**
     * 生成 token
     *
     * @param claims 业务数据，如 id、username、storeId、role
     */
    public static String genToken(Map<String, Object> claims) {
        return JWT.create()
                .withClaim("claims", claims)
                .withExpiresAt(new Date(System.currentTimeMillis() + EXPIRE_MILLIS))
                .sign(Algorithm.HMAC256(KEY));
    }

    /**
     * 校验并解析 token
     *
     * @param token 前端传来的 token
     * @return 生成时放入的业务数据
     * @throws com.auth0.jwt.exceptions.JWTVerificationException token 非法或已过期
     */
    public static Map<String, Object> parseToken(String token) {
        return JWT.require(Algorithm.HMAC256(KEY))
                .build()
                .verify(token)
                .getClaim("claims")
                .asMap();
    }
}
