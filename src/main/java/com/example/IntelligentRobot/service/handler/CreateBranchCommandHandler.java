package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.GitHubClient;
import com.example.IntelligentRobot.config.GitHubConfig;
import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.service.ConfirmService;
import com.example.IntelligentRobot.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 创建 Git 分支指令处理器（新框架版本）
 * 指令格式：/createbranch <仓库别名> <新分支名> [源分支]
 */

@Component
public class CreateBranchCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CreateBranchCommandHandler.class);

    private final GitHubClient gitHubClient;
    private final GitHubConfig gitHubConfig;
    private final StringRedisTemplate redisTemplate;
    private final ConfirmService confirmService;
    private final NotificationService notificationService;

    @Autowired(required = false)
    public CreateBranchCommandHandler(GitHubClient gitHubClient, GitHubConfig gitHubConfig,
                                     StringRedisTemplate redisTemplate,
                                     ConfirmService confirmService,
                                     NotificationService notificationService) {
        this.gitHubClient = gitHubClient;
        this.gitHubConfig = gitHubConfig;
        this.redisTemplate = redisTemplate;
        this.confirmService = confirmService;
        this.notificationService = notificationService;
    }

    @Command(
        name = "createbranch",
        description = "创建 Git 分支（需二次确认）",
        permissionLevel = "DEVELOPER",
        usage = "/createbranch <仓库别名> <新分支名> [源分支]"
    )
    public String handle(CommandContext context) {
        String args = context.getArgs().trim();
        String openId = context.getSender() != null ? context.getSender().getOpenId() : null;
        String chatId = context.getChatId();

        // 如果已确认，直接执行创建分支
        if (context.isConfirmed()) {
            return executeCreateBranch(context, args, openId);
        }

        // 打印用户 Open ID，用于配置白名单
        log.info("用户 Open ID: {}", openId);

        String[] parts = args.split("\\s+");
        if (parts.length < 2) {
            return "❌ 用法：/createbranch <仓库别名> <新分支名> [源分支]\n示例：/createbranch frontend feature/new-ui master";
        }

        String repoAlias = parts[0];
        String branchName = parts[1];
        String ref = (parts.length >= 3) ? parts[2] : null;  // 改为 null，稍后动态获取默认分支

        String repoFullName = gitHubConfig.getRepoAliasesMap().get(repoAlias);
        if (repoFullName == null) {
            return "❌ 未知的仓库别名：「" + repoAlias + "」\n可用别名：" + gitHubConfig.getRepoAliasesMap().keySet() + "\n💡 请先用 /repo 指令查看可用仓库";
        }

        // 如果用户没有指定源分支，提前获取默认分支用于显示
        String displayRef = ref;
        if (displayRef == null) {
            String[] repoParts = repoFullName.split("/");
            if (repoParts.length == 2) {
                displayRef = getDefaultBranch(repoParts[0], repoParts[1]);
                if (displayRef == null) {
                    displayRef = "(默认分支)";
                }
            } else {
                displayRef = "(默认分支)";
            }
        }

        // 二次确认：存储待确认操作
        if (confirmService != null) {
            String summary = String.format("仓库：%s，新分支：%s，基于：%s", repoFullName, branchName, displayRef);
            String token = confirmService.storePendingAction(openId, chatId, "createbranch", args, summary);
            return String.format("""
                    ⚠️ 敏感操作确认

                    📦 操作：创建 Git 分支
                    📂 仓库：%s
                    🌿 新分支：`%s`
                    📎 基于：%s
                    👤 操作者：%s

                    ❗ 请输入以下命令确认创建：
                    `/createbranch %s --confirm %s`

                    ⏰ 确认令牌有效期：5 分钟
                    💡 如需取消，请忽略此消息
                    """, repoFullName, branchName, displayRef, maskOpenId(openId), args, token);
        }

        // Redis 不可用，直接执行（降级）
        log.warn("ConfirmService 不可用，跳过二次确认，直接执行创建分支");
        return executeCreateBranch(context, args, openId);
    }

    /**
     * 实际执行创建分支（二次确认后调用）
     */
    private String executeCreateBranch(CommandContext context, String args, String openId) {
        String[] parts = args.split("\\s+");
        String repoAlias = parts[0];
        String branchName = parts[1];
        String ref = (parts.length >= 3) ? parts[2] : null;  // 改为 null，稍后动态获取

        // 先尝试从别名配置中获取，若找不到则尝试直接解析为 owner/repo 格式
        String repoFullName = gitHubConfig.getRepoAliasesMap().get(repoAlias);
        if (repoFullName == null) {
            // 检查是否直接传了 owner/repo 格式
            if (repoAlias.contains("/") && repoAlias.split("/").length == 2) {
                repoFullName = repoAlias;
            } else {
                return "❌ 未知的仓库别名：「" + repoAlias + "」\n可用别名：" + gitHubConfig.getRepoAliasesMap().keySet() + "\n💡 请先用 /repo 指令查看可用仓库，或直接使用 owner/repo 格式";
            }
        }

        // 解析 owner/repo
        String[] repoParts = repoFullName.split("/");
        if (repoParts.length != 2) {
            return "❌ 仓库格式错误，应为 owner/repo";
        }
        String owner = repoParts[0];
        String repo = repoParts[1];

        try {
            // 如果用户没有指定源分支，动态获取仓库的默认分支
            if (ref == null) {
                ref = getDefaultBranch(owner, repo);
                if (ref == null) {
                    return "❌ 无法获取仓库的默认分支，请手动指定源分支\n示例：/createbranch " + repoAlias + " " + branchName + " main";
                }
                log.info("自动获取默认分支: {}/{} -> {}", owner, repo, ref);
            }

            // 获取源分支的最新 commit SHA
            String sourceSha = gitHubClient.getBranchSha(owner, repo, ref);
            if (sourceSha == null) {
                return "❌ 获取源分支「" + ref + "」的 SHA 失败，请检查分支是否存在";
            }

            // 创建新分支
            Map<String, Object> result = gitHubClient.createBranch(owner, repo, branchName, sourceSha);

            if (result == null) {
                return "❌ 创建分支失败，请检查参数或权限";
            }

            // 设置标记：标记此仓库最近创建了分支，Webhook 处理时跳过通知
            // 标记有效期 60 秒，足够 Webhook 到达
            try {
                String key = "branch_create:" + repoFullName;
                redisTemplate.opsForValue().set(key, String.valueOf(System.currentTimeMillis()), 60, TimeUnit.SECONDS);
                log.info("设置分支创建标记: {}", key);
            } catch (Exception e) {
                log.warn("设置分支创建标记失败", e);
            }

            String successMsg = "✅ 分支创建成功！\n📌 仓库：" + repoAlias + "\n🌿 新分支：`" + branchName + "`\n📎 基于：" + ref + " (" + sourceSha.substring(0, 8) + ")";
            
            // 发送降噪通知
            sendBranchNotification(context, successMsg, repoFullName, branchName);
            
            return successMsg;

        } catch (Exception e) {
            log.error("创建分支失败", e);
            return "❌ 创建失败：" + e.getMessage();
        }
    }

    /**
     * 脱敏 OpenId（日志用）
     */
    private String maskOpenId(String openId) {
        if (openId == null || openId.length() < 8) return "***";
        return openId.substring(0, 4) + "***" + openId.substring(openId.length() - 4);
    }

    /**
     * 获取仓库的默认分支
     * @param owner 仓库所有者
     * @param repo 仓库名称
     * @return 默认分支名（如 main, master），失败返回 null
     */
    private String getDefaultBranch(String owner, String repo) {
        Map<String, Object> repoInfo = gitHubClient.getRepoInfo(owner, repo);
        if (repoInfo != null && repoInfo.containsKey("default_branch")) {
            return (String) repoInfo.get("default_branch");
        }
        return null;
    }

    /**
     * 发送分支创建通知（带智能降噪）
     */
    private void sendBranchNotification(CommandContext context, String message, String repoFullName, String branchName) {
        String chatId = context.getChatId();
        if (chatId == null || chatId.isEmpty()) {
            return;
        }

        try {
            if (notificationService != null) {
                // 使用 BRANCH_CREATE 事件类型，启用智能降噪（去重 + 合并）
                boolean success = notificationService.sendNotification(chatId, "BRANCH_CREATE", message);
                if (success) {
                    log.info("分支创建通知已发送（带降噪）: repo={}, branch={}, chatId={}", 
                            repoFullName, branchName, maskOpenId(chatId));
                } else {
                    log.info("分支创建通知被降噪拦截（去重或合并中）: repo={}", repoFullName);
                }
            } else {
                // NotificationService 不可用，降级处理
                log.warn("NotificationService 不可用，分支创建通知未启用智能降噪");
            }
        } catch (Exception e) {
            log.error("发送分支创建通知失败", e);
        }
    }
}
