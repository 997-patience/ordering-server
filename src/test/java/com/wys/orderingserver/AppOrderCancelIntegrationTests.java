package com.wys.orderingserver;

import com.github.pagehelper.PageHelper;
import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.OrderMapper;
import com.wys.orderingserver.service.AppOrderService;
import com.wys.orderingserver.service.OrderService;
import com.wys.orderingserver.support.DishRedisStub;
import com.wys.orderingserver.task.OrderTimeoutTask;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真正执行锁定读和条件 UPDATE，覆盖取消、接单和超时扫描之间的竞争。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:app-order-cancel-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql,classpath:dish-add-test.sql,classpath:user-login-test.sql,classpath:order-submit-test.sql"
})
@AutoConfigureMockMvc
@Sql({"/app-dish-list-data.sql", "/app-cart-list-data.sql", "/app-order-detail-data.sql"})
class AppOrderCancelIntegrationTests {
    private static final String NS = "com.wys.orderingserver.mapper.OrderMapper.";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AppOrderService service;
    @Autowired private OrderService adminService;
    @Autowired private OrderTimeoutTask timeoutTask;
    @Autowired private SqlSessionTemplate sqlSession;
    @MockitoSpyBean private OrderMapper mapper;
    @MockitoBean private StringRedisTemplate redisTemplate;
    private DishRedisStub redis;

    @BeforeEach
    void prepare() {
        ThreadLocalUtil.remove();
        redis = new DishRedisStub(redisTemplate);
        jdbc.update("update t_orders set status=1 where id=5002"); // 另一用户同门店的待接单订单。
    }

