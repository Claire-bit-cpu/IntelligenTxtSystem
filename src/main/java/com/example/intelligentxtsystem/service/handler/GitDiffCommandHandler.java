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
 * Git 提交差异查询指令处理器（新框架版本）
 * 指令格式：/gitdiff <仓库别名> <commit_sha>
 */
@Component
public class GitDiffCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(GitDiffCommandHandler.class);

    private final GitHubClient gitHubClient;
    private final GitHubConfig gitHubConfig;

    public GitDiffCommandHandler(GitHubClient gitHubClient, GitHubConfig gitHubConfig) {
        this.gitHubClient = gitHubClient;
        this.gitHubConfig = gitHubConfig;
    }

    @Command(
        name = "gitdiff",
        description = "查看 Git 提交差异",
        usage = "/gitdiff <仓库别名> <commit_sha>"
    )
    public String handle(CommandContext context) {
        String args = context.getArgs().trim();
        String[] parts = args.split("\\s+");
        
        if (parts.length < 2) {
            return "❌ 用法：/gitdiff <仓库别名> <commit_sha>\n示例：/gitdiff frontend abc12345";
        }

        String repoAlias = parts[0];
        String sha = parts[1];

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
            // 获取提交详情（包含 stats 和 commit message）
            Map<String, Object> commitDetail = gitHubClient.getCommit(owner, repo, sha);
            List<Map<String, Object>> files = gitHubClient.getCommitFiles(owner, repo, sha);

            if (files == null || files.isEmpty()) {
                return "📝 提交 `" + sha.substring(0, Math.min(8, sha.length())) + "` 无文件变更";
            }

            // 解析提交信息
            String commitMsg = "";
            String authorName = "";
            String authorDate = "";
            int totalAdditions = 0;
            int totalDeletions = 0;
            
            if (commitDetail != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> commitInfo = (Map<String, Object>) commitDetail.get("commit");
                if (commitInfo != null) {
                    commitMsg = (String) commitInfo.get("message");
                    if (commitMsg != null && commitMsg.contains("\n")) {
                        commitMsg = commitMsg.substring(0, commitMsg.indexOf("\n"));
                    }
                    if (commitMsg != null && commitMsg.length() > 100) {
                        commitMsg = commitMsg.substring(0, 100) + "...";
                    }
                    
                    @SuppressWarnings("unchecked")
                    Map<String, Object> author = (Map<String, Object>) commitInfo.get("author");
                    if (author != null) {
                        authorName = (String) author.get("name");
                        authorDate = (String) author.get("date");
                        if (authorDate != null && authorDate.length() >= 10) {
                            authorDate = authorDate.substring(0, 10);
                        }
                    }
                }
                
                // 获取 stats
                if (commitDetail.containsKey("stats")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> stats = (Map<String, Object>) commitDetail.get("stats");
                    totalAdditions = (Integer) stats.get("additions");
                    totalDeletions = (Integer) stats.get("deletions");
                }
            }
            
            // 如果 commitDetail 中没有 stats，从 files 计算
            if (totalAdditions == 0 && totalDeletions == 0) {
                for (Map<String, Object> file : files) {
                    totalAdditions += (Integer) file.get("additions");
                    totalDeletions += (Integer) file.get("deletions");
                }
            }
            
            // 统计文件变更类型
            int addedCount = 0, modifiedCount = 0, deletedCount = 0, renamedCount = 0;
            for (Map<String, Object> file : files) {
                String status = (String) file.get("status");
                switch (status) {
                    case "added" -> addedCount++;
                    case "modified" -> modifiedCount++;
                    case "deleted" -> deletedCount++;
                    case "renamed" -> renamedCount++;
                }
            }

            StringBuilder sb = new StringBuilder();
            
            // 差异总结
            sb.append("📝 **提交差异总结**\n\n");
            sb.append("🔖 SHA：`").append(sha, 0, Math.min(8, sha.length())).append("`\n");
            if (!commitMsg.isEmpty()) {
                sb.append("📋 消息：").append(commitMsg).append("\n");
            }
            if (!authorName.isEmpty()) {
                sb.append("👤 作者：").append(authorName).append("\n");
            }
            if (!authorDate.isEmpty()) {
                sb.append("📅 日期：").append(authorDate).append("\n");
            }
            sb.append("📊 总变更：+").append(totalAdditions).append(" / -").append(totalDeletions).append("\n");
            sb.append("📂 文件：共 ").append(files.size()).append(" 个文件");
            
            StringBuilder fileTypes = new StringBuilder();
            if (addedCount > 0) fileTypes.append(" 新增").append(addedCount);
            if (modifiedCount > 0) fileTypes.append(" 修改").append(modifiedCount);
            if (deletedCount > 0) fileTypes.append(" 删除").append(deletedCount);
            if (renamedCount > 0) fileTypes.append(" 重命名").append(renamedCount);
            if (fileTypes.length() > 0) {
                sb.append("（").append(fileTypes.toString().trim()).append("）");
            }
            sb.append("\n\n");
            sb.append("---\n\n");

            // 文件详情
            int fileCount = 0;
            for (Map<String, Object> file : files) {
                if (fileCount >= 5) {
                    sb.append("\n... 还有 ").append(files.size() - 5).append(" 个文件未显示");
                    break;
                }

                String fileName = (String) file.get("filename");
                String status = (String) file.get("status");
                int additions = (Integer) file.get("additions");
                int deletions = (Integer) file.get("deletions");
                String patch = (String) file.get("patch");

                sb.append("**📄 ").append(fileName).append("** (").append(status).append(" ");
                sb.append("+").append(additions).append("/-").append(deletions).append(")\n");
                
                if (patch != null) {
                    sb.append("```diff\n");
                    // 只显示前 20 行 diff
                    String[] lines = patch.split("\n");
                    int lineCount = 0;
                    for (String line : lines) {
                        if (lineCount >= 20) {
                            sb.append("... 省略更多行\n");
                            break;
                        }
                        sb.append(line).append("\n");
                        lineCount++;
                    }
                    sb.append("```\n\n");
                } else {
                    sb.append("\n");
                }

                fileCount++;
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("查询 Git diff 失败", e);
            return "❌ 查询失败：" + e.getMessage();
        }
    }
}
