/*
 * GitLab API 客户端
 * 支持：查询提交日志、查看 diff、创建分支、查看 MR、CI/CD 流水线
 */
package com.example.IntelligentRobot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
public class GitLabClient {

    private static final Logger log = LoggerFactory.getLogger(GitLabClient.class);

    @Value("${gitlab.api-url:https://gitlab.com/api/v4}")
    private String apiBaseUrl;

    @Value("${gitlab.token:}")
    private String privateToken;

    @Value("${gitlab.enabled:false}")
    private boolean enabled;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GitLabClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取仓库最近提交日志
     * @param projectId 项目 ID 或 URL 编码的路径（如 group%2Fproject）
     * @param limit 返回条数
     */
    public List<Map<String, Object>> getCommits(String projectId, int limit) {
        String url = String.format("%s/projects/%s/repository/commits?per_page=%d",
                apiBaseUrl, projectId, limit);
        return executeGet(url, List.class);
    }

    /**
     * 获取特定提交的差异（diff）
     * @param projectId 项目 ID
     * @param sha 提交 SHA
     */
    public List<Map<String, Object>> getCommitDiff(String projectId, String sha) {
        String url = String.format("%s/projects/%s/repository/commits/%s/diff",
                apiBaseUrl, projectId, sha);
        return executeGet(url, List.class);
    }

    /**
     * 创建分支
     * @param projectId 项目 ID
     * @param branchName 新分支名
     * @param ref 源分支或 commit SHA
     */
    public Map<String, Object> createBranch(String projectId, String branchName, String ref) {
        String url = String.format("%s/projects/%s/repository/branches?branch=%s&ref=%s",
                apiBaseUrl, projectId, branchName, ref);
        return executePost(url, null, Map.class);
    }

    /**
     * 获取合并请求（MR）列表
     * @param projectId 项目 ID
     * @param state 状态：opened, closed, merged, all
     */
    public List<Map<String, Object>> getMergeRequests(String projectId, String state) {
        String url = String.format("%s/projects/%s/merge_requests?state=%s&per_page=20",
                apiBaseUrl, projectId, state);
        return executeGet(url, List.class);
    }

    /**
     * 获取 MR 详情
     * @param projectId 项目 ID
     * @param mrIid MR 的 IID（项目内序号）
     */
    public Map<String, Object> getMergeRequest(String projectId, String mrIid) {
        String url = String.format("%s/projects/%s/merge_requests/%s",
                apiBaseUrl, projectId, mrIid);
        return executeGet(url, Map.class);
    }

    /**
     * 获取 MR 变更（diff）
     */
    public Map<String, Object> getMergeRequestChanges(String projectId, String mrIid) {
        String url = String.format("%s/projects/%s/merge_requests/%s/changes",
                apiBaseUrl, projectId, mrIid);
        return executeGet(url, Map.class);
    }

    // ========== CI/CD 流水线功能 ==========

