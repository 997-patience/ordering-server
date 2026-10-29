package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.CategoryMapper;
import com.wys.orderingserver.service.AppCategoryService;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.Md5Util;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 H2 执行真实分类 SQL，验证用户选店、启用状态、排序和用户端响应字段。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:app-category-list-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-list-test.sql,classpath:user-login-test.sql"
})
@AutoConfigureMockMvc
class AppCategoryListIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AppCategoryService appCategoryService;
    @MockitoSpyBean private CategoryMapper categoryMapper;
    @MockitoBean private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> tokenOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepareUserAndRedis() {
        ThreadLocalUtil.remove();
        jdbcTemplate.update("delete from t_category where id > 4");
        jdbcTemplate.update("update t_category set status = case when id = 2 then 0 else 1 end");
        jdbcTemplate.update("delete from t_user");
        jdbcTemplate.update("insert into t_user(id, phone, password, nickname) values (?, ?, ?, ?)",
                1L, "13800000001", Md5Util.getMD5String("123456"), "小明");
        tokenOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(tokenOperations);
    }

    @AfterEach
    void identityIsCleared() {
        assertNull(ThreadLocalUtil.get());
    }

    @Test
    void customerReceivesOnlyEnabledCategoriesOfSelectedStoreAndDocumentedFields() throws Exception {
        mockMvc.perform(get("/app/category/list").header("Authorization", customerToken()).param("storeId", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(3))
                .andExpect(jsonPath("$.data[0].name").value("热菜"))
                .andExpect(jsonPath("$.data[0].sort").value(1))
                .andExpect(jsonPath("$.data[1].id").value(1))
                .andExpect(jsonPath("$.data[1].name").value("主食"))
                .andExpect(jsonPath("$.data[1].sort").value(2))
                .andExpect(jsonPath("$.data[0]").value(aMapWithSize(3)))
                .andExpect(jsonPath("$.data[1]").value(aMapWithSize(3)));
        verify(categoryMapper).listEnabledByStoreId(1L);
        verify(categoryMapper, never()).getCategory(anyLong());
    }

    @Test
    void sameCustomerCanChooseAnotherStoreWithoutBeingBoundToIt() throws Exception {
        mockMvc.perform(get("/app/category/list").header("Authorization", customerToken()).param("storeId", "2"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(4))
                .andExpect(jsonPath("$.data[0].name").value("其他门店分类"));
        verify(categoryMapper).listEnabledByStoreId(2L);
    }

    @Test
    void equalSortNumbersUseIdForStableOrdering() throws Exception {
        jdbcTemplate.update("insert into t_category(id, store_id, name, sort, status) values (5, 1, '同排序分类', 1, 1)");
        mockMvc.perform(get("/app/category/list").header("Authorization", customerToken()).param("storeId", "1"))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].id").value(3))
                .andExpect(jsonPath("$.data[1].id").value(5))
                .andExpect(jsonPath("$.data[2].id").value(1));
    }

    @ParameterizedTest
    @ValueSource(longs = {3, 999})
    void noMatchingCategoriesReturnsSuccessfulEmptyArray(long storeId) throws Exception {
        mockMvc.perform(get("/app/category/list").header("Authorization", customerToken())
                        .param("storeId", Long.toString(storeId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void storeContainingOnlyDisabledCategoriesReturnsEmptyArray() throws Exception {
        jdbcTemplate.update("update t_category set status = 0 where store_id = 1");
        mockMvc.perform(get("/app/category/list").header("Authorization", customerToken()).param("storeId", "1"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void missingStoreIdReturnsExistingGlobalParameterErrorWithoutQueryingCategories() throws Exception {
        mockMvc.perform(get("/app/category/list").header("Authorization", customerToken()))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("缺少必传参数：storeId"));
        verifyNoInteractions(categoryMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void nonPositiveStoreIdIsRejectedBeforeQuery(String storeId) throws Exception {
        mockMvc.perform(get("/app/category/list").header("Authorization", customerToken()).param("storeId", storeId))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请选择有效的门店ID"));
        verifyNoInteractions(categoryMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "9223372036854775808"})
    void nonLongStoreIdUsesExistingGlobalTypeError(String storeId) throws Exception {
        mockMvc.perform(get("/app/category/list").header("Authorization", customerToken()).param("storeId", storeId))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：storeId"));
        verifyNoInteractions(categoryMapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void directServiceCallsAlsoValidateStoreId(Long storeId) {
        BizException error = assertThrows(BizException.class, () -> appCategoryService.list(storeId));
        assertEquals("请选择有效的门店ID", error.getMessage());
        verifyNoInteractions(categoryMapper);
    }

    @Test
    void missingTokenReturnsEmpty401BeforeCategoryQuery() throws Exception {
        mockMvc.perform(get("/app/category/list").param("storeId", "1"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        verifyNoInteractions(categoryMapper);
    }

    @Test
    void revokedTokenIsRejectedBeforeCategoryQuery() throws Exception {
        String token = customerToken();
        when(tokenOperations.get(token)).thenReturn(null);
        mockMvc.perform(get("/app/category/list").header("Authorization", token).param("storeId", "1"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(categoryMapper);
    }

    @Test
    void adminTokenCannotEnterAppCategoryEndpoint() throws Exception {
        String token = cacheToken(Map.of("id", 1L, "role", 1, "type", "admin", "username", "admin"));
        mockMvc.perform(get("/app/category/list").header("Authorization", token).param("storeId", "1"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(categoryMapper);
    }

    @Test
    void adminQueryStillIncludesDisabledCategoriesAfterAddingAppQuery() {
        assertEquals(3, categoryMapper.getCategory(1L).size());
        assertTrue(categoryMapper.getCategory(1L).stream().anyMatch(category -> category.getStatus() == 0));
    }

    @Test
    void storeListStillWorksAfterMovingCategoryCodeToItsOwnModule() throws Exception {
        mockMvc.perform(get("/app/store/list").header("Authorization", customerToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    private String customerToken() {
        return cacheToken(Map.of("id", 1L, "phone", "13800000001", "nickname", "小明", "type", "user"));
    }

    private String cacheToken(Map<String, Object> claims) {
        String token = JwtUtil.genToken(claims);
        when(tokenOperations.get(token)).thenReturn(token);
        return token;
    }
}
