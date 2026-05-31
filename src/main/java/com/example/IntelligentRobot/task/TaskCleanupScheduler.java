package com.example.IntelligentRobot.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 任务清理定时任务
 * 
 * 定期清理已过期的已完成/失败任务，防止存储无限增长
 * 
 * 配置项（application.yaml）：
 *   task.cleanup.enabled: true        # 是否启用自动清理（默认 true）
 *   task.cleanup.cron: "0 0/5 * * * *"  # Cron 表达式（默认每5分钟）
 *   task.cleanup.older-than-minutes: 60  # 清理超过 60 分钟的已完成任务（默认 60）
 */
@Component
public class TaskCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskCleanupScheduler.class);

    private final TaskStatusService taskStatusService;

    @Value("${task.cleanup.enabled:true}")
    private boolean cleanupEnabled;

    @Value("${task.cleanup.older-than-minutes:60}")
    private int olderThanMinutes;

    public TaskCleanupScheduler(TaskStatusService taskStatusService) {
        this.taskStatusService = taskStatusService;
    }

    /**
     * 定时清理任务
     * 默认每 5 分钟执行一次
     * cron 表达式来自配置 task.cleanup.cron（支持运行时修改后重启生效）
     */
    @Scheduled(cron = "${task.cleanup.cron:0 0/5 * * * *}")
    public void cleanupTasks() {
        if (!cleanupEnabled) {
            log.debug("任务清理已禁用，跳过");
            return;
        }

        int beforeCount = taskStatusService.getTaskCount();
        int removed = taskStatusService.cleanup(olderThanMinutes);
        int afterCount = taskStatusService.getTaskCount();

        if (removed > 0) {
            log.info("定时清理完成, 清理前: {} 个任务, 清理了: {} 个, 清理后: {} 个",
                    beforeCount, removed, afterCount);
        } else {
            log.debug("定时清理完成, 无过期任务可清理, 当前任务数: {}", afterCount);
        }
    }
}
