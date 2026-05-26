/*
 * 应用完全启动后，发送一条测试消息到配置的群聊
 * 使用 ApplicationReadyEvent 确保在应用完全就绪后执行
 */

package com.example.intelligentxtsystem.feishu;

import com.example.intelligentxtsystem.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class SendTestMessageOnStartup {

    private static final Logger log = LoggerFactory.getLogger(SendTestMessageOnStartup.class);

    @Autowired
    private NotificationService notificationService;

    /**
     * 使用 ApplicationReadyEvent 确保在应用完全启动后执行
     * 此时所有 Bean 已初始化完成，配置已加载
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            log.info("应用启动完成，发送测试消息...");
            notificationService.sendNotification("✅ Spring Boot 启动成功，飞书机器人已上线！");
            log.info("启动测试消息发送成功");
        } catch (Exception e) {
            log.error("发送启动测试消息失败", e);
        }
    }
}
