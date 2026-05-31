package com.example.intelligentxtsystem.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 幂等性服务
 * 基于Redis实现事件去重，防止飞书重试导致重复处理
 * 
 * 设计说明：
 * 1. 使用飞书事件的event_id作为幂等键
 * 2. 设置24小时过期时间（飞书最多重试12次，总时间不超过40分钟）
 * 3. 使用Redis的setIfAbsent保证原子性
 */
@Service
public class IdempotentService {

    private static final Logger log = LoggerFactory.getLogger(IdempotentService.class);

    @Value("${idempotent.key-prefix:feishu:event:idempotent:}")
    private String idempotentKeyPrefix;

    @Value("${idempotent.expire-seconds:86400}")
    private long idempotentExpireSeconds;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 检查事件是否已处理（幂等性检查）
     * 
     * @param eventId 飞书事件ID
     * @return true表示事件已处理，false表示未处理且已标记为处理中
     */
    public boolean isAlreadyProcessed(String eventId) {
        if (eventId == null || eventId.isEmpty()) {
            log.warn("事件ID为空，跳过幂等性检查");
            return false;
        }

        String key = idempotentKeyPrefix + eventId;
        
        // 使用setIfAbsent原子操作，只有当key不存在时才设置成功
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", idempotentExpireSeconds, TimeUnit.SECONDS);
        
        if (Boolean.FALSE.equals(success)) {
            log.info("事件已处理，跳过重复处理: eventId={}", eventId);
            return true;
        }
        
        log.debug("事件首次处理，已标记幂等键: eventId={}", eventId);
        return false;
    }

    /**
     * 手动移除幂等键（用于测试或异常处理）
     * 
     * @param eventId 飞书事件ID
     */
    public void removeIdempotentKey(String eventId) {
        if (eventId == null || eventId.isEmpty()) {
            return;
        }
        
        String key = idempotentKeyPrefix + eventId;
        Boolean deleted = stringRedisTemplate.delete(key);
        
        if (Boolean.TRUE.equals(deleted)) {
            log.debug("已移除幂等键: eventId={}", eventId);
        }
    }

    /**
     * 获取幂等键的剩余过期时间（用于调试）
     * 
     * @param eventId 飞书事件ID
     * @return 剩余秒数，-1表示永不过期，-2表示不存在
     */
    public long getIdempotentKeyTTL(String eventId) {
        if (eventId == null || eventId.isEmpty()) {
            return -2;
        }
        
        String key = idempotentKeyPrefix + eventId;
        return stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
    }
}
