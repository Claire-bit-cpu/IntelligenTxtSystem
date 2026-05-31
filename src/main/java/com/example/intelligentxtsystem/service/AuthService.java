/*
 * 通用权限认证服务
 * 提供指令级别的权限控制
 */
package com.example.intelligentxtsystem.service;

import com.example.intelligentxtsystem.dto.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 认证服务
 * 优先从 Redis 动态配置读取权限白名单，fallback 到 application.yaml 静态配置
 *
 * 动态配置 Key（存储在 Redis）：
 *   config:dynamic:auth:admin-open-ids    - 管理员名单（逗号分隔）
 *   config:dynamic:auth:developer-open-ids - 开发者名单（逗号分隔）
 *
 * 静态配置（application.yaml）：
 *   github:
 *     admin-open-ids: "ou_xxx,ou_yyy"
 *     developer-open-ids: "ou_xxx,ou_zzz"
 *
 * 管理接口：
 *   GET  /api/auth/users          - 查看权限名单
 *   POST /api/auth/users          - 添加用户到权限组
 *   DELETE /api/auth/users/{openId} - 移除用户权限
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String DYNAMIC_CONFIG_KEY_ADMIN = "auth:admin-open-ids";
    private static final String DYNAMIC_CONFIG_KEY_DEVELOPER = "auth:developer-open-ids";

    @Value("${github.admin-open-ids:}")
    private String adminOpenIdsConfig;

    @Value("${github.developer-open-ids:}")
    private String developerOpenIdsConfig;

    @Autowired(required = false)
    private DynamicConfigService dynamicConfigService;

    /**
     * 获取管理员名单（优先从 Redis 动态配置读取，fallback 到静态配置）
     */
    private String getAdminOpenIdsConfig() {
        if (dynamicConfigService != null) {
            Object value = dynamicConfigService.getConfigValue(DYNAMIC_CONFIG_KEY_ADMIN);
            if (value != null && !value.toString().trim().isEmpty()) {
                return value.toString();
            }
        }
        return adminOpenIdsConfig != null ? adminOpenIdsConfig : "";
    }

    /**
     * 获取开发者名单（优先从 Redis 动态配置读取，fallback 到静态配置）
     */
    private String getDeveloperOpenIdsConfig() {
        if (dynamicConfigService != null) {
            Object value = dynamicConfigService.getConfigValue(DYNAMIC_CONFIG_KEY_DEVELOPER);
            if (value != null && !value.toString().trim().isEmpty()) {
                return value.toString();
            }
        }
        return developerOpenIdsConfig != null ? developerOpenIdsConfig : "";
    }

    /**
     * 检查用户是否为管理员
     * @param openId 飞书用户 Open ID
     * @return true 如果是管理员
     */
    public boolean isAdmin(String openId) {
        if (openId == null || openId.isEmpty() || "default".equals(openId)) {
            return false;
        }
        String config = getAdminOpenIdsConfig();
        boolean result = isInCommaSeparatedList(openId, config);
        log.debug("管理员权限检查: openId={}, result={}, source={}", maskOpenId(openId), result,
                dynamicConfigService != null && dynamicConfigService.getConfigValue(DYNAMIC_CONFIG_KEY_ADMIN) != null ? "redis" : "static");
        return result;
    }

    /**
     * 检查用户是否为开发者或管理员
     * 管理员自动拥有开发者权限
     * @param openId 飞书用户 Open ID
     * @return true 如果是开发者或管理员
     */
    public boolean isDeveloper(String openId) {
        if (openId == null || openId.isEmpty() || "default".equals(openId)) {
            return false;
        }
        // 管理员自动拥有开发者权限
        if (isAdmin(openId)) {
            return true;
        }
        String config = getDeveloperOpenIdsConfig();
        boolean result = isInCommaSeparatedList(openId, config);
        log.debug("开发者权限检查: openId={}, result={}, source={}", maskOpenId(openId), result,
                dynamicConfigService != null && dynamicConfigService.getConfigValue(DYNAMIC_CONFIG_KEY_DEVELOPER) != null ? "redis" : "static");
        return result;
    }

    /**
     * 检查用户是否有指定权限级别的访问权限
     * @param openId 飞书用户 Open ID
     * @param level 需要的权限级别
     * @return true 如果有权限
     */
    public boolean hasPermission(String openId, PermissionLevel level) {
        return switch (level) {
            case NONE -> true;
            case DEVELOPER -> isDeveloper(openId);
            case ADMIN -> isAdmin(openId);
        };
    }

    /**
     * 检查用户是否可以执行需要鉴权的指令
     * 兼容原有 requiresAuth 逻辑：需要 admin 或 developer 权限
     * @param openId 飞书用户 Open ID
     * @return true 如果有权限
     */
    public boolean hasPermission(String openId) {
        // 检查白名单是否已配置（动态 + 静态）
        String adminConfig = getAdminOpenIdsConfig();
        String devConfig = getDeveloperOpenIdsConfig();
        if ((adminConfig == null || adminConfig.trim().isEmpty()) &&
            (devConfig == null || devConfig.trim().isEmpty())) {
            log.warn("权限白名单未配置（admin-open-ids 和 developer-open-ids 均为空），拒绝所有鉴权请求");
            return false;
        }
        return isAdmin(openId) || isDeveloper(openId);
    }

    // ==================== 动态权限管理（Redis 热更新）====================

    /**
     * 获取当前所有权限名单（合并动态+静态配置）
     * @return 包含 admin 和 developer 名单的 Map
     */
    public java.util.Map<String, Object> getAllPermissions() {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("admin", getAdminOpenIdsConfig());
        result.put("developer", getDeveloperOpenIdsConfig());
        result.put("admin_source", dynamicConfigService != null
                && dynamicConfigService.getConfigValue(DYNAMIC_CONFIG_KEY_ADMIN) != null ? "redis" : "static");
        result.put("developer_source", dynamicConfigService != null
                && dynamicConfigService.getConfigValue(DYNAMIC_CONFIG_KEY_DEVELOPER) != null ? "redis" : "static");
        return result;
    }

    /**
     * 添加用户到权限组
     * @param openId   用户 Open ID
     * @param level    权限级别（admin 或 developer）
     * @return 操作结果消息
     */
    public String addUserPermission(String openId, PermissionLevel level) {
        if (openId == null || openId.trim().isEmpty()) {
            return "❌ 用户 Open ID 不能为空";
        }
        String key;
        if (level == PermissionLevel.ADMIN) {
            key = DYNAMIC_CONFIG_KEY_ADMIN;
        } else if (level == PermissionLevel.DEVELOPER) {
            key = DYNAMIC_CONFIG_KEY_DEVELOPER;
        } else {
            return "❌ 无需将用户添加到 NONE 权限组";
        }
        try {
            // 获取当前名单
            String current = "";
            if (dynamicConfigService != null) {
                Object val = dynamicConfigService.getConfigValue(key);
                if (val != null) current = val.toString();
            }
            // 去重添加
            Set<String> ids = new HashSet<>(Arrays.asList(current.split(",")));
            ids.remove(""); // 移除空字符串
            if (ids.contains(openId)) {
                return "⚠️ 用户 " + maskOpenId(openId) + " 已在 " + level.name() + " 权限组中";
            }
            ids.add(openId);
            String newValue = String.join(",", ids);
            if (dynamicConfigService != null) {
                dynamicConfigService.setConfigValue(key, newValue);
            } else {
                return "❌ DynamicConfigService 不可用，无法动态更新权限（请配置 Redis）";
            }
            log.info("已添加用户到 {} 权限组: {}", level.name(), maskOpenId(openId));
            return "✅ 已添加用户 " + maskOpenId(openId) + " 到 " + level.name() + " 权限组";
        } catch (Exception e) {
            log.error("添加用户权限失败", e);
            return "❌ 添加失败: " + e.getMessage();
        }
    }

    /**
     * 从权限组中移除用户
     * @param openId 用户 Open ID
     * @return 操作结果消息
     */
    public String removeUserPermission(String openId) {
        if (openId == null || openId.trim().isEmpty()) {
            return "❌ 用户 Open ID 不能为空";
        }
        try {
            int removedCount = 0;
            // 从 admin 组移除
            removedCount += removeFromGroup(openId, DYNAMIC_CONFIG_KEY_ADMIN) ? 1 : 0;
            // 从 developer 组移除
            removedCount += removeFromGroup(openId, DYNAMIC_CONFIG_KEY_DEVELOPER) ? 1 : 0;
            if (removedCount > 0) {
                return "✅ 已从权限组中移除用户 " + maskOpenId(openId);
            } else {
                return "⚠️ 用户 " + maskOpenId(openId) + " 不在任何权限组中";
            }
        } catch (Exception e) {
            log.error("移除用户权限失败", e);
            return "❌ 移除失败: " + e.getMessage();
        }
    }

    /**
     * 从指定权限组中移除用户
     */
    private boolean removeFromGroup(String openId, String key) {
        if (dynamicConfigService == null) return false;
        Object val = dynamicConfigService.getConfigValue(key);
        if (val == null) return false;
        Set<String> ids = new HashSet<>(Arrays.asList(val.toString().split(",")));
        ids.remove("");
        if (ids.remove(openId)) {
            String newValue = String.join(",", ids);
            dynamicConfigService.setConfigValue(key, newValue);
            log.info("已从 {} 权限组移除用户: {}", key, maskOpenId(openId));
            return true;
        }
        return false;
    }

    /**
     * 获取用户权限级别
     * @param openId 飞书用户 Open ID
     * @return 权限级别
     */
    public PermissionLevel getUserPermissionLevel(String openId) {
        if (isAdmin(openId)) {
            return PermissionLevel.ADMIN;
        }
        if (isDeveloper(openId)) {
            return PermissionLevel.DEVELOPER;
        }
        return PermissionLevel.NONE;
    }

    /**
     * 验证用户权限，如果无权限则抛出 SecurityException
     * @param openId 飞书用户 Open ID
     * @param level 需要的权限级别
     * @throws SecurityException 如果无权限
     */
    public void requirePermission(String openId, PermissionLevel level) throws SecurityException {
        if (!hasPermission(openId, level)) {
            String errorMsg = String.format("权限不足：用户 %s 需要 %s 权限才能执行此操作",
                    maskOpenId(openId), level.name());
            log.warn("权限拒绝: openId={}, required={}, actual={}",
                    maskOpenId(openId), level.name(), getUserPermissionLevel(openId).name());
            throw new SecurityException(errorMsg);
        }
    }

    /**
     * 检查用户是否有权限执行指令，返回友好错误信息
     * @param openId 飞书用户 Open ID
     * @param level 需要的权限级别
     * @return 如果有权限返回 null，否则返回错误提示信息
     */
    public String checkPermissionWithMessage(String openId, PermissionLevel level) {
        if (hasPermission(openId, level)) {
            return null;
        }
        if (openId == null || openId.isEmpty() || "default".equals(openId)) {
            return "❌ 无法识别用户身份，请确保通过飞书机器人发送指令。";
        }
        return switch (level) {
            case ADMIN ->
                "❌ 权限不足：此指令仅管理员可执行。\n\n" +
                "如需申请权限，请联系系统管理员。";
            case DEVELOPER ->
                "❌ 权限不足：此指令需要开发者或以上权限。\n\n" +
                "如需申请权限，请联系系统管理员。";
            case NONE -> null;
        };
    }

    /**
     * 脱敏 Open ID（用于日志）
     */
    private String maskOpenId(String openId) {
        if (openId == null || openId.length() < 8) {
            return "***";
        }
        return openId.substring(0, 4) + "***" + openId.substring(openId.length() - 4);
    }

    /**
     * 检查目标字符串是否在逗号分隔的列表中
     */
    private boolean isInCommaSeparatedList(String target, String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return false;
        }
        Set<String> set = new HashSet<>();
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(set::add);
        return set.contains(target);
    }
}
