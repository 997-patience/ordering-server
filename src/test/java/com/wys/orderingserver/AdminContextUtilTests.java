package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.utils.AdminContextUtil;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 权限工具的边界测试：防止抽取后误放行角色或误限制未绑定门店的超管。 */
class AdminContextUtilTests {

    @BeforeEach
    @AfterEach
    void clearIdentity() {
        ThreadLocalUtil.remove();
    }

    @Test
    void allEntryPointsRejectMissingLogin() {
        List<Executable> operations = List.of(
                () -> AdminContextUtil.getCurrentAdminStoreId(),
                () -> AdminContextUtil.getCurrentAdminStoreId("没有绑定"),
                () -> AdminContextUtil.isSuperAdmin(),
                () -> AdminContextUtil.requireSuperAdmin("仅超管"),
                () -> AdminContextUtil.getCurrentAdminUsername());
        for (Executable operation : operations) {
            BizException error = assertThrows(BizException.class, operation);
            assertEquals("未获取到登录信息，请重新登录", error.getMessage());
        }
    }

    @ParameterizedTest
    @MethodSource("validNumericIdentities")
    void acceptsIntegerAndLongForBothAdminRoles(Number role, Number storeId) {
        ThreadLocalUtil.set(Map.of("role", role, "storeId", storeId));
        assertEquals(128L, AdminContextUtil.getCurrentAdminStoreId());
        assertEquals(role.longValue() == 1, AdminContextUtil.isSuperAdmin());
    }

    static Stream<Arguments> validNumericIdentities() {
        return Stream.of(Arguments.of(1, 128), Arguments.of(2, 128L),
                Arguments.of(1L, 128L), Arguments.of(2L, 128));
    }

    @ParameterizedTest
    @MethodSource("invalidRoles")
    void validStoreBindingDoesNotGrantAdminRole(Object role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("storeId", 1L);
        if (role != null) {
            claims.put("role", role);
        }
        ThreadLocalUtil.set(claims);
        assertEquals("无门店管理权限",
                assertThrows(BizException.class, AdminContextUtil::getCurrentAdminStoreId).getMessage());
        assertThrows(BizException.class, AdminContextUtil::isSuperAdmin);
        assertEquals("仅超管",
                assertThrows(BizException.class, () -> AdminContextUtil.requireSuperAdmin("仅超管")).getMessage());
    }

    static Stream<Arguments> invalidRoles() {
        return Stream.of(Arguments.of((Object) null), Arguments.of("1"), Arguments.of(0),
                Arguments.of(3), Arguments.of(-1), Arguments.of(4294967297L));
    }

    @ParameterizedTest
    @MethodSource("invalidStoreIds")
    void adminRoleDoesNotBypassRequiredStoreBinding(Object storeId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", 2);
        if (storeId != null) {
            claims.put("storeId", storeId);
        }
        ThreadLocalUtil.set(claims);
        assertEquals("当前员工未绑定门店，请使用已绑定门店的管理员账号",
                assertThrows(BizException.class, AdminContextUtil::getCurrentAdminStoreId).getMessage());
        assertEquals("自定义门店提示",
                assertThrows(BizException.class,
                        () -> AdminContextUtil.getCurrentAdminStoreId("自定义门店提示")).getMessage());
    }

    static Stream<Arguments> invalidStoreIds() {
        return Stream.of(Arguments.of((Object) null), Arguments.of("1"), Arguments.of(0), Arguments.of(-1L));
    }

    @Test
    void superAdminOnlyOperationsDoNotRequireStoreBinding() {
        ThreadLocalUtil.set(Map.of("role", 1));
        assertTrue(AdminContextUtil.isSuperAdmin());
        assertDoesNotThrow(() -> AdminContextUtil.requireSuperAdmin("仅超管"));
        assertThrows(BizException.class, AdminContextUtil::getCurrentAdminStoreId);
    }

    @Test
    void storeAdminCannotUseSuperAdminOperationEvenWithStoreBinding() {
        ThreadLocalUtil.set(Map.of("role", 2, "storeId", 1));
        assertFalse(AdminContextUtil.isSuperAdmin());
        assertEquals("不能创建门店",
                assertThrows(BizException.class,
                        () -> AdminContextUtil.requireSuperAdmin("不能创建门店")).getMessage());
    }

    @Test
    void profileCanGetSuperAdminUsernameWithoutStoreBinding() {
        ThreadLocalUtil.set(Map.of("role", 1, "username", "admin"));
        assertEquals("admin", AdminContextUtil.getCurrentAdminUsername());
    }

    @ParameterizedTest
    @MethodSource("invalidUsernames")
    void profileRequiresNonBlankUsername(Object username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", 1);
        if (username != null) {
            claims.put("username", username);
        }
        ThreadLocalUtil.set(claims);
        assertEquals("登录信息缺少用户名，请重新登录",
                assertThrows(BizException.class, AdminContextUtil::getCurrentAdminUsername).getMessage());
    }

    static Stream<Arguments> invalidUsernames() {
        return Stream.of(Arguments.of((Object) null), Arguments.of(""), Arguments.of("   "), Arguments.of(123));
    }

    @Test
    void eachCallReadsCurrentRequestInsteadOfCachingIdentity() {
        ThreadLocalUtil.set(Map.of("role", 2, "storeId", 1));
        assertEquals(1L, AdminContextUtil.getCurrentAdminStoreId());
        ThreadLocalUtil.remove();
        assertThrows(BizException.class, AdminContextUtil::getCurrentAdminStoreId);
        ThreadLocalUtil.set(Map.of("role", 2, "storeId", 2));
        assertEquals(2L, AdminContextUtil.getCurrentAdminStoreId());
    }
}
