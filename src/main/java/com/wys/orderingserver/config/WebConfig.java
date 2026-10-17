package com.wys.orderingserver.config;

import com.wys.orderingserver.interceptors.LoginInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：注册登录拦截器 + 图片静态资源映射
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private LoginInterceptor loginInterceptor;

    /** 图片落盘目录，取自 application.yaml 的 ordering.upload.dir */
    @Value("${ordering.upload.dir}")
    private String uploadDir;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                // 白名单：登录、注册、图片上传不需要令牌（与接口文档 0.2 节一致）
                .excludePathPatterns(
                        "/admin/login",
                        "/app/login",
                        "/app/register",
                        "/admin/upload"
                );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 把 /upload/** 映射到本地磁盘目录，让上传的图片能通过 URL 直接访问
        // 例：访问 http://localhost:8080/upload/xxx.jpg 实际读取 D:/.../upload/xxx.jpg
        registry.addResourceHandler("/upload/**")
                .addResourceLocations("file:" + uploadDir);
    }
}
