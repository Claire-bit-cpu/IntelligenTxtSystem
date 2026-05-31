package com.example.IntelligentRobot.controller;

import com.example.IntelligentRobot.service.NotificationConfigService;
import com.example.IntelligentRobot.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 通知配置管理接口
 * 用于管理接收通知的群聊列表
 */
@RestController
@RequestMapping("/test/notification")
public class NotificationConfigController {

    private static final Logger log = LoggerFactory.getLogger(NotificationConfigController.class);

    @Autowired
    private NotificationConfigService notificationConfigService;

    @Autowired
    private NotificationService notificationService;

    /**
     * 获取所有通知配置
     * GET /test/notification/config
     */
    @GetMapping("/config")
    public Object getAllConfigs() {
        try {
            List<NotificationConfigService.NotificationConfig> configs = notificationConfigService.getAllConfigs();
            return Map.of("code", 0, "msg", "ok", "data", configs);
        } catch (Exception e) {
            log.error("获取通知配置失败", e);
            return Map.of("code", 500, "msg", "获取失败: " + e.getMessage());
        }
    }

    /**
     * 添加通知群聊
     * POST /test/notification/config
     * Body: {"chatId": "oc_xxx", "chatName": "群名", "description": "描述"}
     */
    @PostMapping("/config")
    public Object addConfig(@RequestBody Map<String, String> body) {
        try {
            String chatId = body.get("chatId");
            String chatName = body.getOrDefault("chatName", "");
            String description = body.getOrDefault("description", "");

            if (chatId == null || chatId.isEmpty()) {
                return Map.of("code", 400, "msg", "chatId 不能为空");
            }

            notificationConfigService.addChat(chatId, chatName, description);
            return Map.of("code", 0, "msg", "添加成功");
        } catch (Exception e) {
            log.error("添加通知配置失败", e);
            return Map.of("code", 500, "msg", "添加失败: " + e.getMessage());
        }
    }

    /**
     * 移除通知群聊
     * DELETE /test/notification/config?chatId=oc_xxx
     */
    @DeleteMapping("/config")
    public Object removeConfig(@RequestParam String chatId) {
        try {
            if (chatId == null || chatId.isEmpty()) {
                return Map.of("code", 400, "msg", "chatId 不能为空");
            }

            notificationConfigService.removeChat(chatId);
            return Map.of("code", 0, "msg", "移除成功");
        } catch (Exception e) {
            log.error("移除通知配置失败", e);
            return Map.of("code", 500, "msg", "移除失败: " + e.getMessage());
        }
    }

    /**
     * 启用/禁用通知群聊
     * PUT /test/notification/config/enable
     * Body: {"chatId": "oc_xxx", "enabled": true}
     */
    @PutMapping("/config/enable")
    public Object setEnabled(@RequestBody Map<String, Object> body) {
        try {
            String chatId = (String) body.get("chatId");
            Boolean enabled = (Boolean) body.get("enabled");

            if (chatId == null || chatId.isEmpty()) {
                return Map.of("code", 400, "msg", "chatId 不能为空");
            }

            notificationConfigService.setEnabled(chatId, enabled != null && enabled);
            return Map.of("code", 0, "msg", "设置成功");
        } catch (Exception e) {
            log.error("设置通知配置状态失败", e);
            return Map.of("code", 500, "msg", "设置失败: " + e.getMessage());
        }
    }

    /**
     * 获取启用的通知群聊ID列表
     * GET /test/notification/config/enabled
     */
    @GetMapping("/config/enabled")
    public Object getEnabledChatIds() {
        try {
            List<String> chatIds = notificationConfigService.getEnabledChatIds();
            return Map.of("code", 0, "msg", "ok", "data", chatIds);
        } catch (Exception e) {
            log.error("获取启用的通知群聊失败", e);
            return Map.of("code", 500, "msg", "获取失败: " + e.getMessage());
        }
    }

    /**
     * 测试发送通知
     * POST /test/notification/test
     * Body: {"message": "测试消息"}
     */
    @PostMapping("/test")
    public Object testNotification(@RequestBody Map<String, String> body) {
        try {
            String message = body.get("message");
            if (message == null || message.isEmpty()) {
                message = "这是一条测试通知";
            }

            notificationService.sendNotification(message);
            return Map.of("code", 0, "msg", "测试通知已发送");
        } catch (Exception e) {
            log.error("测试通知失败", e);
            return Map.of("code", 500, "msg", "测试失败: " + e.getMessage());
        }
    }

