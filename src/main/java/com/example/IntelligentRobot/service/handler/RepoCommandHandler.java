package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.GitHubClient;
import com.example.IntelligentRobot.dto.CommandContext;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub 仓库查询指令处理器（新框架版本）
 * 指令格式：/repo <owner/repo> - 查看仓库信息
 */
@Component
public class RepoCommandHandler {

    private final GitHubClient gitHubClient;

    private static final Pattern REPO_PATTERN =
            Pattern.compile("^([^/]+)/([^\\s]+)");

    public RepoCommandHandler(GitHubClient gitHubClient) {
        this.gitHubClient = gitHubClient;
    }

    @Command(
        name = "repo",
        description = "查看 GitHub 仓库信息",
        usage = "/repo <owner/repo>"
    )
    public String handle(CommandContext context) {
        String args = context.getArgs().trim();

        if (args.isEmpty()) {
            return """
                    ❌ 用法：/repo <owner/repo>
                    
                    📋 示例：
                    /repo facebook/react
                    /repo microsoft/vscode
                    
                    💡 查看仓库的详细信息
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

        Matcher repoMatcher = REPO_PATTERN.matcher(args);
        if (repoMatcher.find()) {
            String owner = repoMatcher.group(1);
            String repo = repoMatcher.group(2);
            return gitHubClient.getRepoInfoText(owner, repo);
        }

        return "❌ 仓库格式错误，应为：owner/repo\n示例：/repo facebook/react";
    }
}
