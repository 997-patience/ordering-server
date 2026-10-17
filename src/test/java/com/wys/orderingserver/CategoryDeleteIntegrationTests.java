package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.CategoryMapper;
import com.wys.orderingserver.mapper.DishMapper;
import com.wys.orderingserver.service.CategoryService;
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
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立数据库验证分类删除、门店隔离、菜品关联检查、外键和回滚。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:category-delete-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql,classpath:category-delete-setup.sql,classpath:db/category-delete-foreign-key.sql"
})
@AutoConfigureMockMvc
@Sql("/category-delete-data.sql")
class CategoryDeleteIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private SqlSessionTemplate sqlSessionTemplate;

    @MockitoSpyBean
    private CategoryMapper categoryMapper;

    @MockitoSpyBean
    private DishMapper dishMapper;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

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
    void deletesOnlyRequestedEmptyCategory() throws Exception {
        mockMvc.perform(request(staffToken()).param("id", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        assertEquals(0, countCategory(101));
        assertEquals(1, countCategory(102));
        assertEquals(1, countCategory(201));
        assertEquals(4, count("t_category"));
        assertEquals(3, count("t_dish"));
        verify(categoryMapper, times(1)).deleteCategory(101L, 1L);
    }

    @ParameterizedTest
    @ValueSource(longs = {102, 103})
    void anyDishIncludingOffShelfPreventsDeletion(long id) throws Exception {
        mockMvc.perform(request(staffToken()).param("id", Long.toString(id)))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("该分类下存在菜品，请先调整菜品分类后再删除"));
        assertOriginalData();
        verify(categoryMapper, never()).deleteCategory(anyLong(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(longs = {201, 202})
    void cannotDeleteAnotherStoresCategoryOrOverrideStoreUsingQueryParameter(long id) throws Exception {
        mockMvc.perform(request(staffToken()).param("id", Long.toString(id)).param("storeId", "2"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("分类不存在或不属于当前门店"));
        assertOriginalData();
        verifyNoInteractions(dishMapper);
    }

    @Test
    void nonexistentCategoryReturnsError() throws Exception {
        mockMvc.perform(request(staffToken()).param("id", "999"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("分类不存在或不属于当前门店"));
        assertOriginalData();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveIdIsRejected(long id) throws Exception {
        assertRejected(staffToken(), Long.toString(id));
    }

    @Test
    void idQueryParameterIsRequired() throws Exception {
        mockMvc.perform(request(staffToken()))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("缺少必传参数：id"));
        assertOriginalData();
    }

    @Test
    void nonNumericIdIsRejected() throws Exception {
        mockMvc.perform(request(staffToken()).param("id", "abc"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：id"));
        assertOriginalData();
    }

    @Test
    void boundSuperAdminCanDeleteItsStoreCategory() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 3, "role", 1, "storeId", 1L))).param("id", "101"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0, countCategory(101));
    }

    @Test
    void unboundSuperAdminIsRejected() throws Exception {
        assertRejected(tokenFor(Map.of("id", 1, "role", 1)), "101");
    }

    @Test
    void unboundStoreAdminIsRejected() throws Exception {
        assertRejected(tokenFor(Map.of("id", 4, "role", 2)), "101");
    }

    @Test
    void customerIsRejected() throws Exception {
        assertRejected(tokenFor(Map.of("id", 1, "phone", "13800000001")), "101");
    }

    @Test
    void unknownRoleIsRejected() throws Exception {
        assertRejected(tokenFor(Map.of("id", 1, "role", 3)), "101");
    }

    @Test
    void nonexistentStoreIsRejectedBeforeAccessingCategory() throws Exception {
        assertRejected(tokenFor(Map.of("id", 2, "role", 2, "storeId", 999)), "101");
        verifyNoInteractions(categoryMapper);
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(delete("/admin/category").param("id", "101"))
                .andExpect(status().isUnauthorized());
        assertOriginalData();
    }

    @Test
    void repeatedDeleteReturnsErrorOnSecondRequest() throws Exception {
        String token = staffToken();
        mockMvc.perform(request(token).param("id", "101")).andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(request(token).param("id", "101")).andExpect(jsonPath("$.code").value(1));
        assertEquals(4, count("t_category"));
        assertEquals(3, count("t_dish"));
    }

    @Test
    void foreignKeyStillProtectsCategoryIfApplicationCountMissesReference() throws Exception {
        // 模拟检查没有看见关联记录，验证数据库约束仍阻止留下孤立菜品。
        doReturn(0L).when(dishMapper).countByCategoryId(102L);
        mockMvc.perform(request(staffToken()).param("id", "102"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("分类存在关联数据，不能删除"));
        assertOriginalData();
    }

    @Test
    void failureAfterDeleteRollsBackRecord() {
        doAnswer(invocation -> {
            sqlSessionTemplate.delete("com.wys.orderingserver.mapper.CategoryMapper.deleteCategory",
                    Map.of("id", invocation.getArgument(0), "storeId", invocation.getArgument(1)));
            throw new DataIntegrityViolationException("模拟删除后异常");
        }).when(categoryMapper).deleteCategory(101L, 1L);
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(BizException.class, () -> categoryService.deleteCategory(101L));
            assertOriginalData();
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    @Test
    void simultaneousDeletesOnlyOneSucceeds() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> deleteAfterSignal(start));
            var second = executor.submit(() -> deleteAfterSignal(start));
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(0, countCategory(101));
        assertEquals(1, countCategory(201));
    }

    private boolean deleteAfterSignal(CountDownLatch start) throws InterruptedException {
        start.await();
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            categoryService.deleteCategory(101L);
            return true;
        } catch (BizException e) {
            assertEquals("分类不存在或不属于当前门店", e.getMessage());
            return false;
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    private void assertRejected(String token, String id) throws Exception {
        mockMvc.perform(request(token).param("id", id)).andExpect(jsonPath("$.code").value(1));
        assertOriginalData();
    }

    private void assertOriginalData() {
        assertEquals(5, count("t_category"));
        assertEquals(3, count("t_dish"));
    }

    private long countCategory(long id) {
        return jdbcTemplate.queryForObject("select count(*) from t_category where id = ?", Long.class, id);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
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
        return delete("/admin/category").header("Authorization", token);
    }
}
