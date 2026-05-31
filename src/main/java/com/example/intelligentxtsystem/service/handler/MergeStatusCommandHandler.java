package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.annotation.Command;
import com.example.intelligentxtsystem.client.GitHubClient;
import com.example.intelligentxtsystem.config.GitHubConfig;
import com.example.intelligentxtsystem.dto.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 查看 Pull Request（PR）状态指令处理器（新框架版本）
 * 指令格式：
 *   /mergestatus <仓库别名>           - 查看所有打开的 PR
 *   /mergestatus <仓库别名> <PR号>   - 查看特定 PR 详情
 */
@Component
public class MergeStatusCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(MergeStatusCommandHandler.class);

    private final GitHubClient gitHubClient;
    private final GitHubConfig gitHubConfig;

    public MergeStatusCommandHandler(GitHubClient gitHubClient, GitHubConfig gitHubConfig) {
        this.gitHubClient = gitHubClient;
        this.gitHubConfig = gitHubConfig;
    }

    @Command(
        name = "mergestatus",
        description = "查看 Pull Request 状态",
        permissionLevel = "DEVELOPER",
        usage = "/mergestatus <仓库别名> [PR号]"
    )
    public String handle(CommandContext context) {
        String args = context.getArgs().trim();

        String[] parts = args.split("\\s+");
        if (parts.length < 1) {
            return "❌ 用法：/mergestatus <仓库别名> [PR号]\n示例：/mergestatus frontend\n       /mergestatus frontend 42";
        }

        String repoAlias = parts[0];
        String prNumber = (parts.length >= 2) ? parts[1] : null;

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
            String state = (String) pr.get("state");
            String htmlUrl = (String) pr.get("html_url");

            sb.append("**#").append(number).append("** ");
            sb.append(title != null ? title : "(无标题)").append("\n");
            
            // 解析分支信息（head 和 base 是 Map 类型）
            Map<String, Object> head = (Map<String, Object>) pr.get("head");
            Map<String, Object> base = (Map<String, Object>) pr.get("base");
            String headRef = (head != null && head.get("ref") != null) ? head.get("ref").toString() : "unknown";
            String baseRef = (base != null && base.get("ref") != null) ? base.get("ref").toString() : "unknown";
            
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
