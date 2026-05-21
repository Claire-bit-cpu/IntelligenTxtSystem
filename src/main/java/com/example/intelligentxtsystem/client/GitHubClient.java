package com.example.intelligentxtsystem.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * GitHub API 客户端
 */
@Component
public class GitHubClient {

    private static final Logger logger = LoggerFactory.getLogger(GitHubClient.class);

    @Value("${github.token:}")
    private String token;

    private final RestTemplate restTemplate = new RestTemplate();

    private static final String API_URL = "https://api.github.com";

    /**
     * 获取仓库信息
     */
    public String getRepoInfo(String owner, String repo) {
        String url = API_URL + "/repos/" + owner + "/" + repo;

        HttpHeaders headers = new HttpHeaders();
        if (token != null && !token.isEmpty()) {
            headers.set("Authorization", "Bearer " + token);
        }
        headers.set("Accept", "application/vnd.github.v3+json");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String fullName = (String) response.getBody().get("full_name");
                String description = (String) response.getBody().get("description");
                int stars = (Integer) response.getBody().get("stargazers_count");
                int forks = (Integer) response.getBody().get("forks_count");

                return String.format("""
                        📦 仓库：%s

                        📝 说明：%s
                        ⭐ 标星：%s
                        🍴 分支：%s
                        🔗 链接：https://github.com/%s/%s
                        """, fullName, description != null ? description : "无", formatCount(stars), formatCount(forks), owner, repo);
            }
            return "⚠️ 获取仓库信息失败";
        } catch (Exception e) {
            logger.error("获取仓库信息异常", e);
            return "⚠️ 获取仓库信息失败：" + e.getMessage();
        }
    }

    /**
     * 获取Pull Request信息
     */
    public String getPRInfo(String owner, String repo, int prNumber) {
        String url = API_URL + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber;

        HttpHeaders headers = new HttpHeaders();
        if (token != null && !token.isEmpty()) {
            headers.set("Authorization", "Bearer " + token);
        }
        headers.set("Accept", "application/vnd.github.v3+json");

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String title = (String) response.getBody().get("title");
                String state = (String) response.getBody().get("state");
                String user = (String) ((Map<?, ?>) response.getBody().get("user")).get("login");
                int additions = (Integer) response.getBody().get("additions");
                int deletions = (Integer) response.getBody().get("deletions");
                int changedFiles = (Integer) response.getBody().get("changed_files");
                String htmlUrl = (String) response.getBody().get("html_url");

                return String.format("""
                        🔍 PR #%d 信息

                        📝 标题：%s
                        👤 作者：%s
                        📊 状态：%s
                        📈 改动：+%d / -%d（%d 个文件）
                        🔗 链接：%s
                        """, prNumber, title, user, state, additions, deletions, changedFiles, htmlUrl);
            }
            return "⚠️ 获取PR信息失败";
        } catch (Exception e) {
            logger.error("获取PR信息异常", e);
            return "⚠️ 获取PR信息失败：" + e.getMessage();
        }
    }

    /**
     * 获取PR的代码差异（diff）
     */
    public String getPRDiff(String owner, String repo, int prNumber) {
        String url = API_URL + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber;

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
