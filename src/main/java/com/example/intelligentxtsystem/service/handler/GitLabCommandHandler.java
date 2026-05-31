package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.annotation.Command;
import com.example.intelligentxtsystem.client.GitLabClient;
import com.example.intelligentxtsystem.dto.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * GitLab CI/CD 指令处理器（新框架版本）
 * 指令格式：
 * /gitlab pipeline <项目> <分支> - 触发流水线
 * /gitlab status <项目> <pipeline-id> - 查询状态
 * /gitlab list <项目> - 列出最近流水线
 * /gitlab cancel <项目> <pipeline-id> - 取消流水线
 */
@Component
public class GitLabCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(GitLabCommandHandler.class);

    @Autowired(required = false)
    private GitLabClient gitLabClient;

    @Command(
        name = "gitlab",
        description = "GitLab CI/CD 管理",
        permissionLevel = "DEVELOPER",
        usage = "/gitlab <子命令>"
    )
    public String handle(CommandContext context) {
        String gitlabCmd = context.getArgs().trim();

        if (gitlabCmd.isEmpty()) {
            return buildGitLabHelp();
        }

        if (gitLabClient == null || !gitLabClient.isEnabled()) {
            return """
                    ⚠️ GitLab 集成未启用

                    请配置以下环境变量：
                    • gitlab.token - GitLab Private Token
                    • gitlab.enabled - 设置为 true

                    💡 获取 Token：GitLab → User Settings → Access Tokens
                    """;
        }

        // /gitlab pipeline <项目> <分支>
        if (gitlabCmd.startsWith("pipeline ")) {
            String[] parts = gitlabCmd.substring(9).split("\\s+", 2);
            if (parts.length < 2) {
                return "❌ 用法：/gitlab pipeline <项目ID或路径> <分支>\n示例：/gitlab pipeline mygroup/myproject main";
            }
            String projectId = parts[0];
            String ref = parts[1];
            try {
                return gitLabClient.triggerPipeline(projectId, ref, null);
            } catch (Exception e) {
                log.error("GitLab 触发流水线失败", e);
                return "❌ 触发失败: " + e.getMessage();
            }
        }

        // /gitlab status <项目> <pipeline-id>
        if (gitlabCmd.startsWith("status ")) {
            String[] parts = gitlabCmd.substring(7).split("\\s+", 2);
            if (parts.length < 2) {
                return "❌ 用法：/gitlab status <项目ID> <pipeline-id>\n示例：/gitlab status mygroup/myproject 123";
            }
            String projectId = parts[0];
            try {
                int pipelineId = Integer.parseInt(parts[1]);
                return gitLabClient.getPipelineStatus(projectId, pipelineId);
            } catch (NumberFormatException e) {
                return "❌ Pipeline ID 必须是数字";
            }
        }

        // /gitlab list <项目>
        if (gitlabCmd.startsWith("list ")) {
            String projectId = gitlabCmd.substring(5).trim();
            return gitLabClient.listPipelines(projectId, 10);
        }

        // /gitlab cancel <项目> <pipeline-id>
        if (gitlabCmd.startsWith("cancel ")) {
            String[] parts = gitlabCmd.substring(7).split("\\s+", 2);
            if (parts.length < 2) {
                return "❌ 用法：/gitlab cancel <项目ID> <pipeline-id>";
            }
            String projectId = parts[0];
            try {
                int pipelineId = Integer.parseInt(parts[1]);
                return gitLabClient.cancelPipeline(projectId, pipelineId);
            } catch (NumberFormatException e) {
                return "❌ Pipeline ID 必须是数字";
            }
        }

        return buildGitLabHelp();
    }

    private String buildGitLabHelp() {
        return """
                ❌ GitLab 命令格式错误

                🚀 可用命令：
                • /gitlab pipeline <项目> <分支> - 触发流水线
                • /gitlab status <项目> <pipeline-id> - 查询状态
                • /gitlab list <项目> - 列出最近流水线
                • /gitlab cancel <项目> <pipeline-id> - 取消流水线

                💡 项目可以是：
                • 项目 ID（数字）
                • URL 编码路径（如 mygroup%2Fmyproject）
                • 或直接路径（如 mygroup/myproject）

                📝 示例：
                /gitlab pipeline 12345 main
                /gitlab pipeline mygroup/myproject main
                /gitlab status mygroup/myproject 123
                """;
    }
}
