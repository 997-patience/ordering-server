package com.wys.orderingserver;

import com.github.pagehelper.PageHelper;
import com.wys.orderingserver.mapper.DishMapper;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 独立 H2 数据库执行真实 Mapper SQL，验证分页、筛选和门店隔离；Redis 使用模拟对象。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dish-page-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:dish-page-test.sql"
})
@AutoConfigureMockMvc
class DishPageIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
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
    void requestContextsAreCleared() {
        assertNull(ThreadLocalUtil.get(), "请求结束后必须清除登录身份");
        assertNull(PageHelper.getLocalPage(), "请求结束后必须清除分页上下文");
    }

    @Test
    void paginationCountsOnlyCurrentStoreAndUsesStableSort() throws Exception {
        mockMvc.perform(request(storeToken()).param("pageNum", "2").param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(12))
                .andExpect(jsonPath("$.data.items[0].storeId").value(1))
                .andExpect(jsonPath("$.data.items[0].categoryId").value(12))
                .andExpect(jsonPath("$.data.items[0].price").value(16.00))
                .andExpect(jsonPath("$.data.items[0].createTime").value("2026-10-11 09:10:05"));
    }

    @ParameterizedTest
    @CsvSource({"name,宫保,2,11", "status,0,1,11", "categoryId,11,2,11"})
    void optionalFiltersWorkIndividually(String parameter, String value, int total, int firstId) throws Exception {
        mockMvc.perform(firstPage(storeToken()).param(parameter, value))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(total))
                .andExpect(jsonPath("$.data.items[0].id").value(firstId));
    }

    @Test
    void combinedFiltersAndTrimmedNameAffectBothCountAndItems() throws Exception {
        mockMvc.perform(firstPage(storeToken()).param("name", "  宫保  ")
                        .param("status", "1").param("categoryId", "11"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankNameDoesNotFilter(String name) throws Exception {
        mockMvc.perform(firstPage(storeToken()).param("name", name))
                .andExpect(jsonPath("$.data.total").value(4));
    }

    @Test
    void clientStoreParameterCannotOverrideJwtStore() throws Exception {
        mockMvc.perform(firstPage(storeToken()).param("storeId", "2"))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.items[*].storeId").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is(1))));
    }

    @Test
    void categoryFromAnotherStoreCannotExposeItsDishes() throws Exception {
        mockMvc.perform(firstPage(storeToken()).param("categoryId", "21"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void categoryIdSupportsDatabaseBigintRange() throws Exception {
        mockMvc.perform(firstPage(storeToken()).param("categoryId", "3000000000"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(13))
                .andExpect(jsonPath("$.data.items[0].categoryId").value(3000000000L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"不存在的菜品", "' OR 1=1 --"})
    void unmatchedAndSqlLikeNamesReturnEmptyResults(String name) throws Exception {
        mockMvc.perform(firstPage(storeToken()).param("name", name))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void adminWithoutBoundStoreIsRejectedIncludingSuperAdmin(int role) throws Exception {
        String token = tokenFor(Map.of("id", role == 1 ? 1 : 2, "role", role));
        mockMvc.perform(firstPage(token))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前员工未绑定门店，请使用已绑定门店的管理员账号"));
    }

    @Test
    void customerCannotAccessAdminDishList() throws Exception {
        String token = tokenFor(Map.of("id", 99, "storeId", 1));
        mockMvc.perform(firstPage(token))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("无门店管理权限"));
    }

    @Test
    void missingTokenReturnsEmpty401() throws Exception {
        mockMvc.perform(get("/admin/dish/page").param("pageNum", "1").param("pageSize", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @ParameterizedTest
    @CsvSource({"pageNum,0", "pageSize,0", "status,2", "categoryId,0", "categoryId,-1"})
    void invalidParametersAreRejected(String parameter, String value) throws Exception {
        MockHttpServletRequestBuilder builder = request(storeToken())
                .param("pageNum", parameter.equals("pageNum") ? value : "1")
                .param("pageSize", parameter.equals("pageSize") ? value : "10");
        if (!parameter.equals("pageNum") && !parameter.equals("pageSize")) {
            builder.param(parameter, value);
        }
        mockMvc.perform(builder).andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void missingPageSizeHasReadableError() throws Exception {
        mockMvc.perform(request(storeToken()).param("pageNum", "1"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("缺少必传参数：pageSize"));
    }

    @Test
    void nonNumericCategoryIdHasReadableError() throws Exception {
        mockMvc.perform(firstPage(storeToken()).param("categoryId", "abc"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：categoryId"));
    }

    @Test
    void mapperDoesNotTreatNullStoreAsPermissionToReadAllStores() {
        assertTrue(dishMapper.list(null, null, null, null).isEmpty());
    }

    private String storeToken() {
        return tokenFor(Map.of("id", 2, "role", 2, "storeId", 1L));
    }

    private String tokenFor(Map<String, Object> claims) {
        String token = JwtUtil.genToken(claims);
        when(tokenOperations.get(token)).thenReturn(token);
        return token;
    }

    private MockHttpServletRequestBuilder request(String token) {
        return get("/admin/dish/page").header("Authorization", token);
    }

    private MockHttpServletRequestBuilder firstPage(String token) {
        return request(token).param("pageNum", "1").param("pageSize", "10");
    }
}
