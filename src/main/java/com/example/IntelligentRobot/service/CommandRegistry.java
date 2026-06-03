package com.example.IntelligentRobot.service;

import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.dto.CommandDefinition;
import com.example.IntelligentRobot.dto.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指令注册表
 * 管理所有已注册的指令
 *
 * 新增：支持上下文感知（多轮对话）
 */
@Component
public class CommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

    /**
     * 指令映射表（指令名 -> 指令定义）
     */
    private final Map<String, CommandDefinition> commands = new ConcurrentHashMap<>();

    /**
     * 上下文管理器
     */
    private final ContextManager contextManager;

    /**
     * 权限认证服务
     */
    private final AuthService authService;

    public CommandRegistry(ContextManager contextManager, AuthService authService) {
        this.contextManager = contextManager;
        this.authService = authService;
    }

    /**
     * 注册指令
     */
    public void register(CommandDefinition definition) {
        // 注册主指令名
        commands.put(definition.getName().toLowerCase(), definition);
        log.info("注册指令: /{} - {}", definition.getName(), definition.getDescription());

        // 注册别名
        for (String alias : definition.getAliases()) {
            commands.put(alias.toLowerCase(), definition);
            log.debug("注册指令别名: /{} -> /{}", alias, definition.getName());
        }
    }

    /**
     * 获取指令定义
     */
    public CommandDefinition getCommand(String name) {
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        return commands.get(name.toLowerCase());
    }

    /**
     * 执行指令（带上下文感知）
     *
     * 处理流程：
     * 1. 检查指令是否独立（independent=true），如果是则直接执行
     * 2. 检查指令是否支持上下文（supportsContext=true）
     * 3. 如果支持，尝试从 ContextManager 获取上下文并填充参数
     * 4. 执行指令
     * 5. 如果指令支持上下文，保存当前参数到上下文
     * 6. 如果切换到新指令，清空之前的局部上下文
     */
    public Object execute(String commandName, CommandContext context) {
        CommandDefinition definition = getCommand(commandName);
        if (definition == null) {
            throw new IllegalArgumentException("未知指令: " + commandName);
        }

        // 检查鉴权（使用 permissionLevel 字符串，兼容旧的 requiresAuth）
        String permissionLevelStr = definition.getPermissionLevel();
        PermissionLevel requiredLevel = PermissionLevel.NONE;
        try {
            requiredLevel = PermissionLevel.valueOf(permissionLevelStr.toUpperCase());
        } catch (Exception e) {
            requiredLevel = PermissionLevel.NONE;
        }
        // 兼容旧逻辑：requiresAuth=true 但 permissionLevel=NONE 时，默认为 DEVELOPER
        if (definition.isRequiresAuth() && requiredLevel == PermissionLevel.NONE) {
            requiredLevel = PermissionLevel.DEVELOPER;
        }
        if (requiredLevel != PermissionLevel.NONE) {
            // 使用新的 hasPermission(context, level) 方法，同时支持 open_id 和 user_id
            log.info("指令 {} 需要鉴权（级别: {}），检查用户权限", commandName, requiredLevel);

            if (!authService.hasPermission(context, requiredLevel)) {
                String permissionDeniedMsg = authService.checkPermissionWithMessage(context.getUserId(), requiredLevel);
                log.warn("权限拒绝: 用户无权执行指令 {}（需要 {} 权限）, userIds={}", 
                        commandName, requiredLevel, getContextUserIds(context));
                throw new SecurityException(permissionDeniedMsg != null ? permissionDeniedMsg : "权限不足");
            }
            log.debug("鉴权通过: command={}, level={}, userIds={}", commandName, requiredLevel, getContextUserIds(context));
        }

        // 获取用户ID
        String userId = context.getUserId();

        // 上下文类型（如果支持上下文感知）
        String contextType = null;

        // 1. 处理强独立性指令（如 /jira 1234）
        if (definition.isIndependent()) {
            log.debug("指令 {} 是强独立性指令，不继承上下文", commandName);
            // 清空之前的局部上下文（切换到新指令）
            contextManager.clearAllLocalContexts(userId);
            return definition.execute(context);
        }

        // 2. 处理上下文感知
        if (definition.isSupportsContext()) {
            // 获取上下文类型
            contextType = definition.getContextType();
            if (contextType == null || contextType.isEmpty()) {
                contextType = definition.getName();
            }

            // 设置上下文信息到 CommandContext
            context.setContextSupported(true);
            context.setContextType(contextType);

            // 尝试从上下文自动填充参数
            Map<String, Object> filledParams = context.getFilledParams();

            // 解析当前输入的参数
            Map<String, Object> currentParams = parseCurrentParams(context);

            // 自动填充参数（全局参数 + 局部上下文）
            filledParams = contextManager.autoFillParams(
                    userId,
                    contextType,
                    currentParams,
                    definition.getLocalParams()
            );

            // 更新 CommandContext
            context.setFilledParams(filledParams);
        } else {
            // 不支持上下文的指令，清空之前的局部上下文
            contextManager.clearAllLocalContexts(userId);
        }

        // 3. 执行指令
        Object result = definition.execute(context);

        // 4. 保存当前上下文（用于下一轮）
        // 必须在 handler 执行后保存，以便 pick up handler 设置的参数（如 city）
        if (definition.isSupportsContext() && contextType != null) {
            contextManager.setLocalContext(
                    userId,
                    contextType,
                    context.getFilledParams(),
                    definition.getContextTimeout()
            );
            log.info("指令 {} 保存上下文: type={}, params={}, userId={}", commandName, contextType, context.getFilledParams(), userId);
        }

        return result;
    }

    /**
     * 解析当前输入的参数
     * 子类可以重写此方法以实现复杂的参数解析
     *
     * 默认实现：将整个 args 作为 raw_input 参数
     * 建议在各 Handler 中自行解析参数并设置到 filledParams
     */
    protected Map<String, Object> parseCurrentParams(CommandContext context) {
        Map<String, Object> params = new ConcurrentHashMap<>();

        // 简单实现：将 args 作为 raw_input 参数
        if (context.getArgs() != null && !context.getArgs().trim().isEmpty()) {
            params.put("raw_input", context.getArgs().trim());
        }

        return params;
    }

    /**
     * 辅助方法：从 CommandContext 的 args 中解析键值对参数
     * 格式：-key value 或 --key value
     *
     * 示例：/command -city 北京 -limit 10
     * 结果：{city: "北京", limit: 10}
     */
    protected Map<String, Object> parseKeyValueParams(String args) {
        Map<String, Object> params = new ConcurrentHashMap<>();

        if (args == null || args.trim().isEmpty()) {
            return params;
        }

        String[] parts = args.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if ((part.startsWith("-") || part.startsWith("--")) && part.length() > 1) {
                String key = part.replaceFirst("^-+", "");
                if (i + 1 < parts.length && !parts[i + 1].startsWith("-")) {
                    params.put(key, parts[i + 1]);
                    i++; // 跳过值
                } else {
                    params.put(key, true); // 布尔标志
                }
            }
        }

        return params;
    }

    /**
     * 获取所有指令
     */
    public List<CommandDefinition> getAllCommands() {
        // 去重（别名会指向同一个定义）
        Set<CommandDefinition> uniqueCommands = new HashSet<>(commands.values());
        return new ArrayList<>(uniqueCommands);
    }

    /**
     * 获取所有指令（按名称排序）
     */
    public List<CommandDefinition> getAllCommandsSorted() {
        List<CommandDefinition> commands = getAllCommands();
        commands.sort(Comparator.comparing(CommandDefinition::getName));
        return commands;
    }

    /**
     * 检查指令是否存在
     */
    public boolean hasCommand(String name) {
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        return commands.containsKey(name.toLowerCase());
    }

    /**
     * 获取所有已注册的指令名（含别名）
     * 用于模糊匹配
     */
    public Set<String> getAllCommandNames() {
        return Collections.unmodifiableSet(commands.keySet());
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
     * 从 CommandContext 中提取所有用户 ID（用于日志）
     */
    private java.util.Set<String> getContextUserIds(CommandContext context) {
        java.util.Set<String> userIds = new java.util.HashSet<>();
        if (context == null) return userIds;
        
        String userId = context.getUserId();
        if (userId != null && !userId.isEmpty() && !"default".equals(userId)) {
            userIds.add(maskOpenId(userId));
        }
        
        if (context.getSender() != null) {
            String openId = context.getSender().getOpenId();
            if (openId != null && !openId.isEmpty()) {
                userIds.add(maskOpenId(openId));
            }
            
            String senderUserId = context.getSender().getUserId();
            if (senderUserId != null && !senderUserId.isEmpty()) {
                userIds.add(maskOpenId(senderUserId));
            }
        }
        
        return userIds;
    }

    /**
     * 生成帮助文档
     */
    public String generateHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("📖 指令列表\n\n");

        List<CommandDefinition> commands = getAllCommandsSorted();
        for (CommandDefinition cmd : commands) {
            sb.append(String.format("**/%s**\n", cmd.getName()));
            if (!cmd.getDescription().isEmpty()) {
                sb.append(String.format("  %s\n", cmd.getDescription()));
            }
            if (!cmd.getUsage().isEmpty()) {
                sb.append(String.format("  用法: %s\n", cmd.getUsage()));
            }
            // 显示权限级别（NONE 不显示）
            String levelStr = cmd.getPermissionLevel();
            if (!"NONE".equalsIgnoreCase(levelStr)) {
                String levelLabel = switch (levelStr.toUpperCase()) {
                    case "DEVELOPER" -> "🔑 需要开发者权限";
                    case "ADMIN" -> "🔐 需要管理员权限";
                    default -> "";
                };
                if (!levelLabel.isEmpty()) {
                    sb.append("  ").append(levelLabel).append("\n");
                }
            }
            // 兼容旧逻辑：requiresAuth=true 但 permissionLevel=NONE 时显示
            if (cmd.isRequiresAuth() && "NONE".equalsIgnoreCase(levelStr)) {
                sb.append("  ⚠️ 需要鉴权（兼容模式）\n");
            }
            if (cmd.isSupportsContext()) {
                sb.append("  💡 支持上下文感知（多轮对话）\n");
            }
            if (cmd.isIndependent()) {
                sb.append("  🔒 强独立性指令（不继承上下文）\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
