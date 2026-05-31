package com.example.intelligentxtsystem.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 敏感操作二次确认服务
 * 将待确认操作存入 Redis，用户确认后执行
 *
 * 流程：
 *   1. 敏感指令首次触发 → 生成 confirmToken，存储操作信息到 Redis，返回确认提示
 *   2. 用户回复 "确认 <token>" 或带 --confirm 参数重发 → 读取 Redis 并执行
 *   3. Redis Key 自动过期（默认 5 分钟）
 */
@Service
public class ConfirmService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmService.class);

    private static final String REDIS_PREFIX = "confirm:";

    @Value("${confirm.token-expire-seconds:300}")
    private long expireSeconds; // 默认5分钟

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 存储待确认操作
     *
     * @param openId       操作者 Open ID
     * @param chatId       群聊 ID
     * @param commandName  指令名称（如 deploy）
     * @param args         指令参数
     * @param summary      操作摘要（展示给用户）
     * @return confirmToken 确认令牌
     */
    public String storePendingAction(String openId, String chatId,
                                   String commandName, String args, String summary) {
        String token = UUID.randomUUID().toString().substring(0, 8);
        String redisKey = REDIS_PREFIX + token;

        // 格式：openId|chatId|commandName|args|summary
        String value = String.join("|",
                openId != null ? openId : "",
                chatId != null ? chatId : "",
                commandName,
                args != null ? args : "",
                summary != null ? summary : "");

        if (stringRedisTemplate != null) {
            stringRedisTemplate.opsForValue().set(redisKey, value, expireSeconds, TimeUnit.SECONDS);
            log.info("待确认操作已存储: token={}, command={}, openId={}", token, commandName, maskOpenId(openId));
        } else {
            log.warn("Redis 不可用，无法存储待确认操作（确认功能将失效）");
        }

        return token;
    }

    /**
     * 消费待确认操作（一次性）
     * 只有操作者本人 + 相同 chatId 才能确认
     *
     * @return 操作信息字符串（openId|chatId|commandName|args|summary），失败返回 null
     */
    public PendingAction consume(String token, String openId, String chatId) {
        if (token == null || token.isEmpty()) return null;

        String redisKey = REDIS_PREFIX + token;
        if (stringRedisTemplate == null) {
            log.warn("Redis 不可用，无法读取待确认操作");
            return null;
        }

        String value = stringRedisTemplate.opsForValue().get(redisKey);
        if (value == null) {
            log.warn("确认令牌不存在或已过期: token={}", token);
            return null;
        }

        // 验证操作者
        String[] parts = value.split("\\|", 5);
        if (parts.length < 5) {
            log.warn("待确认操作数据格式错误: token={}", token);
            stringRedisTemplate.delete(redisKey);
            return null;
        }

        String storedOpenId = parts[0];
        String storedChatId = parts[1];

        // 校验身份和群聊
        if (!storedOpenId.equals(openId)) {
            log.warn("确认操作者不匹配: stored={}, actual={}", maskOpenId(storedOpenId), maskOpenId(openId));
            return null;
        }
        if (!storedChatId.equals(chatId)) {
            log.warn("确认群聊不匹配: stored={}, actual={}", storedChatId, chatId);
            return null;
        }

        // 一次性消费：删除 Key
        stringRedisTemplate.delete(redisKey);

        return new PendingAction(storedOpenId, storedChatId, parts[2], parts[3], parts[4]);
    }

    /**
     * 检查令牌是否存在（用于 handler 判断是否已确认）
     */
    public boolean exists(String token) {
        if (token == null || stringRedisTemplate == null) return false;
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(REDIS_PREFIX + token));
    }

    private String maskOpenId(String openId) {
        if (openId == null || openId.length() < 8) return "***";
        return openId.substring(0, 4) + "***" + openId.substring(openId.length() - 4);
    }

    /**
     * 待确认操作信息
     */
    public static class PendingAction {
        private final String openId;
        private final String chatId;
        private final String commandName;
        private final String args;
        private final String summary;

        public PendingAction(String openId, String chatId, String commandName, String args, String summary) {
            this.openId = openId;
            this.chatId = chatId;
            this.commandName = commandName;
            this.args = args;
            this.summary = summary;
        }

        public String getOpenId() { return openId; }
        public String getChatId() { return chatId; }
        public String getCommandName() { return commandName; }
        public String getArgs() { return args; }
        public String getSummary() { return summary; }
    }
}
