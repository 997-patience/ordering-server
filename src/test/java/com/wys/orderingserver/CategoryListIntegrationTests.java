package com.wys.orderingserver;

import com.wys.orderingserver.mapper.CategoryMapper;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 在独立内存数据库验证分类查询、排序、响应格式和门店权限。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:category-list-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-list-test.sql"
})
@AutoConfigureMockMvc
class CategoryListIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

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
    void superAdminGetsRequestedStoreSortedIncludingDisabledCategory() throws Exception {
        mockMvc.perform(request(adminToken()).param("storeId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].id").value(2))
                .andExpect(jsonPath("$.data[1].id").value(3))
                .andExpect(jsonPath("$.data[2].id").value(1))
                .andExpect(jsonPath("$.data[0].storeId").value(1))
                .andExpect(jsonPath("$.data[0].status").value(0))
                .andExpect(jsonPath("$.data[0].createTime").value("2026-10-11 09:10:05"));
        verify(categoryMapper, times(1)).getCategory(1L);
    }

    @Test
    void superAdminCanQueryAnotherStore() throws Exception {
        mockMvc.perform(request(adminToken()).param("storeId", "2"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(4));
    }

    @Test
    void superAdminMustSpecifyStore() throws Exception {
        mockMvc.perform(request(adminToken()))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("超级管理员必须指定有效的门店ID"));
        verifyNoInteractions(categoryMapper);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void superAdminCannotUseNonPositiveStoreId(long storeId) throws Exception {
        mockMvc.perform(request(adminToken()).param("storeId", Long.toString(storeId)))
                .andExpect(jsonPath("$.code").value(1));
        verifyNoInteractions(categoryMapper);
    }

    @Test
    void storeAdminUsesStoreFromJwtWithoutRequestParameter() throws Exception {
        mockMvc.perform(request(staffToken()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].storeId").value(1));
        verify(categoryMapper, times(1)).getCategory(1L);
    }

    @Test
    void storeAdminCannotOverrideJwtStoreId() throws Exception {
        mockMvc.perform(request(staffToken()).param("storeId", "2"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].storeId").value(1));
        verify(categoryMapper, times(1)).getCategory(1L);
        verify(categoryMapper, never()).getCategory(2L);
    }

    @Test
    void storeAdminWithoutBoundStoreIsRejected() throws Exception {
        String token = tokenFor(Map.of("id", 2, "role", 2));
        mockMvc.perform(request(token))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前员工未绑定门店"));
        verifyNoInteractions(categoryMapper);
    }

    @Test
    void invalidBoundStoreIsRejected() throws Exception {
        String token = tokenFor(Map.of("id", 2, "role", 2, "storeId", 0));
        mockMvc.perform(request(token))
                .andExpect(jsonPath("$.code").value(1));
        verifyNoInteractions(categoryMapper);
    }

    @Test
    void customerCannotQueryAdminCategories() throws Exception {
        String token = tokenFor(Map.of("id", 1, "phone", "13800000001"));
        mockMvc.perform(request(token).param("storeId", "1"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("无门店管理权限"));
        verifyNoInteractions(categoryMapper);
    }

    @Test
    void unknownRoleIsRejected() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 1, "role", 3))).param("storeId", "1"))
                .andExpect(jsonPath("$.code").value(1));
        verifyNoInteractions(categoryMapper);
    }

    @Test
    void missingTokenReturnsEmpty401() throws Exception {
        mockMvc.perform(get("/admin/category/list").param("storeId", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void storeWithoutCategoriesReturnsEmptyArray() throws Exception {
        mockMvc.perform(request(adminToken()).param("storeId", "3"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void nonNumericStoreIdReturnsParameterError() throws Exception {
        mockMvc.perform(request(adminToken()).param("storeId", "abc"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：storeId"));
        verifyNoInteractions(categoryMapper);
    }

    private String adminToken() {
        return tokenFor(Map.of("id", 1, "role", 1));
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
        return get("/admin/category/list").header("Authorization", token);
    }
}
