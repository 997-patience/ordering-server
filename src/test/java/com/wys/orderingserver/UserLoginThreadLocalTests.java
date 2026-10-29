package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.Exception.GlobalExceptionHandler;
import com.wys.orderingserver.controller.app.AppLoginController;
import com.wys.orderingserver.mapper.UserMapper;
import com.wys.orderingserver.pojo.User;
import com.wys.orderingserver.service.UserService;
import com.wys.orderingserver.service.impl.UserServiceimpl;
import com.wys.orderingserver.utils.Md5Util;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 不连接数据库或 Redis，验证身份来源、敏感字段排除和白名单登录请求的上下文清理。 */
@ExtendWith(MockitoExtension.class)
class UserLoginThreadLocalTests {

    @Mock private UserMapper userMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> tokenOperations;
    @InjectMocks private UserServiceimpl userService;

    @BeforeEach
    void clearBeforeTest() {
        ThreadLocalUtil.remove();
    }

    @AfterEach
    void clearAfterTest() {
        ThreadLocalUtil.remove();
    }

    @Test
    void successfulLoginStoresDatabaseIdentityAndExcludesPassword() {
        when(userMapper.findByPhone("13900000002")).thenReturn(databaseUser());
        when(redisTemplate.opsForValue()).thenReturn(tokenOperations);
        User request = credentials("123456");
        request.setId(1L);
        request.setNickname("伪造昵称");

        userService.login(request);

        Map<String, Object> claims = ThreadLocalUtil.get();
        assertNotNull(claims);
        assertEquals(88L, claims.get("id"));
        assertEquals("13900000002", claims.get("phone"));
        assertEquals("小明", claims.get("nickname"));
        assertEquals("user", claims.get("type"));
        assertEquals(Set.of("id", "phone", "nickname", "type"), claims.keySet());
        assertFalse(claims.containsValue("123456"));
        assertFalse(claims.containsValue(Md5Util.getMD5String("123456")));
    }

    @Test
    void wrongPasswordDoesNotCreateAuthenticatedContext() {
        when(userMapper.findByPhone("13900000002")).thenReturn(databaseUser());
        assertThrows(BizException.class, () -> userService.login(credentials("654321")));
        assertNull(ThreadLocalUtil.get());
    }

    @Test
    void unregisteredPhoneDoesNotCreateAuthenticatedContext() {
        when(userMapper.findByPhone("13900000002")).thenReturn(null);
        assertThrows(BizException.class, () -> userService.login(credentials("123456")));
        assertNull(ThreadLocalUtil.get());
    }

    @Test
    void loginControllerClearsContextAfterSuccessfulRequest() throws Exception {
        UserService service = mock(UserService.class);
        doAnswer(invocation -> {
            ThreadLocalUtil.set(Map.of("id", 88L, "type", "user"));
            return "test-token";
        }).when(service).login(any(User.class));

        controller(service).perform(post("/app/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13900000002\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("test-token"));
        verify(service).login(any(User.class));
        assertNull(ThreadLocalUtil.get());
    }

    @Test
    void loginControllerClearsContextEvenWhenServiceThrows() throws Exception {
        UserService service = mock(UserService.class);
        doAnswer(invocation -> {
            ThreadLocalUtil.set(Map.of("id", 88L, "type", "user"));
            throw new BizException("模拟登录失败");
        }).when(service).login(any(User.class));

        controller(service).perform(post("/app/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13900000002\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("模拟登录失败"));
        assertNull(ThreadLocalUtil.get());
    }

    private MockMvc controller(UserService service) {
        AppLoginController controller = new AppLoginController();
        ReflectionTestUtils.setField(controller, "userService", service);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private User databaseUser() {
        User user = new User();
        user.setId(88L);
        user.setPhone("13900000002");
        user.setPassword(Md5Util.getMD5String("123456"));
        user.setNickname("小明");
        return user;
    }

    private User credentials(String password) {
        User user = new User();
        user.setPhone("13900000002");
        user.setPassword(password);
        return user;
    }
}
