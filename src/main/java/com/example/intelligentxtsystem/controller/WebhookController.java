package com.example.intelligentxtsystem.controller;

import com.example.intelligentxtsystem.config.FeishuSignatureVerifier;
import com.example.intelligentxtsystem.service.MessageProcessor;
import com.example.intelligentxtsystem.service.handler.HelpHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞书 Webhook 回调入口
 * 处理所有飞书事件
 */
@RestController
@RequestMapping("/feishu")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FeishuSignatureVerifier signatureVerifier;

    @Autowired
    private MessageProcessor messageProcessor;

    @Autowired
    private HelpHandler helpHandler;

    /**
     * 飞书回调入口
     */
    @PostMapping("/webhook")
    public Object handleWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Signature", required = false) String signature,
            HttpServletResponse response
    ) {
        // 设置响应头，确保飞书能正确解析 JSON 响应
        response.setHeader("ngrok-skip-browser-warning", "true");
        response.setContentType("application/json;charset=UTF-8");

        long startTime = System.currentTimeMillis();
        log.info("收到飞书请求: timestamp={}, hasBody={}", timestamp, rawBody != null);

        // 签名验证
        if (!signatureVerifier.verify(timestamp, signature, rawBody)) {
            log.warn("签名验证失败");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 401);
            result.put("msg", "签名验证失败");
            return result;
        }

        try {
            Map<String, Object> body = objectMapper.readValue(rawBody, Map.class);

            // URL 验证请求
            if ("url_verification".equals(body.get("type"))) {
                log.info("处理 URL 验证请求");
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("challenge", body.get("challenge"));
                return result;
            }

            // 事件回调处理
            if (body.containsKey("header")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> header = (Map<String, Object>) body.get("header");
                String eventType = (String) header.get("event_type");

                if ("im.message.receive_v1".equals(eventType)) {
                    // 消息事件 - 委托给 MessageProcessor 异步处理
                    messageProcessor.processMessageEvent(body);
                } else if ("card.action.trigger".equals(eventType)) {
                    // 卡片回调事件 - 同步处理，返回更新的卡片
                    return handleCardAction(body);
                }
            }

            // 立即返回200，不等待处理完成
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 0);
            result.put("msg", "ok");
            return result;

        } catch (Exception e) {
            log.error("处理请求异常", e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 500);
            result.put("msg", "服务器内部错误");
            return result;
        } finally {
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("飞书请求处理完成，耗时: {}ms", elapsed);
        }
    }

    /**
     * 处理卡片按钮点击事件
     * 根据 action.value 返回对应的帮助卡片
     * 直接返回 Map 对象，由 Spring 自动序列化为 JSON
     */
    @SuppressWarnings("unchecked")
    private Object handleCardAction(Map<String, Object> body) {
        try {
            // 详细打印回调请求的完整 body，用于排查 action.value 格式
            log.info("卡片回调完整 body: {}", objectMapper.writeValueAsString(body));

            Map<String, Object> event = (Map<String, Object>) body.get("event");
            if (event == null) {
                log.warn("卡片回调 event 为空");
                return buildErrorResponse("事件数据为空");
            }

            Map<String, Object> action = (Map<String, Object>) event.get("action");
            if (action == null) {
                log.warn("卡片回调 action 为空, event={}", event);
                return buildErrorResponse("动作数据为空");
            }

            log.info("卡片回调 action 内容: {}", action);

            Object valueObj = action.get("value");
            log.info("卡片回调 value 类型: {}, 值: {}", valueObj == null ? "null" : valueObj.getClass().getSimpleName(), valueObj);

            // 飞书回调的 action.value 可能是 String（JSON 字符串）或 Map（对象）
            String actionName = null;
            if (valueObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> valueMap = (Map<String, Object>) valueObj;
                actionName = (String) valueMap.get("action");
            } else if (valueObj instanceof String) {
                // value 是 JSON 字符串，需要解析
                String valueStr = (String) valueObj;
                
                // 处理双重转义：如果 valueStr 被额外引号包裹，先去掉
                // 例如："{\"action\": \"help_search\"}" → {"action": "help_search"}
                if (valueStr.startsWith("\"") && valueStr.endsWith("\"") && valueStr.length() > 1) {
                    valueStr = valueStr.substring(1, valueStr.length() - 1)
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\");
                    log.info("去掉双重转义后的 valueStr: {}", valueStr);
                }
                
                @SuppressWarnings("unchecked")
                Map<String, Object> valueMap = objectMapper.readValue(valueStr, Map.class);
                actionName = (String) valueMap.get("action");
            }

            if (actionName == null || actionName.isEmpty()) {
                log.warn("卡片回调 actionName 为空, valueObj={}", valueObj);
                return buildErrorResponse("动作名称不能为空");
            }

            log.info("处理卡片回调: action={}", actionName);

            // 根据 action 返回对应的帮助卡片
            Map<String, Object> newCard = helpHandler.buildSubMenuCardMap(actionName);

            // 飞书 card.action.trigger 回调要求返回 {code, msg, data} 结构
            // 其中 data 必须包含 card 字段
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("card", newCard);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", 0);
            result.put("msg", "success");
            result.put("data", data);

            // 打印即将返回的响应 JSON，用于排查格式问题
            String responseJson = objectMapper.writeValueAsString(result);
            log.info("卡片回调响应 JSON: {}", responseJson);

            return result;

        } catch (Exception e) {
            log.error("处理卡片回调异常", e);
            return buildErrorResponse("处理失败: " + e.getMessage());
        }
    }

    /**
     * 构建错误响应（显示 toast 提示，不更新卡片）
     */
    private Object buildErrorResponse(String errorMsg) {
        Map<String, Object> toast = new LinkedHashMap<>();
        toast.put("content", errorMsg);
        toast.put("type", "error");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toast", toast);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 0);
        result.put("msg", "success");
        result.put("data", data);
        return result;
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Object health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("service", "IntelligenTxtSystem");
        return result;
    }
}
