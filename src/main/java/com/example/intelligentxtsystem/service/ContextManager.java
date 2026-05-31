package com.example.intelligentxtsystem.service;

import com.example.intelligentxtsystem.dto.UserContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 上下文管理器（Redis 持久化版）
 * 管理用户的对话上下文状态，支持多轮连贯操作
 *
 * 设计说明：
 * 1. 使用 StringRedisTemplate（Key 和 Value 都是 String）
 * 2. JSON 序列化/反序列化 手动用 ObjectMapper 完成
 * 3. 避免使用 RedisCallback（过时 API）
 * 4. 避免类型转换问题（String → byte[]）
 *
 * Redis 存储结构：
 * - 全局参数：ctx:global:{userId}  (Redis Hash)
 * - 局部上下文：ctx:local:{userId}:{contextType}  (Redis String, JSON, 带 TTL)
 */
@Component
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * Redis Key 前缀
     */
    private static final String GLOBAL_PREFIX = "ctx:global:";
    private static final String LOCAL_PREFIX = "ctx:local:";
    
    /**
     * 全局参数 TTL（分钟）
     * 用户5分钟无活动后自动清理，避免数据长久占用内存
     */
    @Value("${context.global-param-ttl-minutes:5}")
    private int globalParamTtlMinutes;

    public ContextManager(StringRedisTemplate stringRedisTemplate,
                         com.fasterxml.jackson.databind.ObjectMapper redisObjectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = redisObjectMapper;
        log.info("上下文管理器初始化完成（Redis 持久化模式）");
    }

    /**
     * 获取用户的全局参数
     * 同时刷新 TTL（续期，避免用户活跃时数据过期）
     */
    public Map<String, Object> getGlobalParams(String userId) {
        String key = GLOBAL_PREFIX + userId;
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);

        // 转换为 String -> Object 映射
        Map<String, Object> result = new ConcurrentHashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            // 将 JSON 字符串反序列化为 Object
            String valueJson = String.valueOf(entry.getValue());
            try {
                Object value = objectMapper.readValue(valueJson, Object.class);
                result.put(String.valueOf(entry.getKey()), value);
            } catch (Exception e) {
                // 如果不是 JSON，直接存为 String
                result.put(String.valueOf(entry.getKey()), valueJson);
            }
        }
        
        // 如果用户有全局参数，刷新 TTL（续期）
        if (!result.isEmpty()) {
            stringRedisTemplate.expire(key, globalParamTtlMinutes, TimeUnit.MINUTES);
        }
        
        return result;
    }

    /**
     * 设置用户的全局参数
     * 同时刷新 TTL（5分钟无活动后自动过期）
     */
    public void setGlobalParam(String userId, String key, Object value) {
        String redisKey = GLOBAL_PREFIX + userId;
        try {
            // 将 value 序列化为 JSON 字符串（避免类型丢失）
            String valueJson = objectMapper.writeValueAsString(value);
            stringRedisTemplate.opsForHash().put(redisKey, key, valueJson);
            
            // 设置 TTL（用户继续操作时会自动续期）
            stringRedisTemplate.expire(redisKey, globalParamTtlMinutes, TimeUnit.MINUTES);
            
            log.debug("设置全局参数: user={}, key={}, value={}", userId, key, value);
        } catch (Exception e) {
            log.error("序列化全局参数失败: user={}, key={}", userId, key, e);
        }
    }

    /**
     * 获取用户指定类型的局部上下文
     * 如果存在且未过期，更新访问时间并续期
     */
    public UserContext getLocalContext(String userId, String contextType) {
        String key = LOCAL_PREFIX + userId + ":" + contextType;

        // 检查 Key 是否存在（Redis 自动过期）
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(key))) {
            return null;
        }

        // 从 Redis 读取上下文（JSON 字符串）
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }

        try {
            // 将 JSON 字符串反序列化为 UserContext
            UserContext context = objectMapper.readValue(json, UserContext.class);

            // 更新访问时间（续期）
            context.updateAccessTime();
            // 重新写入并重置 TTL
            String updatedJson = objectMapper.writeValueAsString(context);
            stringRedisTemplate.opsForValue().set(
                    key,
                    updatedJson,
                    context.getTimeoutMinutes(),
                    TimeUnit.MINUTES
            );
            return context;
        } catch (Exception e) {
            log.error("反序列化局部上下文失败: user={}, type={}", userId, contextType, e);
            // 清理无效数据
            stringRedisTemplate.delete(key);
            return null;
        }
    }

    /**
     * 设置用户的局部上下文
     */
    public void setLocalContext(String userId, String contextType,
                                Map<String, Object> params, int timeoutMinutes) {
        String key = LOCAL_PREFIX + userId + ":" + contextType;
        UserContext context = new UserContext(contextType, params, timeoutMinutes);

        try {
            // 将 UserContext 序列化为 JSON 字符串
            String json = objectMapper.writeValueAsString(context);
            // 写入 Redis，并设置 TTL（分钟）
            stringRedisTemplate.opsForValue().set(
                    key,
                    json,
                    timeoutMinutes,
                    TimeUnit.MINUTES
            );
            log.debug("设置局部上下文: user={}, type={}, params={}", userId, contextType, params);
        } catch (Exception e) {
            log.error("序列化局部上下文失败: user={}, type={}", userId, contextType, e);
        }
    }

    /**
     * 清除用户指定类型的局部上下文
     */
    public void clearLocalContext(String userId, String contextType) {
        String key = LOCAL_PREFIX + userId + ":" + contextType;
        stringRedisTemplate.delete(key);
        log.debug("清除局部上下文: user={}, type={}", userId, contextType);
    }

    /**
     * 清除用户的所有局部上下文
     * （匹配 ctx:local:{userId}:*）
     */
    public void clearAllLocalContexts(String userId) {
        String pattern = LOCAL_PREFIX + userId + ":*";
        var keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
        log.debug("清除所有局部上下文: user={}", userId);
    }

    /**
     * 清除用户的全局参数
     */
    public void clearGlobalParams(String userId) {
        String key = GLOBAL_PREFIX + userId;
        stringRedisTemplate.delete(key);
        log.debug("清除全局参数: user={}", userId);
    }

    /**
     * 清除用户的所有上下文（退出登录或重置时调用）
     */
    public void clearAll(String userId) {
        clearAllLocalContexts(userId);
        clearGlobalParams(userId);
        log.debug("清除所有上下文: user={}", userId);
    }

    /**
     * 自动填充参数
     * 优先使用当前输入的参数，缺失时从上下文补充
     */
    public Map<String, Object> autoFillParams(String userId, String contextType,
                                              Map<String, Object> currentParams,
                                              String[] localParamNames) {
        Map<String, Object> filledParams = new ConcurrentHashMap<>(currentParams);

        log.info("autoFillParams 开始: userId={}, contextType={}, currentParams={}, localParamNames={}", 
                 userId, contextType, currentParams, String.join(",", localParamNames));

        // 1. 先填充全局参数（如果参数值为空）
        Map<String, Object> userGlobalParams = getGlobalParams(userId);
        for (String paramName : localParamNames) {
            if (!filledParams.containsKey(paramName) || filledParams.get(paramName) == null) {
                Object globalValue = userGlobalParams.get(paramName);
                if (globalValue != null) {
                    filledParams.put(paramName, globalValue);
                    log.info("从全局参数填充: {}={}", paramName, globalValue);
                }
            }
        }

        // 2. 再填充局部上下文参数（优先级低于当前输入）
        UserContext localContext = getLocalContext(userId, contextType);
        if (localContext != null) {
            Map<String, Object> contextParams = localContext.getParams();
            log.info("从Redis读取到局部上下文: userId={}, contextType={}, params={}", userId, contextType, contextParams);
            for (String paramName : localParamNames) {
                if (!filledParams.containsKey(paramName) || filledParams.get(paramName) == null) {
                    Object contextValue = contextParams.get(paramName);
                    if (contextValue != null) {
                        filledParams.put(paramName, contextValue);
                        log.info("从局部上下文填充: {}={}", paramName, contextValue);
                    }
                }
            }
        } else {
            log.warn("未从Redis读取到局部上下文: userId={}, contextType={}", userId, contextType);
        }

        log.info("autoFillParams 完成: filledParams={}", filledParams);
        return filledParams;
    }
}
