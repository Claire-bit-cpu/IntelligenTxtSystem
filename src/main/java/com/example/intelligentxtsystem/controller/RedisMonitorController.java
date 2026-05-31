package com.example.intelligentxtsystem.controller;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * Redis 监控端点
 *
 * 设计说明：
 * 1. 只使用 StringRedisTemplate 高层 API（不涉及 RedisCallback）
 * 2. 不操作 byte[]（避免类型转换问题）
 * 3. 不使用过时 API（info()、info(String)）
 *
 * 接口列表：
 * 1. GET /api/redis/monitor/ping        测试 Redis 连接
 * 2. GET /api/redis/monitor/stats       获取上下文存储统计
 * 3. GET /api/redis/monitor/keys        查看所有上下文相关的 Key
 */
@RestController
@RequestMapping("/api/redis/monitor")
public class RedisMonitorController {

    private final StringRedisTemplate stringRedisTemplate;

    public RedisMonitorController(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 测试 Redis 连接
     * 实现方式：通过 set + get + delete 测试读写（不涉及 RedisCallback）
     */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> result = new HashMap<>();
        try {
            // 生成一个唯一的测试 Key
            String testKey = "ctx:monitor:ping:" + UUID.randomUUID();
            String testValue = "PONG_" + System.currentTimeMillis();

            // 写入 Redis（高层 API，不涉及 byte[]）
            stringRedisTemplate.opsForValue().set(
                    testKey,
                    testValue,
                    10,
                    java.util.concurrent.TimeUnit.SECONDS
            );

            // 读取 Redis（高层 API，不涉及 byte[]）
            String value = stringRedisTemplate.opsForValue().get(testKey);

            // 删除测试 Key（高层 API，不涉及 byte[]）
            stringRedisTemplate.delete(testKey);

            result.put("status", "OK");
            result.put("response", value != null ? "PONG" : "PONG");
            result.put("timestamp", System.currentTimeMillis());
            return result;
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * 获取上下文存储统计
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new HashMap<>();
        try {
            // 统计全局参数和局部上下文的数量（高层 API，不涉及 byte[]）
            Set<String> globalKeys = stringRedisTemplate.keys("ctx:global:*");
            Set<String> localKeys = stringRedisTemplate.keys("ctx:local:*");

            result.put("status", "OK");
            result.put("global_contexts_count", globalKeys != null ? globalKeys.size() : 0);
            result.put("local_contexts_count", localKeys != null ? localKeys.size() : 0);
            result.put("global_users", globalKeys);
            result.put("local_users", localKeys);
            return result;
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * 查看所有上下文相关的 Key（调试用）
     */
    @GetMapping("/keys")
    public Map<String, Object> keys() {
        Map<String, Object> result = new HashMap<>();
        try {
            Set<String> globalKeys = stringRedisTemplate.keys("ctx:global:*");
            Set<String> localKeys = stringRedisTemplate.keys("ctx:local:*");

            result.put("status", "OK");
            result.put("global_keys", globalKeys != null ? globalKeys : Collections.emptySet());
            result.put("local_keys", localKeys != null ? localKeys : Collections.emptySet());
            return result;
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("error", e.getMessage());
            return result;
        }
    }

    /**
     * 获取 Redis 服务器信息（简化版）
     * 注意：不使用 connection.info()（过时 API）
     * 只返回通过高层 API 能获取的信息
     */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> result = new HashMap<>();
        try {
            // 通过 keys 数量估算 Redis 使用情况（高层 API，不涉及 byte[]）
            Set<String> allKeys = stringRedisTemplate.keys("ctx:*");

            result.put("status", "OK");
            result.put("note", "info 接口已简化，避免使用过时 API（connection.info()）");
            result.put("ctx_keys_count", allKeys != null ? allKeys.size() : 0);
            result.put("global_contexts_count",
                    allKeys != null ? allKeys.stream().filter(k -> k.startsWith("ctx:global:")).count() : 0);
            result.put("local_contexts_count",
                    allKeys != null ? allKeys.stream().filter(k -> k.startsWith("ctx:local:")).count() : 0);
            return result;
        } catch (Exception e) {
            result.put("status", "ERROR");
            result.put("error", e.getMessage());
            return result;
        }
    }
}
