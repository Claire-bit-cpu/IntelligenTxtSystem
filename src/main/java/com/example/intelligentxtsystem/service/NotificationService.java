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

    /**
     * 发送同步成功通知
     */
    public void sendSyncSuccessNotification(String syncType, int docCount) {
        String msg = String.format("""
                ✅ 索引同步成功

                📊 同步类型：%s
                📄 索引文档数：%d
                🕐 时间：%s
                """, syncType, docCount, java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        sendNotification(msg);
    }

    /**
     * 发送同步失败通知
     */
    public void sendSyncFailureNotification(String syncType, String errorMsg) {
        String msg = String.format("""
                ❌ 索引同步失败

                📊 同步类型：%s
                ⚠️ 错误信息：%s
                🕐 时间：%s
                """, syncType, errorMsg, java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        sendNotification(msg);
    }

    /**
     * 发送异常预警通知
     */
    public void sendExceptionAlert(String title, String detail) {
        String msg = String.format("""
                ⚠️ 系统异常预警

                📋 异常类型：%s
                📄 详情：%s
                🕐 时间：%s
                """, title, detail, java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        sendNotification(msg);
    }

    /**
     * 发送文档新增提醒
     */
    public void sendDocumentAddedNotification(String docTitle, String docSource, String operator) {
        String msg = String.format("""
                📄 文档新增提醒

                📋 文档标题：%s
                📁 来源：%s
                👤 操作人：%s
                🕐 时间：%s
                """, docTitle, docSource, operator, java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        sendNotification(msg);
    }

    /**
     * 发送文档修改提醒
     */
    public void sendDocumentModifiedNotification(String docTitle, String docSource, String operator, String changeDesc) {
        String msg = String.format("""
                📝 文档修改提醒

                📋 文档标题：%s
                📁 来源：%s
                👤 操作人：%s
                📄 变更描述：%s
                🕐 时间：%s
                """, docTitle, docSource, operator, changeDesc,
                java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        sendNotification(msg);
    }

    /**
     * 发送系统维护开始通知
     */
    public void sendMaintenanceStartNotification(String maintenanceType, int estimatedMinutes) {
        String msg = String.format("""
                🔧 系统维护开始

                📋 维护类型：%s
                ⏱️ 预计时长：%d 分钟
                🕐 开始时间：%s
                """, maintenanceType, estimatedMinutes, java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        sendNotification(msg);
    }

    /**
     * 发送系统维护完成通知
     */
    public void sendMaintenanceCompleteNotification(String maintenanceType, int actualMinutes) {
        String msg = String.format("""
                ✅ 系统维护完成

                📋 维护类型：%s
                ⏱️ 实际时长：%d 分钟
                🕐 完成时间：%s
                """, maintenanceType, actualMinutes, java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        sendNotification(msg);
    }

    /**
     * 发送待办提醒
     */
    public void sendTodoReminder(String todoTitle, String deadline, String assignee) {
        String msg = String.format("""
                📌 待办提醒

                📋 待办事项：%s
                ⏰ 截止时间：%s
                👤 负责人：%s
                """, todoTitle, deadline, assignee);
        sendNotification(msg);
    }

    /**
     * 发送会议预约确认
     */
    public void sendMeetingConfirmation(String meetingTitle, String timeRange, String attendees) {
        String msg = String.format("""
                📅 会议预约确认

                📋 会议主题：%s
                ⏰ 会议时间：%s
                👥 参会人员：%s
                """, meetingTitle, timeRange, attendees);
        sendNotification(msg);
    }
}
