package com.example.intelligentxtsystem.controller;

import com.example.intelligentxtsystem.service.AlertService;
import com.example.intelligentxtsystem.service.TaskMonitorService;
import com.example.intelligentxtsystem.service.ThreadPoolMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 监控指标控制器
 * 提供线程池监控、告警配置查看等接口
 *
 * 接口列表：
 * 1. GET  /api/monitor/threadpool       获取线程池指标
 * 2. GET  /api/monitor/threadpool/{name}  获取指定线程池指标
 * 3. GET  /api/monitor/alert/config     查看告警配置
 * 4. POST /api/monitor/alert/test      发送测试告警
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private static final Logger log = LoggerFactory.getLogger(MonitorController.class);

    @Autowired(required = false)
    private ThreadPoolMonitorService threadPoolMonitorService;

    @Autowired(required = false)
    private AlertService alertService;

    @Autowired(required = false)
    private TaskMonitorService taskMonitorService;

    /**
     * 获取所有线程池指标
     * GET /api/monitor/threadpool
     */
    @GetMapping("/threadpool")
    public Map<String, Object> getThreadPoolMetrics() {
        if (threadPoolMonitorService == null) {
            return Map.of("code", 500, "msg", "ThreadPoolMonitorService 未启用");
        }

        try {
            Map<String, Object> metrics = threadPoolMonitorService.getAllMetrics();
            return Map.of("code", 0, "msg", "ok", "data", metrics);
        } catch (Exception e) {
            log.error("获取线程池指标失败", e);
            return Map.of("code", 500, "msg", "获取失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定线程池指标
     * GET /api/monitor/threadpool/{name}
     * name: high-priority / low-priority / message
     */
    @GetMapping("/threadpool/{name}")
    public Map<String, Object> getThreadPoolMetricsByName(@PathVariable String name) {
        if (threadPoolMonitorService == null) {
            return Map.of("code", 500, "msg", "ThreadPoolMonitorService 未启用");
        }

        try {
            Map<String, Object> allMetrics = threadPoolMonitorService.getAllMetrics();
            Object metrics = allMetrics.get(name.replace("-", "_"));
            if (metrics == null) {
                return Map.of("code", 404, "msg", "线程池不存在: " + name);
            }
            return Map.of("code", 0, "msg", "ok", "data", metrics);
        } catch (Exception e) {
            log.error("获取线程池指标失败: name={}", name, e);
            return Map.of("code", 500, "msg", "获取失败: " + e.getMessage());
        }
    }

    /**
     * 查看告警配置
     * GET /api/monitor/alert/config
     */
    @GetMapping("/alert/config")
    public Map<String, Object> getAlertConfig() {
        if (alertService == null) {
            return Map.of("code", 500, "msg", "AlertService 未启用");
        }

        try {
            Map<String, Object> config = alertService.getAlertConfig();
            return Map.of("code", 0, "msg", "ok", "data", config);
        } catch (Exception e) {
            log.error("获取告警配置失败", e);
            return Map.of("code", 500, "msg", "获取失败: " + e.getMessage());
        }
    }

    /**
     * 发送测试告警
     * POST /api/monitor/alert/test
     * Body: {"type": "rate-limit|task-rejected|system-exception", "detail": "测试详情"}
     */
    @PostMapping("/alert/test")
    public Map<String, Object> sendTestAlert(@RequestBody Map<String, String> body) {
        if (alertService == null) {
            return Map.of("code", 500, "msg", "AlertService 未启用");
        }

        try {
            String type = body.get("type");
            String detail = body.getOrDefault("detail", "这是一条测试告警");

            if ("rate-limit".equals(type)) {
                alertService.sendRateLimitAlert("test:rate:limit", 1000, 1);
            } else if ("task-rejected".equals(type)) {
                alertService.sendTaskRejectedAlert("test-executor", "test-task-info");
            } else if ("system-exception".equals(type)) {
                alertService.sendSystemExceptionAlert("测试告警", detail);
            } else {
                return Map.of("code", 400, "msg", "未知告警类型: " + type);
            }

            return Map.of("code", 0, "msg", "测试告警已发送");
        } catch (Exception e) {
            log.error("发送测试告警失败", e);
            return Map.of("code", 500, "msg", "发送失败: " + e.getMessage());
        }
    }

    /**
     * 重置线程池拒绝计数
     * POST /api/monitor/threadpool/{name}/reset-reject
     */
    @PostMapping("/threadpool/{name}/reset-reject")
    public Map<String, Object> resetRejectCount(@PathVariable String name) {
        if (threadPoolMonitorService == null) {
            return Map.of("code", 500, "msg", "ThreadPoolMonitorService 未启用");
        }

        try {
            threadPoolMonitorService.resetRejectCount(name);
            return Map.of("code", 0, "msg", "拒绝计数已重置: " + name);
        } catch (Exception e) {
            log.error("重置拒绝计数失败: name={}", name, e);
            return Map.of("code", 500, "msg", "重置失败: " + e.getMessage());
        }
    }

    /**
     * 健康检查（详细版）
     * GET /api/monitor/health
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("status", "ok");
        result.put("service", "IntelligenTxtSystem");
        result.put("timestamp", System.currentTimeMillis());

        // 线程池状态
        if (threadPoolMonitorService != null) {
            result.put("threadpool", threadPoolMonitorService.getAllMetrics());
        }

        return Map.of("code", 0, "msg", "ok", "data", result);
    }

    /**
     * 手动触发任务监控面板推送
     * POST /api/monitor/task/push
     * 
     * 请求体（可选）：
     * {
     *   "force": true  // 是否强制推送（忽略状态变化检测）
     * }
     */
    @PostMapping("/task/push")
    public Map<String, Object> pushTaskMonitor(@RequestBody(required = false) Map<String, Object> body) {
        if (taskMonitorService == null) {
            return Map.of("code", 500, "msg", "TaskMonitorService 未启用");
        }

        try {
            boolean force = body != null && Boolean.TRUE.equals(body.get("force"));
            Map<String, Object> result = taskMonitorService.pushMonitorCard(force);
            return result;
        } catch (Exception e) {
            log.error("手动触发任务监控面板推送失败", e);
            return Map.of("code", 500, "msg", "推送失败: " + e.getMessage());
        }
    }

    /**
     * 强制刷新任务监控面板（发送新消息）
     * POST /api/monitor/task/refresh
     */
    @PostMapping("/task/refresh")
    public Map<String, Object> refreshTaskMonitor() {
        if (taskMonitorService == null) {
            return Map.of("code", 500, "msg", "TaskMonitorService 未启用");
        }

        try {
            Map<String, Object> result = taskMonitorService.triggerPush();
            return result;
        } catch (Exception e) {
            log.error("刷新任务监控面板失败", e);
            return Map.of("code", 500, "msg", "刷新失败: " + e.getMessage());
        }
    }

    /**
     * 重置任务监控状态（清除缓存，下次发送新消息）
     * POST /api/monitor/task/reset
     */
    @PostMapping("/task/reset")
    public Map<String, Object> resetTaskMonitor() {
        if (taskMonitorService == null) {
            return Map.of("code", 500, "msg", "TaskMonitorService 未启用");
        }

        try {
            taskMonitorService.resetMonitor();
            return Map.of("code", 0, "msg", "任务监控状态已重置");
        } catch (Exception e) {
            log.error("重置任务监控状态失败", e);
            return Map.of("code", 500, "msg", "重置失败: " + e.getMessage());
        }
    }
}
