package com.wys.orderingserver.utils;

import com.wys.orderingserver.Exception.BizException;

import java.util.Map;

/**
 * 当前管理员上下文：统一检查登录身份、管理员角色和门店绑定。
 * 仅从当前请求的 ThreadLocal 读取信息，不保存跨请求的用户数据。
 * JWT、Redis 和员工有效性校验仍由 LoginInterceptor 负责。
 */
public final class AdminContextUtil {

    private static final long SUPER_ADMIN = 1;
    private static final long STORE_ADMIN = 2;
    private static final String NO_PERMISSION = "无门店管理权限";

    private AdminContextUtil() {
    }

    /** 一次完成三项检查：已登录、是管理员、已绑定有效门店，并返回门店 ID。 */
    public static Long getCurrentAdminStoreId() {
        return getCurrentAdminStoreId("当前员工未绑定门店，请使用已绑定门店的管理员账号");
    }

    /** 同样完成三项检查，允许调用方提供未绑定门店时的提示。 */
    public static Long getCurrentAdminStoreId(String unboundMessage) {
        Map<String, Object> claims = getCurrentAdminClaims(NO_PERMISSION);
        Object storeIdValue = claims.get("storeId");
        if (!(storeIdValue instanceof Number id) || id.longValue() < 1) {
            throw new BizException(unboundMessage);
        }
        return id.longValue();
    }

    /** 先检查已登录且是管理员，再判断是否超管；这里不要求绑定门店。 */
    public static boolean isSuperAdmin() {
        Map<String, Object> claims = getCurrentAdminClaims(NO_PERMISSION);
        return ((Number) claims.get("role")).longValue() == SUPER_ADMIN;
    }

    /** 仅允许超管操作，例如新增、删除门店；超管可以没有所属门店。 */
    public static void requireSuperAdmin(String permissionMessage) {
        Map<String, Object> claims = getCurrentAdminClaims(permissionMessage);
        if (((Number) claims.get("role")).longValue() != SUPER_ADMIN) {
            throw new BizException(permissionMessage);
        }
    }

    /** 获取当前管理员用户名，用于员工信息接口，不要求绑定门店。 */
    public static String getCurrentAdminUsername() {
        Map<String, Object> claims = getCurrentAdminClaims(NO_PERMISSION);
        Object usernameValue = claims.get("username");
        if (!(usernameValue instanceof String username) || username.isBlank()) {
            throw new BizException("登录信息缺少用户名，请重新登录");
        }
        return username;
    }

    /** 所有管理员上下文方法都通过此处检查登录和角色。 */
    private static Map<String, Object> getCurrentAdminClaims(String permissionMessage) {
        Map<String, Object> claims = ThreadLocalUtil.get();
        if (claims == null) {
            throw new BizException("未获取到登录信息，请重新登录");
        }
        Object roleValue = claims.get("role");
        if (!(roleValue instanceof Number role)
                || (role.longValue() != SUPER_ADMIN && role.longValue() != STORE_ADMIN)) {
            throw new BizException(permissionMessage);
        }
        return claims;
    }
}
