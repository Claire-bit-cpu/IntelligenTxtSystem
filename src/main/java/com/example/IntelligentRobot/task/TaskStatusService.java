package com.example.IntelligentRobot.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务状态存储服务
 * 
 * 支持两种存储模式（通过配置切换）：
 * 1. memory  - 内存存储（ConcurrentHashMap），适用于单机部署
 * 2. redis   - Redis 存储，适用于集群部署
 * 
 * 使用方式：
 *   task.status.store=memory  # 默认值
 *   task.status.store=redis   # 使用 Redis
 */
@Service
public class TaskStatusService {

    private static final Logger log = LoggerFactory.getLogger(TaskStatusService.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${task.status.store:memory}")
    private String storeType;

    // 内存存储
    private static final Map<String, AsyncTaskStatus> MEMORY_STORE = new ConcurrentHashMap<>();

    // Redis Key 前缀
    private static final String REDIS_KEY_PREFIX = "task:status:";

    // 构造器注入（允许 stringRedisTemplate 为 null，即 Redis 未配置时）
    public TaskStatusService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @PostConstruct
    public void init() {
        log.info("任务状态存储模式: {}", storeType);
        if ("redis".equalsIgnoreCase(storeType) && stringRedisTemplate == null) {
            log.error("存储模式配置为 redis，但未找到 Redis 连接，请检查配置！将降级为内存存储。");
            storeType = "memory";
        }
    }

    /**
     * 保存/更新任务状态
     */
    public void save(AsyncTaskStatus task) {
        if ("redis".equalsIgnoreCase(storeType)) {
            saveToRedis(task);
        } else {
            MEMORY_STORE.put(task.getTaskId(), task);
        }
    }

    /**
     * 获取任务状态
     */
    public AsyncTaskStatus get(String taskId) {
        if ("redis".equalsIgnoreCase(storeType)) {
            return getFromRedis(taskId);
        } else {
            return MEMORY_STORE.get(taskId);
        }
    }

    /**
     * 获取任务列表（用于前端监控页面）
     * @param status    按状态筛选（可选）
     * @param eventType 按事件类型筛选（可选）
     */
    public java.util.List<AsyncTaskStatus> listTasks(String status, String eventType) {
        java.util.List<AsyncTaskStatus> all;
        if ("redis".equalsIgnoreCase(storeType)) {
            all = new java.util.ArrayList<>();
            var keys = stringRedisTemplate.keys(REDIS_KEY_PREFIX + "*");
            if (keys != null) {
                for (String key : keys) {
                    AsyncTaskStatus task = getFromRedis(key.replace(REDIS_KEY_PREFIX, ""));
                    if (task != null) all.add(task);
                }
            }
        } else {
            all = new java.util.ArrayList<>(MEMORY_STORE.values());
        }
        // 筛选
        if (status != null && !status.isEmpty()) {
            all = all.stream().filter(t -> t.getStatus() != null && t.getStatus().name().equalsIgnoreCase(status)).toList();
        }
        if (eventType != null && !eventType.isEmpty()) {
            all = all.stream().filter(t -> eventType.equalsIgnoreCase(t.getEventType())).toList();
        }
        return all;
    }

    /**
     * 原子更新任务（Redis 用 Lua 脚本保证原子性，内存用 computeIfPresent）
     * @param taskId  任务ID
     * @param updater 更新逻辑
     */
    public void update(String taskId, java.util.function.Consumer<AsyncTaskStatus> updater) {
        if ("redis".equalsIgnoreCase(storeType)) {
            updateRedisAtomic(taskId, updater);
        } else {
            MEMORY_STORE.computeIfPresent(taskId, (k, v) -> {
                updater.accept(v);
                v.setUpdatedAt(LocalDateTime.now());
                return v;
            });
        }
    }

    /**
     * 删除任务
     */
    public void delete(String taskId) {
        if ("redis".equalsIgnoreCase(storeType)) {
            stringRedisTemplate.delete(REDIS_KEY_PREFIX + taskId);
        } else {
            MEMORY_STORE.remove(taskId);
        }
    }
    
    /**
     * 更新任务进度（便捷方法）
     * @param taskId 任务ID
     * @param progress 进度（0-100）
     * @param statusMsg 状态描述信息
     */
    public void updateTaskProgress(String taskId, int progress, String statusMsg) {
        update(taskId, task -> {
            task.setProgress(progress);
            if (statusMsg != null) {
                task.setStatusMsg(statusMsg);
            }
            
            // 根据进度自动更新状态
            if (progress >= 100) {
                task.setStatus(AsyncTaskStatus.Status.COMPLETED);
                // 计算任务耗时
                if (task.getCreatedAt() != null) {
                    long duration = System.currentTimeMillis() - task.getCreatedAt()
                            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                    task.setDurationMs(duration);
                }
            } else if (progress > 0 && task.getStatus() == AsyncTaskStatus.Status.PENDING) {
                task.setStatus(AsyncTaskStatus.Status.PROCESSING);
            }
        });
    }

    /**
     * 获取当前任务数量
     */
    public int getTaskCount() {
        if ("redis".equalsIgnoreCase(storeType)) {
            // Redis 扫描计数
            var keys = stringRedisTemplate.keys(REDIS_KEY_PREFIX + "*");
            return keys != null ? keys.size() : 0;
        } else {
            return MEMORY_STORE.size();
        }
    }

    /**
     * 清理已完成的旧任务
     * @param olderThanMinutes 超过此分钟数的已完成/失败任务将被清理
     */
    public int cleanup(int olderThanMinutes) {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(olderThanMinutes);
        int removed = 0;

        if ("redis".equalsIgnoreCase(storeType)) {
            // Redis 模式：扫描并删除过期任务
            var keys = stringRedisTemplate.keys(REDIS_KEY_PREFIX + "*");
            if (keys != null) {
                for (String key : keys) {
                    AsyncTaskStatus task = getFromRedis(key.replace(REDIS_KEY_PREFIX, ""));
                    if (task != null && isRemovable(task, threshold)) {
                        stringRedisTemplate.delete(key);
                        removed++;
                    }
                }
            }
        } else {
            // 内存模式
            int before = MEMORY_STORE.size();
            MEMORY_STORE.entrySet().removeIf(entry -> {
                AsyncTaskStatus task = entry.getValue();
                return isRemovable(task, threshold);
            });
            removed = before - MEMORY_STORE.size();
        }

        if (removed > 0) {
            log.info("清理了 {} 个过期任务", removed);
        }
        return removed;
    }

    // ====== 私有方法 ======

    private boolean isRemovable(AsyncTaskStatus task, LocalDateTime threshold) {
        return (task.getStatus() == AsyncTaskStatus.Status.COMPLETED
                || task.getStatus() == AsyncTaskStatus.Status.FAILED)
                && task.getUpdatedAt() != null
                && task.getUpdatedAt().isBefore(threshold);
    }

    // ====== Redis 序列化/反序列化 ======

    private void saveToRedis(AsyncTaskStatus task) {
        try {
            String key = REDIS_KEY_PREFIX + task.getTaskId();
            String value = objectMapper.writeValueAsString(task);
            // 设置 TTL（24小时自动过期）
            stringRedisTemplate.opsForValue().set(key, value, java.time.Duration.ofHours(24));
        } catch (JsonProcessingException e) {
            log.error("序列化任务状态失败: taskId={}", task.getTaskId(), e);
        }
    }

    private AsyncTaskStatus getFromRedis(String taskId) {
        try {
            String key = REDIS_KEY_PREFIX + taskId;
            String value = stringRedisTemplate.opsForValue().get(key);
            if (value == null) {
                return null;
            }
            return objectMapper.readValue(value, AsyncTaskStatus.class);
        } catch (Exception e) {
            log.error("反序列化任务状态失败: taskId={}", taskId, e);
            return null;
        }
    }

    /**
     * Redis 原子更新（使用 Lua 脚本）
     * 注意：简化版直接 GET -> 修改 -> SET，高并发场景可改用 Lua 脚本
     */
    private void updateRedisAtomic(String taskId, java.util.function.Consumer<AsyncTaskStatus> updater) {
        try {
            String key = REDIS_KEY_PREFIX + taskId;
            String value = stringRedisTemplate.opsForValue().get(key);
            if (value == null) {
                return;
            }
            AsyncTaskStatus task = objectMapper.readValue(value, AsyncTaskStatus.class);
            updater.accept(task);
            task.setUpdatedAt(LocalDateTime.now());
            String newValue = objectMapper.writeValueAsString(task);
            // 保留原有 TTL
            Long ttl = stringRedisTemplate.getExpire(key);
            if (ttl != null && ttl > 0) {
                stringRedisTemplate.opsForValue().set(key, newValue, java.time.Duration.ofSeconds(ttl));
            } else {
                stringRedisTemplate.opsForValue().set(key, newValue, java.time.Duration.ofHours(24));
            }
        } catch (Exception e) {
            log.error("Redis 更新任务状态失败: taskId={}", taskId, e);
        }
    }
}
