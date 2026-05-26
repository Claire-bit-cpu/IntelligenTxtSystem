/*
 * GitHub Webhook 事件处理服务
 * 负责处理 GitHub Webhook 事件的业务逻辑
 */
package com.example.intelligentxtsystem.service.github;

import com.example.intelligentxtsystem.feishu.FeishuMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${notification.default-chat-ids:}")
    private String defaultChatIds;

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
            String fullName = getStringValue(payload, "repository", "full_name");
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

        } catch (Exception e) {
            log.error("处理 push 事件异常", e);
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

    @SuppressWarnings("unchecked")
    public void handlePullRequestEvent(Map<String, Object> payload) {
        try {
            String action = (String) payload.get("action");
            
            // 方案三：过滤掉不需要通知的 action（如 synchronize，即 push 到 PR 分支）
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

            log.info("========== GitHub Pull Request Event ==========");
            log.info("动作: {}", action);
            log.info("标题: {}", title);
            log.info("状态: {}", state);
            log.info("发起人: {}", initiator);
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
                    getStringValue(payload, "repository", "full_name"),
                    title, state, initiator, htmlUrl
            );

            // 发送飞书群通知
            sendNotification(message);

        } catch (Exception e) {
            log.error("处理 pull_request 事件异常", e);
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
     * 发送通知到配置的群聊
     */
    private void sendNotification(String message) {
        if (defaultChatIds == null || defaultChatIds.isEmpty()) {
            log.warn("未配置通知群聊 ID，跳过发送通知");
            return;
        }

        List<String> chatIds = Arrays.asList(defaultChatIds.split(","));
        for (String chatId : chatIds) {
            chatId = chatId.trim();
            if (!chatId.isEmpty()) {
                try {
                    feishuMessageService.sendTextToGroup(chatId, message);
                    log.info("已发送通知到群聊: {}", chatId);
                } catch (Exception e) {
                    log.error("发送通知到群聊失败: {}", chatId, e);
                }
            }
        }
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
