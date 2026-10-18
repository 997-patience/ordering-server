package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.DishMapper;
import com.wys.orderingserver.pojo.Dish;
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
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 H2 SQL 验证更新范围、可选字段和回滚；模拟 Redis 验证提交后按门店清缓存。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dish-update-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql,classpath:dish-add-test.sql,classpath:dish-update-setup.sql"
})
@AutoConfigureMockMvc
@Sql("/dish-update-data.sql")
class DishUpdateIntegrationTests {

    private static final String BODY = "{\"id\":101,\"name\":\"  宫保鸡丁大份  \",\"categoryId\":11,\"price\":42}";

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
        cacheKeys = new HashSet<>(Set.of("dish:1:11", "dish:1:12", "dish:1:all", "dish:2:21", "dish:10:11", "unrelated"));
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
    void requiredFieldsUpdateOnlyTargetAndPreserveOmittedOptionalFields() throws Exception {
        Map<String, Object> otherDish = row(102);
        Map<String, Object> otherStore = row(201);
        mockMvc.perform(request(staffToken()).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        assertEquals("宫保鸡丁大份", field("name", String.class));
        assertEquals(new BigDecimal("42.00"), field("price", BigDecimal.class));
        assertEquals("/upload/old.jpg", field("image", String.class));
        assertEquals("原描述", field("description", String.class));
        assertEquals(0, field("status", Integer.class));
        assertEquals(7, field("sort", Integer.class));
        assertEquals(LocalDateTime.of(2000, 1, 1, 0, 0), field("create_time", LocalDateTime.class));
        assertNotEquals(field("create_time", LocalDateTime.class), field("update_time", LocalDateTime.class));
        assertEquals(otherDish, row(102));
        assertEquals(otherStore, row(201));
        assertEquals(3L, jdbcTemplate.queryForObject("select count(*) from t_dish", Long.class));
    }

    @Test
    void explicitFieldsAndNewCategoryAreStoredIncludingZeros() throws Exception {
        mockMvc.perform(request(staffToken()).content("""
                {"id":101,"name":"新菜名","categoryId":3000000000,"price":0,
                 "image":" /upload/new.jpg ","description":"新描述","status":1,"sort":0}
                """))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(3000000000L, field("category_id", Long.class));
        assertEquals(new BigDecimal("0.00"), field("price", BigDecimal.class));
        assertEquals("/upload/new.jpg", field("image", String.class));
        assertEquals("新描述", field("description", String.class));
        assertEquals(1, field("status", Integer.class));
        assertEquals(0, field("sort", Integer.class));
    }

    @Test
    void explicitEmptyStringsClearImageAndDescription() throws Exception {
        mockMvc.perform(request(staffToken()).content("""
                {"id":101,"name":"新菜名","categoryId":11,"price":42,"image":"","description":""}
                """))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("", field("image", String.class));
        assertEquals("", field("description", String.class));
    }

    @Test
    void bodyCannotMoveDishOrOverrideCreationTime() throws Exception {
        mockMvc.perform(request(staffToken()).content("""
                {"id":101,"storeId":2,"name":"本店菜品","categoryId":11,"price":42,
                 "createTime":"1990-01-01 00:00:00","updateTime":"1990-01-01 00:00:00"}
                """))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1L, field("store_id", Long.class));
        assertEquals(LocalDateTime.of(2000, 1, 1, 0, 0), field("create_time", LocalDateTime.class));
        assertNotEquals(LocalDateTime.of(1990, 1, 1, 0, 0), field("update_time", LocalDateTime.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {201, 999})
    void foreignOrMissingDishCannotBeUpdated(long id) throws Exception {
        Map<String, Object> target = row(101);
        Map<String, Object> otherStore = row(201);
        mockMvc.perform(request(staffToken()).content(
                        "{\"id\":" + id + ",\"name\":\"改名\",\"categoryId\":11,\"price\":42}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("菜品不存在或不属于当前门店"));
        assertEquals(target, row(101));
        assertEquals(otherStore, row(201));
        verify(redisTemplate, never()).scan(any(ScanOptions.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {21, 999})
    void foreignOrMissingCategoryCannotBeAssigned(long categoryId) throws Exception {
        Map<String, Object> before = row(101);
        mockMvc.perform(request(staffToken()).content(
                        "{\"id\":101,\"name\":\"改名\",\"categoryId\":" + categoryId + ",\"price\":42}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("分类不存在或不属于当前门店"));
        assertEquals(before, row(101));
        verify(redisTemplate, never()).scan(any(ScanOptions.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"菜品\",\"categoryId\":11,\"price\":42}",
            "{\"id\":0,\"name\":\"菜品\",\"categoryId\":11,\"price\":42}",
            "{\"id\":101,\"categoryId\":11,\"price\":42}",
            "{\"id\":101,\"name\":\"   \",\"categoryId\":11,\"price\":42}",
            "{\"id\":101,\"name\":\"菜品\",\"price\":42}",
            "{\"id\":101,\"name\":\"菜品\",\"categoryId\":0,\"price\":42}",
            "{\"id\":101,\"name\":\"菜品\",\"categoryId\":11}",
            "{\"id\":101,\"name\":\"菜品\",\"categoryId\":11,\"price\":-1}",
            "{\"id\":101,\"name\":\"菜品\",\"categoryId\":11,\"price\":42.001}",
            "{\"id\":101,\"name\":\"菜品\",\"categoryId\":11,\"price\":100000000}",
            "{\"id\":101,\"name\":\"菜品\",\"categoryId\":11,\"price\":42,\"status\":2}"
    })
    void invalidFieldsLeaveDatabaseAndCacheUnchanged(String body) throws Exception {
        Map<String, Object> before = row(101);
        mockMvc.perform(request(staffToken()).content(body))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(before, row(101));
        verify(redisTemplate, never()).scan(any(ScanOptions.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void unboundAdminCannotChooseStoreInBody(int role) throws Exception {
        String token = tokenFor(Map.of("id", role == 1 ? 1 : 4, "role", role));
        mockMvc.perform(request(token).content(BODY))
                .andExpect(jsonPath("$.code").value(1));
        verify(redisTemplate, never()).scan(any(ScanOptions.class));
    }

    @Test
    void customerCannotUpdateDish() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 99))).content(BODY))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("无门店管理权限"));
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(put("/admin/dish").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void queryParametersCannotReplaceRequiredJsonBody() throws Exception {
        mockMvc.perform(request(staffToken()).param("id", "101").param("name", "菜品").param("categoryId", "11").param("price", "42"))
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void nonexistentStoreCannotUpdateDish() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 2, "role", 2, "storeId", 999L))).content(BODY))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("所属门店不存在"));
    }

    @Test
    void cacheIsEvictedOnlyAfterCommitAndOnlyForCurrentStore() throws Exception {
        AtomicBoolean sawCommittedData = new AtomicBoolean();
        when(redisTemplate.scan(any(ScanOptions.class))).thenAnswer(invocation -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("select name from t_dish where id = 101");
                 ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                sawCommittedData.set("宫保鸡丁大份".equals(result.getString(1)));
            }
            return matchingCursor(invocation.getArgument(0));
        });
        mockMvc.perform(request(staffToken()).content(BODY)).andExpect(jsonPath("$.code").value(0));
        assertTrue(sawCommittedData.get(), "另一数据库连接应先看到已提交的数据，再清除缓存");
        assertEquals(Set.of("dish:2:21", "dish:10:11", "unrelated"), cacheKeys);
    }

    @Test
    void changingAnotherStoresDishEvictsThatStoresCacheOnly() throws Exception {
        String token = tokenFor(Map.of("id", 5, "role", 2, "storeId", 2L));
        mockMvc.perform(request(token).content("{\"id\":201,\"name\":\"门店2新菜名\",\"categoryId\":21,\"price\":51}"))
                .andExpect(jsonPath("$.code").value(0));
        assertFalse(cacheKeys.contains("dish:2:21"));
        assertTrue(cacheKeys.containsAll(Set.of("dish:1:11", "dish:1:12", "dish:1:all", "dish:10:11")));
    }

    @Test
    void cacheFailureDoesNotReportCommittedDatabaseWriteAsFailure() throws Exception {
        when(redisTemplate.scan(any(ScanOptions.class))).thenThrow(new IllegalStateException("模拟缓存不可用"));
        mockMvc.perform(request(staffToken()).content(BODY)).andExpect(jsonPath("$.code").value(0));
        assertEquals("宫保鸡丁大份", field("name", String.class));
    }

    @Test
    void manyCacheKeysAreDeletedAcrossAllBatches() throws Exception {
        for (int categoryId = 1000; categoryId < 1450; categoryId++) {
            cacheKeys.add("dish:1:" + categoryId);
        }
        mockMvc.perform(request(staffToken()).content(BODY)).andExpect(jsonPath("$.code").value(0));
        assertEquals(Set.of("dish:2:21", "dish:10:11", "unrelated"), cacheKeys);
        verify(redisTemplate, times(3)).delete(anyCollection());
    }

    @Test
    void outerTransactionRollbackCancelsAlreadyRegisteredCacheEviction() {
        Map<String, Object> before = row(101);
        Set<String> beforeKeys = Set.copyOf(cacheKeys);
        Dish dish = new Dish();
        dish.setId(101L);
        dish.setName("最终回滚的菜品");
        dish.setCategoryId(11L);
        dish.setPrice(new BigDecimal("42.00"));
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                dishService.updateDish(dish);
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

    @Test
    void rollbackRestoresRowsAndDoesNotEvictCache() {
        Map<String, Object> before = row(101);
        Set<String> beforeKeys = Set.copyOf(cacheKeys);
        doAnswer(invocation -> {
            sqlSessionTemplate.update("com.wys.orderingserver.mapper.DishMapper.updateDish", invocation.getArgument(0));
            return 0;
        }).when(dishMapper).updateDish(any(Dish.class));
        Dish dish = new Dish();
        dish.setId(101L);
        dish.setName("回滚菜品");
        dish.setCategoryId(11L);
        dish.setPrice(new BigDecimal("42.00"));
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(BizException.class, () -> dishService.updateDish(dish));
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

    private <T> T field(String column, Class<T> type) {
        return jdbcTemplate.queryForObject("select " + column + " from t_dish where id = 101", type);
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
        return put("/admin/dish").header("Authorization", token).contentType(MediaType.APPLICATION_JSON);
    }
}
