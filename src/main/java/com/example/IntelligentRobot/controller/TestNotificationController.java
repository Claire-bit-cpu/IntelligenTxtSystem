package com.example.IntelligentRobot.controller;

import com.example.IntelligentRobot.service.NotificationConfigService;
import com.example.IntelligentRobot.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 *    - GET /test/notify/sync-success
 *    - GET /test/notify/sync-failure
 *    - GET /test/notify/exception-alert
 *    - GET /test/notify/doc-added
 *    - GET /test/notify/doc-modified
 *    - GET /test/notify/maintenance-start
 *    - GET /test/notify/maintenance-complete
 *    - GET /test/notify/todo-reminder
 *    - GET /test/notify/meeting-confirmation
 * 4. 查看配置的群聊：
 *    - GET /test/notify/config
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
     * 默认通知群聊 ID（从配置文件读取）
     */
    @Value("${notification.default-chat-ids:}")
    private String defaultChatIds;

    /**
     * 获取默认群聊 ID
     */
    private String getDefaultChatId() {
        if (defaultChatIds == null || defaultChatIds.isEmpty()) {
            return null;
        }
        // 取第一个群聊 ID
        String[] chatIds = defaultChatIds.split(",");
        return chatIds[0].trim();
    }

    /**
     * 测试：同步成功通知
     */
    @GetMapping("/sync-success")
    public String testSyncSuccess() {
        try {
            String chatId = getDefaultChatId();
            if (chatId == null) {
                return "❌ 未配置默认通知群聊 ID";
            }
            
            String content = "✅ **同步成功通知**\n\n" +
                    "**任务：** 全量同步\n" +
                    "**耗时：** 100ms\n" +
                    "**状态：** 成功";
            
            notificationService.sendNotification(chatId, "SYNC", content);
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
            String chatId = getDefaultChatId();
            if (chatId == null) {
                return "❌ 未配置默认通知群聊 ID";
            }
            
            String content = "❌ **同步失败通知**\n\n" +
                    "**任务：** 增量同步\n" +
                    "**错误：** 连接超时：Read timed out\n" +
                    "**建议：** 检查网络连接后重试";
            
            notificationService.sendNotification(chatId, "SYNC", content);
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
            String chatId = getDefaultChatId();
            if (chatId == null) {
                return "❌ 未配置默认通知群聊 ID";
            }
            
            String content = "⚠️ **异常预警通知**\n\n" +
                    "**类型：** API 限流\n" +
                    "**详情：** 飞书 API 返回 99991663 限流错误\n" +
                    "**建议：** 降低请求频率或申请更高配额";
            
            notificationService.sendNotification(chatId, "ALERT", content);
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
            String chatId = getDefaultChatId();
            if (chatId == null) {
                return "❌ 未配置默认通知群聊 ID";
            }
            
            String content = "📄 **文档新增提醒**\n\n" +
                    "**文档：** 项目设计规范 v2.0\n" +
                    "**位置：** 知识库文档\n" +
                    "**操作人：** 张三";
            
            notificationService.sendNotification(chatId, "DOC", content);
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
            String chatId = getDefaultChatId();
            if (chatId == null) {
                return "❌ 未配置默认通知群聊 ID";
            }
            
            String content = "📝 **文档修改提醒**\n\n" +
                    "**文档：** API 接口文档\n" +
                    "**位置：** 群文档\n" +
                    "**操作人：** 李四\n" +
                    "**修改内容：** 更新了第 3 章：认证流程";
            
            notificationService.sendNotification(chatId, "DOC", content);
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
            String chatId = getDefaultChatId();
            if (chatId == null) {
                return "❌ 未配置默认通知群聊 ID";
            }
            
            String content = "🔧 **系统维护开始通知**\n\n" +
                    "**任务：** 索引重建\n" +
                    "**预计耗时：** 30 分钟\n" +
                    "**开始时间：** " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n\n" +
                    "⚠️ 维护期间部分功能可能不可用";
            
            notificationService.sendNotification(chatId, "MAINT", content);
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
            String chatId = getDefaultChatId();
            if (chatId == null) {
                return "❌ 未配置默认通知群聊 ID";
            }
            
            String content = "✅ **系统维护完成通知**\n\n" +
                    "**任务：** 索引重建\n" +
                    "**实际耗时：** 25 分钟\n" +
                    "**完成时间：** " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n\n" +
                    "🎉 所有功能已恢复正常";
            
            notificationService.sendNotification(chatId, "MAINT", content);
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
            String chatId = getDefaultChatId();
            if (chatId == null) {
                return "❌ 未配置默认通知群聊 ID";
            }
            
            String content = "📋 **待办提醒**\n\n" +
                    "**任务：** 完成项目需求文档\n" +
                    "**截止时间：** 2024-01-20 18:00\n" +
                    "**负责人：** 王五\n\n" +
                    "⏰ 请尽快处理！";
            
            notificationService.sendNotification(chatId, "TODO", content);
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
            String chatId = getDefaultChatId();
            if (chatId == null) {
                return "❌ 未配置默认通知群聊 ID";
            }
            
            String content = "📅 **会议预约确认**\n\n" +
                    "**会议主题：** 每周项目进度汇报\n" +
                    "**时间：** 2024-01-15 14:00-15:00\n" +
                    "**参会人：** 张三、李四、王五\n\n" +
                    "✅ 请准时参加！";
            
            notificationService.sendNotification(chatId, "MEETING", content);
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
