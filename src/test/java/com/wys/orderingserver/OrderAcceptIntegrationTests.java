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

/** 独立 H2 执行真实接单 SQL，验证门店范围、状态流转、事务与并发接单。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-accept-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:order-page-test.sql,classpath:order-accept-setup.sql"
})
@AutoConfigureMockMvc
@Sql("/order-accept-reset.sql")
class OrderAcceptIntegrationTests {

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
    void pendingOrderIsAcceptedUsingPathIdAndOnlyStateAndUpdateTimeChange() throws Exception {
        Map<String, Object> before = row(101);
        Map<String, Object> otherOrder = row(105);
        Map<String, Object> otherStore = row(201);
        LocalDateTime beforeCall = LocalDateTime.now().minusSeconds(1);
        mockMvc.perform(request(staffToken(), 101))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        assertEquals(2, statusOf(101));
        LocalDateTime updated = jdbcTemplate.queryForObject("select update_time from t_orders where id = 101", LocalDateTime.class);
        assertNotNull(updated);
        assertFalse(updated.isBefore(beforeCall));
        assertEquals(otherFields(before), otherFields(row(101)));
        assertEquals(otherOrder, row(105));
        assertEquals(otherStore, row(201));
    }

    @Test
    void queryParametersCannotReplacePathIdStoreOrTargetState() throws Exception {
        mockMvc.perform(request(staffToken(), 101).param("id", "201").param("storeId", "2").param("status", "4"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(2, statusOf(101));
        assertEquals(1, statusOf(201));
    }

    @ParameterizedTest
    @ValueSource(longs = {102, 103, 104})
    void nonPendingStatesAreRejectedWithDocumentedMessage(long id) throws Exception {
        Map<String, Object> before = row(id);
        mockMvc.perform(request(staffToken(), id))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前订单状态不允许接单"));
        assertEquals(before, row(id));
        verify(orderMapper, never()).accept(anyLong(), anyLong(), any(LocalDateTime.class));
    }

    @Test
    void repeatedAcceptanceIsRejectedWithoutRewritingOrder() throws Exception {
        String token = staffToken();
        mockMvc.perform(request(token, 101)).andExpect(jsonPath("$.code").value(0));
        Map<String, Object> accepted = row(101);
        mockMvc.perform(request(token, 101))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前订单状态不允许接单"));
        assertEquals(accepted, row(101));
        verify(orderMapper, times(1)).accept(anyLong(), anyLong(), any(LocalDateTime.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {201, 999})
    void foreignOrMissingOrderCannotBeAccepted(long id) throws Exception {
        Map<String, Object> otherStore = row(201);
        mockMvc.perform(request(staffToken(), id))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("订单不存在或不属于当前门店"));
        assertEquals(otherStore, row(201));
        assertEquals(1, statusOf(101));
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
    void nonNumericPathIdHasReadableError() throws Exception {
        mockMvc.perform(put("/admin/order/accept/abc").header("Authorization", staffToken()))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：id"));
    }

    @Test
    void bigintOrderIdIsAccepted() throws Exception {
        mockMvc.perform(request(staffToken(), 3000000000L)).andExpect(jsonPath("$.code").value(0));
        assertEquals(2, statusOf(3000000000L));
        assertEquals(1, statusOf(101));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void unboundAdminCannotAcceptOrder(int role) throws Exception {
        String token = tokenFor(Map.of("id", role == 1 ? 1 : 4, "role", role));
        mockMvc.perform(request(token, 101).param("storeId", "1"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前员工未绑定门店，请使用已绑定门店的管理员账号"));
        assertEquals(1, statusOf(101));
    }

    @Test
    void boundSuperAdminUsesJwtStore() throws Exception {
        String token = tokenFor(Map.of("id", 3, "role", 1, "storeId", 1L));
        mockMvc.perform(request(token, 101)).andExpect(jsonPath("$.code").value(0));
        assertEquals(2, statusOf(101));
    }

    @Test
    void secondStoreAcceptsOnlyItsOwnOrder() throws Exception {
        String token = tokenFor(Map.of("id", 5, "role", 2, "storeId", 2L));
        mockMvc.perform(request(token, 201)).andExpect(jsonPath("$.code").value(0));
        assertEquals(2, statusOf(201));
        assertEquals(1, statusOf(101));
    }

    @Test
    void customerCannotAcceptOrder() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 99)), 101))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("无门店管理权限"));
        assertEquals(1, statusOf(101));
    }

    @Test
    void unknownRoleCannotAcceptOrder() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 1, "role", 3)), 101))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(1, statusOf(101));
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(put("/admin/order/accept/101")).andExpect(status().isUnauthorized());
        assertEquals(1, statusOf(101));
    }

    @Test
    void nonexistentStoreCannotAcceptOrder() throws Exception {
        String token = tokenFor(Map.of("id", 2, "role", 2, "storeId", 999L));
        mockMvc.perform(request(token, 101))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("所属门店不存在"));
        assertEquals(1, statusOf(101));
    }

    @Test
    void directServiceCallRejectsNullId() {
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(BizException.class, () -> orderService.accept(null));
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals(1, statusOf(101));
    }

    @ParameterizedTest
    @ValueSource(longs = {102, 103, 104})
    void mapperCannotOverwriteNonPendingStateEvenWithoutJavaCheck(long id) {
        Map<String, Object> before = row(id);
        assertEquals(0, orderMapper.accept(id, 1L, LocalDateTime.now()));
        assertEquals(before, row(id));
    }

    @Test
    void mapperCannotAcceptAnotherStoresOrder() {
        Map<String, Object> before = row(201);
        assertEquals(0, orderMapper.accept(201L, 1L, LocalDateTime.now()));
        assertEquals(before, row(201));
    }

    @Test
    void failedRowCountRollsBackActualDatabaseUpdate() {
        Map<String, Object> before = row(101);
        doAnswer(invocation -> {
            sqlSessionTemplate.update("com.wys.orderingserver.mapper.OrderMapper.accept",
                    Map.of("id", invocation.getArgument(0), "storeId", invocation.getArgument(1),
                            "updateTime", invocation.getArgument(2)));
            return 0;
        }).when(orderMapper).accept(anyLong(), anyLong(), any(LocalDateTime.class));
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(BizException.class, () -> orderService.accept(101L));
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals(before, row(101));
    }

    @Test
    void twoConcurrentAcceptRequestsHaveExactlyOneSuccessfulTransition() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        Callable<String> attempt = () -> {
            ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
            try {
                startTogether.await(5, TimeUnit.SECONDS);
                orderService.accept(101L);
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
            assertEquals(1, Collections.frequency(results, "当前订单状态不允许接单"));
            assertEquals(2, statusOf(101));
            assertEquals(1, statusOf(105));
        } finally {
            executor.shutdownNow();
        }
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("select * from t_orders where id = ?", id);
    }

    private Map<String, Object> otherFields(Map<String, Object> row) {
        Map<String, Object> result = new HashMap<>(row);
        result.remove("STATUS");
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
        return put("/admin/order/accept/" + id).header("Authorization", token);
    }
}
