package com.example.intelligentxtsystem.service;

import com.example.intelligentxtsystem.client.FeishuClient;
import com.example.intelligentxtsystem.dto.FeishuCallback;
import com.example.intelligentxtsystem.dto.MessageContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步消息处理器
 * 独立 Service 类，确保 @Async 通过 Spring 代理生效
 */
@Service
public class MessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);

    private final ConcurrentHashMap<String, Long> processedMessages = new ConcurrentHashMap<>();

    @Value("${dedup.window-ms}")
    private long dedupWindowMs;

    @Value("${dedup.cleanup-ms}")
    private long dedupCleanupMs;

    private final ObjectMapper objectMapper;
    private final FeishuClient feishuClient;
    private final MessageDispatcher messageDispatcher;
    private final NotificationConfigService notificationConfigService;

    public MessageProcessor(ObjectMapper objectMapper, FeishuClient feishuClient, 
                           MessageDispatcher messageDispatcher, NotificationConfigService notificationConfigService) {
        this.objectMapper = objectMapper;
        this.feishuClient = feishuClient;
        this.messageDispatcher = messageDispatcher;
        this.notificationConfigService = notificationConfigService;
    }

    /**
     * 异步处理消息事件
     */
    @Async("messageExecutor")
    public void processMessageEvent(Map<String, Object> body) {
        try {
            FeishuCallback callback = objectMapper.convertValue(body, FeishuCallback.class);

            // 消息去重
            String messageId = callback.getEvent().getMessage().getMessageId();
            long now = System.currentTimeMillis();
            Long lastTime = processedMessages.putIfAbsent(messageId, now);

            if (lastTime != null) {
                if (now - lastTime < dedupWindowMs) {
                    log.info("消息 {} 已在 {}ms 前处理过，跳过", messageId, now - lastTime);
                    return;
                } else {
                    processedMessages.put(messageId, now);
                }
            }

            // 清理过期记录
            processedMessages.entrySet().removeIf(entry -> now - entry.getValue() > dedupCleanupMs);

            // 自动注册群聊（从飞书事件获取chatId，默认禁用）
            String chatId = callback.getEvent().getMessage().getChatId();
            if (chatId != null && !chatId.isEmpty()) {
                notificationConfigService.registerChatFromEvent(chatId, null);
            }

            // 提取消息文本
            String text = extractText(callback);
            log.info("收到消息: {}", text);

            if (text == null || text.isEmpty()) {
                return;
            }

            // 分发处理
            String reply = messageDispatcher.dispatch(text, callback.getEvent().getSender(), chatId);

            // 只有匹配到命令时才发送回复
            // reply == null 可能是 handler 已通过其他方式回复（如发送卡片），不算错误
            if (reply != null && !reply.isEmpty()) {
                feishuClient.sendText(chatId, reply);
            } else if (reply == null) {
                log.info("命令已处理（无需文本回复）: {}", text);
            } else {
                log.info("消息不匹配任何命令，不回复: {}", text);
            }

        } catch (Exception e) {
            log.error("异步处理消息事件异常", e);
        }
    }

    private String extractText(FeishuCallback callback) {
        try {
            String contentJson = callback.getEvent().getMessage().getContent();
            String messageType = callback.getEvent().getMessage().getMessageType();
            log.info("原始消息内容: {}, 消息类型: {}", contentJson, messageType);

            // 根据消息类型解析内容
            String text = null;
            
            if ("text".equals(messageType)) {
                // 文本消息
                MessageContent content = objectMapper.readValue(contentJson, MessageContent.class);
                text = content.getText();
            } else if ("wiki".equals(messageType)) {
                // 知识库链接消息
                @SuppressWarnings("unchecked")
                Map<String, Object> wikiContent = objectMapper.readValue(contentJson, Map.class);
                text = (String) wikiContent.getOrDefault("title", "Wiki文档");
                log.info("知识库消息: title={}", text);
            } else {
                // 其他类型消息（file, doc, docx, sheet 等）
                log.info("不支持的消息类型: {}", messageType);
                return null;
            }

            log.info("解析后消息: {}", text);

            // 去掉 @机器人 部分
            if (text != null && text.contains(" ")) {
                text = text.replaceFirst("^@[^\\s]+\\s*", "");
            }

            return text;
        } catch (Exception e) {
            log.warn("解析消息内容失败", e);
            return null;
        }
    }
}
