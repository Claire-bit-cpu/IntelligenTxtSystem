package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.GitHubClient;
import com.example.IntelligentRobot.dto.CommandContext;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub PR 查询指令处理器（新框架版本）
 * 指令格式：/pr <owner/repo> <PR号> - 查看PR信息
 */
@Component
public class PrCommandHandler {

    private final GitHubClient gitHubClient;

    private static final Pattern PR_PATTERN =
            Pattern.compile("^([^/]+)/([^\\s]+)\\s+(\\d+)");

    public PrCommandHandler(GitHubClient gitHubClient) {
        this.gitHubClient = gitHubClient;
    }

    @Command(
        name = "pr",
        description = "查看 GitHub Pull Request 信息",
        usage = "/pr <owner/repo> <PR号>"
    )
    public String handle(CommandContext context) {
        String args = context.getArgs().trim();

        if (args.isEmpty()) {
            return """
                    ❌ 用法：/pr <owner/repo> <PR号>
                    
                    📋 示例：
                    /pr facebook/react 12345
                    /pr microsoft/vscode 42
                    
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

        Matcher prMatcher = PR_PATTERN.matcher(args);
        if (prMatcher.find()) {
            String owner = prMatcher.group(1);
            String repo = prMatcher.group(2);
            int prNumber = Integer.parseInt(prMatcher.group(3));
            return gitHubClient.getPRInfo(owner, repo, prNumber);
        }

        return "❌ 格式错误\n用法：/pr owner/repo PR号\n示例：/pr facebook/react 12345";
    }
}
