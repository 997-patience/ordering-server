package com.wys.orderingserver;

import com.github.pagehelper.PageHelper;
import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.OrderMapper;
import com.wys.orderingserver.pojo.TodayStatsVO;
import com.wys.orderingserver.service.StatsService;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 H2 验证真实统计 SQL、跨天口径、上海日期、门店隔离和同一报表的读取一致性。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:stats-today-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:order-page-test.sql"
})
@AutoConfigureMockMvc
class StatsTodayIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private StatsService statsService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @MockitoSpyBean private OrderMapper orderMapper;
    @MockitoBean private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> tokenOperations;
    private LocalDateTime begin;
    private LocalDateTime end;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepareOrdersAndRedis() {
        tokenOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(tokenOperations);
        begin = LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay();
        end = begin.plusDays(1);
        // 只操作独立 H2 数据库，按实际业务日期生成数据，测试不会随日期过期。
        jdbcTemplate.update("delete from t_orders");
        jdbcTemplate.update("delete from t_employee where id = 6");
        jdbcTemplate.update("insert into t_employee values (6, 'empty-store', 3, 2, 1)");
        insert(101, 1, "10.00", 1, begin, null);
        insert(102, 1, "20.00", 2, begin.plusHours(2), null);
        insert(103, 1, "30.10", 3, begin.plusHours(3), begin.plusHours(4));
        insert(104, 1, "40.00", 4, begin.plusHours(4), null);
        insert(105, 1, "50.20", 3, begin.plusHours(5), end);
        insert(106, 1, "60.00", 1, end.minusNanos(1000), null);
        insert(107, 1, "70.30", 3, begin.minusDays(1).plusHours(1), begin);
        insert(108, 1, "80.40", 3, begin.minusDays(1).plusHours(2), end.minusNanos(1000));
        insert(109, 1, "90.00", 3, begin.minusDays(1), begin.minusNanos(1000));
        insert(110, 1, "100.00", 1, end, null);
        insert(201, 2, "999.99", 3, begin.plusHours(1), begin.plusHours(2));
        insert(202, 2, "222.00", 1, begin.plusHours(1), null);
        insert(203, 2, "333.00", 4, begin.plusHours(3), null);
    }

    @AfterEach
    void requestContextsAreCleared() {
        assertNull(ThreadLocalUtil.get());
        assertNull(PageHelper.getLocalPage());
    }

    @Test
    void endpointReturnsFiveNumbersForOwnStoreWithoutChangingOrders() throws Exception {
        List<Map<String, Object>> before = allOrders();
        mockMvc.perform(request(staffToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.aMapWithSize(5)))
                .andExpect(jsonPath("$.data.orderCount").value(6))
                .andExpect(jsonPath("$.data.pendingCount").value(2))
                .andExpect(jsonPath("$.data.finishedCount").value(2))
                .andExpect(jsonPath("$.data.canceledCount").value(1))
                .andExpect(jsonPath("$.data.turnover").value(180.80));
        assertEquals(before, allOrders());
    }

    @Test
    void queryParametersCannotChangeStoreDayOrStatus() throws Exception {
        mockMvc.perform(request(staffToken()).param("storeId", "2").param("status", "4")
                        .param("beginTime", "2000-01-01 00:00:00").param("endTime", "2099-01-01 00:00:00"))
                .andExpect(jsonPath("$.data.orderCount").value(6))
                .andExpect(jsonPath("$.data.pendingCount").value(2))
                .andExpect(jsonPath("$.data.turnover").value(180.80));
    }

    @Test
    void secondStoreHasItsOwnCountsAndTurnover() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 5, "role", 2, "storeId", 2L))))
                .andExpect(jsonPath("$.data.orderCount").value(3))
                .andExpect(jsonPath("$.data.pendingCount").value(1))
                .andExpect(jsonPath("$.data.finishedCount").value(1))
                .andExpect(jsonPath("$.data.canceledCount").value(1))
                .andExpect(jsonPath("$.data.turnover").value(999.99));
    }

    @Test
    void storeWithoutOrdersReturnsZeroNumbers() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 6, "role", 2, "storeId", 3L))))
                .andExpect(jsonPath("$.data.orderCount").value(0))
                .andExpect(jsonPath("$.data.pendingCount").value(0))
                .andExpect(jsonPath("$.data.finishedCount").value(0))
                .andExpect(jsonPath("$.data.canceledCount").value(0))
                .andExpect(jsonPath("$.data.turnover").value(0));
    }

    @Test
    void emptyDatabaseStillProducesNonNullCountsAndTwoDecimalMoney() {
        jdbcTemplate.update("delete from t_orders");
        TodayStatsVO stats = serviceStats();
        assertEquals(0L, stats.getOrderCount());
        assertEquals(0L, stats.getPendingCount());
        assertEquals(0L, stats.getFinishedCount());
        assertEquals(0L, stats.getCanceledCount());
        assertEquals(new BigDecimal("0.00"), stats.getTurnover());
    }

    @ParameterizedTest
    @CsvSource({"1,2", "2,1", "3,2", "4,1"})
    void mapperCountsCurrentStateAmongOrdersCreatedToday(int status, long expected) {
        assertEquals(expected, orderMapper.countByCreateTime(1L, status, begin, end));
    }

    @Test
    void countIncludesMidnightAndLastFractionalSecondButExcludesTomorrow() {
        jdbcTemplate.update("delete from t_orders");
        insert(1, 1, "1.00", 1, begin.minusNanos(1000), null);
        insert(2, 1, "1.00", 1, begin, null);
        insert(3, 1, "1.00", 1, end.minusNanos(1000), null);
        insert(4, 1, "1.00", 1, end, null);
        assertEquals(2L, serviceStats().getOrderCount());
    }

    @Test
    void turnoverUsesCompletionDateInsteadOfCreationDateAndKeepsDecimalPrecision() {
        jdbcTemplate.update("delete from t_orders");
        insert(1, 1, "0.10", 3, begin.minusDays(1), begin);
        insert(2, 1, "0.20", 3, begin.minusDays(1), end.minusNanos(1000));
        insert(3, 1, "999.00", 3, begin, end);
        insert(4, 1, "777.00", 3, begin.minusDays(1), begin.minusNanos(1000));
        TodayStatsVO stats = serviceStats();
        assertEquals(1L, stats.getOrderCount());
        assertEquals(1L, stats.getFinishedCount());
        assertEquals(new BigDecimal("0.30"), stats.getTurnover());
    }

    @Test
    void todayCanHaveTurnoverWithoutAnyOrdersCreatedToday() {
        jdbcTemplate.update("delete from t_orders");
        insert(1, 1, "25.50", 3, begin.minusDays(1), begin.plusHours(1));
        TodayStatsVO stats = serviceStats();
        assertEquals(0L, stats.getOrderCount());
        assertEquals(0L, stats.getFinishedCount());
        assertEquals(new BigDecimal("25.50"), stats.getTurnover());
    }

    @Test
    void turnoverRequiresCompletedStatusAndNonNullFinishTime() {
        jdbcTemplate.update("delete from t_orders");
        insert(1, 1, "10.00", 1, begin, begin.plusHours(1));
        insert(2, 1, "20.00", 2, begin, begin.plusHours(1));
        insert(3, 1, "30.00", 4, begin, begin.plusHours(1));
        insert(4, 1, "40.00", 3, begin, null);
        assertEquals(new BigDecimal("0.00"), serviceStats().getTurnover());
    }

    @Test
    void nullStoreInMapperCannotExposeAllStores() {
        assertEquals(0L, orderMapper.countByCreateTime(null, null, begin, end));
        assertEquals(0, orderMapper.sumTurnover(null, begin, end).compareTo(BigDecimal.ZERO));
    }

    @Test
    void boundSuperAdminStillUsesJwtStoreForThisEndpoint() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 3, "role", 1, "storeId", 1L))).param("storeId", "2"))
                .andExpect(jsonPath("$.data.orderCount").value(6))
                .andExpect(jsonPath("$.data.turnover").value(180.80));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void unboundAdminCannotChooseStoreFromRequest(int role) throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", role == 1 ? 1 : 4, "role", role))).param("storeId", "1"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前员工未绑定门店，请使用已绑定门店的管理员账号"));
    }

    @Test
    void customerCannotReadStoreDashboard() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 99))))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("无门店管理权限"));
    }

    @Test
    void unknownRoleCannotReadStoreDashboard() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 2, "role", 3, "storeId", 1L))))
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void missingTokenReturnsEmpty401() throws Exception {
        mockMvc.perform(get("/admin/stats/today"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    @Test
    void tokenAbsentFromRedisReturns401() throws Exception {
        String token = JwtUtil.genToken(Map.of("id", 2, "role", 2, "storeId", 1L));
        mockMvc.perform(request(token)).andExpect(status().isUnauthorized());
    }

    @Test
    void directServiceCallAlsoRequiresLogin() {
        assertThrows(BizException.class, () -> statsService.getStats());
    }

    @Test
    void businessDayUsesShanghaiEvenWhenJvmDefaultZoneChanges() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            assertEquals(6L, serviceStats().getOrderCount());
            verify(orderMapper).countByCreateTime(1L, null, begin, end);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void multipleQueriesUseSameSnapshotWhileAnotherTransactionCompletesOrder() throws Exception {
        doAnswer(invocation -> {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("storeId", invocation.getArgument(0));
            parameters.put("status", null);
            parameters.put("beginTime", invocation.getArgument(2));
            parameters.put("endTime", invocation.getArgument(3));
            Long count = sqlSessionTemplate.selectOne("com.wys.orderingserver.mapper.OrderMapper.countByCreateTime", parameters);
            ExecutorService writer = Executors.newSingleThreadExecutor();
            try {
                // 另一条连接提交修改，后续状态数量和营业额应仍读取同一报表快照。
                assertEquals(1, writer.submit(() -> jdbcTemplate.update(
                        "update t_orders set status = 3, finish_time = ? where id = 101", begin.plusHours(1)))
                        .get(5, TimeUnit.SECONDS));
            } finally {
                writer.shutdownNow();
            }
            return count;
        }).when(orderMapper).countByCreateTime(eq(1L), isNull(), any(LocalDateTime.class), any(LocalDateTime.class));

        TodayStatsVO stats = serviceStats();
        assertEquals(6L, stats.getOrderCount());
        assertEquals(2L, stats.getPendingCount());
        assertEquals(2L, stats.getFinishedCount());
        assertEquals(new BigDecimal("180.80"), stats.getTurnover());
        assertEquals(3, jdbcTemplate.queryForObject("select status from t_orders where id = 101", Integer.class));
    }

    private void insert(long id, long storeId, String amount, int status, LocalDateTime created, LocalDateTime finished) {
        jdbcTemplate.update("insert into t_orders(id, order_no, user_id, store_id, amount, status, finish_time, create_time, update_time) " +
                        "values (?, ?, 99, ?, ?, ?, ?, ?, ?)",
                id, "STATS-" + id, storeId, new BigDecimal(amount), status, finished, created, created);
    }

    private TodayStatsVO serviceStats() {
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            return statsService.getStats();
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    private List<Map<String, Object>> allOrders() {
        return jdbcTemplate.queryForList("select * from t_orders order by id");
    }

    private String staffToken() {
        return tokenFor(Map.of("id", 2, "role", 2, "storeId", 1L));
    }

    private String tokenFor(Map<String, Object> claims) {
        String token = JwtUtil.genToken(claims);
        when(tokenOperations.get(token)).thenReturn(token);
        return token;
    }

    private MockHttpServletRequestBuilder request(String token) {
        return get("/admin/stats/today").header("Authorization", token);
    }
}
