package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.CartMapper;
import com.wys.orderingserver.mapper.StoreMapper;
import com.wys.orderingserver.pojo.Cart;
import com.wys.orderingserver.service.CartService;
import com.wys.orderingserver.support.DishRedisStub;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 H2 MySQL 模式执行真实 upsert，验证加购归属、并发数量和事务回滚。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:app-cart-add-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql,classpath:dish-add-test.sql,classpath:user-login-test.sql,classpath:cart-add-test.sql"
})
@AutoConfigureMockMvc
@Sql("/app-dish-list-data.sql")
class AppCartAddIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CartService cartService;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private CartMapper cartMapper;
    @MockitoSpyBean private StoreMapper storeMapper;
    @MockitoBean private StringRedisTemplate redisTemplate;
    private DishRedisStub redis;

    @BeforeEach
    void prepareRedisAndSecondUser() {
        ThreadLocalUtil.remove();
        redis = new DishRedisStub(redisTemplate);
        jdbcTemplate.update("insert into t_user(id, phone, password, nickname) values (2, '13900000002', 'test', '小红')");
    }

    @AfterEach
    void identityIsCleared() {
        assertNull(ThreadLocalUtil.get());
    }

    @Test
    void newCartItemUsesGeneratedIdDatabaseStoreAndServerTimes() throws Exception {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        addRequest(1, "{\"dishId\":101,\"quantity\":2}");
        assertEquals(1, rowCount());
        Map<String, Object> row = row(1, 101);
        assertEquals(1L, ((Number) row.get("USER_ID")).longValue());
        assertEquals(1L, ((Number) row.get("STORE_ID")).longValue());
        assertEquals(2, quantity(1, 101));
        assertTrue(((Number) row.get("ID")).longValue() > 0);
        LocalDateTime created = jdbcTemplate.queryForObject("select create_time from t_cart where user_id = 1", LocalDateTime.class);
        LocalDateTime updated = jdbcTemplate.queryForObject("select update_time from t_cart where user_id = 1", LocalDateTime.class);
        assertNotNull(created);
        assertTrue(!created.isBefore(before) && !created.isAfter(LocalDateTime.now().plusSeconds(1)));
        assertEquals(created, updated);
    }

    @Test
    void repeatedAddAccumulatesQuantityAndPreservesIdAndCreateTime() throws Exception {
        addRequest(1, "{\"dishId\":101,\"quantity\":2}");
        Map<String, Object> before = row(1, 101);
        addRequest(1, "{\"dishId\":101,\"quantity\":3}");
        assertEquals(1, rowCount());
        assertEquals(5, quantity(1, 101));
        assertEquals(before.get("ID"), row(1, 101).get("ID"));
        assertEquals(before.get("CREATE_TIME"), row(1, 101).get("CREATE_TIME"));
    }

    @Test
    void forgedUserStoreIdAndCartIdDoNotChangeAnotherUsersRecord() throws Exception {
        jdbcTemplate.update("insert into t_cart(id,user_id,store_id,dish_id,quantity,create_time,update_time) " +
                "values (7777,2,1,101,4,'2000-01-01 00:00:00','2000-01-01 00:00:00')");
        Map<String, Object> otherUser = row(2, 101);
        addRequest(1, "{\"id\":7777,\"userId\":2,\"storeId\":2,\"dishId\":101,\"quantity\":2," +
                "\"createTime\":\"2000-01-01 00:00:00\",\"updateTime\":\"2000-01-01 00:00:00\"}");
        assertEquals(otherUser, row(2, 101));
        assertEquals(2, rowCount());
        assertEquals(2, quantity(1, 101));
        assertEquals(1L, ((Number) row(1, 101).get("STORE_ID")).longValue());
        assertNotEquals(7777L, ((Number) row(1, 101).get("ID")).longValue());
    }

    @Test
    void differentUsersCanAddSameDishWithoutMixingQuantities() throws Exception {
        addRequest(1, "{\"dishId\":101,\"quantity\":2}");
        addRequest(2, "{\"dishId\":101,\"quantity\":7}");
        addRequest(1, "{\"dishId\":101,\"quantity\":3}");
        assertEquals(2, rowCount());
        assertEquals(5, quantity(1, 101));
        assertEquals(7, quantity(2, 101));
    }

    @Test
    void addingFromAnotherStoreKeepsExistingCartRatherThanSilentlyClearingIt() throws Exception {
        addRequest(1, "{\"dishId\":101,\"quantity\":2}");
        addRequest(1, "{\"dishId\":201,\"quantity\":1,\"storeId\":1}");
        assertEquals(2, rowCount());
        assertEquals(2, quantity(1, 101));
        assertEquals(2L, ((Number) row(1, 201).get("STORE_ID")).longValue());
    }

    @Test
    void absentDishReturnsBusinessErrorWithoutWritingCart() throws Exception {
        mockMvc.perform(post("/app/cart").header("Authorization", userToken(1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dishId\":999,\"quantity\":1}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("菜品不存在"));
        verify(cartMapper, never()).add(any(Cart.class));
        assertEquals(0, rowCount());
    }

    @Test
    void offSaleDishUsesExactDocumentedMessageAndDoesNotWrite() throws Exception {
        mockMvc.perform(post("/app/cart").header("Authorization", userToken(1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dishId\":102,\"quantity\":1}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("该菜品已下架"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        verify(cartMapper, never()).add(any(Cart.class));
        assertEquals(0, rowCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"dishId\":101}", "{\"quantity\":1}",
            "{\"dishId\":null,\"quantity\":1}", "{\"dishId\":101,\"quantity\":null}",
            "{\"dishId\":0,\"quantity\":1}", "{\"dishId\":-1,\"quantity\":1}",
            "{\"dishId\":101,\"quantity\":0}", "{\"dishId\":101,\"quantity\":-1}",
            "{\"dishId\":\"abc\",\"quantity\":1}", "{\"dishId\":101,\"quantity\":\"abc\"}",
            "{\"dishId\":101,\"quantity\":2147483648}"
    })
    void invalidJsonFieldsAreRejectedBeforeCartWrite(String body) throws Exception {
        mockMvc.perform(post("/app/cart").header("Authorization", userToken(1))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(1));
        verify(cartMapper, never()).add(any(Cart.class));
        assertEquals(0, rowCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{bad-json"})
    void missingOrMalformedBodyUsesExistingGlobalErrorHandler(String body) throws Exception {
        mockMvc.perform(post("/app/cart").header("Authorization", userToken(1))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请求参数格式错误，请检查请求体是否为合法 JSON"));
        assertEquals(0, rowCount());
    }

    @ParameterizedTest
    @MethodSource("invalidCarts")
    void serviceAlsoValidatesParametersForDirectCalls(Cart cart) {
        assertThrows(BizException.class, () -> asUser(1, () -> cartService.add(cart)));
        verify(cartMapper, never()).add(any(Cart.class));
        assertEquals(0, rowCount());
    }

    static Stream<Cart> invalidCarts() {
        return Stream.of(null, requestCart(null, 1), requestCart(0L, 1), requestCart(-1L, 1),
                requestCart(101L, null), requestCart(101L, 0), requestCart(101L, -1));
    }

    @Test
    void missingOrWrongIdentityCannotAddCart() throws Exception {
        String body = "{\"dishId\":101,\"quantity\":1}";
        mockMvc.perform(post("/app/cart").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        String admin = cacheToken(Map.of("id", 1, "type", "admin", "role", 1));
        mockMvc.perform(post("/app/cart").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertThrows(BizException.class, () -> cartService.add(requestCart(101L, 1)));
        verify(cartMapper, never()).add(any(Cart.class));
        assertEquals(0, rowCount());
    }

    @Test
    void revokedTokenCannotAddCart() throws Exception {
        String token = userToken(1);
        redis.values.remove(token);
        mockMvc.perform(post("/app/cart").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dishId\":101,\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
        assertEquals(0, rowCount());
    }

    @Test
    void quantityOverflowIsRejectedAndExistingRowIsUnchanged() throws Exception {
        addRequest(1, "{\"dishId\":101,\"quantity\":2147483647}");
        Map<String, Object> before = row(1, 101);
        mockMvc.perform(post("/app/cart").header("Authorization", userToken(1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dishId\":101,\"quantity\":1}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("加入购物车失败，请检查数量"));
        assertEquals(before, row(1, 101));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3})
    void abnormalAffectedRowsRollBackTheInsert(int affectedRows) {
        doAnswer(call -> {
            sqlSessionTemplate.insert("com.wys.orderingserver.mapper.CartMapper.add", call.getArgument(0));
            return affectedRows;
        }).when(cartMapper).add(any(Cart.class));
        assertThrows(BizException.class, () -> asUser(1, () -> cartService.add(requestCart(101L, 1))));
        assertEquals(0, rowCount());
    }

    @Test
    void failedAccumulationRollsBackQuantityAndTimestamp() throws Exception {
        addRequest(1, "{\"dishId\":101,\"quantity\":2}");
        Map<String, Object> before = row(1, 101);
        doAnswer(call -> {
            sqlSessionTemplate.insert("com.wys.orderingserver.mapper.CartMapper.add", call.getArgument(0));
            return 0;
        }).when(cartMapper).add(any(Cart.class));
        assertThrows(BizException.class, () -> asUser(1, () -> cartService.add(requestCart(101L, 3))));
        assertEquals(before, row(1, 101));
    }

    @Test
    void upsertItselfReturnsInsertOrUpdateCountAndPreservesCreationTime() {
        Cart cart = databaseCart(2);
        cart.setCreateTime(LocalDateTime.of(2000, 1, 1, 0, 0));
        cart.setUpdateTime(cart.getCreateTime());
        assertEquals(1, cartMapper.add(cart));
        Map<String, Object> before = row(1, 101);
        cart.setQuantity(3);
        cart.setCreateTime(LocalDateTime.of(2020, 1, 1, 0, 0));
        cart.setUpdateTime(cart.getCreateTime());
        assertEquals(2, cartMapper.add(cart));
        assertEquals(5, quantity(1, 101));
        assertEquals(before.get("ID"), row(1, 101).get("ID"));
        assertEquals(before.get("CREATE_TIME"), row(1, 101).get("CREATE_TIME"));
        assertEquals(LocalDateTime.of(2020, 1, 1, 0, 0), jdbcTemplate.queryForObject("select update_time from t_cart", LocalDateTime.class));
    }

    @Test
    void concurrentUpsertWithoutServiceLocksStillUsesOneRowAndLosesNoIncrements() throws Exception {
        runTogether(6, () -> cartMapper.add(databaseCart(1)));
        assertEquals(1, rowCount());
        assertEquals(6, quantity(1, 101));
    }

    @Test
    void concurrentServiceCallsKeepExactQuantity() throws Exception {
        runTogether(6, () -> asUser(1, () -> cartService.add(requestCart(101L, 1))));
        assertEquals(1, rowCount());
        assertEquals(6, quantity(1, 101));
    }

    @ParameterizedTest
    @ValueSource(strings = {"off-sale", "delete"})
    void stateIsRecheckedAfterWaitingForAdminTransaction(String action) throws Exception {
        CountDownLatch attemptingStoreLock = new CountDownLatch(1);
        AtomicReference<Future<BizException>> pending = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("select id from t_store where id = 1 for update", Long.class);
                if (action.equals("off-sale")) jdbcTemplate.update("update t_dish set status = 0 where id = 101");
                else jdbcTemplate.update("delete from t_dish where id = 101");
                doAnswer(call -> {
                    attemptingStoreLock.countDown();
                    return sqlSessionTemplate.selectOne("com.wys.orderingserver.mapper.StoreMapper.findByIdForUpdate", 1L);
                }).when(storeMapper).findByIdForUpdate(1L);
                pending.set(executor.submit(() -> assertThrows(BizException.class,
                        () -> asUser(1, () -> cartService.add(requestCart(101L, 1))))));
                try {
                    assertTrue(attemptingStoreLock.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                // 离开回调后提交，等待中的加购才能取得门店锁并读取最新菜品状态。
            });
            assertEquals(action.equals("off-sale") ? "该菜品已下架" : "菜品不存在",
                    pending.get().get(5, TimeUnit.SECONDS).getMessage());
            assertEquals(0, rowCount());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void storeRemovedBeforeLockAcquisitionIsRejected() {
        doReturn(null).when(storeMapper).findByIdForUpdate(1L);
        BizException error = assertThrows(BizException.class,
                () -> asUser(1, () -> cartService.add(requestCart(101L, 1))));
        assertEquals("所属门店不存在", error.getMessage());
        assertEquals(0, rowCount());
    }

    private void runTogether(int count, Runnable action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    try { assertTrue(start.await(3, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                    action.run();
                    assertNull(ThreadLocalUtil.get());
                }));
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> result : results) result.get(8, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void addRequest(long userId, String body) throws Exception {
        mockMvc.perform(post("/app/cart").header("Authorization", userToken(userId))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    private static Cart requestCart(Long dishId, Integer quantity) {
        Cart cart = new Cart();
        cart.setDishId(dishId);
        cart.setQuantity(quantity);
        return cart;
    }

    private Cart databaseCart(int quantity) {
        Cart cart = requestCart(101L, quantity);
        cart.setUserId(1L); cart.setStoreId(1L);
        cart.setCreateTime(LocalDateTime.now()); cart.setUpdateTime(cart.getCreateTime());
        return cart;
    }

    private void asUser(long userId, Runnable action) {
        ThreadLocalUtil.set(Map.of("id", userId, "type", "user"));
        try { action.run(); } finally { ThreadLocalUtil.remove(); }
    }

    private int quantity(long userId, long dishId) {
        return jdbcTemplate.queryForObject("select quantity from t_cart where user_id = ? and dish_id = ?",
                Integer.class, userId, dishId);
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("select count(*) from t_cart", Integer.class);
    }

    private Map<String, Object> row(long userId, long dishId) {
        return jdbcTemplate.queryForMap("select * from t_cart where user_id = ? and dish_id = ?", userId, dishId);
    }

    private String userToken(long userId) {
        return cacheToken(Map.of("id", userId, "phone", userId == 1 ? "13800000001" : "13900000002", "type", "user"));
    }

    private String cacheToken(Map<String, Object> claims) {
        String token = JwtUtil.genToken(claims);
        redis.values.put(token, token);
        return token;
    }
}
