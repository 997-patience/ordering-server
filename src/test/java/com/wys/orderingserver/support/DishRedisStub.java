package com.wys.orderingserver.support;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 带状态的 Redis 模拟：模拟 NX、版本和脚本原子操作的契约，不连接实际 Redis。 */
public class DishRedisStub {
    public final Map<String, String> values = new ConcurrentHashMap<>();
    public final Map<String, Duration> ttls = new ConcurrentHashMap<>();
    public final ValueOperations<String, String> operations;

    @SuppressWarnings("unchecked")
    public DishRedisStub(StringRedisTemplate template) {
        operations = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(operations);
        when(operations.get(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        when(operations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(call -> {
            String key = call.getArgument(0);
            boolean acquired = values.putIfAbsent(key, call.getArgument(1)) == null;
            if (acquired) ttls.put(key, call.getArgument(2));
            return acquired;
        });
        when(operations.increment(anyString())).thenAnswer(call -> {
            String value = values.compute(call.getArgument(0),
                    (key, old) -> Long.toString(old == null ? 1 : Long.parseLong(old) + 1));
            return Long.parseLong(value);
        });
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(call -> {
            List<String> keys = call.getArgument(1);
            Object[] args = (Object[]) call.getRawArguments()[2];
            synchronized (values) {
                if (keys.size() == 1) {
                    boolean removed = values.remove(keys.getFirst(), args[0]);
                    if (removed) ttls.remove(keys.getFirst());
                    return removed ? 1L : 0L;
                }
                if (args[0].equals(values.get(keys.get(0)))
                        && args[1].equals(values.getOrDefault(keys.get(2), "0"))) {
                    values.put(keys.get(1), (String) args[2]);
                    ttls.put(keys.get(1), Duration.ofSeconds(Long.parseLong((String) args[3])));
                    return 1L;
                }
                return 0L;
            }
        });
        when(template.scan(any(ScanOptions.class))).thenAnswer(call -> {
            ScanOptions options = call.getArgument(0);
            String pattern = options.getPattern();
            String prefix = pattern.substring(0, pattern.length() - 1);
            Iterator<String> iterator = values.keySet().stream().filter(key -> key.startsWith(prefix))
                    .toList().iterator();
            Cursor<String> cursor = mock(Cursor.class);
            when(cursor.hasNext()).thenAnswer(unused -> iterator.hasNext());
            when(cursor.next()).thenAnswer(unused -> iterator.next());
            return cursor;
        });
        when(template.delete(anyCollection())).thenAnswer(call -> {
            long removed = 0;
            for (String key : (Collection<String>) call.getArgument(0)) {
                if (values.remove(key) != null) removed++;
                ttls.remove(key);
            }
            return removed;
        });
    }
}
