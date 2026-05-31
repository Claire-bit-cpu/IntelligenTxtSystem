/*
 * JIRA API 客户端
 * 支持：查询任务、创建任务、更新任务状态
 * 特性：支持本地降级模式（当未配置 JIRA 云服务时，记录到本地文件）
 */
package com.example.intelligentxtsystem.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Component
public class JiraClient {

    private static final Logger log = LoggerFactory.getLogger(JiraClient.class);

    @Value("${jira.url:https://your-domain.atlassian.net}")
    private String jiraUrl;

    @Value("${jira.username:}")
    private String username;

    @Value("${jira.api-token:}")
    private String apiToken;

    @Value("${jira.enabled:false}")
    private boolean enabled;

    /**
     * 本地任务文件路径（降级模式使用）
     */
    @Value("${jira.local-fallback-file:./local_tasks.md}")
    private String localFallbackFile;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public JiraClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 检测是否配置了有效的 JIRA 连接
     * @return true 如果已配置且可以连接
     */
    public boolean isConfigured() {
        return enabled && !username.isEmpty() && !apiToken.isEmpty() && !jiraUrl.contains("your-domain");
    }

    /**
     * 检测是否处于本地降级模式
     * @return true 如果未配置 JIRA 但启用了降级模式
     */
    public boolean isLocalMode() {
        return !isConfigured();
    }

    /**
     * 查询 JIRA 任务
     * @param issueKey 任务编号，如 PROJ-123
     */
    public String getIssue(String issueKey) {
        // 检查是否处于本地降级模式
        if (isLocalMode()) {
            return getIssueFromLocal(issueKey);
        }

        // 正常模式：调用 JIRA API
        try {
            String url = String.format("%s/rest/api/3/issue/%s", jiraUrl, issueKey);

            HttpHeaders headers = buildHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            JsonNode issue = objectMapper.readTree(response.getBody());

            // 解析任务信息
            JsonNode fields = issue.get("fields");
            String summary = fields.get("summary").asText();
            String status = fields.get("status").get("name").asText();
            String priority = fields.has("priority") && !fields.get("priority").isNull()
                    ? fields.get("priority").get("name").asText() : "无";
            String assignee = fields.has("assignee") && !fields.get("assignee").isNull()
                    ? fields.get("assignee").get("displayName").asText() : "未分配";
            String reporter = fields.has("reporter") && !fields.get("reporter").isNull()
                    ? fields.get("reporter").get("displayName").asText() : "未知";
            String created = fields.has("created") ? fields.get("created").asText() : "";
            String description = fields.has("description") && !fields.get("description").isNull()
                    ? fields.get("description").asText() : "无描述";

            // 简化描述（如果是 Atlassian Document Format）
            if (fields.has("description") && !fields.get("description").isNull()) {
                JsonNode descNode = fields.get("description");
                if (descNode.has("content")) {
                    description = extractTextFromADF(descNode);
                }
            }

            return String.format(
                    "📋 JIRA 任务详情\n\n" +
                    "🔖 编号：%s\n" +
                    "📝 标题：%s\n" +
                    "📊 状态：%s\n" +
                    "⚡ 优先级：%s\n" +
                    "👤 负责人：%s\n" +
                    "📝 报告人：%s\n" +
                    "📅 创建时间：%s\n" +
                    "📄 描述：%s\n\n" +
                    "🔗 链接：%s/browse/%s",
                    issueKey, summary, status, priority, assignee, reporter,
                    created, description, jiraUrl, issueKey);
        } catch (Exception e) {
            log.error("JIRA 查询任务失败: issueKey={}", issueKey, e);
            // 如果 API 调用失败，尝试从本地文件读取
            log.info("API 调用失败，尝试从本地文件读取");
            return getIssueFromLocal(issueKey);
        }
    }

