package com.wys.orderingserver.utils;

/**
 * ThreadLocal 工具类
 *
 * 用途：保存"当前请求的登录信息"，让 Service 层不用层层传参就能拿到当前登录人。
 *
 * 完整链路：
 *   JwtInterceptor.preHandle           解析 token 成功 --> ThreadLocalUtil.set(claims)
 *   Service 层                           ThreadLocalUtil.get() 取 id / storeId
 *   JwtInterceptor.afterCompletion      请求结束     --> ThreadLocalUtil.remove()
 *
 * ⚠️ 面试必问：为什么必须 remove？
 *   Tomcat 用线程池处理请求，线程会被复用。若不清理，
 *   下一个请求（可能是另一个用户）会读到上一个用户的数据 —— 数据串号 + 内存泄漏。
 */
@SuppressWarnings("all")
public class ThreadLocalUtil {

    /** 提供 ThreadLocal 对象 */
    private static final ThreadLocal THREAD_LOCAL = new ThreadLocal();

    /** 获取当前线程保存的值 */
    public static <T> T get() {
        return (T) THREAD_LOCAL.get();
    }

    /** 存储值 */
    public static void set(Object value) {
        THREAD_LOCAL.set(value);
    }

    /** 清除，防止内存泄漏（必须在拦截器 afterCompletion 中调用） */
    public static void remove() {
        THREAD_LOCAL.remove();
    }
}
