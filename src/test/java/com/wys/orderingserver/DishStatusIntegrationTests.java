package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.DishMapper;
import com.wys.orderingserver.service.DishService;
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
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 H2 执行真实 SQL，验证上下架范围、重复请求、事务和提交后缓存清理。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dish-status-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql,classpath:dish-add-test.sql,classpath:dish-update-setup.sql"
})
@AutoConfigureMockMvc
@Sql({"/dish-update-data.sql", "/dish-status-data.sql"})
class DishStatusIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private DishService dishService;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private DishMapper dishMapper;
    @MockitoBean private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> tokenOperations;
    private Set<String> cacheKeys;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepareRedis() {
        tokenOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(tokenOperations);
        cacheKeys = new HashSet<>(Set.of("dish:1:11", "dish:1:all", "dish:2:21", "dish:10:11", "unrelated"));
        when(redisTemplate.scan(any(ScanOptions.class))).thenAnswer(invocation -> matchingCursor(invocation.getArgument(0)));
        when(redisTemplate.delete(anyCollection())).thenAnswer(invocation -> {
            Collection<String> keys = invocation.getArgument(0);
            long removed = 0;
            for (String key : keys) {
                if (cacheKeys.remove(key)) removed++;
            }
            return removed;
        });
    }

    @AfterEach
    void identityIsCleared() {
        assertNull(ThreadLocalUtil.get());
    }

    @Test
    void puttingDishOnShelfChangesOnlyTargetStatusAndUpdateTime() throws Exception {
        Map<String, Object> target = row(101);
        Map<String, Object> otherDish = row(102);
        Map<String, Object> otherStore = row(201);
        Map<String, Object> largeIdDish = row(3000000000L);
        mockMvc.perform(request(staffToken(), 101).param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        assertEquals(1, statusOf(101));
        assertNotEquals(target.get("UPDATE_TIME"), row(101).get("UPDATE_TIME"));
        assertEquals(otherFields(target), otherFields(row(101)));
        assertEquals(otherDish, row(102));
        assertEquals(otherStore, row(201));
        assertEquals(largeIdDish, row(3000000000L));
        assertEquals(Set.of("dish:2:21", "dish:10:11", "unrelated"), cacheKeys);
    }

    @Test
    void takingDishOffShelfStoresZero() throws Exception {
        mockMvc.perform(request(staffToken(), 102).param("status", "0"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0, statusOf(102));
        assertEquals(0, statusOf(101));
        assertEquals(1, statusOf(201));
    }

    @Test
    void repeatedTargetStatusRequestsStaySuccessfulAndDoNotToggleOrRewriteDish() throws Exception {
        Map<String, Object> before = row(101);
        String token = staffToken();
        for (int repeat = 0; repeat < 2; repeat++) {
            mockMvc.perform(request(token, 101).param("status", "0"))
                    .andExpect(jsonPath("$.code").value(0));
        }
        assertEquals(before, row(101));
        verify(dishMapper, never()).updateDishStatus(anyLong(), anyInt(), anyLong(), any(LocalDateTime.class));
        verify(redisTemplate, times(2)).scan(any(ScanOptions.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {201, 999})
    void foreignOrMissingDishIsRejectedEvenWithForgedStoreParameter(long id) throws Exception {
        Map<String, Object> otherStore = row(201);
        mockMvc.perform(request(staffToken(), id).param("status", "0").param("storeId", "2"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("菜品不存在或不属于当前门店"));
        assertEquals(otherStore, row(201));
        verify(redisTemplate, never()).scan(any(ScanOptions.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void invalidDishIdIsRejected(long id) throws Exception {
        mockMvc.perform(request(staffToken(), id).param("status", "1"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请选择有效的菜品ID"));
        assertEquals(0, statusOf(101));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 2})
    void invalidStatusIsRejected(int targetStatus) throws Exception {
        mockMvc.perform(request(staffToken(), 101).param("status", String.valueOf(targetStatus)))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("菜品状态只能为0或1"));
        assertEquals(0, statusOf(101));
        verify(redisTemplate, never()).scan(any(ScanOptions.class));
    }

    @Test
    void statusQueryParameterIsRequired() throws Exception {
        mockMvc.perform(request(staffToken(), 101))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("缺少必传参数：status"));
    }

    @ParameterizedTest
    @CsvSource({"abc,1,id", "101,abc,status"})
    void nonNumericParametersHaveReadableError(String id, String targetStatus, String parameter) throws Exception {
        mockMvc.perform(put("/admin/dish/status/" + id).header("Authorization", staffToken()).param("status", targetStatus))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：" + parameter));
    }

    @Test
    void directServiceCallRejectsNullIdAndStatusWithoutUnboxingFailure() {
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(BizException.class, () -> dishService.updateDishStatus(null, 1));
            assertThrows(BizException.class, () -> dishService.updateDishStatus(101L, null));
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals(0, statusOf(101));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void unboundAdminCannotUpdateStatus(int role) throws Exception {
        String token = tokenFor(Map.of("id", role == 1 ? 1 : 4, "role", role));
        mockMvc.perform(request(token, 101).param("status", "1"))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(0, statusOf(101));
        verify(redisTemplate, never()).scan(any(ScanOptions.class));
    }

    @Test
    void customerCannotUpdateStatus() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 99)), 101).param("status", "1"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("无门店管理权限"));
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(put("/admin/dish/status/101").param("status", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonexistentStoreIsRejected() throws Exception {
        String token = tokenFor(Map.of("id", 2, "role", 2, "storeId", 999L));
        mockMvc.perform(request(token, 101).param("status", "1"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("所属门店不存在"));
        assertEquals(0, statusOf(101));
    }

    @Test
    void boundSuperAdminUsesJwtStore() throws Exception {
        String token = tokenFor(Map.of("id", 3, "role", 1, "storeId", 1L));
        mockMvc.perform(request(token, 101).param("status", "1"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1, statusOf(101));
    }

    @Test
    void bigintDishIdCanBeUpdated() throws Exception {
        mockMvc.perform(request(staffToken(), 3000000000L).param("status", "1"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1, statusOf(3000000000L));
        assertEquals(0, statusOf(101));
    }

    @Test
    void secondStoreCanUpdateItsOwnDishAndEvictsOnlyItsCache() throws Exception {
        String token = tokenFor(Map.of("id", 5, "role", 2, "storeId", 2L));
        mockMvc.perform(request(token, 201).param("status", "0"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0, statusOf(201));
        assertEquals(Set.of("dish:1:11", "dish:1:all", "dish:10:11", "unrelated"), cacheKeys);
    }

    @Test
    void mapperEnforcesStoreConditionIndependently() {
        Map<String, Object> before = row(201);
        assertEquals(0, dishMapper.updateDishStatus(201L, 0, 1L, LocalDateTime.now()));
        assertEquals(before, row(201));
    }

    @Test
    void cacheEvictionSeesCommittedStatusFromIndependentConnection() throws Exception {
        AtomicBoolean sawCommittedStatus = new AtomicBoolean();
        when(redisTemplate.scan(any(ScanOptions.class))).thenAnswer(invocation -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("select status from t_dish where id = 101");
                 ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                sawCommittedStatus.set(result.getInt(1) == 1);
            }
            return matchingCursor(invocation.getArgument(0));
        });
        mockMvc.perform(request(staffToken(), 101).param("status", "1"))
                .andExpect(jsonPath("$.code").value(0));
        assertTrue(sawCommittedStatus.get());
        assertFalse(cacheKeys.contains("dish:1:11"));
    }

    @Test
    void unsuccessfulRowCountRollsBackActualStatusWrite() {
        Map<String, Object> before = row(101);
        Set<String> beforeKeys = Set.copyOf(cacheKeys);
        doAnswer(invocation -> {
            Map<String, Object> parameters = Map.of("id", invocation.getArgument(0), "status", invocation.getArgument(1),
                    "storeId", invocation.getArgument(2), "updateTime", invocation.getArgument(3));
            sqlSessionTemplate.update("com.wys.orderingserver.mapper.DishMapper.updateDishStatus", parameters);
            return 0;
        }).when(dishMapper).updateDishStatus(anyLong(), anyInt(), anyLong(), any(LocalDateTime.class));
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(BizException.class, () -> dishService.updateDishStatus(101L, 1));
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals(before, row(101));
        assertEquals(beforeKeys, cacheKeys);
        verify(redisTemplate, never()).scan(any(ScanOptions.class));
    }

    @Test
    void outerRollbackCancelsRegisteredCacheEviction() {
        Map<String, Object> before = row(101);
        Set<String> beforeKeys = Set.copyOf(cacheKeys);
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                dishService.updateDishStatus(101L, 1);
                verify(redisTemplate, never()).scan(any(ScanOptions.class));
                transaction.setRollbackOnly();
            });
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals(before, row(101));
        assertEquals(beforeKeys, cacheKeys);
        verify(redisTemplate, never()).scan(any(ScanOptions.class));
    }

    @SuppressWarnings("unchecked")
    private Cursor<String> matchingCursor(ScanOptions options) {
        String pattern = Objects.requireNonNull(options.getPattern());
        String prefix = pattern.substring(0, pattern.length() - 1);
        Iterator<String> iterator = cacheKeys.stream().filter(key -> key.startsWith(prefix)).toList().iterator();
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenAnswer(invocation -> iterator.hasNext());
        when(cursor.next()).thenAnswer(invocation -> iterator.next());
        return cursor;
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("select * from t_dish where id = ?", id);
    }

    private Map<String, Object> otherFields(Map<String, Object> row) {
        Map<String, Object> result = new HashMap<>(row);
        result.remove("STATUS");
        result.remove("UPDATE_TIME");
        return result;
    }

    private int statusOf(long id) {
        return jdbcTemplate.queryForObject("select status from t_dish where id = ?", Integer.class, id);
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
        return put("/admin/dish/status/" + id).header("Authorization", token);
    }
}
