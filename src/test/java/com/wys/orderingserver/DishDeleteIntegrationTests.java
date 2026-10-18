package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.CartMapper;
import com.wys.orderingserver.mapper.DishMapper;
import com.wys.orderingserver.service.DishService;
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
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 H2 验证删除、购物车清理和历史快照，Redis 使用模拟对象，不连接业务数据库。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dish-delete-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql,classpath:dish-add-test.sql,classpath:dish-update-setup.sql,classpath:dish-delete-setup.sql"
})
@AutoConfigureMockMvc
@Sql({"/dish-delete-reset.sql", "/dish-update-data.sql", "/dish-status-data.sql", "/dish-delete-data.sql"})
class DishDeleteIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private DishService dishService;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private DishMapper dishMapper;
    @MockitoSpyBean private CartMapper cartMapper;
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
    void deletionRemovesOnlyTargetAndItsCartReferencesAndPreservesHistory() throws Exception {
        List<Map<String, Object>> orders = rows("t_orders");
        List<Map<String, Object>> details = rows("t_order_detail");
        Map<String, Object> otherDish = row("t_dish", 102);
        Map<String, Object> otherStore = row("t_dish", 201);
        Map<String, Object> largeIdDish = row("t_dish", 3000000000L);
        mockMvc.perform(request(staffToken(), 101))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        assertEquals(0, dishCount(101));
        assertEquals(0, cartCount(101));
        assertEquals(otherDish, row("t_dish", 102));
        assertEquals(otherStore, row("t_dish", 201));
        assertEquals(largeIdDish, row("t_dish", 3000000000L));
        assertEquals(3L, jdbcTemplate.queryForObject("select count(*) from t_cart", Long.class));
        assertEquals(orders, rows("t_orders"));
        assertEquals(details, rows("t_order_detail"));
        assertEquals(Set.of("dish:2:21", "dish:10:11", "unrelated"), cacheKeys);
    }

    @Test
    void onShelfDishCanBeDeletedBecauseDocumentDoesNotRequireTakingItOffShelfFirst() throws Exception {
        mockMvc.perform(request(staffToken(), 102)).andExpect(jsonPath("$.code").value(0));
        assertEquals(0, dishCount(102));
        assertEquals(0, cartCount(102));
        assertEquals(1, dishCount(101));
    }

    @Test
    void dishWithoutCartReferencesCanBeDeleted() throws Exception {
        jdbcTemplate.update("delete from t_cart where dish_id = 101");
        mockMvc.perform(request(staffToken(), 101)).andExpect(jsonPath("$.code").value(0));
        assertEquals(0, dishCount(101));
        assertEquals(3L, jdbcTemplate.queryForObject("select count(*) from t_order_detail", Long.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {201, 999})
    void foreignOrMissingDishCannotBeDeletedUsingForgedStoreParameter(long id) throws Exception {
        Map<String, List<Map<String, Object>>> before = snapshot();
        mockMvc.perform(request(staffToken(), id).param("storeId", "2"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("菜品不存在或不属于当前门店"));
        assertEquals(before, snapshot());
        verify(redisTemplate, never()).scan(any(ScanOptions.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void invalidIdIsRejected(long id) throws Exception {
        Map<String, List<Map<String, Object>>> before = snapshot();
        mockMvc.perform(request(staffToken(), id))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请选择有效的菜品ID"));
        assertEquals(before, snapshot());
    }

    @Test
    void idQueryParameterIsRequired() throws Exception {
        mockMvc.perform(delete("/admin/dish").header("Authorization", staffToken()))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("缺少必传参数：id"));
    }

    @Test
    void nonNumericIdHasReadableError() throws Exception {
        mockMvc.perform(delete("/admin/dish").header("Authorization", staffToken()).param("id", "abc"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：id"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void unboundAdminCannotChooseStoreWithQueryParameter(int role) throws Exception {
        Map<String, List<Map<String, Object>>> before = snapshot();
        String token = tokenFor(Map.of("id", role == 1 ? 1 : 4, "role", role));
        mockMvc.perform(request(token, 101).param("storeId", "1"))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(before, snapshot());
    }

    @Test
    void boundSuperAdminCanDeleteWithinJwtStore() throws Exception {
        String token = tokenFor(Map.of("id", 3, "role", 1, "storeId", 1L));
        mockMvc.perform(request(token, 101)).andExpect(jsonPath("$.code").value(0));
        assertEquals(0, dishCount(101));
    }

    @Test
    void customerCannotDeleteDish() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 99)), 101))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("无门店管理权限"));
        assertEquals(1, dishCount(101));
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(delete("/admin/dish").param("id", "101"))
                .andExpect(status().isUnauthorized());
        assertEquals(1, dishCount(101));
    }

    @Test
    void nonexistentStoreCannotDeleteDish() throws Exception {
        String token = tokenFor(Map.of("id", 2, "role", 2, "storeId", 999L));
        mockMvc.perform(request(token, 101))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("所属门店不存在"));
        assertEquals(1, dishCount(101));
    }

    @Test
    void bigintDishIdCanBeDeleted() throws Exception {
        mockMvc.perform(request(staffToken(), 3000000000L)).andExpect(jsonPath("$.code").value(0));
        assertEquals(0, dishCount(3000000000L));
        assertEquals(0, cartCount(3000000000L));
        assertEquals(1, dishCount(101));
    }

    @Test
    void secondStoreDeletionPreservesFirstStoreAndAllOrderSnapshots() throws Exception {
        List<Map<String, Object>> details = rows("t_order_detail");
        String token = tokenFor(Map.of("id", 5, "role", 2, "storeId", 2L));
        mockMvc.perform(request(token, 201)).andExpect(jsonPath("$.code").value(0));
        assertEquals(0, dishCount(201));
        assertEquals(0, cartCount(201));
        assertEquals(1, dishCount(101));
        assertEquals(2, cartCount(101));
        assertEquals(details, rows("t_order_detail"));
        assertEquals(Set.of("dish:1:11", "dish:1:all", "dish:10:11", "unrelated"), cacheKeys);
    }

    @Test
    void directMapperCallsCannotDeleteAnotherStoresDishOrCart() {
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertEquals(0, dishMapper.deleteDish(201L, 1L));
        assertEquals(0, cartMapper.deleteByDishIdAndStoreId(101L, 2L));
        assertEquals(before, snapshot());
    }

    @Test
    void directServiceCallRejectsNullId() {
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(BizException.class, () -> dishService.deleteDish(null));
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals(1, dishCount(101));
    }

    @Test
    void repeatedDeletionReportsMissingDishWithoutAffectingOtherData() throws Exception {
        String token = staffToken();
        mockMvc.perform(request(token, 101)).andExpect(jsonPath("$.code").value(0));
        Map<String, List<Map<String, Object>>> afterFirst = snapshot();
        mockMvc.perform(request(token, 101)).andExpect(jsonPath("$.code").value(1));
        assertEquals(afterFirst, snapshot());
        verify(redisTemplate, times(1)).scan(any(ScanOptions.class));
    }

    @Test
    void cacheIsClearedOnlyAfterDishAndCartDeletionCommit() throws Exception {
        AtomicBoolean sawCommittedDeletion = new AtomicBoolean();
        when(redisTemplate.scan(any(ScanOptions.class))).thenAnswer(invocation -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "select (select count(*) from t_dish where id = 101) + (select count(*) from t_cart where dish_id = 101)");
                 ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                sawCommittedDeletion.set(result.getInt(1) == 0);
            }
            return matchingCursor(invocation.getArgument(0));
        });
        mockMvc.perform(request(staffToken(), 101)).andExpect(jsonPath("$.code").value(0));
        assertTrue(sawCommittedDeletion.get());
    }

    @Test
    void failedDishDeleteCountRollsBackDishAndCartChanges() {
        Map<String, List<Map<String, Object>>> before = snapshot();
        Set<String> beforeKeys = Set.copyOf(cacheKeys);
        doAnswer(invocation -> {
            sqlSessionTemplate.delete("com.wys.orderingserver.mapper.DishMapper.deleteDish",
                    Map.of("id", invocation.getArgument(0), "storeId", invocation.getArgument(1)));
            return 0;
        }).when(dishMapper).deleteDish(anyLong(), anyLong());
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(BizException.class, () -> dishService.deleteDish(101L));
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals(before, snapshot());
        assertEquals(beforeKeys, cacheKeys);
        verify(redisTemplate, never()).scan(any(ScanOptions.class));
    }

    @Test
    void cartCleanupFailureRollsBackItsPartialDeletion() {
        Map<String, List<Map<String, Object>>> before = snapshot();
        doAnswer(invocation -> {
            sqlSessionTemplate.delete("com.wys.orderingserver.mapper.CartMapper.deleteByDishIdAndStoreId",
                    Map.of("dishId", invocation.getArgument(0), "storeId", invocation.getArgument(1)));
            throw new DataIntegrityViolationException("模拟购物车清理后异常");
        }).when(cartMapper).deleteByDishIdAndStoreId(anyLong(), anyLong());
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(DataIntegrityViolationException.class, () -> dishService.deleteDish(101L));
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals(before, snapshot());
        verify(dishMapper, never()).deleteDish(anyLong(), anyLong());
        verify(redisTemplate, never()).scan(any(ScanOptions.class));
    }

    @Test
    void outerRollbackRestoresDishAndCartAndCancelsCacheEviction() {
        Map<String, List<Map<String, Object>>> before = snapshot();
        Set<String> beforeKeys = Set.copyOf(cacheKeys);
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                dishService.deleteDish(101L);
                verify(redisTemplate, never()).scan(any(ScanOptions.class));
                transaction.setRollbackOnly();
            });
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals(before, snapshot());
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

    private List<Map<String, Object>> rows(String table) {
        return jdbcTemplate.queryForList("select * from " + table + " order by id");
    }

    private Map<String, Object> row(String table, long id) {
        return jdbcTemplate.queryForMap("select * from " + table + " where id = ?", id);
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        return Map.of("dish", rows("t_dish"), "cart", rows("t_cart"), "orders", rows("t_orders"), "details", rows("t_order_detail"));
    }

    private int dishCount(long id) {
        return jdbcTemplate.queryForObject("select count(*) from t_dish where id = ?", Integer.class, id);
    }

    private int cartCount(long id) {
        return jdbcTemplate.queryForObject("select count(*) from t_cart where dish_id = ?", Integer.class, id);
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
        return delete("/admin/dish").header("Authorization", token).param("id", String.valueOf(id));
    }
}
