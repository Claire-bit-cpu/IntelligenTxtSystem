package com.example.intelligentxtsystem.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * GitHub API 客户端
 * 提供 GitHub REST API 的封装方法
 */
@Component
public class GitHubClient {

    private static final Logger logger = LoggerFactory.getLogger(GitHubClient.class);

    @Value("${github.token:}")
    private String token;

    @Value("${github.api-url}")
    private String apiUrl;

    private final RestTemplate restTemplate;

    public GitHubClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 获取仓库信息
     */
    public Map<String, Object> getRepoInfo(String owner, String repo) {
        String url = apiUrl + "/repos/" + owner + "/" + repo;
        ResponseEntity<Map> response = executeGet(url, Map.class);
        return response != null ? response.getBody() : null;
    }

    /**
     * 获取仓库信息（格式化文本）
     */
    public String getRepoInfoText(String owner, String repo) {
        Map<String, Object> repoInfo = getRepoInfo(owner, repo);
        
        if (repoInfo == null) {
            return "⚠️ 获取仓库信息失败";
        }

        try {
            String fullName = (String) repoInfo.get("full_name");
            String description = (String) repoInfo.get("description");
            String htmlUrl = (String) repoInfo.get("html_url");
            int stars = repoInfo.get("stargazers_count") != null ? (Integer) repoInfo.get("stargazers_count") : 0;
            int watchers = repoInfo.get("watchers_count") != null ? (Integer) repoInfo.get("watchers_count") : 0;
            int forks = repoInfo.get("forks_count") != null ? (Integer) repoInfo.get("forks_count") : 0;
            int issues = repoInfo.get("open_issues_count") != null ? (Integer) repoInfo.get("open_issues_count") : 0;
            String language = (String) repoInfo.get("language");
            
            @SuppressWarnings("unchecked")
            Map<String, Object> licenseMap = (Map<String, Object>) repoInfo.get("license");
            String license = licenseMap != null && licenseMap.get("spdx_id") != null ? (String) licenseMap.get("spdx_id") : "None";
            
            String createdAt = (String) repoInfo.get("created_at");
            String updatedAt = (String) repoInfo.get("updated_at");
            
            @SuppressWarnings("unchecked")
            Map<String, Object> ownerMap = (Map<String, Object>) repoInfo.get("owner");
            String ownerLogin = ownerMap != null && ownerMap.get("login") != null ? (String) ownerMap.get("login") : owner;

            return String.format("""
                    📦 仓库信息：%s
                    
                    👤 所有者：%s
                    📝 描述：%s
                    🌐 链接：%s
                    
                    ⭐ Star：%s
                    👀 Watchers：%s
                    🍴 Forks：%s
                    ❗ 开放 Issue：%s
                    
                    💻 主要语言：%s
                    📄 许可证：%s
                    
                    📅 创建时间：%s
                    🔄 更新时间：%s
                    """, 
                    fullName, 
                    ownerLogin,
                    description != null ? description : "无描述",
                    htmlUrl,
                    formatCount(stars),
                    formatCount(watchers),
                    formatCount(forks),
                    formatCount(issues),
                    language != null ? language : "未知",
                    license,
                    createdAt,
                    updatedAt);
        } catch (Exception e) {
            logger.error("解析仓库信息异常", e);
            return "⚠️ 解析仓库信息失败";
        }
    }

    /**
     * 获取仓库最近提交日志
     * @param owner 仓库所有者
     * @param repo 仓库名称
     * @param limit 返回条数
     * @return 提交列表
     */
    public List<Map<String, Object>> getCommits(String owner, String repo, int limit) {
        return getCommits(owner, repo, limit, null);
    }
    
