package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.GitHubClient;
import com.example.IntelligentRobot.config.GitHubConfig;
import com.example.IntelligentRobot.dto.CommandContext;
import org.springframework.stereotype.Component;

/**
 * GitHub PR 查询指令处理器（新框架版本）
 * 指令格式：/pr <仓库别名或owner/repo> <PR号> - 查看PR信息
 */
@Component
public class PrCommandHandler {

    private final GitHubClient gitHubClient;
    private final GitHubConfig gitHubConfig;

    public PrCommandHandler(GitHubClient gitHubClient, GitHubConfig gitHubConfig) {
        this.gitHubClient = gitHubClient;
        this.gitHubConfig = gitHubConfig;
    }

    @Command(
        name = "pr",
        description = "查看 GitHub Pull Request 信息",
        usage = "/pr <仓库别名或owner/repo> <PR号>"
    )
    public String handle(CommandContext context) {
        String args = context.getArgs().trim();

        if (args.isEmpty()) {
            return """
                    ❌ 用法：/pr <仓库别名或owner/repo> <PR号>
                    
                    📋 示例：
                    /pr frontend 12345
                    /pr facebook/react 12345
                    
                    💡 查看 Pull Request 的详细信息
                    """;
        }

        // 检查是否配置了GitHub Token
        if (!gitHubClient.isConfigured()) {
            return """
                    ⚠️ GitHub 功能未配置

                    请配置 github.token 环境变量
                    当前功能受限
                    """;
        }

        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2) {
            return "❌ 格式错误\n用法：/pr <仓库别名或owner/repo> <PR号>\n示例：/pr frontend 12345";
        }

        String repoIdentifier = parts[0];  // 可能是别名或 owner/repo
        int prNumber;
        try {
            prNumber = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return "❌ PR号必须是数字\n示例：/pr frontend 12345";
        }

        // 尝试从别名配置中获取仓库全名
        String repoFullName = gitHubConfig.getRepoAliasesMap().get(repoIdentifier);
        
        // 如果不是别名，尝试按 owner/repo 格式解析
        if (repoFullName == null) {
            if (repoIdentifier.contains("/") && repoIdentifier.split("/").length == 2) {
                repoFullName = repoIdentifier;
            } else {
                return "❌ 未知的仓库别名：「" + repoIdentifier + "」\n可用别名：" + gitHubConfig.getRepoAliasesMap().keySet() + "\n💡 请直接使用 owner/repo 格式，如：/pr facebook/react 12345";
            }
        }

        // 解析 owner/repo
        String[] repoParts = repoFullName.split("/");
        String owner = repoParts[0];
        String repo = repoParts[1];

        return gitHubClient.getPRInfo(owner, repo, prNumber);
    }
}
