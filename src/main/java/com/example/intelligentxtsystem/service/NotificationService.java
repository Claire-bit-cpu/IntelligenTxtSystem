package com.example.intelligentxtsystem.service;

import com.example.intelligentxtsystem.client.FeishuClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 消息广播服务
 * 用于发送各类通知消息到指定群聊
 *
 * 功能分类：
 * 1. 同步任务状态通知（同步成功、失败、异常预警）
 * 2. 内容更新与变更提醒（群文档、云文档、知识库）
 * 3. 系统维护通知
 * 4. 流程协作通知（待办提醒、会议预约确认）
 *
 * 群聊配置存储在数据库中，支持动态增删、按业务类型绑定
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    // 业务类型常量（与 NotificationConfigController.BusinessType 对应）
    public static class BusinessType {
        public static final String ALL = "all";                      // 所有通知
        public static final String SYNC_STATUS = "sync_status";      // 同步任务状态通知
        public static final String CONTENT_UPDATE = "content_update"; // 内容更新与变更提醒
        public static final String SYSTEM_MAINTENANCE = "system_maintenance"; // 系统维护通知
        public static final String WORKFLOW = "workflow";            // 流程协作通知
    }

    @Autowired
    private FeishuClient feishuClient;

    @Autowired
    private NotificationConfigService notificationConfigService;

    // ==================== 1. 同步任务状态通知 ====================

    /**
     * 发送同步成功通知（发送到配置了 sync_status 或 all 类型的启用群聊）
     * @param syncType 同步类型（如 "全量同步"、"增量同步"）
     * @param documentCount 同步的文档数量
     */
    public void sendSyncSuccessNotification(String syncType, int documentCount) {
        String content = String.format(
                "✅ **%s完成**\n\n" +
                "📊 同步统计：\n" +
                "- 类型：%s\n" +
                "- 文档数量：%d 个\n" +
                "- 状态：成功\n\n" +
                "⏰ 时间：%s",
                syncType, syncType, documentCount, getCurrentTime()
        );

        sendMarkdownMessage("同步成功通知", content, BusinessType.SYNC_STATUS);
        log.info("发送同步成功通知: syncType={}, documentCount={}", syncType, documentCount);
    }

    /**
     * 发送同步失败通知（发送到配置了 sync_status 或 all 类型的启用群聊）
     * @param syncType 同步类型
     * @param errorMessage 错误信息
     */
    public void sendSyncFailureNotification(String syncType, String errorMessage) {
        String content = String.format(
                "❌ **%s失败**\n\n" +
                "🔍 错误信息：\n" +
                "```\n%s\n```\n\n" +
                "⚠️ 建议检查：\n" +
                "- 网络连接是否正常\n" +
                "- 飞书 API 权限是否齐全\n" +
                "- 查看应用日志获取详细错误\n\n" +
                "⏰ 时间：%s",
                syncType, errorMessage, getCurrentTime()
        );

        sendMarkdownMessage("同步失败通知", content, BusinessType.SYNC_STATUS);
        log.warn("发送同步失败通知: syncType={}, error={}", syncType, errorMessage);
    }

    /**
     * 发送异常预警通知（发送到配置了 sync_status 或 all 类型的启用群聊）
     * @param exceptionType 异常类型（如 "API 限流"、"索引损坏"）
     * @param details 异常详情
     */
    public void sendExceptionAlert(String exceptionType, String details) {
        String content = String.format(
                "🚨 **异常预警**\n\n" +
                "🔴 异常类型：%s\n" +
                "📝 详情：\n" +
                "```\n%s\n```\n\n" +
                "🔧 建议操作：\n" +
                "- 查看应用日志\n" +
                "- 联系开发者处理\n" +
                "- 必要时重启应用\n\n" +
                "⏰ 时间：%s",
                exceptionType, details, getCurrentTime()
        );

        sendMarkdownMessage("异常预警通知", content, BusinessType.SYNC_STATUS);
        log.error("发送异常预警: exceptionType={}, details={}", exceptionType, details);
    }

    // ==================== 2. 内容更新与变更提醒 ====================

    /**
     * 发送文档新增提醒（发送到配置了 content_update 或 all 类型的启用群聊）
     * @param documentName 文档名称
     * @param documentType 文档类型（如 "知识库文档"、"群文档"、"云文档"）
     * @param operator 操作人
     */
    public void sendDocumentAddedNotification(String documentName, String documentType, String operator) {
        String content = String.format(
                "📄 **新文档已添加**\n\n" +
                "📚 文档名称：%s\n" +
                "🏷️ 文档类型：%s\n" +
                "👤 操作人：%s\n" +
                "⏰ 时间：%s\n\n" +
                "💡 提示：可使用 `/search %s` 搜索该文档",
                documentName, documentType, operator, getCurrentTime(), documentName
        );

        sendMarkdownMessage("文档新增提醒", content, BusinessType.CONTENT_UPDATE);
        log.info("发送文档新增提醒: documentName={}, documentType={}", documentName, documentType);
    }

    /**
     * 发送文档修改提醒（发送到配置了 content_update 或 all 类型的启用群聊）
     * @param documentName 文档名称
     * @param documentType 文档类型
     * @param operator 操作人
     * @param changes 修改内容摘要
     */
    public void sendDocumentModifiedNotification(String documentName, String documentType, String operator, String changes) {
        String content = String.format(
                "✏️ **文档已修改**\n\n" +
                "📚 文档名称：%s\n" +
                "🏷️ 文档类型：%s\n" +
                "👤 操作人：%s\n" +
                "📝 修改摘要：%s\n" +
                "⏰ 时间：%s\n\n" +
                "💡 提示：可使用 `/search %s` 查看最新内容",
                documentName, documentType, operator, changes, getCurrentTime(), documentName
        );

        sendMarkdownMessage("文档修改提醒", content, BusinessType.CONTENT_UPDATE);
        log.info("发送文档修改提醒: documentName={}, documentType={}", documentName, documentType);
    }

    // ==================== 3. 系统维护通知 ====================

    /**
     * 发送系统维护开始通知（发送到配置了 system_maintenance 或 all 类型的启用群聊）
     * @param maintenanceType 维护类型（如 "索引重建"、"版本更新"）
     * @param estimatedDuration 预计耗时（分钟）
     */
    public void sendMaintenanceStartNotification(String maintenanceType, int estimatedDuration) {
        String content = String.format(
                "🔧 **系统维护开始**\n\n" +
                "🔩 维护类型：%s\n" +
                "⏱️ 预计耗时：%d 分钟\n" +
                "⏰ 开始时间：%s\n\n" +
                "📢 注意事项：\n" +
                "- 维护期间部分功能可能不可用\n" +
                "- 维护完成后会发送通知\n" +
                "- 如有紧急问题，请联系管理员",
                maintenanceType, estimatedDuration, getCurrentTime()
        );

        sendMarkdownMessage("系统维护通知", content, BusinessType.SYSTEM_MAINTENANCE);
        log.info("发送系统维护开始通知: maintenanceType={}, estimatedDuration={}", maintenanceType, estimatedDuration);
    }

    /**
     * 发送系统维护完成通知（发送到配置了 system_maintenance 或 all 类型的启用群聊）
     * @param maintenanceType 维护类型
     * @param actualDuration 实际耗时（分钟）
     */
    public void sendMaintenanceCompleteNotification(String maintenanceType, int actualDuration) {
        String content = String.format(
                "✅ **系统维护完成**\n\n" +
                "🔩 维护类型：%s\n" +
                "⏱️ 实际耗时：%d 分钟\n" +
                "⏰ 完成时间：%s\n\n" +
                "📢 提示：\n" +
                "- 所有功能已恢复正常\n" +
                "- 如有问题，请随时反馈",
                maintenanceType, actualDuration, getCurrentTime()
        );

        sendMarkdownMessage("系统维护完成通知", content, BusinessType.SYSTEM_MAINTENANCE);
        log.info("发送系统维护完成通知: maintenanceType={}, actualDuration={}", maintenanceType, actualDuration);
    }

    // ==================== 4. 流程协作通知 ====================

    /**
     * 发送待办提醒（发送到配置了 workflow 或 all 类型的启用群聊）
     * @param todoTitle 待办标题
     * @param dueTime 截止时间
     * @param assignee 负责人
     */
    public void sendTodoReminder(String todoTitle, String dueTime, String assignee) {
        String content = String.format(
                "📋 **待办提醒**\n\n" +
                "📝 待办事项：%s\n" +
                "👤 负责人：%s\n" +
                "⏰ 截止时间：%s\n\n" +
                "💡 提示：请及时处理待办事项",
                todoTitle, assignee, dueTime
        );

        sendMarkdownMessage("待办提醒", content, BusinessType.WORKFLOW);
        log.info("发送待办提醒: todoTitle={}, assignee={}", todoTitle, assignee);
    }

    /**
     * 发送会议预约确认（发送到配置了 workflow 或 all 类型的启用群聊）
     * @param meetingTitle 会议标题
     * @param meetingTime 会议时间
     * @param attendees 参会人员
     */
    public void sendMeetingConfirmation(String meetingTitle, String meetingTime, String attendees) {
        String content = String.format(
                "📅 **会议预约确认**\n\n" +
                "📝 会议主题：%s\n" +
                "⏰ 会议时间：%s\n" +
                "👥 参会人员：%s\n\n" +
                "✅ 会议已成功预约，请准时参加",
                meetingTitle, meetingTime, attendees
        );

        sendMarkdownMessage("会议预约确认", content, BusinessType.WORKFLOW);
        log.info("发送会议预约确认: meetingTitle={}, meetingTime={}", meetingTitle, meetingTime);
    }

    // ==================== 通用方法 ====================

    /**
     * 发送 Markdown 格式的消息到配置了指定业务类型的启用群聊
     * @param title 消息标题
     * @param content Markdown 内容
     * @param businessType 业务类型
     */
    private void sendMarkdownMessage(String title, String content, String businessType) {
        List<String> targetChatIds = notificationConfigService.getEnabledChatIds(businessType);

        if (targetChatIds.isEmpty()) {
            log.warn("未配置通知目标群聊，请在数据库中配置 notification_chat_config 表");
            return;
        }

        for (String chatId : targetChatIds) {
            try {
                // 构建 Markdown 消息体（post 类型支持 Markdown）
                Map<String, Object> message = new java.util.LinkedHashMap<>();
                message.put("receive_id", chatId);

                // 将 content 字符串转换为飞书 post 类型要求的二维数组格式
                java.util.List<Object> contentArray = new java.util.ArrayList<>();
                String[] lines = content.split("\n");
                for (String line : lines) {
                    java.util.List<Object> lineArray = new java.util.ArrayList<>();
                    Map<String, Object> textObj = new java.util.LinkedHashMap<>();
                    textObj.put("tag", "text");
                    textObj.put("text", line);
                    lineArray.add(textObj);
                    contentArray.add(lineArray);
                }

                Map<String, Object> zhCn = new java.util.LinkedHashMap<>();
                zhCn.put("title", title);
                zhCn.put("content", contentArray);

                Map<String, Object> contentMap = new java.util.LinkedHashMap<>();
                contentMap.put("zh_cn", zhCn);

                message.put("content", com.example.intelligentxtsystem.client.FeishuClient.mapToJson(contentMap));
                message.put("msg_type", "post");

                // 发送消息
                feishuClient.sendMessage(message);
                log.info("发送通知成功: chatId={}, title={}", chatId, title);
            } catch (Exception e) {
                log.error("发送 Markdown 消息失败: chatId={}, title={}", chatId, title, e);
            }
        }
    }

    /**
     * 获取当前时间字符串
     */
    private String getCurrentTime() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
