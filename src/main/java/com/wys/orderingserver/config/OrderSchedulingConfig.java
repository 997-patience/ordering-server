package com.wys.orderingserver.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 生产默认开启；测试关闭自动触发，再手动调用任务验证业务。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "ordering.order-timeout.enabled", havingValue = "true", matchIfMissing = true)
public class OrderSchedulingConfig {
}
