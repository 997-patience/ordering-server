package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.CartMapper;
import com.wys.orderingserver.pojo.CartItemVO;
import com.wys.orderingserver.service.CartService;
import com.wys.orderingserver.support.DishRedisStub;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 H2 执行真实购物车关联 SQL，覆盖当前用户隔离、可选门店和精确金额。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:app-cart-list-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql,classpath:dish-add-test.sql,classpath:user-login-test.sql,classpath:dish-delete-setup.sql"
})
@AutoConfigureMockMvc
@Sql({"/app-dish-list-data.sql", "/app-cart-list-data.sql"})
class AppCartListIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CartService cartService;
    @MockitoSpyBean private CartMapper cartMapper;
    @MockitoBean private StringRedisTemplate redisTemplate;
    private DishRedisStub redis;

    @BeforeEach
    void prepareRedis() {
        ThreadLocalUtil.remove();
        redis = new DishRedisStub(redisTemplate);
    }

    @AfterEach
    void identityIsCleared() {
        assertNull(ThreadLocalUtil.get());
    }

    @Test
    void omittedStoreIdReturnsOnlyCurrentUsersRowsAcrossStoresAndDocumentedFields() throws Exception {
        mockMvc.perform(get("/app/cart/list").header("Authorization", userToken(1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].id").value(1001))
                .andExpect(jsonPath("$.data[1].id").value(1003))
                .andExpect(jsonPath("$.data[2].id").value(1004))
                .andExpect(jsonPath("$.data[0]").value(aMapWithSize(7)))
                .andExpect(jsonPath("$.data[0].dishId").value(101))
                .andExpect(jsonPath("$.data[0].name").value("宫保鸡丁"))
                .andExpect(jsonPath("$.data[0].image").value("/upload/chicken.jpg"))
                .andExpect(jsonPath("$.data[0].price").value(38.00))
                .andExpect(jsonPath("$.data[0].quantity").value(2))
                .andExpect(jsonPath("$.data[0].amount").value(76.00));
        verify(cartMapper).listByUserIdAndStoreId(1L, null);
    }

    @ParameterizedTest
    @CsvSource({"1,2,1001", "2,1,1003"})
    void storeIdFiltersOnlyWithinCurrentUsersCart(long storeId, int count, long firstId) throws Exception {
        mockMvc.perform(get("/app/cart/list").header("Authorization", userToken(1))
                        .param("storeId", Long.toString(storeId)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(count))
                .andExpect(jsonPath("$.data[0].id").value(firstId));
        verify(cartMapper).listByUserIdAndStoreId(1L, storeId);
    }

    @Test
    void secondUserGetsOwnQuantityForSameDishInSameStore() throws Exception {
        mockMvc.perform(get("/app/cart/list").header("Authorization", userToken(2)).param("storeId", "1"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(1002))
                .andExpect(jsonPath("$.data[0].quantity").value(7))
                .andExpect(jsonPath("$.data[0].amount").value(266.00));
        verify(cartMapper).listByUserIdAndStoreId(2L, 1L);
    }

    @Test
    void userWithEmptyCartGetsSuccessfulEmptyArray() throws Exception {
        mockMvc.perform(get("/app/cart/list").header("Authorization", userToken(3)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(longs = {3, 999})
    void storeWithoutCurrentUsersItemsReturnsEmptyArray(long storeId) throws Exception {
        mockMvc.perform(get("/app/cart/list").header("Authorization", userToken(1))
                        .param("storeId", Long.toString(storeId)))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void clientCannotChooseAnotherUserThroughQueryParameters() throws Exception {
        mockMvc.perform(get("/app/cart/list").header("Authorization", userToken(1))
                        .param("storeId", "1").param("userId", "2"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1001))
                .andExpect(jsonPath("$.data[1].id").value(1004));
        verify(cartMapper).listByUserIdAndStoreId(1L, 1L);
        verify(cartMapper, never()).listByUserIdAndStoreId(eq(2L), any());
    }

    @Test
    void consecutiveRequestsFromDifferentUsersDoNotShareIdentityOrCartCache() throws Exception {
        mockMvc.perform(get("/app/cart/list").header("Authorization", userToken(1)))
                .andExpect(jsonPath("$.data.length()").value(3));
        assertNull(ThreadLocalUtil.get());
        mockMvc.perform(get("/app/cart/list").header("Authorization", userToken(2)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(1002));
        verify(redis.operations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void nonPositiveStoreIdIsRejectedBeforeCartQuery(String storeId) throws Exception {
        mockMvc.perform(get("/app/cart/list").header("Authorization", userToken(1)).param("storeId", storeId))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请选择有效的门店ID"));
        verifyNoInteractions(cartMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "9223372036854775808"})
    void malformedStoreIdUsesExistingGlobalTypeError(String storeId) throws Exception {
        mockMvc.perform(get("/app/cart/list").header("Authorization", userToken(1)).param("storeId", storeId))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：storeId"));
        verifyNoInteractions(cartMapper);
    }

    @Test
    void amountUsesExactDecimalMultiplication() {
        jdbcTemplate.update("update t_dish set price = 0.10 where id = 101");
        jdbcTemplate.update("update t_cart set quantity = 3 where id = 1001");
        CartItemVO item = withUser(1L, () -> cartService.find(1L).getFirst());
        assertEquals(new BigDecimal("0.10"), item.getPrice());
        assertEquals(new BigDecimal("0.30"), item.getAmount());
    }

    @Test
    void freeDishHasZeroAmount() {
        jdbcTemplate.update("update t_dish set price = 0.00 where id = 101");
        assertEquals(new BigDecimal("0.00"), withUser(1L, () -> cartService.find(1L).getFirst().getAmount()));
    }

    @Test
    void joinedDishFieldsAndAmountReflectCurrentDatabasePrice() throws Exception {
        String token = userToken(1);
        mockMvc.perform(get("/app/cart/list").header("Authorization", token).param("storeId", "1"))
                .andExpect(jsonPath("$.data[0].price").value(38.00));
        jdbcTemplate.update("update t_dish set price = 40.10, name = '菜品新名称', image = '/upload/new.jpg' where id = 101");
        mockMvc.perform(get("/app/cart/list").header("Authorization", token).param("storeId", "1"))
                .andExpect(jsonPath("$.data[0].price").value(40.10))
                .andExpect(jsonPath("$.data[0].amount").value(80.20))
                .andExpect(jsonPath("$.data[0].name").value("菜品新名称"))
                .andExpect(jsonPath("$.data[0].image").value("/upload/new.jpg"));
    }

    @Test
    void mismatchedCartAndDishStoreCannotBeJoined() throws Exception {
        jdbcTemplate.update("insert into t_cart(id, user_id, store_id, dish_id, quantity, create_time, update_time) " +
                "values (1005, 1, 2, 103, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        mockMvc.perform(get("/app/cart/list").header("Authorization", userToken(1)))
                .andExpect(jsonPath("$.data.length()").value(3));
        mockMvc.perform(get("/app/cart/list").header("Authorization", userToken(1)).param("storeId", "2"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(1003));
    }

    @Test
    void offSaleCartItemRemainsVisibleBecauseListDocumentDoesNotFilterStatus() throws Exception {
        mockMvc.perform(get("/app/cart/list").header("Authorization", userToken(1)).param("storeId", "1"))
                .andExpect(jsonPath("$.data[1].dishId").value(102))
                .andExpect(jsonPath("$.data[1].name").value("下架菜品"));
    }

    @Test
    void longUserAndCartIdsAreSupported() throws Exception {
        jdbcTemplate.update("insert into t_user(id, phone, password, nickname) values (3000000000, '13600000004', 'test', '大ID用户')");
        jdbcTemplate.update("insert into t_cart(id, user_id, store_id, dish_id, quantity, create_time, update_time) " +
                "values (3000000001, 3000000000, 1, 101, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        String token = cacheToken(Map.of("id", 3000000000L, "phone", "13600000004", "type", "user"));
        mockMvc.perform(get("/app/cart/list").header("Authorization", token))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(3000000001L))
                .andExpect(jsonPath("$.data[0].amount").value(152.00));
    }

    @Test
    void missingTokenReturnsEmpty401BeforeCartQuery() throws Exception {
        mockMvc.perform(get("/app/cart/list"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        verifyNoInteractions(cartMapper);
    }

    @Test
    void adminTokenWithSameNumericIdCannotEnterCartEndpoint() throws Exception {
        String token = cacheToken(Map.of("id", 1L, "role", 1, "type", "admin"));
        mockMvc.perform(get("/app/cart/list").header("Authorization", token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(cartMapper);
    }

    @Test
    void revokedTokenCannotEnterCartEndpoint() throws Exception {
        String token = userToken(1);
        redis.values.remove(token);
        mockMvc.perform(get("/app/cart/list").header("Authorization", token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(cartMapper);
    }

    @Test
    void inconsistentPhoneAndIdInTokenCannotChooseAnotherUsersCart() throws Exception {
        String token = cacheToken(Map.of("id", 2L, "phone", "13800000001", "type", "user"));
        mockMvc.perform(get("/app/cart/list").header("Authorization", token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(cartMapper);
    }

    @ParameterizedTest
    @MethodSource("invalidContexts")
    void invalidDirectServiceContextCannotQueryAnyCart(Object context) {
        ThreadLocalUtil.set(context);
        try {
            assertThrows(BizException.class, () -> cartService.find(null));
            verifyNoInteractions(cartMapper);
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    static Stream<Object> invalidContexts() {
        return Stream.of(null, "invalid context", Map.of("id", 1),
                Map.of("id", 1, "type", "admin", "role", 1),
                Map.of("type", "user"), Map.of("type", "user", "id", 0),
                Map.of("type", "user", "id", -1), Map.of("type", "user", "id", "1"),
                Map.of("type", "user", "id", 1.5), Map.of("type", "user", "id", 1, "role", 1));
    }

    @Test
    void directServiceCallsAlsoValidateOptionalStoreId() {
        withUser(1L, () -> {
            assertThrows(BizException.class, () -> cartService.find(0L));
            return null;
        });
        verifyNoInteractions(cartMapper);
    }

    private <T> T withUser(Long userId, Supplier<T> action) {
        ThreadLocalUtil.set(Map.of("id", userId, "type", "user"));
        try { return action.get(); } finally { ThreadLocalUtil.remove(); }
    }

    private String userToken(int userId) {
        String phone = switch (userId) {
            case 1 -> "13800000001";
            case 2 -> "13900000002";
            default -> "13700000003";
        };
        return cacheToken(Map.of("id", (long) userId, "phone", phone, "type", "user"));
    }

    private String cacheToken(Map<String, Object> claims) {
        String token = JwtUtil.genToken(claims);
        redis.values.put(token, token);
        return token;
    }
}
