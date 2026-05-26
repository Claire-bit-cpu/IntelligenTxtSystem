/*
 * GitLab API 客户端
 * 支持：查询提交日志、查看 diff、创建分支、查看 MR
 */
package com.example.intelligentxtsystem.client;

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
}