    /**
     * 获取仓库最近提交日志（可指定分支）
     * @param owner 仓库所有者
     * @param repo 仓库名称
     * @param limit 返回条数
     * @param branch 分支名称（可选，null 表示默认分支）
     * @return 提交列表
     */
    public List<Map<String, Object>> getCommits(String owner, String repo, int limit, String branch) {
        StringBuilder urlBuilder = new StringBuilder(apiUrl)
            .append("/repos/").append(owner).append("/").append(repo)
            .append("/commits?per_page=").append(limit);
        
        if (branch != null && !branch.isEmpty()) {
            urlBuilder.append("&sha=").append(branch);
        }
        
        String url = urlBuilder.toString();
        
        HttpHeaders headers = new HttpHeaders();
        if (token != null && !token.isEmpty()) {
            headers.set("Authorization", "Bearer " + token);
        }
        headers.set("Accept", "application/vnd.github.v3+json");
        
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        
        try {
            logger.info("【调试】开始调用GitHub API获取提交日志: url={}, owner={}, repo={}, limit={}", url, owner, repo, limit);
            
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                entity, 
                new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            
            logger.info("【调试】GitHub API响应状态码: {}", response.getStatusCode());
            
            // 打印响应头中的速率限制信息
            HttpHeaders responseHeaders = response.getHeaders();
            if (responseHeaders != null) {
                logger.info("【调试】响应头 - RateLimit-Remaining: {}", responseHeaders.getFirst("X-RateLimit-Remaining"));
                logger.info("【调试】响应头 - RateLimit-Limit: {}", responseHeaders.getFirst("X-RateLimit-Limit"));
                logger.info("【调试】响应头 - RateLimit-Reset: {}", responseHeaders.getFirst("X-RateLimit-Reset"));
            }
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                int actualSize = response.getBody().size();
                logger.info("【调试】成功获取提交日志: 请求条数={}, 实际返回条数={}", limit, actualSize);
                
                // 如果返回条数少于请求条数，打印警告
                if (actualSize < limit) {
                    logger.warn("【调试】警告：实际返回条数({})少于请求条数({})，可能原因：1)仓库提交不足 2)API权限限制 3)API返回被截断", actualSize, limit);
                }
                
                // 打印前3条提交的简要信息（用于调试）
                int printCount = Math.min(3, actualSize);
                for (int i = 0; i < printCount; i++) {
                    Map<String, Object> commit = response.getBody().get(i);
                    String sha = (String) commit.get("sha");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> commitInfo = (Map<String, Object>) commit.get("commit");
                    String message = commitInfo != null ? (String) commitInfo.get("message") : "N/A";
                    if (message != null && message.length() > 50) {
                        message = message.substring(0, 50) + "...";
                    }
                    logger.info("【调试】提交[{}]: sha={}, message={}", i + 1, sha != null ? sha.substring(0, 8) : "N/A", message);
                }
                
                return response.getBody();
            } else {
                logger.error("【调试】GitHub API返回非成功状态码: status={}, body={}", response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            logger.error("【调试】获取提交日志失败: url={}", url, e);
            
            // 尝试获取更多错误信息
            if (e instanceof org.springframework.web.client.HttpClientErrorException) {
                org.springframework.web.client.HttpClientErrorException httpError = 
                    (org.springframework.web.client.HttpClientErrorException) e;
                logger.error("【调试】HTTP错误状态码: {}", httpError.getStatusCode());
                logger.error("【调试】HTTP错误响应体: {}", httpError.getResponseBodyAsString());
            }
        }
        return null;
    }

    /**
     * 获取特定提交的详细信息（包含文件变更）
     * @param owner 仓库所有者
     * @param repo 仓库名称
     * @param sha 提交 SHA
     * @return 提交详情
     */
    public Map<String, Object> getCommit(String owner, String repo, String sha) {
        String url = apiUrl + "/repos/" + owner + "/" + repo + "/commits/" + sha;
        ResponseEntity<Map> response = executeGet(url, Map.class);
        return response != null ? response.getBody() : null;
    }

    /**
     * 获取提交的文件差异
     * @param owner 仓库所有者
     * @param repo 仓库名称
     * @param sha 提交 SHA
     * @return 文件变更列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getCommitFiles(String owner, String repo, String sha) {
        Map<String, Object> commit = getCommit(owner, repo, sha);
        if (commit != null && commit.containsKey("files")) {
            return (List<Map<String, Object>>) commit.get("files");
        }
        return null;
    }

/**
 * 创建分支
 * @param owner 仓库所有者
 * @param repo 仓库名称
 * @param branchName 新分支名称
 * @param sha 源分支的 SHA
 * @return 创建结果
 */
public Map<String, Object> createBranch(String owner, String repo, String branchName, String sha) {
    String url = apiUrl + "/repos/" + owner + "/" + repo + "/git/refs";
    
    Map<String, Object> body = Map.of(
        "ref", "refs/heads/" + branchName,
        "sha", sha
    );
    
    ResponseEntity<Map> response = executePost(url, body, Map.class);
    return response != null ? response.getBody() : null;
}

/**
 * 获取分支的最新 commit SHA
 * @param owner 仓库所有者
 * @param repo 仓库名称
 * @param branch 分支名称
 * @return commit SHA
 */
public String getBranchSha(String owner, String repo, String branch) {
    String url = apiUrl + "/repos/" + owner + "/" + repo + "/git/ref/heads/" + branch;
    ResponseEntity<Map> response = executeGet(url, Map.class);
    
    if (response != null && response.getBody() != null) {
        Map<String, Object> refInfo = response.getBody();
        if (refInfo.containsKey("object")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> object = (Map<String, Object>) refInfo.get("object");
            return (String) object.get("sha");
        }
    }
    
    return null;
}

    /**
     * 获取所有打开的 Pull Request
     * @param owner 仓库所有者
     * @param repo 仓库名称
     * @return PR 列表
     */
    public List<Map<String, Object>> getPullRequests(String owner, String repo) {
        String url = apiUrl + "/repos/" + owner + "/" + repo + "/pulls?state=open";
        
        HttpHeaders headers = new HttpHeaders();
        if (token != null && !token.isEmpty()) {
            headers.set("Authorization", "Bearer " + token);
        }
        headers.set("Accept", "application/vnd.github.v3+json");
        
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                entity, 
                new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
        } catch (Exception e) {
            logger.error("获取PR列表失败: url={}", url, e);
        }
        return null;
    }

