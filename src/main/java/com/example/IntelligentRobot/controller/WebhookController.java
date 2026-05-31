package com.example.IntelligentRobot.controller;

import com.example.IntelligentRobot.config.FeishuEncryptDecoder;
import com.example.IntelligentRobot.config.FeishuSignatureVerifier;
import com.example.IntelligentRobot.service.EventAsyncProcessor;
import com.example.IntelligentRobot.service.IdempotentService;
import com.example.IntelligentRobot.task.AsyncTaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 飞书 Webhook 回调入口
 * 处理所有飞书事件
 * 
 * 高并发优化设计：
 * 1. 快速返回响应（10ms内），避免飞书超时重试
 * 2. 异步处理业务逻辑，使用线程池
 * 3. 幂等性控制，防止重复处理
 * 4. 限流保护，防止突发流量打垮服务
 */
@RestController
@RequestMapping("/feishu")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    @Value("${ratelimit.global-qps:1000}")
    private int globalQpsLimit;

    @Value("${ratelimit.ip-qps:100}")
    private int ipQpsLimit;

    @Value("${ratelimit.enabled:true}")
    private boolean rateLimitEnabled;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private FeishuSignatureVerifier feishuSignatureVerifier;

    @Autowired(required = false)
    private FeishuEncryptDecoder feishuEncryptDecoder;

    @Autowired
    private EventAsyncProcessor eventAsyncProcessor;

    @Autowired
    private IdempotentService idempotentService;

    @Autowired(required = false)
    private com.example.IntelligentRobot.service.RateLimitService rateLimitService;

    @Autowired(required = false)
    private com.example.IntelligentRobot.service.ThreadPoolMonitorService threadPoolMonitorService;

    /**
     * 飞书回调入口（仅接受 POST 请求）
     * 飞书 URL 验证要求：
     *   请求: {"type": "url_verification", "challenge": "xxx"}
     *   响应: {"challenge": "xxx"}  (Content-Type: application/json)
     * 
     * 性能优化：
     * 1. 只做必要的快速校验（签名验证、解密）
     * 2. 业务逻辑全部异步处理
     * 3. 立即返回200，不等待处理完成
     */
    @PostMapping(value = "/webhook",
            consumes = "application/json",
            produces = "application/json")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Signature", required = false) String signature,
            HttpServletRequest request
    ) {
        long startTime = System.currentTimeMillis();
        log.info("========== 收到飞书Webhook请求 ==========");
        log.info("timestamp={}, signature存在={}, body长度={}", 
                timestamp, signature != null, rawBody != null ? rawBody.length() : 0);
        log.debug("原始请求体: {}", rawBody);
        
        // 提前声明 taskId，确保在 catch 块中可访问
        String taskId = null;

        // ===== 限流检查（可选，如果RateLimitService不可用则跳过）=====
        if (rateLimitEnabled && rateLimitService != null) {
            String clientIp = getClientIp(request);
            if (!rateLimitService.tryAcquireGlobal(globalQpsLimit)) {
                log.warn("全局限流触发，拒绝请求");
                return ResponseEntity.status(429)
                        .body(Map.of("code", 429, "msg", "Too Many Requests"));
            }
            if (clientIp != null && !rateLimitService.tryAcquireByIp(clientIp, ipQpsLimit)) {
                log.warn("IP限流触发: ip={}", clientIp);
                return ResponseEntity.status(429)
                        .body(Map.of("code", 429, "msg", "Too Many Requests"));
            }
        }

        // ===== 处理加密请求 =====
        String bodyToProcess = rawBody;
        if (feishuEncryptDecoder != null) {
            try {
                // 先解析请求体，检查是否包含 encrypt 字段
                Map<String, Object> requestBody = objectMapper.readValue(rawBody, Map.class);
                
                if (requestBody.containsKey("encrypt")) {
                    String encryptData = (String) requestBody.get("encrypt");
                    if (encryptData == null || encryptData.isEmpty()) {
                        log.error("encrypt 字段为空");
                        return ResponseEntity.status(400)
                                .body(Map.of("code", 400, "msg", "encrypt 字段为空"));
                    }
                    
                    // 解密 encrypt 字段的值（不是整个请求体）
                    bodyToProcess = feishuEncryptDecoder.decrypt(encryptData);
                    log.info("请求已解密，原始长度={}, 解密后长度={}", rawBody.length(), bodyToProcess.length());
                    log.debug("解密后的请求体: {}", bodyToProcess.substring(0, Math.min(200, bodyToProcess.length())));
                } else {
                    log.debug("请求未加密，直接使用原始请求体");
                }
            } catch (Exception e) {
                log.error("解密失败", e);
                return ResponseEntity.status(400)
                        .body(Map.of("code", 400, "msg", "解密失败: " + e.getMessage()));
            }
        } else {
            log.debug("FeishuEncryptDecoder 未配置，跳过解密");
        }

        // ===== 签名验证 =====
        if (timestamp != null && signature != null) {
            // 如果 FeishuSignatureVerifier 不存在（未配置 encrypt-key），跳过验证
            if (feishuSignatureVerifier != null) {
                if (!feishuSignatureVerifier.verify(timestamp, signature, rawBody)) {
                    log.warn("签名验证失败");
                    return ResponseEntity.status(401)
                            .body(Map.of("code", 401, "msg", "签名验证失败"));
                }
            } else {
                log.warn("FeishuSignatureVerifier 未配置，跳过签名验证");
            }
        }

        try {
            // 解析 JSON
            Map<String, Object> body = objectMapper.readValue(bodyToProcess, Map.class);
            
            // ===== URL 验证请求（必须最优先处理，同步返回）=====
            if ("url_verification".equals(body.get("type"))) {
                Object challenge = body.get("challenge");
                if (challenge == null) {
                    log.error("URL 验证请求缺少 challenge 字段");
                    return ResponseEntity.badRequest().build();
                }
                log.info("处理 URL 验证请求, challenge={}", challenge);
                Map<String, Object> result = new HashMap<>();
                result.put("challenge", challenge);
                
                long elapsedTime = System.currentTimeMillis() - startTime;
                log.info("URL验证请求处理完成, 耗时: {}ms", elapsedTime);
                return ResponseEntity.ok(result);
            }

            // ===== 提取事件ID和事件类型（用于幂等性和路由）=====
            String eventId = extractEventId(body);
            String eventType = extractEventType(body);
            
            if (eventType == null) {
                log.warn("无法识别的事件类型，body keys: {}", body.keySet());
                // 未知事件类型，仍然返回成功，避免飞书重试
                return createSuccessResponse(startTime);
            }

            log.info("收到飞书事件: event_type={}, event_id={}", eventType, eventId);

            // ===== 生成任务ID =====
            taskId = UUID.randomUUID().toString();
            
            // ===== 创建任务状态记录 =====
            AsyncTaskStatus task = new AsyncTaskStatus(taskId, eventType, eventId);
            AsyncTaskStatus.save(task);
            log.info("创建异步任务: taskId={}, eventType={}", taskId, eventType);

            // ===== 幂等性检查 =====
            if (eventId != null && !eventId.isEmpty()) {
                if (idempotentService.isAlreadyProcessed(eventId)) {
                    // 已处理过，标记任务完成
                    AsyncTaskStatus.markCompleted(taskId, "事件已处理过");
                    return createSuccessResponseWithTaskId(startTime, taskId);
                }
            }

            // ===== 异步处理业务事件 =====
            submitAsyncTask(eventType, body, eventId, taskId);

            // ===== 立即返回成功响应（包含taskId）=====
            return createSuccessResponseWithTaskId(startTime, taskId);

        } catch (Exception e) {
            log.error("处理请求异常: taskId={}", taskId, e);
            // 注意：即使处理失败，也返回200，避免飞书重试
            // 错误信息记录在日志中，通过监控系统告警
            
            // 如果已生成 taskId，标记任务失败并返回 taskId
            if (taskId != null) {
                try {
                    AsyncTaskStatus.markFailed(taskId, e.getMessage());
                } catch (Exception ex) {
                    log.warn("标记任务失败状态时出错: taskId={}", taskId, ex);
                }
                return createSuccessResponseWithTaskId(startTime, taskId);
            }
            
            return createSuccessResponse(startTime);
        }
    }

    /**
     * 处理 GET 请求到 /feishu/webhook（防止被当作静态资源）
     * 返回 405 Method Not Allowed
     */
    @GetMapping("/webhook")
    public ResponseEntity<Map<String, Object>> handleWebhookGet() {
        log.warn("收到 GET 请求到 /feishu/webhook，仅支持 POST");
        Map<String, Object> err = new HashMap<>();
        err.put("code", 405);
        err.put("msg", "Webhook 只支持 POST 请求");
        return ResponseEntity.status(405).body(err);
    }

    /**
     * 从请求体中提取事件ID
     * 兼容两种结构：
     * 1. 标准结构：{"header": {"event_id": "..."}}
     * 2. 扁平结构：{"event_id": "..."}
     */
    @SuppressWarnings("unchecked")
    private String extractEventId(Map<String, Object> body) {
        // 尝试从header中获取
        if (body.containsKey("header")) {
            Map<String, Object> header = (Map<String, Object>) body.get("header");
            if (header != null && header.containsKey("event_id")) {
                return (String) header.get("event_id");
            }
        }
        
        // 尝试直接从body中获取（扁平结构）
        if (body.containsKey("event_id")) {
            return (String) body.get("event_id");
        }
        
        // 如果没有event_id，生成一个基于内容的ID（用于幂等性降级）
        try {
            String content = objectMapper.writeValueAsString(body);
            return "generated_" + Integer.toHexString(content.hashCode());
        } catch (Exception e) {
            log.warn("生成事件ID失败", e);
            return null;
        }
    }

    /**
     * 从请求体中提取事件类型
     * 兼容两种结构：
     * 1. 标准结构：{"header": {"event_type": "..."}}
     * 2. 扁平结构：{"event_type": "..."}
     */
    @SuppressWarnings("unchecked")
    private String extractEventType(Map<String, Object> body) {
        // 尝试从header中获取
        if (body.containsKey("header")) {
            Map<String, Object> header = (Map<String, Object>) body.get("header");
            if (header != null && header.containsKey("event_type")) {
                return (String) header.get("event_type");
            }
        }
        
        // 尝试直接从body中获取（扁平结构）
        if (body.containsKey("event_type")) {
            return (String) body.get("event_type");
        }
        
        return null;
    }

    /**
     * 提交异步任务（带任务ID）
     */
    @SuppressWarnings("unchecked")
    private void submitAsyncTask(String eventType, Map<String, Object> body, String eventId, String taskId) {
        try {
            // 标记任务开始处理
            AsyncTaskStatus.markProcessing(taskId);

            // 消息接收事件
            if ("im.message.receive_v1".equals(eventType)) {
                eventAsyncProcessor.processMessageEventAsync(body, eventId, taskId);
                return;
            }

            // 新成员入群事件
            if (isMemberAddedEvent(eventType)) {
                Map<String, Object> event = (Map<String, Object>) body.get("event");
                eventAsyncProcessor.processMemberAddedEventAsync(eventType, event, eventId, taskId);
                return;
            }

            // 审批状态变更事件
            if ("approval.instance.state_change_v4".equals(eventType)) {
                Map<String, Object> event = (Map<String, Object>) body.get("event");
                eventAsyncProcessor.processApprovalEventAsync(event, eventId, taskId);
                return;
            }

            // 卡片按钮点击事件
            if ("card.action.trigger".equals(eventType)) {
                eventAsyncProcessor.processCardActionAsync(body, eventId, taskId);
                return;
            }

            log.warn("未处理的事件类型: {}", eventType);
            AsyncTaskStatus.markCompleted(taskId, "未处理的事件类型: " + eventType);
        } catch (Exception e) {
            log.error("提交异步任务失败: eventType={}, eventId={}, taskId={}", eventType, eventId, taskId, e);
            // 标记任务失败
            AsyncTaskStatus.markFailed(taskId, e.getMessage());
            // 提交失败，移除幂等键，允许重试
            if (eventId != null && !eventId.isEmpty()) {
                idempotentService.removeIdempotentKey(eventId);
            }
        }
    }

    /**
     * 创建成功响应并记录耗时
     */
    private ResponseEntity<Map<String, Object>> createSuccessResponse(long startTime) {
        Map<String, Object> ok = new HashMap<>();
        ok.put("code", 0);
        ok.put("msg", "ok");
        
        long elapsedTime = System.currentTimeMillis() - startTime;
        log.info("Webhook请求处理完成(已异步提交), 耗时: {}ms", elapsedTime);
        
        return ResponseEntity.ok(ok);
    }

    /**
     * 创建成功响应（包含taskId）并记录耗时
     */
    private ResponseEntity<Map<String, Object>> createSuccessResponseWithTaskId(long startTime, String taskId) {
        Map<String, Object> ok = new HashMap<>();
        ok.put("code", 0);
        ok.put("msg", "ok");
        ok.put("task_id", taskId);
        
        long elapsedTime = System.currentTimeMillis() - startTime;
        log.info("Webhook请求处理完成(已异步提交), taskId={}, 耗时: {}ms", taskId, elapsedTime);
        
        return ResponseEntity.ok(ok);
    }

    /**
     * 判断是否为新成员入群事件
     */
    private boolean isMemberAddedEvent(String eventType) {
        return com.example.IntelligentRobot.service.WelcomeEventHandler.EVENT_USER_ADDED.equals(eventType)
            || com.example.IntelligentRobot.service.WelcomeEventHandler.EVENT_BOT_ADDED.equals(eventType)
            || com.example.IntelligentRobot.service.WelcomeEventHandler.EVENT_INVITED_V1.equals(eventType);
    }

    /**
     * 获取客户端IP地址
     */
    private String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        
        String[] headerNames = {
            "X-Forwarded-For",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP",
            "HTTP_X_FORWARDED_FOR"
        };
        
        for (String header : headerNames) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                return ip.split(",")[0].trim();
            }
        }
        
        return request.getRemoteAddr();
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "IntelligentTxtSystem");
    }
}
