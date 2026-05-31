package com.example.IntelligentRobot.controller;

import com.example.IntelligentRobot.task.AsyncTaskStatus;
import com.example.IntelligentRobot.task.TaskStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 异步任务状态查询接口
 * 
 * 提供任务状态的实时查询能力，支持细粒度进度跟踪
 * 
 * 接口列表：
 * 1. GET /api/task/{taskId} - 查询任务状态
 * 2. GET /api/task/count - 获取当前任务数量（用于监控）
 */
@RestController
@RequestMapping("/api/task")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    /**
     * 查询任务状态
     * 
     * @param taskId 任务ID
     * @return 任务状态详情
     * 
     * 返回字段说明：
     * - taskId: 任务ID
     * - status: 任务状态 (PENDING/PROCESSING/COMPLETED/FAILED)
     * - progress: 进度 (0-100)
     * - statusMsg: 状态描述信息
     * - result: 执行结果（完成时返回）
     * - errorMessage: 错误信息（失败时返回）
     * - eventType: 关联的事件类型
     * - eventId: 关联的事件ID
     * - createdAt: 创建时间
     * - updatedAt: 更新时间
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> getTaskStatus(@PathVariable String taskId) {
        log.debug("查询任务状态: taskId={}", taskId);

        AsyncTaskStatus task = AsyncTaskStatus.get(taskId);

        if (task == null) {
            log.warn("任务不存在: taskId={}", taskId);
            return ResponseEntity.status(404)
                    .body(Map.of(
                            "code", 404,
                            "msg", "任务不存在",
                            "taskId", taskId
                    ));
        }

        // 构建响应
        Map<String, Object> response = new HashMap<>();
        response.put("code", 0);
        response.put("msg", "ok");
        response.put("taskId", task.getTaskId());
        response.put("status", task.getStatus().name());
        response.put("progress", task.getProgress());
        response.put("statusMsg", task.getStatusMsg());
        response.put("eventType", task.getEventType());
        response.put("eventId", task.getEventId());
        response.put("createdAt", task.getCreatedAt());
        response.put("updatedAt", task.getUpdatedAt());
        response.put("messageId", task.getMessageId());
        response.put("logs", task.getLogs());
        response.put("durationMs", task.getDurationMs());

        // 根据状态返回额外信息
        if (task.getStatus() == AsyncTaskStatus.Status.COMPLETED) {
            response.put("result", task.getResult());
        } else if (task.getStatus() == AsyncTaskStatus.Status.FAILED) {
            response.put("errorMessage", task.getErrorMessage());
        }

        log.debug("任务状态查询成功: taskId={}, status={}, progress={}%",
                taskId, task.getStatus(), task.getProgress());

        return ResponseEntity.ok(response);
    }

    /**
     * 获取当前任务数量（用于监控）
     * 
     * @return 任务数量统计
     */
    @GetMapping("/count")
    public Map<String, Object> getTaskCount() {
        int count = AsyncTaskStatus.getTaskCount();
        log.debug("当前任务数量: {}", count);

        Map<String, Object> response = new HashMap<>();
        response.put("code", 0);
        response.put("msg", "ok");
        response.put("taskCount", count);

        return response;
    }

    /**
     * 获取任务列表（用于前端监控页面）
     * 支持按状态、事件类型筛选
     */
    @GetMapping("/list")
    public Map<String, Object> getTaskList(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String eventType) {
        log.debug("查询任务列表: status={}, eventType={}", status, eventType);
        java.util.List<AsyncTaskStatus> tasks = getService().listTasks(status, eventType);
        java.util.List<Map<String, Object>> result = tasks.stream().map(t -> {
            Map<String, Object> m = new HashMap<>();
            m.put("taskId", t.getTaskId());
            m.put("status", t.getStatus() != null ? t.getStatus().name() : null);
            m.put("progress", t.getProgress());
            m.put("statusMsg", t.getStatusMsg());
            m.put("eventType", t.getEventType());
            m.put("eventId", t.getEventId());
            m.put("messageId", t.getMessageId());
            m.put("durationMs", t.getDurationMs());
            m.put("createdAt", t.getCreatedAt());
            m.put("updatedAt", t.getUpdatedAt());
            return m;
        }).toList();

        Map<String, Object> response = new HashMap<>();
        response.put("code", 0);
        response.put("msg", "ok");
        response.put("total", result.size());
        response.put("tasks", result);
        return response;
    }

    // 内部辅助：获取 TaskStatusService 实例（用于 list 查询）
    private TaskStatusService getService() {
        return com.example.IntelligentRobot.config.ApplicationContextProvider.getBean(TaskStatusService.class);
    }

    /**
     * 手动清理已完成的任务（可选，防止内存泄漏）
     * 
     * @param olderThanMinutes 清理超过指定分钟数的已完成任务（默认60分钟）
     * @return 清理结果
     */
    @PostMapping("/cleanup")
    public Map<String, Object> cleanupTasks(
            @RequestParam(defaultValue = "60") int olderThanMinutes) {
        log.info("开始清理已完成任务: olderThanMinutes={}", olderThanMinutes);

        try {
            AsyncTaskStatus.cleanup(olderThanMinutes);

            Map<String, Object> response = new HashMap<>();
            response.put("code", 0);
            response.put("msg", "清理完成");
            response.put("taskCountAfterCleanup", AsyncTaskStatus.getTaskCount());

            log.info("任务清理完成");
            return response;
        } catch (Exception e) {
            log.error("任务清理失败", e);
            return Map.of(
                    "code", 500,
                    "msg", "清理失败: " + e.getMessage()
            );
        }
    }
}
