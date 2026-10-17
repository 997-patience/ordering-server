package com.wys.orderingserver.service.impl;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.EmployeeMapper;
import com.wys.orderingserver.pojo.Employee;
import com.wys.orderingserver.service.AdminService;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.Md5Util;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 管理端（员工）业务实现
 */
@Service
public class AdminServiceimpl implements AdminService {

    @Autowired
    private EmployeeMapper employeeMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public String login(String username, String password) {
        // 1. 按用户名查询员工
        Employee employee = employeeMapper.findByUsername(username);
        if (employee == null) {
            throw new BizException("用户名错误");
        }

        // 2. 校验密码（库里存的是 MD5 密文）
        if (!Md5Util.getMD5String(password).equals(employee.getPassword())) {
            throw new BizException("密码错误");
        }

        // 3. 校验账号状态
        if (employee.getStatus() == null || employee.getStatus() != 1) {
            throw new BizException("该账号已被禁用，请联系超级管理员");
        }

        // 4. 生成令牌：把登录人信息放进 claims，供后续请求还原身份
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", employee.getId());
        claims.put("username", employee.getUsername());
        claims.put("name", employee.getName());
        // storeId 是门店数据隔离的关键：登录时写进令牌，后续接口从令牌取值过滤
        claims.put("storeId", employee.getStoreId());
        claims.put("role", employee.getRole());
        String token = JwtUtil.genToken(claims);

        // 5. 令牌写入 Redis（key = token），有效期与 token 一致
        //    作用是让 JWT 也能"主动失效"：退出登录或改密码时直接删除该 key 即可
        ValueOperations<String, String> operations = stringRedisTemplate.opsForValue();
        operations.set(token, token, JwtUtil.EXPIRE_SECONDS, TimeUnit.SECONDS);

        return token;
    }

    @Override
    public Employee getCurrentEmployee(String username) {
        Employee employee = employeeMapper.findByUsername(username);
        if (employee == null) {
            throw new BizException("当前登录员工不存在");
        }
        return employee;
    }
}
