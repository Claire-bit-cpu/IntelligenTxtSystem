package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.client.FeishuClient;
import com.example.intelligentxtsystem.client.GitHubClient;
import com.example.intelligentxtsystem.config.GitHubConfig;
import com.example.intelligentxtsystem.dto.FeishuSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Git 提交日志查询（GitHub）
 * 指令格式：/gitlog <仓库别名> [条数] [分支]
 * 示例：/gitlog frontend 10 main
 */
@Component
public class GitLogHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(GitLogHandler.class);

    private final GitHubClient gitHubClient;
    private final GitHubConfig gitHubConfig;
    private final FeishuClient feishuClient;

    public GitLogHandler(GitHubClient gitHubClient, GitHubConfig gitHubConfig, FeishuClient feishuClient) {
        this.gitHubClient = gitHubClient;
        this.gitHubConfig = gitHubConfig;
        this.feishuClient = feishuClient;
    }

    @Override
    public boolean support(String text) {
        return text.trim().toLowerCase().startsWith("/gitlog");
    }

    @Override
    public String handle(String text, FeishuSender sender, String chatId) {
        String[] parts = text.trim().split("\\s+");
        
        log.info("解析 /gitlog 命令: text={}, parts={}", text, String.join(",", parts));
        
        if (parts.length < 2) {
            return "❌ 用法：/gitlog <仓库别名> [条数] [分支]\n示例：/gitlog frontend 10 main";
        }

        String repoAlias = parts[1];
        int limit = (parts.length >= 3) ? Integer.parseInt(parts[2]) : 10;
        String branch = (parts.length >= 4) ? parts[3] : null;
        
        log.info("解析结果: repoAlias={}, limit={}, branch={}", repoAlias, limit, branch);

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
            log.info("【调试】开始调用 gitHubClient.getCommits: owner={}, repo={}, limit={}, branch={}", owner, repo, limit, branch);
            
            List<Map<String, Object>> commits = gitHubClient.getCommits(owner, repo, limit, branch);
            
            log.info("【调试】gitHubClient.getCommits 返回: commits={}", commits != null ? "size=" + commits.size() : "null");

            if (commits == null || commits.isEmpty()) {
                log.warn("【调试】提交列表为空: repo={}/{}, limit={}", owner, repo, limit);
                return "📋 仓库 " + repoAlias + " 暂无提交记录";
            }

            log.info("【调试】开始处理 {} 条提交记录", commits.size());
            
            StringBuilder sb = new StringBuilder();
            sb.append("📋 **").append(repoAlias).append("** 最近 ").append(commits.size()).append(" 条提交：\n\n");

            int processedCount = 0;
            for (Map<String, Object> commit : commits) {
                processedCount++;
                
                // 调试：打印提交对象的关键字段
                log.debug("【调试】处理提交[{}/{}]: commit={}", processedCount, commits.size(), 
                    commit != null ? "sha=" + commit.get("sha") : "null");
                
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
                    
                    log.debug("【调试】提交[{}]处理完成: sha={}, author={}, date={}", processedCount, sha, author, date);
                } else {
                    log.warn("【调试】提交[{}]的commitInfo为null: sha={}", processedCount, sha);
                }
            }
            
            log.info("【调试】提交记录处理完成: 总数={}, 处理数={}", commits.size(), processedCount);

            return sb.toString();

        } catch (Exception e) {
            log.error("【调试】查询 Git 日志失败: repo={}/{}, limit={}", owner, repo, limit, e);
            return "❌ 查询失败：" + e.getMessage();
        }
    }
}
