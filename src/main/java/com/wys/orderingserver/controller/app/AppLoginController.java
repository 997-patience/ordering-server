package com.wys.orderingserver.controller.app;

import com.wys.orderingserver.pojo.Result;
import com.wys.orderingserver.pojo.User;
import com.wys.orderingserver.service.UserService;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户注册与登录入口，对应文档 7.x。 */
@RequestMapping("/app")
@RestController
public class AppLoginController {
    @Autowired
    private UserService userService;
    @PostMapping("/register")
    public Result register(@Validated(User.Add.class) @RequestBody User user) {
        userService.register(user);
        return Result.success();
    }
    @PostMapping("/login")
    public Result<String> login(@RequestBody User user) {
        try {
            String token = userService.login(user);
            return Result.success(token);
        } finally {
            // 登录在拦截器白名单中，本次请求的 ThreadLocal 由这里负责清理。
            ThreadLocalUtil.remove();
        }
    }
}
