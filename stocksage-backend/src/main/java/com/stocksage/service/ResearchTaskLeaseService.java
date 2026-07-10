package com.stocksage.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
public class ResearchTaskLeaseService {

    private static final String KEY_PREFIX = "lock:research-task:";
    private static final DefaultRedisScript<String> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current then
              return current
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return ARGV[1]
            """, String.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final long leaseTtlMillis;
    private final ConcurrentMap<String, ProcessLease> processLeases = new ConcurrentHashMap<>();

    public ResearchTaskLeaseService(
            Optional<StringRedisTemplate> stringRedisTemplate,
            @Value("${stocksage.research-task.lease-ttl-ms:1800000}") long leaseTtlMillis
    ) {
        this.stringRedisTemplate = stringRedisTemplate.orElse(null);
        this.leaseTtlMillis = Math.max(1_000, leaseTtlMillis);
    }

    public Optional<Lease> tryAcquire(String idempotencyKey) {
        String normalizedKey = normalizeKey(idempotencyKey);
        if (normalizedKey.isBlank()) {
            return Optional.empty();
        }

        String token = UUID.randomUUID().toString();
        if (stringRedisTemplate != null) {
            try {
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

    private void removeExpiredProcessLease(String normalizedKey) {
        processLeases.computeIfPresent(normalizedKey, (key, current) ->
                current.expiredAt(System.currentTimeMillis()) ? null : current);
    }

    private long expiresAtMillis() {
        return System.currentTimeMillis() + leaseTtlMillis;
    }

    private String normalizeKey(String idempotencyKey) {
        return idempotencyKey == null ? "" : idempotencyKey.trim();
    }

    private String redisKey(String normalizedKey) {
        return KEY_PREFIX + normalizedKey;
    }

    public enum Backend {
        REDIS,
        PROCESS
    }

    public record Lease(String idempotencyKey, String token, Backend backend) {
    }

    private record ProcessLease(String token, long expiresAtMillis) {
        private boolean expiredAt(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }
    }
}
