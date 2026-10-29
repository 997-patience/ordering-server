package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.DishMapper;
import com.wys.orderingserver.pojo.Dish;
import com.wys.orderingserver.service.DishService;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 执行真实新增 SQL，验证 JSON、门店与分类归属、价格、默认值和事务；不访问业务数据库。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dish-add-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:category-add-test.sql,classpath:dish-add-test.sql"
})
@AutoConfigureMockMvc
@Sql("/dish-add-reset.sql")
class DishAddIntegrationTests {

    private static final String MINIMUM_BODY = "{\"name\":\"  宫保鸡丁  \",\"categoryId\":11,\"price\":38}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DishService dishService;

    @Autowired
    private SqlSessionTemplate sqlSessionTemplate;

    @MockitoSpyBean
    private DishMapper dishMapper;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> tokenOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepareRedis() {
        tokenOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(tokenOperations);
        when(redisTemplate.scan(any(org.springframework.data.redis.core.ScanOptions.class)))
                .thenReturn(mock(org.springframework.data.redis.core.Cursor.class));
    }

    @AfterEach
    void identityIsCleared() {
        assertNull(ThreadLocalUtil.get());
    }

    @Test
    void minimumJsonCreatesDishWithoutImageWithServerDefaultsAndTimes() throws Exception {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        mockMvc.perform(request(staffToken()).content(MINIMUM_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));

        assertEquals(1, count());
        assertEquals("宫保鸡丁", jdbcTemplate.queryForObject("select name from t_dish", String.class));
        assertEquals(1L, jdbcTemplate.queryForObject("select store_id from t_dish", Long.class));
        assertEquals(11L, jdbcTemplate.queryForObject("select category_id from t_dish", Long.class));
        assertEquals(new BigDecimal("38.00"), jdbcTemplate.queryForObject("select price from t_dish", BigDecimal.class));
        assertEquals(1, jdbcTemplate.queryForObject("select status from t_dish", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("select sort from t_dish", Integer.class));
        assertNull(jdbcTemplate.queryForObject("select image from t_dish", String.class));
        LocalDateTime created = jdbcTemplate.queryForObject("select create_time from t_dish", LocalDateTime.class);
        LocalDateTime updated = jdbcTemplate.queryForObject("select update_time from t_dish", LocalDateTime.class);
        assertNotNull(created);
        assertFalse(created.isBefore(before));
        assertEquals(created, updated);
    }

    @Test
    void optionalFieldsAndOffShelfStatusAreStored() throws Exception {
        mockMvc.perform(request(staffToken()).content("""
                {"name":"主厨's 菜","categoryId":11,"price":48,
                 "description":"微辣","status":0,"sort":3,"image":""}
                """))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("主厨's 菜", jdbcTemplate.queryForObject("select name from t_dish", String.class));
        assertEquals("微辣", jdbcTemplate.queryForObject("select description from t_dish", String.class));
        assertEquals(0, jdbcTemplate.queryForObject("select status from t_dish", Integer.class));
        assertEquals(3, jdbcTemplate.queryForObject("select sort from t_dish", Integer.class));
        assertNull(jdbcTemplate.queryForObject("select image from t_dish", String.class));
    }

    @Test
    void bodyCannotOverrideStoreIdPrimaryKeyOrServerTimes() throws Exception {
        mockMvc.perform(request(staffToken()).content("""
                {"id":999,"storeId":2,"name":"本店菜品","categoryId":11,"price":8,
                 "createTime":"2000-01-01 00:00:00","updateTime":"2000-01-01 00:00:00"}
                """))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1L, jdbcTemplate.queryForObject("select store_id from t_dish", Long.class));
        assertNotEquals(999L, jdbcTemplate.queryForObject("select id from t_dish", Long.class));
        assertNotEquals(LocalDateTime.of(2000, 1, 1, 0, 0),
                jdbcTemplate.queryForObject("select create_time from t_dish", LocalDateTime.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {21, 999})
    void foreignOrMissingCategoryIsRejected(long categoryId) throws Exception {
        mockMvc.perform(request(staffToken()).content(
                        "{\"name\":\"宫保鸡丁\",\"categoryId\":" + categoryId + ",\"price\":38}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("分类不存在或不属于当前门店"));
        assertEquals(0, count());
    }

    @Test
    void categoryIdSupportsBigintRange() throws Exception {
        mockMvc.perform(request(staffToken()).content(
                        "{\"name\":\"米饭\",\"categoryId\":3000000000,\"price\":2}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(3000000000L, jdbcTemplate.queryForObject("select category_id from t_dish", Long.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"categoryId\":11,\"price\":38}",
            "{\"name\":\"\",\"categoryId\":11,\"price\":38}",
            "{\"name\":\"   \",\"categoryId\":11,\"price\":38}",
            "{\"name\":\"宫保鸡丁\",\"price\":38}",
            "{\"name\":\"宫保鸡丁\",\"categoryId\":0,\"price\":38}",
            "{\"name\":\"宫保鸡丁\",\"categoryId\":-1,\"price\":38}",
            "{\"name\":\"宫保鸡丁\",\"categoryId\":11}",
            "{\"name\":\"宫保鸡丁\",\"categoryId\":11,\"price\":null}"
    })
    void missingOrInvalidRequiredFieldsAreRejected(String body) throws Exception {
        mockMvc.perform(request(staffToken()).content(body))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "0.001", "100000000"})
    void invalidPriceCannotBeRoundedOrOverflowSilently(String price) throws Exception {
        mockMvc.perform(request(staffToken()).content(
                        "{\"name\":\"宫保鸡丁\",\"categoryId\":11,\"price\":" + price + "}"))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "99999999.99", "38.000"})
    void validPriceBoundariesAreStoredExactly(String price) throws Exception {
        mockMvc.perform(request(staffToken()).content(
                        "{\"name\":\"宫保鸡丁\",\"categoryId\":11,\"price\":" + price + "}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0, new BigDecimal(price).compareTo(
                jdbcTemplate.queryForObject("select price from t_dish", BigDecimal.class)));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 2})
    void invalidStatusIsRejected(int dishStatus) throws Exception {
        mockMvc.perform(request(staffToken()).content(
                        "{\"name\":\"宫保鸡丁\",\"categoryId\":11,\"price\":38,\"status\":" + dishStatus + "}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("菜品状态只能为0或1"));
        assertEquals(0, count());
    }

    @ParameterizedTest
    @CsvSource({"name,101", "description,501", "image,256"})
    void overlongFieldsAreRejectedBeforeDatabaseWrite(String field, int length) throws Exception {
        String body = "{\"categoryId\":11,\"price\":38,";
        if (!field.equals("name")) {
            body += "\"name\":\"宫保鸡丁\",";
        }
        body += "\"" + field + "\":\"" + "菜".repeat(length) + "\"}";
        mockMvc.perform(request(staffToken()).content(body))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void unboundAdminCannotChooseStoreUsingRequestBody(int role) throws Exception {
        String token = tokenFor(Map.of("id", role == 1 ? 1 : 4, "role", role));
        mockMvc.perform(request(token).content(
                        "{\"storeId\":1,\"name\":\"宫保鸡丁\",\"categoryId\":11,\"price\":38}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("当前员工未绑定门店，请使用已绑定门店的管理员账号"));
        assertEquals(0, count());
    }

    @Test
    void boundSuperAdminUsesJwtStore() throws Exception {
        String token = tokenFor(Map.of("id", 3, "role", 1, "storeId", 1L));
        mockMvc.perform(request(token).content(MINIMUM_BODY))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1L, jdbcTemplate.queryForObject("select store_id from t_dish", Long.class));
    }

    @Test
    void nonexistentStoreIsRejected() throws Exception {
        String token = tokenFor(Map.of("id", 2, "role", 2, "storeId", 999L));
        mockMvc.perform(request(token).content(MINIMUM_BODY))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("所属门店不存在"));
        assertEquals(0, count());
    }

    @Test
    void customerCannotInsertDish() throws Exception {
        mockMvc.perform(request(tokenFor(Map.of("id", 99))).content(MINIMUM_BODY))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("无门店管理权限"));
        assertEquals(0, count());
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(post("/admin/dish").contentType(MediaType.APPLICATION_JSON).content(MINIMUM_BODY))
                .andExpect(status().isUnauthorized());
        assertEquals(0, count());
    }

    @Test
    void queryParametersCannotReplaceJsonBody() throws Exception {
        mockMvc.perform(request(staffToken()).param("name", "宫保鸡丁").param("categoryId", "11").param("price", "38"))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(0, count());
    }

    @Test
    void serviceBackfillsGeneratedId() {
        Dish dish = validDish();
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            dishService.addDish(dish);
            assertNotNull(dish.getId());
            assertEquals(dish.getId(), jdbcTemplate.queryForObject("select id from t_dish", Long.class));
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    @Test
    void failedRowCountRollsBackActualDatabaseWrite() {
        doAnswer(invocation -> {
            sqlSessionTemplate.insert("com.wys.orderingserver.mapper.DishMapper.addDish", invocation.getArgument(0));
            return 0;
        }).when(dishMapper).addDish(any(Dish.class));
        ThreadLocalUtil.set(Map.of("id", 2, "role", 2, "storeId", 1L));
        try {
            BizException exception = assertThrows(BizException.class, () -> dishService.addDish(validDish()));
            assertEquals("新增菜品失败，请重试", exception.getMessage());
            assertEquals(0, count());
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    private Dish validDish() {
        Dish dish = new Dish();
        dish.setName("宫保鸡丁");
        dish.setCategoryId(11L);
        dish.setPrice(new BigDecimal("38.00"));
        return dish;
    }

    private long count() {
        return jdbcTemplate.queryForObject("select count(*) from t_dish", Long.class);
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
        return post("/admin/dish").header("Authorization", token).contentType(MediaType.APPLICATION_JSON);
    }
}
