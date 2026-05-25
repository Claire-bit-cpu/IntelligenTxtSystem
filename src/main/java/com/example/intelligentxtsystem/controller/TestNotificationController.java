package com.example.intelligentxtsystem.controller;

import com.example.intelligentxtsystem.service.NotificationConfigService;
import com.example.intelligentxtsystem.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 通知功能测试控制器
 * 用于测试各类通知消息（群聊配置必须通过飞书事件自动注册）
 *
 * 使用方法：
 * 1. 先在飞书群中发送消息，触发自动注册群聊（默认禁用）
 * 2. 通过 API 启用群聊：POST /api/notification/config/chats/{id}/enable
 * 3. 启动应用后，访问以下 URL 测试通知：
 *    - GET /test/notification/sync-success
 *    - GET /test/notification/sync-failure
 *    - GET /test/notification/exception-alert
 *    - GET /test/notification/doc-added
 *    - GET /test/notification/doc-modified
 *    - GET /test/notification/maintenance-start
 *    - GET /test/notification/maintenance-complete
 *    - GET /test/notification/todo-reminder
 *    - GET /test/notification/meeting-confirmation
 * 3. 查看配置的群聊：
 *    - GET /test/notification/config
 */
@RestController
@RequestMapping("/test/notify")
public class TestNotificationController {

    private static final Logger log = LoggerFactory.getLogger(TestNotificationController.class);

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationConfigService notificationConfigService;

    /**
     * 测试：同步成功通知
     */
    @GetMapping("/sync-success")
    public String testSyncSuccess() {
        try {
            notificationService.sendSyncSuccessNotification("全量同步", 100);
            return "✅ 同步成功通知已发送";
        } catch (Exception e) {
            log.error("测试失败", e);
            return "❌ 测试失败：" + e.getMessage();
        }
    }

    /**
     * 测试：同步失败通知
     */
    @GetMapping("/sync-failure")
    public String testSyncFailure() {
        try {
            notificationService.sendSyncFailureNotification("增量同步", "连接超时：Read timed out");
            return "✅ 同步失败通知已发送";
        } catch (Exception e) {
            log.error("测试失败", e);
            return "❌ 测试失败：" + e.getMessage();
        }
    }

    /**
     * 测试：异常预警通知
     */
    @GetMapping("/exception-alert")
    public String testExceptionAlert() {
        try {
            notificationService.sendExceptionAlert("API 限流", "飞书 API 返回 99991663 限流错误");
            return "✅ 异常预警通知已发送";
        } catch (Exception e) {
            log.error("测试失败", e);
            return "❌ 测试失败：" + e.getMessage();
        }
    }

    /**
     * 测试：文档新增提醒
     */
    @GetMapping("/doc-added")
    public String testDocumentAdded() {
        try {
            notificationService.sendDocumentAddedNotification(
                    "项目设计规范 v2.0",
                    "知识库文档",
                    "张三"
            );
            return "✅ 文档新增提醒已发送";
        } catch (Exception e) {
            log.error("测试失败", e);
            return "❌ 测试失败：" + e.getMessage();
        }
    }

    /**
     * 测试：文档修改提醒
     */
    @GetMapping("/doc-modified")
    public String testDocumentModified() {
        try {
            notificationService.sendDocumentModifiedNotification(
                    "API 接口文档",
                    "群文档",
                    "李四",
                    "更新了第 3 章：认证流程"
            );
            return "✅ 文档修改提醒已发送";
        } catch (Exception e) {
            log.error("测试失败", e);
            return "❌ 测试失败：" + e.getMessage();
        }
    }

    /**
     * 测试：系统维护开始通知
     */
    @GetMapping("/maintenance-start")
    public String testMaintenanceStart() {
        try {
            notificationService.sendMaintenanceStartNotification("索引重建", 30);
            return "✅ 系统维护开始通知已发送";
        } catch (Exception e) {
            log.error("测试失败", e);
            return "❌ 测试失败：" + e.getMessage();
        }
    }

    /**
     * 测试：系统维护完成通知
     */
    @GetMapping("/maintenance-complete")
    public String testMaintenanceComplete() {
        try {
            notificationService.sendMaintenanceCompleteNotification("索引重建", 25);
            return "✅ 系统维护完成通知已发送";
        } catch (Exception e) {
            log.error("测试失败", e);
            return "❌ 测试失败：" + e.getMessage();
        }
    }

    /**
     * 测试：待办提醒
     */
    @GetMapping("/todo-reminder")
    public String testTodoReminder() {
        try {
            notificationService.sendTodoReminder(
                    "完成项目需求文档",
                    "2024-01-20 18:00",
                    "王五"
            );
            return "✅ 待办提醒已发送";
        } catch (Exception e) {
            log.error("测试失败", e);
            return "❌ 测试失败：" + e.getMessage();
        }
    }

    /**
     * 测试：会议预约确认
     */
    @GetMapping("/meeting-confirmation")
    public String testMeetingConfirmation() {
        try {
            notificationService.sendMeetingConfirmation(
                    "每周项目进度汇报",
                    "2024-01-15 14:00-15:00",
                    "张三、李四、王五"
            );
            return "✅ 会议预约确认已发送";
        } catch (Exception e) {
            log.error("测试失败", e);
            return "❌ 测试失败：" + e.getMessage();
        }
    }

    /**
     * 查看当前配置的群聊
     */
    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        try {
            List<NotificationConfigService.ChatConfig> configs = notificationConfigService.getAllChatConfigs();
            List<NotificationConfigService.ChatConfig> enabledConfigs = notificationConfigService.getEnabledChatConfigs();
            return Map.of(
                    "allConfigs", configs,
                    "enabledConfigs", enabledConfigs,
                    "totalCount", configs.size(),
                    "enabledCount", enabledConfigs.size()
            );
        } catch (Exception e) {
            log.error("查询配置失败", e);
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * 启用群聊配置
     */
    @GetMapping("/enable-chat/{id}")
    public String enableChat(@PathVariable Long id) {
        try {
            boolean success = notificationConfigService.enableChatConfig(id);
            if (success) {
                return "✅ 群聊配置已启用：id=" + id;
            } else {
                return "❌ 群聊配置不存在：id=" + id;
            }
        } catch (Exception e) {
            log.error("启用群聊配置失败", e);
            return "❌ 启用失败：" + e.getMessage();
        }
    }

    /**
     * 禁用群聊配置
     */
    @GetMapping("/disable-chat/{id}")
    public String disableChat(@PathVariable Long id) {
        try {
            boolean success = notificationConfigService.disableChatConfig(id);
            if (success) {
                return "✅ 群聊配置已禁用：id=" + id;
            } else {
                return "❌ 群聊配置不存在：id=" + id;
            }
        } catch (Exception e) {
            log.error("禁用群聊配置失败", e);
            return "❌ 禁用失败：" + e.getMessage();
        }
    }

    /**
     * 删除群聊配置
     */
    @GetMapping("/delete-chat/{id}")
    public String deleteChat(@PathVariable Long id) {
        try {
            boolean success = notificationConfigService.deleteChatConfig(id);
            if (success) {
                return "✅ 群聊配置已删除：id=" + id;
            } else {
                return "❌ 群聊配置不存在：id=" + id;
            }
        } catch (Exception e) {
            log.error("删除群聊配置失败", e);
            return "❌ 删除失败：" + e.getMessage();
        }
    }
}
