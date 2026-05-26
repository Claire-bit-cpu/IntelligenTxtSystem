package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.client.GitHubClient;
import com.example.intelligentxtsystem.config.GitHubConfig;
import com.example.intelligentxtsystem.dto.FeishuSender;
import com.example.intelligentxtsystem.service.GitPermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 查看 Pull Request（PR）状态（需要写权限）
 * 指令格式：
 *   /mergestatus <仓库别名>           - 查看所有打开的 PR
 *   /mergestatus <仓库别名> <PR号>   - 查看特定 PR 详情
 * 示例：/mergestatus frontend
 *       /mergestatus frontend 42
 */
@Component
public class MergeStatusHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(MergeStatusHandler.class);

    private final GitHubClient gitHubClient;
    private final GitHubConfig gitHubConfig;
    private final GitPermissionService permissionService;

    public MergeStatusHandler(GitHubClient gitHubClient, GitHubConfig gitHubConfig, GitPermissionService permissionService) {
        this.gitHubClient = gitHubClient;
        this.gitHubConfig = gitHubConfig;
        this.permissionService = permissionService;
    }

    @Override
    public boolean support(String text) {
        return text.trim().toLowerCase().startsWith("/mergestatus");
    }

    @Override
    public String handle(String text, FeishuSender sender, String chatId) {
        // 权限检查
        String openId = sender.getOpenId();
        if (openId == null || !permissionService.hasWritePermission(openId)) {
            return "❌ 权限不足！查看 PR 需要管理员或开发者权限。\n请联系管理员将你的飞书账号加入白名单。";
        }

        String[] parts = text.trim().split("\\s+");
        if (parts.length < 2) {
            return "❌ 用法：/mergestatus <仓库别名> [PR号]\n示例：/mergestatus frontend\n       /mergestatus frontend 42";
        }

        String repoAlias = parts[1];
        String prNumber = (parts.length >= 3) ? parts[2] : null;

        String repoFullName = gitHubConfig.getRepoAliases().get(repoAlias);
        if (repoFullName == null) {
            return "❌ 未知的仓库别名：「" + repoAlias + "」\n可用别名：" + gitHubConfig.getRepoAliases().keySet();
        }

        // 解析 owner/repo
        String[] repoParts = repoFullName.split("/");
        if (repoParts.length != 2) {
            return "❌ 仓库配置格式错误，应为 owner/repo";
        }
        String owner = repoParts[0];
        String repo = repoParts[1];

        try {
            if (prNumber != null) {
                // 查看特定 PR 详情
                return getPullRequestDetail(owner, repo, Integer.parseInt(prNumber), repoAlias);
            } else {
                // 查看所有打开的 PR
                return listOpenPullRequests(owner, repo, repoAlias);
            }
        } catch (Exception e) {
            log.error("查询 PR 失败", e);
            return "❌ 查询失败：" + e.getMessage();
        }
    }

    /**
     * 列出所有打开的 PR
     */
    private String listOpenPullRequests(String owner, String repo, String repoAlias) {
        List<Map<String, Object>> prs = gitHubClient.getPullRequests(owner, repo);

        if (prs == null || prs.isEmpty()) {
            return "✅ 仓库 **" + repoAlias + "** 当前没有打开的 Pull Request";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📊 **").append(repoAlias).append("** 打开的 Pull Request（").append(prs.size()).append(" 个）：\n\n");

        for (Map<String, Object> pr : prs) {
            int number = (Integer) pr.get("number");
            String title = (String) pr.get("title");
            String sourceBranch = (String) pr.get("head");
            String targetBranch = (String) pr.get("base");
            String state = (String) pr.get("state");
            String htmlUrl = (String) pr.get("html_url");

            sb.append("**#").append(number).append("** ");
            sb.append(title != null ? title : "(无标题)").append("\n");
            
            // 解析分支信息
            String headRef = (sourceBranch != null) ? ((Map<String, Object>) pr.get("head")).get("ref").toString() : "unknown";
            String baseRef = (targetBranch != null) ? ((Map<String, Object>) pr.get("base")).get("ref").toString() : "unknown";
            
            sb.append("  🌿 `").append(headRef).append("` → `").append(baseRef).append("`\n");
            sb.append("  🔗 ").append(htmlUrl).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * 查看特定 PR 详情
     */
    private String getPullRequestDetail(String owner, String repo, int prNumber, String repoAlias) {
        Map<String, Object> pr = gitHubClient.getPullRequest(owner, repo, prNumber);

        if (pr == null) {
            return "❌ 未找到 PR #" + prNumber;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📋 **PR #").append(pr.get("number")).append("**\n\n");
        sb.append("**标题：** ").append(pr.get("title")).append("\n");
        sb.append("**状态：** ").append(pr.get("state")).append("\n");
        sb.append("**作者：** ").append(getAuthorName(pr)).append("\n");
        
        Map<String, Object> head = (Map<String, Object>) pr.get("head");
        Map<String, Object> base = (Map<String, Object>) pr.get("base");
        sb.append("**源分支：** `").append(head != null ? head.get("ref") : "unknown").append("`\n");
        sb.append("**目标分支：** `").append(base != null ? base.get("ref") : "unknown").append("`\n");
        
        sb.append("**创建时间：** ").append(pr.get("created_at")).append("\n");
        sb.append("**链接：** ").append(pr.get("html_url")).append("\n");

        return sb.toString();
    }

    private String getAuthorName(Map<String, Object> pr) {
        Map<String, Object> user = (Map<String, Object>) pr.get("user");
        return user != null ? (String) user.get("login") : "未知";
    }
}
