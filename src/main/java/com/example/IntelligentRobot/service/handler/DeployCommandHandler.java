package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.FeishuClient;
import com.example.IntelligentRobot.client.GitHubClient;
import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.service.ConfirmService;
import com.example.IntelligentRobot.service.NotificationService;
import com.example.IntelligentRobot.task.AsyncTaskStatus;
import com.example.IntelligentRobot.task.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
     * 飞书客户端（用于发送任务监控消息）
     */
    @Autowired(required = false)
    private FeishuClient feishuClient;

    /**
     * Spring 环境对象，用于读取配置
     */
    @Autowired
    private Environment environment;

    /**
     * Redis 模板，用于存储回调 token
     */
    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 部署环境映射配置
     * 格式：环境名 = owner/repo:工作流文件名:分支名
     */
    private Map<String, String> deployConfig;

    /**
     * 支持的部署目标列表
     */
    private static final java.util.Set<String> SUPPORTED_TARGETS = 
            java.util.Set.of("default", "server1", "server2", "aliyun", "aws", "tencent");

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
        usage = "/deploy <环境> [--target <目标>]"
    )
    public String handle(CommandContext context) {
        String operator = context.getSender() != null ? context.getSender().getOpenId() : "系统";
        String now = LocalDateTime.now(ZONE).format(FORMATTER);

        // 设置任务类型为部署（用于任务监控面板分类显示）
        String taskId = TaskContext.getTaskId();
        if (taskId != null) {
            try {
                AsyncTaskStatus.updateEventType(taskId, "deploy");
                log.info("部署任务事件类型已设置: taskId={}, eventType=deploy", taskId);
            } catch (Exception e) {
                log.warn("设置部署任务事件类型失败: taskId={}, error={}", taskId, e.getMessage());
            }
        }

        // 如果已确认，直接执行部署（从 filledParams 中获取 target）
        if (context.isConfirmed()) {
            String target = (String) context.getFilledParam("deploy_target");
            if (target == null) {
                // 从配置读取默认目标
                target = environment.getProperty("github.default-target", "default");
            }
            // 从 args 中获取环境（确认时 args 可能是 "dev|aliyun" 格式）
            String args = context.getArgs() != null ? context.getArgs().trim() : "";
            String normalizedEnv = args.split("\\|")[0].toLowerCase();
            return executeDeploy(context, normalizedEnv, target, operator, now);
        }

        // 解析参数：支持 /deploy <环境> [--target <目标>]
        String args = context.getArgs().trim();
        String chatId = context.getChatId();
        String openId = context.getSender() != null ? context.getSender().getOpenId() : null;

        // 解析环境和 target 参数
        String env;
        // 从配置读取默认目标，如果配置不存在则使用 "default"
        String target = environment.getProperty("github.default-target", "default");
        
        if (args.contains("--target")) {
            String[] parts = args.split("--target");
            env = parts[0].trim();
            if (parts.length > 1) {
                target = parts[1].trim().toLowerCase();
                // 验证 target 是否合法
                if (!SUPPORTED_TARGETS.contains(target)) {
                    return String.format("""
                            ❌ 部署目标无效：%s

                            📡 支持的部署目标：
                            %s

                            💡 示例：
                            `/deploy dev --target aliyun`
                            """, target, String.join(", ", SUPPORTED_TARGETS));
                }
            }
        } else {
            env = args;
        }
        
        String normalizedEnv = env.toLowerCase();

        // 更新任务状态：开始处理部署请求
        TaskContext.updateProgress(10, "开始处理部署请求");

        if (normalizedEnv.isEmpty()) {
            return buildDeployHelp();
        }

        // 二次确认：存储待确认操作（包含 target 信息）
        if (confirmService != null) {
            String targetInfo = "default".equals(target) ? "默认" : target;
            String summary = String.format("部署环境：%s，部署目标：%s，操作者：%s", 
                    normalizedEnv, targetInfo, maskOpenId(openId));
            // 将 target 存入 filledParams，确认时传递
            context.setFilledParam("deploy_target", target);
            String token = confirmService.storePendingAction(openId, chatId, "deploy",
                    normalizedEnv + "|" + target, summary);
            TaskContext.updateProgress(100, "等待用户确认");
            return String.format("""
                    ⚠️ 敏感操作确认

                    📦 操作：部署到 %s 环境
                    📡 部署目标：%s
                    👤 操作者：%s
                    🕐 时间：%s

                    ❗ 请输入以下命令确认部署：
                    `/deploy %s --confirm %s`

                    ⏰ 确认令牌有效期：5 分钟
                    💡 如需取消，请忽略此消息
                    """, normalizedEnv, targetInfo, operator, now, normalizedEnv, token);
        }

        // Redis 不可用，直接执行（降级）
        log.warn("ConfirmService 不可用，跳过二次确认，直接执行部署");
        return executeDeploy(context, normalizedEnv, target, operator, now);
    }

    /**
     * 实际执行部署（二次确认后调用）
     */
    private String executeDeploy(CommandContext context, String env, String target, String operator, String now) {
        String normalizedEnv = env.toLowerCase();

        // 发送部署任务开始消息
        String taskId = TaskContext.getTaskId();
        String chatId = context.getChatId();
        if (taskId != null && feishuClient != null && chatId != null) {
            try {
                String startMsg = buildDeployStartText(taskId, normalizedEnv, target);
                feishuClient.sendText(chatId, startMsg);
                log.info("部署任务开始消息已发送: taskId={}, chatId={}", taskId, chatId);
            } catch (Exception e) {
                log.warn("发送部署任务开始消息失败: taskId={}, error={}", taskId, e.getMessage());
            }

            // 标记此为部署任务，避免发送慢任务完成通知
            try {
                AsyncTaskStatus.setTaskType(taskId, "DEPLOY");
                log.info("已标记任务为部署任务: taskId={}", taskId);
            } catch (Exception e) {
                log.warn("标记部署任务类型失败: taskId={}, error={}", taskId, e.getMessage());
            }
        }

        // 更新任务状态：验证环境配置
        TaskContext.updateProgress(20, "验证部署环境配置");

        // 如果配置了 GitHub，实际触发部署
        if (gitHubClient != null && gitHubClient.isConfigured()) {
            try {
                // 关键节点1：30% - 开始触发部署
                TaskContext.updateProgress(30, "开始触发部署");

                DeployConfig config = getGitHubDeployConfig(normalizedEnv);

                // 生成唯一的部署 ID
                String deployId = generateDeployId(normalizedEnv, operator);

                // 构建 inputs，包含 target 参数
                Map<String, String> inputs = new java.util.HashMap<>();
                inputs.put("ref", config.branch());
                inputs.put("target", target);

                // 构建回调地址
                String callbackBaseUrl = environment.getProperty("github.callback.url");
                String callbackSecret = environment.getProperty("github.callback.secret");
                String callbackToken = java.util.UUID.randomUUID().toString().substring(0, 16);
                
                // 存储 callbackToken 到 Redis（用于验证回调请求）
                // Key 格式：deploy:callback:token:{deployId}
                // Value: callbackToken
                // 过期时间：24 小时
                if (stringRedisTemplate != null) {
                    String redisKey = "deploy:callback:token:" + deployId;
                    stringRedisTemplate.opsForValue().set(redisKey, callbackToken, 24, TimeUnit.HOURS);
                    log.info("回调 token 已存储到 Redis: deployId={}", deployId);

                    // 同时保存 deployId -> taskId 映射，供回调时更新卡片使用
                    String taskIdKey = "deploy:task:id:" + deployId;
                    stringRedisTemplate.opsForValue().set(taskIdKey, taskId, 24, TimeUnit.HOURS);
                    log.info("部署任务ID 已存储到 Redis: deployId={}, taskId={}", deployId, taskId);
                } else {
                    log.warn("StringRedisTemplate 不可用，无法存储回调 token");
                }
                
                String callbackUrl = null;
                if (callbackBaseUrl != null && !callbackBaseUrl.isEmpty()) {
                    callbackUrl = callbackBaseUrl + "?deployId=" + deployId + "&token=" + callbackToken;
                    log.info("部署回调地址: {}", callbackUrl);
                } else {
                    log.warn("未配置 github.callback.url，GitHub Actions 将无法回调通知部署结果");
                }
                
                // 触发工作流（传入 target 和 callbackUrl）
                Map<String, Object> resultMap = gitHubClient.triggerWorkflowWithCallback(
                        config.owner(),
                        config.repo(),
                        config.workflowId(),
                        config.branch(),
                        inputs,  // 传入 inputs，包含 target
                        deployId,
                        callbackUrl  // 传入回调地址
                );

                // 更新任务状态：部署已触发，飞书通知由工作流直接发送
                TaskContext.updateProgress(80, "部署已触发，飞书通知将由 GitHub Actions 直接发送");

                String targetInfo = "default".equals(target) ? "默认" : target;
                String successMsg = String.format("""
                        🚀 部署已触发

                        📦 环境：%s
                        📡 部署目标：%s
                        📂 仓库：%s/%s
                        🔧 工作流：%s
                        👤 操作者：%s
                        🕐 时间：%s
                        🆔 部署 ID：%s

                        📢 部署完成后将通过飞书通知您

                        🔗 GitHub Actions: %s/%s/actions
                        """, normalizedEnv, targetInfo, config.owner(), config.repo(),
                           config.workflowId(), operator, now, deployId,
                           config.owner(), config.repo());
                
                // 发送降噪通知（触发成功通知）
                sendDeployNotification(context, successMsg, normalizedEnv);
                
                return successMsg;
            } catch (Exception e) {
                log.error("GitHub Actions 部署失败", e);
                TaskContext.updateProgress(0, "部署失败: " + e.getMessage());
                
                String errorMsg = String.format("""
                        ❌ 部署失败

                        📦 环境：%s
                        📡 部署目标：%s
                        👤 操作者：%s

                        错误：%s

                        💡 请检查 GitHub 配置
                        """, normalizedEnv, target, operator, e.getMessage());
                
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
                    📡 部署目标：%s
                    👤 操作者：%s
                    🕐 时间：%s

                    ❗ 生产环境部署需要额外确认！
                    当前为模拟模式，未执行实际部署操作。

                    如需接入真实部署，请配置 github.token 并启用 GitHub 集成
                    """, target, operator, now);
        }

        String envLabel = switch (normalizedEnv) {
            case "dev" -> "开发环境";
            case "test" -> "测试环境";
            case "staging" -> "预发布环境";
            default -> normalizedEnv;
        };

        String targetInfo = "default".equals(target) ? "默认" : target;
        return String.format("""
                🚀 模拟部署流程

                📦 环境：%s（%s）
                📡 部署目标：%s
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
                """, normalizedEnv, envLabel, targetInfo, operator, now);
    }

    /**
     * 构建部署任务开始文本消息
     */
    private String buildDeployStartText(String taskId, String env, String target) {
        String taskIdShort = taskId.length() > 8 ? taskId.substring(0, 8) + "..." : taskId;
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return String.format("""
                🚀 部署任务已开始

                🆔 任务ID: %s
                📦 环境: %s
                📡 目标: %s
                📝 状态: 初始化中...
                🕐 开始时间: %s
                """, taskIdShort, env, target, timestamp);
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

                📡 可用部署目标（--target）：
                • default - 默认服务器
                • server1 - 服务器1
                • server2 - 服务器2
                • aliyun - 阿里云
                • aws - AWS
                • tencent - 腾讯云

                💡 示例：
                /deploy dev
                /deploy dev --target aliyun
                /deploy prod --target tencent

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
     * 生成唯一的部署 ID
     */
    private String generateDeployId(String env, String operator) {
        String timestamp = LocalDateTime.now(ZONE).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String random = java.util.UUID.randomUUID().toString().substring(0, 8);
        return String.format("deploy-%s-%s-%s", env, timestamp, random);
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
