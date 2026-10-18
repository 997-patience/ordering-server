package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.OrderMapper;
import com.wys.orderingserver.service.OrderService;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 H2 执行真实 SQL，验证拒单原因、门店范围、终态、事务及接单与拒单竞争。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-cancel-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:order-page-test.sql,classpath:order-cancel-setup.sql"
})
@AutoConfigureMockMvc
@Sql("/order-cancel-reset.sql")
class OrderCancelIntegrationTests {

    private static final String BODY = "{\"cancelReason\":\"备料已售完，抱歉\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrderService orderService;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @MockitoSpyBean private OrderMapper orderMapper;
    @MockitoBean private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> tokenOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepareRedis() {
        tokenOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(tokenOperations);
    }

    @AfterEach
    void identityIsCleared() {
        assertNull(ThreadLocalUtil.get());
    }

    @Test
    void cancellingChangesOnlyTargetStateReasonAndUpdateTime() throws Exception {
        Map<String, Object> before = row(101);
        List<Map<String, Object>> others = otherOrders(101);
        LocalDateTime beforeCall = LocalDateTime.now().minusSeconds(1);

        mockMvc.perform(request(staffToken(), 101))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));

        Map<String, Object> cancelled = row(101);
        assertEquals(4, statusOf(101));
        assertEquals("备料已售完，抱歉", cancelled.get("CANCEL_REASON"));
        LocalDateTime updated = jdbcTemplate.queryForObject("select update_time from t_orders where id = 101", LocalDateTime.class);
        assertNotNull(updated);
        assertFalse(updated.isBefore(beforeCall));
        assertFalse(updated.isAfter(LocalDateTime.now().plusSeconds(1)));
        assertEquals(unchangedFields(before), unchangedFields(cancelled));
        assertNull(cancelled.get("FINISH_TIME"));
        assertEquals(others, otherOrders(101));
    }

    @Test
    void requestCannotOverridePathStoreTargetStatePaymentOrFinishTime() throws Exception {
        List<Map<String, Object>> others = otherOrders(101);
        mockMvc.perform(request(staffToken(), 101).param("id", "201").param("storeId", "2")
                        .content("{\"id\":201,\"storeId\":2,\"status\":3,\"amount\":0,\"payStatus\":1," +
                                "\"finishTime\":\"1999-01-01 00:00:00\",\"cancelReason\":\"售完\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(4, statusOf(101));
        assertEquals("售完", row(101).get("CANCEL_REASON"));
        assertNull(row(101).get("FINISH_TIME"));
        assertEquals(0, ((Number) row(101).get("PAY_STATUS")).intValue());
        assertEquals(others, otherOrders(101));
    }

    @ParameterizedTest
    @ValueSource(longs = {102, 103, 104})
    void acceptedCompletedAndCancelledStatesCannotBeRejected(long id) throws Exception {
        List<Map<String, Object>> before = allOrders();
        mockMvc.perform(request(staffToken(), id))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前订单状态不允许拒单"));
        assertEquals(before, allOrders());
        verify(orderMapper, never()).cancel(anyLong(), anyLong(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void repeatedCancellationCannotOverwriteOriginalReasonOrTime() throws Exception {
        String token = staffToken();
        mockMvc.perform(request(token, 101)).andExpect(jsonPath("$.code").value(0));
        Map<String, Object> cancelled = row(101);
        mockMvc.perform(request(token, 101).content("{\"cancelReason\":\"改成另一个原因\"}"))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(cancelled, row(101));
        verify(orderMapper, times(1)).cancel(anyLong(), anyLong(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void cancelledOrderCannotBeAcceptedOrCompleted() throws Exception {
        String token = staffToken();
        mockMvc.perform(request(token, 101)).andExpect(jsonPath("$.code").value(0));
        Map<String, Object> cancelled = row(101);
        mockMvc.perform(put("/admin/order/accept/101").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(1));
        mockMvc.perform(put("/admin/order/complete/101").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(cancelled, row(101));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"cancelReason\":null}", "{\"cancelReason\":\"\"}",
            "{\"cancelReason\":\"  \\t\\n  \"}", "{\"cancelReason\":\"　\"}"})
    void missingNullEmptyAndWhitespaceReasonsAreRejected(String body) throws Exception {
        List<Map<String, Object>> before = allOrders();
        mockMvc.perform(request(staffToken(), 101).content(body))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("拒单原因不能为空"));
        assertEquals(before, allOrders());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{bad-json"})
    void missingNullOrMalformedBodyReturnsReadableError(String body) throws Exception {
        mockMvc.perform(request(staffToken(), 101).content(body))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请求参数格式错误，请检查请求体是否为合法 JSON"));
        assertEquals(1, statusOf(101));
    }

    @Test
    void surroundingWhitespaceIsRemovedFromReason() throws Exception {
        mockMvc.perform(request(staffToken(), 101).content("{\"cancelReason\":\"　  售完 　\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("售完", row(101).get("CANCEL_REASON"));
    }

    @Test
    void reasonAtColumnLimitIsAcceptedWithoutTruncation() throws Exception {
        String reason = "原".repeat(255);
        mockMvc.perform(request(staffToken(), 101).content("{\"cancelReason\":\"" + reason + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(reason, row(101).get("CANCEL_REASON"));
    }

    @Test
    void reasonOverColumnLimitIsRejectedBeforeWriting() throws Exception {
        List<Map<String, Object>> before = allOrders();
        mockMvc.perform(request(staffToken(), 101).content("{\"cancelReason\":\"" + "原".repeat(256) + "\"}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("拒单原因不能超过255个字符"));
        assertEquals(before, allOrders());
    }

    @ParameterizedTest
    @ValueSource(longs = {201, 999})
    void foreignOrMissingOrderIsRejected(long id) throws Exception {
        List<Map<String, Object>> before = allOrders();
        mockMvc.perform(request(staffToken(), id))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("订单不存在或不属于当前门店"));
        assertEquals(before, allOrders());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void invalidOrderIdIsRejected(long id) throws Exception {
        mockMvc.perform(request(staffToken(), id))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请选择有效的订单ID"));
        assertEquals(1, statusOf(101));
    }

    @Test
    void nonNumericPathIdIsRejected() throws Exception {
        mockMvc.perform(put("/admin/order/cancel/abc").header("Authorization", staffToken())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：id"));
    }

    @Test
    void bigintOrderIdIsSupported() throws Exception {
        mockMvc.perform(request(staffToken(), 3000000000L)).andExpect(jsonPath("$.code").value(0));
        assertEquals(4, statusOf(3000000000L));
        assertEquals(1, statusOf(101));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void unboundAdminCannotSelectStoreFromRequest(int role) throws Exception {
        String token = tokenFor(Map.of("id", role == 1 ? 1 : 4, "role", role));
        mockMvc.perform(request(token, 101).param("storeId", "1"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前员工未绑定门店，请使用已绑定门店的管理员账号"));
        assertEquals(1, statusOf(101));
    }

    @Test
    void boundSuperAdminUsesItsOwnStore() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 3, "role", 1, "storeId", 1L)), 101))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(4, statusOf(101));
        assertEquals(1, statusOf(201));
    }

    @Test
    void secondStoreCanRejectItsOwnPaidPendingOrderWithoutChangingPayment() throws Exception {
        Map<String, Object> before = row(201);
        mockMvc.perform(request(tokenFor(Map.of("id", 5, "role", 2, "storeId", 2L)), 201))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(4, statusOf(201));
        assertEquals(unchangedFields(before), unchangedFields(row(201)));
        assertEquals(1, statusOf(101));
    }

    @Test
    void customerCannotRejectOrder() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 99)), 101))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("无门店管理权限"));
        assertEquals(1, statusOf(101));
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(put("/admin/order/cancel/101").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        assertEquals(1, statusOf(101));
    }

    @Test
    void nonexistentStoreIsRejected() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 2, "role", 2, "storeId", 999L)), 101))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("所属门店不存在"));
        assertEquals(1, statusOf(101));
    }

    @Test
    void directServiceCallAlsoValidatesIdReasonAndLogin() {
        assertThrows(BizException.class, () -> orderService.cancel(101L, "售完"));
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(BizException.class, () -> orderService.cancel(null, "售完"));
            assertThrows(BizException.class, () -> orderService.cancel(101L, null));
            assertThrows(BizException.class, () -> orderService.cancel(101L, "原".repeat(256)));
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals(1, statusOf(101));
    }

    @ParameterizedTest
    @ValueSource(longs = {102, 103, 104})
    void mapperAlsoRejectsStatesOtherThanPending(long id) {
        Map<String, Object> before = row(id);
        assertEquals(0, orderMapper.cancel(id, 1L, "售完", LocalDateTime.now()));
        assertEquals(before, row(id));
    }

    @Test
    void mapperCannotRejectAnotherStoresOrder() {
        Map<String, Object> before = row(201);
        assertEquals(0, orderMapper.cancel(201L, 1L, "售完", LocalDateTime.now()));
        assertEquals(before, row(201));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void unexpectedRowCountRollsBackActualStateReasonAndTimeUpdate(int reportedRows) {
        Map<String, Object> before = row(101);
        doAnswer(invocation -> {
            int changed = sqlSessionTemplate.update("com.wys.orderingserver.mapper.OrderMapper.cancel",
                    Map.of("id", invocation.getArgument(0), "storeId", invocation.getArgument(1),
                            "cancelReason", invocation.getArgument(2), "updateTime", invocation.getArgument(3)));
            assertEquals(1, changed);
            return reportedRows;
        }).when(orderMapper).cancel(anyLong(), anyLong(), anyString(), any(LocalDateTime.class));

        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(BizException.class, () -> orderService.cancel(101L, "售完"));
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals(before, row(101));
    }

    @Test
    void concurrentCancellationsCannotOverwriteTheWinningReason() throws Exception {
        List<Map<String, Object>> others = otherOrders(101);
        List<String> results = runConcurrently(false);
        assertEquals(1, Collections.frequency(results, "cancelled"));
        assertEquals(1, Collections.frequency(results, "当前订单状态不允许拒单"));
        assertEquals(4, statusOf(101));
        assertTrue(Set.of("原因甲", "原因乙").contains(row(101).get("CANCEL_REASON")));
        verify(orderMapper, times(1)).cancel(anyLong(), anyLong(), anyString(), any(LocalDateTime.class));
        assertEquals(others, otherOrders(101));
    }

    @Test
    void acceptingAndRejectingSamePendingOrderCannotBothSucceed() throws Exception {
        List<Map<String, Object>> others = otherOrders(101);
        List<String> results = runConcurrently(true);
        int successes = Collections.frequency(results, "accepted") + Collections.frequency(results, "cancelled");
        assertEquals(1, successes);
        if (results.contains("accepted")) {
            assertTrue(results.contains("当前订单状态不允许拒单"));
            assertEquals(2, statusOf(101));
            assertNull(row(101).get("CANCEL_REASON"));
        } else {
            assertTrue(results.contains("当前订单状态不允许接单"));
            assertEquals(4, statusOf(101));
            assertEquals("原因乙", row(101).get("CANCEL_REASON"));
        }
        assertNull(row(101).get("FINISH_TIME"));
        assertEquals(others, otherOrders(101));
    }

    private List<String> runConcurrently(boolean firstAccepts) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<String> first = executor.submit(() -> attempt(barrier, firstAccepts, "原因甲"));
            Future<String> second = executor.submit(() -> attempt(barrier, false, "原因乙"));
            return List.of(first.get(8, TimeUnit.SECONDS), second.get(8, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private String attempt(CyclicBarrier barrier, boolean accept, String reason) throws Exception {
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            barrier.await(5, TimeUnit.SECONDS);
            if (accept) {
                orderService.accept(101L);
                return "accepted";
            }
            orderService.cancel(101L, reason);
            return "cancelled";
        } catch (BizException exception) {
            return exception.getMessage();
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("select * from t_orders where id = ?", id);
    }

    private List<Map<String, Object>> allOrders() {
        return jdbcTemplate.queryForList("select * from t_orders order by id");
    }

    private List<Map<String, Object>> otherOrders(long id) {
        return jdbcTemplate.queryForList("select * from t_orders where id <> ? order by id", id);
    }

    private Map<String, Object> unchangedFields(Map<String, Object> row) {
        Map<String, Object> result = new HashMap<>(row);
        result.remove("STATUS");
        result.remove("CANCEL_REASON");
        result.remove("UPDATE_TIME");
        return result;
    }

    private int statusOf(long id) {
        return jdbcTemplate.queryForObject("select status from t_orders where id = ?", Integer.class, id);
    }

    private String staffToken() {
        return tokenFor(Map.of("id", 2, "role", 2, "storeId", 1L));
    }

    private String tokenFor(Map<String, Object> claims) {
        String token = JwtUtil.genToken(claims);
        when(tokenOperations.get(token)).thenReturn(token);
        return token;
    }

    private MockHttpServletRequestBuilder request(String token, long id) {
        return put("/admin/order/cancel/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(BODY);
    }
}
