package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.client.GitHubClient;
import com.example.intelligentxtsystem.dto.FeishuSender;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub 指令处理器
 * 指令格式：
 * /repo <owner/repo> - 查看仓库信息
 * /pr <owner/repo> <PR号> - 查看PR信息
 */
@Component
public class GitHubHandler implements CommandHandler {

    private final GitHubClient gitHubClient;

    // /repo owner/repo
    private static final Pattern REPO_PATTERN =
            Pattern.compile("^(?:/repo|仓库)\\s+([^/]+)/([^\\s]+)");

    // /pr owner/repo 123
    private static final Pattern PR_PATTERN =
            Pattern.compile("^(?:/pr|PR)\\s+([^/]+)/([^\\s]+)\\s+(\\d+)");

    public GitHubHandler(GitHubClient gitHubClient) {
        this.gitHubClient = gitHubClient;
    }

    @Override
    public boolean support(String text) {
        return text.startsWith("/repo") || text.startsWith("/pr") ||
               text.startsWith("仓库 ") || text.startsWith("PR ");
    }

    @Override
    public String handle(String text, FeishuSender sender, String chatId) {
        // 检查是否配置了GitHub Token
        if (!gitHubClient.isConfigured()) {
            return """
                    ⚠️ GitHub 功能未配置

                    请配置 github.token 环境变量
                    当前功能受限
                    """;
        }

        // 匹配仓库查询
        Matcher repoMatcher = REPO_PATTERN.matcher(text);
        if (repoMatcher.find()) {
            String owner = repoMatcher.group(1);
            String repo = repoMatcher.group(2);
            return gitHubClient.getRepoInfo(owner, repo);
        }

        // 匹配PR查询
        Matcher prMatcher = PR_PATTERN.matcher(text);
        if (prMatcher.find()) {
            String owner = prMatcher.group(1);
            String repo = prMatcher.group(2);
            int prNumber = Integer.parseInt(prMatcher.group(3));
            return gitHubClient.getPRInfo(owner, repo, prNumber);
        }

        return """
                ❌ 用法：

                📦 仓库查询：
                /repo owner/repo
                /repo octocat/Hello-World

                🔍 PR查询：
                /pr owner/repo 123
                /pr microsoft/vscode 12345

                💡 示例：
                /repo facebook/react
                /pr facebook/react 12345
                """;
    }
}
