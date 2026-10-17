package com.wys.orderingserver.interceptors;

import com.wys.orderingserver.mapper.EmployeeMapper;
import com.wys.orderingserver.pojo.Employee;
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
 *   4. 员工令牌还需检查员工是否存在且启用，避免删店后旧令牌继续使用
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

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
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
            // 管理端登录生成的 claims 包含 role；顾客令牌不走员工查询。
            // Redis 中的旧令牌即使尚未过期，被删除员工也不能再发起新请求。
            if (claims.get("role") instanceof Number) {
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
