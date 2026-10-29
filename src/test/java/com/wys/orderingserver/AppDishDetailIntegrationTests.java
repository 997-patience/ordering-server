package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.DishMapper;
import com.wys.orderingserver.service.AppDishService;
import com.wys.orderingserver.support.DishRedisStub;
import com.wys.orderingserver.utils.JwtUtil;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立 H2 执行真实详情 SQL，验证路径 ID、单对象响应、上下架状态和错误提示。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:app-dish-detail-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql,classpath:dish-add-test.sql,classpath:user-login-test.sql,classpath:dish-delete-setup.sql"
})
@AutoConfigureMockMvc
@Sql("/app-dish-list-data.sql")
class AppDishDetailIntegrationTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AppDishService appDishService;
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
    void pathIdReturnsSingleDishWithoutRequiringStoreIdAndContainsDocumentedFields() throws Exception {
        mockMvc.perform(get("/app/dish/101").header("Authorization", userToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data").value(aMapWithSize(9)))
                .andExpect(jsonPath("$.data.id").value(101))
                .andExpect(jsonPath("$.data.storeId").value(1))
                .andExpect(jsonPath("$.data.categoryId").value(11))
                .andExpect(jsonPath("$.data.name").value("宫保鸡丁"))
                .andExpect(jsonPath("$.data.price").value(38.00))
                .andExpect(jsonPath("$.data.image").value("/upload/chicken.jpg"))
                .andExpect(jsonPath("$.data.description").value("微辣"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.sort").value(2));
        verify(dishMapper).findById(101L);
        verify(dishMapper, never()).list(any(), any(), any(), any());
    }

    @Test
    void offSaleDishIsStillReturnedWithStatusZeroAsDocumented() throws Exception {
        mockMvc.perform(get("/app/dish/102").header("Authorization", userToken()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(102))
                .andExpect(jsonPath("$.data.status").value(0));
    }

    @Test
    void customerCanReadDishFromAnotherStoreUsingItsGloballyUniqueId() throws Exception {
        mockMvc.perform(get("/app/dish/201").header("Authorization", userToken()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(201))
                .andExpect(jsonPath("$.data.storeId").value(2));
    }

    @Test
    void unknownDishUsesDocumentedFailureResponse() throws Exception {
        mockMvc.perform(get("/app/dish/999").header("Authorization", userToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("菜品不存在"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositivePathIdIsRejectedBeforeQuery(long id) throws Exception {
        mockMvc.perform(get("/app/dish/" + id).header("Authorization", userToken()))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请选择有效的菜品ID"));
        verifyNoInteractions(dishMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "9223372036854775808"})
    void malformedPathIdUsesExistingGlobalTypeError(String id) throws Exception {
        mockMvc.perform(get("/app/dish/" + id).header("Authorization", userToken()))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("参数类型错误：id"));
        verifyNoInteractions(dishMapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void serviceAlsoValidatesIdsForDirectCalls(Long id) {
        BizException error = assertThrows(BizException.class, () -> appDishService.getDetail(id));
        assertEquals("请选择有效的菜品ID", error.getMessage());
        verifyNoInteractions(dishMapper);
    }

    @Test
    void longDishIdLargerThanIntegerRangeIsSupported() throws Exception {
        jdbcTemplate.update("insert into t_dish(id, store_id, category_id, name, price, status, sort, create_time, update_time) " +
                "values (3000000001, 1, 11, '大ID菜品', 12.50, 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        mockMvc.perform(get("/app/dish/3000000001").header("Authorization", userToken()))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(3000000001L));
    }

    @Test
    void queryParametersCannotReplaceTheDishIdFromPath() throws Exception {
        mockMvc.perform(get("/app/dish/101").header("Authorization", userToken())
                        .param("id", "201").param("storeId", "2"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(101))
                .andExpect(jsonPath("$.data.storeId").value(1));
        verify(dishMapper).findById(101L);
        verify(dishMapper, never()).findById(201L);
    }

    @Test
    void missingTokenReturnsEmpty401BeforeDishQuery() throws Exception {
        mockMvc.perform(get("/app/dish/101"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        verifyNoInteractions(dishMapper);
    }

    @Test
    void adminTokenCannotEnterUserDetailEndpoint() throws Exception {
        String token = cacheToken(Map.of("id", 1, "role", 1, "type", "admin"));
        mockMvc.perform(get("/app/dish/101").header("Authorization", token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(dishMapper);
    }

    @Test
    void revokedUserTokenCannotEnterDetailEndpoint() throws Exception {
        String token = userToken();
        redis.values.remove(token);
        mockMvc.perform(get("/app/dish/101").header("Authorization", token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(dishMapper);
    }

    @Test
    void detailReadsLatestPriceAndStatusWithoutUsingListCache() throws Exception {
        String token = userToken();
        mockMvc.perform(get("/app/dish/101").header("Authorization", token))
                .andExpect(jsonPath("$.data.price").value(38.00));
        jdbcTemplate.update("update t_dish set price = 42.50, status = 0 where id = 101");
        mockMvc.perform(get("/app/dish/101").header("Authorization", token))
                .andExpect(jsonPath("$.data.price").value(42.50))
                .andExpect(jsonPath("$.data.status").value(0));
        verify(dishMapper, times(2)).findById(101L);
        verify(redis.operations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void deletedDishIsNotReturnedFromAnOldCachedDetail() throws Exception {
        String token = userToken();
        mockMvc.perform(get("/app/dish/101").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(0));
        jdbcTemplate.update("delete from t_dish where id = 101");
        mockMvc.perform(get("/app/dish/101").header("Authorization", token))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("菜品不存在"));
    }

    @Test
    void fixedListRouteStillUsesListQueryAndExcludesOffSaleDishes() throws Exception {
        mockMvc.perform(get("/app/dish/list").header("Authorization", userToken()).param("storeId", "1"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(3));
        verify(dishMapper).list(null, 1, null, 1L);
        verify(dishMapper, never()).findById(anyLong());
    }

    private String userToken() {
        return cacheToken(Map.of("id", 1L, "phone", "13800000001", "type", "user"));
    }

    private String cacheToken(Map<String, Object> claims) {
        String token = JwtUtil.genToken(claims);
        redis.values.put(token, token);
        return token;
    }
}
