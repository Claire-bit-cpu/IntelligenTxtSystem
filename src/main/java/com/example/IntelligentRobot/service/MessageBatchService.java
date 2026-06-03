package com.example.IntelligentRobot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 消息合并服务（智能降噪第二步）
 * 
 * 功能：将短时间内的同类消息合并为一条摘要推送
 * 
 * 使用场景：
 * - CI/CD 构建：10 分钟内多次构建失败，合并为一条摘要
 * - 监控告警：同一服务多个实例告警，合并为一条
 * - JIRA 更新：短时间内多个任务状态变更，合并通知
 * 
 * 合并策略：
 * 1. 按 (chatId + eventType) 分组，使用 Redis List 存储待合并消息
 * 2. 每条消息加入队列时，检查是否达到合并阈值
 * 3. 达到阈值立即推送合并摘要；未达阈值等待定时任务推送
 * 4. 超过合并窗口时间，无论是否达到阈值都推送
 * 
 * 配置项：
 * - notification.batch.enabled: 是否启用消息合并（默认 true）
 * - notification.batch.window-seconds: 合并窗口时间（默认 600 秒，10 分钟）
 * - notification.batch.threshold: 合并阈值，达到此数量立即推送（默认 5）
 */
@Service
public class MessageBatchService {

    private static final Logger log = LoggerFactory.getLogger(MessageBatchService.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private com.example.IntelligentRobot.client.FeishuClient feishuClient;

    /**
     * 合并窗口时间（秒），默认 600 秒（10 分钟）
     */
    @Value("${notification.batch.window-seconds:600}")
    private int batchWindowSeconds;

    /**
     * 合并阈值，达到此数量立即推送，默认 5
     */
    @Value("${notification.batch.threshold:5}")
    private int batchThreshold;

    /**
     * 是否启用消息合并，默认 true
     */
    @Value("${notification.batch.enabled:true}")
    private boolean batchEnabled;

    /**
     * Redis Key 前缀（待合并消息队列）
     */
    private static final String BATCH_QUEUE_PREFIX = "notify:batch:queue:";

    /**
     * Redis Key 前缀（合并摘要缓存，防止窗口内重复推送）
     */
    private static final String BATCH_SENT_PREFIX = "notify:batch:sent:";

    /**
     * 添加消息到合并队列
     * 
     * @param chatId    群聊 ID
     * @param eventType 事件类型（如 BUILD、DEPLOY、ALERT、JIRA）
     * @param content   消息内容
     * @return 如果达到合并阈值，返回合并后的摘要消息；否则返回 null（稍后定时推送）
     */
    public String addToBatch(String chatId, String eventType, String content) {
        if (!batchEnabled) {
            return null; // 未启用合并，返回 null 表示立即推送原消息
        }

        if (chatId == null || content == null || content.isEmpty()) {
            return null;
        }

        String safeEventType = eventType != null ? eventType.toUpperCase() : "UNKNOWN";
        String queueKey = buildQueueKey(chatId, safeEventType);
        String sentKey = buildSentKey(chatId, safeEventType);

        // 检查本窗口内是否已推送过合并摘要（避免重复推送）
        Boolean alreadySent = redisTemplate.hasKey(sentKey);
        if (alreadySent != null && alreadySent) {
            // 已推送过，清理旧队列，开启新窗口
            redisTemplate.delete(queueKey);
        }

        // 将消息加入队列
        Long size = redisTemplate.opsForList().rightPush(queueKey, content);
        redisTemplate.expire(queueKey, batchWindowSeconds, TimeUnit.SECONDS);

        log.debug("消息已加入合并队列: chatId={}, eventType={}, 队列长度={}", 
                maskChatId(chatId), safeEventType, size);

        // 达到阈值，立即生成合并摘要并推送
        if (size != null && size >= batchThreshold) {
            String summary = buildBatchSummary(queueKey, safeEventType);
            // 标记本窗口已推送
            redisTemplate.opsForValue().set(sentKey, "1", batchWindowSeconds, TimeUnit.SECONDS);
            // 清空队列
            redisTemplate.delete(queueKey);
            return summary;
        }

        return null; // 未达阈值，不推送
    }

    /**
     * 定时任务调用：推送所有到期未推送的合并消息
     * 由 @Scheduled 任务调用（在 MessageBatchScheduler 中）
     */
    public void flushAllBatches() {
        if (!batchEnabled) return;

        // 获取所有匹配的通知合并队列 Key
        // 注意：生产环境建议使用 Redis SCAN，这里简化为固定前缀扫描
        String pattern = BATCH_QUEUE_PREFIX + "*";
        Iterable<String> keys = redisTemplate.keys(pattern);

        if (keys == null) return;

        for (String queueKey : keys) {
            try {
                flushBatch(queueKey);
            } catch (Exception e) {
                log.warn("刷新合并队列失败: key={}", queueKey, e);
            }
        }
    }

    /**
     * 推送单个合并队列中的消息
     */
    private void flushBatch(String queueKey) {
        // 获取队列中的所有消息
        List<String> messages = redisTemplate.opsForList().range(queueKey, 0, -1);
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // 解析 chatId 和 eventType
        String[] parts = parseQueueKey(queueKey);
        if (parts == null) return;

        String chatId = parts[0];
        String eventType = parts[1];

        // 检查是否已推送过
        String sentKey = buildSentKey(chatId, eventType);
        Boolean alreadySent = redisTemplate.hasKey(sentKey);
        if (alreadySent != null && alreadySent) {
            return; // 已推送过，跳过
        }

        // 生成合并摘要
        String summary = buildBatchSummary(queueKey, eventType);

        // 推送摘要
        if (feishuClient != null && summary != null) {
            try {
                feishuClient.sendText(chatId, summary);
                log.info("合并消息已推送: chatId={}, eventType={}, 消息数={}", 
                        maskChatId(chatId), eventType, messages.size());
            } catch (Exception e) {
                log.error("合并消息推送失败: chatId={}, eventType={}", maskChatId(chatId), eventType, e);
            }
        }

        // 标记已推送，清空队列
        redisTemplate.opsForValue().set(sentKey, "1", batchWindowSeconds, TimeUnit.SECONDS);
        redisTemplate.delete(queueKey);
    }

    /**
     * 构建合并摘要消息
     */
    private String buildBatchSummary(String queueKey, String eventType) {
        List<String> messages = redisTemplate.opsForList().range(queueKey, 0, -1);
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 **%s 事件汇总**（过去 %d 分钟）\n\n", 
                getEventTypeDisplayName(eventType), batchWindowSeconds / 60));