    /**
     * 获取特定 Pull Request 详情
     * @param owner 仓库所有者
     * @param repo 仓库名称
     * @param prNumber PR 编号
     * @return PR 详情
     */
    public Map<String, Object> getPullRequest(String owner, String repo, int prNumber) {
        String url = apiUrl + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber;
        ResponseEntity<Map> response = executeGet(url, Map.class);
        return response != null ? response.getBody() : null;
    }

    /**
     * 获取Pull Request信息（格式化文本）
     */
    public String getPRInfo(String owner, String repo, int prNumber) {
        Map<String, Object> pr = getPullRequest(owner, repo, prNumber);
        
        if (pr == null) {
            return "⚠️ 获取PR信息失败";
        }

        try {
            String title = (String) pr.get("title");
            String state = (String) pr.get("state");
            String user = (String) ((Map<?, ?>) pr.get("user")).get("login");
            int additions = (Integer) pr.get("additions");
            int deletions = (Integer) pr.get("deletions");
            int changedFiles = (Integer) pr.get("changed_files");
            String htmlUrl = (String) pr.get("html_url");

            return String.format("""
                    🔍 PR #%d 信息
                    
                    📝 标题：%s
                    👤 作者：%s
                    📊 状态：%s
                    📈 改动：+%d / -%d（%d 个文件）
                    🔗 链接：%s
                    """, prNumber, title, user, state, additions, deletions, changedFiles, htmlUrl);
        } catch (Exception e) {
            logger.error("解析PR信息异常", e);
            return "⚠️ 解析PR信息失败";
        }
    }

    /**
     * 获取PR的代码差异（diff）
     */
    public String getPRDiff(String owner, String repo, int prNumber) {
        String url = apiUrl + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber;

        HttpHeaders headers = new HttpHeaders();
        if (token != null && !token.isEmpty()) {
            headers.set("Authorization", "Bearer " + token);
        }
        headers.set("Accept", "application/vnd.github.v3.diff");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
            return null;
        } catch (Exception e) {
            logger.error("获取PR diff异常", e);
            return null;
        }
    }

    /**
     * 检查Token配置
     */
    public boolean isConfigured() {
        return token != null && !token.isEmpty();
    }

    /**
     * 执行 GET 请求
     */
    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> executeGet(String url, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null && !token.isEmpty()) {
            headers.set("Authorization", "Bearer " + token);
        }
        headers.set("Accept", "application/vnd.github.v3+json");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            logger.debug("【调试】executeGet 开始: url={}, responseType={}", url, responseType.getSimpleName());
            
            ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.GET, entity, responseType);
            
            logger.debug("【调试】executeGet 成功: url={}, status={}", url, response.getStatusCode());
            return response;
        } catch (Exception e) {
            logger.error("【调试】GitHub API GET 请求失败: url={}", url, e);
            
            // 尝试获取更多错误信息
            if (e instanceof org.springframework.web.client.HttpClientErrorException) {
                org.springframework.web.client.HttpClientErrorException httpError = 
                    (org.springframework.web.client.HttpClientErrorException) e;
                logger.error("【调试】HTTP错误状态码: {}", httpError.getStatusCode());
                logger.error("【调试】HTTP错误响应体: {}", httpError.getResponseBodyAsString());
            }
            
            return null;
        }
    }

    /**
     * 执行 POST 请求
     */
    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> executePost(String url, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null && !token.isEmpty()) {
            headers.set("Authorization", "Bearer " + token);
        }
        headers.set("Accept", "application/vnd.github.v3+json");
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Object> entity = new HttpEntity<>(body, headers);

        try {
            return restTemplate.exchange(url, HttpMethod.POST, entity, responseType);
        } catch (Exception e) {
            logger.error("GitHub API POST 请求失败: url={}", url, e);
            return null;
        }
    }

    /**
     * 格式化数字（1000 -> 1k, 1000000 -> 1M）
     */
    private String formatCount(int count) {
        if (count >= 1000000) {
            return String.format("%.1fM", count / 1000000.0);
        } else if (count >= 1000) {
            return String.format("%.1fk", count / 1000.0);
        }
        return String.valueOf(count);
    }
}
