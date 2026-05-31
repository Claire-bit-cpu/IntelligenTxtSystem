package com.example.IntelligentRobot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * 分布式限流服务
 * 基于Redis实现固定窗口计数限流，支持多实例部署
 * 
 * 限流粒度：
 * 1. 全局限流：限制服务总QPS
 * 2. 单IP限流：防止单个IP刷爆接口
 * 3. 单企业限流：防止单个企业占用全部资源
 * 
 * 使用方式：
 * if (!rateLimitService.tryAcquire("global", "all", 1000)) {
 *     return ResponseEntity.status(429).body("Too Many Requests");
 * }
 * 
 * 简化说明：
 * 使用固定窗口计数器算法，实现简单，性能高
 * 如需更平滑的限流，可升级为滑动窗口或令牌桶
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    /**
     * Lua脚本：原子性限流（固定窗口计数器）
     * KEYS[1] = 限流key
     * ARGV[1] = 限流阈值（最大请求数）
     * ARGV[2] = 时间窗口（秒）
     * 返回值：1=允许，0=拒绝
     */
    private static final String RATE_LIMIT_LUA =
            "local key = KEYS[1]\n" +
            "local max_requests = tonumber(ARGV[1])\n" +
            "local window = tonumber(ARGV[2])\n" +
            "\n" +
            "local current = redis.call('incr', key)\n" +
            "if current == 1 then\n" +
            "    redis.call('expire', key, window)\n" +
            "end\n" +
            "\n" +
            "if current <= max_requests then\n" +
            "    return 1\n" +
            "else\n" +
            "    return 0\n" +
            "end";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private AlertService alertService;

    /**
     * 尝试获取许可（限流判断）
     * 
     * @param keyPrefix 限流key前缀（如 "global", "ip", "tenant"）
     * @param keyId 限流标识（如 IP地址、企业ID、"all"）
     * @param maxRequests 时间窗口内最大请求数
     * @param windowSeconds 时间窗口（秒）
     * @return true=允许通过，false=被限流
     */
    public boolean tryAcquire(String keyPrefix, String keyId, int maxRequests, int windowSeconds) {
        String key = "ratelimit:" + keyPrefix + ":" + keyId;
        
        try {
            RedisScript<Long> redisScript = RedisScript.of(RATE_LIMIT_LUA, Long.class);
            Long result = stringRedisTemplate.execute(redisScript, Collections.singletonList(key), String.valueOf(maxRequests), String.valueOf(windowSeconds));

            boolean allowed = result != null && result == 1;
            
            if (!allowed) {
                log.warn("限流触发: key={}, max={}/{}s", key, maxRequests, windowSeconds);
                // 触发限流告警
                if (alertService != null) {
                    try {
                        alertService.sendRateLimitAlert(key, maxRequests, windowSeconds);
                    } catch (Exception e) {
                        log.warn("发送限流告警失败", e);
                    }
                }
            }
            
            return allowed;
        } catch (Exception e) {
            log.error("限流脚本执行失败，Fail Open允许请求通过: key={}", key, e);
            // 限流脚本失败时，Fail Open，允许请求通过，避免误杀
            return true;
        }
    }

    /**
     * 全局限流（简化版）
     * 
     * @param qps 允许的QPS（每秒请求数）
     * @return true=允许通过
     */
    public boolean tryAcquireGlobal(int qps) {
        return tryAcquire("global", "all", qps, 1);
    }

    /**
     * IP限流（简化版）
     * 
     * @param ip 请求IP
     * @param qps 允许的QPS
     * @return true=允许通过
     */
    public boolean tryAcquireByIp(String ip, int qps) {
        return tryAcquire("ip", ip, qps, 1);
    }

    /**
     * 获取限流key的当前计数（用于监控调试）
     */
    public int getCurrentCount(String keyPrefix, String keyId) {
        String key = "ratelimit:" + keyPrefix + ":" + keyId;
        try {
            String value = stringRedisTemplate.opsForValue().get(key);
            if (value != null) {
                return Integer.parseInt(value);
            }
        } catch (Exception e) {
            log.warn("获取限流计数失败: key={}", key, e);
        }
        return 0;
    }

    /**
     * 重置限流计数器（用于测试或手动解除限流）
     */
    public void resetLimit(String keyPrefix, String keyId) {
        String key = "ratelimit:" + keyPrefix + ":" + keyId;
        stringRedisTemplate.delete(key);
        log.info("已重置限流计数器: key={}", key);
    }
}
