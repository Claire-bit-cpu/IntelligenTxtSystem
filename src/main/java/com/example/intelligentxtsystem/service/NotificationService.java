package com.example.intelligentxtsystem.service;

import com.example.intelligentxtsystem.client.FeishuClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知发送服务
 * 向配置中的群聊发送通知消息
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    @Value("${notification.default-chat-ids:}")
    private String defaultChatIds;

    private final FeishuClient feishuClient;
    private final NotificationConfigService notificationConfigService;

    public NotificationService(FeishuClient feishuClient, NotificationConfigService notificationConfigService) {
        this.feishuClient = feishuClient;
        this.notificationConfigService = notificationConfigService;
    }

    /**
     * 发送通知到所有配置的群聊
     * @param message 通知内容
     */
    public void sendNotification(String message) {
        // 先从数据库获取启用的群聊
        List<String> chatIds = notificationConfigService.getEnabledChatIds();

        // 如果数据库没有配置，使用默认配置
        if (chatIds.isEmpty() && !defaultChatIds.isEmpty()) {
            for (String chatId : defaultChatIds.split(",")) {
                chatId = chatId.trim();
                if (!chatId.isEmpty()) {
                    chatIds.add(chatId);
                }
            }
        }

        if (chatIds.isEmpty()) {
            log.warn("没有配置通知群聊，通知未发送: {}", message);
            return;
        }

        for (String chatId : chatIds) {
            try {
                feishuClient.sendText(chatId, message);
                log.info("通知已发送到群聊: {}", chatId);
            } catch (Exception e) {
                log.error("发送通知失败: chatId={}", chatId, e);
            }
        }
    }

    /**
     * 发送通知到指定群聊
     * @param chatId 群聊ID
     * @param message 通知内容
     */
    public void sendNotification(String chatId, String message) {
        try {
            feishuClient.sendText(chatId, message);
            log.info("通知已发送到群聊: {}", chatId);
        } catch (Exception e) {
            log.error("发送通知失败: chatId={}", chatId, e);
            throw new RuntimeException("发送通知失败", e);
        }
    }

    /**
     * 发送格式化通知（支持简单 Markdown）
     * @param message 通知内容
     */
    public void sendMarkdownNotification(String title, String content) {
        String markdownText = "# " + title + "\n\n" + content;
        sendNotification(markdownText);
    }
}
