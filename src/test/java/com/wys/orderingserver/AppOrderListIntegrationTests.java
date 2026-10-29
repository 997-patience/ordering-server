package com.wys.orderingserver;

import com.github.pagehelper.PageHelper;
import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.OrderMapper;
import com.wys.orderingserver.pojo.AppOrderListVO;
import com.wys.orderingserver.pojo.PageBean;
import com.wys.orderingserver.service.AppOrderService;
import com.wys.orderingserver.support.DishRedisStub;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 H2 验证本人订单隔离、关联字段、分页、排序和同一请求内的快照一致性。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:app-order-list-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:order-page-test.sql,classpath:user-login-test.sql"
})
@AutoConfigureMockMvc
@Import(AppOrderListIntegrationTests.PauseConfig.class)
@Sql("/app-order-list-data.sql")
class AppOrderListIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AppOrderService service;
    @Autowired private CountQueryPause queryPause;
    @MockitoSpyBean private OrderMapper mapper;
    @MockitoBean private StringRedisTemplate redisTemplate;
    private DishRedisStub redis;

    @BeforeEach
    void prepare() {
        ThreadLocalUtil.remove();
        PageHelper.clearPage();
        queryPause.clear();
        redis = new DishRedisStub(redisTemplate);
    }

    @AfterEach
    void contextsAreAlwaysCleared() {
        assertNull(ThreadLocalUtil.get());
        assertNull(PageHelper.getLocalPage());
    }

    @Test
    void responseContainsExactlyDocumentedFieldsAndCountsOnlyCurrentUsersOrders() throws Exception {
        mvc.perform(page(1, 1, 2))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].length()").value(9))
                .andExpect(jsonPath("$.data.items[0].id").value(1105))
                .andExpect(jsonPath("$.data.items[0].orderNo").value("USER1-1105"))
                .andExpect(jsonPath("$.data.items[0].storeId").value(1))
                .andExpect(jsonPath("$.data.items[0].storeName").value("一号店"))
                .andExpect(jsonPath("$.data.items[0].amount").value(12.30))
                .andExpect(jsonPath("$.data.items[0].status").value(1))
                .andExpect(jsonPath("$.data.items[0].pickupType").value(2))
                .andExpect(jsonPath("$.data.items[0].pickupTime").value("2026-10-23 11:30:00"))
                .andExpect(jsonPath("$.data.items[0].createTime").value("2026-10-23 10:00:00"))
                .andExpect(jsonPath("$.data.items[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].payStatus").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].remark").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].storeName").value("已打烊门店"))
                .andExpect(jsonPath("$.data.items[1].pickupTime").isEmpty());
        verify(mapper).listByUserId(1L, null);
        verify(mapper, never()).list(any(), any(), any(), any());
    }

    @Test
    void pagesUseCreationTimeDescendingAndStableIdDescendingForSameSecond() throws Exception {
        mvc.perform(page(1, 1, 2)).andExpect(jsonPath("$.data.items[*].id").value(contains(1105, 1104)));
        mvc.perform(page(1, 2, 2)).andExpect(jsonPath("$.data.items[*].id").value(contains(1103, 1102)));
        mvc.perform(page(1, 3, 2)).andExpect(jsonPath("$.data.items[*].id").value(contains(1101, 3000000000L)));
    }

    @ParameterizedTest
    @CsvSource({"1,3,1105", "2,1,1102", "3,1,1103", "4,1,1104"})
    void everyStatusFilterAppliesBeforeCountingAndPaging(int status, int total, int firstId) throws Exception {
        mvc.perform(page(1, 1, 10).param("status", String.valueOf(status)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(total))
                .andExpect(jsonPath("$.data.items.length()").value(total))
                .andExpect(jsonPath("$.data.items[0].id").value(firstId));
    }

    @Test
    void statusFilteredSecondPageKeepsFilteredTotalAndLongId() throws Exception {
        mvc.perform(page(1, 2, 2).param("status", "1"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(3000000000L));
    }

    @Test
    void secondUserSeesOnlyOwnOrdersIncludingOtherStores() throws Exception {
        mvc.perform(page(2, 1, 10))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[*].id").value(contains(2202, 2201)))
                .andExpect(jsonPath("$.data.items[*].storeName").value(contains("二号店", "一号店")));
    }

    @Test
    void queryParametersCannotOverrideUserOrRestrictToAnAdminStore() throws Exception {
        mvc.perform(page(1, 1, 10).param("userId", "2").param("storeId", "2")
                        .param("role", "1").param("type", "admin"))
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.items[*].id").value(contains(1105, 1104, 1103, 1102, 1101, 3000000000L)));
    }

    @Test
    void userWithoutOrdersGetsZeroAndEmptyArray() throws Exception {
        mvc.perform(page(3, 1, 5))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void validStatusWithNoMatchingOrdersGetsEmptyPage() throws Exception {
        mvc.perform(page(2, 1, 5).param("status", "3"))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void emptyOptionalStatusMeansAllStatuses() throws Exception {
        mvc.perform(page(1, 1, 10).param("status", ""))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.total").value(6));
    }

    @Test
    void beyondLastPagePreservesProjectsExistingReasonableSetting() throws Exception {
        mvc.perform(page(1, Integer.MAX_VALUE, 2))
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.items[*].id").value(contains(1101, 3000000000L)));
    }

    @Test
    void storeNameComesFromCurrentStoreJoinAndClosedStoreHistoryRemainsVisible() throws Exception {
        jdbc.update("update t_store set name='修改后的门店名',status=0 where id=1");
        mvc.perform(page(1, 1, 10))
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.items[0].storeName").value("修改后的门店名"))
                .andExpect(jsonPath("$.data.items[1].storeName").value("已打烊门店"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 5})
    void invalidStatusIsRejectedBeforeQuery(int status) throws Exception {
        mvc.perform(page(1, 1, 5).param("status", String.valueOf(status)))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("订单状态只能为1、2、3或4"));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @CsvSource({"0,5", "-1,5", "1,0", "1,-1"})
    void invalidPaginationIsRejectedBeforeQuery(int pageNum, int pageSize) throws Exception {
        mvc.perform(page(1, pageNum, pageSize)).andExpect(jsonPath("$.code").value(1));
        verifyNoInteractions(mapper);
    }

    @Test
    void bothPaginationParametersAreRequired() throws Exception {
        mvc.perform(get("/app/order/list").header("Authorization", token(1)).param("pageNum", "1"))
                .andExpect(jsonPath("$.message").value("缺少必传参数：pageSize"));
        mvc.perform(get("/app/order/list").header("Authorization", token(1)).param("pageSize", "5"))
                .andExpect(jsonPath("$.message").value("缺少必传参数：pageNum"));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @CsvSource({"pageNum,abc", "pageNum,1.5", "pageNum,2147483648", "pageSize,abc", "status,abc", "status,1.5"})
    void malformedQueryValuesHaveReadableTypeErrors(String parameter, String value) throws Exception {
        MockHttpServletRequestBuilder request = get("/app/order/list").header("Authorization", token(1));
        if (!parameter.equals("pageNum")) request.param("pageNum", "1");
        if (!parameter.equals("pageSize")) request.param("pageSize", "5");
        mvc.perform(request.param(parameter, value))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：" + parameter));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL,5,NULL", "0,5,NULL", "1,NULL,NULL", "1,0,NULL", "1,5,0", "1,5,5"}, nullValues = "NULL")
    void serviceAlsoValidatesDirectCalls(Integer pageNum, Integer pageSize, Integer status) {
        assertThrows(BizException.class, () -> asUser(1, () -> service.page(pageNum, pageSize, status)));
        verifyNoInteractions(mapper);
    }

    @Test
    void missingRevokedAndAdminTokensCannotAccessList() throws Exception {
        mvc.perform(get("/app/order/list").param("pageNum", "1").param("pageSize", "5"))
                .andExpect(status().isUnauthorized());
        String revoked = token(1); redis.values.remove(revoked);
        mvc.perform(get("/app/order/list").header("Authorization", revoked).param("pageNum", "1").param("pageSize", "5"))
                .andExpect(status().isUnauthorized());
        for (Map<String, Object> claims : List.of(
                Map.<String, Object>of("id", 2, "role", 2, "storeId", 1, "type", "admin"),
                Map.<String, Object>of("id", 1, "role", 1))) {
            String admin = JwtUtil.genToken(claims); redis.values.put(admin, admin);
            mvc.perform(get("/app/order/list").header("Authorization", admin).param("pageNum", "1").param("pageSize", "5"))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void directCallsRequireCustomerIdentityBeforeStartingPagination() {
        assertThrows(BizException.class, () -> service.page(1, 5, null));
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "type", "admin", "storeId", 1));
        try { assertThrows(BizException.class, () -> service.page(1, 5, null)); }
        finally { ThreadLocalUtil.remove(); }
        verifyNoInteractions(mapper);
    }

    @Test
    void queryFailureClearsPaginationAndDoesNotPageTheNextMapperQuery() {
        doThrow(new DataAccessResourceFailureException("模拟订单列表查询失败"))
                .when(mapper).listByUserId(1L, null);
        assertThrows(DataAccessResourceFailureException.class, () -> asUser(1, () -> service.page(1, 1, null)));
        assertNull(PageHelper.getLocalPage());
        assertEquals(5, mapper.list(1L, null, null, null).size());
    }

    @Test
    void pageCountAndItemsShareSnapshotWhenAnotherOrderIsInsertedBetweenQueries() throws Exception {
        assertSnapshotDuringChange(null, () -> jdbc.update(
                "insert into t_orders(id,order_no,user_id,store_id,amount,status,pay_status,pickup_type,create_time,update_time) " +
                "values(1106,'USER1-NEW',1,1,10.00,1,0,1,'2026-10-24 10:00:00','2026-10-24 10:00:00')"), 6);
        PageBean<AppOrderListVO> next = asUser(1, () -> service.page(1, 20, null));
        assertEquals(7L, next.getTotal());
        assertEquals(1106L, next.getItems().getFirst().getId());
    }

    @Test
    void statusFilterUsesSameSnapshotWhenAnotherRequestCancelsAnOrderBetweenQueries() throws Exception {
        assertSnapshotDuringChange(1, () -> jdbc.update("update t_orders set status=4 where id=1105"), 3);
        PageBean<AppOrderListVO> next = asUser(1, () -> service.page(1, 20, 1));
        assertEquals(2L, next.getTotal());
        assertEquals(List.of(1101L, 3000000000L), next.getItems().stream().map(AppOrderListVO::getId).toList());
    }

    private void assertSnapshotDuringChange(Integer status, Runnable change, long originalCount) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch countFinished = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        queryPause.arm(countFinished, release);
        try {
            Future<PageBean<AppOrderListVO>> future = pool.submit(() -> asUser(1, () -> service.page(1, 20, status)));
            assertTrue(countFinished.await(3, TimeUnit.SECONDS));
            change.run();
            release.countDown();
            PageBean<AppOrderListVO> result = future.get(5, TimeUnit.SECONDS);
            assertEquals(originalCount, result.getTotal());
            assertEquals(originalCount, result.getItems().size());
            assertEquals(1105L, result.getItems().getFirst().getId());
            if (status != null) assertTrue(result.getItems().stream().allMatch(item -> status.equals(item.getStatus())));
        } finally {
            release.countDown(); queryPause.clear();
            pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private MockHttpServletRequestBuilder page(int userId, int pageNum, int pageSize) {
        return get("/app/order/list").header("Authorization", token(userId))
                .param("pageNum", String.valueOf(pageNum)).param("pageSize", String.valueOf(pageSize));
    }

    private String token(int userId) {
        String phone = switch (userId) { case 1 -> "13800000001"; case 2 -> "13900000002"; default -> "13700000003"; };
        String token = JwtUtil.genToken(Map.of("id", userId, "phone", phone, "type", "user"));
        redis.values.put(token, token); return token;
    }

    private <T> T asUser(int userId, Supplier<T> action) {
        ThreadLocalUtil.set(Map.of("id", userId, "type", "user"));
        try { return action.get(); }
        finally { ThreadLocalUtil.remove(); assertNull(PageHelper.getLocalPage()); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PauseConfig {
        @Bean CountQueryPause countQueryPause() { return new CountQueryPause(); }
    }

    /** 测试在 count 完成后插入并发写入，保证真正覆盖两条 SQL 之间的窗口。 */
    @Intercepts(@Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}))
    public static class CountQueryPause implements Interceptor {
        private record Pause(CountDownLatch counted, CountDownLatch release) {}
        private final AtomicReference<Pause> next = new AtomicReference<>();
        void arm(CountDownLatch counted, CountDownLatch release) { next.set(new Pause(counted, release)); }
        void clear() { next.set(null); }
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            String sql = ((StatementHandler) invocation.getTarget()).getBoundSql().getSql().toLowerCase(Locale.ROOT);
            Object result = invocation.proceed();
            if (sql.contains("t_orders") && sql.matches("(?s).*\\bcount\\s*\\(.*")) {
                Pause pause = next.getAndSet(null);
                if (pause != null) {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                    pause.counted.countDown();
                    assertTrue(pause.release.await(4, TimeUnit.SECONDS));
                }
            }
            return result;
        }
    }
}
