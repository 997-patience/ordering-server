package com.wys.orderingserver.service;

import com.wys.orderingserver.pojo.User;

/** 用户注册与登录业务，对应文档 7.x。 */
public interface UserService {
    //注册新用户（顾客）
    void register(User user);
    //登录成功后返回 JWT，供前端在后续请求中携带
    String login(User user);
}