    /**
     * 发送同步成功通知
     * GET/POST /test/notification/sync-success?detail=xxx
     */
    @GetMapping("/sync-success")
    @PostMapping("/sync-success")
    public Object syncSuccess(@RequestParam(required = false) String detail) {
        return sendNotificationWithDefaults("✅ 搜索索引同步完成", detail, "同步任务已成功执行。");
    }

    /**
     * 发送同步失败通知
     * GET/POST /test/notification/sync-failure?detail=xxx
     */
    @GetMapping("/sync-failure")
    @PostMapping("/sync-failure")
    public Object syncFailure(@RequestParam(required = false) String detail) {
        return sendNotificationWithDefaults("❌ 搜索索引同步失败", detail, "同步任务执行失败，请检查日志。");
    }

    /**
     * 发送异常预警通知
     * GET/POST /test/notification/exception-alert?detail=xxx
     */
    @GetMapping("/exception-alert")
    @PostMapping("/exception-alert")
    public Object exceptionAlert(@RequestParam(required = false) String detail) {
        return sendNotificationWithDefaults("⚠️ 系统异常预警", detail, "系统检测到异常情况，请及时排查。");
    }

    /**
     * 发送文档新增提醒
     * GET/POST /test/notification/doc-added?detail=xxx
     */
    @GetMapping("/doc-added")
    @PostMapping("/doc-added")
    public Object docAdded(@RequestParam(required = false) String detail) {
        return sendNotificationWithDefaults("📄 文档新增提醒", detail, "有新的文档已添加到知识库。");
    }

    /**
     * 发送文档修改提醒
     * GET/POST /test/notification/doc-modified?detail=xxx
     */
    @GetMapping("/doc-modified")
    @PostMapping("/doc-modified")
    public Object docModified(@RequestParam(required = false) String detail) {
        return sendNotificationWithDefaults("✏️ 文档修改提醒", detail, "知识库中有文档已被修改。");
    }

    /**
     * 发送维护开始通知
     * GET/POST /test/notification/maintenance-start?detail=xxx
     */
    @GetMapping("/maintenance-start")
    @PostMapping("/maintenance-start")
    public Object maintenanceStart(@RequestParam(required = false) String detail) {
        return sendNotificationWithDefaults("🛠️ 系统维护开始", detail, "系统维护已开始，部分功能可能暂时不可用。");
    }

    /**
     * 发送维护完成通知
     * GET/POST /test/notification/maintenance-complete?detail=xxx
     */
    @GetMapping("/maintenance-complete")
    @PostMapping("/maintenance-complete")
    public Object maintenanceComplete(@RequestParam(required = false) String detail) {
        return sendNotificationWithDefaults("✅ 系统维护完成", detail, "系统维护已结束，所有功能恢复正常。");
    }

    /**
     * 发送待办提醒
     * GET/POST /test/notification/todo-reminder?detail=xxx
     */
    @GetMapping("/todo-reminder")
    @PostMapping("/todo-reminder")
    public Object todoReminder(@RequestParam(required = false) String detail) {
        return sendNotificationWithDefaults("📋 待办事项提醒", detail, "您有待办事项需要处理。");
    }

    /**
     * 发送会议预约确认
     * GET/POST /test/notification/meeting-confirmation?detail=xxx
     */
    @GetMapping("/meeting-confirmation")
    @PostMapping("/meeting-confirmation")
    public Object meetingConfirmation(@RequestParam(required = false) String detail) {
        return sendNotificationWithDefaults("📅 会议预约确认", detail, "会议已预约，请准时参加。");
    }

    /**
     * 通用通知发送方法
     */
    private Object sendNotificationWithDefaults(String title, String detail, String defaultDetail) {
        try {
            String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String message = title + "\n" +
                    "⏰ 时间: " + timestamp + "\n" +
                    "📝 详情: " + (detail != null ? detail : defaultDetail);

            notificationService.sendNotification(message);
            return Map.of("code", 0, "msg", "通知已发送");
        } catch (Exception e) {
            log.error("发送通知失败: {}", title, e);
            return Map.of("code", 500, "msg", "发送失败: " + e.getMessage());
        }
    }
}
