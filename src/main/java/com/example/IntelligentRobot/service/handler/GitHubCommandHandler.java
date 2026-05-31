package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.GitHubClient;
import com.example.IntelligentRobot.dto.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * GitHub Actions 指令处理器（新框架版本）
 * 指令格式：
 * /github workflow <仓库> <工作流> <分支> - 触发工作流
 * /github status <仓库> <run-id> - 查询运行状态
 * /github list <仓库> [工作流] - 列出最近运行
 * /github cancel <仓库> <run-id> - 取消运行
 */
@Component
public class GitHubCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(GitHubCommandHandler.class);

    @Autowired(required = false)
    private GitHubClient gitHubClient;

    @Command(
        name = "github",
        description = "GitHub Actions 管理",
        permissionLevel = "DEVELOPER",
        usage = "/github <子命令>"
    )
    public String handle(CommandContext context) {
        String githubCmd = context.getArgs().trim();
        String senderOpenId = context.getSender() != null ? context.getSender().getOpenId() : "系统";

        if (githubCmd.isEmpty()) {
            return buildGitHubHelp();
        }

        if (gitHubClient == null || !gitHubClient.isConfigured()) {
            return """
                    ⚠️ GitHub 集成未启用

                    请配置以下环境变量：
                    • github.token - GitHub Personal Access Token
                    • github.api-url - GitHub API 地址（可选，默认 https://api.github.com）

                    💡 获取 Token：GitHub → Settings → Developer settings → Personal access tokens
                    """;
        }

        // /github workflow <owner/repo> <工作流文件> <分支>
        if (githubCmd.startsWith("workflow ")) {
            return handleWorkflow(githubCmd, senderOpenId);
        }

        // /github status <owner/repo> <run-id>
        if (githubCmd.startsWith("status ")) {
            return handleStatus(githubCmd);
        }

        // /github list <owner/repo> [工作流]
        if (githubCmd.startsWith("list ")) {
            return handleList(githubCmd);
        }

        // /github cancel <owner/repo> <run-id>
        if (githubCmd.startsWith("cancel ")) {
            return handleCancel(githubCmd, senderOpenId);
        }

        return buildGitHubHelp();
    }

    private String handleWorkflow(String githubCmd, String senderOpenId) {
        String[] parts = githubCmd.substring(9).split("\\s+", 3);
        if (parts.length < 3) {
            return "❌ 用法：/github workflow <owner/repo> <工作流文件> <分支>\n示例：/github workflow Claire-bit-cpu/Test deploy-dev.yml develop";
        }
        String[] repoParts = parts[0].split("/", 2);
        if (repoParts.length < 2) {
            return "❌ 仓库格式错误，应为：owner/repo";
        }
        String owner = repoParts[0];
        String repo = repoParts[1];
        String workflowId = parts[1];
        String ref = parts[2];
        try {
            String result = gitHubClient.triggerWorkflow(owner, repo, workflowId, ref, null);
            return String.format("""
                        🚀 GitHub Actions 工作流已触发

                        📂 仓库：%s/%s
                        🔧 工作流：%s
                        🌿 分支：%s
                        👤 操作者：%s
                        🕐 时间：%s

                        %s

                        🔗 查看详情：https://github.com/%s/%s/actions
                        """, owner, repo, workflowId, ref, 
                           senderOpenId,
                           java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                           result, owner, repo);
        } catch (Exception e) {
            log.error("GitHub Actions 触发失败", e);
            return "❌ 触发失败: " + e.getMessage();
        }
    }

    private String handleStatus(String githubCmd) {
        String[] parts = githubCmd.substring(7).split("\\s+", 2);
        if (parts.length < 2) {
            return "❌ 用法：/github status <owner/repo> <run-id>\n示例：/github status Claire-bit-cpu/Test 123456";
        }
        String[] repoParts = parts[0].split("/", 2);
        if (repoParts.length < 2) {
            return "❌ 仓库格式错误，应为：owner/repo";
        }
        String owner = repoParts[0];
        String repo = repoParts[1];
        try {
            int runId = Integer.parseInt(parts[1]);
            Map<String, Object> run = gitHubClient.getWorkflowRun(owner, repo, runId);
            if (run == null) {
                return "❌ 获取工作流运行状态失败";
            }
            String status = (String) run.get("status");
            String conclusion = (String) run.get("conclusion");
            String htmlUrl = (String) run.get("html_url");
            Object headBranchObj = run.get("head_branch");
            String headBranch = headBranchObj != null ? headBranchObj.toString() : "N/A";
            Object headCommitObj = run.get("head_sha");
            String headCommit = headCommitObj != null ? headCommitObj.toString() : "N/A";

            return String.format("""
                        🔍 GitHub Actions 运行状态

                        📂 仓库：%s/%s
                        🆔 运行 ID：%d
                        🌿 分支：%s
                        📝 提交：%s
                        📊 状态：%s
                        🎯 结果：%s

                        🔗 查看详情：%s
                        """, owner, repo, runId, headBranch, 
                           headCommit != null && headCommit.length() >= 8 ? headCommit.substring(0, 8) : "N/A",
                           status, 
                           conclusion != null ? conclusion : "进行中",
                           htmlUrl);
        } catch (NumberFormatException e) {
            return "❌ 运行 ID 必须是数字";
        }
    }

    private String handleList(String githubCmd) {
        String[] parts = githubCmd.substring(5).split("\\s+", 2);
        String[] repoParts = parts[0].split("/", 2);
        if (repoParts.length < 2) {
            return "❌ 仓库格式错误，应为：owner/repo";
        }
        String owner = repoParts[0];
        String repo = repoParts[1];
        String workflowId = parts.length > 1 ? parts[1] : null;
        try {
            List<Map<String, Object>> runs = gitHubClient.listWorkflowRuns(owner, repo, workflowId, 10);
            if (runs == null || runs.isEmpty()) {
                return "❌ 未找到工作流运行记录";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("📋 最近的工作流运行\n\n");
            for (Map<String, Object> run : runs) {
                int runId = (Integer) run.get("id");
                String status = (String) run.get("status");
                String conclusion = (String) run.get("conclusion");
                String branch = (String) run.get("head_branch");
                sb.append(String.format("• #%d - %s (分支: %s, 结果: %s)\n", 
                        runId, status, branch, 
                        conclusion != null ? conclusion : "进行中"));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("获取工作流运行列表失败", e);
            return "❌ 获取失败: " + e.getMessage();
        }
    }

    private String handleCancel(String githubCmd, String senderOpenId) {
        String[] parts = githubCmd.substring(7).split("\\s+", 2);
        if (parts.length < 2) {
            return "❌ 用法：/github cancel <owner/repo> <run-id>";
        }
        String[] repoParts = parts[0].split("/", 2);
        if (repoParts.length < 2) {
            return "❌ 仓库格式错误，应为：owner/repo";
        }
        String owner = repoParts[0];
        String repo = repoParts[1];
        try {
            int runId = Integer.parseInt(parts[1]);
            String result = gitHubClient.cancelWorkflowRun(owner, repo, runId);
            return String.format("""
                        🛑 取消工作流运行

                        📂 仓库：%s/%s
                        🆔 运行 ID：%d
                        🕐 时间：%s

                        %s
                        """, owner, repo, runId,
                           java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                           result);
        } catch (NumberFormatException e) {
            return "❌ 运行 ID 必须是数字";
        }
    }

    private String buildGitHubHelp() {
        return """
                ❌ GitHub 命令格式错误

                🚀 可用命令：
                • /github workflow <owner/repo> <工作流文件> <分支> - 触发工作流
                • /github status <owner/repo> <run-id> - 查询运行状态
                • /github list <owner/repo> [工作流文件] - 列出最近运行
                • /github cancel <owner/repo> <run-id> - 取消运行

                💡 示例：
                /github workflow Claire-bit-cpu/Test deploy-dev.yml develop
                /github status Claire-bit-cpu/Test 123456
                /github list Claire-bit-cpu/Test
                /github cancel Claire-bit-cpu/Test 123456
                """;
    }
}
