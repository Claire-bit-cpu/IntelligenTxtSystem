package com.example.intelligentxtsystem.task;

import com.example.intelligentxtsystem.config.ApplicationContextProvider;
import org.springframework.core.env.Environment;

import java.time.LocalDateTime;

/**
 * 异步任务状态实体
 * 用于跟踪异步任务的执行状态、进度和结果
 * 
 * 存储层通过 TaskStatusService 实现（支持内存/Redis 切换）
 */
public class AsyncTaskStatus {
    
    /**
     * 任务状态枚举
     */
    public enum Status {
        PENDING,    // 待处理
        PROCESSING, // 处理中
        COMPLETED,  // 已完成
        FAILED      // 失败
    }
    
    // ====== 任务字段 ======
    private String taskId;        // 任务唯一标识（UUID）
    private Status status;        // 任务状态
    private int progress;         // 进度（0-100）
    private String statusMsg;     // 状态描述信息
    private String result;        // 执行结果（JSON格式）
    private String errorMessage;  // 错误信息（失败时）
    private LocalDateTime createdAt;  // 创建时间
    private LocalDateTime updatedAt;  // 更新时间
    private String eventType;     // 关联的事件类型
    private String eventId;       // 关联的事件ID
    private String messageId;     // 飞书消息ID（用于原地更新进度）
    private String chatId;        // 飞书群聊ID（用于发送新消息）
    private StringBuilder logs;   // 任务执行日志（累积）
    private Long durationMs;      // 任务耗时（毫秒）
    
    // ====== 静态引用 TaskStatusService（通过 ApplicationContextProvider 获取）======
    private static TaskStatusService getService() {
        TaskStatusService service = ApplicationContextProvider.getBean(TaskStatusService.class);
        if (service == null) {
            throw new IllegalStateException("TaskStatusService 尚未初始化，请确认 Spring 上下文已加载");
        }
        return service;
    }
    
    // ====== 构造函数 ======
    public AsyncTaskStatus() {}
    
    public AsyncTaskStatus(String taskId, String eventType, String eventId) {
        this.taskId = taskId;
        this.eventType = eventType;
        this.eventId = eventId;
        this.status = Status.PENDING;
        this.progress = 0;
        this.statusMsg = "任务已创建，等待处理";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.logs = new StringBuilder();
        this.appendLog("任务已创建");
    }
    
    // ====== 静态操作方法 ======
    
    /**
     * 保存任务状态
     */
    public static void save(AsyncTaskStatus task) {
        getService().save(task);
    }
    
    /**
     * 根据 taskId 获取任务状态
     */
    public static AsyncTaskStatus get(String taskId) {
        return getService().get(taskId);
    }
    
    /**
     * 更新任务状态（线程安全）
     */
    public static void updateStatus(String taskId, Status status) {
        getService().update(taskId, v -> v.setStatus(status));
    }
    
    /**
     * 更新任务进度（线程安全）
     */
    public static void updateProgress(String taskId, int progress) {
        getService().update(taskId, v -> v.setProgress(Math.min(100, Math.max(0, progress))));
    }
    
    /**
     * 标记任务完成（线程安全）
     */
    public static void markCompleted(String taskId, String result) {
        getService().update(taskId, v -> {
            v.setStatus(Status.COMPLETED);
            v.setProgress(100);
            v.setStatusMsg("任务已完成");
            v.setResult(result);
        });
    }
    
    /**
     * 标记任务失败（线程安全）
     */
    public static void markFailed(String taskId, String errorMessage) {
        getService().update(taskId, v -> {
            v.setStatus(Status.FAILED);
            v.setStatusMsg("任务失败: " + errorMessage);
            v.setErrorMessage(errorMessage);
        });
    }
    
    /**
     * 标记任务开始处理（线程安全）
     */
    public static void markProcessing(String taskId) {
        getService().update(taskId, v -> {
            v.setStatus(Status.PROCESSING);
            v.setProgress(10);
            v.setStatusMsg("开始处理任务");
        });
    }