        // 按消息内容分组计数
        var messageCounts = new java.util.HashMap<String, Integer>();
        for (String msg : messages) {
            messageCounts.merge(msg, 1, Integer::sum);
        }

        // 生成摘要
        int index = 1;
        for (var entry : messageCounts.entrySet()) {
            String content = entry.getKey();
            int count = entry.getValue();
            if (count > 1) {
                sb.append(String.format("%d. %s（重复 %d 次）\n", index++, content, count));
            } else {
                sb.append(String.format("%d. %s\n", index++, content));
            }
        }

        sb.append(String.format("\n💡 共 %d 条 %s 事件", messages.size(), eventType));
        return sb.toString();
    }

    /**
     * 构建合并队列 Redis Key
     * 格式：notify:batch:queue:{chatId}:{eventType}
     * 注意：chatId 中的 : 需要转义，因为 : 被用作分隔符
     * 飞书 chatId 格式为 oc_xxx（下划线），不包含 :，所以无需转义
     * 但为了安全，使用 __COLON__ 作为转义序列
     */
    private String buildQueueKey(String chatId, String eventType) {
        // 规范化 chatId：确保是 oc_ 格式，而不是 oc: 格式
        if (chatId != null && chatId.startsWith("oc:")) {
            chatId = "oc_" + chatId.substring(3);
        }
        // 转义 : 为 __COLON__（虽然 chatId 通常不包含 :，但为了安全）
        String safeChatId = chatId.replace(":", "__COLON__");
        return BATCH_QUEUE_PREFIX + safeChatId + ":" + eventType;
    }

    /**
     * 构建已推送标记 Redis Key
     */
    private String buildSentKey(String chatId, String eventType) {
        // 规范化 chatId：确保是 oc_ 格式，而不是 oc: 格式
        if (chatId != null && chatId.startsWith("oc:")) {
            chatId = "oc_" + chatId.substring(3);
        }
        String safeChatId = chatId.replace(":", "__COLON__");
        return BATCH_SENT_PREFIX + safeChatId + ":" + eventType;
    }

    /**
     * 从队列 Key 解析 chatId 和 eventType
     */
    private String[] parseQueueKey(String queueKey) {
        // 格式：notify:batch:queue:{safeChatId}:{eventType}
        String prefix = BATCH_QUEUE_PREFIX;
        if (!queueKey.startsWith(prefix)) return null;

        String remaining = queueKey.substring(prefix.length());
        int lastColon = remaining.lastIndexOf(":");
        if (lastColon < 0) return null;

        String safeChatId = remaining.substring(0, lastColon);
        String eventType = remaining.substring(lastColon + 1);
        
        // 反转义：将 __COLON__ 转回 :
        String chatId = safeChatId.replace("__COLON__", ":");
        
        // 规范化：确保是 oc_ 格式
        if (chatId.startsWith("oc:")) {
            chatId = "oc_" + chatId.substring(3);
        }
        
        return new String[]{chatId, eventType};
    }

    /**
     * 获取事件类型的中文显示名称
     */
    private String getEventTypeDisplayName(String eventType) {
        return switch (eventType.toUpperCase()) {
            case "BUILD" -> "构建";
            case "DEPLOY" -> "部署";
            case "ALERT" -> "告警";
            case "JIRA" -> "JIRA";
            case "MONITOR" -> "监控";
            default -> eventType;
        };
    }

    /**
     * 脱敏 chatId（日志打印用）
     */
    private String maskChatId(String chatId) {
        if (chatId == null || chatId.length() < 8) return "***";
        return chatId.substring(0, 4) + "***" + chatId.substring(chatId.length() - 4);
    }
}
