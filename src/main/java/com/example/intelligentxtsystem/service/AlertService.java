package com.example.intelligentxtsystem.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 告警服务
 * 封装所有告警逻辑，通过飞书通知发送告警
 *
 * 告警类型：
 * 1. 限流告警：当触发限流时发送
 * 2. 任务拒绝告警：当线程池拒绝任务时发送
 * 3. 系统异常告警：当系统发生异常时发送
 * 4. 线程池饱和告警：当线程池队列使用率超过阈值时发送
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    @Value("${alert.enabled:true}")
    private boolean alertEnabled;

    @Value("${alert.thread-pool-saturation-threshold:0.8}")
    private double threadPoolSaturationThreshold;

    @Autowired(required = false)
    private NotificationService notificationService;

    @Autowired(required = false)
    private com.example.intelligentxtsystem.client.FeishuClient feishuClient;

    /**
     * 发送限流告警
     */
    public void sendRateLimitAlert(String key, int maxRequests, int windowSeconds) {
        if (!alertEnabled) return;

        String message = String.format(
                "⚠️ **限流告警**\n\n" +
                        "**限流Key：** `%s`\n" +
                        "**阈值：** %d 请求/%d秒\n" +
                        "**时间：** %s\n\n" +
                        "> 触发限流，请求已被拒绝，请检查流量是否正常。",
                key, maxRequests, windowSeconds,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );

        sendAlert(message);
        log.warn("限流告警已发送: key={}", key);
    }

    /**
     * 发送任务拒绝告警
     */
    public void sendTaskRejectedAlert(String executorName, String taskInfo) {
        if (!alertEnabled) return;

        String message = String.format(
                "🚨 **任务拒绝告警**\n\n" +
                        "**线程池：** `%s`\n" +
                        "**任务信息：** %s\n" +
                        "**时间：** %s\n\n" +
                        "> 线程池已满，任务被拒绝。请检查线程池配置或增加实例。",
                executorName, taskInfo,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );

        sendAlert(message);
        log.warn("任务拒绝告警已发送: executor={}, task={}", executorName, taskInfo);
    }

    /**
     * 发送线程池饱和告警
     */
    public void sendThreadPoolSaturationAlert(String executorName, double queueUsageRate) {
        if (!alertEnabled) return;

        String message = String.format(
                "🔥 **线程池饱和告警**\n\n" +
                        "**线程池：** `%s`\n" +
                        "**队列使用率：** %.1f%%\n" +
                        "**阈值：** %.1f%%\n" +
                        "**时间：** %s\n\n" +
                        "> 线程池队列使用率超过阈值，请及时关注。",
                executorName, queueUsageRate * 100,
                threadPoolSaturationThreshold * 100,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );

        sendAlert(message);
        log.warn("线程池饱和告警已发送: executor={}, usageRate={}", executorName, queueUsageRate);
    }

    /**
     * 发送系统异常告警
     */
    public void sendSystemExceptionAlert(String title, String detail) {
        if (!alertEnabled) return;

        String message = String.format(
                "❌ **系统异常告警**\n\n" +
                        "**异常类型：** %s\n" +
                        "**异常详情：** %s\n" +
                        "**时间：** %s\n\n" +
                        "> 系统发生异常，请及时排查。",
                title, detail,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );

        sendAlert(message);
        log.error("系统异常告警已发送: title={}, detail={}", title, detail);
    }

    /**
     * 发送通用告警（内部方法）
     */
    private void sendAlert(String message) {
        try {
            if (notificationService != null) {
                notificationService.sendNotification(message);
            } else if (feishuClient != null) {
                // 降级：直接发送到默认群聊
                feishuClient.sendText(null, message);
            }
        } catch (Exception e) {
            log.error("发送告警失败", e);
        }
    }

    /**
     * 获取告警配置（用于监控接口）
     */
    public Map<String, Object> getAlertConfig() {
        return Map.of(
                "enabled", alertEnabled,
                "thread-pool-saturation-threshold", threadPoolSaturationThreshold
        );
    }
}
