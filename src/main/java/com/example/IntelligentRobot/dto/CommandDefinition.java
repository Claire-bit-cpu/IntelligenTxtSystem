package com.example.IntelligentRobot.dto;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 指令定义
 * 包含指令的元数据和执行方法
 *
 * 新增：支持上下文感知
 */
public class CommandDefinition {

    /**
     * 指令名称
     */
    private String name;

    /**
     * 指令描述
     */
    private String description;

    /**
     * 是否需要鉴权（已废弃，保留兼容）
     * @deprecated 使用 permissionLevel 代替
     */
    private boolean requiresAuth;

    /**
     * 权限级别（字符串形式，与 @Command 注解保持一致）
     * "NONE"      - 无需鉴权
     * "DEVELOPER" - 需要开发者权限
     * "ADMIN"     - 需要管理员权限
     */
    private String permissionLevel = "NONE";

    /**
     * 指令别名
     */
    private String[] aliases;

    /**
     * 使用帮助信息
     */
    private String usage;

    /**
     * 执行方法
     */
    private Method method;

    /**
     * 方法所在的 Bean 对象
     */
    private Object bean;

    /**
     * 是否支持上下文感知（多轮对话）
     */
    private boolean supportsContext = false;

    /**
     * 上下文类型标识
     */
    private String contextType = "";

    /**
     * 全局参数列表
     */
    private String[] globalParams = {};

    /**
     * 局部参数列表
     */
    private String[] localParams = {};

    /**
     * 上下文超时时间（分钟）
     */
    private int contextTimeout = 5;

    /**
     * 是否强独立性指令
     */
    private boolean independent = false;

    // ===== Getter / Setter =====

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isRequiresAuth() { return requiresAuth; }
    public void setRequiresAuth(boolean requiresAuth) { this.requiresAuth = requiresAuth; }

    public String getPermissionLevel() { return permissionLevel; }
    public void setPermissionLevel(String permissionLevel) { this.permissionLevel = permissionLevel; }

    public String[] getAliases() { return aliases; }
    public void setAliases(String[] aliases) { this.aliases = aliases; }

    public String getUsage() { return usage; }
    public void setUsage(String usage) { this.usage = usage; }

    public Method getMethod() { return method; }
    public void setMethod(Method method) { this.method = method; }

    public Object getBean() { return bean; }
    public void setBean(Object bean) { this.bean = bean; }

    public boolean isSupportsContext() { return supportsContext; }
    public void setSupportsContext(boolean supportsContext) { this.supportsContext = supportsContext; }

    public String getContextType() { return contextType; }
    public void setContextType(String contextType) { this.contextType = contextType; }

    public String[] getGlobalParams() { return globalParams; }
    public void setGlobalParams(String[] globalParams) { this.globalParams = globalParams; }

    public String[] getLocalParams() { return localParams; }
    public void setLocalParams(String[] localParams) { this.localParams = localParams; }

    public int getContextTimeout() { return contextTimeout; }
    public void setContextTimeout(int contextTimeout) { this.contextTimeout = contextTimeout; }

    public boolean isIndependent() { return independent; }
    public void setIndependent(boolean independent) { this.independent = independent; }

    /**
     * 获取权限级别枚举（供外部调用）
     */
    public PermissionLevel getPermissionLevelEnum() {
        try {
            return PermissionLevel.valueOf(permissionLevel.toUpperCase());
        } catch (Exception e) {
            return PermissionLevel.NONE;
        }
    }

    /**
     * 执行指令
     */
    public Object execute(CommandContext context) {
        try {
            return method.invoke(bean, context);
        } catch (Exception e) {
            // 将受检异常转换为运行时异常
            Throwable cause = e.getCause(); // 获取 InvocationTargetException 的根本原因
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("指令执行失败: " + method.getName(), cause != null ? cause : e);
        }
    }

    /**
     * 检查是否匹配该指令（包括别名）
     */
    public boolean matches(String commandName) {
        if (this.name.equalsIgnoreCase(commandName)) {
            return true;
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias.equalsIgnoreCase(commandName)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "CommandDefinition{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", permissionLevel='" + permissionLevel + '\'' +
                ", aliases=" + Arrays.toString(aliases) +
                '}';
    }
}
