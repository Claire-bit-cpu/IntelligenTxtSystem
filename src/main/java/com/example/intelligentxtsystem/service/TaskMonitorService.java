package com.example.intelligentxtsystem.service;

import com.example.intelligentxtsystem.client.FeishuClient;
import com.example.intelligentxtsystem.task.AsyncTaskStatus;
import com.example.intelligentxtsystem.task.TaskStatusService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 任务监控服务：定时推送任务状态到飞书群
 * 通过飞书文本消息展示任务列表和进度，支持原地更新
 * 
 * 设计考虑：
 * 1. 飞书消息有编辑次数限制（约100次），按10秒间隔，约16分钟后会触发限制
 * 2. 当更新失败时，发送新消息并重置计数
 * 3. 连续更新失败时，增加延迟避免刷屏
 */
@Service
public class TaskMonitorService {

    private static final Logger log = LoggerFactory.getLogger(TaskMonitorService.class);

    @Value("${task.monitor.chat-id:}")
    private String monitorChatId;

    private final FeishuClient feishuClient;
    private final TaskStatusService taskStatusService;

    // 上次推送的 messageId，用于原地更新（避免刷屏）
    private String lastMonitorMessageId = null;
    
    // 连续更新失败次数（用于退避策略）
    private int consecutiveUpdateFailures = 0;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    
    // 消息编辑次数计数（近似，用于主动刷新消息）
    private int updateCount = 0;
    private static final int MAX_UPDATE_COUNT = 80; // 低于100，提前刷新
    
    // 上次推送的内容缓存（用于状态变化检测）
    private String lastPushedContent = null;
    
    // 强制推送标志（首次启动或手动刷新时强制推送）
    private boolean forcePush = true;

    public TaskMonitorService(FeishuClient feishuClient,
                             TaskStatusService taskStatusService) {
        this.feishuClient = feishuClient;
        this.taskStatusService = taskStatusService;
    }

    /**
     * 应用启动时清除旧的 messageId 缓存
     * 防止重启后尝试更新不存在/类型不匹配的旧消息
     */
    @jakarta.annotation.PostConstruct
    public void resetMessageId() {
        this.lastMonitorMessageId = null;
        this.consecutiveUpdateFailures = 0;
        this.updateCount = 0;
        log.info("已重置监控消息ID，下次将发送新消息");
    }

