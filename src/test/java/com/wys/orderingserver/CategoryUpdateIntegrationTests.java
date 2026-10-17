package com.wys.orderingserver;

import com.wys.orderingserver.mapper.CategoryMapper;
import com.wys.orderingserver.pojo.Category;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用独立 H2 数据库验证修改、权限、可选字段保留与异常回滚。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:category-update-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql,classpath:category-update-setup.sql"
})
@AutoConfigureMockMvc
@Sql("/category-update-data.sql")
class CategoryUpdateIntegrationTests {

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
    void nameOnlyUpdatePreservesOptionalFieldsCreationTimeAndOtherRows() throws Exception {
        Map<String, Object> otherCategory = row(102);
        Map<String, Object> otherStore = row(201);
        mockMvc.perform(request(staffToken()).content("{\"id\":101,\"name\":\"  饮品/甜点  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        assertEquals("饮品/甜点", field(101, "name", String.class));
        assertEquals(7, field(101, "sort", Integer.class));
        assertEquals(0, field(101, "status", Integer.class));
        assertEquals(LocalDateTime.of(2000, 1, 1, 0, 0), field(101, "create_time", LocalDateTime.class));
        assertNotEquals(LocalDateTime.of(2000, 1, 1, 0, 0), field(101, "update_time", LocalDateTime.class));
        assertEquals(otherCategory, row(102));
        assertEquals(otherStore, row(201));
        verify(categoryMapper, times(1)).updateCategory(any(Category.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, -1})
    void providedSortIsStoredWithoutIncrement(int sort) throws Exception {
        mockMvc.perform(request(staffToken()).content(
                        "{\"id\":101,\"name\":\"新分类\",\"sort\":" + sort + ",\"status\":1}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(sort, field(101, "sort", Integer.class));
        assertEquals(1, field(101, "status", Integer.class));
    }

    @Test
    void nullOptionalFieldsPreserveCurrentValues() throws Exception {
        mockMvc.perform(request(staffToken()).content(
                        "{\"id\":101,\"name\":\"新分类\",\"sort\":null,\"status\":null}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(7, field(101, "sort", Integer.class));
        assertEquals(0, field(101, "status", Integer.class));
    }

    @Test
    void forgedBodyStoreIdCannotMoveOwnCategory() throws Exception {
        mockMvc.perform(request(staffToken()).content("{\"id\":101,\"storeId\":2,\"name\":\"本店分类\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1L, field(101, "store_id", Long.class));
    }

    @Test
    void forgedCategoryAndStoreIdsCannotModifyOtherStore() throws Exception {
        Map<String, Object> before = row(201);
        mockMvc.perform(request(staffToken()).content("{\"id\":201,\"storeId\":2,\"name\":\"越权修改\"}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("分类不存在或不属于当前门店"));
        assertEquals(before, row(201));
    }

    @Test
    void missingCategoryReturnsErrorInsteadOfSuccess() throws Exception {
        mockMvc.perform(request(staffToken()).content("{\"id\":999,\"name\":\"不存在的分类\"}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("分类不存在或不属于当前门店"));
        assertEquals(4L, jdbcTemplate.queryForObject("select count(*) from t_category", Long.class));
    }

    @Test
    void largeStoreIdDoesNotDependOnWrapperObjectIdentity() throws Exception {
        String token = tokenFor(Map.of("id", 5, "role", 2, "storeId", 128L));
        mockMvc.perform(request(token).content("{\"id\":12801,\"name\":\"大编号修改成功\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("大编号修改成功", field(12801, "name", String.class));
    }

    @Test
    void boundSuperAdminCanModifyItsStore() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 3, "role", 1, "storeId", 1L)))
                        .content("{\"id\":101,\"name\":\"超管修改\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void unboundAdminCannotSpecifyStoreUsingBody() throws Exception {
        assertRejectedWithoutChange(tokenFor(Map.of("id", 1, "role", 1)),
                "{\"id\":101,\"name\":\"修改\",\"storeId\":1}");
    }

    @Test
    void unboundStoreAdminIsRejected() throws Exception {
        assertRejectedWithoutChange(tokenFor(Map.of("id", 4, "role", 2)),
                "{\"id\":101,\"name\":\"修改\"}");
    }

    @Test
    void customerIsRejected() throws Exception {
        assertRejectedWithoutChange(tokenFor(Map.of("id", 1, "phone", "13800000001")),
                "{\"id\":101,\"name\":\"修改\"}");
    }

    @Test
    void unknownRoleIsRejected() throws Exception {
        assertRejectedWithoutChange(tokenFor(Map.of("id", 1, "role", 3)),
                "{\"id\":101,\"name\":\"修改\"}");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 2})
    void invalidStatusIsRejected(int categoryStatus) throws Exception {
        assertRejectedWithoutChange(staffToken(),
                "{\"id\":101,\"name\":\"修改\",\"status\":" + categoryStatus + "}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":101}", "{\"id\":101,\"name\":null}",
            "{\"id\":101,\"name\":\"\"}", "{\"id\":101,\"name\":\"   \"}"})
    void missingOrBlankNameIsRejected(String body) throws Exception {
        assertRejectedWithoutChange(staffToken(), body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":\"修改\"}", "{\"id\":null,\"name\":\"修改\"}",
            "{\"id\":0,\"name\":\"修改\"}", "{\"id\":-1,\"name\":\"修改\"}"})
    void missingOrInvalidIdIsRejected(String body) throws Exception {
        assertRejectedWithoutChange(staffToken(), body);
    }

    @Test
    void nonexistentStoreIsRejectedBeforeUpdate() throws Exception {
        assertRejectedWithoutChange(tokenFor(Map.of("id", 2, "role", 2, "storeId", 999L)),
                "{\"id\":101,\"name\":\"修改\"}");
        verifyNoInteractions(categoryMapper);
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(put("/admin/category").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":101,\"name\":\"修改\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bodyCannotOverrideCreationOrUpdateTimes() throws Exception {
        mockMvc.perform(request(staffToken()).content("""
                {"id":101,"name":"新分类","createTime":"2030-01-01 00:00:00",
                 "updateTime":"2030-01-01 00:00:00"}
                """))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(LocalDateTime.of(2000, 1, 1, 0, 0), field(101, "create_time", LocalDateTime.class));
        assertNotEquals(LocalDateTime.of(2030, 1, 1, 0, 0), field(101, "update_time", LocalDateTime.class));
    }

    @Test
    void databaseExceptionRollsBackUpdate() {
        Map<String, Object> before = row(101);
        doAnswer(invocation -> {
            sqlSessionTemplate.update("com.wys.orderingserver.mapper.CategoryMapper.updateCategory",
                    invocation.getArgument(0));
            throw new DataIntegrityViolationException("模拟修改后异常");
        }).when(categoryMapper).updateCategory(any(Category.class));
        Category category = new Category();
        category.setId(101L);
        category.setName("应该回滚");
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(DataIntegrityViolationException.class, () -> categoryService.updateCategory(category));
            assertEquals(before, row(101));
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    private void assertRejectedWithoutChange(String token, String body) throws Exception {
        Map<String, Object> before = row(101);
        mockMvc.perform(request(token).content(body)).andExpect(jsonPath("$.code").value(1));
        assertEquals(before, row(101));
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("select * from t_category where id = ?", id);
    }

    private <T> T field(long id, String column, Class<T> type) {
        return jdbcTemplate.queryForObject("select " + column + " from t_category where id = ?", type, id);
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
        return put("/admin/category").header("Authorization", token).contentType(MediaType.APPLICATION_JSON);
    }
}
