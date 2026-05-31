package com.example.intelligentxtsystem.service;

import com.example.intelligentxtsystem.client.GitHubClient;
import com.example.intelligentxtsystem.client.QwenClient;
import com.example.intelligentxtsystem.dto.CodeReviewResult;
import com.example.intelligentxtsystem.dto.FeishuSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 代码审查服务
 * 封装代码审查的通用逻辑，供手动触发和自动触发共用
 */
@Service
public class CodeReviewService {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewService.class);

    private final GitHubClient gitHubClient;
    private final QwenClient qwenClient;

    @Value("${notification.default-chat-ids:}")
    private String defaultChatIds;

    public CodeReviewService(GitHubClient gitHubClient, QwenClient qwenClient) {
        this.gitHubClient = gitHubClient;
        this.qwenClient = qwenClient;
    }

    /**
     * 审查 Pull Request
     *
     * @param owner    仓库所有者
     * @param repo     仓库名称
     * @param prNumber PR 编号
     * @return 审查结果
     */
    public CodeReviewResult reviewPullRequest(String owner, String repo, int prNumber) {
        log.info("开始审查 PR: {}/{}/{}", owner, repo, prNumber);

        try {
            // 1. 获取 PR 基本信息
            String prInfo = gitHubClient.getPRInfo(owner, repo, prNumber);

            // 2. 获取 PR 代码差异
            String diff = gitHubClient.getPRDiff(owner, repo, prNumber);

            if (diff == null || diff.isEmpty()) {
                log.warn("无法获取 PR #{} 的代码差异", prNumber);
                return createErrorResult("无法获取代码差异，请检查 PR 是否存在或 Token 权限");
            }

            // 3. AI 代码审查
            CodeReviewResult result = qwenClient.reviewCodeStructured(diff, prInfo);

            log.info("PR #{} 审查完成，评分: {}", prNumber, result.getScore());
            return result;

        } catch (Exception e) {
            log.error("审查 PR 失败: {}/{} #{}", owner, repo, prNumber, e);
            return createErrorResult("代码审查失败：" + e.getMessage());
        }
    }

    /**
     * 审查 Commit
     *
     * @param owner 仓库所有者
     * @param repo  仓库名称
     * @param sha   提交 SHA
     * @return 审查结果
     */
    public CodeReviewResult reviewCommit(String owner, String repo, String sha) {
        log.info("开始审查 Commit: {}/{}/{}", owner, repo, sha);

        try {
            // 1. 获取提交详情
            Map<String, Object> commit = gitHubClient.getCommit(owner, repo, sha);
            if (commit == null) {
                return createErrorResult("无法获取提交信息，请检查 SHA 是否正确");
            }

            // 构建提交信息文本
            String commitInfo = buildCommitInfo(commit, owner, repo);

            // 2. 获取提交代码差异
            String diff = gitHubClient.getCommitDiff(owner, repo, sha);

            if (diff == null || diff.isEmpty()) {
                log.warn("无法获取提交 {} 的代码差异", sha);
                return createErrorResult("无法获取代码差异");
            }

            // 3. AI 代码审查
            CodeReviewResult result = qwenClient.reviewCodeStructured(diff, commitInfo);

            log.info("Commit {} 审查完成，评分: {}", sha, result.getScore());
            return result;

        } catch (Exception e) {
            log.error("审查 Commit 失败: {}/{} {}", owner, repo, sha, e);
            return createErrorResult("代码审查失败：" + e.getMessage());
        }
    }

    /**
     * 构建提交信息文本
     */
    private String buildCommitInfo(Map<String, Object> commit, String owner, String repo) {
        String sha = (String) commit.get("sha");
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> commitInfo = (Map<String, Object>) commit.get("commit");
            String message = commitInfo != null ? (String) commitInfo.get("message") : "N/A";

            @SuppressWarnings("unchecked")
            Map<String, Object> author = commitInfo != null ? (Map<String, Object>) commitInfo.get("author") : null;
            String authorName = author != null ? (String) author.get("name") : "N/A";

            String shortSha = sha != null && sha.length() >= 7 ? sha.substring(0, 7) : sha;

            return String.format("""
                    📝 提交审查
                    
                    📦 仓库：%s/%s
                    🔖 SHA：%s
                    👤 作者：%s
                    📄 提交信息：%s
                    """, owner, repo, shortSha, authorName, message);
        } catch (Exception e) {
            log.warn("解析提交信息失败", e);
            return "提交：" + sha;
        }
    }

    /**
     * 格式化审查结果为文本（用于发送到飞书）
     *
     * @param result    审查结果
     * @param target    审查目标（PR 或 Commit）
     * @param targetUrl 目标链接
     * @return 格式化的文本
     */
    public String formatReviewResult(CodeReviewResult result, String target, String targetUrl) {
        StringBuilder sb = new StringBuilder();

        // 标题
        sb.append("🔍 **代码审查报告**\n\n");

        // 目标信息
        sb.append("**审查目标**：").append(target).append("\n");
        if (targetUrl != null && !targetUrl.isEmpty()) {
            sb.append("**链接**：").append(targetUrl).append("\n");
        }
        sb.append("\n");

        // 评分（用进度条样式）
        int score = result.getScore();
        sb.append("**评分**：");
        if (score >= 90) {
            sb.append("✅ ").append(score).append("/100 (优秀)\n");
        } else if (score >= 70) {
            sb.append("⚠️ ").append(score).append("/100 (良好)\n");
        } else if (score >= 50) {
            sb.append("⚠️ ").append(score).append("/100 (需改进)\n");
        } else {
            sb.append("❌ ").append(score).append("/100 (存在问题)\n");
        }
        sb.append("\n");

        // 问题列表
        sb.append("**问题列表**：\n");
        if (result.getProblems() != null && !result.getProblems().isEmpty()) {
            for (int i = 0; i < result.getProblems().size(); i++) {
                sb.append(i + 1).append(". ").append(result.getProblems().get(i)).append("\n");
            }
        } else {
            sb.append("暂无发现问题\n");
        }
        sb.append("\n");

        // 修改建议
        sb.append("**修改建议**：\n");
        if (result.getSuggestions() != null && !result.getSuggestions().isEmpty()) {
            for (int i = 0; i < result.getSuggestions().size(); i++) {
                sb.append(i + 1).append(". ").append(result.getSuggestions().get(i)).append("\n");
            }
        } else {
            sb.append("暂无修改建议\n");
        }
        sb.append("\n");

        // 详细分析
        if (result.getFullText() != null && !result.getFullText().isEmpty()) {
            sb.append("**详细分析**：\n");
            sb.append(result.getFullText());
        }

        return sb.toString();
    }

    /**
     * 创建错误结果
     */
    private CodeReviewResult createErrorResult(String message) {
        java.util.List<String> problems = new java.util.ArrayList<>();
        problems.add(message);
        return new CodeReviewResult(0, problems, new java.util.ArrayList<>(), message);
    }
}