    /**
     * 触发 CI/CD 流水线
     * @param projectId 项目 ID
     * @param ref 分支名或 tag
     * @param variables 环境变量（可选）
     */
    public String triggerPipeline(String projectId, String ref, Map<String, String> variables) {
        if (!enabled) {
            throw new IllegalStateException("GitLab 集成未启用");
        }

        try {
            String url = String.format("%s/projects/%s/pipeline?ref=%s",
                    apiBaseUrl, projectId, ref);

            // 添加变量
            if (variables != null && !variables.isEmpty()) {
                StringBuilder varStr = new StringBuilder(url);
                variables.forEach((k, v) -> varStr.append("&variables[").append(k).append("]=").append(v));
                url = varStr.toString();
            }

            HttpHeaders headers = buildHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            JsonNode result = objectMapper.readTree(response.getBody());
            int pipelineId = result.get("id").asInt();
            String status = result.get("status").asText();

            return String.format("✅ 流水线已触发\nID: %d\n状态: %s\n分支: %s",
                    pipelineId, status, ref);
        } catch (Exception e) {
            log.error("GitLab 触发流水线失败: projectId={}, ref={}", projectId, ref, e);
            throw new RuntimeException("GitLab 触发流水线失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取流水线状态
     * @param projectId 项目 ID
     * @param pipelineId 流水线 ID
     */
    public String getPipelineStatus(String projectId, int pipelineId) {
        if (!enabled) {
            throw new IllegalStateException("GitLab 集成未启用");
        }

        try {
            String url = String.format("%s/projects/%s/pipelines/%d",
                    apiBaseUrl, projectId, pipelineId);

            HttpHeaders headers = buildHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            JsonNode result = objectMapper.readTree(response.getBody());

            String status = result.get("status").asText();
            String ref = result.get("ref").asText();
            String sha = result.get("sha").asText().substring(0, 8);
            String createdAt = result.get("created_at").asText();

            return String.format(
                    "🚀 GitLab 流水线状态\n\n" +
                    "ID: %d\n" +
                    "状态: %s\n" +
                    "分支: %s\n" +
                    "提交: %s\n" +
                    "创建时间: %s\n\n" +
                    "🔗 %s/%s/-/pipelines/%d",
                    pipelineId, status, ref, sha, createdAt,
                    apiBaseUrl.replace("/api/v4", ""), projectId, pipelineId);
        } catch (Exception e) {
            log.error("GitLab 查询流水线失败", e);
            return "❌ 查询失败: " + e.getMessage();
        }
    }

    /**
     * 取消流水线
     */
    public String cancelPipeline(String projectId, int pipelineId) {
        if (!enabled) {
            throw new IllegalStateException("GitLab 集成未启用");
        }

        try {
            String url = String.format("%s/projects/%s/pipelines/%d/cancel",
                    apiBaseUrl, projectId, pipelineId);

            HttpHeaders headers = buildHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            return "✅ 已取消流水线 #" + pipelineId;
        } catch (Exception e) {
            log.error("GitLab 取消流水线失败", e);
            return "❌ 取消失败: " + e.getMessage();
        }
    }

    /**
     * 获取项目最近流水线列表
     */
    public String listPipelines(String projectId, int limit) {
        if (!enabled) {
            return "⚠️ GitLab 集成未启用";
        }

        try {
            String url = String.format("%s/projects/%s/pipelines?per_page=%d",
                    apiBaseUrl, projectId, limit);

            HttpHeaders headers = buildHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            JsonNode pipelines = objectMapper.readTree(response.getBody());

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("🚀 GitLab 流水线列表（最近 %d 条）\n\n", limit));

            for (JsonNode pipeline : pipelines) {
                int id = pipeline.get("id").asInt();
                String ref = pipeline.get("ref").asText();
                String status = pipeline.get("status").asText();
                String createdAt = pipeline.get("created_at").asText();

                String emoji = switch (status) {
                    case "success" -> "✅";
                    case "failed" -> "❌";
                    case "running" -> "🔄";
                    case "pending" -> "⏳";
                    case "canceled" -> "⏹";
                    default -> "❓";
                };

                sb.append(String.format("%s #%d - %s 【%s】%n    创建: %s%n\n",
                        emoji, id, ref, status, createdAt));
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("GitLab 查询流水线列表失败", e);
            return "❌ 查询失败: " + e.getMessage();
        }
    }

    // ========== 通用请求方法 ==========

    private <T> T executeGet(String url, Class<T> responseType) {
        HttpHeaders headers = buildHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.GET, entity, responseType);
            return response.getBody();
        } catch (Exception e) {
            log.error("GitLab API GET 请求失败: url={}", url, e);
            throw new RuntimeException("GitLab API 请求失败: " + e.getMessage(), e);
        }
    }

    private <T> T executePost(String url, Object body, Class<T> responseType) {
        HttpHeaders headers = buildHeaders();
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<T> response = restTemplate.postForEntity(url, entity, responseType);
            return response.getBody();
        } catch (Exception e) {
            log.error("GitLab API POST 请求失败: url={}", url, e);
            throw new RuntimeException("GitLab API 请求失败: " + e.getMessage(), e);
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (privateToken != null && !privateToken.isEmpty()) {
            headers.set("PRIVATE-TOKEN", privateToken);
        }
        return headers;
    }

    /**
     * 根据仓库别名获取项目 ID（需在配置中映射）
     */
    public String resolveProjectId(String alias, Map<String, String> repoAliasMap) {
        if (repoAliasMap != null && repoAliasMap.containsKey(alias)) {
            return repoAliasMap.get(alias);
        }
        // 如果不是别名，假设它是直接的 projectId
        return alias;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
