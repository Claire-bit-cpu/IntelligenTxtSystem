package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.client.GitHubClient;
import com.example.intelligentxtsystem.config.GitHubConfig;
import com.example.intelligentxtsystem.dto.FeishuSender;
import com.example.intelligentxtsystem.service.CodeReviewService;
import com.example.intelligentxtsystem.client.QwenClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码审查指令处理器
 * 指令格式：
 *   /review <仓库别名> <PR号>        - 审查 PR
 *   /review <仓库别名> commit <SHA>   - 审查提交
 *   /cr <owner/repo> <PR号>         - 兼容旧命令
 */
@Component
public class CodeReviewHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewHandler.class);

    private final CodeReviewService codeReviewService;
    private final GitHubClient gitHubClient;
    private final GitHubConfig gitHubConfig;

    // /review frontend 42 或 /review frontend commit abc123
    private static final Pattern REVIEW_ALIAS_PATTERN =
            Pattern.compile("^/review\\s+(\\S+)\\s+(commit\\s+)?(\\S+)");

    // /cr owner/repo 123 (兼容旧命令)
    private static final Pattern CR_PATTERN =
            Pattern.compile("^(?:/cr|审查|代码审查)\\s+([^/]+)/([^\\s]+)\\s+(\\d+)");

    public CodeReviewHandler(CodeReviewService codeReviewService,
                            GitHubClient gitHubClient,
                            GitHubConfig gitHubConfig) {
        this.codeReviewService = codeReviewService;
        this.gitHubClient = gitHubClient;
        this.gitHubConfig = gitHubConfig;
    }

    @Override
    public boolean support(String text) {
        String trimmed = text.trim().toLowerCase();
        return trimmed.equals("/review") || trimmed.startsWith("/review ") ||
               trimmed.equals("/cr") || trimmed.startsWith("/cr ") ||
               trimmed.startsWith("审查 ") || trimmed.equals("审查") ||
               trimmed.startsWith("代码审查 ") || trimmed.equals("代码审查");
    }

    @Override
    public String handle(String text, FeishuSender sender, String chatId) {
        // 检查是否配置了 GitHub Token
        if (!gitHubClient.isConfigured()) {
            return """
                    ⚠️ 代码审查功能未配置

                    请配置 github.token 环境变量
                    当前功能受限
                    """;
        }

        // 尝试匹配新命令格式：/review <别名> <PR号或commit>
        Matcher reviewMatcher = REVIEW_ALIAS_PATTERN.matcher(text.trim());
        if (reviewMatcher.find()) {
            return handleReviewAlias(reviewMatcher);
        }

        // 尝试匹配旧命令格式：/cr owner/repo PR号
        Matcher crMatcher = CR_PATTERN.matcher(text.trim());
        if (crMatcher.find()) {
            return handleCrCommand(crMatcher);
        }

        // 命令格式错误，显示帮助
        return buildHelpText();
    }

    /**
     * 处理 /review <别名> <PR号或commit> 格式
     */
    private String handleReviewAlias(Matcher matcher) {
        String repoAlias = matcher.group(1);
        boolean isCommit = matcher.group(2) != null;
        String target = matcher.group(3);

        // 解析仓库别名
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
            if (isCommit) {
                // 审查提交
                return reviewCommit(owner, repo, target);
            } else {
                // 判断是 PR 号还是 commit hash
                if (target.matches("\\d+")) {
                    // PR 号
                    int prNumber = Integer.parseInt(target);
                    return reviewPullRequest(owner, repo, prNumber);
                } else {
                    // 当作 commit hash 处理
                    return reviewCommit(owner, repo, target);
                }
            }
        } catch (Exception e) {
            log.error("处理审查命令失败", e);
            return "⚠️ 代码审查失败：" + e.getMessage();
        }
    }

    /**
     * 处理 /cr owner/repo PR号 格式（兼容旧命令）
     */
    private String handleCrCommand(Matcher matcher) {
        String owner = matcher.group(1);
        String repo = matcher.group(2);
        int prNumber = Integer.parseInt(matcher.group(3));

        try {
            return reviewPullRequest(owner, repo, prNumber);
        } catch (Exception e) {
            log.error("处理 /cr 命令失败", e);
            return "⚠️ 代码审查失败：" + e.getMessage();
        }
    }

    /**
     * 审查 Pull Request
     */
    private String reviewPullRequest(String owner, String repo, int prNumber) {
        // 调用审查服务
        com.example.intelligentxtsystem.dto.CodeReviewResult result =
                codeReviewService.reviewPullRequest(owner, repo, prNumber);

        // 获取 PR 链接
        String prUrl = "https://github.com/" + owner + "/" + repo + "/pull/" + prNumber;

        // 格式化结果
        String target = String.format("PR #%d (%s/%s)", prNumber, owner, repo);
        String formattedResult = codeReviewService.formatReviewResult(result, target, prUrl);

        return formattedResult;
    }

    /**
     * 审查 Commit
     */
    private String reviewCommit(String owner, String repo, String sha) {
        // 调用审查服务
        com.example.intelligentxtsystem.dto.CodeReviewResult result =
                codeReviewService.reviewCommit(owner, repo, sha);

        // 获取 Commit 链接
        String commitUrl = "https://github.com/" + owner + "/" + repo + "/commit/" + sha;

        // 格式化结果
        String shortSha = sha.length() >= 7 ? sha.substring(0, 7) : sha;
        String target = String.format("Commit %s (%s/%s)", shortSha, owner, repo);
        String formattedResult = codeReviewService.formatReviewResult(result, target, commitUrl);

        return formattedResult;
    }

    /**
     * 构建帮助文本
     */
    private String buildHelpText() {
        return """
                ❌ 用法：/review <仓库别名> <PR号或commit>

                📋 示例：
                /review frontend 42               - 审查 frontend 仓库的 PR #42
                /review frontend commit abc1234   - 审查 frontend 仓库的提交 abc1234
                /review frontend abc1234          - 自动识别为 commit hash，审查该提交

                🔄 兼容旧命令：
                /cr owner/repo 123               - 审查指定仓库的 PR

                💡 功能说明：
                使用 AI 自动分析代码，给出审查建议
                包含：评分、问题列表、修改建议
                """;
    }
}
