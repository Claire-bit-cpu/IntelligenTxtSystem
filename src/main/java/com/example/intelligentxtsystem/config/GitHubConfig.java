/*
 * GitHub 配置属性类
 * 读取 application.yaml 中的 github 配置
 */
package com.example.intelligentxtsystem.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "github")
public class GitHubConfig {

    private String token;

    private String apiUrl;

    private String webhookSecret;

    private Map<String, String> repoAliases = new HashMap<>();

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

    public Map<String, String> getRepoAliases() {
        return repoAliases;
    }

    public void setRepoAliases(Map<String, String> repoAliases) {
        this.repoAliases = repoAliases;
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
}
