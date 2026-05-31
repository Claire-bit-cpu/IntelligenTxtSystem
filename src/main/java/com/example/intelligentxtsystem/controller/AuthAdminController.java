package com.example.intelligentxtsystem.controller;

import com.example.intelligentxtsystem.dto.PermissionLevel;
import com.example.intelligentxtsystem.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 权限管理控制器
 * 提供权限名单的动态管理接口（通过 Redis 热更新，无需重启）
 *
 * 安全设计：
 *   这些接口本身需要 ADMIN 权限才能调用，
 *   但首次配置需要先在 application.yaml 中配置至少一个 admin Open ID。
 *
 * 接口列表：
 *   GET  /api/auth/users          - 查看当前权限名单
 *   POST /api/auth/users/add      - 添加用户到权限组
 *   DELETE /api/auth/users/{openId} - 移除用户权限
 *   POST /api/auth/users/check   - 检查指定用户权限
 */
@RestController
@RequestMapping("/api/auth")
public class AuthAdminController {

    private static final Logger log = LoggerFactory.getLogger(AuthAdminController.class);

    @Autowired
    private AuthService authService;

    /**
     * 查看当前权限名单
     * GET /api/auth/users
     */
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> getUsers() {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> permissions = authService.getAllPermissions();
        result.put("admin_open_ids", permissions.get("admin"));
        result.put("developer_open_ids", permissions.get("developer"));
        Map<String, Object> sources = new HashMap<>();
        sources.put("admin", permissions.get("admin_source"));
        sources.put("developer", permissions.get("developer_source"));
        result.put("sources", sources);
        result.put("note", "动态配置存储在 Redis，优先级高于 application.yaml");
        return ResponseEntity.ok(result);
    }

    /**
     * 添加用户到权限组
     * POST /api/auth/users/add
     * Body: { "open_id": "ou_xxx", "level": "admin" }
     */
    @PostMapping("/users/add")
    public ResponseEntity<Map<String, Object>> addUser(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        String openId = body.get("open_id");
        String levelStr = body.get("level");

        if (openId == null || openId.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "❌ open_id 不能为空");
            return ResponseEntity.badRequest().body(result);
        }
        if (levelStr == null) {
            levelStr = "developer"; // 默认添加到 developer 组
        }

        try {
            PermissionLevel level = PermissionLevel.valueOf(levelStr.toUpperCase());
            String msg = authService.addUserPermission(openId, level);
            boolean success = msg.startsWith("✅");
            result.put("success", success);
            result.put("message", msg);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", "❌ 无效的权限级别，可选值：ADMIN, DEVELOPER");
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 移除用户权限（从所有权限组中移除）
     * DELETE /api/auth/users/{openId}
     */
    @DeleteMapping("/users/{openId}")
    public ResponseEntity<Map<String, Object>> removeUser(@PathVariable("openId") String openId) {
        Map<String, Object> result = new HashMap<>();
        String msg = authService.removeUserPermission(openId);
        boolean success = msg.startsWith("✅");
        result.put("success", success);
        result.put("message", msg);
        return success ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    /**
     * 检查指定用户的权限级别
     * POST /api/auth/users/check
     * Body: { "open_id": "ou_xxx" }
     */
    @PostMapping("/users/check")
    public ResponseEntity<Map<String, Object>> checkUser(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        String openId = body.get("open_id");

        if (openId == null || openId.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "❌ open_id 不能为空");
            return ResponseEntity.badRequest().body(result);
        }

        result.put("open_id", openId);
        result.put("is_admin", authService.isAdmin(openId));
        result.put("is_developer", authService.isDeveloper(openId));
        result.put("permission_level", authService.getUserPermissionLevel(openId).name());
        result.put("has_any_permission", authService.hasPermission(openId));
        result.put("success", true);
        return ResponseEntity.ok(result);
    }
}
