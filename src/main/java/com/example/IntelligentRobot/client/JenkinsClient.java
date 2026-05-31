/*
 * Jenkins CI/CD 客户端
 * 支持：触发构建、查询构建状态、取消构建
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

import java.util.Base64;
import java.util.Map;

@Component
public class JenkinsClient {

    private static final Logger log = LoggerFactory.getLogger(JenkinsClient.class);

    @Value("${jenkins.url:http://localhost:8080}")
    private String jenkinsUrl;

    @Value("${jenkins.username:}")
    private String username;

    @Value("${jenkins.api-token:}")
    private String apiToken;

    @Value("${jenkins.enabled:false}")
    private boolean enabled;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public JenkinsClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 触发构建
     * @param jobName Jenkins 任务名称
     * @param parameters 构建参数（可选）
     * @return 构建队列 ID
     */
    public String triggerBuild(String jobName, Map<String, String> parameters) {
        if (!enabled) {
            throw new IllegalStateException("Jenkins 集成未启用，请配置 jenkins.enabled=true");
        }

        try {
            String url;
            if (parameters != null && !parameters.isEmpty()) {
                // 带参数的构建
                StringBuilder paramStr = new StringBuilder();
                parameters.forEach((k, v) -> paramStr.append("&").append(k).append("=").append(v));
                url = String.format("%s/job/%s/buildWithParameters?%s",
                        jenkinsUrl, jobName, paramStr.substring(1));
            } else {
                // 无参数构建
                url = String.format("%s/job/%s/build", jenkinsUrl, jobName);
            }

            HttpHeaders headers = buildHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            // Jenkins 返回 201 Created，Location 头包含队列 URL
            if (response.getStatusCode() == HttpStatus.CREATED) {
                String location = response.getHeaders().getLocation().toString();
                return "构建已触发，队列 ID: " + extractQueueId(location);
            }

            return "构建已触发";
        } catch (Exception e) {
            log.error("Jenkins 触发构建失败: job={}", jobName, e);
            throw new RuntimeException("Jenkins 触发构建失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询构建状态
     * @param jobName Jenkins 任务名称
     * @param buildNumber 构建编号（可选，默认最新）
     */
    public JsonNode getBuildStatus(String jobName, Integer buildNumber) {
        if (!enabled) {
            throw new IllegalStateException("Jenkins 集成未启用");
        }

        try {
            String buildId = buildNumber != null ? String.valueOf(buildNumber) : "lastBuild";
            String url = String.format("%s/job/%s/%s/api/json", jenkinsUrl, jobName, buildId);

            HttpHeaders headers = buildHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            log.error("Jenkins 查询构建状态失败: job={}", jobName, e);
            throw new RuntimeException("Jenkins 查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取任务最后一次构建状态
     */
    public String getLastBuildStatus(String jobName) {
        try {
            JsonNode status = getBuildStatus(jobName, null);
            if (status == null) {
                return "未找到构建记录";
            }

            String result = status.has("result") && !status.get("result").isNull()
                    ? status.get("result").asText() : "BUILDING";
            String displayName = status.has("displayName")
                    ? status.get("displayName").asText() : "#" + status.get("number").asText();
            boolean building = status.has("building") && status.get("building").asBoolean();

            return String.format("任务: %s\n状态: %s\n构建中: %s\n编号: %s",
                    jobName, result, building ? "是" : "否", displayName);
        } catch (Exception e) {
            return "查询失败: " + e.getMessage();
        }
    }

    /**
     * 取消构建
     */
    public String stopBuild(String jobName, int buildNumber) {
        if (!enabled) {
            throw new IllegalStateException("Jenkins 集成未启用");
        }

        try {
            String url = String.format("%s/job/%s/%d/stop", jenkinsUrl, jobName, buildNumber);
            HttpHeaders headers = buildHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            return "已发送停止信号到构建 #" + buildNumber;
        } catch (Exception e) {
            log.error("Jenkins 停止构建失败", e);
            return "停止失败: " + e.getMessage();
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (!username.isEmpty() && !apiToken.isEmpty()) {
            String auth = username + ":" + apiToken;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
            headers.set("Authorization", "Basic " + encodedAuth);
        }

        return headers;
    }

    private String extractQueueId(String location) {
        // Location: http://jenkins/queue/item/123/
        String[] parts = location.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("item".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return "未知";
    }

    public boolean isEnabled() {
        return enabled;
    }
}
