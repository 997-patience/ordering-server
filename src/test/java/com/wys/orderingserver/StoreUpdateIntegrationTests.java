package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.StoreMapper;
import com.wys.orderingserver.pojo.Store;
import com.wys.orderingserver.service.StoreService;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 H2 执行真实门店修改 SQL，覆盖权限、部分更新、字段边界与异常回滚。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:store-update-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:store-update-setup.sql"
})
@AutoConfigureMockMvc
@Sql("/store-update-reset.sql")
class StoreUpdateIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StoreService storeService;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @MockitoSpyBean private StoreMapper storeMapper;
    @MockitoBean private StringRedisTemplate redisTemplate;

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
    void storeAdminUpdatesOwnStoreAndLeavesAllOtherStoresAndEmployeesUnchanged() throws Exception {
        Map<String, Object> before = row(1);
        List<Map<String, Object>> others = otherStores(1);
        List<Map<String, Object>> employees = jdbcTemplate.queryForList("select * from t_employee order by id");
        LocalDateTime beforeCall = LocalDateTime.now().minusSeconds(1);
        mockMvc.perform(request(staffToken()).content("""
                {"id":1,"name":"  望京旗舰店  ","address":"新地址","phone":"010-12345678",
                 "openTime":"10:00:00","closeTime":"22:00:00","notice":"欢迎光临","status":0}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        assertEquals("望京旗舰店", field(1, "name", String.class));
        assertEquals("新地址", field(1, "address", String.class));
        assertEquals("010-12345678", field(1, "phone", String.class));
        assertEquals(LocalTime.of(10, 0), field(1, "open_time", LocalTime.class));
        assertEquals(LocalTime.of(22, 0), field(1, "close_time", LocalTime.class));
        assertEquals("欢迎光临", field(1, "notice", String.class));
        assertEquals(0, field(1, "status", Integer.class));
        assertEquals(before.get("CREATE_TIME"), row(1).get("CREATE_TIME"));
        LocalDateTime updated = field(1, "update_time", LocalDateTime.class);
        assertFalse(updated.isBefore(beforeCall));
        assertFalse(updated.isAfter(LocalDateTime.now().plusSeconds(1)));
        assertEquals(others, otherStores(1));
        assertEquals(employees, jdbcTemplate.queryForList("select * from t_employee order by id"));
    }

    @Test
    void omittedOptionalFieldsPreserveExistingValuesAndDoNotResetClosedStatus() throws Exception {
        Map<String, Object> before = row(3);
        mockMvc.perform(request(superToken()).content("{\"id\":3,\"name\":\"夜宵新店名\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(unchangedWhenRenaming(before), unchangedWhenRenaming(row(3)));
        assertEquals(0, field(3, "status", Integer.class));
    }

    @Test
    void explicitNullAlsoPreservesOptionalFields() throws Exception {
        Map<String, Object> before = row(1);
        mockMvc.perform(request(staffToken()).content("""
                {"id":1,"name":"新名称","address":null,"phone":null,"openTime":null,
                 "closeTime":null,"notice":null,"status":null}
                """))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(unchangedWhenRenaming(before), unchangedWhenRenaming(row(1)));
    }

    @Test
    void emptyOptionalStringsClearAddressPhoneAndNotice() throws Exception {
        mockMvc.perform(request(staffToken()).content("{\"id\":1,\"name\":\"新名称\",\"address\":\"\",\"phone\":\"\",\"notice\":\"\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("", field(1, "address", String.class));
        assertEquals("", field(1, "phone", String.class));
        assertEquals("", field(1, "notice", String.class));
        assertEquals(LocalTime.of(9, 0), field(1, "open_time", LocalTime.class));
    }

    @Test
    void overnightOpeningHoursAreAllowed() throws Exception {
        mockMvc.perform(request(staffToken()).content("{\"id\":1,\"name\":\"夜宵店\",\"openTime\":\"22:00:00\",\"closeTime\":\"02:00:00\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(LocalTime.of(2, 0), field(1, "close_time", LocalTime.class));
    }

    @Test
    void requestCannotOverwriteCreationOrUpdateTimes() throws Exception {
        Object created = row(1).get("CREATE_TIME");
        mockMvc.perform(request(staffToken()).content("""
                {"id":1,"name":"新名称","createTime":"2099-01-01 00:00:00","updateTime":"2099-01-01 00:00:00"}
                """))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(created, row(1).get("CREATE_TIME"));
        assertNotEquals(LocalDateTime.of(2099, 1, 1, 0, 0), field(1, "update_time", LocalDateTime.class));
    }

    @Test
    void missingIdIsRejected() throws Exception {
        assertRejectedWithoutChange(staffToken(), "{\"name\":\"新名称\"}", "门店ID不能为空");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":1}", "{\"id\":1,\"name\":null}", "{\"id\":1,\"name\":\"\"}",
            "{\"id\":1,\"name\":\"   \"}", "{\"id\":1,\"name\":\"　\"}"})
    void nameIsRequiredAndCannotBeBlank(String body) throws Exception {
        assertRejectedWithoutChange(staffToken(), body, "门店名称不能为空");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveIdIsRejected(long id) throws Exception {
        assertRejectedWithoutChange(superToken(), "{\"id\":" + id + ",\"name\":\"新名称\"}", "门店ID不合法");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 2})
    void statusMustBeZeroOrOne(int storeStatus) throws Exception {
        assertRejectedWithoutChange(staffToken(), "{\"id\":1,\"name\":\"新名称\",\"status\":" + storeStatus + "}", "门店状态只能为0或1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{bad-json", "{\"id\":\"abc\",\"name\":\"新名称\"}",
            "{\"id\":1,\"name\":\"新名称\",\"openTime\":\"25:00:00\"}",
            "{\"id\":1,\"name\":\"新名称\",\"closeTime\":\"09:00\"}"})
    void malformedBodyOrTimeIsRejected(String body) throws Exception {
        assertRejectedWithoutChange(staffToken(), body, "请求参数格式错误，请检查请求体是否为合法 JSON");
    }

    @ParameterizedTest
    @CsvSource({"name,100", "address,255", "phone,20", "notice,500"})
    void exactColumnLengthIsAcceptedWithoutTruncation(String column, int max) throws Exception {
        String value = "字".repeat(max);
        mockMvc.perform(request(staffToken()).content(bodyWithText(column, value)))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(value, field(1, column, String.class));
    }

    @ParameterizedTest
    @CsvSource({"name,100,门店名称", "address,255,门店地址", "phone,20,门店电话", "notice,500,门店公告"})
    void oversizedTextIsRejectedBeforeWriting(String column, int max, String label) throws Exception {
        assertRejectedWithoutChange(staffToken(), bodyWithText(column, "字".repeat(max + 1)), label + "不能超过" + max + "个字符");
    }

    @Test
    void storeAdminCannotModifyAnotherStoreEvenWithQueryParameters() throws Exception {
        List<Map<String, Object>> before = allStores();
        mockMvc.perform(request(staffToken()).param("storeId", "1").param("id", "1")
                        .content("{\"id\":2,\"name\":\"不能修改\"}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("只能修改当前门店的信息"));
        assertEquals(before, allStores());
        verify(storeMapper, never()).updateStore(any(Store.class));
    }

    @Test
    void unboundSuperAdminCanModifyAnyStore() throws Exception {
        mockMvc.perform(request(superToken()).content("{\"id\":2,\"name\":\"超管修改\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("超管修改", field(2, "name", String.class));
        assertEquals("望京SOHO店", field(1, "name", String.class));
    }

    @Test
    void boundSuperAdminCanAlsoModifyStoreOutsideItsBinding() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 3, "role", 1, "storeId", 1L)))
                        .content("{\"id\":2,\"name\":\"超管修改其他门店\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("超管修改其他门店", field(2, "name", String.class));
    }

    @Test
    void secondStoreAdminCanModifyOwnStore() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 5, "role", 2, "storeId", 2L)))
                        .content("{\"id\":2,\"name\":\"二店新名称\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("二店新名称", field(2, "name", String.class));
    }

    @Test
    void unboundStoreAdminIsRejected() throws Exception {
        assertRejectedWithoutChange(tokenFor(Map.of("id", 4, "role", 2)), "{\"id\":1,\"name\":\"不能修改\"}",
                "当前员工未绑定门店，请使用已绑定门店的管理员账号");
    }

    @Test
    void customerIsRejected() throws Exception {
        assertRejectedWithoutChange(tokenFor(Map.of("id", 99)), "{\"id\":1,\"name\":\"不能修改\"}", "无门店管理权限");
    }

    @Test
    void unknownRoleIsRejected() throws Exception {
        assertRejectedWithoutChange(tokenFor(Map.of("id", 2, "role", 3, "storeId", 1L)), "{\"id\":1,\"name\":\"不能修改\"}", "无门店管理权限");
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(put("/admin/store").contentType(MediaType.APPLICATION_JSON).content("{\"id\":1,\"name\":\"不能修改\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals("望京SOHO店", field(1, "name", String.class));
    }

    @Test
    void nonexistentStoreIsRejected() throws Exception {
        assertRejectedWithoutChange(superToken(), "{\"id\":999,\"name\":\"不存在\"}", "门店不存在");
    }

    @Test
    void bigintIdIsSupported() throws Exception {
        mockMvc.perform(request(superToken()).content("{\"id\":3000000000,\"name\":\"大ID新名称\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("大ID新名称", field(3000000000L, "name", String.class));
        assertEquals("望京SOHO店", field(1, "name", String.class));
    }

    @Test
    void pageQueryImmediatelySeesUpdatedNameAndStatus() throws Exception {
        String token = staffToken();
        mockMvc.perform(request(token).content("{\"id\":1,\"name\":\"旗舰新名称\",\"status\":0}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/admin/store/page").header("Authorization", token)
                        .param("pageNum", "1").param("pageSize", "10").param("name", "旗舰").param("status", "0"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("旗舰新名称"))
                .andExpect(jsonPath("$.data.items[0].status").value(0));
    }

    @Test
    void directServiceCallsAlsoValidateIdentityAndRequiredFields() {
        assertThrows(BizException.class, () -> storeService.updateStore(store(1, "新名称")));
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            assertThrows(BizException.class, () -> storeService.updateStore(null));
            assertThrows(BizException.class, () -> storeService.updateStore(new Store()));
            assertThrows(BizException.class, () -> storeService.updateStore(store(1, "  ")));
            assertThrows(BizException.class, () -> storeService.updateStore(store(1, "字".repeat(101))));
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals("望京SOHO店", field(1, "name", String.class));
    }

    @Test
    void nullMapperIdCannotUpdateAllStores() {
        List<Map<String, Object>> before = allStores();
        Store store = new Store();
        store.setName("不能修改所有店");
        store.setUpdateTime(LocalDateTime.now());
        assertEquals(0, storeMapper.updateStore(store));
        assertEquals(before, allStores());
    }

    @Test
    void zeroAffectedRowsRollBackAnActualDatabaseUpdate() {
        assertRollbackAfterWrite(false);
    }

    @Test
    void databaseFailureRollsBackAnActualDatabaseUpdate() {
        assertRollbackAfterWrite(true);
    }

    @Test
    void concurrentPartialUpdatesPreserveBothSetsOfOptionalFields() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Store phone = store(1, "并发门店");
        phone.setPhone("010-12345678");
        Store address = store(1, "并发门店");
        address.setAddress("并发新地址");
        try {
            Future<?> first = executor.submit(() -> updateTogether(phone, barrier));
            Future<?> second = executor.submit(() -> updateTogether(address, barrier));
            first.get(8, TimeUnit.SECONDS);
            second.get(8, TimeUnit.SECONDS);
            assertEquals("010-12345678", field(1, "phone", String.class));
            assertEquals("并发新地址", field(1, "address", String.class));
            assertEquals("原公告", field(1, "notice", String.class));
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertRollbackAfterWrite(boolean databaseError) {
        Map<String, Object> before = row(1);
        doAnswer(invocation -> {
            assertEquals(1, sqlSessionTemplate.update("com.wys.orderingserver.mapper.StoreMapper.updateStore", invocation.getArgument(0)));
            if (databaseError) {
                throw new DataIntegrityViolationException("模拟写入后异常");
            }
            return 0;
        }).when(storeMapper).updateStore(any(Store.class));
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            if (databaseError) {
                assertThrows(DataIntegrityViolationException.class, () -> storeService.updateStore(store(1, "应该回滚")));
            } else {
                assertThrows(BizException.class, () -> storeService.updateStore(store(1, "应该回滚")));
            }
        } finally {
            ThreadLocalUtil.remove();
        }
        assertEquals(before, row(1));
    }

    private void updateTogether(Store store, CyclicBarrier barrier) {
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            barrier.await(5, TimeUnit.SECONDS);
            storeService.updateStore(store);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    private Store store(long id, String name) {
        Store store = new Store();
        store.setId(id);
        store.setName(name);
        return store;
    }

    private String bodyWithText(String column, String value) {
        if (column.equals("name")) {
            return "{\"id\":1,\"name\":\"" + value + "\"}";
        }
        return "{\"id\":1,\"name\":\"新名称\",\"" + column + "\":\"" + value + "\"}";
    }

    private void assertRejectedWithoutChange(String token, String body, String message) throws Exception {
        List<Map<String, Object>> before = allStores();
        mockMvc.perform(request(token).content(body)).andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value(message));
        assertEquals(before, allStores());
    }

    private Map<String, Object> unchangedWhenRenaming(Map<String, Object> source) {
        Map<String, Object> fields = new HashMap<>(source);
        fields.remove("NAME");
        fields.remove("UPDATE_TIME");
        return fields;
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("select * from t_store where id = ?", id);
    }

    private List<Map<String, Object>> allStores() {
        return jdbcTemplate.queryForList("select * from t_store order by id");
    }

    private List<Map<String, Object>> otherStores(long id) {
        return jdbcTemplate.queryForList("select * from t_store where id <> ? order by id", id);
    }

    private <T> T field(long id, String column, Class<T> type) {
        return jdbcTemplate.queryForObject("select " + column + " from t_store where id = ?", type, id);
    }

    private String staffToken() {
        return tokenFor(Map.of("id", 2, "role", 2, "storeId", 1L));
    }

    private String superToken() {
        return tokenFor(Map.of("id", 1, "role", 1));
    }

    private String tokenFor(Map<String, Object> claims) {
        String token = JwtUtil.genToken(claims);
        when(tokenOperations.get(token)).thenReturn(token);
        return token;
    }

    private MockHttpServletRequestBuilder request(String token) {
        return put("/admin/store").header("Authorization", token).contentType(MediaType.APPLICATION_JSON);
    }
}
