package com.example.intelligentxtsystem.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * 消息去重服务（智能降噪第一步）
 * 
 * 功能：基于消息内容哈希，在短时间内屏蔽重复消息
 * 
 * 使用场景：
 * - Jenkins 构建失败，短时间内可能触发多条相同通知
 * - 监控告警可能重复发送相同内容的告警
 * - JIRA 状态变更可能触发重复通知
 * 
 * 去重策略：
 * 1. 计算消息内容的 MD5 哈希值
 * 2. 基于 (chatId + eventType + hash) 构建 Redis Key
 * 3. 在去重窗口时间内，相同哈希的消息只推送一次
 */
@Service
public class MessageDedupService {

    private static final Logger log = LoggerFactory.getLogger(MessageDedupService.class);

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 去重窗口时间（秒），默认 300 秒（5 分钟）
     * 可通过配置文件修改：notification.dedup.window-seconds
     */
    @Value("${notification.dedup.window-seconds:300}")
    private int dedupWindowSeconds;

    /**
     * Redis Key 前缀
     */
    private static final String REDIS_KEY_PREFIX = "notify:dedup:";

    /**
     * 检查消息是否重复，如果是重复消息则记录日志
     * 
     * @param chatId    群聊 ID
     * @param eventType 事件类型（如 BUILD、DEPLOY、ALERT、JIRA）
     * @param content   消息内容
     * @return true 表示消息已重复，应丢弃；false 表示可以推送
     */
    public boolean isDuplicate(String chatId, String eventType, String content) {
        if (chatId == null || content == null || content.isEmpty()) {
            return false; // 参数无效，不拦截
        }

        String hash = md5Hash(content);
        String key = buildKey(chatId, eventType, hash);

        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(key, "1", dedupWindowSeconds, TimeUnit.SECONDS);

        if (isNew != null && isNew) {
            // setIfAbsent 返回 true，表示 Key 不存在，这是新消息
            return false;
        } else {
            // setIfAbsent 返回 false 或 null，表示 Key 已存在，这是重复消息
            log.info("检测到重复消息，已屏蔽: chatId={}, eventType={}, hash={}", 
                    maskChatId(chatId), eventType, hash);
            return true;
        }
    }

    /**
     * 手动移除去重记录（用于测试或特殊场景）
     */
    public void clearDedup(String chatId, String eventType, String content) {
        String hash = md5Hash(content);
        String key = buildKey(chatId, eventType, hash);
        redisTemplate.delete(key);
        log.info("已清除去重记录: chatId={}, eventType={}", maskChatId(chatId), eventType);
    }

    /**
     * 构建 Redis Key
     * 格式：notify:dedup:{chatId}:{eventType}:{hash}
     */
    private String buildKey(String chatId, String eventType, String hash) {
        String safeChatId = chatId.replace(":", "_");
        String safeEventType = eventType != null ? eventType.toUpperCase() : "UNKNOWN";
        return REDIS_KEY_PREFIX + safeChatId + ":" + safeEventType + ":" + hash;
    }

    /**
     * 计算字符串的 MD5 哈希值
     */
    private String md5Hash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 不可能不存在，这里做兜底
            log.warn("MD5 算法不存在，使用内容长度作为哈希", e);
            return String.valueOf(content.hashCode());
        }
    }

    /**
     * 脱敏 chatId（日志打印用）
     */
    private String maskChatId(String chatId) {
        if (chatId == null || chatId.length() < 8) return "***";
        return chatId.substring(0, 4) + "***" + chatId.substring(chatId.length() - 4);
    }
}
