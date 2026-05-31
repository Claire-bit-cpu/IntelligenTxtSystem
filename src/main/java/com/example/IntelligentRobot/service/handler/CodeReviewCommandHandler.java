package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.GitHubClient;
import com.example.IntelligentRobot.config.GitHubConfig;
import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.service.CodeReviewService;
import com.example.IntelligentRobot.service.TaskMonitorService;
import com.example.IntelligentRobot.task.AsyncTaskStatus;
import com.example.IntelligentRobot.task.TaskContext;
import com.example.IntelligentRobot.task.TaskStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码审查指令处理器（新框架版本）
 * 指令格式：
 *   /review <仓库别名> <PR号>        - 审查 PR
 *   /review <仓库别名> commit <SHA>   - 审查提交
 *   /cr <owner/repo> <PR号>         - 兼容旧命令
 */
@Component
public class CodeReviewCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewCommandHandler.class);

    private final CodeReviewService codeReviewService;
    private final GitHubClient gitHubClient;
    private final GitHubConfig gitHubConfig;
    private final TaskStatusService taskStatusService;
    private final TaskMonitorService taskMonitorService;

    // /review frontend 42 或 /review frontend commit abc123
    private static final Pattern REVIEW_ALIAS_PATTERN =
            Pattern.compile("(\\S+)\\s+(commit\\s+)?(\\S+)");

    // /cr owner/repo 123 (兼容旧命令)
    private static final Pattern CR_PATTERN =
            Pattern.compile("([^/]+)/([^\\s]+)\\s+(\\d+)");

    // 当前任务ID
    private String currentTaskId = null;

    public CodeReviewCommandHandler(CodeReviewService codeReviewService,
                            GitHubClient gitHubClient,
                            GitHubConfig gitHubConfig,
                            TaskStatusService taskStatusService,
                            TaskMonitorService taskMonitorService) {
        this.codeReviewService = codeReviewService;
        this.gitHubClient = gitHubClient;
        this.gitHubConfig = gitHubConfig;
        this.taskStatusService = taskStatusService;
        this.taskMonitorService = taskMonitorService;
    }

    @Command(
        name = "review",
        description = "代码审查（AI 分析）",
        permissionLevel = "DEVELOPER",
        usage = "/review <仓库别名> <PR号或commit>"
    )
    public String handle(CommandContext context) {
        String args = context.getArgs().trim();

        // 创建任务并显示任务面板
        createTaskAndShowPanel(context, args);

        // 更新任务状态：开始处理
        TaskContext.updateProgress(10, "开始处理代码审查请求");

        // 构建返回消息（包含任务面板）
        StringBuilder response = new StringBuilder();
        response.append(buildTaskPanel());
        response.append("\n---\n\n");

        if (args.isEmpty()) {
            TaskContext.updateProgress(0, "参数为空，显示帮助");
            response.append(buildHelpText());
            return response.toString();
        }

        // 检查是否配置了 GitHub Token
        if (!gitHubClient.isConfigured()) {
            TaskContext.updateProgress(0, "代码审查功能未配置");
            response.append("""
                    ⚠️ 代码审查功能未配置

                    请配置 github.token 环境变量
                    当前功能受限
                    """);
            return response.toString();
        }

        // 更新任务状态：正在解析参数
        TaskContext.updateProgress(30, "正在解析审查参数");

        // 尝试匹配新命令格式：/review <别名> <PR号或commit>
        Matcher reviewMatcher = REVIEW_ALIAS_PATTERN.matcher(args);
        if (reviewMatcher.find()) {
            // 更新任务状态：正在执行代码审查
            TaskContext.updateProgress(50, "正在执行代码审查（AI 分析中）");
            String result = handleReviewAlias(reviewMatcher);
            // 更新任务状态：审查完成
            TaskContext.updateProgress(100, "代码审查完成");
            response.append(result);
            return response.toString();
        }

        // 尝试匹配旧命令格式：/cr owner/repo PR号
        Matcher crMatcher = CR_PATTERN.matcher(args);
        if (crMatcher.find()) {
            // 更新任务状态：正在执行代码审查
            TaskContext.updateProgress(50, "正在执行代码审查（AI 分析中）");
            String result = handleCrCommand(crMatcher);
            // 更新任务状态：审查完成
            TaskContext.updateProgress(100, "代码审查完成");
            response.append(result);
            return response.toString();
        }

        // 命令格式错误，显示帮助
        TaskContext.updateProgress(0, "参数格式错误");
        response.append(buildHelpText());
        return response.toString();
    }
    
    /**
     * 构建任务面板文本
     */
    private String buildTaskPanel() {
        if (currentTaskId == null) {
            return "";
        }
        
        AsyncTaskStatus task = taskStatusService.get(currentTaskId);
        if (task == null) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 **代码审查任务已创建**\n\n");
        sb.append("📋 **任务信息**\n");
        sb.append(String.format("任务ID: `%s`\n", task.getTaskId().substring(0, 8) + "..."));
        sb.append(String.format("状态: %s\n", getStatusText(task.getStatus())));
        sb.append(String.format("状态信息: %s\n", task.getStatusMsg() != null ? task.getStatusMsg() : "-"));
        
        if (task.getCreatedAt() != null) {
            sb.append(String.format("创建时间: %s\n", task.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
        }
        
        sb.append("\n💡 提示: 使用 `/task ").append(task.getTaskId()).append("` 查看任务详情\n");
        
        return sb.toString();
    }
    
    /**
     * 获取状态文本
     */
    private String getStatusText(AsyncTaskStatus.Status status) {
        if (status == null) return "⏳ 未知";
        return switch (status) {
            case PENDING -> "⏳ 待处理";
            case PROCESSING -> "🔄 处理中";
            case COMPLETED -> "✅ 已完成";
            case FAILED -> "❌ 失败";
        };
    }
    
    /**
     * 构建进度条
     */
    private String buildProgressBar(int progress) {
        int bars = progress / 10;
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < 10; i++) {
            sb.append(i < bars ? "█" : "░");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 创建任务并展示任务面板
     */
    private void createTaskAndShowPanel(CommandContext context, String args) {
        try {
            // 创建任务
            String taskId = UUID.randomUUID().toString();
            currentTaskId = taskId;
            
            // 初始化任务状态
            AsyncTaskStatus taskStatus = new AsyncTaskStatus();
            taskStatus.setTaskId(taskId);
            taskStatus.setStatus(AsyncTaskStatus.Status.PENDING);
            taskStatus.setProgress(0);
            taskStatus.setStatusMsg("任务已创建，准备执行代码审查");
            taskStatus.setEventType("code_review"); // 设置事件类型为代码审查
            taskStatus.setCreatedAt(LocalDateTime.now());
            taskStatus.setUpdatedAt(LocalDateTime.now());
            
            // 保存任务状态
            taskStatusService.save(taskStatus);
            
            // 更新 TaskContext
            TaskContext.setTaskId(taskId);
            TaskContext.updateProgress(5, "任务已创建，准备执行代码审查");
            
            log.info("代码审查任务已创建，任务ID: {}", taskId);
            
        } catch (Exception e) {
            log.error("创建任务失败", e);
        }
    }

    /**
     * 处理 /review <别名> <PR号或commit> 格式
     */
    private String handleReviewAlias(Matcher matcher) {
        String repoAlias = matcher.group(1);
        boolean isCommit = matcher.group(2) != null;
        String target = matcher.group(3);

        // 解析仓库别名
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
        try {
            // 更新任务状态：正在执行代码审查
            TaskContext.updateProgress(50, "正在执行代码审查（AI 分析中）");
            taskMonitorService.triggerPush();
            
            // 调用审查服务
            com.example.IntelligentRobot.dto.CodeReviewResult result =
                    codeReviewService.reviewPullRequest(owner, repo, prNumber);

            // 获取 PR 链接
            String prUrl = "https://github.com/" + owner + "/" + repo + "/pull/" + prNumber;

            // 格式化结果
            String target = String.format("PR #%d (%s/%s)", prNumber, owner, repo);
            String formattedResult = codeReviewService.formatReviewResult(result, target, prUrl);

            // 更新任务状态：审查完成
            TaskContext.updateProgress(100, "代码审查完成");
            taskMonitorService.triggerPush();
            
            return formattedResult;
        } catch (Exception e) {
            // 更新任务状态：审查失败
            TaskContext.updateProgress(0, "代码审查失败: " + e.getMessage());
            taskMonitorService.triggerPush();
            throw e;
        }
    }

    /**
     * 审查 Commit
     */
    private String reviewCommit(String owner, String repo, String sha) {
        try {
            // 更新任务状态：正在执行代码审查
            TaskContext.updateProgress(50, "正在执行代码审查（AI 分析中）");
            taskMonitorService.triggerPush();
            
            // 调用审查服务
            com.example.IntelligentRobot.dto.CodeReviewResult result =
                    codeReviewService.reviewCommit(owner, repo, sha);

            // 获取 Commit 链接
            String commitUrl = "https://github.com/" + owner + "/" + repo + "/commit/" + sha;

            // 格式化结果
            String shortSha = sha.length() >= 7 ? sha.substring(0, 7) : sha;
            String target = String.format("Commit %s (%s/%s)", shortSha, owner, repo);
            String formattedResult = codeReviewService.formatReviewResult(result, target, commitUrl);

            // 更新任务状态：审查完成
            TaskContext.updateProgress(100, "代码审查完成");
            taskMonitorService.triggerPush();
            
            return formattedResult;
        } catch (Exception e) {
            // 更新任务状态：审查失败
            TaskContext.updateProgress(0, "代码审查失败: " + e.getMessage());
            taskMonitorService.triggerPush();
            throw e;
        }
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
