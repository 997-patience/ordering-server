package com.wys.orderingserver;

import com.wys.orderingserver.config.OrderSchedulingConfig;
import com.wys.orderingserver.mapper.OrderMapper;
import com.wys.orderingserver.task.OrderTimeoutTask;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证 @EnableScheduling 真正注册并在后台触发任务，而非只测试手工调用。 */
class OrderTimeoutSchedulingTests {
    @Test
    void schedulingActuallyCallsTaskOnBackgroundThreadWithoutUserContext() throws Exception {
        OrderMapper mapper = mock(OrderMapper.class);
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<String> workerName = new AtomicReference<>();
        AtomicReference<Object> workerIdentity = new AtomicReference<>();
        when(mapper.cancelTimedOut(any(), any())).thenAnswer(call -> {
            workerName.set(Thread.currentThread().getName());
            workerIdentity.set(ThreadLocalUtil.get());
            ran.countDown(); return 0;
        });
        String callingThread = Thread.currentThread().getName();
        ThreadLocalUtil.set(Map.of("id", 1, "type", "user"));
        try (AnnotationConfigApplicationContext context = context(mapper, true)) {
            assertTrue(ran.await(3, TimeUnit.SECONDS));
            assertNotEquals(callingThread, workerName.get());
            assertNull(workerIdentity.get());
            assertEquals(1, context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
        } finally { ThreadLocalUtil.remove(); }
    }

    @Test
    void explicitSwitchDisablesAutomaticScheduling() {
        OrderMapper mapper = mock(OrderMapper.class);
        try (AnnotationConfigApplicationContext context = context(mapper, false)) {
            assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
            verifyNoInteractions(mapper);
        }
    }

    @Test
    void failedScanDoesNotPreventNextScheduledScan() throws Exception {
        OrderMapper mapper = mock(OrderMapper.class);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        when(mapper.cancelTimedOut(any(), any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) throw new DataAccessResourceFailureException("模拟短暂数据库故障");
            recovered.countDown(); return 0;
        });
        try (AnnotationConfigApplicationContext ignored = context(mapper, true)) {
            assertTrue(recovered.await(3, TimeUnit.SECONDS));
            assertTrue(calls.get() >= 2);
        }
    }

    private AnnotationConfigApplicationContext context(OrderMapper mapper, boolean enabled) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "ordering.order-timeout.enabled", Boolean.toString(enabled),
                "ordering.order-timeout.initial-delay-ms", "1",
                "ordering.order-timeout.scan-delay-ms", "20")));
        context.registerBean(OrderMapper.class, () -> mapper);
        context.register(OrderTimeoutTask.class, OrderSchedulingConfig.class);
        context.refresh();
        return context;
    }
}
