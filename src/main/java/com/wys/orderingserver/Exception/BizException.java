package com.wys.orderingserver.Exception;

/**
 * 业务异常
 *
 * 用途：Service 层抛出可预期的业务错误（如"密码错误""手机号已被注册"），
 * 由 GlobalExceptionHandler 统一捕获并转成 Result.error(message) 返回给前端。
 *
 * 为什么继承 RuntimeException 而不是 Exception？
 * 继承 RuntimeException（非受检）不会强制方法签名加 throws，业务代码更干净；
 * 同时 Spring 的 @Transactional 默认只对 RuntimeException 回滚，
 * 业务异常回滚正是我们想要的。
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }

    public BizException(String message, Throwable cause) {
        super(message, cause);
    }
}
