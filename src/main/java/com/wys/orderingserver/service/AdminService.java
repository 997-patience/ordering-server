package com.wys.orderingserver.service;

import com.wys.orderingserver.pojo.Employee;

/**
 * 管理端（员工）业务接口
 * 对应接口文档 1.1 管理员登录、1.2 获取当前登录员工信息
 */
public interface AdminService {

    /**
     * 管理员登录
     *
     * @param username 用户名
     * @param password 明文密码
     * @return JWT 令牌（同时已写入 Redis，支持主动失效）
     */
    String login(String username, String password);

    /**
     * 获取当前登录员工信息
     *
     * @param username 从令牌中解析出的用户名
     * @return 员工信息（password 字段不会被序列化返回给前端）
     */
    Employee getCurrentEmployee(String username);
}
