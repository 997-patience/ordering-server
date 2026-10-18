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

/** 使用独立 H2 和真实 SQL 验证完成时间、门店范围、事务回滚与并发完成。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-complete-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:order-page-test.sql,classpath:order-complete-setup.sql"
})
@AutoConfigureMockMvc
@Sql("/order-complete-reset.sql")
class OrderCompleteIntegrationTests {

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
    void completingOrderChangesOnlyItsStateAndTwoTimesWithoutTouchingAnyOtherOrder() throws Exception {
        Map<String, Object> before = row(102);
        List<Map<String, Object>> others = otherOrders(102);
        LocalDateTime beforeCall = LocalDateTime.now().minusSeconds(1);

        mockMvc.perform(request(staffToken(), 102))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));

        Map<String, Object> completed = row(102);
        assertEquals(3, statusOf(102));
        LocalDateTime finished = finishTime(102);
        assertNotNull(finished);
        assertFalse(finished.isBefore(beforeCall));
        assertFalse(finished.isAfter(LocalDateTime.now().plusSeconds(1)));
        assertEquals(completed.get("UPDATE_TIME"), completed.get("FINISH_TIME"));
        assertEquals(unchangedFields(before), unchangedFields(completed));
        // 包括其他店订单和历史已完成订单，防止出现无 WHERE 的全表更新时间。
        assertEquals(others, otherOrders(102));
    }

    @Test
    void queryParametersCannotOverridePathStoreStatusOrServerTime() throws Exception {
        List<Map<String, Object>> others = otherOrders(102);
        mockMvc.perform(request(staffToken(), 102).param("id", "201").param("storeId", "2")
                        .param("status", "4").param("finishTime", "1999-01-01 00:00:00"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(3, statusOf(102));
        assertTrue(finishTime(102).isAfter(LocalDateTime.of(2000, 1, 1, 0, 0)));
        assertEquals(others, otherOrders(102));
    }

    @ParameterizedTest
    @ValueSource(longs = {101, 103, 104})
    void pendingCompletedAndCancelledStatesCannotBeCompleted(long id) throws Exception {
        Map<String, Object> before = row(id);
        mockMvc.perform(request(staffToken(), id))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前订单状态不能完成"));
        assertEquals(before, row(id));
        verify(orderMapper, never()).complete(anyLong(), anyLong(), any(LocalDateTime.class));
    }

    @Test
    void repeatedCompletionDoesNotRewriteFinishTime() throws Exception {
        String token = staffToken();
        mockMvc.perform(request(token, 102)).andExpect(jsonPath("$.code").value(0));
        Map<String, Object> completed = row(102);
        mockMvc.perform(request(token, 102))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前订单状态不能完成"));
        assertEquals(completed, row(102));
        verify(orderMapper, times(1)).complete(anyLong(), anyLong(), any(LocalDateTime.class));
    }

    @Test
    void completedOrderCannotBeAcceptedAgain() throws Exception {
        String token = staffToken();
        mockMvc.perform(request(token, 102)).andExpect(jsonPath("$.code").value(0));
        Map<String, Object> completed = row(102);
        mockMvc.perform(put("/admin/order/accept/102").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(completed, row(102));
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
        assertEquals(2, statusOf(102));
        assertNull(finishTime(102));
    }

    @Test
    void nonNumericPathIdIsRejected() throws Exception {
        mockMvc.perform(put("/admin/order/complete/abc").header("Authorization", staffToken()))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：id"));
    }

    @Test
    void bigintOrderIdIsSupported() throws Exception {
        mockMvc.perform(request(staffToken(), 3000000000L)).andExpect(jsonPath("$.code").value(0));
        assertEquals(3, statusOf(3000000000L));
        assertNotNull(finishTime(3000000000L));
        assertNull(finishTime(102));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void unboundAdminCannotChooseStoreViaQueryParameter(int role) throws Exception {
        String token = tokenFor(Map.of("id", role == 1 ? 1 : 4, "role", role));
        mockMvc.perform(request(token, 102).param("storeId", "1"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前员工未绑定门店，请使用已绑定门店的管理员账号"));
        assertEquals(2, statusOf(102));
    }

    @Test
    void boundSuperAdminUsesItsOwnStore() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 3, "role", 1, "storeId", 1L)), 102))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(3, statusOf(102));
        assertNull(finishTime(201));
    }

    @Test
    void secondStoreCanCompleteItsOwnOrder() throws Exception {
        List<Map<String, Object>> others = otherOrders(201);
        mockMvc.perform(request(tokenFor(Map.of("id", 5, "role", 2, "storeId", 2L)), 201))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(3, statusOf(201));
        assertNotNull(finishTime(201));
        assertEquals(others, otherOrders(201));
    }

    @Test
    void customerCannotCompleteOrder() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 99)), 102))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("无门店管理权限"));
        assertEquals(2, statusOf(102));
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(put("/admin/order/complete/102")).andExpect(status().isUnauthorized());
        assertNull(finishTime(102));
    }

    @Test
    void nonexistentStoreIsRejected() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 2, "role", 2, "storeId", 999L)), 102))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("所属门店不存在"));
        assertNull(finishTime(102));
    }

    @Test
    void directServiceCallRejectsNullId() {
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(BizException.class, () -> orderService.complete(null));
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals(2, statusOf(102));
    }

    @Test
    void acceptedUnpaidOrderIsAllowedByDocumentedStatusRule() throws Exception {
        assertEquals(0, ((Number) row(105).get("PAY_STATUS")).intValue());
        mockMvc.perform(request(staffToken(), 105)).andExpect(jsonPath("$.code").value(0));
        assertEquals(3, statusOf(105));
        assertNotNull(finishTime(105));
        assertEquals(0, ((Number) row(105).get("PAY_STATUS")).intValue());
    }

    @ParameterizedTest
    @ValueSource(longs = {101, 103, 104})
    void mapperAlsoRejectsStatesOtherThanAccepted(long id) {
        Map<String, Object> before = row(id);
        assertEquals(0, orderMapper.complete(id, 1L, LocalDateTime.now()));
        assertEquals(before, row(id));
    }

    @Test
    void mapperCannotCompleteAnotherStoresOrder() {
        Map<String, Object> before = row(201);
        assertEquals(0, orderMapper.complete(201L, 1L, LocalDateTime.now()));
        assertEquals(before, row(201));
    }

    @Test
    void failedRowCountRollsBackStatusAndBothTimes() {
        Map<String, Object> before = row(102);
        doAnswer(invocation -> {
            executeRealCompletion(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return 0;
        }).when(orderMapper).complete(anyLong(), anyLong(), any(LocalDateTime.class));

        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            BizException error = assertThrows(BizException.class, () -> orderService.complete(102L));
            assertEquals("当前订单状态不能完成", error.getMessage());
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals(before, row(102));
    }

    @Test
    void runtimeFailureAfterWriteRollsBackStatusAndBothTimes() {
        Map<String, Object> before = row(102);
        doAnswer(invocation -> {
            executeRealCompletion(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            throw new IllegalStateException("模拟写入后的运行时异常");
        }).when(orderMapper).complete(anyLong(), anyLong(), any(LocalDateTime.class));

        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(IllegalStateException.class, () -> orderService.complete(102L));
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals(before, row(102));
    }

    @Test
    void twoConcurrentCompletionsHaveExactlyOneSuccessfulTransition() throws Exception {
        List<Map<String, Object>> others = otherOrders(102);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        Callable<String> attempt = () -> {
            ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
            try {
                startTogether.await(5, TimeUnit.SECONDS);
                orderService.complete(102L);
                return "success";
            } catch (BizException exception) {
                return exception.getMessage();
            } finally {
                ThreadLocalUtil.remove();
            }
        };
        try {
            Future<String> first = executor.submit(attempt);
            Future<String> second = executor.submit(attempt);
            List<String> results = List.of(first.get(8, TimeUnit.SECONDS), second.get(8, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(results, "success"));
            assertEquals(1, Collections.frequency(results, "当前订单状态不能完成"));
            assertEquals(3, statusOf(102));
            assertNotNull(finishTime(102));
            assertEquals(others, otherOrders(102));
            verify(orderMapper, times(1)).complete(anyLong(), anyLong(), any(LocalDateTime.class));
        } finally {
            executor.shutdownNow();
        }
    }

    private void executeRealCompletion(Long id, Long storeId, LocalDateTime completeTime) {
        int rows = sqlSessionTemplate.update("com.wys.orderingserver.mapper.OrderMapper.complete",
                Map.of("id", id, "storeId", storeId, "completeTime", completeTime));
        assertEquals(1, rows);
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
        result.remove("UPDATE_TIME");
        result.remove("FINISH_TIME");
        return result;
    }

    private int statusOf(long id) {
        return jdbcTemplate.queryForObject("select status from t_orders where id = ?", Integer.class, id);
    }

    private LocalDateTime finishTime(long id) {
        return jdbcTemplate.queryForObject("select finish_time from t_orders where id = ?", LocalDateTime.class, id);
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
        return put("/admin/order/complete/" + id).header("Authorization", token);
    }
}
