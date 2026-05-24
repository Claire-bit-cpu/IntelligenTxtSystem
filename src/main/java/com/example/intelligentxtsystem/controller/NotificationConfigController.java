package com.example.intelligentxtsystem.controller;

import com.example.intelligentxtsystem.service.NotificationConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知群聊配置管理接口
 * 
 * 设计原则：
 * 1. 群聊ID必须来自飞书事件推送（自动注册，默认禁用）
 * 2. 人工只能配置"是否启用"和"接收什么通知"
 */
@RestController
@RequestMapping("/api/notification/config")
public class NotificationConfigController {

    private static final Logger log = LoggerFactory.getLogger(NotificationConfigController.class);

    @Autowired
    private NotificationConfigService configService;

    /**
     * 业务类型常量
     */
    public static class BusinessType {
        public static final String ALL = "all";                      // 所有通知
        public static final String SYNC_STATUS = "sync_status";      // 同步任务状态通知
        public static final String CONTENT_UPDATE = "content_update"; // 内容更新与变更提醒
        public static final String SYSTEM_MAINTENANCE = "system_maintenance"; // 系统维护通知
        public static final String WORKFLOW = "workflow";            // 流程协作通知
    }

    /**
     * 查询所有群聊配置（包括已禁用和未配置的）
     */
    @GetMapping("/chats")
    public ResponseEntity<?> getAllChatConfigs() {
        try {
            List<NotificationConfigService.ChatConfig> configs = configService.getAllChatConfigs();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", configs,
                    "message", "查询成功"
            ));
        } catch (Exception e) {
            log.error("查询所有群聊配置失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "查询失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 查询启用的群聊配置
     */
    @GetMapping("/chats/enabled")
    public ResponseEntity<?> getEnabledChatConfigs() {
        try {
            List<NotificationConfigService.ChatConfig> configs = configService.getEnabledChatConfigs();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", configs,
                    "message", "查询成功"
            ));
        } catch (Exception e) {
            log.error("查询启用的群聊配置失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "查询失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 根据业务类型查询群聊配置
     */
    @GetMapping("/chats/by-business-type")
    public ResponseEntity<?> getChatConfigsByBusinessType(@RequestParam String businessType) {
        try {
            List<NotificationConfigService.ChatConfig> configs = configService.getEnabledChatConfigsByBusinessType(businessType);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", configs,
                    "message", "查询成功"
            ));
        } catch (Exception e) {
            log.error("查询业务类型群聊配置失败: businessType={}", businessType, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "查询失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 更新群聊配置（人工配置"是否启用"和"接收什么通知"）
     * 
     * 请求体可以包含：
     * - chatName: 群聊名称（可选）
     * - businessType: 业务类型（可选）
     * - description: 描述（可选）
     * - enabled: 是否启用（可选）
     */
    @PutMapping("/chats/{id}")
    public ResponseEntity<?> updateChatConfig(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        try {
            String chatName = (String) request.get("chatName");
            String businessType = (String) request.get("businessType");
            String description = (String) request.get("description");
            Boolean enabled = (Boolean) request.get("enabled");

            boolean success = configService.updateChatConfig(id, chatName, businessType, description, enabled);
            if (success) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "更新成功"
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "更新失败，配置不存在"
                ));
            }
        } catch (Exception e) {
            log.error("更新群聊配置失败: id={}", id, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "更新失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 删除群聊配置
     */
    @DeleteMapping("/chats/{id}")
    public ResponseEntity<?> deleteChatConfig(@PathVariable Long id) {
        try {
            boolean success = configService.deleteChatConfig(id);
            if (success) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "删除成功"
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "删除失败，配置不存在"
                ));
            }
        } catch (Exception e) {
            log.error("删除群聊配置失败: id={}", id, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "删除失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 启用群聊配置
     */
    @PostMapping("/chats/{id}/enable")
    public ResponseEntity<?> enableChatConfig(@PathVariable Long id) {
        try {
            boolean success = configService.enableChatConfig(id);
            if (success) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "启用成功"
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "启用失败，配置不存在"
                ));
            }
        } catch (Exception e) {
            log.error("启用群聊配置失败: id={}", id, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "启用失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 禁用群聊配置
     */
    @PostMapping("/chats/{id}/disable")
    public ResponseEntity<?> disableChatConfig(@PathVariable Long id) {
        try {
            boolean success = configService.disableChatConfig(id);
            if (success) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "禁用成功"
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "禁用失败，配置不存在"
                ));
            }
        } catch (Exception e) {
            log.error("禁用群聊配置失败: id={}", id, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "禁用失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 获取支持的业务类型列表
     */
    @GetMapping("/business-types")
    public ResponseEntity<?> getBusinessTypes() {
        try {
            List<Map<String, String>> types = new ArrayList<>();
            types.add(Map.of("value", BusinessType.ALL, "label", "所有通知"));
            types.add(Map.of("value", BusinessType.SYNC_STATUS, "label", "同步任务状态通知"));
            types.add(Map.of("value", BusinessType.CONTENT_UPDATE, "label", "内容更新与变更提醒"));
            types.add(Map.of("value", BusinessType.SYSTEM_MAINTENANCE, "label", "系统维护通知"));
            types.add(Map.of("value", BusinessType.WORKFLOW, "label", "流程协作通知"));
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", types,
                    "message", "查询成功"
            ));
        } catch (Exception e) {
            log.error("查询业务类型失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "查询失败: " + e.getMessage()
            ));
        }
    }
}
