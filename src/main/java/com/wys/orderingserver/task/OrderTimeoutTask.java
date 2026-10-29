package com.wys.orderingserver.task;

import com.wys.orderingserver.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** 后台扫描超时订单；不依赖 HTTP 请求，也不从 ThreadLocal 获取用户身份。 */
@Component
public class OrderTimeoutTask {
    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutTask.class);
    private final OrderMapper orderMapper;

    public OrderTimeoutTask(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    // 本轮结束 60 秒后再扫描；启动 10 秒后先补扫一次停机期间积压的订单。
    @Scheduled(fixedDelayString = "${ordering.order-timeout.scan-delay-ms:60000}",
            initialDelayString = "${ordering.order-timeout.initial-delay-ms:10000}")
    @Transactional
    public void cancelExpiredOrders() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);
        int rows = orderMapper.cancelTimedOut(now.minusMinutes(15), now);
        if (rows > 0) {
            log.info("已自动取消 {} 笔商家超时未接单的订单", rows);
        }
    }
}
