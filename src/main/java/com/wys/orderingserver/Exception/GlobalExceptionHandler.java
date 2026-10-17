package com.wys.orderingserver.Exception;

import com.wys.orderingserver.pojo.Result;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器
 *
 * 所有 Controller 抛出的异常都会在这里被捕获，统一转成 {code, message, data} 格式，
 * 避免前端收到 Spring 默认的错误页或裸的 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：Service 层主动抛出的可预期错误，直接把 message 返回给前端 */
    @ExceptionHandler(BizException.class)
    public Result handleBizException(BizException e) {
        return Result.error(e.getMessage());
    }

    /** 参数校验失败：@Validated / @Valid 触发的校验异常，返回第一条校验提示 */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result handleValidationException(Exception e) {
        FieldError fieldError = null;
        if (e instanceof MethodArgumentNotValidException ex) {
            fieldError = ex.getBindingResult().getFieldError();
        } else if (e instanceof BindException ex) {
            fieldError = ex.getBindingResult().getFieldError();
        }
        String message = fieldError == null ? "参数校验失败" : fieldError.getDefaultMessage();
        return Result.error(message);
    }

    /** 缺少必传参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result handleMissingParam(MissingServletRequestParameterException e) {
        return Result.error("缺少必传参数：" + e.getParameterName());
    }

    /** 参数类型不匹配，如把 abc 传给 Integer 类型的 id */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.error("参数类型错误：" + e.getName());
    }

    /** 请求体格式错误，如没有传 @RequestBody 要求的内容 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result handleNotReadable(HttpMessageNotReadableException e) {
        return Result.error("请求参数格式错误，请检查请求体是否为合法 JSON");
    }

    /** 兜底：未预料到的异常，打印堆栈便于排查，但不把堆栈暴露给前端 */
    @ExceptionHandler(Exception.class)
    public Result handleException(Exception e) {
        e.printStackTrace();
        String message = e.getMessage();
        return Result.error(message == null || message.isBlank() ? "服务器内部错误，请稍后重试" : message);
    }
}
