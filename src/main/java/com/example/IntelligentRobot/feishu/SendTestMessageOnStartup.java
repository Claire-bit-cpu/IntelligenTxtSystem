/*
 * 应用完全启动后，发送一条测试消息到配置的群聊
 * 使用 ApplicationReadyEvent 确保在应用完全就绪后执行
 */

package com.example.IntelligentRobot.feishu;

import com.example.IntelligentRobot.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class SendTestMessageOnStartup {

    private static final Logger log = LoggerFactory.getLogger(SendTestMessageOnStartup.class);

    @Autowired
    private NotificationService notificationService;

    /**
     * 默认通知群聊 ID（从配置文件读取）
     */
    @Value("${notification.default-chat-ids:}")
    private String defaultChatIds;

    /**
     * 监控群聊 ID（从配置文件读取）
     */
    @Value("${task.monitor.chat-id:}")
    private String monitorChatId;

    /**
     * 是否启用启动测试消息（默认 true）
     */
    @Value("${feishu.send-test-on-startup:true}")
    private boolean sendTestOnStartup;

    /**
     * 使用 ApplicationReadyEvent 确保在应用完全启动后执行
     * 此时所有 Bean 已初始化完成，配置已加载
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!sendTestOnStartup) {
            log.info("启动测试消息已禁用");
            return;
        }

        try {
            log.info("应用启动完成，发送测试消息...");
            
            // 从配置中读取默认群聊 ID
            String chatId = getDefaultChatId();
            if (chatId == null || chatId.isEmpty()) {
                log.warn("未配置默认通知群聊 ID，跳过启动测试消息");
                return;
            }
            
            // 使用正确的方法签名：sendNotification(chatId, eventType, content)
            boolean success = notificationService.sendNotification(chatId, "SYSTEM", "✅ Spring Boot 启动成功，飞书机器人已上线！");
            if (success) {
                log.info("启动测试消息发送成功: chatId={}", maskChatId(chatId));
            } else {
                log.warn("启动测试消息发送失败: chatId={}", maskChatId(chatId));
            }
        } catch (Exception e) {
            log.error("发送启动测试消息失败", e);
        }
    }

    /**
     * 获取默认群聊 ID
     * 优先使用监控群，其次使用默认通知群
     */
    private String getDefaultChatId() {
        // 优先使用 task.monitor.chat-id（监控群）
        if (monitorChatId != null && !monitorChatId.isEmpty()) {
            return monitorChatId;
        }
        
        // 其次使用 notification.default-chat-ids（默认通知群）
        if (defaultChatIds != null && !defaultChatIds.isEmpty()) {
            // 取第一个群聊 ID
            String[] chatIds = defaultChatIds.split(",");
            return chatIds[0].trim();
        }
        
        return null;
    }

    /**
     * 脱敏 chatId（日志打印用）
     */
    private String maskChatId(String chatId) {
        if (chatId == null || chatId.length() < 8) return "***";
        return chatId.substring(0, 4) + "***" + chatId.substring(chatId.length() - 4);
    }
}
