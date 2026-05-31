package com.example.IntelligentRobot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池监控服务
 * 定期采集线程池指标，存储到 Redis，支持告警
 *
 * 监控指标：
 * 1. 核心线程数、最大线程数、当前线程数
 * 2. 队列大小、活跃线程数、已完成任务数
 * 3. 拒绝任务数（通过自定义拒绝策略记录）
 */
@Service
public class ThreadPoolMonitorService {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolMonitorService.class);

    private static final String METRIC_KEY_PREFIX = "metrics:threadpool:";
    private static final String REJECT_COUNT_KEY_PREFIX = "metrics:reject:";

    @Autowired(required = false)
    private ThreadPoolTaskExecutor highPriorityEventExecutor;

    @Autowired(required = false)
    private ThreadPoolTaskExecutor lowPriorityEventExecutor;

    @Autowired(required = false)
    private ThreadPoolTaskExecutor messageExecutor;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    @Autowired(required = false)
    private AlertService alertService;

    /**
     * 定时采集线程池指标（每 30 秒）
     */
    @Scheduled(fixedDelay = 30000)
    public void collectMetrics() {
        collectExecutorMetrics("high-priority", highPriorityEventExecutor);
        collectExecutorMetrics("low-priority", lowPriorityEventExecutor);
        collectExecutorMetrics("message", messageExecutor);
    }

    /**
     * 采集单个线程池的指标
     */
    private void collectExecutorMetrics(String name, ThreadPoolTaskExecutor executor) {
        if (executor == null) return;

        try {
            ThreadPoolExecutor threadPool = executor.getThreadPoolExecutor();
            if (threadPool == null) return;

            Map<String, Object> metrics = getExecutorMetrics(threadPool);

            // 存储到 Redis（过期时间 5 分钟）
            if (stringRedisTemplate != null) {
                String key = METRIC_KEY_PREFIX + name;
                Map<String, String> redisData = new HashMap<>();
                for (Map.Entry<String, Object> entry : metrics.entrySet()) {
                    redisData.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
                stringRedisTemplate.opsForHash().putAll(key, redisData);
                stringRedisTemplate.expire(key, 300, java.util.concurrent.TimeUnit.SECONDS);
            }

            // 检查队列饱和度告警
            checkQueueSaturation(name, metrics);

            log.debug("线程池指标采集完成: name={}, metrics={}", name, metrics);

        } catch (Exception e) {
            log.warn("采集线程池指标失败: name={}", name, e);
        }
    }

    /**
     * 获取线程池指标（公共方法，供 Controller 调用）
     */
    public Map<String, Object> getExecutorMetrics(ThreadPoolExecutor executor) {
        Map<String, Object> metrics = new HashMap<>();
        if (executor == null) return metrics;

        int poolSize = executor.getPoolSize();
        int corePoolSize = executor.getCorePoolSize();
        int maximumPoolSize = executor.getMaximumPoolSize();
        long completedTaskCount = executor.getCompletedTaskCount();
        long taskCount = executor.getTaskCount();
        int activeCount = executor.getActiveCount();
        int queueSize = executor.getQueue().size();
        int remainingCapacity = executor.getQueue().remainingCapacity();
        int totalQueueCapacity = queueSize + remainingCapacity;

        metrics.put("pool_size", poolSize);
        metrics.put("core_pool_size", corePoolSize);
        metrics.put("max_pool_size", maximumPoolSize);
        metrics.put("active_count", activeCount);
        metrics.put("completed_task_count", completedTaskCount);
        metrics.put("task_count", taskCount);
        metrics.put("queue_size", queueSize);
        metrics.put("queue_remaining", remainingCapacity);
        metrics.put("queue_capacity", totalQueueCapacity);
        metrics.put("queue_usage_rate", totalQueueCapacity > 0 ?
                (double) queueSize / totalQueueCapacity : 0.0);

        // 读取拒绝计数
        long rejectCount = getRejectCount(nameFromExecutor(executor));
        metrics.put("rejected_count", rejectCount);

        return metrics;
    }

    /**
     * 获取所有线程池的指标
     */
    public Map<String, Object> getAllMetrics() {
        Map<String, Object> result = new HashMap<>();
        result.put("high_priority", getExecutorMetrics(
                highPriorityEventExecutor != null ? highPriorityEventExecutor.getThreadPoolExecutor() : null));
        result.put("low_priority", getExecutorMetrics(
                lowPriorityEventExecutor != null ? lowPriorityEventExecutor.getThreadPoolExecutor() : null));
        result.put("message", getExecutorMetrics(
                messageExecutor != null ? messageExecutor.getThreadPoolExecutor() : null));
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    /**
     * 检查队列饱和度，触发告警
     */
    private void checkQueueSaturation(String name, Map<String, Object> metrics) {
        if (alertService == null) return;

        double usageRate = (double) metrics.getOrDefault("queue_usage_rate", 0.0);
        double threshold = 0.8; // 默认 80%

        if (usageRate >= threshold) {
            alertService.sendThreadPoolSaturationAlert(name, usageRate);
        }
    }

    /**
     * 记录任务拒绝（供自定义拒绝策略调用）
     */
    public void recordRejection(String executorName, String taskInfo) {
        // 写入 Redis 计数器
        if (stringRedisTemplate != null) {
            String key = REJECT_COUNT_KEY_PREFIX + executorName;
            stringRedisTemplate.opsForValue().increment(key);
            stringRedisTemplate.expire(key, 86400, java.util.concurrent.TimeUnit.SECONDS);
        }

        log.warn("任务被拒绝: executor={}, task={}", executorName, taskInfo);

        // 发送告警
        if (alertService != null) {
            alertService.sendTaskRejectedAlert(executorName, taskInfo);
        }
    }

    /**
     * 获取拒绝计数
     */
    public long getRejectCount(String executorName) {
        if (stringRedisTemplate == null) return 0;
        String key = REJECT_COUNT_KEY_PREFIX + executorName;
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value != null) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 重置拒绝计数
     */
    public void resetRejectCount(String executorName) {
        if (stringRedisTemplate == null) return;
        String key = REJECT_COUNT_KEY_PREFIX + executorName;
        stringRedisTemplate.delete(key);
        log.info("已重置拒绝计数: executor={}", executorName);
    }

    /**
     * 从 ThreadPoolExecutor 反查名称
     */
    private String nameFromExecutor(ThreadPoolExecutor executor) {
        if (highPriorityEventExecutor != null &&
                highPriorityEventExecutor.getThreadPoolExecutor() == executor) {
            return "high-priority";
        }
        if (lowPriorityEventExecutor != null &&
                lowPriorityEventExecutor.getThreadPoolExecutor() == executor) {
            return "low-priority";
        }
        if (messageExecutor != null &&
                messageExecutor.getThreadPoolExecutor() == executor) {
            return "message";
        }
        return "unknown";
    }
}
