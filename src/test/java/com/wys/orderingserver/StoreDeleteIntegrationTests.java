package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.StoreMapper;
import com.wys.orderingserver.service.StoreService;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 对独立内存数据库执行整店删除，验证隔离、回滚、权限和员工旧令牌失效。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:store-delete-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-delete-schema.sql,classpath:db/store-delete-foreign-keys.sql"
})
@AutoConfigureMockMvc
@Sql("/store-delete-data.sql")
class StoreDeleteIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StoreService storeService;

    @MockitoSpyBean
    private StoreMapper storeMapper;

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
    void clearIdentity() {
        ThreadLocalUtil.remove();
    }

    @Test
    void removesAllStoreDataAndKeepsOtherStoreAndSharedCustomers() throws Exception {
        deleteStore(1, adminToken());
        assertEquals(0, count("select count(*) from t_store where id = 1"));
        assertEquals(0, count("select count(*) from t_order_detail where id = 11001"));
        for (String table : new String[]{"t_orders", "t_cart", "t_dish", "t_category", "t_employee"}) {
            assertEquals(0, count("select count(*) from " + table + " where store_id = 1"), table);
            assertEquals(1, count("select count(*) from " + table + " where store_id = 2"), table);
        }
        assertEquals(1, count("select count(*) from t_store where id = 2"));
        assertEquals(1, count("select count(*) from t_order_detail where id = 21001"));
        assertEquals(1, count("select count(*) from t_employee where id = 1"));
        assertEquals(1, count("select count(*) from t_user"));
        assertEquals(1, count("select count(*) from t_address"));
    }

    @Test
    void emptyStoreCanBeDeletedAlongWithItsEmployee() throws Exception {
        deleteStore(3, adminToken());
        assertEquals(0, count("select count(*) from t_store where id = 3"));
        assertEquals(0, count("select count(*) from t_employee where store_id = 3"));
        assertEquals(2, count("select count(*) from t_store"));
    }

    @Test
    void storeAdminCannotDeleteAnyStore() throws Exception {
        mockMvc.perform(delete("/admin/store").param("id", "2").header("Authorization", staffToken(2, 1)))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("只有超管可以删除门店"));
        assertAllOriginalDataPresent();
    }

    @Test
    void customerTokenCannotDeleteStore() throws Exception {
        String token = tokenFor(Map.of("id", 1, "phone", "13800000001"));
        mockMvc.perform(delete("/admin/store").param("id", "1").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(1));
        assertAllOriginalDataPresent();
    }

    @Test
    void missingTokenCannotDeleteStore() throws Exception {
        mockMvc.perform(delete("/admin/store").param("id", "1"))
                .andExpect(status().isUnauthorized());
        assertAllOriginalDataPresent();
    }

    @Test
    void invalidIdDoesNotDeleteData() throws Exception {
        mockMvc.perform(delete("/admin/store").param("id", "0").header("Authorization", adminToken()))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("门店ID不合法"));
        assertAllOriginalDataPresent();
    }

    @Test
    void nonexistentStoreDoesNotDeleteData() throws Exception {
        mockMvc.perform(delete("/admin/store").param("id", "999").header("Authorization", adminToken()))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("门店不存在"));
        assertAllOriginalDataPresent();
    }

    @Test
    void finalDeleteFailureRollsBackEveryEarlierDelete() {
        // 模拟最后一步未删除到门店，确认此前六张关联表的删除全部回滚。
        doReturn(0).when(storeMapper).delete(1L);
        ThreadLocalUtil.set(Map.of("id", 1, "role", 1));
        assertThrows(BizException.class, () -> storeService.delete(1L));
        assertAllOriginalDataPresent();
    }

    @Test
    void sqlExceptionRollsBackEarlierDeletes() {
        doThrow(new DataIntegrityViolationException("模拟菜品删除失败"))
                .when(storeMapper).deleteDishesByStoreId(1L);
        ThreadLocalUtil.set(Map.of("id", 1, "role", 1));
        assertThrows(DataIntegrityViolationException.class, () -> storeService.delete(1L));
        assertAllOriginalDataPresent();
    }

    @Test
    void deletedEmployeesOldTokenIsRejectedEvenWhileRedisStillContainsIt() throws Exception {
        String oldToken = staffToken(2, 1);
        deleteStore(1, adminToken());
        mockMvc.perform(get("/admin/store/page").param("pageNum", "1").param("pageSize", "10")
                        .header("Authorization", oldToken))
                .andExpect(status().isUnauthorized());
        // 另一家门店的员工和登录态不受影响。
        mockMvc.perform(get("/admin/store/page").param("pageNum", "1").param("pageSize", "10")
                        .header("Authorization", staffToken(3, 2)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(2));
    }

    @Test
    void foreignKeyRejectsNewDataForDeletedStore() throws Exception {
        deleteStore(1, adminToken());
        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "insert into t_category(id, store_id, name) values(31, 1, '无效分类')"));
    }

    @Test
    void simultaneousDeletesOnlyOneSucceeds() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> deleteAfterSignal(start));
            var second = executor.submit(() -> deleteAfterSignal(start));
            start.countDown();
            boolean firstDeleted = first.get(10, TimeUnit.SECONDS);
            boolean secondDeleted = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstDeleted, secondDeleted);
        }
        assertEquals(0, count("select count(*) from t_store where id = 1"));
        assertEquals(1, count("select count(*) from t_store where id = 2"));
    }

    private boolean deleteAfterSignal(CountDownLatch start) throws InterruptedException {
        start.await();
        ThreadLocalUtil.set(Map.of("id", 1, "role", 1));
        try {
            storeService.delete(1L);
            return true;
        } catch (BizException e) {
            assertEquals("门店不存在", e.getMessage());
            return false;
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    private void deleteStore(long storeId, String token) throws Exception {
        mockMvc.perform(delete("/admin/store").param("id", Long.toString(storeId))
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private void assertAllOriginalDataPresent() {
        assertEquals(3, count("select count(*) from t_store"));
        assertEquals(4, count("select count(*) from t_employee"));
        for (String table : new String[]{"t_category", "t_dish", "t_orders", "t_order_detail", "t_cart"}) {
            assertEquals(2, count("select count(*) from " + table), table);
        }
    }

    private String adminToken() {
        return tokenFor(Map.of("id", 1, "role", 1));
    }

    private String staffToken(long employeeId, long storeId) {
        return tokenFor(Map.of("id", employeeId, "role", 2, "storeId", storeId));
    }

    private String tokenFor(Map<String, Object> claims) {
        String token = JwtUtil.genToken(claims);
        when(tokenOperations.get(token)).thenReturn(token);
        return token;
    }
}
