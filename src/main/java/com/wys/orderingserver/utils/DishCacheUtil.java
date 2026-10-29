package com.wys.orderingserver.utils;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.pojo.AppDishVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** 菜品列表缓存：读缓存、互斥回源，以及写库提交后按门店失效。 */
@Component
public class DishCacheUtil {
    private static final Logger log = LoggerFactory.getLogger(DishCacheUtil.class);
    private static final TypeReference<List<AppDishVO>> LIST_TYPE = new TypeReference<>() {};
    private static final Duration LOCK_TTL = Duration.ofSeconds(15);
    private static final int MAX_ATTEMPTS = 40;
    private static final long RETRY_MILLIS = 50;

    // 删除锁时必须比较持有者，避免旧请求删除锁过期后被新请求取得的锁。
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "return redis.call('DEL', KEYS[1]) else return 0 end", Long.class);

    // 锁仍归本请求、且查库期间门店菜品没有变更，才允许写入缓存。
    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] " +
            "and (redis.call('GET', KEYS[3]) or '0') == ARGV[2] then " +
            "redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4]); return 1 " +
            "else return 0 end", Long.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    public List<AppDishVO> getOrLoad(Long storeId, Long categoryId, Supplier<List<AppDishVO>> loader) {
        String key = "dish:" + storeId + ":" + (categoryId == null ? "all" : categoryId);
        String lockKey = "lock:" + key;
        String owner = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            boolean acquired;
            try {
                List<AppDishVO> cached = readCache(key, storeId, categoryId);
                if (cached != null) {
                    return cached; // [] 也是命中，不能把空列表当成未命中。
                }
                acquired = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(lockKey, owner, LOCK_TTL));
            } catch (DataAccessException e) {
                log.warn("菜品缓存 {} 不可用，本次查询回源数据库", key, e);
                return loader.get();
            }
            if (acquired) {
                return loadUnderLock(key, lockKey, owner, storeId, categoryId, loader);
            }
            try {
                Thread.sleep(RETRY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BizException("菜品加载被中断，请重试", e);
            }
        }
        // 不无限递归等待，也不让抢锁失败的所有请求同时冲击数据库。
        throw new BizException("菜品正在加载，请稍后重试");
    }

    private List<AppDishVO> loadUnderLock(String key, String lockKey, String owner,
                                         Long storeId, Long categoryId, Supplier<List<AppDishVO>> loader) {
        try {
            String version;
            try {
                // 拿到锁后再次检查：前一个持有者可能已经完成缓存重建。
                List<AppDishVO> cached = readCache(key, storeId, categoryId);
                if (cached != null) {
                    return cached;
                }
                version = Objects.requireNonNullElse(redisTemplate.opsForValue().get(versionKey(storeId)), "0");
            } catch (DataAccessException e) {
                log.warn("菜品缓存 {} 不可用，本次查询回源数据库", key, e);
                return loader.get();
            }
            // 数据库异常向上传播，不能被当成 Redis 异常反复重查。
            List<AppDishVO> dishes = loader.get();
            long ttl = dishes.isEmpty() ? 60 : 1800 + ThreadLocalRandom.current().nextLong(1, 301);
            try {
                redisTemplate.execute(PUBLISH, List.of(lockKey, key, versionKey(storeId)),
                        owner, version, objectMapper.writeValueAsString(dishes), Long.toString(ttl));
            } catch (DataAccessException | JacksonException e) {
                // 查库已成功，缓存故障不应让本次菜品查询失败。
                log.warn("菜品列表 {} 查询成功，但写入缓存失败", key, e);
            }
            return dishes;
        } finally {
            try {
                redisTemplate.execute(UNLOCK, List.of(lockKey), owner);
            } catch (DataAccessException e) {
                // 锁自身有过期时间，释放失败时也不会永久占用。
                log.warn("菜品缓存锁 {} 释放失败，等待自动过期", lockKey, e);
            }
        }
    }

    private List<AppDishVO> readCache(String key, Long storeId, Long categoryId) {
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            List<AppDishVO> cached = objectMapper.readValue(json, LIST_TYPE);
            if (cached != null && cached.stream().allMatch(dish -> dish != null
                    && storeId.equals(dish.getStoreId()) && Integer.valueOf(1).equals(dish.getStatus())
                    && (categoryId == null || categoryId.equals(dish.getCategoryId())))) {
                return cached;
            }
        } catch (JacksonException e) {
            log.warn("菜品缓存 {} 格式错误，将重新查询", key);
        }
        return null;
    }

    public void clearAfterCommit(Long storeId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    clearStore(storeId);
                }
            });
        } else {
            clearStore(storeId);
        }
    }

    private void clearStore(Long storeId) {
        try {
            // 先变更版本，再删列表，阻止正在查旧数据的请求把旧结果重新填回缓存。
            redisTemplate.opsForValue().increment(versionKey(storeId));
            ScanOptions options = ScanOptions.scanOptions().match("dish:" + storeId + ":*").count(200).build();
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                List<String> keys = new ArrayList<>();
                while (cursor.hasNext()) {
                    keys.add(cursor.next());
                    if (keys.size() == 200) {
                        redisTemplate.delete(keys);
                        keys.clear();
                    }
                }
                if (!keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            }
        } catch (RuntimeException e) {
            // 此时数据库已经提交，保留成功结果，记录故障供排查。
            log.error("菜品数据已变更，清理门店 {} 的菜品列表缓存失败", storeId, e);
        }
    }

    private String versionKey(Long storeId) {
        return "dish:version:" + storeId;
    }
}