    /**
     * 手动触发推送任务监控消息到飞书群
     * 改为手动触发模式，不再自动定时推送
     * 
     * 优化策略：
     * 1. 状态变化检测：仅当任务状态变化时才推送
     * 2. 当消息编辑次数接近上限时，主动发送新消息
     * 3. 连续更新失败时，增加延迟避免刷屏
     * 4. 更新成功后重置失败计数
     * 
     * @param force 是否强制推送（忽略状态变化检测）
     * @return 推送结果
     */
    public Map<String, Object> pushMonitorCard(boolean force) {
        Map<String, Object> result = new java.util.HashMap<>();
        
        if (monitorChatId == null || monitorChatId.isBlank()) {
            result.put("code", 400);
            result.put("msg", "未配置监控群 chat-id");
            return result;
        }

        // 连续失败退避：如果连续失败多次，增加延迟
        if (consecutiveUpdateFailures >= MAX_CONSECUTIVE_FAILURES) {
            log.warn("连续更新失败 {} 次，跳过本次推送（避免刷屏）", consecutiveUpdateFailures);
            result.put("code", 429);
            result.put("msg", "连续更新失败 " + consecutiveUpdateFailures + " 次，请稍后重试");
            return result;
        }

        try {
            String content = buildMonitorText();
            if (content == null) {
                result.put("code", 500);
                result.put("msg", "获取任务列表失败");
                return result;
            }

            // 状态变化检测：仅当内容变化或强制推送时才推送
            if (!force && lastPushedContent != null && lastPushedContent.equals(content)) {
                log.debug("任务状态未变化，跳过推送");
                result.put("code", 0);
                result.put("msg", "任务状态未变化，无需推送");
                result.put("skipped", true);
                return result;
            }

            // content 必须是 JSON 字符串: {"text":"..."}
            String contentJson = "{\"text\":\"" + escapeJson(content) + "\"}";

            // 策略1：编辑次数接近上限时，主动发送新消息
            if (lastMonitorMessageId != null && updateCount >= MAX_UPDATE_COUNT) {
                log.info("消息编辑次数接近上限（{}/{}），主动发送新消息", updateCount, MAX_UPDATE_COUNT);
                lastMonitorMessageId = null; // 强制发送新消息
                updateCount = 0;
            }

            boolean pushSuccess = false;
            if (lastMonitorMessageId != null) {
                // 尝试原地更新文本消息
                boolean updateSuccess = feishuClient.updateMessage(lastMonitorMessageId, contentJson, "text");
                if (updateSuccess) {
                    updateCount++;
                    consecutiveUpdateFailures = 0; // 重置失败计数
                    pushSuccess = true;
                    log.debug("任务监控消息已更新: messageId={}, 编辑次数={}", lastMonitorMessageId, updateCount);
                } else {
                    // 更新失败（可能是编辑次数上限），发送新消息
                    consecutiveUpdateFailures++;
                    log.info("消息更新失败（第 {} 次），发送新消息（旧messageId={}）", consecutiveUpdateFailures, lastMonitorMessageId);
                    lastMonitorMessageId = feishuClient.sendText(monitorChatId, content);
                    if (lastMonitorMessageId != null) {
                        updateCount = 0; // 重置编辑计数
                        consecutiveUpdateFailures = 0; // 重置失败计数
                        pushSuccess = true;
                        log.info("任务监控新消息已发送: messageId={}", lastMonitorMessageId);
                    } else {
                        log.warn("任务监控新消息发送失败");
                    }
                }
            } else {
                // 首次发送或主动刷新
                lastMonitorMessageId = feishuClient.sendText(monitorChatId, content);
                if (lastMonitorMessageId != null) {
                    updateCount = 0;
                    consecutiveUpdateFailures = 0;
                    pushSuccess = true;
                    log.info("任务监控消息已发送: messageId={}", lastMonitorMessageId);
                } else {
                    consecutiveUpdateFailures++;
                    log.warn("任务监控消息发送失败（第 {} 次）", consecutiveUpdateFailures);
                }
            }
            
            // 更新缓存
            if (pushSuccess) {
                lastPushedContent = content;
                forcePush = false;
                result.put("code", 0);
                result.put("msg", "推送成功");
                result.put("messageId", lastMonitorMessageId);
                result.put("updateCount", updateCount);
            } else {
                result.put("code", 500);
                result.put("msg", "推送失败");
            }
        } catch (Exception e) {
            consecutiveUpdateFailures++;
            log.error("推送任务监控消息失败（第 {} 次）", consecutiveUpdateFailures, e);
            result.put("code", 500);
            result.put("msg", "推送失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 手动触发推送（强制推送）
     * 可以通过 API 调用此方法
     */
    public Map<String, Object> triggerPush() {
        log.info("手动触发任务监控面板推送");
        return pushMonitorCard(true);
    }

    /**
     * 重置监控状态（清除缓存的 messageId，下次发送新消息）
     */
    public void resetMonitor() {
        this.lastMonitorMessageId = null;
        this.consecutiveUpdateFailures = 0;
        this.updateCount = 0;
        this.lastPushedContent = null;
        this.forcePush = true;
        log.info("已重置任务监控状态");
    }

    /**
     * 构建任务监控文本（使用飞书 Markdown 格式）
     */
    private String buildMonitorText() {
        List<AsyncTaskStatus> tasks;
        try {
            tasks = taskStatusService.listTasks(null, null);
        } catch (Exception e) {
            log.warn("获取任务列表失败: {}", e.getMessage());
            return null;
        }

        int total = tasks.size();
        int completed = (int) tasks.stream().filter(t -> t.getStatus() == AsyncTaskStatus.Status.COMPLETED).count();
        int failed = (int) tasks.stream().filter(t -> t.getStatus() == AsyncTaskStatus.Status.FAILED).count();
        int running = total - completed - failed;

        // 计算平均耗时（仅统计已完成的任务）
        double avgDuration = tasks.stream()
                .filter(t -> t.getDurationMs() != null && t.getStatus() == AsyncTaskStatus.Status.COMPLETED)
                .mapToLong(AsyncTaskStatus::getDurationMs)
                .average()
                .orElse(0);

        StringBuilder sb = new StringBuilder();
        sb.append("🤖 **任务监控面板**\n\n");

        // 统计信息
        sb.append("📊 **任务统计**\n");
        sb.append(String.format("总数: %d | 运行中: %d | 成功: %d | 失败: %d\n",
                total, running, completed, failed));
        sb.append(String.format("⏱️ 平均耗时: %.0fms\n", avgDuration));

        sb.append("\n---\n\n");

        // 最近任务列表
        sb.append("📋 **最近任务**\n\n");

        int showCount = Math.min(tasks.size(), 10);
        for (int i = 0; i < showCount; i++) {
            AsyncTaskStatus task = tasks.get(i);
            String statusEmoji = getStatusEmoji(task.getStatus());
            String progressBar = buildProgressBar(task.getProgress());
            String durationStr = task.getDurationMs() != null ? task.getDurationMs() + "ms" : "-";

            String taskIdShort = task.getTaskId().length() > 8
                    ? task.getTaskId().substring(0, 8) + "..."
                    : task.getTaskId();

            sb.append(String.format("%s `%s`\n", statusEmoji, taskIdShort));
            sb.append(String.format("   📊 %d%% %s | ⏱️ %s\n", task.getProgress(), progressBar, durationStr));
            sb.append(String.format("   📝 %s\n\n", task.getStatusMsg() != null ? task.getStatusMsg() : "-"));
        }

        // 底部时间戳
        sb.append("---\n");
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        sb.append("最后更新: ").append(timestamp).append("\n");

        return sb.toString();
    }

    /**
     * 转义 JSON 字符串中的特殊字符
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 根据状态返回对应 Emoji
     */
    private String getStatusEmoji(AsyncTaskStatus.Status status) {
        if (status == null) return "⏳";
        return switch (status) {
            case PENDING -> "⏳";
            case PROCESSING -> "🔄";
            case COMPLETED -> "✅";
            case FAILED -> "❌";
        };
    }

    /**
     * 构建文本进度条
     */
    private String buildProgressBar(int progress) {
        int bars = progress / 10;
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < 10; i++) {
            sb.append(i < bars ? "█" : "░");
        }
        sb.append("]");
        return sb.toString();
    }
}
