package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.JiraClient;
import com.example.IntelligentRobot.dto.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JIRA 指令处理器（新框架版本）
 * 指令格式：
 * /jira <任务编号> - 查询任务
 * /jira create <项目> <标题> - 创建Bug工单
 * /jira search <JQL> - 搜索任务
 */
@Component
public class JiraCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(JiraCommandHandler.class);

    @Autowired(required = false)
    private JiraClient jiraClient;

    @Command(
        name = "jira",
        description = "JIRA 任务管理（强独立性指令）",
        usage = "/jira <任务编号>",
        independent = true  // 强独立性：每次都是全新执行，不继承上下文
    )
    public String handle(CommandContext context) {
        String jiraCmd = context.getArgs().trim();

        if (jiraCmd.isEmpty()) {
            return buildJiraHelp();
        }

        // 检查 JIRA 客户端是否可用
        if (jiraClient == null) {
            return """
                    ⚠️ JIRA 客户端未初始化

                    请检查配置：
                    • jira.enabled - 设置为 true
                    • 确保 JiraClient 已正确注入
                    """;
        }

        // 检查是否处于本地降级模式
        if (jiraClient.isLocalMode()) {
            log.info("JIRA 处于本地降级模式，使用本地文件存储任务");
            // 不返回错误，继续执行（会触发本地降级逻辑）
        } else if (!jiraClient.isEnabled()) {
            return """
                    ⚠️ JIRA 集成未启用

                    请配置以下环境变量：
                    • jira.url - JIRA 地址
                    • jira.username - 用户名（邮箱）
                    • jira.api-token - API Token
                    • jira.enabled - 设置为 true

                    💡 获取 API Token：https://id.atlassian.com/manage-profile/security/api-tokens
                    """;
        }

        // /jira create <项目> <标题>
        if (jiraCmd.startsWith("create ")) {
            String[] parts = jiraCmd.substring(7).split("\\s+", 2);
            if (parts.length < 2) {
                return "❌ 用法：/jira create <项目KEY> <标题>\n示例：/jira create PROJ 修复登录Bug";
            }
            String projectKey = parts[0];
            String summary = parts[1];
            return jiraClient.createBug(projectKey, summary, "");
        }

        // /jira search <JQL>
        if (jiraCmd.startsWith("search ")) {
            String jql = jiraCmd.substring(7);
            return jiraClient.searchIssues(jql, 10);
        }

        // /jira <任务编号> - 查询任务
        String issueKey = jiraCmd.trim();
        if (issueKey.matches("^[A-Z]+-\\d+$")) {
            return jiraClient.getIssue(issueKey);
        }

        return buildJiraHelp();
    }

    private String buildJiraHelp() {
        return """
                ❌ JIRA 命令格式错误

                📋 可用命令：
                • /jira <任务编号> - 查询任务
                • /jira create <项目> <标题> - 创建Bug工单
                • /jira search <JQL> - 搜索任务

                💡 示例：
                /jira PROJ-123
                /jira create PROJ 修复登录Bug
                /jira search assignee=currentUser()
                """;
    }
}
