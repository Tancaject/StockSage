package com.stocksage.research;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 为同一幂等研究请求提供带 owner token 的可续约执行租约。
 *
 * <p>优先用 Redis Lua 原子获取、续期和释放，Redis 未配置或获取异常时回退到进程内 map。
 * 进程内回退只能避免单 JVM 重复执行，跨实例正确性仍依赖后续数据库 owner fence。</p>
 */
@Slf4j
@Service
public class ResearchTaskLeaseService {

    /** Redis 租约键前缀。 */
    private static final String KEY_PREFIX = "lock:research-task:";
    /** 生产心跳间隔上限。 */
    private static final long MAX_HEARTBEAT_INTERVAL_MILLIS = Duration.ofSeconds(60).toMillis();
    /** 极短测试租约的最小心跳间隔。 */
    private static final long MIN_HEARTBEAT_INTERVAL_MILLIS = 100;
    /** 仅在键不存在时写入 owner token 和 TTL，并返回当前 owner。 */
    private static final DefaultRedisScript<String> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current then
              return current
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return ARGV[1]
            """, String.class);
    /** 只有 token 匹配时才删除租约，防止旧 worker 释放新 owner 的锁。 */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);
    /** 只有 token 匹配时才刷新 TTL，形成续约 owner fence。 */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    /** 可选 Redis 客户端；为空时使用单进程回退。 */
    private final StringRedisTemplate stringRedisTemplate;
    /** 每次获取或续期后的租约有效期。 */
    private final long leaseTtlMillis;
    /** Redis 不可用时的单 JVM 租约表。 */
    private final ConcurrentMap<String, ProcessLease> processLeases = new ConcurrentHashMap<>();

    /**
     * 创建租约服务并收敛最小 TTL。
     *
     * @param stringRedisTemplate 可选 Redis 客户端
     * @param leaseTtlMillis 租约 TTL 毫秒数
     */
    public ResearchTaskLeaseService(
            Optional<StringRedisTemplate> stringRedisTemplate,
            @Value("${stocksage.research-task.lease-ttl-ms:1800000}") long leaseTtlMillis
    ) {
        this.stringRedisTemplate = stringRedisTemplate.orElse(null);
        this.leaseTtlMillis = Math.max(1_000, leaseTtlMillis);
    }

    /**
     * 尝试获取幂等键租约，不等待现有 owner。
     *
     * @param idempotencyKey 研究请求稳定幂等键
     * @return 新租约；已有 owner 或键为空时为空
     */
    public Optional<Lease> tryAcquire(String idempotencyKey) {
        String normalizedKey = normalizeKey(idempotencyKey);
        if (normalizedKey.isBlank()) {
            return Optional.empty();
        }

        String token = UUID.randomUUID().toString();
        if (stringRedisTemplate != null) {
            try {
                // 调用 ACQUIRE_SCRIPT 原子检查并写入 token，避免 GET+SET 竞态。
                String result = stringRedisTemplate.execute(
                        ACQUIRE_SCRIPT,
                        List.of(redisKey(normalizedKey)),
                        token,
                        String.valueOf(leaseTtlMillis)
                );
                if (token.equals(result)) {
                    return Optional.of(new Lease(normalizedKey, token, Backend.REDIS));
                }
                return Optional.empty();
            } catch (Exception e) {
                log.warn("Research task Redis lease acquire failed, falling back to process map: {}", e.getMessage());
            }
        }

        return acquireProcessLease(normalizedKey, token);
    }

    /**
     * 释放仍属于该 token 的租约；过期或换 owner 后安全无操作。
     *
     * @param lease 当前 worker 持有的租约
     */
    public void release(Lease lease) {
        if (lease == null || lease.idempotencyKey() == null || lease.token() == null) {
            return;
        }
        if (lease.backend() == Backend.REDIS && stringRedisTemplate != null) {
            try {
                stringRedisTemplate.execute(
                        RELEASE_SCRIPT,
                        List.of(redisKey(lease.idempotencyKey())),
                        lease.token()
                );
                return;
            } catch (Exception e) {
                log.warn("Research task Redis lease release failed: {}", e.getMessage());
            }
        }
        if (lease.backend() == Backend.PROCESS) {
            processLeases.computeIfPresent(lease.idempotencyKey(), (key, current) ->
                    current.token().equals(lease.token()) ? null : current);
        }
    }

    /**
     * 续约仍属于该 token 的租约。
     *
     * @param lease 当前 worker 持有的租约
     * @return true 表示 TTL 已刷新；false 表示 ownership 丢失或 Redis 异常
     */
    public boolean renew(Lease lease) {
        if (lease == null || lease.idempotencyKey() == null || lease.token() == null) {
            return false;
        }
        if (lease.backend() == Backend.REDIS && stringRedisTemplate != null) {
            try {
                Long renewed = stringRedisTemplate.execute(
                        RENEW_SCRIPT,
                        List.of(redisKey(lease.idempotencyKey())),
                        lease.token(),
                        String.valueOf(leaseTtlMillis)
                );
                return renewed != null && renewed > 0;
            } catch (Exception e) {
                log.warn("Research task Redis lease renew failed: {}", e.getMessage());
                return false;
            }
        }
        if (lease.backend() == Backend.PROCESS) {
            return renewProcessLease(lease);
        }
        return false;
    }

    /**
     * 返回明显短于 TTL 的安全续约节奏。
     *
     * <p>生产长租约保持不超过 60 秒；演示和测试短租约按 TTL 三分之一续约。</p>
     *
     * @return 建议心跳间隔
     */
    public Duration heartbeatInterval() {
        long ttlFractionMillis = Math.max(1, leaseTtlMillis / 3);
        long intervalMillis = Math.max(
                MIN_HEARTBEAT_INTERVAL_MILLIS,
                Math.min(MAX_HEARTBEAT_INTERVAL_MILLIS, ttlFractionMillis)
        );
        return Duration.ofMillis(intervalMillis);
    }

    /** @return 当前配置生效后的租约 TTL。 */
    public Duration leaseTtl() {
        return Duration.ofMillis(leaseTtlMillis);
    }

    /** 在单进程 map 中原子获取回退租约。 */
    private Optional<Lease> acquireProcessLease(String normalizedKey, String token) {
        removeExpiredProcessLease(normalizedKey);
        ProcessLease existing = processLeases.putIfAbsent(
                normalizedKey,
                new ProcessLease(token, expiresAtMillis())
        );
        if (existing != null) {
            return Optional.empty();
        }
        return Optional.of(new Lease(normalizedKey, token, Backend.PROCESS));
    }

    /** 只有 token 匹配且尚未过期时才续期进程内租约。 */
    private boolean renewProcessLease(Lease lease) {
        ProcessLease renewed = processLeases.computeIfPresent(lease.idempotencyKey(), (key, current) -> {
            if (!current.token().equals(lease.token()) || current.expiredAt(System.currentTimeMillis())) {
                return current;
            }
            return new ProcessLease(current.token(), expiresAtMillis());
        });
        return renewed != null
                && renewed.token().equals(lease.token())
                && !renewed.expiredAt(System.currentTimeMillis());
    }

    /** 获取前惰性移除已过期的进程租约。 */
    private void removeExpiredProcessLease(String normalizedKey) {
        processLeases.computeIfPresent(normalizedKey, (key, current) ->
                current.expiredAt(System.currentTimeMillis()) ? null : current);
    }

    /** 计算一次新租约的绝对过期时间。 */
    private long expiresAtMillis() {
        return System.currentTimeMillis() + leaseTtlMillis;
    }

    /** 去除幂等键首尾空白。 */
    private String normalizeKey(String idempotencyKey) {
        return idempotencyKey == null ? "" : idempotencyKey.trim();
    }

    /** 构造隔离于其他 Redis 数据的租约键。 */
    private String redisKey(String normalizedKey) {
        return KEY_PREFIX + normalizedKey;
    }

    /** 标记租约实际由 Redis 或单进程回退承载。 */
    public enum Backend {
        REDIS,
        PROCESS
    }

    /**
     * 调用方必须在状态写入和释放时携带的 owner 凭证。
     *
     * @param idempotencyKey 归一化幂等键
     * @param token 唯一 owner token
     * @param backend 租约后端
     */
    public record Lease(String idempotencyKey, String token, Backend backend) {
    }

    /** 进程回退租约的内部值。 */
    private record ProcessLease(String token, long expiresAtMillis) {
        private boolean expiredAt(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }
    }
}
