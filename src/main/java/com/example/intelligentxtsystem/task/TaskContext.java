package com.example.intelligentxtsystem.task;

/**
 * 任务上下文（线程局部变量）
 * 
 * 用于在命令执行链中传递 taskId 和 chatId，使命令处理器能够更新任务进度
 * 
 * 使用方式：
 * 1. 在 MessageDispatcher.dispatch() 中设置 taskId 和 chatId
 * 2. 在命令处理器中通过 TaskContext.getTaskId() 获取 taskId
 * 3. 在命令处理器中通过 TaskContext.getChatId() 获取 chatId
 * 4. 在命令处理器中更新任务进度
 * 5. 在 finally 块中调用 TaskContext.clear() 清理
 */
public class TaskContext {
    
    private static final ThreadLocal<String> TASK_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> CHAT_ID_HOLDER = new ThreadLocal<>();
    
    /**
     * 设置当前线程的 taskId
     */
    public static void setTaskId(String taskId) {
        if (taskId != null && !taskId.isEmpty()) {
            TASK_ID_HOLDER.set(taskId);
        }
    }
    
    /**
     * 获取当前线程的 taskId
     * @return taskId，如果未设置则返回 null
     */
    public static String getTaskId() {
        return TASK_ID_HOLDER.get();
    }
    
    /**
     * 设置当前线程的 chatId
     */
    public static void setChatId(String chatId) {
        if (chatId != null && !chatId.isEmpty()) {
            CHAT_ID_HOLDER.set(chatId);
        }
    }
    
    /**
     * 获取当前线程的 chatId
     * @return chatId，如果未设置则返回 null
     */
    public static String getChatId() {
        return CHAT_ID_HOLDER.get();
    }
    
    /**
     * 清除当前线程的 taskId 和 chatId（防止内存泄漏）
     * 必须在 finally 块中调用
     */
    public static void clear() {
        TASK_ID_HOLDER.remove();
        CHAT_ID_HOLDER.remove();
    }
    
    /**
     * 更新当前任务的进度（便捷方法）
     * @param progress 进度（0-100）
     * @param statusMsg 状态描述信息
     */
    public static void updateProgress(int progress, String statusMsg) {
        String taskId = getTaskId();
        if (taskId != null) {
            try {
                AsyncTaskStatus.updateTaskProgress(taskId, progress, statusMsg);
            } catch (Exception e) {
                // 静默处理，不影响主流程
                org.slf4j.LoggerFactory.getLogger(TaskContext.class)
                        .warn("更新任务进度失败: taskId={}, error={}", taskId, e.getMessage());
            }
        }
    }
}
