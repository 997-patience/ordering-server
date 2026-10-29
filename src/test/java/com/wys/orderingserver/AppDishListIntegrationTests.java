package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.DishMapper;
import com.wys.orderingserver.pojo.AppDishVO;
import com.wys.orderingserver.pojo.Dish;
import com.wys.orderingserver.service.AppDishService;
import com.wys.orderingserver.service.DishService;
import com.wys.orderingserver.service.StoreService;
import com.wys.orderingserver.support.DishRedisStub;
import com.wys.orderingserver.utils.DishCacheUtil;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 H2 SQL + 带状态的模拟 Redis；验证缓存策略、并发回源和管理端失效链。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:app-dish-list-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql,classpath:dish-add-test.sql,classpath:user-login-test.sql,classpath:dish-delete-setup.sql"
})
@AutoConfigureMockMvc
@Sql("/app-dish-list-data.sql")
class AppDishListIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private AppDishService appDishService;
    @Autowired private DishService dishService;
    @Autowired private StoreService storeService;
    @Autowired private DishCacheUtil dishCacheUtil;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private DishMapper dishMapper;
    @MockitoBean private StringRedisTemplate redisTemplate;
    private DishRedisStub redis;

    @BeforeEach
    void prepareRedis() {
        ThreadLocalUtil.remove();
        redis = new DishRedisStub(redisTemplate);
    }

    @AfterEach
    void contextIsCleared() {
        assertNull(ThreadLocalUtil.get());
    }

    @Test
    void optionalCategoryListsOnlySelectedStoresOnSaleDishesInOrderAndDocumentedFields() throws Exception {
        mockMvc.perform(get("/app/dish/list").header("Authorization", userToken()).param("storeId", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].id").value(103))
                .andExpect(jsonPath("$.data[1].id").value(101))
                .andExpect(jsonPath("$.data[2].id").value(104))
                .andExpect(jsonPath("$.data[1].price").value(38.00))
                .andExpect(jsonPath("$.data[1].storeId").value(1))
                .andExpect(jsonPath("$.data[1].categoryId").value(11))
                .andExpect(jsonPath("$.data[1].image").value("/upload/chicken.jpg"))
                .andExpect(jsonPath("$.data[1].description").value("微辣"))
                .andExpect(jsonPath("$.data[1].status").value(1))
                .andExpect(jsonPath("$.data[1]").value(aMapWithSize(9)));
    }

    @Test
    void categoryFiltersDishesAndCacheHitSkipsDatabaseQuery() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(get("/app/dish/list").header("Authorization", userToken())
                            .param("storeId", "1").param("categoryId", "11"))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(101));
        }
        verify(dishMapper, times(1)).list(null, 1, 11L, 1L);
        assertTrue(redis.values.containsKey("dish:1:11"));
        assertFalse(redis.values.containsKey("lock:dish:1:11"));
        long seconds = redis.ttls.get("dish:1:11").toSeconds();
        assertTrue(seconds >= 1801 && seconds <= 2100);
    }

    @Test
    void allCategoriesAndSpecificCategoryHaveDifferentCacheKeys() {
        assertEquals(3, appDishService.find(1L, null).size());
        assertEquals(1, appDishService.find(1L, 11L).size());
        assertTrue(redis.values.keySet().containsAll(List.of("dish:1:all", "dish:1:11")));
    }

    @Test
    void sameCategoryIdOnDifferentStoresNeverSharesDataOrEmptyCache() {
        assertTrue(appDishService.find(1L, 21L).isEmpty());
        assertEquals(201L, appDishService.find(2L, 21L).getFirst().getId());
        assertEquals("[]", redis.values.get("dish:1:21"));
        assertNotEquals(redis.values.get("dish:1:21"), redis.values.get("dish:2:21"));
    }

    @Test
    void emptyResultIsCachedForSixtySecondsAndSecondRequestDoesNotQueryAgain() {
        assertTrue(appDishService.find(1L, 999L).isEmpty());
        assertTrue(appDishService.find(1L, 999L).isEmpty());
        assertEquals("[]", redis.values.get("dish:1:999"));
        assertEquals(Duration.ofSeconds(60), redis.ttls.get("dish:1:999"));
        verify(dishMapper, times(1)).list(null, 1, 999L, 1L);
    }

    @Test
    void categoryLargerThanIntegerRangeIsSupported() {
        assertEquals(104L, appDishService.find(1L, 3000000000L).getFirst().getId());
        assertTrue(redis.values.containsKey("dish:1:3000000000"));
    }

    @Test
    void unknownStoreReturnsEmptyArray() {
        assertTrue(appDishService.find(999L, null).isEmpty());
        assertEquals("[]", redis.values.get("dish:999:all"));
    }

    @Test
    void equalSortUsesIdForStableOrder() {
        jdbcTemplate.update("insert into t_dish(id, store_id, category_id, name, price, status, sort, create_time, update_time) " +
                "values (105, 1, 11, '同排序', 1.00, 1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        assertEquals(List.of(101L, 105L), appDishService.find(1L, 11L).stream().map(AppDishVO::getId).toList());
    }

    @ParameterizedTest
    @CsvSource({"storeId,0", "storeId,-1", "categoryId,0", "categoryId,-1"})
    void invalidIdsAreRejectedBeforeQuery(String parameter, String value) throws Exception {
        var request = get("/app/dish/list").header("Authorization", userToken());
        if (!parameter.equals("storeId")) request.param("storeId", "1");
        mockMvc.perform(request.param(parameter, value))
                .andExpect(jsonPath("$.code").value(1));
        verifyNoInteractions(dishMapper);
        verify(redis.operations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @ParameterizedTest
    @CsvSource({"storeId,abc", "storeId,1.5", "categoryId,abc", "categoryId,9223372036854775808"})
    void malformedIdsUseGlobalParameterError(String parameter, String value) throws Exception {
        var request = get("/app/dish/list").header("Authorization", userToken());
        if (!parameter.equals("storeId")) request.param("storeId", "1");
        mockMvc.perform(request.param(parameter, value))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：" + parameter));
        verifyNoInteractions(dishMapper);
    }

    @Test
    void missingStoreIdUsesGlobalRequiredParameterError() throws Exception {
        mockMvc.perform(get("/app/dish/list").header("Authorization", userToken()))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("缺少必传参数：storeId"));
        verifyNoInteractions(dishMapper);
    }

    @Test
    void directServiceNullStoreIdIsRejected() {
        assertThrows(BizException.class, () -> appDishService.find(null, null));
        verifyNoInteractions(dishMapper);
    }

    @Test
    void missingTokenAndAdminTokenCannotQueryAppDishes() throws Exception {
        mockMvc.perform(get("/app/dish/list").param("storeId", "1"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mockMvc.perform(get("/app/dish/list").header("Authorization",
                        token(Map.of("id", 1, "role", 1, "type", "admin"))).param("storeId", "1"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(dishMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "[null]", "{}",
            "[{\"id\":201,\"storeId\":2,\"categoryId\":11,\"status\":1}]",
            "[{\"id\":102,\"storeId\":1,\"categoryId\":11,\"status\":0}]"})
    void corruptOrOutOfScopeCachedDataIsRebuiltInsteadOfReturned(String cached) {
        redis.values.put("dish:1:11", cached);
        assertEquals(101L, appDishService.find(1L, 11L).getFirst().getId());
        verify(dishMapper).list(null, 1, 11L, 1L);
    }

    @Test
    void cacheReadFailureFallsBackToSingleDatabaseQuery() {
        when(redis.operations.get("dish:1:all")).thenThrow(new RedisConnectionFailureException("test outage"));
        assertEquals(3, appDishService.find(1L, null).size());
        verify(dishMapper, times(1)).list(null, 1, null, 1L);
    }

    @Test
    void lockAcquisitionFailureFallsBackToDatabase() {
        when(redis.operations.setIfAbsent(eq("lock:dish:1:all"), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("test outage"));
        assertEquals(3, appDishService.find(1L, null).size());
        verify(dishMapper, times(1)).list(null, 1, null, 1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cacheWriteFailureDoesNotTurnSuccessfulQueryIntoFailureOrRepeatSql() {
        doAnswer(call -> {
            List<String> keys = call.getArgument(1);
            if (keys.size() == 3) throw new RedisConnectionFailureException("test write outage");
            Object[] args = (Object[]) call.getRawArguments()[2];
            return redis.values.remove(keys.getFirst(), args[0]) ? 1L : 0L;
        }).when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
        assertEquals(3, appDishService.find(1L, null).size());
        verify(dishMapper, times(1)).list(null, 1, null, 1L);
        assertFalse(redis.values.containsKey("lock:dish:1:all"));
    }

    @Test
    void successfulOtherRebuilderIsSeenAfterAcquiringLock() throws Exception {
        String cached = objectMapper.writeValueAsString(sample());
        when(redis.operations.setIfAbsent(eq("lock:dish:1:11"), anyString(), any(Duration.class)))
                .thenAnswer(call -> {
                    redis.values.put("dish:1:11", cached);
                    redis.values.put("lock:dish:1:11", call.getArgument(1));
                    return true;
                });
        assertEquals(sample(), appDishService.find(1L, 11L));
        verifyNoInteractions(dishMapper);
        assertFalse(redis.values.containsKey("lock:dish:1:11"));
    }

    @Test
    void concurrentMissesRebuildOnlyOnce() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch waiters = new CountDownLatch(7);
        var waitingThreads = ConcurrentHashMap.<Long>newKeySet();
        when(redis.operations.setIfAbsent(eq("lock:dish:1:11"), anyString(), any(Duration.class)))
                .thenAnswer(call -> {
                    boolean acquired = redis.values.putIfAbsent("lock:dish:1:11", call.getArgument(1)) == null;
                    if (acquired) {
                        redis.ttls.put("lock:dish:1:11", call.getArgument(2));
                    } else if (waitingThreads.add(Thread.currentThread().threadId())) {
                        waiters.countDown();
                    }
                    return acquired;
                });
        Supplier<List<AppDishVO>> loader = () -> {
            loads.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("查询未释放");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return sample();
        };
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<List<AppDishVO>>> results = new ArrayList<>();
        try {
            results.add(executor.submit(() -> dishCacheUtil.getOrLoad(1L, 11L, loader)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 7; index++) {
                results.add(executor.submit(() -> dishCacheUtil.getOrLoad(1L, 11L, loader)));
            }
            // 确认七个请求都在冷缓存上竞争过锁，避免退化成缓存已热后的顺序命中测试。
            assertTrue(waiters.await(2, TimeUnit.SECONDS));
            release.countDown();
            for (Future<List<AppDishVO>> result : results) assertEquals(sample(), result.get(4, TimeUnit.SECONDS));
            assertEquals(1, loads.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(4, TimeUnit.SECONDS));
        }
    }

    @Test
    void databaseFailureReleasesLockAndDoesNotGetRetriedAsRedisFailure() {
        AtomicInteger loads = new AtomicInteger();
        DataIntegrityViolationException failure = new DataIntegrityViolationException("test database failure");
        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> dishCacheUtil.getOrLoad(1L, 11L, () -> { loads.incrementAndGet(); throw failure; })));
        assertEquals(1, loads.get());
        assertFalse(redis.values.containsKey("lock:dish:1:11"));
        assertFalse(redis.values.containsKey("dish:1:11"));
    }

    @Test
    void expiredOwnersCannotPublishOrDeleteNewOwnersLock() {
        dishCacheUtil.getOrLoad(1L, 11L, () -> {
            redis.values.put("lock:dish:1:11", "new-owner");
            return sample();
        });
        assertEquals("new-owner", redis.values.get("lock:dish:1:11"));
        assertFalse(redis.values.containsKey("dish:1:11"));
    }

    @Test
    void writeDuringOldReadPreventsOldResultFromRefillingCache() {
        dishCacheUtil.getOrLoad(1L, 11L, () -> {
            dishCacheUtil.clearAfterCommit(1L);
            return sample();
        });
        assertEquals("1", redis.values.get("dish:version:1"));
        assertFalse(redis.values.containsKey("dish:1:11"));
        assertEquals(101L, appDishService.find(1L, 11L).getFirst().getId());
        assertTrue(redis.values.containsKey("dish:1:11"));
    }

    @Test
    void busyLockEventuallyReturnsRetryMessageWithoutStampedingDatabase() {
        redis.values.put("lock:dish:1:11", "other-owner");
        BizException error = assertThrows(BizException.class, () -> appDishService.find(1L, 11L));
        assertEquals("菜品正在加载，请稍后重试", error.getMessage());
        assertEquals("other-owner", redis.values.get("lock:dish:1:11"));
        verifyNoInteractions(dishMapper);
    }

    @Test
    void interruptedWaitKeepsInterruptFlagAndDoesNotQuery() {
        redis.values.put("lock:dish:1:11", "other-owner");
        Thread.currentThread().interrupt();
        try {
            assertThrows(BizException.class, () -> appDishService.find(1L, 11L));
            assertTrue(Thread.currentThread().isInterrupted());
            verifyNoInteractions(dishMapper);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void addingDishEvictsEmptyAndAllListsAfterCommitAndKeepsOtherStoresCache() {
        jdbcTemplate.update("insert into t_category(id, store_id, name, sort, status) values (13, 1, '新分类', 4, 1)");
        assertTrue(appDishService.find(1L, 13L).isEmpty());
        appDishService.find(1L, null);
        appDishService.find(2L, null);
        asAdmin(() -> dishService.addDish(newDish(13L)));
        assertFalse(redis.values.containsKey("dish:1:13"));
        assertFalse(redis.values.containsKey("dish:1:all"));
        assertTrue(redis.values.containsKey("dish:2:all"));
        assertEquals(1, appDishService.find(1L, 13L).size());
    }

    @Test
    void updateAndStatusAndDeleteInvalidateActualReadCaches() {
        appDishService.find(1L, 11L);
        Dish update = newDish(11L);
        update.setId(101L);
        update.setName("新名字");
        asAdmin(() -> dishService.updateDish(update));
        assertEquals("新名字", appDishService.find(1L, 11L).getFirst().getName());
        asAdmin(() -> dishService.updateDishStatus(101L, 0));
        assertTrue(appDishService.find(1L, 11L).isEmpty());
        asAdmin(() -> dishService.deleteDish(101L));
        assertFalse(redis.values.containsKey("dish:1:11"));
        assertTrue(appDishService.find(1L, 11L).isEmpty());
    }

    @Test
    void rollbackDoesNotEvictCacheOrAdvanceVersion() {
        appDishService.find(1L, null);
        String before = redis.values.get("dish:1:all");
        doAnswer(call -> {
            sqlSessionTemplate.insert("com.wys.orderingserver.mapper.DishMapper.addDish", call.getArgument(0));
            return 0;
        }).when(dishMapper).addDish(any(Dish.class));
        asAdmin(() -> assertThrows(BizException.class, () -> dishService.addDish(newDish(11L))));
        assertEquals(before, redis.values.get("dish:1:all"));
        assertFalse(redis.values.containsKey("dish:version:1"));
        assertEquals(5, jdbcTemplate.queryForObject("select count(*) from t_dish", Integer.class));
    }

    @Test
    void invalidationWaitsUntilCommitAndRollbackPreservesCache() {
        appDishService.find(1L, null);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            dishCacheUtil.clearAfterCommit(1L);
            assertTrue(redis.values.containsKey("dish:1:all"));
            status.setRollbackOnly();
        });
        assertTrue(redis.values.containsKey("dish:1:all"));
        transaction.executeWithoutResult(status -> {
            dishCacheUtil.clearAfterCommit(1L);
            assertTrue(redis.values.containsKey("dish:1:all"));
        });
        assertFalse(redis.values.containsKey("dish:1:all"));
    }

    @Test
    void deletingStoreEvictsItsDishListsAndKeepsOtherStore() {
        appDishService.find(1L, null);
        appDishService.find(2L, null);
        ThreadLocalUtil.set(Map.of("id", 1, "role", 1));
        try {
            storeService.delete(1L);
        } finally {
            ThreadLocalUtil.remove();
        }
        assertFalse(redis.values.containsKey("dish:1:all"));
        assertTrue(redis.values.containsKey("dish:2:all"));
        assertTrue(appDishService.find(1L, null).isEmpty());
    }

    private List<AppDishVO> sample() {
        AppDishVO dish = new AppDishVO();
        dish.setId(101L); dish.setStoreId(1L); dish.setCategoryId(11L);
        dish.setName("测试菜品"); dish.setPrice(new BigDecimal("38.00"));
        dish.setStatus(1); dish.setSort(1);
        return List.of(dish);
    }

    private Dish newDish(Long categoryId) {
        Dish dish = new Dish();
        dish.setName("新增菜品"); dish.setCategoryId(categoryId);
        dish.setPrice(new BigDecimal("12.00"));
        return dish;
    }

    private void asAdmin(Runnable action) {
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try { action.run(); } finally { ThreadLocalUtil.remove(); }
    }

    private String userToken() {
        return token(Map.of("id", 1L, "phone", "13800000001", "type", "user"));
    }

    private String token(Map<String, Object> claims) {
        String token = JwtUtil.genToken(claims);
        redis.values.put(token, token);
        return token;
    }
}
