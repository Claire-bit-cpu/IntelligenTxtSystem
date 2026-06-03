package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.GitHubClient;
import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.service.ConfirmService;
import com.example.IntelligentRobot.service.NotificationService;
import com.example.IntelligentRobot.task.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 部署指令处理器（新框架版本）
 * 指令格式：/deploy <环境>
 */
@Component
public class DeployCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(DeployCommandHandler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired(required = false)
    private GitHubClient gitHubClient;

    /**
     * 二次确认服务
     */
    @Autowired(required = false)
    private ConfirmService confirmService;

    /**
     * 通知服务（智能降噪）
     */
    @Autowired(required = false)
    private NotificationService notificationService;

    /**
     * Spring 环境对象，用于读取配置
     */
    @Autowired
    private Environment environment;

    /**
     * 部署环境映射配置
     * 格式：环境名 = owner/repo:工作流文件名:分支名
     */
    private Map<String, String> deployConfig;

    @jakarta.annotation.PostConstruct
    public void init() {
        deployConfig = loadDeployConfig();
    }

    /**
     * 从 Environment 加载部署配置
     */
    private Map<String, String> loadDeployConfig() {
        Map<String, String> configMap = new java.util.HashMap<>();
        
        String configStr = environment.getProperty("github.deploy");
        
        if (configStr == null || configStr.isEmpty()) {
            log.warn("未找到 github.deploy 配置，将使用默认配置");
            return configMap;
        }

        log.info("加载部署配置: {}", configStr);
        
        String[] pairs = configStr.split(",");
        for (String pair : pairs) {
            String[] keyValue = pair.split(":", 2);
            if (keyValue.length == 2) {
                String key = keyValue[0].trim();
                String value = keyValue[1].trim();
                configMap.put(key, value);
                log.debug("加载部署配置: {} = {}", key, value);
            }
        }
        
        return configMap;
    }

    @Command(
        name = "deploy",
        description = "触发部署流程（需二次确认）",
        permissionLevel = "DEVELOPER",
        usage = "/deploy <环境>"
    )
    public String handle(CommandContext context) {
        String env = context.getArgs().trim();
        String operator = context.getSender() != null ? context.getSender().getOpenId() : "系统";
        String now = LocalDateTime.now(ZONE).format(FORMATTER);
        String chatId = context.getChatId();
        String openId = context.getSender() != null ? context.getSender().getOpenId() : null;

        // 如果已确认，直接执行部署
        if (context.isConfirmed()) {
            return executeDeploy(context, env, operator, now);
        }

        // 更新任务状态：开始处理部署
        TaskContext.updateProgress(10, "开始处理部署请求");

        if (env.isEmpty()) {
            return buildDeployHelp();
        }

        String normalizedEnv = env.toLowerCase();

        // 二次确认：存储待确认操作
        if (confirmService != null) {
            String summary = String.format("部署环境：%s，操作者：%s", normalizedEnv, maskOpenId(openId));
            String token = confirmService.storePendingAction(openId, chatId, "deploy", normalizedEnv, summary);
            TaskContext.updateProgress(100, "等待用户确认");
            return String.format("""
                    ⚠️ 敏感操作确认

                    📦 操作：部署到 %s 环境
                    👤 操作者：%s
                    🕐 时间：%s

                    ❗ 请输入以下命令确认部署：
                    `/deploy %s --confirm %s`

                    ⏰ 确认令牌有效期：5 分钟
                    💡 如需取消，请忽略此消息
                    """, normalizedEnv, operator, now, normalizedEnv, token);
        }

        // Redis 不可用，直接执行（降级）
        log.warn("ConfirmService 不可用，跳过二次确认，直接执行部署");
        return executeDeploy(context, env, operator, now);
    }

    /**
     * 实际执行部署（二次确认后调用）
     */
    private String executeDeploy(CommandContext context, String env, String operator, String now) {
        String normalizedEnv = env.toLowerCase();

        // 更新任务状态：验证环境配置
        TaskContext.updateProgress(30, "验证部署环境配置");

        // 如果配置了 GitHub，实际触发部署
        if (gitHubClient != null && gitHubClient.isConfigured()) {
            try {
                // 更新任务状态：获取部署配置
                TaskContext.updateProgress(50, "获取部署配置");

                DeployConfig config = getGitHubDeployConfig(normalizedEnv);

                // 更新任务状态：触发 GitHub Actions 工作流
                TaskContext.updateProgress(70, "触发 GitHub Actions 工作流");

                String result = gitHubClient.triggerWorkflow(
                        config.owner(), 
                        config.repo(), 
                        config.workflowId(), 
                        config.branch(), 
                        null
                );

                // 更新任务状态：部署已触发
                TaskContext.updateProgress(90, "部署已触发，等待完成");

                String successMsg = String.format("""
                        🚀 部署已触发

                        📦 环境：%s
                        📂 仓库：%s/%s
                        🔧 工作流：%s
                        👤 操作者：%s
                        🕐 时间：%s

                        %s

                        🔗 GitHub Actions: %s/%s/actions
                        """, normalizedEnv, config.owner(), config.repo(), 
                           config.workflowId(), operator, now, result,
                           config.owner(), config.repo());
                
                // 发送降噪通知
                sendDeployNotification(context, successMsg, normalizedEnv);
                
                return successMsg;
            } catch (Exception e) {
                log.error("GitHub Actions 部署失败", e);
                TaskContext.updateProgress(0, "部署失败: " + e.getMessage());
                
                String errorMsg = String.format("""
                        ❌ 部署失败

                        📦 环境：%s
                        👤 操作者：%s

                        错误：%s

                        💡 请检查 GitHub 配置
                        """, normalizedEnv, operator, e.getMessage());
                
                // 发送降噪通知
                sendDeployNotification(context, errorMsg, normalizedEnv);
                return errorMsg;
            }
        }

        // 模拟模式
        if (!normalizedEnv.matches("^(dev|test|staging|prod|production)$")) {
            return buildDeployHelp();
        }

        boolean isProd = normalizedEnv.equals("prod") || normalizedEnv.equals("production");

        if (isProd) {
            return String.format("""
                    ⚠️ 生产环境部署确认

                    📦 环境：production
                    👤 操作者：%s
                    🕐 时间：%s

                    ❗ 生产环境部署需要额外确认！
                    当前为模拟模式，未执行实际部署操作。

                    如需接入真实部署，请配置 github.token 并启用 GitHub 集成
                    """, operator, now);
        }

        String envLabel = switch (normalizedEnv) {
            case "dev" -> "开发环境";
            case "test" -> "测试环境";
            case "staging" -> "预发布环境";
            default -> normalizedEnv;
        };

        return String.format("""
                🚀 模拟部署流程

                📦 环境：%s（%s）
                👤 操作者：%s
                🕐 时间：%s

                📋 模拟步骤：
                1. 代码拉取 ✓
                2. 构建打包 ✓
                3. 部署服务 ✓
                4. 健康检查 ✓

                ✅ 模拟部署完成

                💡 当前为模拟模式。
                如需接入真实部署，请配置 GitHub Actions 集成。
                """, normalizedEnv, envLabel, operator, now);
    }

    /**
     * 部署配置记录
     */
    private record DeployConfig(String owner, String repo, String workflowId, String branch) {}

    /**
     * 根据环境获取 GitHub 部署配置
     */
    private DeployConfig getGitHubDeployConfig(String env) {
        if (deployConfig != null && deployConfig.containsKey(env)) {
            String config = deployConfig.get(env);
            try {
                String[] parts = config.split(":");
                if (parts.length >= 3) {
                    String[] repoParts = parts[0].split("/", 2);
                    if (repoParts.length == 2) {
                        return new DeployConfig(repoParts[0], repoParts[1], parts[1], parts[2]);
                    }
                }
                log.warn("部署配置格式错误: {} = {}", env, config);
            } catch (Exception e) {
                log.error("解析部署配置失败: {}", config, e);
            }
        }
        
        return switch (env) {
            case "dev" -> new DeployConfig("Claire-bit-cpu", "Test", "deploy-dev.yml", "develop");
            case "test" -> new DeployConfig("Claire-bit-cpu", "Test", "deploy-test.yml", "test");
            case "staging" -> new DeployConfig("Claire-bit-cpu", "Test", "deploy-staging.yml", "staging");
            case "prod", "production" -> new DeployConfig("Claire-bit-cpu", "Test", "deploy-prod.yml", "main");
            default -> new DeployConfig("Claire-bit-cpu", "Test", "deploy-" + env + ".yml", "main");
        };
    }

    private String buildDeployHelp() {
        return """
                ❌ 部署环境无效

                📦 可用环境：
                • dev - 开发环境
                • test - 测试环境
                • staging - 预发布环境
                • prod / production - 生产环境

                💡 示例：
                /deploy test
                /deploy prod

                🔧 高级用法（直接触发 GitHub Actions）：
                /github workflow <仓库> <工作流文件> <分支>
                /github status <仓库> <run-id>
                """;
    }

    /**
     * 脱敏 OpenId（日志用）
     */
    private String maskOpenId(String openId) {
        if (openId == null || openId.length() < 8) return "***";
        return openId.substring(0, 4) + "***" + openId.substring(openId.length() - 4);
    }

    /**
     * 发送部署通知（带智能降噪）
     */
    private void sendDeployNotification(CommandContext context, String message, String env) {
        String chatId = context.getChatId();
        if (chatId == null || chatId.isEmpty()) {
            return;
        }

        try {
            if (notificationService != null) {
                // 使用 DEPLOY 事件类型，启用智能降噪（去重 + 合并）
                boolean success = notificationService.sendNotification(chatId, "DEPLOY", message);
                if (success) {
                    log.info("部署通知已发送（带降噪）: env={}, chatId={}", env, maskOpenId(chatId));
                } else {
                    log.info("部署通知被降噪拦截（去重或合并中）: env={}", env);
                }
            } else {
                // NotificationService 不可用，降级处理
                log.warn("NotificationService 不可用，部署通知未启用智能降噪");
            }
        } catch (Exception e) {
            log.error("发送部署通知失败", e);
        }
    }
}
