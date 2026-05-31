package com.example.IntelligentRobot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 统一通知服务（集成去重 + 合并）
 * 
 * 功能：
 * 1. 消息去重（调用 MessageDedupService）
 * 2. 消息合并（调用 MessageBatchService）
 * 3. 统一推送入口（所有 DevOps 通知都通过此服务发送）
 * 
 * 使用方式：
 * - 在 JenkinsClient、JiraClient、MonitorClient 等地方，
 *   用 @Autowired NotificationService 替换直接调用 FeishuClient.sendText()
 * 
 * 降噪流程：
 *   消息来源 → 去重检查 → 合并检查 → 推送 / 暂存
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    @Autowired(required = false)
    private MessageDedupService messageDedupService;

    @Autowired(required = false)
    private MessageBatchService messageBatchService;

    @Autowired(required = false)
    private com.example.IntelligentRobot.client.FeishuClient feishuClient;

    /**
     * 是否启用智能降噪（去重 + 合并），默认 true
     */
    @Value("${notification.noise-reduction-enabled:true}")
    private boolean noiseReductionEnabled;

    /**
     * 监控群聊 ID（用于任务监控面板）
     */
    @Value("${task.monitor.chat-id:}")
    private String monitorChatId;

    /**
     * 默认通知群聊 ID 列表（逗号分隔）
     */
    @Value("${notification.default-chat-ids:}")
    private String defaultChatIds;

    /**
     * 发送通知（带智能降噪）
     * 
     * @param chatId    群聊 ID
     * @param eventType 事件类型（BUILD、DEPLOY、ALERT、JIRA 等）
     * @param content   消息内容
     * @return 是否成功推送（true=已推送，false=被降噪拦截或推送失败）
     */
    public boolean sendNotification(String chatId, String eventType, String content) {
        if (chatId == null || content == null || content.isEmpty()) {
            log.warn("通知参数无效: chatId={}, eventType={}", chatId, eventType);
            return false;
        }

        // ===== 第一步：消息去重 =====
        if (noiseReductionEnabled && messageDedupService != null) {
            boolean isDup = messageDedupService.isDuplicate(chatId, eventType, content);
            if (isDup) {
                log.info("消息已被去重拦截，未推送: chatId={}, eventType={}", 
                        maskChatId(chatId), eventType);
                return false; // 重复消息，拦截
            }
        }

        // ===== 第二步：消息合并 =====
        if (noiseReductionEnabled && messageBatchService != null) {
            String batchSummary = messageBatchService.addToBatch(chatId, eventType, content);
            if (batchSummary != null) {
                // 达到合并阈值，推送合并摘要
                log.info("合并阈值达到，推送合并摘要: chatId={}, eventType={}", 
                        maskChatId(chatId), eventType);
                return doSend(chatId, batchSummary);
            } else {
                // 未达阈值，消息已加入合并队列，暂不推送
                log.debug("消息已加入合并队列，暂不推送: chatId={}, eventType={}", 
                        maskChatId(chatId), eventType);
                return true; // 加入队列也算"成功"
            }
        }

        // ===== 第三步：直接推送（未启用合并或合并服务不可用）=====
        return doSend(chatId, content);
    }

    /**
     * 发送通知（强制立即推送，跳过合并队列）
     * 用于紧急告警（P0 级别）
     */
    public boolean sendUrgentNotification(String chatId, String eventType, String content) {
        if (chatId == null || content == null || content.isEmpty()) {
            return false;
        }

        // 紧急通知也做去重，但不做合并
        if (noiseReductionEnabled && messageDedupService != null) {
            boolean isDup = messageDedupService.isDuplicate(chatId, eventType, content);
            if (isDup) {
                log.info("紧急通知已被去重拦截: chatId={}, eventType={}", 
                        maskChatId(chatId), eventType);
                return false;
            }
        }

        return doSend(chatId, content);
    }

    /**
     * 发送通知（简化版，使用默认 chatId）
     * 从配置中读取默认群聊 ID
     * 
     * @param content 消息内容
     * @return 是否成功推送
     */
    public boolean sendNotification(String content) {
        // 使用已注入的 monitorChatId 或 defaultChatIds
        String defaultChatId = getDefaultChatId();
        if (defaultChatId == null || defaultChatId.isEmpty()) {
            log.warn("未配置默认通知群聊 ID，无法发送通知");
            return false;
        }
        
        // 使用 "SYSTEM" 作为默认事件类型
        return sendNotification(defaultChatId, "SYSTEM", content);
    }

    /**
     * 获取默认群聊 ID
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
     * 底层发送方法
     */
    private boolean doSend(String chatId, String content) {
        if (feishuClient == null) {
            log.error("FeishuClient 未注入，无法发送通知");
            return false;
        }

        try {
            String messageId = feishuClient.sendText(chatId, content);
            if (messageId != null) {
                log.info("通知推送成功: chatId={}, messageId={}", maskChatId(chatId), messageId);
                return true;
            } else {
                log.warn("通知推送失败: chatId={}", maskChatId(chatId));
                return false;
            }
        } catch (Exception e) {
            log.error("通知推送异常: chatId={}", maskChatId(chatId), e);
            return false;
        }
    }

    /**
     * 脱敏 chatId（日志打印用）
     */
    private String maskChatId(String chatId) {
        if (chatId == null || chatId.length() < 8) return "***";
        return chatId.substring(0, 4) + "***" + chatId.substring(chatId.length() - 4);
    }
}
