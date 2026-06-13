package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.FeishuClient;
import com.example.IntelligentRobot.client.GitHubClient;
import com.example.IntelligentRobot.config.GitHubConfig;
import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.task.AsyncTaskStatus;
import com.example.IntelligentRobot.task.TaskContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Git 提交差异查询指令处理器（新框架版本）
 * 指令格式：/gitdiff <仓库别名> <commit_sha>
 * 使用飞书卡片消息展示差异，提供更清晰的视觉效果
 */
@Component
public class GitDiffCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(GitDiffCommandHandler.class);

    private final GitHubClient gitHubClient;
    private final GitHubConfig gitHubConfig;
    private final FeishuClient feishuClient;
    private final ObjectMapper objectMapper;

    public GitDiffCommandHandler(GitHubClient gitHubClient, GitHubConfig gitHubConfig,
                                FeishuClient feishuClient, ObjectMapper objectMapper) {
        this.gitHubClient = gitHubClient;
        this.gitHubConfig = gitHubConfig;
        this.feishuClient = feishuClient;
        this.objectMapper = objectMapper;
    }

    @Command(
        name = "gitdiff",
        description = "查看 Git 提交差异",
        usage = "/gitdiff <仓库别名> <commit_sha>"
    )
    public String handle(CommandContext context) {
        String args = context.getArgs().trim();
        String[] parts = args.split("\\s+");
        
        // 创建任务并发送"任务已触发"通知
        String taskId = UUID.randomUUID().toString();
        TaskContext.setTaskId(taskId);
        
        String chatId = context.getChatId();
        if (chatId != null && !chatId.isEmpty()) {
            String notification = String.format("""
                        📝 Git 差异查询任务已触发

                        🆔 任务ID: `%s`
                        📝 参数: %s
                        🕐 触发时间: %s

                        ⏳ 正在处理中，请稍候...
                        """, 
                        taskId.substring(0, 8) + "...",
                        args,
                        LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            
            feishuClient.sendText(chatId, notification);
            log.info("Git 差异查询任务已触发通知已发送: taskId={}, chatId={}", taskId, chatId);
        }
        
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

            // 构建飞书卡片消息
            String cardJson = buildGitDiffCard(sha, commitMsg, authorName, authorDate,
                    totalAdditions, totalDeletions, files, addedCount, modifiedCount,
                    deletedCount, renamedCount, repoFullName);

            // 如果卡片构建成功，返回卡片 JSON（以 __CARD__ 开头，MessageProcessor 会识别并发送卡片）
            if (cardJson != null && !cardJson.isEmpty() && !cardJson.equals("{}")) {
                return "__CARD__" + cardJson;
            } else {
                // 卡片构建失败，降级为文本消息
                log.warn("Git diff 卡片构建失败，降级为文本消息");
                return buildTextFallback(sha, commitMsg, authorName, authorDate,
                        totalAdditions, totalDeletions, files, addedCount, modifiedCount,
                        deletedCount, renamedCount);
            }

        } catch (Exception e) {
            log.error("查询 Git diff 失败", e);
            return "❌ 查询失败：" + e.getMessage();
        }
    }

    /**
     * 构建 Git diff 飞书卡片 JSON
     */
    private String buildGitDiffCard(String sha, String commitMsg, String authorName, String authorDate,
                                   int totalAdditions, int totalDeletions,
                                   List<Map<String, Object>> files,
                                   int addedCount, int modifiedCount, int deletedCount, int renamedCount,
                                   String repoFullName) {
        try {
            Map<String, Object> card = new HashMap<>();
            card.put("config", Map.of("wide_screen_mode", true));

            // Header
            Map<String, Object> header = new HashMap<>();
            header.put("title", Map.of("tag", "plain_text", "content", "📝 Git 提交差异"));
            header.put("template", "blue");
            card.put("header", header);

            // Elements
            List<Map<String, Object>> elements = new ArrayList<>();

            // 提交信息
            StringBuilder infoText = new StringBuilder();
            infoText.append("**🔖 SHA：** `").append(sha, 0, Math.min(8, sha.length())).append("`\n");
            if (!commitMsg.isEmpty()) {
                infoText.append("**📋 消息：** ").append(escapeMarkdown(commitMsg)).append("\n");
            }
            if (!authorName.isEmpty()) {
                infoText.append("**👤 作者：** ").append(escapeMarkdown(authorName)).append("\n");
            }
            if (!authorDate.isEmpty()) {
                infoText.append("**📅 日期：** ").append(authorDate).append("\n");
            }
            infoText.append("**📊 总变更：** +").append(totalAdditions).append(" / -").append(totalDeletions).append("\n");
            infoText.append("**📂 文件：** 共 ").append(files.size()).append(" 个文件");

            StringBuilder fileTypes = new StringBuilder();
            if (addedCount > 0) fileTypes.append(" 新增").append(addedCount);
            if (modifiedCount > 0) fileTypes.append(" 修改").append(modifiedCount);
            if (deletedCount > 0) fileTypes.append(" 删除").append(deletedCount);
            if (renamedCount > 0) fileTypes.append(" 重命名").append(renamedCount);
            if (fileTypes.length() > 0) {
                infoText.append("（").append(fileTypes.toString().trim()).append("）");
            }

            elements.add(Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", infoText.toString())));
            elements.add(Map.of("tag", "hr"));

            // 文件详情（最多显示5个）
            int fileCount = 0;
            for (Map<String, Object> file : files) {
                if (fileCount >= 5) {
                    elements.add(Map.of("tag", "div", "text", Map.of("tag", "lark_md",
                            "content", "💡 还有 " + (files.size() - 5) + " 个文件未显示，请在 GitHub 查看完整差异")));
                    break;
                }

                String fileName = (String) file.get("filename");
                String status = (String) file.get("status");
                int additions = (Integer) file.get("additions");
                int deletions = (Integer) file.get("deletions");
                String patch = (String) file.get("patch");

                // 文件信息
                String statusEmoji = switch (status) {
                    case "added" -> "✅";
                    case "modified" -> "📝";
                    case "deleted" -> "❌";
                    case "renamed" -> "🔄";
                    default -> "📄";
                };

                StringBuilder fileInfo = new StringBuilder();
                fileInfo.append("**").append(statusEmoji).append(" ").append(escapeMarkdown(fileName)).append("**\n");
                fileInfo.append("状态：").append(status).append(" | +").append(additions).append("/-").append(deletions);

                elements.add(Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", fileInfo.toString())));

                // 代码差异（如果有）
                if (patch != null && !patch.isEmpty()) {
                    String truncatedPatch = truncatePatch(patch, 15);
                    if (truncatedPatch != null) {
                        // 使用 Markdown 代码块嵌入 diff 内容
                        String diffMarkdown = "```diff\n" + truncatedPatch + "\n```";
                        elements.add(Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", diffMarkdown)));
                    }
                }

                elements.add(Map.of("tag", "hr"));
                fileCount++;
            }

            // 查看完整差异按钮
            String githubUrl = "https://github.com/" + repoFullName + "/commit/" + sha;
            elements.add(Map.of("tag", "action", "actions", List.of(
                    Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", "🔗 查看完整差异"),
                            "url", githubUrl, "type", "primary")
            )));

            card.put("elements", elements);
            return objectMapper.writeValueAsString(card);
        } catch (Exception e) {
            log.error("构建 Git diff 卡片失败", e);
            return null;
        }
    }

    /**
     * 构建降级文本消息（当卡片发送失败时）
     */
    private String buildTextFallback(String sha, String commitMsg, String authorName, String authorDate,
                                     int totalAdditions, int totalDeletions,
                                     List<Map<String, Object>> files,
                                     int addedCount, int modifiedCount, int deletedCount, int renamedCount) {
        StringBuilder sb = new StringBuilder();

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
    }

    /**
     * 截断 patch 内容（限制行数）
     */
    private String truncatePatch(String patch, int maxLines) {
        if (patch == null) return null;
        String[] lines = patch.split("\n");
        if (lines.length <= maxLines) {
            return patch;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append("... 省略更多行");
        return sb.toString();
    }

    /**
     * 转义飞书 Markdown 特殊字符
     */
    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("`", "\\`")
                .replace("[", "\\[")
                .replace("]", "\\]");
    }
}
