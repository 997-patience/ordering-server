package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.CartMapper;
import com.wys.orderingserver.pojo.Cart;
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
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 H2 执行真实数量更新 SQL，验证 JSON、用户归属、目标数量及并发事务。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:app-cart-update-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql,classpath:dish-add-test.sql,classpath:user-login-test.sql,classpath:cart-add-test.sql"
})
@AutoConfigureMockMvc
@Sql({"/app-dish-list-data.sql", "/app-cart-list-data.sql"})
class AppCartUpdateIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CartService cartService;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private CartMapper cartMapper;
    @MockitoBean private StringRedisTemplate redisTemplate;
    private DishRedisStub redis;

    @BeforeEach
    void prepareRedisAndOldTimes() {
        ThreadLocalUtil.remove();
        redis = new DishRedisStub(redisTemplate);
        jdbcTemplate.update("update t_cart set create_time = '2000-01-01 00:00:00', update_time = '2001-01-01 00:00:00'");
    }

    @AfterEach
    void contextIsCleared() {
        assertNull(ThreadLocalUtil.get());
    }

    @Test
    void minimalJsonWithoutCartIdSetsTargetQuantityAndChangesOnlyOwnedItemAndUpdateTime() throws Exception {
        Map<String, Object> before = row(1, 101);
        List<Map<String, Object>> others = otherRows();
        LocalDateTime beforeRequest = LocalDateTime.now().minusSeconds(1);
        updateRequest(1, "{\"dishId\":101,\"quantity\":5}");
        assertEquals(5, quantity(1, 101));
        assertEquals(before.get("ID"), row(1, 101).get("ID"));
        assertEquals(before.get("CREATE_TIME"), row(1, 101).get("CREATE_TIME"));
        assertEquals(before.get("USER_ID"), row(1, 101).get("USER_ID"));
        assertEquals(before.get("STORE_ID"), row(1, 101).get("STORE_ID"));
        assertEquals(others, otherRows());
        LocalDateTime updated = jdbcTemplate.queryForObject("select update_time from t_cart where id = 1001", LocalDateTime.class);
        assertNotNull(updated);
        assertTrue(!updated.isBefore(beforeRequest) && !updated.isAfter(LocalDateTime.now().plusSeconds(1)));
        verify(cartMapper).findByUserIdAndDishIdForUpdate(1L, 101L);
        mockMvc.perform(get("/app/cart/list").header("Authorization", userToken(1)).param("storeId", "1"))
                .andExpect(jsonPath("$.data[0].quantity").value(5))
                .andExpect(jsonPath("$.data[0].amount").value(190.00));
    }

    @Test
    void quantityCanDecreaseAndIsNotAccumulated() throws Exception {
        updateRequest(1, "{\"dishId\":101,\"quantity\":1}");
        assertEquals(1, quantity(1, 101));
        assertEquals(4, rowCount());
    }

    @Test
    void sameQuantitySucceedsWithoutDependingOnUnchangedUpdateRowCount() throws Exception {
        Map<String, Object> before = row(1, 101);
        updateRequest(1, "{\"dishId\":101,\"quantity\":2}");
        updateRequest(1, "{\"dishId\":101,\"quantity\":2}");
        assertEquals(before, row(1, 101));
        verify(cartMapper, never()).update(anyLong(), anyInt(), anyLong(), any(LocalDateTime.class));
    }

    @Test
    void retryingSameTargetDoesNotAddAgainOrChangeTimestampAgain() throws Exception {
        updateRequest(1, "{\"dishId\":101,\"quantity\":5}");
        Map<String, Object> once = row(1, 101);
        updateRequest(1, "{\"dishId\":101,\"quantity\":5}");
        assertEquals(once, row(1, 101));
        verify(cartMapper, times(1)).update(eq(101L), eq(5), eq(1L), any(LocalDateTime.class));
    }

    @Test
    void forgedCartUserAndStoreIdsCannotChangeAnotherUsersRecord() throws Exception {
        Map<String, Object> otherUser = row(2, 101);
        updateRequest(1, "{\"id\":1002,\"userId\":2,\"storeId\":2,\"dishId\":101,\"quantity\":6," +
                "\"createTime\":\"1970-01-01 00:00:00\",\"updateTime\":\"1970-01-01 00:00:00\"}");
        assertEquals(6, quantity(1, 101));
        assertEquals(otherUser, row(2, 101));
        assertEquals(1L, ((Number) row(1, 101).get("STORE_ID")).longValue());
        assertEquals(LocalDateTime.of(2000, 1, 1, 0, 0), jdbcTemplate.queryForObject(
                "select create_time from t_cart where id = 1001", LocalDateTime.class));
    }

    @Test
    void differentUserUpdatesOnlyTheirOwnQuantityForSameDish() throws Exception {
        updateRequest(2, "{\"dishId\":101,\"quantity\":4}");
        assertEquals(4, quantity(2, 101));
        assertEquals(2, quantity(1, 101));
    }

    @ParameterizedTest
    @CsvSource({"1,999", "3,101"})
    void itemNotInCurrentUsersCartIsRejectedWithoutCreatingOrChangingRows(int userId, long dishId) throws Exception {
        List<Map<String, Object>> before = allRows();
        mockMvc.perform(put("/app/cart").header("Authorization", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dishId\":" + dishId + ",\"quantity\":5}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("购物车中没有该菜品"));
        assertEquals(before, allRows());
        verify(cartMapper, never()).update(anyLong(), anyInt(), anyLong(), any(LocalDateTime.class));
    }

    @Test
    void existingOffSaleItemCanBeAdjustedAsQuantityEndpointDoesNotAddANewItem() throws Exception {
        updateRequest(1, "{\"dishId\":102,\"quantity\":1}");
        assertEquals(1, quantity(1, 102));
    }

    @Test
    void existingItemFromAnotherStoreDoesNotRequireStoreParameter() throws Exception {
        updateRequest(1, "{\"dishId\":201,\"quantity\":4}");
        assertEquals(4, quantity(1, 201));
        assertEquals(2L, ((Number) row(1, 201).get("STORE_ID")).longValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"dishId\":101}", "{\"quantity\":5}",
            "{\"dishId\":null,\"quantity\":1}", "{\"dishId\":101,\"quantity\":null}",
            "{\"dishId\":0,\"quantity\":1}", "{\"dishId\":-1,\"quantity\":1}",
            "{\"dishId\":101,\"quantity\":0}", "{\"dishId\":101,\"quantity\":-1}",
            "{\"dishId\":\"abc\",\"quantity\":1}", "{\"dishId\":101,\"quantity\":\"abc\"}",
            "{\"dishId\":101,\"quantity\":2147483648}"
    })
    void invalidJsonParametersDoNotUpdateOrDeleteCartRows(String body) throws Exception {
        List<Map<String, Object>> before = allRows();
        mockMvc.perform(put("/app/cart").header("Authorization", userToken(1))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(before, allRows());
        verify(cartMapper, never()).update(anyLong(), anyInt(), anyLong(), any(LocalDateTime.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{bad-json"})
    void missingOrMalformedBodyUsesExistingGlobalErrorHandler(String body) throws Exception {
        mockMvc.perform(put("/app/cart").header("Authorization", userToken(1))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请求参数格式错误，请检查请求体是否为合法 JSON"));
        verify(cartMapper, never()).update(anyLong(), anyInt(), anyLong(), any(LocalDateTime.class));
    }

    @ParameterizedTest
    @MethodSource("invalidCarts")
    void directServiceCallsAlsoValidateInput(Cart cart) {
        List<Map<String, Object>> before = allRows();
        assertThrows(BizException.class, () -> asUser(1, () -> cartService.update(cart)));
        assertEquals(before, allRows());
    }

    static Stream<Cart> invalidCarts() {
        return Stream.of(null, requestCart(null, 1), requestCart(0L, 1), requestCart(-1L, 1),
                requestCart(101L, null), requestCart(101L, 0), requestCart(101L, -1));
    }

    @Test
    void missingAdminAndRevokedTokensCannotUpdateCart() throws Exception {
        String body = "{\"dishId\":101,\"quantity\":5}";
        mockMvc.perform(put("/app/cart").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        String admin = cacheToken(Map.of("id", 1L, "type", "admin", "role", 1));
        mockMvc.perform(put("/app/cart").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        String user = userToken(1);
        redis.values.remove(user);
        mockMvc.perform(put("/app/cart").header("Authorization", user)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertThrows(BizException.class, () -> cartService.update(requestCart(101L, 5)));
        verifyNoInteractions(cartMapper);
    }

    @Test
    void longDishAndCartIdsAreSupported() throws Exception {
        jdbcTemplate.update("insert into t_dish(id,store_id,category_id,name,price,status,sort,create_time,update_time) " +
                "values (3000000001,1,11,'大ID菜品',1.00,1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbcTemplate.update("insert into t_cart(id,user_id,store_id,dish_id,quantity,create_time,update_time) " +
                "values (3000000002,1,1,3000000001,2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        updateRequest(1, "{\"dishId\":3000000001,\"quantity\":5}");
        assertEquals(5, quantity(1, 3000000001L));
    }

    @Test
    void maximumIntegerTargetCanBeSetWithoutArithmeticOverflow() throws Exception {
        updateRequest(1, "{\"dishId\":101,\"quantity\":2147483647}");
        assertEquals(Integer.MAX_VALUE, quantity(1, 101));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void abnormalAffectedRowsRollBackQuantityAndTimestamp(int affectedRows) {
        Map<String, Object> before = row(1, 101);
        doAnswer(call -> {
            sqlSessionTemplate.update("com.wys.orderingserver.mapper.CartMapper.update", Map.of(
                    "dishId", call.getArgument(0), "quantity", call.getArgument(1),
                    "userId", call.getArgument(2), "updateTime", call.getArgument(3)));
            return affectedRows;
        }).when(cartMapper).update(anyLong(), anyInt(), anyLong(), any(LocalDateTime.class));
        BizException error = assertThrows(BizException.class,
                () -> asUser(1, () -> cartService.update(requestCart(101L, 5))));
        assertEquals("购物车数量修改失败，请重试", error.getMessage());
        assertEquals(before, row(1, 101));
    }

    @Test
    void sqlItselfRequiresCurrentUserEvenWhenCalledWithoutService() {
        List<Map<String, Object>> before = allRows();
        assertEquals(0, cartMapper.update(101L, 5, 999L, LocalDateTime.now()));
        assertEquals(before, allRows());
        assertEquals(1, cartMapper.update(101L, 9, 2L, LocalDateTime.now()));
        assertEquals(9, quantity(2, 101));
        assertEquals(2, quantity(1, 101));
    }

    @ParameterizedTest
    @ValueSource(strings = {"set-first", "add-first"})
    void concurrentAddAndSetRespectSerializedOperationOrder(String order) throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicReference<Future<?>> pending = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                if (order.equals("set-first")) {
                    asUser(1, () -> cartService.update(requestCart(101L, 5)));
                    doAnswer(call -> {
                        attempted.countDown();
                        return sqlSessionTemplate.insert("com.wys.orderingserver.mapper.CartMapper.add", call.getArgument(0));
                    }).when(cartMapper).add(any(Cart.class));
                    pending.set(executor.submit(() -> asUser(1, () -> cartService.add(requestCart(101L, 2)))));
                } else {
                    asUser(1, () -> cartService.add(requestCart(101L, 2)));
                    interceptCartLookup(attempted);
                    pending.set(executor.submit(() -> asUser(1, () -> cartService.update(requestCart(101L, 5)))));
                }
                awaitAttempt(attempted);
            });
            pending.get().get(6, TimeUnit.SECONDS);
            assertEquals(order.equals("set-first") ? 7 : 5, quantity(1, 101));
            assertEquals(7, quantity(2, 101));
            assertEquals(4, rowCount());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void deletionDuringWaitIsDetectedAndDoesNotRecreateTheCartItem() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicReference<Future<BizException>> pending = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.update("delete from t_cart where user_id = 1 and dish_id = 101");
                interceptCartLookup(attempted);
                pending.set(executor.submit(() -> assertThrows(BizException.class,
                        () -> asUser(1, () -> cartService.update(requestCart(101L, 5))))));
                awaitAttempt(attempted);
            });
            assertEquals("购物车中没有该菜品", pending.get().get(6, TimeUnit.SECONDS).getMessage());
            assertEquals(0, jdbcTemplate.queryForObject("select count(*) from t_cart where user_id = 1 and dish_id = 101", Integer.class));
            assertEquals(7, quantity(2, 101));
            verify(cartMapper, never()).update(anyLong(), anyInt(), anyLong(), any(LocalDateTime.class));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void interceptCartLookup(CountDownLatch attempted) {
        doAnswer(call -> {
            attempted.countDown();
            return sqlSessionTemplate.selectOne("com.wys.orderingserver.mapper.CartMapper.findByUserIdAndDishIdForUpdate",
                    Map.of("userId", call.getArgument(0), "dishId", call.getArgument(1)));
        }).when(cartMapper).findByUserIdAndDishIdForUpdate(1L, 101L);
    }

    private void awaitAttempt(CountDownLatch attempted) {
        try { assertTrue(attempted.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }

    private void updateRequest(int userId, String body) throws Exception {
        mockMvc.perform(put("/app/cart").header("Authorization", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    private static Cart requestCart(Long dishId, Integer quantity) {
        Cart cart = new Cart(); cart.setDishId(dishId); cart.setQuantity(quantity); return cart;
    }

    private void asUser(long userId, Runnable action) {
        ThreadLocalUtil.set(Map.of("id", userId, "type", "user"));
        try { action.run(); } finally { ThreadLocalUtil.remove(); }
    }

    private int quantity(long userId, long dishId) {
        return jdbcTemplate.queryForObject("select quantity from t_cart where user_id = ? and dish_id = ?",
                Integer.class, userId, dishId);
    }

    private Map<String, Object> row(long userId, long dishId) {
        return jdbcTemplate.queryForMap("select * from t_cart where user_id = ? and dish_id = ?", userId, dishId);
    }

    private List<Map<String, Object>> otherRows() {
        return jdbcTemplate.queryForList("select * from t_cart where id <> 1001 order by id");
    }

    private List<Map<String, Object>> allRows() {
        return jdbcTemplate.queryForList("select * from t_cart order by id");
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("select count(*) from t_cart", Integer.class);
    }

    private String userToken(int userId) {
        String phone = switch (userId) {
            case 1 -> "13800000001"; case 2 -> "13900000002"; default -> "13700000003";
        };
        return cacheToken(Map.of("id", (long) userId, "phone", phone, "type", "user"));
    }

    private String cacheToken(Map<String, Object> claims) {
        String token = JwtUtil.genToken(claims); redis.values.put(token, token); return token;
    }
}
