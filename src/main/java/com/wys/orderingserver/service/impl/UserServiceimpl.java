package com.wys.orderingserver.service.impl;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.mapper.UserMapper;
import com.wys.orderingserver.pojo.User;
import com.wys.orderingserver.service.UserService;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.Md5Util;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 用户注册与登录业务。 */
@Service
public class UserServiceimpl implements UserService {
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void register(User user) {
        if (user == null || user.getPhone() == null
                || !user.getPhone().matches("^1[3-9]\\d{9}$")) {
            throw new BizException("手机号格式不正确");
        }

        if (user.getPassword() == null
                || !user.getPassword().matches("^\\S{5,16}$")) {
            throw new BizException("密码必须为5~16位非空白字符");
        }

        if (user.getNickname() == null
                || !user.getNickname().matches("^\\S{1,16}$")) {
            throw new BizException("昵称长度需为1~16个非空白字符");
        }
        User existingUser=userMapper.findByPhone(user.getPhone());
        if(existingUser!=null){
            throw new BizException("该手机号已被注册");
        }
        User newUser=new User();
        newUser.setPhone(user.getPhone());
        newUser.setNickname(user.getNickname());
        newUser.setPassword(Md5Util.getMD5String(user.getPassword()));
        LocalDateTime now = LocalDateTime.now();
        newUser.setCreateTime(now);
        newUser.setUpdateTime(now);
        try {
            int rows = userMapper.add(newUser);
            if (rows != 1) {
                throw new BizException("注册失败，请重试");
            }
        } catch (DuplicateKeyException e) {
            throw new BizException("该手机号已被注册", e);
        }
    }

    @Override
    public String login(User user) {
        if (user == null || !StringUtils.hasText(user.getPhone()) || !StringUtils.hasText(user.getPassword())) {
            throw new BizException("手机号或密码错误");
        }
        User existingUser = userMapper.findByPhone(user.getPhone());
        // 按手机号查询已限定账号，不再重复比较手机号；两种认证失败使用相同提示。
        if (existingUser == null || !Md5Util.checkPassword(user.getPassword(), existingUser.getPassword())) {
            throw new BizException("手机号或密码错误");
        }
        Map<String, Object> claims = new HashMap<>();
        // 身份信息取自数据库中的用户，不使用请求体中可伪造的 ID、昵称。
        claims.put("id", existingUser.getId());
        claims.put("phone", existingUser.getPhone());
        claims.put("nickname", existingUser.getNickname());
        claims.put("type", "user");
        String token = JwtUtil.genToken(claims);
        // 后续拦截器会检查 Redis，必须保存成功后才向前端返回 token。
        try {
            stringRedisTemplate.opsForValue().set(token, token, JwtUtil.EXPIRE_SECONDS, TimeUnit.SECONDS);
        } catch (DataAccessException e) {
            throw new BizException("登录服务暂不可用，请稍后重试", e);
        }
        // 当前登录请求由 Controller 清理；后续请求由拦截器解析 token 后重新填充。
        ThreadLocalUtil.set(claims);
        return token;
    }
}