    /**
     * 更新任务进度和状态信息（线程安全）
     * @param taskId 任务ID
     * @param progress 进度（0-100）
     * @param statusMsg 状态描述信息
     */
    public static void updateTaskProgress(String taskId, int progress, String statusMsg) {
        getService().update(taskId, v -> {
            v.setProgress(Math.min(100, Math.max(0, progress)));
            if (statusMsg != null) {
                v.setStatusMsg(statusMsg);
            }
        });
    }

    /**
     * 追加任务日志（线程安全）
     * @param taskId 任务ID
     * @param logLine 日志内容
     */
    public static void appendLog(String taskId, String logLine) {
        getService().update(taskId, v -> {
            if (v.logs == null) v.logs = new StringBuilder();
            v.appendLog(logLine);
        });
    }

    /**
     * 设置任务耗时（线程安全）
     * @param taskId 任务ID
     * @param durationMs 耗时（毫秒）
     */
    public static void setDuration(String taskId, long durationMs) {
        getService().update(taskId, v -> v.setDurationMs(durationMs));
    }

    /**
     * 保存飞书消息ID（线程安全）
     * @param taskId 任务ID
     * @param messageId 飞书消息ID
     */
    public static void setMessageId(String taskId, String messageId) {
        getService().update(taskId, v -> v.setMessageId(messageId));
    }
    
    /**
     * 保存飞书群聊ID（线程安全）
     * @param taskId 任务ID
     * @param chatId 飞书群聊ID
     */
    public static void setChatId(String taskId, String chatId) {
        getService().update(taskId, v -> v.setChatId(chatId));
    }
    
    /**
     * 清理已完成的任务（可选，防止内存泄漏）
     * @param olderThanMinutes 清理超过指定分钟数的已完成任务
     */
    public static int cleanup(int olderThanMinutes) {
        return getService().cleanup(olderThanMinutes);
    }
    
    /**
     * 获取当前任务数量（用于监控）
     */
    public static int getTaskCount() {
        return getService().getTaskCount();
    }
    
    // ====== Getter/Setter ======
    
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    
    public String getStatusMsg() { return statusMsg; }
    public void setStatusMsg(String statusMsg) { this.statusMsg = statusMsg; }
    
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }

    public String getLogs() { return logs != null ? logs.toString() : ""; }
    public void setLogs(StringBuilder logs) { this.logs = logs; }

    // 单任务日志最大长度（可通过配置调整，默认 5000）
    private static final int DEFAULT_MAX_LOGS_LENGTH = 5000;

    public void appendLog(String logLine) {
        if (this.logs == null) this.logs = new StringBuilder();
        String timestamp = LocalDateTime.now().toString().substring(11, 19);
        this.logs.append(timestamp).append(" ").append(logLine).append("\n");
        
        // 从 Spring Environment 获取配置（AsyncTaskStatus 不是 Spring Bean，需手动获取）
        int maxLogsLength = getMaxLogsLengthFromConfig();
        
        // 超过最大长度时截断（保留最后部分）
        if (this.logs.length() > maxLogsLength) {
            String trimmed = this.logs.substring(Math.max(0, this.logs.length() - maxLogsLength));
            this.logs = new StringBuilder("...(日志过长，已截断)...\n").append(trimmed);
        }
    }
    
    /**
     * 从 Spring Environment 获取 maxLogsLength 配置
     */
    private int getMaxLogsLengthFromConfig() {
        try {
            Environment environment = ApplicationContextProvider.getBean(Environment.class);
            if (environment != null) {
                String value = environment.getProperty("task.status.max-logs-length");
                if (value != null) {
                    return Integer.parseInt(value);
                }
            }
        } catch (Exception e) {
            // 配置获取失败，使用默认值
        }
        return DEFAULT_MAX_LOGS_LENGTH;
    }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
}
