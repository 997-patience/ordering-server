package com.wys.orderingserver.interceptors;

import com.wys.orderingserver.mapper.EmployeeMapper;
import com.wys.orderingserver.mapper.UserMapper;
import com.wys.orderingserver.pojo.Employee;
import com.wys.orderingserver.pojo.User;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * 登录校验拦截器
 *
 * 校验流程：
 *   1. 从请求头 Authorization 取出 token
 *   2. 检查 Redis 中是否还有该 token（退出登录/改密码后会被删除，实现"主动失效"）
 *   3. 解析 JWT，校验签名与有效期
 *   4. 区分顾客与管理员身份，并检查对应账号是否仍然有效
 *   5. 把登录信息存入 ThreadLocal，供 Service 层取当前登录人
 *   6. 请求结束后在 afterCompletion 中清理 ThreadLocal，防止内存泄漏与数据串号
 *
 * 校验失败时不返回 JSON 响应体，而是设置 http 状态码 401（与接口文档 0.2 节一致）。
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private EmployeeMapper employeeMapper;

    @Autowired
    private UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // preHandle 拒绝请求时，当前拦截器不会进入 afterCompletion，先清除可能残留的上下文。
        ThreadLocalUtil.remove();
        String token = request.getHeader("Authorization");
        if (!StringUtils.hasLength(token)) {
            response.setStatus(401);
            return false;
        }

        try {
            // 令牌必须还在 Redis 中，否则视为已失效
            ValueOperations<String, String> operations = stringRedisTemplate.opsForValue();
            if (operations.get(token) == null) {
                response.setStatus(401);
                return false;
            }

            // 解析 JWT（签名不对或已过期都会抛异常，被下面的 catch 捕获）
            Map<String, Object> claims = JwtUtil.parseToken(token);
            String path = request.getRequestURI().substring(request.getContextPath().length());
            boolean adminRequest = path.equals("/admin") || path.startsWith("/admin/");
            boolean appRequest = path.equals("/app") || path.startsWith("/app/");
            Object type = claims.get("type");
            if (type != null && !"user".equals(type) && !"admin".equals(type)) {
                response.setStatus(401);
                return false;
            }
            if ("user".equals(type)) {
                // 顾客身份不能携带管理员角色，也不能访问管理端。
                if (adminRequest || claims.get("role") != null
                        || !(claims.get("id") instanceof Number id) || id.longValue() < 1
                        || !(claims.get("phone") instanceof String phone) || !StringUtils.hasText(phone)) {
                    response.setStatus(401);
                    return false;
                }
                User user = userMapper.findByPhone(phone);
                if (user == null || !Long.valueOf(id.longValue()).equals(user.getId())) {
                    response.setStatus(401);
                    return false;
                }
            } else if (claims.get("role") instanceof Number) {
                // 新管理员令牌有 type=admin；兼容此前只有 role 的管理员令牌。
                if (appRequest) {
                    response.setStatus(401);
                    return false;
                }
                Object idValue = claims.get("id");
                if (!(idValue instanceof Number id)) {
                    response.setStatus(401);
                    return false;
                }
                Employee employee = employeeMapper.findById(id.longValue());
                if (employee == null || !Integer.valueOf(1).equals(employee.getStatus())) {
                    response.setStatus(401);
                    return false;
                }
            } else if (type != null || appRequest) {
                // 用户端受保护接口要求明确的顾客身份；管理端旧无角色令牌仍由业务权限检查拒绝。
                response.setStatus(401);
                return false;
            }
            ThreadLocalUtil.set(claims);
            return true;
        } catch (Exception e) {
            response.setStatus(401);
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 必须清理：Tomcat 线程池会复用线程，不清理会让下一个请求读到上一个用户的信息
        ThreadLocalUtil.remove();
    }
}
