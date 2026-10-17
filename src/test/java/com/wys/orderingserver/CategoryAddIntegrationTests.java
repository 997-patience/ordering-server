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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证真实新增 SQL、JSON 校验、门店归属、默认值和事务，不连接业务数据库。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:category-add-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql"
})
@AutoConfigureMockMvc
@Sql("/category-add-reset.sql")
class CategoryAddIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SqlSessionTemplate sqlSessionTemplate;

    @Autowired
    private CategoryService categoryService;

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
    void minimumJsonCreatesCategoryWithDefaultsAndServerTimes() throws Exception {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        mockMvc.perform(request(staffToken()).content("{\"name\":\"  汤羹  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        assertEquals(1, count());
        assertEquals("汤羹", jdbcTemplate.queryForObject("select name from t_category", String.class));
        assertEquals(1L, jdbcTemplate.queryForObject("select store_id from t_category", Long.class));
        assertEquals(0, jdbcTemplate.queryForObject("select sort from t_category", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject("select status from t_category", Integer.class));
        LocalDateTime created = jdbcTemplate.queryForObject("select create_time from t_category", LocalDateTime.class);
        LocalDateTime updated = jdbcTemplate.queryForObject("select update_time from t_category", LocalDateTime.class);
        assertNotNull(created);
        assertFalse(created.isBefore(before));
        assertEquals(created, updated);
        verify(categoryMapper, times(1)).add(any(Category.class));
    }

    @Test
    void explicitSortAndDisabledStatusAreStored() throws Exception {
        mockMvc.perform(request(staffToken()).content("{\"name\":\"饮品\",\"sort\":4,\"status\":0}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(4, jdbcTemplate.queryForObject("select sort from t_category", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("select status from t_category", Integer.class));
    }

    @Test
    void bodyCannotOverrideStoreIdPrimaryKeyOrServerTimes() throws Exception {
        mockMvc.perform(request(staffToken()).content("""
                {"id":999,"storeId":2,"name":"本店分类",
                 "createTime":"2000-01-01 00:00:00","updateTime":"2000-01-01 00:00:00"}
                """))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1L, jdbcTemplate.queryForObject("select store_id from t_category", Long.class));
        assertNotEquals(999L, jdbcTemplate.queryForObject("select id from t_category", Long.class));
        assertNotEquals(LocalDateTime.of(2000, 1, 1, 0, 0),
                jdbcTemplate.queryForObject("select create_time from t_category", LocalDateTime.class));
    }

    @Test
    void boundSuperAdminUsesStoreFromJwt() throws Exception {
        String token = tokenFor(Map.of("id", 3, "role", 1, "storeId", 1L));
        mockMvc.perform(request(token).content("{\"name\":\"超管分类\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1L, jdbcTemplate.queryForObject("select store_id from t_category", Long.class));
    }

    @Test
    void unboundSuperAdminCannotChooseStoreInBody() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 1, "role", 1)))
                        .content("{\"name\":\"汤羹\",\"storeId\":1}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前员工未绑定门店，请使用已绑定门店的管理员账号"));
        assertEquals(0, count());
    }

    @Test
    void unboundStoreAdminCannotInsert() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 4, "role", 2))).content("{\"name\":\"汤羹\"}"))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(0, count());
    }

    @Test
    void nonexistentJwtStoreIsRejected() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 2, "role", 2, "storeId", 999)))
                        .content("{\"name\":\"汤羹\"}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("所属门店不存在"));
        assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void invalidJwtStoreIsRejected(int storeId) throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 2, "role", 2, "storeId", storeId)))
                        .content("{\"name\":\"汤羹\"}"))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"   \"}"})
    void missingOrBlankNameIsRejected(String body) throws Exception {
        mockMvc.perform(request(staffToken()).content(body))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("分类名称不能为空"));
        assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 2})
    void invalidStatusIsRejected(int categoryStatus) throws Exception {
        mockMvc.perform(request(staffToken()).content("{\"name\":\"汤羹\",\"status\":" + categoryStatus + "}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("分类状态只能为0或1"));
        assertEquals(0, count());
    }

    @Test
    void customerCannotInsertCategory() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 1, "phone", "13800000001")))
                        .content("{\"name\":\"汤羹\"}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("无门店管理权限"));
        assertEquals(0, count());
    }

    @Test
    void unknownRoleCannotInsertCategory() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 1, "role", 3)))
                        .content("{\"name\":\"汤羹\"}"))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(0, count());
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(post("/admin/category").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"汤羹\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals(0, count());
    }

    @Test
    void queryParametersDoNotReplaceRequiredJsonBody() throws Exception {
        mockMvc.perform(request(staffToken()).param("name", "汤羹"))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(0, count());
    }

    @Test
    void nonNumericSortIsRejected() throws Exception {
        mockMvc.perform(request(staffToken()).content("{\"name\":\"汤羹\",\"sort\":\"abc\"}"))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(0, count());
    }

    @Test
    void insertFailureRollsBackDatabaseWrite() {
        doAnswer(invocation -> {
            // Mapper 是接口代理，通过实际映射语句写入，再模拟后续异常。
            sqlSessionTemplate.insert("com.wys.orderingserver.mapper.CategoryMapper.add",
                    invocation.getArgument(0));
            throw new DataIntegrityViolationException("模拟插入后异常");
        }).when(categoryMapper).add(any(Category.class));
        Category category = new Category();
        category.setName("回滚分类");
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(DataIntegrityViolationException.class, () -> categoryService.add(category));
            assertEquals(0, count());
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    private long count() {
        return jdbcTemplate.queryForObject("select count(*) from t_category", Long.class);
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
        return post("/admin/category").header("Authorization", token).contentType(MediaType.APPLICATION_JSON);
    }
}
