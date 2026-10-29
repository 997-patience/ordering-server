package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.CartMapper;
import com.wys.orderingserver.mapper.OrderDetailMapper;
import com.wys.orderingserver.mapper.OrderMapper;
import com.wys.orderingserver.pojo.Cart;
import com.wys.orderingserver.pojo.Dish;
import com.wys.orderingserver.pojo.Order;
import com.wys.orderingserver.pojo.OrderDetail;
import com.wys.orderingserver.pojo.OrderSubmitDTO;
import com.wys.orderingserver.pojo.OrderSubmitVO;
import com.wys.orderingserver.service.AppOrderService;
import com.wys.orderingserver.service.CartService;
import com.wys.orderingserver.service.DishService;
import com.wys.orderingserver.service.OrderService;
import com.wys.orderingserver.support.DishRedisStub;
import com.wys.orderingserver.task.OrderTimeoutTask;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真正经过 Controller、事务代理、MyBatis 和 H2 SQL，Redis 仅模拟登录状态。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:app-order-submit-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql,classpath:dish-add-test.sql,classpath:user-login-test.sql,classpath:order-submit-test.sql"
})
@AutoConfigureMockMvc
@Sql({"/app-dish-list-data.sql", "/app-cart-list-data.sql"})
class AppOrderSubmitIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AppOrderService appOrderService;
    @Autowired private CartService cartService;
    @Autowired private DishService dishService;
    @Autowired private OrderService orderService;
    @Autowired private OrderTimeoutTask timeoutTask;
    @Autowired private SqlSessionTemplate sqlSession;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private OrderMapper orderMapper;
    @MockitoSpyBean private OrderDetailMapper detailMapper;
    @MockitoSpyBean private CartMapper cartMapper;
    @MockitoBean private StringRedisTemplate redisTemplate;
    private DishRedisStub redis;
    private static final String NS = "com.wys.orderingserver.mapper.";

    @BeforeEach
    void prepare() {
        ThreadLocalUtil.remove();
        redis = new DishRedisStub(redisTemplate);
        jdbc.update("delete from t_cart where id = 1004"); // 单独的测试再加入下架菜品。
        jdbc.update("insert into t_cart values(1005,1,1,103,3,'2026-10-11 10:00:00','2026-10-11 10:00:00')");
    }

    @AfterEach
    void threadLocalIsCleared() {
        assertNull(ThreadLocalUtil.get());
    }

    @Test
    void submitsSnapshotsAndClearsOnlyCurrentUsersSelectedStore() throws Exception {
        mockMvc.perform(post("/app/order/submit").header("Authorization", token(1))
                        .contentType("application/json")
                        .content("{\"storeId\":1,\"pickupType\":2,\"remark\":\"不要香菜\",\"pickupTime\":\"2026-10-25 18:30:00\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.orderNo").isString())
                .andExpect(jsonPath("$.data.amount").value(124.00))
                .andExpect(jsonPath("$.data.userId").doesNotExist());
        Map<String, Object> order = jdbc.queryForMap("select * from t_orders");
        long id = ((Number) order.get("id")).longValue();
        assertEquals(1L, ((Number) order.get("user_id")).longValue());
        assertEquals(1L, ((Number) order.get("store_id")).longValue());
        assertEquals("不要香菜", order.get("remark"));
        assertEquals(2, ((Number) order.get("pickup_type")).intValue());
        assertEquals(LocalDateTime.of(2026, 10, 25, 18, 30), jdbc.queryForObject(
                "select pickup_time from t_orders", LocalDateTime.class));
        assertEquals(1, orderStatus(id));
        assertEquals(0, ((Number) order.get("pay_status")).intValue());
        assertNull(order.get("pay_time"));
        assertNull(order.get("finish_time"));
        assertNull(order.get("cancel_reason"));
        assertEquals(32, order.get("order_no").toString().length());
        assertEquals(2, count("t_order_detail"));
        assertEquals(2, count("t_cart")); // 用户 2 的本店购物车、用户 1 的其他店购物车都保留。
        assertEquals(0, jdbc.queryForObject("select count(*) from t_cart where user_id=1 and store_id=1", Integer.class));
        assertEquals(7, jdbc.queryForObject("select quantity from t_cart where id=1002", Integer.class));
        assertEquals(1, jdbc.queryForObject("select quantity from t_cart where id=1003", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(distinct order_id) from t_order_detail where order_id=?", Integer.class, id));
        assertEquals(new BigDecimal("76.00"), jdbc.queryForObject("select amount from t_order_detail where dish_id=101", BigDecimal.class));
        assertEquals("宫保鸡丁", jdbc.queryForObject("select dish_name from t_order_detail where dish_id=101", String.class));
        assertEquals("/upload/chicken.jpg", jdbc.queryForObject("select dish_image from t_order_detail where dish_id=101", String.class));
    }

    @Test
    void requestCannotForgeIdentityPriceStatusIdsOrTimestamps() throws Exception {
        mockMvc.perform(post("/app/order/submit").header("Authorization", token(1))
                        .contentType("application/json")
                        .content("{\"storeId\":1,\"pickupType\":1,\"userId\":2,\"amount\":0.01,\"id\":999,\"orderNo\":\"FORGED\",\"status\":3,\"payStatus\":1,\"createTime\":\"2000-01-01 00:00:00\"}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.amount").value(124));
        Order order = onlyOrder();
        assertEquals(1L, order.getUserId());
        assertNotEquals(999L, order.getId());
        assertNotEquals("FORGED", order.getOrderNo());
        assertEquals(1, order.getStatus());
        assertEquals(0, order.getPayStatus());
        assertTrue(order.getCreateTime().getYear() >= 2026);
    }

    @Test
    void liveDatabasePriceIsUsedEvenIfRedisContainsStaleMenu() {
        redis.values.put("dish:1:all", "[{\"id\":101,\"price\":0.01}]");
        jdbc.update("update t_dish set price=39.99 where id=101");
        OrderSubmitVO result = submit(1, 1);
        assertEquals(new BigDecimal("127.98"), result.getAmount());
        assertEquals(new BigDecimal("39.99"), jdbc.queryForObject("select dish_price from t_order_detail where dish_id=101", BigDecimal.class));
        verifyNoInteractions(redisTemplate); // Service 直接调用，不需要 Redis 参与结算。
    }

    @Test
    void snapshotsSurviveLaterRenameRepriceDownsaleAndDeletion() {
        submit(1, 1);
        List<Map<String, Object>> snapshots = jdbc.queryForList("select * from t_order_detail order by id");
        jdbc.update("update t_dish set name='改名',price=99.99,image='/new.jpg',status=0 where id=101");
        jdbc.update("delete from t_dish where id=103"); // 该菜品的购物车已清空，快照不依赖菜品外键。
        assertEquals(snapshots, jdbc.queryForList("select * from t_order_detail order by id"));
        assertEquals(new BigDecimal("124.00"), onlyOrder().getAmount());
    }

    @Test
    void decimalCalculationDoesNotIntroduceFloatingPointError() {
        jdbc.update("update t_dish set price=0.10 where id=101");
        jdbc.update("update t_dish set price=0.20 where id=103");
        assertEquals(new BigDecimal("0.80"), submit(1, 1).getAmount());
    }

    @Test
    void secondUserAndAnotherStoreHaveIndependentOrders() {
        OrderSubmitVO secondUser = submit(2, 1);
        OrderSubmitVO anotherStore = submit(1, 2);
        assertEquals(new BigDecimal("266.00"), secondUser.getAmount());
        assertEquals(new BigDecimal("45.00"), anotherStore.getAmount());
        assertNotEquals(secondUser.getOrderNo(), anotherStore.getOrderNo());
        assertEquals(2, count("t_orders"));
        assertEquals(2, count("t_cart"));
    }

    @Test
    void emptyCartMatchesDocumentFailureAndDoesNotTouchOtherUsersCarts() throws Exception {
        mockMvc.perform(post("/app/order/submit").header("Authorization", token(3))
                        .contentType("application/json").content("{\"storeId\":1,\"pickupType\":1}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("购物车为空，无法下单"))
                .andExpect(jsonPath("$.data").isEmpty());
        assertEquals(0, count("t_orders"));
        assertEquals(4, count("t_cart"));
    }

    @Test
    void repeatedSubmitCannotChargeTheSameCartTwice() {
        submit(1, 1);
        BizException error = assertThrows(BizException.class, () -> submit(1, 1));
        assertEquals("购物车为空，无法下单", error.getMessage());
        assertEquals(1, count("t_orders"));
        assertEquals(2, count("t_order_detail"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"storeId\":null,\"pickupType\":1}", "{\"storeId\":0,\"pickupType\":1}",
            "{\"storeId\":-1,\"pickupType\":1}", "{\"storeId\":1}", "{\"storeId\":1,\"pickupType\":null}",
            "{\"storeId\":1,\"pickupType\":0}", "{\"storeId\":1,\"pickupType\":3}",
            "{\"storeId\":1,\"pickupType\":\"abc\"}", "{\"storeId\":1,\"pickupType\":1,\"pickupTime\":\"bad\"}"
    })
    void invalidHttpRequestIsRejectedBeforeBusinessWrites(String body) throws Exception {
        mockMvc.perform(post("/app/order/submit").header("Authorization", token(1))
                        .contentType("application/json").content(body))
                .andExpect(jsonPath("$.code").value(1));
        verify(orderMapper, never()).insert(any());
        assertEquals(4, count("t_cart"));
    }

    @Test
    void oversizedRemarkIsRejected() throws Exception {
        mockMvc.perform(post("/app/order/submit").header("Authorization", token(1))
                        .contentType("application/json").content("{\"storeId\":1,\"pickupType\":1,\"remark\":\"" + "长".repeat(256) + "\"}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("订单备注不能超过255个字符"));
        assertEquals(0, count("t_orders"));
    }

    @Test
    void serviceAlsoValidatesDirectCalls() {
        assertThrows(BizException.class, () -> asUser(1, () -> appOrderService.submit(null)));
        OrderSubmitDTO dto = request(1);
        dto.setPickupType(3);
        assertThrows(BizException.class, () -> asUser(1, () -> appOrderService.submit(dto)));
        dto.setPickupType(1);
        dto.setRemark("长".repeat(256));
        assertThrows(BizException.class, () -> asUser(1, () -> appOrderService.submit(dto)));
        assertEquals(0, count("t_orders"));
    }

    @Test
    void unauthenticatedAndAdminRequestsCannotSubmitCustomerOrders() throws Exception {
        mockMvc.perform(post("/app/order/submit").contentType("application/json")
                        .content("{\"storeId\":1,\"pickupType\":1}"))
                .andExpect(status().isUnauthorized());
        String adminToken = JwtUtil.genToken(Map.of("id", 2, "role", 2, "storeId", 1, "type", "admin"));
        redis.values.put(adminToken, adminToken);
        mockMvc.perform(post("/app/order/submit").header("Authorization", adminToken)
                        .contentType("application/json").content("{\"storeId\":1,\"pickupType\":1}"))
                .andExpect(status().isUnauthorized());
        assertEquals(0, count("t_orders"));
    }

    @Test
    void missingAndClosedStoresAreRejectedWithoutCartLoss() {
        assertEquals("门店不存在", assertThrows(BizException.class, () -> submit(1, 999)).getMessage());
        jdbc.update("update t_store set status=0 where id=1");
        assertEquals("门店已打烊，暂时无法下单", assertThrows(BizException.class, () -> submit(1, 1)).getMessage());
        assertEquals(0, count("t_orders"));
        assertEquals(4, count("t_cart"));
    }

    @Test
    void offSaleItemRejectsWholeOrderAndPreservesCart() {
        jdbc.update("update t_dish set status=0 where id=103");
        assertEquals("购物车中存在已下架菜品，请先移除后再下单",
                assertThrows(BizException.class, () -> submit(1, 1)).getMessage());
        assertEquals(0, count("t_orders"));
        assertEquals(0, count("t_order_detail"));
        assertEquals(4, count("t_cart"));
    }

    @Test
    void corruptedCartStoreIsNotSilentlyDroppedByAJoin() {
        jdbc.update("update t_cart set store_id=2 where id=1005");
        assertEquals("购物车中的菜品不存在或不属于当前门店",
                assertThrows(BizException.class, () -> submit(1, 2)).getMessage());
        assertEquals(0, count("t_orders"));
        assertEquals(4, count("t_cart"));
    }

    @Test
    void quantityAndDatabaseAmountRangeAreValidatedBeforeSaving() {
        jdbc.update("update t_cart set quantity=0 where id=1001");
        assertThrows(BizException.class, () -> submit(1, 1));
        jdbc.update("update t_cart set quantity=2147483647 where id=1001");
        assertEquals("订单金额超出允许范围，请减少购买数量", assertThrows(BizException.class, () -> submit(1, 1)).getMessage());
        assertEquals(0, count("t_orders"));
        assertEquals(4, count("t_cart"));
    }

    @Test
    void invalidPriceIsRejected() {
        jdbc.update("update t_dish set price=0 where id=101");
        assertEquals("菜品价格异常，暂时无法下单", assertThrows(BizException.class, () -> submit(1, 1)).getMessage());
        assertEquals(0, count("t_orders"));
    }

    @Test
    void masterInsertAnomalyRollsBackEvenAfterInsertReallyHappened() {
        doAnswer(call -> {
            sqlSession.insert(NS + "OrderMapper.insert", call.getArgument(0));
            return 0;
        }).when(orderMapper).insert(any(Order.class));
        assertThrows(BizException.class, () -> submit(1, 1));
        assertAllRolledBack();
    }

    @Test
    void secondDetailInsertFailureRollsBackMasterAndFirstDetail() {
        doAnswer(call -> {
            OrderDetail detail = call.getArgument(0);
            int rows = sqlSession.insert(NS + "OrderDetailMapper.insert", detail);
            return detail.getDishId().equals(103L) ? 0 : rows;
        }).when(detailMapper).insert(any(OrderDetail.class));
        assertThrows(BizException.class, () -> submit(1, 1));
        assertAllRolledBack();
    }

    @Test
    void actualDatabaseExceptionAlsoRollsBackMasterAndDetails() {
        doThrow(new DataIntegrityViolationException("模拟明细写入失败"))
                .when(detailMapper).insert(any(OrderDetail.class));
        assertThrows(DataIntegrityViolationException.class, () -> submit(1, 1));
        assertAllRolledBack();
    }

    @Test
    void cartDeleteAnomalyRestoresDeletedRowsAndRollsBackAllOrderWrites() {
        doAnswer(call -> {
            sqlSession.delete(NS + "CartMapper.deleteByUserIdAndStoreId", Map.of("userId", 1L, "storeId", 1L));
            return 0;
        }).when(cartMapper).deleteByUserIdAndStoreId(1L, 1L);
        assertThrows(BizException.class, () -> submit(1, 1));
        assertAllRolledBack();
    }

    @Test
    void concurrentSubmissionsOfSameCartCreateOnlyOneOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    await(start);
                    try { return submit(1, 1); }
                    catch (BizException e) { return e.getMessage(); }
                }));
            }
            start.countDown();
            List<Object> results = List.of(futures.get(0).get(5, TimeUnit.SECONDS), futures.get(1).get(5, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(OrderSubmitVO.class::isInstance).count());
            assertTrue(results.contains("购物车为空，无法下单"));
            assertEquals(1, count("t_orders"));
            assertEquals(2, count("t_order_detail"));
        } finally { stop(pool); }
    }

    @Test
    void addDuringCheckoutWaitsAndRemainsInANewCartAfterCheckout() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch addEntered = new CountDownLatch(1);
        pauseAfterCartLock(locked, release);
        try {
            Future<OrderSubmitVO> submit = pool.submit(() -> submit(1, 1));
            assertTrue(locked.await(3, TimeUnit.SECONDS));
            Future<?> add = pool.submit(() -> asUser(1, () -> {
                addEntered.countDown();
                Cart cart = new Cart(); cart.setDishId(101L); cart.setQuantity(1);
                cartService.add(cart); return null;
            }));
            assertTrue(addEntered.await(3, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> add.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(new BigDecimal("124.00"), submit.get(5, TimeUnit.SECONDS).getAmount());
            add.get(5, TimeUnit.SECONDS);
            assertEquals(1, jdbc.queryForObject("select quantity from t_cart where user_id=1 and dish_id=101", Integer.class));
            assertEquals(2, jdbc.queryForObject("select quantity from t_order_detail where dish_id=101", Integer.class));
        } finally { release.countDown(); stop(pool); }
    }

    @Test
    void quantityUpdateDuringCheckoutWaitsAndCannotChangeTheOrderedSnapshot() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pauseAfterCartLock(locked, release);
        try {
            Future<OrderSubmitVO> submit = pool.submit(() -> submit(1, 1));
            assertTrue(locked.await(3, TimeUnit.SECONDS));
            Future<?> update = pool.submit(() -> asUser(1, () -> {
                Cart cart = new Cart(); cart.setDishId(101L); cart.setQuantity(9);
                cartService.update(cart); return null;
            }));
            assertThrows(TimeoutException.class, () -> update.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            submit.get(5, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(ExecutionException.class, () -> update.get(5, TimeUnit.SECONDS));
            assertInstanceOf(BizException.class, error.getCause());
            assertEquals("购物车中没有该菜品", error.getCause().getMessage());
            assertEquals(2, jdbc.queryForObject("select quantity from t_order_detail where dish_id=101", Integer.class));
        } finally { release.countDown(); stop(pool); }
    }

    @Test
    void quantityUpdateThatCommittedBeforeCheckoutLockIsIncludedInAmount() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> update = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbc.update("update t_cart set quantity=9 where id=1001");
                changed.countDown(); await(release);
            }));
            assertTrue(changed.await(3, TimeUnit.SECONDS));
            Future<OrderSubmitVO> checkout = pool.submit(() -> submit(1, 1));
            assertThrows(TimeoutException.class, () -> checkout.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            update.get(5, TimeUnit.SECONDS);
            assertEquals(new BigDecimal("390.00"), checkout.get(5, TimeUnit.SECONDS).getAmount());
            assertEquals(9, jdbc.queryForObject("select quantity from t_order_detail where dish_id=101", Integer.class));
        } finally { release.countDown(); stop(pool); }
    }

    @Test
    void managementMenuEditWaitsForCheckoutAndCannotChangeItsSnapshotPrice() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pauseAfterCartLock(locked, release);
        try {
            Future<OrderSubmitVO> checkout = pool.submit(() -> submit(1, 1));
            assertTrue(locked.await(3, TimeUnit.SECONDS));
            Future<?> edit = pool.submit(() -> {
                ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1, "type", "admin"));
                try {
                    Dish dish = new Dish(); dish.setId(101L); dish.setCategoryId(11L);
                    dish.setName("新菜名"); dish.setPrice(new BigDecimal("99.99"));
                    dishService.updateDish(dish);
                } finally { ThreadLocalUtil.remove(); }
            });
            assertThrows(TimeoutException.class, () -> edit.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(new BigDecimal("124.00"), checkout.get(5, TimeUnit.SECONDS).getAmount());
            edit.get(5, TimeUnit.SECONDS);
            assertEquals(new BigDecimal("99.99"), jdbc.queryForObject("select price from t_dish where id=101", BigDecimal.class));
            assertEquals(new BigDecimal("38.00"), jdbc.queryForObject("select dish_price from t_order_detail where dish_id=101", BigDecimal.class));
        } finally { release.countDown(); stop(pool); }
    }

    @Test
    void timeoutSqlUsesExactBoundaryAndPreservesAllOtherStatesAndPaymentFields() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 25, 15, 0);
        LocalDateTime deadline = now.minusMinutes(15);
        insertOrder(7001, 1, 1, deadline);
        insertOrder(7002, 1, 1, deadline.plusSeconds(1));
        insertOrder(7003, 2, 1, deadline.minusHours(1));
        insertOrder(7004, 3, 1, deadline.minusHours(1));
        insertOrder(7005, 4, 1, deadline.minusHours(1));
        insertOrder(7006, 1, 2, deadline.minusSeconds(1));
        jdbc.update("update t_orders set pay_status=1,pay_time=?,remark='保留备注' where id=7001", deadline);
        assertEquals(2, orderMapper.cancelTimedOut(deadline, now));
        assertEquals(4, orderStatus(7001));
        assertEquals(1, orderStatus(7002));
        assertEquals(2, orderStatus(7003));
        assertEquals(3, orderStatus(7004));
        assertEquals(4, orderStatus(7005));
        assertEquals(4, orderStatus(7006));
        assertEquals("商家超时未接单，订单自动取消", jdbc.queryForObject("select cancel_reason from t_orders where id=7001", String.class));
        assertEquals(1, jdbc.queryForObject("select pay_status from t_orders where id=7001", Integer.class));
        assertEquals(deadline, jdbc.queryForObject("select pay_time from t_orders where id=7001", LocalDateTime.class));
        assertEquals("保留备注", jdbc.queryForObject("select remark from t_orders where id=7001", String.class));
        assertNull(jdbc.queryForObject("select finish_time from t_orders where id=7001", LocalDateTime.class));
        assertEquals(0, orderMapper.cancelTimedOut(deadline, now.plusSeconds(1)));
    }

    @Test
    void taskRunsWithoutLoginContextAndCatchesUpOldOrdersFromBeforeRestart() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);
        insertOrder(7001, 1, 1, now.minusDays(1));
        insertOrder(7002, 1, 1, now.minusMinutes(14));
        assertNull(ThreadLocalUtil.get());
        timeoutTask.cancelExpiredOrders();
        assertEquals(4, orderStatus(7001));
        assertEquals(1, orderStatus(7002));
        verify(orderMapper).cancelTimedOut(argThat(t -> t.isAfter(now.minusMinutes(16)) && t.isBefore(now.minusMinutes(14))), any());
        assertEquals(4, count("t_cart"));
    }

    @Test
    void repeatedOrConcurrentTimeoutScansAreSafe() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);
        insertOrder(7001, 1, 1, now.minusHours(1));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(timeoutTask::cancelExpiredOrders);
            Future<?> second = pool.submit(timeoutTask::cancelExpiredOrders);
            first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertEquals(4, orderStatus(7001));
            Map<String, Object> cancelled = jdbc.queryForMap("select * from t_orders where id=7001");
            timeoutTask.cancelExpiredOrders();
            assertEquals(cancelled, jdbc.queryForMap("select * from t_orders where id=7001"));
        } finally { stop(pool); }
    }

    @Test
    void merchantAcceptCommittedFirstCannotBeOverwrittenByWaitingTimeoutScan() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);
        insertOrder(7001, 1, 1, now.minusHours(1));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> accept = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                assertEquals(1, orderMapper.accept(7001L, 1L, now));
                accepted.countDown(); await(release);
            }));
            assertTrue(accepted.await(3, TimeUnit.SECONDS));
            Future<?> timeout = pool.submit(timeoutTask::cancelExpiredOrders);
            assertThrows(TimeoutException.class, () -> timeout.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            accept.get(5, TimeUnit.SECONDS); timeout.get(5, TimeUnit.SECONDS);
            assertEquals(2, orderStatus(7001));
            assertNull(jdbc.queryForObject("select cancel_reason from t_orders where id=7001", String.class));
        } finally { release.countDown(); stop(pool); }
    }

    @Test
    void timeoutCommittedFirstMakesMerchantAcceptFailInsteadOfRestoringOrder() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);
        insertOrder(7001, 1, 1, now.minusHours(1));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> timeout = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                assertEquals(1, orderMapper.cancelTimedOut(now.minusMinutes(15), now));
                cancelled.countDown(); await(release);
            }));
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
            Future<?> accept = pool.submit(() -> {
                ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1, "type", "admin"));
                try { orderService.accept(7001L); } finally { ThreadLocalUtil.remove(); }
            });
            assertThrows(TimeoutException.class, () -> accept.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            timeout.get(5, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(ExecutionException.class, () -> accept.get(5, TimeUnit.SECONDS));
            assertInstanceOf(BizException.class, error.getCause());
            assertEquals(4, orderStatus(7001));
        } finally { release.countDown(); stop(pool); }
    }

    private void pauseAfterCartLock(CountDownLatch locked, CountDownLatch release) {
        doAnswer(call -> {
            List<Cart> carts = sqlSession.selectList(NS + "CartMapper.listForUpdate", Map.of("userId", 1L, "storeId", 1L));
            locked.countDown(); await(release);
            return carts;
        }).when(cartMapper).listForUpdate(1L, 1L);
    }

    private void insertOrder(long id, int status, long storeId, LocalDateTime created) {
        jdbc.update("insert into t_orders(id,order_no,user_id,store_id,amount,status,pay_status,pickup_type,create_time,update_time) " +
                "values(?,?,1,?,38.00,?,0,1,?,?)", id, "TIMEOUT-" + id, storeId, status, created, created);
    }

    private Order onlyOrder() {
        return jdbc.queryForObject("select * from t_orders", (rs, row) -> {
            Order order = new Order();
            order.setId(rs.getLong("id")); order.setUserId(rs.getLong("user_id"));
            order.setOrderNo(rs.getString("order_no")); order.setStatus(rs.getInt("status"));
            order.setPayStatus(rs.getInt("pay_status")); order.setAmount(rs.getBigDecimal("amount"));
            order.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
            return order;
        });
    }

    private String token(int userId) {
        String phone = switch (userId) { case 1 -> "13800000001"; case 2 -> "13900000002"; default -> "13700000003"; };
        String token = JwtUtil.genToken(Map.of("id", userId, "phone", phone, "type", "user"));
        redis.values.put(token, token);
        return token;
    }

    private OrderSubmitDTO request(long storeId) {
        OrderSubmitDTO dto = new OrderSubmitDTO(); dto.setStoreId(storeId); dto.setPickupType(1);
        return dto;
    }

    private OrderSubmitVO submit(int userId, long storeId) {
        return asUser(userId, () -> appOrderService.submit(request(storeId)));
    }

    private <T> T asUser(int userId, Supplier<T> action) {
        ThreadLocalUtil.set(Map.of("id", userId, "type", "user"));
        try { return action.get(); } finally { ThreadLocalUtil.remove(); }
    }

    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }
    private int orderStatus(long id) { return jdbc.queryForObject("select status from t_orders where id=?", Integer.class, id); }
    private void assertAllRolledBack() {
        assertEquals(0, count("t_orders")); assertEquals(0, count("t_order_detail")); assertEquals(4, count("t_cart"));
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(4, TimeUnit.SECONDS), "等待并发测试协调信号超时"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void stop(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
}
