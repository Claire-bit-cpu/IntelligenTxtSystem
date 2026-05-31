/*
 * GitHub Webhook 回调入口
 * 接收并处理 GitHub Webhook 事件
 */
package com.example.IntelligentRobot.controller;

import com.example.IntelligentRobot.config.GitHubConfig;
import com.example.IntelligentRobot.service.github.GitHubWebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * GitHub Webhook 控制器
 * 接收 GitHub 发送的 Webhook 事件
 */
@RestController
@RequestMapping("/github-webhook")
public class GitHubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitHubConfig gitHubConfig;

    @Autowired
    private GitHubWebhookService gitHubWebhookService;

    /**
     * 处理 GitHub Webhook 事件
     * 
     * @param payload 原始 JSON 载荷
     * @param eventHeader X-GitHub-Event 请求头（事件类型）
     * @param signatureHeader X-Hub-Signature-256 请求头（签名）
     * @return 响应实体
     */
    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventHeader,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader
    ) {
        log.info("收到 GitHub Webhook 事件: event={}", eventHeader);

        // 1. 安全校验：验证签名
        if (!verifySignature(payload, signatureHeader)) {
            log.warn("GitHub Webhook 签名校验失败");
            return ResponseEntity.status(401)
                    .body(Map.of("code", 401, "message", "签名校验失败"));
        }

        try {
            // 2. 解析 JSON 载荷
            @SuppressWarnings("unchecked")
            Map<String, Object> payloadMap = objectMapper.readValue(payload, Map.class);

            // 3. 根据事件类型分发处理
            if (eventHeader != null) {
                switch (eventHeader) {
                    case "push":
                        gitHubWebhookService.handlePushEvent(payloadMap);
                        break;
                    case "pull_request":
                        gitHubWebhookService.handlePullRequestEvent(payloadMap);
                        break;
                    default:
                        log.info("未处理的 GitHub 事件类型: {}", eventHeader);
                }
            }

            // 4. 立即返回 200 OK（GitHub 要求快速响应）
            return ResponseEntity.ok(Map.of("code", 200, "message", "ok"));

        } catch (Exception e) {
            log.error("处理 GitHub Webhook 异常", e);
            return ResponseEntity.status(500)
                    .body(Map.of("code", 500, "message", "服务器内部错误"));
        }
    }

    /**
     * 验证 GitHub Webhook 签名
     * 使用 HMAC SHA256 算法
     * 
     * @param payload 原始请求体
     * @param signatureHeader X-Hub-Signature-256 请求头
     * @return 签名是否有效
     */
    private boolean verifySignature(String payload, String signatureHeader) {
        String secret = gitHubConfig.getWebhookSecret();
        
        // 如果未配置 secret，跳过校验（仅用于开发环境）
        if (secret == null || secret.isEmpty() || "replace_me".equals(secret)) {
            log.warn("GitHub webhook_secret 未配置，跳过签名校验（仅开发环境）");
            return true;
        }

        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            log.warn("缺少或无效的 X-Hub-Signature-256 请求头");
            return false;
        }

        try {
            String expectedSignature = signatureHeader.substring(7); // 去掉 "sha256=" 前缀
            
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            
            // 将字节数组转换为十六进制字符串
            StringBuilder actualSignature = new StringBuilder();
            for (byte b : hash) {
                actualSignature.append(String.format("%02x", b));
            }
            
            boolean isValid = expectedSignature.equals(actualSignature.toString());
            if (!isValid) {
                log.warn("签名不匹配: expected={}, actual={}", expectedSignature, actualSignature);
            }
            
            return isValid;
            
        } catch (Exception e) {
            log.error("验证签名异常", e);
            return false;
        }
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "GitHubWebhook");
    }
}
