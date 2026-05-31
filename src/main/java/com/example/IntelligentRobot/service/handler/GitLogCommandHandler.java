package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.GitHubClient;
import com.example.IntelligentRobot.config.GitHubConfig;
import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.client.FeishuClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Git 提交日志查询指令处理器（新框架版本）
 * 指令格式：/gitlog <仓库别名> [条数] [分支]
 */
@Component
public class GitLogCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(GitLogCommandHandler.class);

    private final GitHubClient gitHubClient;
    private final GitHubConfig gitHubConfig;
    private final FeishuClient feishuClient;

    public GitLogCommandHandler(GitHubClient gitHubClient, GitHubConfig gitHubConfig, FeishuClient feishuClient) {
        this.gitHubClient = gitHubClient;
        this.gitHubConfig = gitHubConfig;
        this.feishuClient = feishuClient;
    }

    @Command(
        name = "gitlog",
        description = "查看 Git 提交日志（支持上下文感知）",
        usage = "/gitlog <仓库别名> [条数] [分支]",
        supportsContext = true,
        contextType = "gitlog",
        localParams = {"repoAlias", "limit", "branch"},
        contextTimeout = 5
    )
    public String handle(CommandContext context) {
        String args = context.getArgs().trim();

        // 尝试从上下文获取参数
        String repoAlias = null;
        Integer limit = null;
        String branch = null;

        // 优先使用当前输入
        if (!args.isEmpty()) {
            String[] parts = args.split("\\s+");
            if (parts.length >= 1) repoAlias = parts[0];
            if (parts.length >= 2) limit = Integer.parseInt(parts[1]);
            if (parts.length >= 3) branch = parts[2];
        }

        // 如果当前输入缺失，从上下文填充
        if (context.isContextSupported()) {
            if (repoAlias == null && context.hasFilledParam("repoAlias")) {
                repoAlias = (String) context.getFilledParam("repoAlias");
            }
            if (limit == null && context.hasFilledParam("limit")) {
                limit = (Integer) context.getFilledParam("limit");
            }
            if (branch == null && context.hasFilledParam("branch")) {
                branch = (String) context.getFilledParam("branch");
            }
        }

        // 设置默认值
        if (repoAlias == null) {
            return "❌ 用法：/gitlog <仓库别名> [条数] [分支]\n示例：/gitlog frontend 10 main";
        }
        if (limit == null) limit = 10;

        // 保存参数到上下文
        context.setFilledParam("repoAlias", repoAlias);
        context.setFilledParam("limit", limit);
        if (branch != null) context.setFilledParam("branch", branch);

        log.info("解析 /gitlog 命令: repoAlias={}, limit={}, branch={}", repoAlias, limit, branch);

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
            log.info("【调试】开始调用 gitHubClient.getCommits: owner={}, repo={}, limit={}, branch={}", owner, repo, limit, branch);
            
            List<Map<String, Object>> commits = gitHubClient.getCommits(owner, repo, limit, branch);
            
            log.info("【调试】gitHubClient.getCommits 返回: commits={}", commits != null ? "size=" + commits.size() : "null");

            if (commits == null || commits.isEmpty()) {
                log.warn("【调试】提交列表为空: repo={}/{}, limit={}", owner, repo, limit);
                return "📋 仓库 " + repoAlias + " 暂无提交记录";
            }

            log.info("【调试】处理 {} 条提交记录", commits.size());
            
            StringBuilder sb = new StringBuilder();
            sb.append("📋 **").append(repoAlias).append("** 最近 ").append(commits.size()).append(" 条提交：\n\n");

            int processedCount = 0;
            for (Map<String, Object> commit : commits) {
                processedCount++;
                
                if (commit == null) {
                    log.warn("【调试】提交[{}]为null，跳过", processedCount);
                    continue;
                }
                
                String sha = ((String) commit.get("sha")).substring(0, 8);
                
                // GitHub API 返回的是嵌套的 commit 对象
                @SuppressWarnings("unchecked")
                Map<String, Object> commitInfo = (Map<String, Object>) commit.get("commit");
                
                if (commitInfo != null) {
                    String message = (String) commitInfo.get("message");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> authorInfo = (Map<String, Object>) commitInfo.get("author");
                    String author = (String) authorInfo.get("name");
                    String date = (String) authorInfo.get("date");

                    // 简化日期
                    if (date != null && date.length() > 10) {
                        date = date.substring(0, 10);
                    }

                    sb.append("`").append(sha).append("` ");
                    sb.append(message != null ? message.split("\n")[0] : "(无消息)");
                    sb.append("\n  👤 ").append(author).append(" · ").append(date).append("\n\n");
                }
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("【调试】查询 Git 日志失败: repo={}/{}, limit={}", owner, repo, limit, e);
            return "❌ 查询失败：" + e.getMessage();
        }
    }
}
