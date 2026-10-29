package com.wys.orderingserver.controller.admin;

import com.wys.orderingserver.pojo.Employee;
import com.wys.orderingserver.pojo.Result;
import com.wys.orderingserver.service.AdminService;
import com.wys.orderingserver.utils.AdminContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端登录相关接口
 * 对应接口文档：1.1 管理员登录、1.2 获取当前登录员工信息、1.3 退出登录
 *
 * 注意：必须是 @RestController（= @Controller + @ResponseBody），
 * 光写 @Controller 会把返回值当作视图名去解析，导致 404 或报错。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Autowired
    private AdminService adminService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 1.1 管理员登录
     * POST /admin/login
     * 请求体：{"username":"admin","password":"123456"}
     */
    @PostMapping("/login")
    public Result<String> login(@RequestBody Map<String, String> params) {
        String username = params.get("username");
        String password = params.get("password");
        if (!StringUtils.hasLength(username) || !StringUtils.hasLength(password)) {
            return Result.error("用户名和密码不能为空");
        }
        // 登录失败会抛 BizException，由 GlobalExceptionHandler 统一转成 Result.error
        String token = adminService.login(username, password);
        return Result.success(token);
    }

    /**
     * 1.2 获取当前登录员工信息
     * GET /admin/info
     * 无需参数：身份从令牌解析后由 LoginInterceptor 存入 ThreadLocal
     */
    @GetMapping("/info")
    public Result<Employee> info() {
        String username = AdminContextUtil.getCurrentAdminUsername();
        Employee employee = adminService.getCurrentEmployee(username);
        // password 字段标注了 @JsonProperty(WRITE_ONLY)，不会被序列化返回
        return Result.success(employee);
    }

    /**
     * 1.3 退出登录
     * POST /admin/logout
     * 把令牌从 Redis 中删除，使其立即失效（JWT 本身无法主动失效，靠这一步实现）
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "Authorization", required = false) String token) {
        if (StringUtils.hasLength(token)) {
            stringRedisTemplate.delete(token);
        }
        return Result.success();
    }


}
