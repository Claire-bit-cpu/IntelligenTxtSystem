/*
 * GitHub 配置属性类
 * 读取 application.yaml 中的 github 配置
 */
package com.example.IntelligentRobot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@RefreshScope
@ConfigurationProperties(prefix = "github")
public class GitHubConfig {

    private String token;

    private String apiUrl;

    private String webhookSecret;

    /**
     * 仓库别名配置，格式：alias1=owner1/repo1,alias2=owner2/repo2
     * 支持通过环境变量 GITHUB_REPO_ALIASES 设置
     */
    private String repoAliases;

    private String adminOpenIds;

    private String developerOpenIds;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String getRepoAliases() {
        return repoAliases;
    }

    public void setRepoAliases(String repoAliases) {
        this.repoAliases = repoAliases;
    }

    /**
     * 解析 repoAliases 字符串为 Map
     * 格式：alias1=owner1/repo1,alias2=owner2/repo2
     */
    public Map<String, String> getRepoAliasesMap() {
        Map<String, String> result = new HashMap<>();
        if (repoAliases == null || repoAliases.trim().isEmpty()) {
            return result;
        }
        for (String pair : repoAliases.split(",")) {
            String[] kv = pair.trim().split("=", 2);
            if (kv.length == 2) {
                result.put(kv[0].trim(), kv[1].trim());
            }
        }
        return result;
    }

    public String getAdminOpenIds() {
        return adminOpenIds;
    }

    public void setAdminOpenIds(String adminOpenIds) {
        this.adminOpenIds = adminOpenIds;
    }

    public String getDeveloperOpenIds() {
        return developerOpenIds;
    }

    public void setDeveloperOpenIds(String developerOpenIds) {
        this.developerOpenIds = developerOpenIds;
    }

    /**
     * GitHub Actions 回调配置
     */
    private Callback callback = new Callback();

    public static class Callback {
        private String url;
        private String secret;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }

    public Callback getCallback() {
        return callback;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }
}
