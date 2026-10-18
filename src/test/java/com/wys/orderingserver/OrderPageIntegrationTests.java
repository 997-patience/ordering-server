package com.wys.orderingserver;

import com.github.pagehelper.PageHelper;
import com.wys.orderingserver.mapper.OrderMapper;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 H2 执行真实分页 SQL，验证下单时间区间、订单状态、返回格式和门店隔离。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-page-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:order-page-test.sql"
})
@AutoConfigureMockMvc
class OrderPageIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrderMapper orderMapper;
    @MockitoBean private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> tokenOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepareRedis() {
        tokenOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(tokenOperations);
    }

    @AfterEach
    void requestContextsAreCleared() {
        assertNull(ThreadLocalUtil.get(), "请求结束后清除登录身份");
        assertNull(PageHelper.getLocalPage(), "请求结束后清除分页上下文");
    }

    @Test
    void pageAndTotalAreRestrictedToCurrentStoreAndResponseFieldsMatchDocument() throws Exception {
        mockMvc.perform(request(staffToken()).param("pageNum", "2").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(103))
                .andExpect(jsonPath("$.data.items[0].orderNo").value("ORDER-103"))
                .andExpect(jsonPath("$.data.items[0].userId").value(13))
                .andExpect(jsonPath("$.data.items[0].storeId").value(1))
                .andExpect(jsonPath("$.data.items[0].amount").value(76.00))
                .andExpect(jsonPath("$.data.items[0].status").value(3))
                .andExpect(jsonPath("$.data.items[0].payStatus").value(1))
                .andExpect(jsonPath("$.data.items[0].payTime").value("2026-10-11 12:00:05"))
                .andExpect(jsonPath("$.data.items[0].remark").value("不要香菜"))
                .andExpect(jsonPath("$.data.items[0].pickupType").value(2))
                .andExpect(jsonPath("$.data.items[0].pickupTime").value("2026-10-11 12:30:00"))
                .andExpect(jsonPath("$.data.items[0].createTime").value("2026-10-11 12:00:00"))
                .andExpect(jsonPath("$.data.items[0].finishTime").value("2026-10-12 13:00:00"))
                .andExpect(jsonPath("$.data.items[1].id").value(104));
    }

    @ParameterizedTest
    @CsvSource({"1,3,101", "2,1,102", "3,1,103", "4,1,104"})
    void allFourOrderStatusesCanBeFiltered(int orderStatus, int total, int firstId) throws Exception {
        mockMvc.perform(firstPage(staffToken()).param("status", String.valueOf(orderStatus)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(total))
                .andExpect(jsonPath("$.data.items[0].id").value(firstId));
    }

    @Test
    void inclusiveDateRangeUsesCreationTimeForBothBounds() throws Exception {
        mockMvc.perform(firstPage(staffToken())
                        .param("beginTime", "2026-10-11 00:00:00")
                        .param("endTime", "2026-10-11 23:59:59"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.items[*].id").value(org.hamcrest.Matchers.contains(102, 103, 104, 106)))
                .andExpect(jsonPath("$.data.items[0].finishTime").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void beginTimeOnlyFiltersLowerBound() throws Exception {
        mockMvc.perform(firstPage(staffToken()).param("beginTime", "2026-10-11 12:00:00"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.items[*].id").value(org.hamcrest.Matchers.contains(103, 104, 105, 106)));
    }

    @Test
    void endTimeOnlyFiltersUpperBound() throws Exception {
        mockMvc.perform(firstPage(staffToken()).param("endTime", "2026-10-11 00:00:00"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[*].id").value(org.hamcrest.Matchers.contains(101, 102)));
    }

    @Test
    void equalBoundsReturnOrdersCreatedAtThatExactSecond() throws Exception {
        mockMvc.perform(firstPage(staffToken()).param("beginTime", "2026-10-11 12:00:00")
                        .param("endTime", "2026-10-11 12:00:00"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[*].id").value(org.hamcrest.Matchers.contains(103, 106)));
    }

    @Test
    void combinedFiltersAffectCountBeforePagination() throws Exception {
        mockMvc.perform(request(staffToken()).param("pageNum", "1").param("pageSize", "1")
                        .param("status", "1").param("beginTime", "2026-10-11 00:00:00")
                        .param("endTime", "2026-10-11 23:59:59"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(106));
    }

    @Test
    void rangeWithNoOrdersReturnsZeroAndEmptyItems() throws Exception {
        mockMvc.perform(firstPage(staffToken()).param("beginTime", "2030-01-01 00:00:00"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void reversedTimeRangeIsRejected() throws Exception {
        mockMvc.perform(firstPage(staffToken()).param("beginTime", "2026-10-12 00:00:00")
                        .param("endTime", "2026-10-11 00:00:00"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("开始时间不能晚于结束时间"));
    }

    @ParameterizedTest
    @CsvSource({"beginTime,not-a-time", "endTime,not-a-time", "beginTime,2026-02-30 10:00:00"})
    void invalidTimeHasReadableError(String parameter, String value) throws Exception {
        mockMvc.perform(firstPage(staffToken()).param(parameter, value))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：" + parameter));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 5})
    void invalidOrderStatusIsRejected(int orderStatus) throws Exception {
        mockMvc.perform(firstPage(staffToken()).param("status", String.valueOf(orderStatus)))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("订单状态只能为1、2、3或4"));
    }

    @ParameterizedTest
    @CsvSource({"0,10", "-1,10", "1,0", "1,-1"})
    void invalidPaginationIsRejected(String pageNum, String pageSize) throws Exception {
        mockMvc.perform(request(staffToken()).param("pageNum", pageNum).param("pageSize", pageSize))
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    void bothPaginationParametersAreRequired() throws Exception {
        mockMvc.perform(request(staffToken()).param("pageNum", "1"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("缺少必传参数：pageSize"));
        mockMvc.perform(request(staffToken()).param("pageSize", "10"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("缺少必传参数：pageNum"));
    }

    @Test
    void clientCannotOverrideJwtStore() throws Exception {
        mockMvc.perform(firstPage(staffToken()).param("storeId", "2"))
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.items[*].storeId").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(1))));
    }

    @Test
    void secondStoreSeesOnlyItsOwnOrders() throws Exception {
        String token = tokenFor(Map.of("id", 5, "role", 2, "storeId", 2L));
        mockMvc.perform(firstPage(token))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(201));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void unboundAdminCannotSelectStoreInQuery(int role) throws Exception {
        String token = tokenFor(Map.of("id", role == 1 ? 1 : 4, "role", role));
        mockMvc.perform(firstPage(token).param("storeId", "1"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前员工未绑定门店，请使用已绑定门店的管理员账号"));
    }

    @Test
    void boundSuperAdminUsesJwtStore() throws Exception {
        String token = tokenFor(Map.of("id", 3, "role", 1, "storeId", 1L));
        mockMvc.perform(firstPage(token)).andExpect(jsonPath("$.data.total").value(6));
    }

    @Test
    void customerCannotReadAdminOrders() throws Exception {
        mockMvc.perform(firstPage(tokenFor(Map.of("id", 99))))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("无门店管理权限"));
    }

    @Test
    void missingTokenReturnsEmpty401() throws Exception {
        mockMvc.perform(get("/admin/order/page").param("pageNum", "1").param("pageSize", "10"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    @Test
    void nullStoreInMapperCannotExposeAllOrders() {
        assertTrue(orderMapper.list(null, null, null, null).isEmpty());
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
        return get("/admin/order/page").header("Authorization", token);
    }

    private MockHttpServletRequestBuilder firstPage(String token) {
        return request(token).param("pageNum", "1").param("pageSize", "10");
    }
}