    /**
     * 从本地文件查询任务（降级模式）
     */
    private String getIssueFromLocal(String issueKey) {
        Path filePath = Paths.get(localFallbackFile);
        
        if (!Files.exists(filePath)) {
            return String.format(
                    "⚠️ 当前处于本地离线模式\n\n" +
                    "📂 本地任务文件：%s\n" +
                    "❌ 未找到任务：%s\n\n" +
                    "💡 使用 /jira create <项目> <标题> 创建新任务",
                    localFallbackFile, issueKey);
        }

        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            
            // 简单解析：查找任务编号
            if (content.contains("## " + issueKey)) {
                return String.format(
                        "📋 本地任务详情（离线模式）\n\n" +
                        "🔖 编号：%s\n" +
                        "📂 来源：%s\n\n" +
                        "📄 任务内容：\n%s\n\n" +
                        "💡 当前处于本地离线模式，已将任务记录至本地文件中",
                        issueKey, localFallbackFile, 
                        extractTaskContent(content, issueKey));
            } else {
                return String.format(
                        "⚠️ 当前处于本地离线模式\n\n" +
                        "📂 本地任务文件：%s\n" +
                        "❌ 未找到任务：%s\n\n" +
                        "💡 使用 /jira create <项目> <标题> 创建新任务",
                        localFallbackFile, issueKey);
            }
        } catch (IOException e) {
            log.error("读取本地任务文件失败", e);
            return "❌ 读取本地任务文件失败: " + e.getMessage();
        }
    }

    /**
     * 从本地文件中提取任务内容
     */
    private String extractTaskContent(String content, String issueKey) {
        String[] lines = content.split("\n");
        StringBuilder taskContent = new StringBuilder();
        boolean inTask = false;
        
        for (String line : lines) {
            if (line.startsWith("## " + issueKey)) {
                inTask = true;
                taskContent.append(line).append("\n");
            } else if (inTask) {
                if (line.startsWith("## ")) {
                    break;
                }
                taskContent.append(line).append("\n");
            }
        }
        
        return taskContent.toString();
    }

    /**
     * 创建 JIRA 任务（Bug 工单）
     */
    public String createIssue(String projectKey, String summary, String description, String issueType) {
        // 检查是否处于本地降级模式
        if (isLocalMode()) {
            return createIssueLocally(projectKey, summary, description, issueType);
        }

        // 正常模式：调用 JIRA API
        try {
            String url = String.format("%s/rest/api/3/issue", jiraUrl);

            HttpHeaders headers = buildHeaders();
            HttpEntity<String> entity = new HttpEntity<>(buildCreateIssueBody(
                    projectKey, summary, description, issueType), headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            JsonNode result = objectMapper.readTree(response.getBody());
            String issueKey = result.get("key").asText();

            return String.format(
                    "✅ JIRA 任务创建成功\n\n" +
                    "🔖 编号：%s\n" +
                    "📝 标题：%s\n" +
                    "📊 类型：%s\n\n" +
                    "🔗 链接：%s/browse/%s",
                    issueKey, summary, issueType, jiraUrl, issueKey);
        } catch (Exception e) {
            log.error("JIRA 创建任务失败，尝试本地降级", e);
            // 如果 API 调用失败，降级到本地模式
            return createIssueLocally(projectKey, summary, description, issueType);
        }
    }

    /**
     * 本地创建任务（降级模式）
     */
    private String createIssueLocally(String projectKey, String summary, String description, String issueType) {
        try {
            Path filePath = Paths.get(localFallbackFile);
            String timestamp = LocalDateTime.now().format(FORMATTER);
            
            // 生成任务编号（格式：项目Key-序号）
            String issueKey = generateLocalIssueKey(filePath, projectKey);
            
            // 构建任务内容
            String taskEntry = String.format(
                    "\n" +
                    "## %s\n" +
                    "\n" +
                    "- **标题**: %s\n" +
                    "- **类型**: %s\n" +
                    "- **状态**: 待处理\n" +
                    "- **优先级**: 中\n" +
                    "- **负责人**: 未分配\n" +
                    "- **报告人**: 本地用户\n" +
                    "- **创建时间**: %s\n" +
                    "- **描述**: %s\n" +
                    "\n" +
                    "---\n",
                    issueKey, summary, issueType, timestamp, 
                    description != null ? description : "无描述"
            );
            
            // 写入文件（追加模式）
            String content = Files.exists(filePath) 
                    ? Files.readString(filePath, StandardCharsets.UTF_8) + taskEntry
                    : "# 本地任务列表\n\n> 当前处于本地离线模式，任务记录在此文件中\n\n" + taskEntry;
            
            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            
            log.info("任务已写入本地文件: issueKey={}, file={}", issueKey, localFallbackFile);
            
            return String.format(
                    "✅ 任务已记录到本地（离线模式）\n\n" +
                    "🔖 编号：%s\n" +
                    "📝 标题：%s\n" +
                    "📊 类型：%s\n" +
                    "📅 创建时间：%s\n" +
                    "📂 文件位置：%s\n\n" +
                    "💡 当前处于本地离线模式，已将任务记录至本地文件中",
                    issueKey, summary, issueType, timestamp, localFallbackFile);
        } catch (IOException e) {
            log.error("写入本地任务文件失败", e);
            return "❌ 写入本地任务文件失败: " + e.getMessage();
        }
    }

    /**
     * 生成本地任务编号
     */
    private String generateLocalIssueKey(Path filePath, String projectKey) throws IOException {
        int maxNum = 0;
        
        if (Files.exists(filePath)) {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            String prefix = "## " + projectKey + "-";
            
            for (String line : content.split("\n")) {
                if (line.startsWith(prefix)) {
                    try {
                        String numStr = line.substring(prefix.length()).trim();
                        int num = Integer.parseInt(numStr);
                        maxNum = Math.max(maxNum, num);
                    } catch (NumberFormatException ignored) {
                        // 忽略格式错误的行
                    }
                }
            }
        }
        
        return projectKey + "-" + (maxNum + 1);
    }

    /**
     * 快速创建 Bug 工单
     */
    public String createBug(String projectKey, String summary, String description) {
        return createIssue(projectKey, summary, description, "Bug");
    }

    /**
     * 查询任务列表（JQL 查询）
     */
    public String searchIssues(String jql, int maxResults) {
        // 检查是否处于本地降级模式
        if (isLocalMode()) {
            return searchIssuesLocally(jql, maxResults);
        }

        // 正常模式：调用 JIRA API
        try {
            String url = String.format("%s/rest/api/3/search?jql=%s&maxResults=%d",
                    jiraUrl, java.net.URLEncoder.encode(jql, StandardCharsets.UTF_8), maxResults);

            HttpHeaders headers = buildHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            JsonNode result = objectMapper.readTree(response.getBody());
            int total = result.get("total").asInt();
            JsonNode issues = result.get("issues");

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("📋 JIRA 任务列表（共 %d 条）\n\n", total));

            for (JsonNode issue : issues) {
                String key = issue.get("key").asText();
                String summary = issue.get("fields").get("summary").asText();
                String status = issue.get("fields").get("status").get("name").asText();
                sb.append(String.format("• %s - %s 【%s】\n", key, summary, status));
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("JIRA 搜索任务失败，尝试本地降级", e);
            return searchIssuesLocally(jql, maxResults);
        }
    }

    /**
     * 本地搜索任务（降级模式）
     */
    private String searchIssuesLocally(String jql, int maxResults) {
        Path filePath = Paths.get(localFallbackFile);
        
        if (!Files.exists(filePath)) {
            return "⚠️ 当前处于本地离线模式\n\n" +
                   "📂 本地任务文件不存在：" + localFallbackFile + "\n\n" +
                   "💡 使用 /jira create <项目> <标题> 创建新任务";
        }

        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            
            StringBuilder sb = new StringBuilder();
            sb.append("📋 本地任务列表（离线模式）\n\n");
            
            int count = 0;
            String currentTask = null;
            StringBuilder taskContent = new StringBuilder();
            
            for (String line : lines) {
                if (line.startsWith("## ")) {
                    // 保存上一个任务
                    if (currentTask != null && count < maxResults) {
                        sb.append(formatLocalTask(currentTask, taskContent.toString()));
                        count++;
                    }
                    
                    // 开始新任务
                    currentTask = line.substring(3).trim();
                    taskContent = new StringBuilder();
                } else if (currentTask != null) {
                    taskContent.append(line).append("\n");
                }
            }
            
            // 保存最后一个任务
            if (currentTask != null && count < maxResults) {
                sb.append(formatLocalTask(currentTask, taskContent.toString()));
                count++;
            }
            
            if (count == 0) {
                return "⚠️ 当前处于本地离线模式\n\n" +
                       "📂 本地任务文件：" + localFallbackFile + "\n" +
                       "📭 暂无任务记录\n\n" +
                       "💡 使用 /jira create <项目> <标题> 创建新任务";
            }
            
            sb.append("\n💡 当前处于本地离线模式，任务记录在本地文件中");
            return sb.toString();
        } catch (IOException e) {
            log.error("读取本地任务文件失败", e);
            return "❌ 读取本地任务文件失败: " + e.getMessage();
        }
    }

    /**
     * 格式化本地任务显示
     */
    private String formatLocalTask(String issueKey, String content) {
        String title = "未知标题";
        String status = "待处理";
        
        // 简单解析
        for (String line : content.split("\n")) {
            if (line.contains("**标题**:")) {
                title = line.substring(line.indexOf("**标题**:") + 6).trim();
            } else if (line.contains("**状态**:")) {
                status = line.substring(line.indexOf("**状态**:") + 6).trim();
            }
        }
        
        return String.format("• %s - %s 【%s】\n", issueKey, title, status);
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (!username.isEmpty() && !apiToken.isEmpty()) {
            // JIRA Cloud 使用 Basic Auth（邮箱:API Token）
            String auth = username + ":" + apiToken;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
            headers.set("Authorization", "Basic " + encodedAuth);
        }

        return headers;
    }

    private String buildCreateIssueBody(String projectKey, String summary,
                                        String description, String issueType) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode fields = objectMapper.createObjectNode();

        ObjectNode project = objectMapper.createObjectNode();
        project.put("key", projectKey);
        fields.set("project", project);

        fields.put("summary", summary);

        ObjectNode issueTypeNode = objectMapper.createObjectNode();
        issueTypeNode.put("name", issueType);
        fields.set("issuetype", issueTypeNode);

        if (description != null && !description.isEmpty()) {
            fields.put("description", description);
        }

        root.set("fields", fields);
        return root.toString();
    }

    private String extractTextFromADF(JsonNode adfNode) {
        StringBuilder text = new StringBuilder();
        if (adfNode.has("content")) {
            for (JsonNode node : adfNode.get("content")) {
                if ("text".equals(node.get("type").asText()) && node.has("text")) {
                    text.append(node.get("text").asText());
                }
                if (node.has("content")) {
                    text.append(extractTextFromADF(node));
                }
            }
        }
        return text.length() > 0 ? text.toString() : "无描述";
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取本地任务文件路径
     */
    public String getLocalFallbackFile() {
        return localFallbackFile;
    }
}
