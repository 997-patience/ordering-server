package com.wys.orderingserver;

import com.github.pagehelper.PageHelper;
import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.DishMapper;
import com.wys.orderingserver.mapper.OrderDetailMapper;
import com.wys.orderingserver.mapper.OrderMapper;
import com.wys.orderingserver.pojo.AppOrderDetailVO;
import com.wys.orderingserver.pojo.OrderSubmitDTO;
import com.wys.orderingserver.pojo.OrderSubmitVO;
import com.wys.orderingserver.service.AppOrderService;
import com.wys.orderingserver.support.DishRedisStub;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证订单归属、主表与多条快照组装，以及多次查询之间的事务一致性。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:app-order-detail-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql,classpath:dish-add-test.sql,classpath:user-login-test.sql,classpath:order-submit-test.sql"
})
@AutoConfigureMockMvc
@Sql({"/app-dish-list-data.sql", "/app-cart-list-data.sql", "/app-order-detail-data.sql"})
class AppOrderDetailIntegrationTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AppOrderService service;
    @Autowired private SqlSessionTemplate sqlSession;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private OrderMapper orderMapper;
    @MockitoSpyBean private OrderDetailMapper detailMapper;
    @MockitoSpyBean private DishMapper dishMapper;
    @MockitoBean private StringRedisTemplate redisTemplate;
    private DishRedisStub redis;

    @BeforeEach
    void prepare() {
        ThreadLocalUtil.remove();
        redis = new DishRedisStub(redisTemplate);
    }

    @AfterEach
    void contextsAreCleared() {
        assertNull(ThreadLocalUtil.get());
        assertNull(PageHelper.getLocalPage());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void responseContainsDocumentedHeaderAndAllSnapshotItems() throws Exception {
        mvc.perform(request(1, 5001))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.length()").value(14))
                .andExpect(jsonPath("$.data.id").value(5001))
                .andExpect(jsonPath("$.data.orderNo").value("DETAIL-5001"))
                .andExpect(jsonPath("$.data.storeId").value(1))
                .andExpect(jsonPath("$.data.storeName").value("一号店"))
                .andExpect(jsonPath("$.data.amount").value(76.00))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.payStatus").value(1))
                .andExpect(jsonPath("$.data.remark").value("不要香菜"))
                .andExpect(jsonPath("$.data.pickupType").value(1))
                .andExpect(jsonPath("$.data.pickupTime").value("2026-10-11 14:30:00"))
                .andExpect(jsonPath("$.data.createTime").value("2026-10-11 14:09:30"))
                .andExpect(jsonPath("$.data.cancelReason").isEmpty())
                .andExpect(jsonPath("$.data.finishTime").isEmpty())
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.payTime").doesNotExist())
                .andExpect(jsonPath("$.data.updateTime").doesNotExist())
                .andExpect(jsonPath("$.data.details.length()").value(2))
                .andExpect(jsonPath("$.data.details[0].length()").value(6))
                .andExpect(jsonPath("$.data.details[0].dishId").value(101))
                .andExpect(jsonPath("$.data.details[0].dishName").value("下单时的宫保鸡丁"))
                .andExpect(jsonPath("$.data.details[0].dishImage").value("/upload/snapshot.jpg"))
                .andExpect(jsonPath("$.data.details[0].dishPrice").value(38.00))
                .andExpect(jsonPath("$.data.details[0].quantity").value(1))
                .andExpect(jsonPath("$.data.details[0].amount").value(38.00))
                .andExpect(jsonPath("$.data.details[0].orderId").doesNotExist())
                .andExpect(jsonPath("$.data.details[0].id").doesNotExist())
                .andExpect(jsonPath("$.data.details[1].dishId").value(103))
                .andExpect(jsonPath("$.data.details[1].dishPrice").value(19.00))
                .andExpect(jsonPath("$.data.details[1].quantity").value(2))
                .andExpect(jsonPath("$.data.details[1].amount").value(38.00))
                .andExpect(jsonPath("$.data.details[1].dishImage").isEmpty());
        verify(orderMapper).getByIdAndUserId(5001L, 1L);
        verify(detailMapper).listByOrderIdAndUserId(5001L, 1L);
        verifyNoInteractions(dishMapper);
    }

    @Test
    void orderPrimaryKeyIsNotMistakenForDetailPrimaryKey() {
        AppOrderDetailVO result = asUser(1, () -> service.getById(5001L));
        assertEquals(2, result.getDetails().size());
        assertEquals(List.of(101L, 103L), result.getDetails().stream().map(item -> item.getDishId()).toList());
        assertEquals("其他用户的宫保鸡丁", jdbc.queryForObject("select dish_name from t_order_detail where id=5001", String.class));
        assertTrue(result.getDetails().stream().noneMatch(item -> item.getQuantity() == 7));
    }

    @ParameterizedTest
    @ValueSource(longs = {5002, 99999999, Long.MAX_VALUE})
    void foreignOrMissingOrdersReturnSameFailureBeforeReadingDetails(long id) throws Exception {
        mvc.perform(request(1, id))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("订单不存在或无权访问"))
                .andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(detailMapper, dishMapper);
    }

    @Test
    void anotherUserCanReadOwnOrderWithoutLeakingTheFirstUsersOrder() throws Exception {
        mvc.perform(request(2, 5002))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.remark").value("其他用户的备注"))
                .andExpect(jsonPath("$.data.details.length()").value(1))
                .andExpect(jsonPath("$.data.details[0].quantity").value(7));
        mvc.perform(request(2, 5001))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("订单不存在或无权访问"));
    }

    @Test
    void evenDirectDetailMapperQueryIsRestrictedToOrderOwner() {
        assertTrue(detailMapper.listByOrderIdAndUserId(5002L, 1L).isEmpty());
        assertTrue(detailMapper.listByOrderIdAndUserId(5001L, 2L).isEmpty());
        assertEquals(1, detailMapper.listByOrderIdAndUserId(5002L, 2L).size());
    }

    @Test
    void queryParametersCannotOverridePathIdOrLoggedInOwner() throws Exception {
        mvc.perform(request(1, 5001).param("id", "5002").param("orderId", "5002")
                        .param("userId", "2").param("storeId", "2"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(5001))
                .andExpect(jsonPath("$.data.remark").value("不要香菜"));
    }

    @Test
    void usersCanViewOwnOrdersInAnotherStoreAndItsCompletionTime() throws Exception {
        mvc.perform(request(1, 5003))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.storeId").value(2))
                .andExpect(jsonPath("$.data.storeName").value("二号店"))
                .andExpect(jsonPath("$.data.status").value(3))
                .andExpect(jsonPath("$.data.finishTime").value("2026-10-11 15:30:00"))
                .andExpect(jsonPath("$.data.pickupTime").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"5001,1", "5006,2", "5003,3", "5004,4"})
    void allOrderStatesRemainReadable(long id, int orderStatus) throws Exception {
        mvc.perform(request(1, id)).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value(orderStatus));
    }

    @Test
    void closedStoreAndDeletedDishDoNotHideHistoricalCancelledOrder() throws Exception {
        assertEquals(0, jdbc.queryForObject("select status from t_store where id=3", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from t_dish where id=99999", Integer.class));
        mvc.perform(request(1, 5004))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.cancelReason").value("商家超时未接单，订单自动取消"))
                .andExpect(jsonPath("$.data.details[0].dishName").value("现已删除的套餐"))
                .andExpect(jsonPath("$.data.details[0].dishPrice").value(20.00));
    }

    @Test
    void currentMenuRenameRepriceDownsaleAndDeletionDoNotChangeResponse() throws Exception {
        String before = mvc.perform(request(1, 5001)).andReturn().getResponse().getContentAsString();
        jdbc.update("update t_dish set name='新名字',price=99.99,image='/new.jpg',status=0 where id=101");
        jdbc.update("delete from t_dish where id=103");
        String after = mvc.perform(request(1, 5001)).andReturn().getResponse().getContentAsString();
        assertEquals(before, after);
        verifyNoInteractions(dishMapper);
    }

    @Test
    void storeNameUsesCurrentJoinWhileDishInformationKeepsItsSnapshot() throws Exception {
        jdbc.update("update t_store set name='门店新名字',status=0 where id=1");
        mvc.perform(request(1, 5001))
                .andExpect(jsonPath("$.data.storeName").value("门店新名字"))
                .andExpect(jsonPath("$.data.details[0].dishName").value("下单时的宫保鸡丁"));
    }

    @Test
    void noDetailsIsAnEmptyArrayInsteadOfNullOrMissingOrder() throws Exception {
        mvc.perform(request(1, 5005))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.details").isArray())
                .andExpect(jsonPath("$.data.details").isEmpty());
    }

    @Test
    void longOrderAndDishIdsAreNotTruncated() throws Exception {
        mvc.perform(request(1, 3000000000L))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(3000000000L))
                .andExpect(jsonPath("$.data.details[0].dishId").value(3000000001L));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveHttpIdIsRejectedBeforeQuery(long id) throws Exception {
        mvc.perform(request(1, id)).andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请传入合法的订单ID"));
        verifyNoInteractions(orderMapper, detailMapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void serviceValidatesNullBeforeUnboxingAndRejectsNonPositiveIds(Long id) {
        BizException error = assertThrows(BizException.class, () -> asUser(1, () -> service.getById(id)));
        assertEquals("请传入合法的订单ID", error.getMessage());
        verifyNoInteractions(orderMapper, detailMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "9223372036854775808"})
    void malformedPathValuesHaveReadableTypeErrors(String id) throws Exception {
        mvc.perform(get("/app/order/" + id).header("Authorization", token(1)))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：id"));
        verifyNoInteractions(orderMapper, detailMapper);
    }

    @Test
    void listPathStillSelectsListEndpointInsteadOfLongPathConversion() throws Exception {
        mvc.perform(get("/app/order/list").header("Authorization", token(1)).param("pageNum", "1").param("pageSize", "5"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.total").value(6));
        verifyNoInteractions(detailMapper);
    }

    @Test
    void missingRevokedAndAdminTokensCannotReadCustomerDetail() throws Exception {
        mvc.perform(get("/app/order/5001")).andExpect(status().isUnauthorized());
        String revoked = token(1); redis.values.remove(revoked);
        mvc.perform(get("/app/order/5001").header("Authorization", revoked)).andExpect(status().isUnauthorized());
        String admin = JwtUtil.genToken(Map.of("id", 2, "role", 2, "storeId", 1, "type", "admin"));
        redis.values.put(admin, admin);
        mvc.perform(get("/app/order/5001").header("Authorization", admin)).andExpect(status().isUnauthorized());
        verifyNoInteractions(orderMapper, detailMapper);
    }

    @Test
    void directCallsRequireCustomerIdentity() {
        assertThrows(BizException.class, () -> service.getById(5001L));
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "type", "admin", "storeId", 1));
        try { assertThrows(BizException.class, () -> service.getById(5001L)); }
        finally { ThreadLocalUtil.remove(); }
        verifyNoInteractions(orderMapper, detailMapper);
    }

    @Test
    void detailReadFailurePropagatesAndReleasesTransactionContext() {
        doThrow(new DataAccessResourceFailureException("模拟明细读取失败"))
                .when(detailMapper).listByOrderIdAndUserId(5001L, 1L);
        assertThrows(DataAccessResourceFailureException.class, () -> asUser(1, () -> service.getById(5001L)));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(7, jdbc.queryForObject("select count(*) from t_orders", Integer.class));
    }

    @Test
    void newlySubmittedOrderCanImmediatelyBeReadWithItsSavedSnapshot() throws Exception {
        jdbc.update("delete from t_cart where id=1004");
        OrderSubmitDTO dto = new OrderSubmitDTO(); dto.setStoreId(1L); dto.setPickupType(1); dto.setRemark("少辣");
        OrderSubmitVO created = asUser(1, () -> service.submit(dto));
        mvc.perform(request(1, created.getId()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.orderNo").value(created.getOrderNo()))
                .andExpect(jsonPath("$.data.amount").value(76.00))
                .andExpect(jsonPath("$.data.payStatus").value(0))
                .andExpect(jsonPath("$.data.remark").value("少辣"))
                .andExpect(jsonPath("$.data.details.length()").value(1))
                .andExpect(jsonPath("$.data.details[0].dishPrice").value(38.00))
                .andExpect(jsonPath("$.data.details[0].quantity").value(2));
    }

    @Test
    void headerAndItemsUseSameSnapshotDuringConcurrentOrderDeletion() throws Exception {
        AppOrderDetailVO result = readWhileChanging(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.update("delete from t_order_detail where order_id=5001");
            jdbc.update("delete from t_orders where id=5001");
        }));
        assertEquals(5001L, result.getId());
        assertEquals(2, result.getDetails().size());
        assertEquals("订单不存在或无权访问", assertThrows(BizException.class,
                () -> asUser(1, () -> service.getById(5001L))).getMessage());
    }

    @Test
    void headerAndItemsUseSameSnapshotDuringConcurrentChanges() throws Exception {
        AppOrderDetailVO result = readWhileChanging(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.update("update t_orders set amount=100.00,status=2 where id=5001");
            jdbc.update("update t_order_detail set dish_price=50.00,amount=50.00 where id=5002");
            jdbc.update("update t_order_detail set dish_price=25.00,amount=50.00 where id=8001");
        }));
        assertEquals(new BigDecimal("76.00"), result.getAmount());
        assertEquals(1, result.getStatus());
        assertEquals(new BigDecimal("38.00"), result.getDetails().getFirst().getAmount());
        AppOrderDetailVO next = asUser(1, () -> service.getById(5001L));
        assertEquals(new BigDecimal("100.00"), next.getAmount());
        assertEquals(2, next.getStatus());
        assertEquals(new BigDecimal("50.00"), next.getDetails().getFirst().getAmount());
    }

    private AppOrderDetailVO readWhileChanging(Runnable change) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch headerRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            AppOrderDetailVO header = sqlSession.selectOne("com.wys.orderingserver.mapper.OrderMapper.getByIdAndUserId",
                    Map.of("id", 5001L, "userId", 1L));
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            headerRead.countDown();
            assertTrue(release.await(4, TimeUnit.SECONDS));
            return header;
        }).when(orderMapper).getByIdAndUserId(5001L, 1L);
        try {
            Future<AppOrderDetailVO> future = pool.submit(() -> asUser(1, () -> service.getById(5001L)));
            assertTrue(headerRead.await(3, TimeUnit.SECONDS));
            change.run(); release.countDown();
            return future.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown(); pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private MockHttpServletRequestBuilder request(int userId, long id) {
        return get("/app/order/" + id).header("Authorization", token(userId));
    }

    private String token(int userId) {
        String phone = userId == 1 ? "13800000001" : "13900000002";
        String token = JwtUtil.genToken(Map.of("id", userId, "phone", phone, "type", "user"));
        redis.values.put(token, token); return token;
    }

    private <T> T asUser(int userId, Supplier<T> action) {
        ThreadLocalUtil.set(Map.of("id", userId, "type", "user"));
        try { return action.get(); } finally { ThreadLocalUtil.remove(); }
    }
}
