package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.client.GitHubClient;
import com.example.intelligentxtsystem.config.GitHubConfig;
import com.example.intelligentxtsystem.dto.FeishuSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Git 提交差异查询（GitHub）
 * 指令格式：/gitdiff <仓库别名> <commit_sha>
 * 示例：/gitdiff frontend abc12345
 */
@Component
public class GitDiffHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(GitDiffHandler.class);

    private final GitHubClient gitHubClient;
    private final GitHubConfig gitHubConfig;

    public GitDiffHandler(GitHubClient gitHubClient, GitHubConfig gitHubConfig) {
        this.gitHubClient = gitHubClient;
        this.gitHubConfig = gitHubConfig;
    }

    @Override
    public boolean support(String text) {
        return text.trim().toLowerCase().startsWith("/gitdiff");
    }

    @Override
    public String handle(String text, FeishuSender sender, String chatId) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 3) {
            return "❌ 用法：/gitdiff <仓库别名> <commit_sha>\n示例：/gitdiff frontend abc12345";
        }

        String repoAlias = parts[1];
        String sha = parts[2];

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
            List<Map<String, Object>> files = gitHubClient.getCommitFiles(owner, repo, sha);

            if (files == null || files.isEmpty()) {
                return "📝 提交 `" + sha.substring(0, Math.min(8, sha.length())) + "` 无文件变更";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📝 **提交差异**：`").append(sha, 0, Math.min(8, sha.length())).append("`\n\n");

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
