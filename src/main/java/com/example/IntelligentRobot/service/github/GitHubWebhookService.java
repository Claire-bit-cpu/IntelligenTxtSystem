/*
 * GitHub Webhook 事件处理服务
 * 负责处理 GitHub Webhook 事件的业务逻辑
 */
package com.example.IntelligentRobot.service.github;

import com.example.IntelligentRobot.client.GitHubClient;
import com.example.IntelligentRobot.feishu.FeishuMessageService;
import com.example.IntelligentRobot.service.CodeReviewService;
import com.example.IntelligentRobot.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * GitHub Webhook 事件处理服务
 * 处理 Webhook 事件并发送飞书群通知
 */
@Service
public class GitHubWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookService.class);

    @Autowired
    private FeishuMessageService feishuMessageService;

    @Autowired(required = false)
    private NotificationService notificationService;

    @Autowired(required = false)
    private CodeReviewService codeReviewService;

    @Autowired(required = false)
    private GitHubClient gitHubClient;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Value("${notification.default-chat-ids:}")
    private String defaultChatIds;

    /**
     * 是否启用自动代码审查
     * 可通过配置控制，默认启用
     */
    @Value("${github.webhook.auto-review:true}")
    private boolean autoReviewEnabled;

    /**
     * 处理 push 事件
     * 解析并发送通知：
     *   - repository.full_name
     *   - pusher.name
     *   - head_commit.message
     */
    @SuppressWarnings("unchecked")
    public void handlePushEvent(Map<String, Object> payload) {
        try {
            // 检查是否是最近通过系统创建的分支（避免重复通知）
            // CreateBranchCommandHandler 创建分支后会设置 Redis 标记，有效期 60 秒
            String fullName = getStringValue(payload, "repository", "full_name");
            String branchCreateKey = "branch_create:" + fullName;
            String marker = redisTemplate.opsForValue().get(branchCreateKey);
            
            if (marker != null) {
                // 存在标记，说明这是系统刚刚创建的分支，跳过通知和代码审查
                log.info("检测到系统创建的分支推送事件，跳过通知: repository={}", fullName);
                try {
                    redisTemplate.delete(branchCreateKey); // 消费掉标记
                } catch (Exception e) {
                    log.warn("删除分支创建标记失败", e);
                }
                return;
            }

            // 检查是否是纯分支创建事件（无新提交）
            // GitHub 在新分支创建时，before 字段为全 0
            String before = (String) payload.get("before");
            boolean isNewBranchPush = "0000000000000000000000000000000000000000".equals(before);

            if (isNewBranchPush) {
                // 新分支创建，检查是否有新提交
                List<Map<String, Object>> commits = (List<Map<String, Object>>) payload.get("commits");
                
                if (commits == null || commits.isEmpty()) {
                    // 完全没有 commits，肯定是纯分支创建
                    log.info("忽略纯分支创建事件（无提交）: repository={}", fullName);
                    return;
                }
                
                log.info("检测到新分支推送，包含 {} 个提交，继续处理", commits.size());
            }
            String pusherName = getStringValue(payload, "pusher", "name");
            String commitMessage = getStringValue(payload, "head_commit", "message");
            String compareUrl = getStringValue(payload, "compare");

            log.info("========== GitHub Push Event ==========");
            log.info("仓库: {}", fullName);
            log.info("推送者: {}", pusherName);
            log.info("提交信息: {}", commitMessage);
            log.info("=======================================");

            // 构建通知消息
            String message = String.format(
                    "🚀 GitHub Push 通知\n\n" +
                    "📦 仓库：%s\n" +
                    "👤 推送者：%s\n" +
                    "📝 提交信息：%s\n",
                    fullName, pusherName, commitMessage
            );

            if (!compareUrl.isEmpty() && !"unknown".equals(compareUrl)) {
                message += "🔗 查看差异：" + compareUrl + "\n";
            }

            // 发送飞书群通知
            sendNotification(message);

            // 自动触发代码审查（异步）
            if (autoReviewEnabled && codeReviewService != null && gitHubClient != null) {
                triggerAutoReviewForPush(payload);
            }

        } catch (Exception e) {
            log.error("处理 push 事件异常", e);
        }
    }

    /**
     * 触发 Push 事件的自动代码审查
     */
    @SuppressWarnings("unchecked")
    private void triggerAutoReviewForPush(Map<String, Object> payload) {
        try {
            String fullName = getStringValue(payload, "repository", "full_name");
            if (fullName.equals("unknown") || !fullName.contains("/")) {
                log.warn("无法解析仓库信息，跳过自动审查");
                return;
            }

            // 获取最新提交的 SHA
            Map<String, Object> headCommit = (Map<String, Object>) payload.get("head_commit");
            if (headCommit == null) {
                log.warn("head_commit 为空，跳过自动审查");
                return;
            }

            String sha = (String) headCommit.get("id");
            if (sha == null || sha.isEmpty()) {
                log.warn("无法获取 commit SHA，跳过自动审查");
                return;
            }

            String[] parts = fullName.split("/");
            String owner = parts[0];
            String repo = parts[1];

            log.info("触发自动代码审查: {}/{} commit {}", owner, repo, sha);
            autoReviewCommit(owner, repo, sha, fullName);

        } catch (Exception e) {
            log.error("触发自动代码审查失败", e);
        }
    }

    /**
     * 处理 pull_request 事件
     * 解析并发送通知：
     *   - action（事件动作）
     *   - PR 标题
     *   - PR 状态
     *   - 发起人
     */
    // 需要忽略的 PR 动作（这些动作会产生重复通知）
    private static final List<String> IGNORED_PR_ACTIONS = Arrays.asList("synchronize", "labeled", "unlabeled", "milestoned", "demilestoned");

    // 需要触发自动代码审查的 PR 动作
    private static final List<String> REVIEW_PR_ACTIONS = Arrays.asList("opened", "ready_for_review");

    @SuppressWarnings("unchecked")
    public void handlePullRequestEvent(Map<String, Object> payload) {
        try {
            String action = (String) payload.get("action");
            
            // 过滤掉不需要通知的 action（如 synchronize，即 push 到 PR 分支）
            if (action != null && IGNORED_PR_ACTIONS.contains(action)) {
                log.info("忽略 pull_request 事件，action: {} (避免与 push 事件重复通知)", action);
                return;
            }
            
            Map<String, Object> pullRequest = (Map<String, Object>) payload.get("pull_request");
            if (pullRequest == null) {
                log.warn("pull_request 字段为空");
                return;
            }

            String title = (String) pullRequest.get("title");
            String state = (String) pullRequest.get("state");
            String htmlUrl = (String) pullRequest.get("html_url");
            
            Map<String, Object> user = (Map<String, Object>) pullRequest.get("user");
            String initiator = user != null ? (String) user.get("login") : "unknown";

            // 获取 PR 编号
            Integer prNumber = (Integer) pullRequest.get("number");
            String fullName = getStringValue(payload, "repository", "full_name");

            log.info("========== GitHub Pull Request Event ==========");
            log.info("动作: {}", action);
            log.info("标题: {}", title);
            log.info("状态: {}", state);
            log.info("发起人: {}", initiator);
            log.info("PR 编号: {}", prNumber);
            log.info("==============================================");

            // 构建通知消息
            String actionText = getActionText(action);
            String message = String.format(
                    "🔍 GitHub PR 通知\n\n" +
                    "📌 动作：%s\n" +
                    "📦 仓库：%s\n" +
                    "📝 标题：%s\n" +
                    "📊 状态：%s\n" +
                    "👤 发起人：%s\n" +
                    "🔗 链接：%s\n",
                    actionText,
                    fullName,
                    title, state, initiator, htmlUrl
            );

            // 发送飞书群通知
            sendNotification(message);

            // 自动触发代码审查（当 PR 创建或准备好审查时）
            if (autoReviewEnabled && codeReviewService != null && gitHubClient != null) {
                if (action != null && REVIEW_PR_ACTIONS.contains(action) && prNumber != null) {
                    triggerAutoReviewForPR(payload, prNumber);
                }
            }

        } catch (Exception e) {
            log.error("处理 pull_request 事件异常", e);
        }
    }

    /**
     * 触发 PR 事件的自动代码审查
     */
    @SuppressWarnings("unchecked")
    private void triggerAutoReviewForPR(Map<String, Object> payload, Integer prNumber) {
        try {
            String fullName = getStringValue(payload, "repository", "full_name");
            if (fullName.equals("unknown") || !fullName.contains("/")) {
                log.warn("无法解析仓库信息，跳过自动审查");
                return;
            }

            String[] parts = fullName.split("/");
            String owner = parts[0];
            String repo = parts[1];

            log.info("触发 PR 自动代码审查: {}/{} PR #{}", owner, repo, prNumber);
            autoReviewPR(owner, repo, prNumber, fullName);

        } catch (Exception e) {
            log.error("触发 PR 自动代码审查失败", e);
        }
    }

    /**
     * 异步审查提交并发送结果到飞书
     */
    @Async("messageExecutor")
    public void autoReviewCommit(String owner, String repo, String sha, String fullName) {
        try {
            log.info("开始自动审查 Commit: {}/{} {}", owner, repo, sha);

            // 调用审查服务
            com.example.IntelligentRobot.dto.CodeReviewResult result =
                    codeReviewService.reviewCommit(owner, repo, sha);

            // 构建目标信息
            String shortSha = sha.length() >= 7 ? sha.substring(0, 7) : sha;
            String target = String.format("Commit %s (%s)", shortSha, fullName);
            String commitUrl = "https://github.com/" + owner + "/" + repo + "/commit/" + sha;

            // 格式化结果
            String formattedResult = codeReviewService.formatReviewResult(result, target, commitUrl);

            // 发送通知
            sendNotification("🔍 自动代码审查完成\n\n" + formattedResult);

            log.info("自动审查 Commit 完成: {}/{} {}", owner, repo, sha);

        } catch (Exception e) {
            log.error("自动审查 Commit 失败: {}/{} {}", owner, repo, sha, e);
            sendNotification("⚠️ 自动代码审查失败\n\n仓库：" + fullName + "\nCommit：" + sha + "\n错误：" + e.getMessage());
        }
    }

    /**
     * 异步审查 PR 并发送结果到飞书
     */
    @Async("messageExecutor")
    public void autoReviewPR(String owner, String repo, int prNumber, String fullName) {
        try {
            log.info("开始自动审查 PR: {}/{} #{}", owner, repo, prNumber);

            // 调用审查服务
            com.example.IntelligentRobot.dto.CodeReviewResult result =
                    codeReviewService.reviewPullRequest(owner, repo, prNumber);

            // 构建目标信息
            String target = String.format("PR #%d (%s)", prNumber, fullName);
            String prUrl = "https://github.com/" + owner + "/" + repo + "/pull/" + prNumber;

            // 格式化结果
            String formattedResult = codeReviewService.formatReviewResult(result, target, prUrl);

            // 发送通知
            sendNotification("🔍 自动代码审查完成\n\n" + formattedResult);

            log.info("自动审查 PR 完成: {}/{} #{}", owner, repo, prNumber);

        } catch (Exception e) {
            log.error("自动审查 PR 失败: {}/{} #{}", owner, repo, prNumber, e);
            sendNotification("⚠️ 自动代码审查失败\n\n仓库：" + fullName + "\nPR：" + prNumber + "\n错误：" + e.getMessage());
        }
    }

    /**
     * 获取动作的中文描述
     */
    private String getActionText(String action) {
        if (action == null) return "unknown";
        return switch (action) {
            case "opened" -> "✅ 创建";
            case "closed" -> "❌ 关闭";
            case "reopened" -> "🔄 重新打开";
            case "merged" -> "✅ 已合并";
            case "edited" -> "✏️ 编辑";
            case "assigned", "unassigned" -> "👤 分配变更";
            case "review_requested", "review_request_removed" -> "👀 审查请求";
            default -> action;
        };
    }

    /**
     * 发送通知到配置的群聊（集成智能降噪）
     * 使用 NotificationService 进行去重和合并
     */
    private void sendNotification(String message) {
        if (defaultChatIds == null || defaultChatIds.isEmpty()) {
            log.warn("未配置通知群聊 ID，跳过发送通知");
            return;
        }

        // 判断事件类型（根据消息内容判断）
        String eventType = determineEventType(message);

        List<String> chatIds = Arrays.asList(defaultChatIds.split(","));
        for (String chatId : chatIds) {
            chatId = chatId.trim();
            if (!chatId.isEmpty()) {
                try {
                    // 优先使用 NotificationService（带智能降噪）
                    if (notificationService != null) {
                        boolean success = notificationService.sendNotification(chatId, eventType, message);
                        if (success) {
                            log.info("已通过智能降噪服务发送通知到群聊: {}", maskChatId(chatId));
                        } else {
                            log.info("通知被智能降噪拦截（去重或合并中）: {}", maskChatId(chatId));
                        }
                    } else {
                        // 降级：直接发送
                        feishuMessageService.sendTextToGroup(chatId, message);
                        log.info("已发送通知到群聊（未启用智能降噪）: {}", maskChatId(chatId));
                    }
                } catch (Exception e) {
                    log.error("发送通知到群聊失败: {}", maskChatId(chatId), e);
                }
            }
        }
    }

    /**
     * 根据消息内容判断事件类型
     */
    private String determineEventType(String message) {
        if (message == null) return "GITHUB";
        if (message.contains("Push 通知") || message.contains("🚀")) return "GITHUB_PUSH";
        if (message.contains("PR 通知") || message.contains("🔍")) return "GITHUB_PR";
        if (message.contains("代码审查")) return "CODE_REVIEW";
        return "GITHUB";
    }

    /**
     * 脱敏 chatId（日志打印用）
     */
    private String maskChatId(String chatId) {
        if (chatId == null || chatId.length() < 8) return "***";
        return chatId.substring(0, 4) + "***" + chatId.substring(chatId.length() - 4);
    }

    /**
     * 从嵌套 Map 中安全获取字符串值
     */
    @SuppressWarnings("unchecked")
    private String getStringValue(Map<String, Object> payload, String... keys) {
        if (payload == null || keys == null || keys.length == 0) {
            return "unknown";
        }

        Object current = payload;
        for (int i = 0; i < keys.length; i++) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(keys[i]);
            } else {
                return "unknown";
            }
        }

        return current != null ? current.toString() : "unknown";
    }
}
