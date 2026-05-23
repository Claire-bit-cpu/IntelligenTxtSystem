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

    public MessageProcessor(ObjectMapper objectMapper, FeishuClient feishuClient, MessageDispatcher messageDispatcher) {
        this.objectMapper = objectMapper;
        this.feishuClient = feishuClient;
        this.messageDispatcher = messageDispatcher;
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

            // 提取消息文本
            String text = extractText(callback);
            log.info("收到消息: {}", text);

            if (text == null || text.isEmpty()) {
                return;
            }

            // 分发处理
            String chatId = callback.getEvent().getMessage().getChatId();
            String reply = messageDispatcher.dispatch(text, callback.getEvent().getSender(), chatId);

            // 发送回复
            feishuClient.sendText(chatId, reply);

        } catch (Exception e) {
            log.error("异步处理消息事件异常", e);
        }
    }

    private String extractText(FeishuCallback callback) {
        try {
            String contentJson = callback.getEvent().getMessage().getContent();
            log.info("原始消息内容: {}", contentJson);

            MessageContent content = objectMapper.readValue(contentJson, MessageContent.class);
            String text = content.getText();
            log.info("解析后消息: {}", text);

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
