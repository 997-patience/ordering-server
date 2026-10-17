package com.wys.orderingserver;

import com.github.pagehelper.PageHelper;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用独立内存数据库验证真实 SQL、PageHelper、拦截器和 JSON，不访问业务数据库。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:store-page-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql"
})
@AutoConfigureMockMvc
class StorePageIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    // 仅替换 Redis 存取，JWT 生成和校验仍执行真实代码。
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
    void superAdminGetsSecondPageAndTotalWithFormattedTimes() throws Exception {
        mockMvc.perform(pageRequest(superAdminToken()).param("pageNum", "2").param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(2))
                .andExpect(jsonPath("$.data.items[0].openTime").value("10:00:00"))
                .andExpect(jsonPath("$.data.items[0].createTime").value("2026-10-11 09:10:05"));
    }

    @Test
    void nameAndClosedStatusAreCombinedBeforeCounting() throws Exception {
        mockMvc.perform(firstPage(superAdminToken()).param("name", "望京").param("status", "0"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(3))
                .andExpect(jsonPath("$.data.items[0].status").value(0));
    }

    @Test
    void nameOnlyMatchesMultipleStores() throws Exception {
        mockMvc.perform(firstPage(superAdminToken()).param("name", "望京"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    void blankNameDoesNotFilter() throws Exception {
        mockMvc.perform(firstPage(superAdminToken()).param("name", "   "))
                .andExpect(jsonPath("$.data.total").value(3));
    }

    @Test
    void noMatchReturnsZeroAndEmptyArray() throws Exception {
        mockMvc.perform(firstPage(superAdminToken()).param("name", "不存在的门店"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void storeAdminCannotOverrideStoreUsingRequestParameter() throws Exception {
        String token = tokenFor(Map.of("id", 2, "role", 2, "storeId", 1L));
        mockMvc.perform(firstPage(token).param("storeId", "2"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(1));
    }

    @Test
    void storeAdminCannotFindAnotherStoreByName() throws Exception {
        String token = tokenFor(Map.of("id", 2, "role", 2, "storeId", 1L));
        mockMvc.perform(firstPage(token).param("name", "中关村"))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void storeAdminWithoutBoundStoreIsRejected() throws Exception {
        mockMvc.perform(firstPage(tokenFor(Map.of("id", 2, "role", 2))))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前员工未绑定门店"));
    }

    @Test
    void customerTokenWithoutAdminRoleIsRejected() throws Exception {
        mockMvc.perform(firstPage(tokenFor(Map.of("id", 1, "phone", "13800000001"))))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("无门店管理权限"));
    }

    @Test
    void unknownRoleIsRejected() throws Exception {
        mockMvc.perform(firstPage(tokenFor(Map.of("id", 1, "role", 3))))
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void missingTokenReturnsEmpty401() throws Exception {
        mockMvc.perform(get("/admin/store/page").param("pageNum", "1").param("pageSize", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void revokedTokenReturns401() throws Exception {
        String token = JwtUtil.genToken(Map.of("id", 1, "role", 1));
        mockMvc.perform(firstPage(token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidJwtInRedisStillReturns401() throws Exception {
        when(tokenOperations.get("invalid-token")).thenReturn("invalid-token");
        mockMvc.perform(firstPage("invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pageSizeIsRequired() throws Exception {
        mockMvc.perform(pageRequest(superAdminToken()).param("pageNum", "1"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("缺少必传参数：pageSize"));
    }

    @Test
    void nonNumericPageNumberReturnsBusinessError() throws Exception {
        mockMvc.perform(pageRequest(superAdminToken()).param("pageNum", "abc").param("pageSize", "10"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：pageNum"));
    }

    @ParameterizedTest
    @CsvSource({"0,10,1", "1,0,1", "1,10,2"})
    void invalidPagingOrStatusIsRejected(String pageNum, String pageSize, String storeStatus) throws Exception {
        mockMvc.perform(pageRequest(superAdminToken())
                        .param("pageNum", pageNum).param("pageSize", pageSize).param("status", storeStatus))
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void pageBeyondLastUsesExistingReasonableConfiguration() throws Exception {
        mockMvc.perform(pageRequest(superAdminToken()).param("pageNum", "99").param("pageSize", "1"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items[0].id").value(3));
    }

    private String superAdminToken() {
        return tokenFor(Map.of("id", 1, "role", 1));
    }

    private String tokenFor(Map<String, Object> claims) {
        String token = JwtUtil.genToken(claims);
        when(tokenOperations.get(token)).thenReturn(token);
        return token;
    }

    private MockHttpServletRequestBuilder pageRequest(String token) {
        return get("/admin/store/page").header("Authorization", token);
    }

    private MockHttpServletRequestBuilder firstPage(String token) {
        return pageRequest(token).param("pageNum", "1").param("pageSize", "10");
    }
}
