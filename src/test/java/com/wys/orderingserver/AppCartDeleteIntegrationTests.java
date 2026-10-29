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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 H2 执行真实删除 SQL，验证购物车主键、用户归属、重试及并发行为。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:app-cart-delete-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql,classpath:dish-add-test.sql,classpath:user-login-test.sql,classpath:cart-add-test.sql"
})
@AutoConfigureMockMvc
@Sql({"/app-dish-list-data.sql", "/app-cart-list-data.sql"})
class AppCartDeleteIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CartService cartService;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private CartMapper cartMapper;
    @MockitoBean private StringRedisTemplate redisTemplate;
    private DishRedisStub redis;

    @BeforeEach
    void prepareRedis() {
        ThreadLocalUtil.remove();
        redis = new DishRedisStub(redisTemplate);
    }

    @AfterEach
    void contextIsCleared() {
        assertNull(ThreadLocalUtil.get());
    }

    @Test
    void pathCartIdDeletesOnlyOwnedItemAndKeepsDishAndAllOtherRows() throws Exception {
        List<Map<String, Object>> otherCarts = jdbcTemplate.queryForList("select * from t_cart where id <> 1001 order by id");
        List<Map<String, Object>> dishes = jdbcTemplate.queryForList("select * from t_dish order by id");
        deleteRequest(1, 1001L);
        assertEquals(0, countId(1001));
        assertEquals(otherCarts, allRows());
        assertEquals(dishes, jdbcTemplate.queryForList("select * from t_dish order by id"));
        verify(cartMapper).deleteByIdAndUserId(1001L, 1L);
        verify(cartMapper, never()).deleteByDishIdAndStoreId(anyLong(), anyLong());
        mockMvc.perform(get("/app/cart/list").header("Authorization", userToken(1)))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(1003));
    }

    @Test
    void secondUserCanDeleteOwnItemWithoutDeletingFirstUsersSameDish() throws Exception {
        deleteRequest(2, 1002L);
        assertEquals(0, countId(1002));
        assertEquals(2, quantity(1, 101));
        assertEquals(3, allRows().size());
    }

    @Test
    void otherUsersItemIsNotDeletedAndDoesNotDiscloseItsExistence() throws Exception {
        List<Map<String, Object>> before = allRows();
        deleteRequest(1, 1002L);
        assertEquals(before, allRows());
    }

    @Test
    void absentItemReturnsSuccessfulNoOp() throws Exception {
        List<Map<String, Object>> before = allRows();
        deleteRequest(1, 9999L);
        assertEquals(before, allRows());
    }

    @Test
    void repeatedDeleteRemainsSuccessful() throws Exception {
        deleteRequest(1, 1001L);
        List<Map<String, Object>> once = allRows();
        deleteRequest(1, 1001L);
        assertEquals(once, allRows());
        verify(cartMapper, times(2)).deleteByIdAndUserId(1001L, 1L);
    }

    @Test
    void offSaleItemCanBeRemovedWithoutDeletingTheDish() throws Exception {
        deleteRequest(1, 1004L);
        assertEquals(0, countId(1004));
        assertEquals(0, jdbcTemplate.queryForObject("select status from t_dish where id = 102", Integer.class));
    }

    @Test
    void anotherStoresOwnedItemCanBeRemovedWithoutStoreParameter() throws Exception {
        deleteRequest(1, 1003L);
        assertEquals(0, countId(1003));
        assertEquals(2, quantity(1, 101));
        assertEquals(1, jdbcTemplate.queryForObject("select count(*) from t_dish where id = 201", Integer.class));
    }

    @Test
    void queryParametersCannotReplacePathIdOrCurrentUser() throws Exception {
        mockMvc.perform(delete("/app/cart/1001").header("Authorization", userToken(1))
                        .param("id", "1002").param("userId", "2").param("dishId", "201").param("storeId", "2"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0, countId(1001));
        assertEquals(1, countId(1002));
        assertEquals(1, countId(1003));
    }

    @Test
    void dishIdIsNotMistakenForCartPrimaryKey() throws Exception {
        List<Map<String, Object>> before = allRows();
        deleteRequest(1, 101L);
        assertEquals(before, allRows());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositivePathIdIsRejectedBeforeDelete(long id) throws Exception {
        mockMvc.perform(delete("/app/cart/" + id).header("Authorization", userToken(1)))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请选择有效的购物车项ID"));
        verifyNoInteractions(cartMapper);
        assertEquals(4, allRows().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "9223372036854775808"})
    void malformedPathIdUsesExistingGlobalTypeError(String id) throws Exception {
        mockMvc.perform(delete("/app/cart/" + id).header("Authorization", userToken(1)))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：id"));
        verifyNoInteractions(cartMapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void directServiceCallsAlsoValidateId(Long id) {
        assertThrows(BizException.class, () -> asUser(1, () -> cartService.delete(id)));
        verifyNoInteractions(cartMapper);
    }

    @Test
    void missingAdminAndRevokedTokensCannotDeleteCart() throws Exception {
        mockMvc.perform(delete("/app/cart/1001"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        String admin = cacheToken(Map.of("id", 1L, "role", 1, "type", "admin"));
        mockMvc.perform(delete("/app/cart/1001").header("Authorization", admin))
                .andExpect(status().isUnauthorized());
        String revoked = userToken(1);
        redis.values.remove(revoked);
        mockMvc.perform(delete("/app/cart/1001").header("Authorization", revoked))
                .andExpect(status().isUnauthorized());
        assertThrows(BizException.class, () -> cartService.delete(1001L));
        verifyNoInteractions(cartMapper);
        assertEquals(4, allRows().size());
    }

    @Test
    void longCartIdLargerThanIntegerRangeIsSupported() throws Exception {
        jdbcTemplate.update("insert into t_cart(id,user_id,store_id,dish_id,quantity,create_time,update_time) " +
                "values (3000000001,1,1,103,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        deleteRequest(1, 3000000001L);
        assertEquals(0, countId(3000000001L));
        assertEquals(4, allRows().size());
    }

    @Test
    void abnormalAffectedRowCountRollsBackActualDeletion() {
        List<Map<String, Object>> before = allRows();
        doAnswer(call -> {
            sqlSessionTemplate.delete("com.wys.orderingserver.mapper.CartMapper.deleteByIdAndUserId",
                    Map.of("id", call.getArgument(0), "userId", call.getArgument(1)));
            return 2;
        }).when(cartMapper).deleteByIdAndUserId(1001L, 1L);
        BizException error = assertThrows(BizException.class, () -> asUser(1, () -> cartService.delete(1001L)));
        assertEquals("删除购物车项失败，请重试", error.getMessage());
        assertEquals(before, allRows());
    }

    @Test
    void sqlItselfRequiresUserOwnershipWithoutServiceChecks() {
        List<Map<String, Object>> before = allRows();
        assertEquals(0, cartMapper.deleteByIdAndUserId(1001L, 2L));
        assertEquals(before, allRows());
        assertEquals(1, cartMapper.deleteByIdAndUserId(1001L, 1L));
        assertEquals(1, countId(1002));
    }

    @Test
    void concurrentDeletesAllSucceedAndKeepOtherItems() throws Exception {
        List<Map<String, Object>> others = jdbcTemplate.queryForList("select * from t_cart where id <> 1001 order by id");
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> pending = new ArrayList<>();
        try {
            for (int index = 0; index < 6; index++) {
                pending.add(executor.submit(() -> {
                    ready.countDown(); await(start);
                    asUser(1, () -> cartService.delete(1001L));
                    assertNull(ThreadLocalUtil.get());
                }));
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> result : pending) result.get(8, TimeUnit.SECONDS);
            assertEquals(others, allRows());
        } finally {
            start.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"update-first", "delete-first"})
    void deleteAndQuantityUpdateCoordinateOnTheSameCartRow(String order) throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicReference<Future<?>> pending = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                if (order.equals("update-first")) {
                    asUser(1, () -> cartService.update(requestCart(5)));
                    interceptDelete(attempted);
                    pending.set(executor.submit(() -> asUser(1, () -> cartService.delete(1001L))));
                } else {
                    asUser(1, () -> cartService.delete(1001L));
                    doAnswer(call -> {
                        attempted.countDown();
                        return sqlSessionTemplate.selectOne("com.wys.orderingserver.mapper.CartMapper.findByUserIdAndDishIdForUpdate",
                                Map.of("userId", call.getArgument(0), "dishId", call.getArgument(1)));
                    }).when(cartMapper).findByUserIdAndDishIdForUpdate(1L, 101L);
                    pending.set(executor.submit(() -> {
                        BizException error = assertThrows(BizException.class,
                                () -> asUser(1, () -> cartService.update(requestCart(5))));
                        assertEquals("购物车中没有该菜品", error.getMessage());
                    }));
                }
                await(attempted);
            });
            pending.get().get(6, TimeUnit.SECONDS);
            assertEquals(0, countId(1001));
            assertEquals(7, quantity(2, 101));
            assertEquals(3, allRows().size());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"add-first", "delete-first"})
    void deleteAndAddHaveWellDefinedOrderAndOldIdCannotDeleteNewItem(String order) throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicReference<Future<?>> pending = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                if (order.equals("add-first")) {
                    asUser(1, () -> cartService.add(requestCart(2)));
                    interceptDelete(attempted);
                    pending.set(executor.submit(() -> asUser(1, () -> cartService.delete(1001L))));
                } else {
                    asUser(1, () -> cartService.delete(1001L));
                    doAnswer(call -> {
                        attempted.countDown();
                        return sqlSessionTemplate.insert("com.wys.orderingserver.mapper.CartMapper.add", call.getArgument(0));
                    }).when(cartMapper).add(any(Cart.class));
                    pending.set(executor.submit(() -> asUser(1, () -> cartService.add(requestCart(2)))));
                }
                await(attempted);
            });
            pending.get().get(6, TimeUnit.SECONDS);
            assertEquals(0, countId(1001));
            if (order.equals("delete-first")) {
                assertEquals(2, quantity(1, 101));
                asUser(1, () -> cartService.delete(1001L));
                assertEquals(2, quantity(1, 101));
            } else {
                assertEquals(0, jdbcTemplate.queryForObject("select count(*) from t_cart where user_id = 1 and dish_id = 101", Integer.class));
            }
            assertEquals(7, quantity(2, 101));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void interceptDelete(CountDownLatch attempted) {
        doAnswer(call -> {
            attempted.countDown();
            return sqlSessionTemplate.delete("com.wys.orderingserver.mapper.CartMapper.deleteByIdAndUserId",
                    Map.of("id", call.getArgument(0), "userId", call.getArgument(1)));
        }).when(cartMapper).deleteByIdAndUserId(1001L, 1L);
    }

    private void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }

    private void deleteRequest(int userId, Long id) throws Exception {
        mockMvc.perform(delete("/app/cart/" + id).header("Authorization", userToken(userId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    private Cart requestCart(int quantity) {
        Cart cart = new Cart(); cart.setDishId(101L); cart.setQuantity(quantity); return cart;
    }

    private void asUser(long userId, Runnable action) {
        ThreadLocalUtil.set(Map.of("id", userId, "type", "user"));
        try { action.run(); } finally { ThreadLocalUtil.remove(); }
    }

    private int countId(long id) {
        return jdbcTemplate.queryForObject("select count(*) from t_cart where id = ?", Integer.class, id);
    }

    private int quantity(long userId, long dishId) {
        return jdbcTemplate.queryForObject("select quantity from t_cart where user_id = ? and dish_id = ?",
                Integer.class, userId, dishId);
    }

    private List<Map<String, Object>> allRows() {
        return jdbcTemplate.queryForList("select * from t_cart order by id");
    }

    private String userToken(int userId) {
        return cacheToken(Map.of("id", (long) userId, "phone", userId == 1 ? "13800000001" : "13900000002", "type", "user"));
    }

    private String cacheToken(Map<String, Object> claims) {
        String token = JwtUtil.genToken(claims); redis.values.put(token, token); return token;
    }
}
