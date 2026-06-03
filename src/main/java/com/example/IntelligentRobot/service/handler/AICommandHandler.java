package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.QwenClient;
import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.service.AiUnderstandingService;
import com.example.IntelligentRobot.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * AI智能问答指令处理器（新框架版本）
 * 指令格式：/AI <问题>
 * 使用通义千问进行智能问答（支持多轮对话）
 * 
 * 智能降噪：
 * - 去重：30秒内相同用户提问相同问题，只处理一次
 * - 通过 Redis 实现分布式去重
 */
@Component
public class AICommandHandler {

    private static final Logger log = LoggerFactory.getLogger(AICommandHandler.class);

    private final QwenClient qwenClient;
    private final AiUnderstandingService aiUnderstandingService;
    private final NotificationService notificationService;
    private final StringRedisTemplate redisTemplate;

    // 去重窗口：30秒
    private static final int DEDUP_WINDOW_SECONDS = 30;

    // Redis Key 前缀
    private static final String REDIS_KEY_PREFIX = "ai:dedup:";

    @Autowired(required = false)
    public AICommandHandler(QwenClient qwenClient, 
                            AiUnderstandingService aiUnderstandingService,
                            NotificationService notificationService,
                            StringRedisTemplate redisTemplate) {
        this.qwenClient = qwenClient;
        this.aiUnderstandingService = aiUnderstandingService;
        this.notificationService = notificationService;
        this.redisTemplate = redisTemplate;
    }

    @Command(
        name = "ai",
        description = "AI智能问答（支持多轮对话）",
        usage = "/ai <问题>"
    )
    public String handle(CommandContext context) {
        String query = context.getArgs().trim();

        if (query.isEmpty()) {
            return """
                    ❌ 用法：/AI <问题>
                    
                    📋 示例：
                    /AI 如何创建项目
                    /AI Java数组怎么用
                    
                    💡 可以询问任何技术或业务问题
                    💡 支持多轮对话，可以接着上一轮的问题继续问
                    """;
        }

        if (query.length() > 1000) {
            return "⚠️ 问题长度不能超过1000个字符";
        }

        // 去重检查：避免短时间内重复提问相同问题
        String chatId = context.getChatId();
        String userId = context.getSender() != null ? context.getSender().getId() : "unknown";
        
        if (isDuplicateQuery(chatId, userId, query)) {
            log.info("检测到重复的 AI 提问，已拦截: userId={}, query={}", maskUserId(userId), query);
            return "⚠️ 您刚才已经问过相同的问题，请等待回答或换个问题问问看～";
        }

        try {
            // 获取用户ID和聊天ID
            userId = context.getSender().getId();
            chatId = context.getChatId();
            
            // 构建包含对话历史的提示词
            String prompt = buildPromptWithHistory(query, chatId, userId);
            
            // 调用AI获取回答
            String answer = qwenClient.answerQuestion(prompt);
            
            // 保存对话历史
            aiUnderstandingService.saveConversationHistory(chatId, userId, query, answer);

            return String.format("""
                    🤖 问题：%s
                    
                    💡 回答：
                    %s
                    """, query, answer);
        } catch (Exception e) {
            return "⚠️ AI服务暂时不可用，请稍后再试";
        }
    }

    /**
     * 构建包含对话历史的提示词
     */
    private String buildPromptWithHistory(String query, String chatId, String userId) {
        // 获取最近的对话历史（最近5轮）
        java.util.List<String> history = aiUnderstandingService.getConversationHistory(chatId, userId, 10);
        
        StringBuilder prompt = new StringBuilder();
        
        if (!history.isEmpty()) {
            prompt.append("你和一个用户正在进行多轮对话。以下是最近的对话记录：\n\n");
            
            // 历史记录格式是 "用户：xxx" 和 "机器人：xxx"
            // 需要转换成更清晰的格式
            for (String h : history) {
                if (h.startsWith("用户：")) {
                    prompt.append("用户：").append(h.substring(3)).append("\n");
                } else if (h.startsWith("机器人：")) {
                    prompt.append("助手：").append(h.substring(4)).append("\n");
                } else {
                    prompt.append(h).append("\n");
                }
            }
            
            prompt.append("\n基于以上对话历史，继续回答用户的新问题。注意要理解上下文，不要重复之前已经回答过的内容。\n\n");
        }
        
            prompt.append("用户的新问题：").append(query).append("\n\n请回答：");
        
        return prompt.toString();
    }

    /**
     * 检查是否是重复的 AI 提问（去重逻辑）
     * 基于 (chatId + userId + queryHash) 构建去重 Key
     * 去重窗口：30秒（避免用户短时间内重复发送相同问题）
     * 
     * @param chatId 群聊ID
     * @param userId 用户ID
     * @param query 提问内容
     * @return true 表示重复提问，应拦截；false 表示可以处理
     */
    private boolean isDuplicateQuery(String chatId, String userId, String query) {
        if (redisTemplate == null) {
            return false; // Redis 不可用，不拦截
        }

        try {
            // 计算查询内容的 MD5 哈希
            String queryHash = md5Hash(query);
            
            // 构建 Redis Key：ai:dedup:{chatId}:{userId}:{queryHash}
            String safeChatId = chatId.replace(":", "_");
            String key = REDIS_KEY_PREFIX + safeChatId + ":" + userId + ":" + queryHash;
            
            // 使用 setIfAbsent 实现去重（原子操作）
            Boolean isNew = redisTemplate.opsForValue()
                    .setIfAbsent(key, "1", DEDUP_WINDOW_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            
            if (isNew != null && isNew) {
                // setIfAbsent 返回 true，表示 Key 不存在，这是新提问
                return false;
            } else {
                // setIfAbsent 返回 false 或 null，表示 Key 已存在，这是重复提问
                log.info("检测到重复的 AI 提问，已拦截: userId={}, query={}", maskUserId(userId), query);
                return true;
            }
            
        } catch (Exception e) {
            log.warn("AI 提问去重检查失败，允许通过", e);
        }
        
        return false; // 检查失败，不拦截
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
        } catch (Exception e) {
            log.warn("MD5 计算失败，使用内容长度作为哈希", e);
            return String.valueOf(content.hashCode());
        }
    }

    /**
     * 脱敏用户ID（日志用）
     */
    private String maskUserId(String userId) {
        if (userId == null || userId.length() < 8) return "***";
        return userId.substring(0, 4) + "***" + userId.substring(userId.length() - 4);
    }
}
