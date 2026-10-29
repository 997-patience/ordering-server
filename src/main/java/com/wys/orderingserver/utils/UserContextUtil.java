package com.wys.orderingserver.utils;

import com.wys.orderingserver.Exception.BizException;

import java.util.Map;

/** 当前顾客身份：从本次请求的 ThreadLocal 取得用户 ID，供用户端业务统一使用。 */
public final class UserContextUtil {
    private UserContextUtil() {
    }

    public static Long getCurrentUserId() {
        Object context = ThreadLocalUtil.get();
        if (!(context instanceof Map<?, ?> claims)) {
            throw new BizException("未获取到登录信息，请重新登录");
        }
        if (!"user".equals(claims.get("type")) || claims.get("role") != null) {
            throw new BizException("无用户端访问权限");
        }
        Object idValue = claims.get("id");
        // 本项目用户 ID 为 Long，JWT 还原较小整数时也可能得到 Integer。
        if (!(idValue instanceof Integer || idValue instanceof Long)) {
            throw new BizException("登录信息缺少有效的用户ID，请重新登录");
        }
        long userId = ((Number) idValue).longValue();
        if (userId < 1) {
            throw new BizException("登录信息缺少有效的用户ID，请重新登录");
        }
        return userId;
    }
}