    @AfterEach
    void contextsAreCleared() {
        assertNull(ThreadLocalUtil.get());
        assertNull(PageHelper.getLocalPage());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void pendingOrderIsCancelledAndOnlyThreeColumnsChange() throws Exception {
        Map<String, Object> before = order(5001);
        List<Map<String, Object>> others = jdbc.queryForList("select * from t_orders where id<>5001 order by id");
        List<Map<String, Object>> details = jdbc.queryForList("select * from t_order_detail order by id");
        List<Map<String, Object>> carts = jdbc.queryForList("select * from t_cart order by id");
        List<Map<String, Object>> dishes = jdbc.queryForList("select * from t_dish order by id");
        LocalDateTime earliest = now().minusSeconds(2);
        mvc.perform(request(1, 5001))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data").isEmpty());
        assertEquals(4, orderStatus(5001));
        assertEquals("用户主动取消", reason(5001));
        assertEquals(unchangedColumns(before), unchangedColumns(order(5001)));
        LocalDateTime updated = jdbc.queryForObject("select update_time from t_orders where id=5001", LocalDateTime.class);
        assertFalse(updated.isBefore(earliest));
        assertFalse(updated.isAfter(now().plusSeconds(2)));
        assertEquals(others, jdbc.queryForList("select * from t_orders where id<>5001 order by id"));
        assertEquals(details, jdbc.queryForList("select * from t_order_detail order by id"));
        assertEquals(carts, jdbc.queryForList("select * from t_cart order by id"));
        assertEquals(dishes, jdbc.queryForList("select * from t_dish order by id"));
        verify(mapper).findByIdAndUserIdForUpdate(5001L, 1L);
        verify(mapper).cancelByUser(eq(5001L), eq(1L), any(LocalDateTime.class));
        mvc.perform(get("/app/order/5001").header("Authorization", token(1)))
                .andExpect(jsonPath("$.data.status").value(4))
                .andExpect(jsonPath("$.data.cancelReason").value("用户主动取消"))
                .andExpect(jsonPath("$.data.details.length()").value(2));
    }

    @Test
    void unpaidOrderWithoutDetailsCanBeCancelledAccordingToStateRule() throws Exception {
        mvc.perform(request(1, 5005)).andExpect(jsonPath("$.code").value(0));
        assertEquals(4, orderStatus(5005));
        assertEquals(0, jdbc.queryForObject("select pay_status from t_orders where id=5005", Integer.class));
    }

    @Test
    void ownOrderInAnotherStoreDoesNotRequireAdminStoreBinding() throws Exception {
        jdbc.update("update t_orders set status=1,finish_time=null where id=5003");
        mvc.perform(request(1, 5003)).andExpect(jsonPath("$.code").value(0));
        assertEquals(4, orderStatus(5003));
        assertEquals("用户主动取消", reason(5003));
    }

    @Test
    void closedStoreDoesNotPreventCancellingOwnPendingOrder() throws Exception {
        jdbc.update("update t_orders set status=1,cancel_reason=null where id=5004");
        mvc.perform(request(1, 5004)).andExpect(jsonPath("$.code").value(0));
        assertEquals(4, orderStatus(5004));
    }

    @Test
    void acceptedOrderMatchesExactDocumentFailureAndIsNotModified() throws Exception {
        Map<String, Object> before = order(5006);
        mvc.perform(request(1, 5006))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("商家已接单，无法取消，请联系门店"))
                .andExpect(jsonPath("$.data").isEmpty());
        assertEquals(before, order(5006));
        verify(mapper, never()).cancelByUser(anyLong(), anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(longs = {5003, 5004})
    void finishedAndCancelledOrdersAreRejectedWithoutOverwritingReason(long id) throws Exception {
        Map<String, Object> before = order(id);
        mvc.perform(request(1, id)).andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前订单状态不允许取消"));
        assertEquals(before, order(id));
        verify(mapper, never()).cancelByUser(anyLong(), anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(longs = {5002, 99999999, Long.MAX_VALUE})
    void foreignAndMissingOrderHaveSameFailureAndNoUpdate(long id) throws Exception {
        List<Map<String, Object>> before = jdbc.queryForList("select * from t_orders order by id");
        mvc.perform(request(1, id)).andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("订单不存在或无权访问"));
        assertEquals(before, jdbc.queryForList("select * from t_orders order by id"));
        verify(mapper, never()).cancelByUser(anyLong(), anyLong(), any());
    }

    @Test
    void secondUserCanCancelOwnOrderWithoutCancellingFirstUsersOrder() throws Exception {
        mvc.perform(request(2, 5002)).andExpect(jsonPath("$.code").value(0));
        assertEquals(4, orderStatus(5002));
        assertEquals(1, orderStatus(5001));
    }

    @Test
    void requestCannotOverridePathOwnerReasonTargetStateOrTimestamp() throws Exception {
        mvc.perform(request(1, 5001).param("id", "5002").param("userId", "2")
                        .param("cancelReason", "伪造原因").param("status", "3")
                        .contentType("application/json")
                        .content("{\"id\":5002,\"userId\":2,\"cancelReason\":\"伪造原因\",\"status\":3,\"updateTime\":\"2000-01-01 00:00:00\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(4, orderStatus(5001)); assertEquals("用户主动取消", reason(5001));
        assertEquals(1, orderStatus(5002));
        assertTrue(jdbc.queryForObject("select update_time from t_orders where id=5001", LocalDateTime.class).getYear() >= 2026);
    }

    @Test
    void longOrderIdIsNotTruncated() throws Exception {
        mvc.perform(request(1, 3000000000L)).andExpect(jsonPath("$.code").value(0));
        assertEquals(4, orderStatus(3000000000L));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositivePathIdIsRejectedBeforeQuery(long id) throws Exception {
        mvc.perform(request(1, id)).andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请输入合法的订单ID"));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void serviceAlsoChecksNullAndNonPositiveIds(Long id) {
        BizException error = assertThrows(BizException.class, () -> cancel(1, id));
        assertEquals("请输入合法的订单ID", error.getMessage());
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "9223372036854775808"})
    void malformedPathIdUsesExistingReadableTypeError(String id) throws Exception {
        mvc.perform(put("/app/order/cancel/" + id).header("Authorization", token(1)))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：id"));
        verifyNoInteractions(mapper);
    }

    @Test
    void missingRevokedAndAdminTokensCannotCancelCustomerOrder() throws Exception {
        mvc.perform(put("/app/order/cancel/5001")).andExpect(status().isUnauthorized());
        String revoked = token(1); redis.values.remove(revoked);
        mvc.perform(put("/app/order/cancel/5001").header("Authorization", revoked)).andExpect(status().isUnauthorized());
        String admin = JwtUtil.genToken(Map.of("id", 2, "role", 2, "storeId", 1, "type", "admin"));
        redis.values.put(admin, admin);
        mvc.perform(put("/app/order/cancel/5001").header("Authorization", admin)).andExpect(status().isUnauthorized());
        verifyNoInteractions(mapper);
    }

    @Test
    void directCallsRequireCustomerIdentity() {
        assertThrows(BizException.class, () -> service.cancel(5001L));
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "type", "admin", "storeId", 1));
        try { assertThrows(BizException.class, () -> service.cancel(5001L)); }
        finally { ThreadLocalUtil.remove(); }
        verifyNoInteractions(mapper);
    }

    @Test
    void directSqlCannotCancelAnotherUsersPendingOrder() {
        Map<String, Object> before = order(5001);
        assertEquals(0, mapper.cancelByUser(5001L, 2L, now()));
        assertEquals(before, order(5001));
    }

    @ParameterizedTest
    @CsvSource({"5006,2", "5003,3", "5004,4"})
    void conditionalSqlDoesNotOverwriteOtherStates(long id, int expectedStatus) {
        Map<String, Object> before = order(id);
        assertEquals(0, mapper.cancelByUser(id, 1L, now()));
        assertEquals(expectedStatus, orderStatus(id)); assertEquals(before, order(id));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void unexpectedAffectedRowCountRollsBackEvenAfterRealUpdate(int reportedRows) {
        Map<String, Object> before = order(5001);
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertEquals(1, executeUserUpdate(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            return reportedRows;
        }).when(mapper).cancelByUser(eq(5001L), eq(1L), any(LocalDateTime.class));
        assertEquals("取消订单失败，请重试", assertThrows(BizException.class, () -> cancel(1, 5001L)).getMessage());
        assertEquals(before, order(5001));
    }

    @Test
    void databaseExceptionAfterRealUpdateAlsoRollsBack() {
        Map<String, Object> before = order(5001);
        doAnswer(call -> {
            executeUserUpdate(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            throw new DataAccessResourceFailureException("模拟更新异常");
        }).when(mapper).cancelByUser(eq(5001L), eq(1L), any(LocalDateTime.class));
        assertThrows(DataAccessResourceFailureException.class, () -> cancel(1, 5001L));
        assertEquals(before, order(5001));
    }

    @Test
    void repeatedCancellationIsRejectedWithoutChangingFirstCancellation() throws Exception {
        mvc.perform(request(1, 5001)).andExpect(jsonPath("$.code").value(0));
        Map<String, Object> once = order(5001);
        mvc.perform(request(1, 5001)).andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前订单状态不允许取消"));
        assertEquals(once, order(5001));
        verify(mapper, times(1)).cancelByUser(eq(5001L), eq(1L), any());
    }

    @Test
    void cancellationBusinessDoesNotNeedRedisOrChangeMenuCache() {
        cancel(1, 5001L);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void concurrentDuplicateRequestsOnlyUpdateOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> action = () -> {
            await(start);
            try { cancel(1, 5001L); return "success"; }
            catch (BizException e) { return e.getMessage(); }
        };
        try {
            Future<String> first = pool.submit(action); Future<String> second = pool.submit(action);
            start.countDown();
            List<String> results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("success"::equals).count());
            assertTrue(results.contains("当前订单状态不允许取消"));
            assertEquals(4, orderStatus(5001));
            verify(mapper, times(1)).cancelByUser(eq(5001L), eq(1L), any());
        } finally { stop(pool); }
    }

    @Test
    void cancellationCommittedFirstMakesWaitingMerchantAcceptFail() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch updated = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        pauseUserUpdate(updated, release);
        try {
            Future<?> cancelling = pool.submit(() -> cancel(1, 5001L));
            assertTrue(updated.await(3, TimeUnit.SECONDS));
            Future<?> accepting = pool.submit(() -> asAdmin(() -> adminService.accept(5001L)));
            assertThrows(TimeoutException.class, () -> accepting.get(100, TimeUnit.MILLISECONDS));
            release.countDown(); cancelling.get(5, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(ExecutionException.class, () -> accepting.get(5, TimeUnit.SECONDS));
            assertInstanceOf(BizException.class, error.getCause());
            assertEquals(4, orderStatus(5001)); assertEquals("用户主动取消", reason(5001));
        } finally { release.countDown(); stop(pool); }
    }

    @Test
    void merchantAcceptCommittedFirstGivesWaitingUserExactDocumentFailure() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch updated = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            LocalDateTime acceptedAt = call.getArgument(2);
            int rows = sqlSession.update(NS + "accept", Map.of("id", 5001L, "storeId", 1L, "updateTime", acceptedAt));
            updated.countDown(); await(release); return rows;
        }).when(mapper).accept(eq(5001L), eq(1L), any(LocalDateTime.class));
        try {
            Future<?> accepting = pool.submit(() -> asAdmin(() -> adminService.accept(5001L)));
            boolean reached = updated.await(3, TimeUnit.SECONDS);
            if (!reached) accepting.get(1, TimeUnit.SECONDS);
            assertTrue(reached);
            Future<?> cancelling = pool.submit(() -> cancel(1, 5001L));
            assertThrows(TimeoutException.class, () -> cancelling.get(100, TimeUnit.MILLISECONDS));
            release.countDown(); accepting.get(5, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(ExecutionException.class, () -> cancelling.get(5, TimeUnit.SECONDS));
            assertEquals("商家已接单，无法取消，请联系门店", error.getCause().getMessage());
            assertEquals(2, orderStatus(5001)); assertNull(reason(5001));
            verify(mapper, never()).cancelByUser(anyLong(), anyLong(), any());
        } finally { release.countDown(); stop(pool); }
    }

    @Test
    void cancellationCommittedFirstIsNotOverwrittenByTimeoutTask() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch updated = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        pauseUserUpdate(updated, release);
        try {
            Future<?> cancelling = pool.submit(() -> cancel(1, 5001L));
            assertTrue(updated.await(3, TimeUnit.SECONDS));
            Future<?> timingOut = pool.submit(timeoutTask::cancelExpiredOrders);
            assertThrows(TimeoutException.class, () -> timingOut.get(100, TimeUnit.MILLISECONDS));
            release.countDown(); cancelling.get(5, TimeUnit.SECONDS); timingOut.get(5, TimeUnit.SECONDS);
            assertEquals(4, orderStatus(5001)); assertEquals("用户主动取消", reason(5001));
        } finally { release.countDown(); stop(pool); }
    }

    @Test
    void timeoutCommittedFirstIsNotRewrittenAsUserCancellation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch updated = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            int rows = sqlSession.update(NS + "cancelTimedOut", Map.of("deadline", call.getArgument(0), "now", call.getArgument(1)));
            updated.countDown(); await(release); return rows;
        }).when(mapper).cancelTimedOut(any(LocalDateTime.class), any(LocalDateTime.class));
        try {
            Future<?> timingOut = pool.submit(timeoutTask::cancelExpiredOrders);
            assertTrue(updated.await(3, TimeUnit.SECONDS));
            Future<?> cancelling = pool.submit(() -> cancel(1, 5001L));
            assertThrows(TimeoutException.class, () -> cancelling.get(100, TimeUnit.MILLISECONDS));
            release.countDown(); timingOut.get(5, TimeUnit.SECONDS);
            ExecutionException error = assertThrows(ExecutionException.class, () -> cancelling.get(5, TimeUnit.SECONDS));
            assertEquals("当前订单状态不允许取消", error.getCause().getMessage());
            assertEquals(4, orderStatus(5001)); assertEquals("商家超时未接单，订单自动取消", reason(5001));
            verify(mapper, never()).cancelByUser(anyLong(), anyLong(), any());
        } finally { release.countDown(); stop(pool); }
    }

    private void pauseUserUpdate(CountDownLatch updated, CountDownLatch release) {
        doAnswer(call -> {
            int rows = executeUserUpdate(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            updated.countDown(); await(release); return rows;
        }).when(mapper).cancelByUser(eq(5001L), eq(1L), any(LocalDateTime.class));
    }

    private int executeUserUpdate(Long id, Long userId, LocalDateTime updateTime) {
        return sqlSession.update(NS + "cancelByUser", Map.of("id", id, "userId", userId, "updateTime", updateTime));
    }

    private void cancel(int userId, Long id) {
        ThreadLocalUtil.set(Map.of("id", userId, "type", "user"));
        try { service.cancel(id); } finally { ThreadLocalUtil.remove(); }
    }

    private void asAdmin(Runnable action) {
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1, "type", "admin"));
        try { action.run(); } finally { ThreadLocalUtil.remove(); }
    }

    private MockHttpServletRequestBuilder request(int userId, long id) {
        return put("/app/order/cancel/" + id).header("Authorization", token(userId));
    }

    private String token(int userId) {
        String phone = userId == 1 ? "13800000001" : "13900000002";
        String token = JwtUtil.genToken(Map.of("id", userId, "phone", phone, "type", "user"));
        redis.values.put(token, token); return token;
    }

    private Map<String, Object> order(long id) { return jdbc.queryForMap("select * from t_orders where id=?", id); }
    private int orderStatus(long id) { return jdbc.queryForObject("select status from t_orders where id=?", Integer.class, id); }
    private String reason(long id) { return jdbc.queryForObject("select cancel_reason from t_orders where id=?", String.class, id); }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0); }
    private Map<String, Object> unchangedColumns(Map<String, Object> row) {
        Map<String, Object> fields = new LinkedHashMap<>(row);
        fields.keySet().removeIf(key -> key.equalsIgnoreCase("status")
                || key.equalsIgnoreCase("cancel_reason") || key.equalsIgnoreCase("update_time"));
        return fields;
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(4, TimeUnit.SECONDS), "等待并发测试协调信号超时"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void stop(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }
}
