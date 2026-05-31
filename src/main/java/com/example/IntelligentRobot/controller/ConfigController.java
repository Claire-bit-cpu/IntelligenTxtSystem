package com.example.IntelligentRobot.controller;

import com.example.IntelligentRobot.service.DynamicConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 动态配置管理控制器
 * 提供配置查看、刷新、动态调整等接口
 *
 * 接口列表：
 * 1. GET  /api/config               查看当前配置
 * 2. POST /api/config/refresh      刷新配置
 * 3. PUT  /api/config/threadpool   动态调整线程池参数
 * 4. PUT  /api/config/ratelimit   动态调整限流参数
 * 5. PUT  /api/config/alert       动态调整告警参数
 * 6. POST /api/config/reset       重置配置为默认值
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    @Autowired(required = false)
    private DynamicConfigService dynamicConfigService;

    /**
     * 查看当前配置
     * GET /api/config
     */
    @GetMapping("")
    public Map<String, Object> getConfig() {
        if (dynamicConfigService == null) {
            return Map.of("code", 500, "msg", "DynamicConfigService 未启用");
        }

        try {
            Map<String, Object> config = dynamicConfigService.getAllConfig();
            return Map.of("code", 0, "msg", "ok", "data", config);
        } catch (Exception e) {
            log.error("获取配置失败", e);
            return Map.of("code", 500, "msg", "获取失败: " + e.getMessage());
        }
    }

    /**
     * 刷新配置
     * POST /api/config/refresh
     */
    @PostMapping("/refresh")
    public Map<String, Object> refreshConfig() {
        if (dynamicConfigService == null) {
            return Map.of("code", 500, "msg", "DynamicConfigService 未启用");
        }

        try {
            Map<String, Object> config = dynamicConfigService.refreshConfig();
            return Map.of("code", 0, "msg", "配置已刷新", "data", config);
        } catch (Exception e) {
            log.error("刷新配置失败", e);
            return Map.of("code", 500, "msg", "刷新失败: " + e.getMessage());
        }
    }

    /**
     * 动态调整线程池参数
     * PUT /api/config/threadpool
     * Body: {"high-priority": {"core-size": 10, "max-size": 50, "queue-capacity": 1000},
     *        "low-priority": {...}}
     */
    @PutMapping("/threadpool")
    public Map<String, Object> updateThreadPool(@RequestBody Map<String, Object> updates) {
        if (dynamicConfigService == null) {
            return Map.of("code", 500, "msg", "DynamicConfigService 未启用");
        }

        try {
            boolean success = dynamicConfigService.updateThreadPoolConfig(updates);
            if (success) {
                return Map.of("code", 0, "msg", "线程池配置已更新");
            } else {
                return Map.of("code", 500, "msg", "线程池配置更新失败");
            }
        } catch (Exception e) {
            log.error("更新线程池配置失败", e);
            return Map.of("code", 500, "msg", "更新失败: " + e.getMessage());
        }
    }

    /**
     * 动态调整限流参数
     * PUT /api/config/ratelimit
     * Body: {"global-qps": 1000, "ip-qps": 100, "enabled": true}
     */
    @PutMapping("/ratelimit")
    public Map<String, Object> updateRateLimit(@RequestBody Map<String, Object> updates) {
        if (dynamicConfigService == null) {
            return Map.of("code", 500, "msg", "DynamicConfigService 未启用");
        }

        try {
            boolean success = dynamicConfigService.updateRateLimitConfig(updates);
            if (success) {
                return Map.of("code", 0, "msg", "限流配置已更新");
            } else {
                return Map.of("code", 500, "msg", "限流配置更新失败");
            }
        } catch (Exception e) {
            log.error("更新限流配置失败", e);
            return Map.of("code", 500, "msg", "更新失败: " + e.getMessage());
        }
    }

    /**
     * 动态调整告警参数
     * PUT /api/config/alert
     * Body: {"enabled": true, "thread-pool-saturation-threshold": 0.8}
     */
    @PutMapping("/alert")
    public Map<String, Object> updateAlert(@RequestBody Map<String, Object> updates) {
        if (dynamicConfigService == null) {
            return Map.of("code", 500, "msg", "DynamicConfigService 未启用");
        }

        try {
            boolean success = dynamicConfigService.updateAlertConfig(updates);
            if (success) {
                return Map.of("code", 0, "msg", "告警配置已更新");
            } else {
                return Map.of("code", 500, "msg", "告警配置更新失败");
            }
        } catch (Exception e) {
            log.error("更新告警配置失败", e);
            return Map.of("code", 500, "msg", "更新失败: " + e.getMessage());
        }
    }

    /**
     * 重置配置为默认值
     * POST /api/config/reset
     * Body: {"type": "threadpool|ratelimit|alert|all"}
     */
    @PostMapping("/reset")
    public Map<String, Object> resetConfig(@RequestBody Map<String, String> body) {
        if (dynamicConfigService == null) {
            return Map.of("code", 500, "msg", "DynamicConfigService 未启用");
        }

        try {
            String type = body.getOrDefault("type", "all");
            boolean success = dynamicConfigService.resetConfig(type);
            if (success) {
                return Map.of("code", 0, "msg", "配置已重置: " + type);
            } else {
                return Map.of("code", 500, "msg", "配置重置失败");
            }
        } catch (Exception e) {
            log.error("重置配置失败", e);
            return Map.of("code", 500, "msg", "重置失败: " + e.getMessage());
        }
    }
}
