package com.example.intelligentxtsystem.controller;

import com.example.intelligentxtsystem.config.FeishuSignatureVerifier;
import com.example.intelligentxtsystem.service.ApprovalService;
import com.example.intelligentxtsystem.service.MessageProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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
    private ApprovalService approvalService;

    @Autowired
    private FeishuClient feishuClient;

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
        response.setHeader("ngrok-skip-browser-warning", "true");

        log.info("收到飞书请求: timestamp={}, hasBody={}", timestamp, rawBody != null);

        try {
            Map<String, Object> body = objectMapper.readValue(rawBody, Map.class);

            // URL 验证请求 - 跳过签名验证
            if ("url_verification".equals(body.get("type"))) {
                log.info("处理 URL 验证请求");
                // URL 验证不需要签名验证，直接返回 challenge
                return Map.of("challenge", body.get("challenge"));
            }

            // 非 URL 验证请求，进行签名验证
            if (!signatureVerifier.verify(timestamp, signature, rawBody)) {
                log.warn("签名验证失败");
                return Map.of("code", 401, "msg", "签名验证失败");
            }

            // 打印完整事件类型，方便调试
            if (body.containsKey("header")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> header = (Map<String, Object>) body.get("header");
                String eventType = (String) header.get("event_type");
                log.info("收到飞书事件: event_type={}", eventType);

                if ("im.message.receive_v1".equals(eventType)) {
                    messageProcessor.processMessageEvent(body);
                }

                // 审批事件 - 监听审批实例状态变化（V4版本事件名：approval.instance.state_change_v4）
                if ("approval.instance.state_change_v4".equals(eventType)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> event = (Map<String, Object>) body.get("event");
                    log.info("收到审批事件: event={}", event);
                    approvalService.handleApprovalStateChange(event);
                }

                // 卡片按钮回调 - 处理帮助中心按钮点击
                if ("card.action.trigger".equals(eventType)) {
                    handleCardAction(body);
                }
            }

            // 立即返回200，不等待处理完成
            return Map.of("code", 0, "msg", "ok");

        } catch (Exception e) {
            log.error("处理请求异常", e);
            return Map.of("code", 500, "msg", "服务器内部错误");
        }
    }

    /**
     * 处理卡片按钮回调（card.action.trigger）
     * 用户点击帮助中心卡片按钮后，飞书会 POST 此事件到 webhook
     */
    @SuppressWarnings("unchecked")
    private void handleCardAction(Map<String, Object> body) {
        Map<String, Object> event = (Map<String, Object>) body.get("event");
        Map<String, Object> action = (Map<String, Object>) event.get("action");
        Map<String, Object> value = (Map<String, Object>) action.get("value");
        String actionName = (String) value.get("action");

        Map<String, Object> container = (Map<String, Object>) event.get("container");
        String openId = (String) container.get("open_id");
        String chatId = (String) container.get("chat_id");

        String reply = getHelpReply(actionName);
        if (reply != null) {
            String receiveId = (chatId != null && !chatId.isEmpty()) ? chatId : openId;
            feishuClient.sendText(receiveId, reply);
            log.info("卡片按钮回调已回复: action={}, receiveId={}", actionName, receiveId);
        }
    }

    /**
     * 根据 action 值返回对应指令的使用说明
     */
    private String getHelpReply(String actionName) {
        return switch (actionName) {
            case "help_weather" -> "🌤 天气查询\n用法：/weather <城市>\n例如：/weather 北京";
            case "help_translate" -> "🌐 翻译\n用法：/translate <文本>\n例如：/translate Hello";
            case "help_schedule" -> "📅 创建日程\n用法：/schedule <时间> <事件>\n例如：/schedule 2024-01-15 15:00 团队会议";
            case "help_group" -> "👥 创建群组\n用法：/group <群名>\n例如：/group 项目组";
            case "help_search" -> "🔍 搜索文档\n用法：/search <关键词>\n例如：/search 需求文档";
            case "help_ai" -> "🤖 AI问答\n用法：/AI <问题>\n例如：/AI 如何创建项目";
            case "help_repo" -> "📦 查看仓库\n用法：/repo <owner/repo>\n例如：/repo facebook/react";
            case "help_pr" -> "🔀 查看PR\n用法：/pr <owner/repo> <号>\n例如：/pr facebook/react 12345";
            case "help_cr" -> "🔍 代码审查\n用法：/cr <owner/repo> <号>\n例如：/cr microsoft/vscode 12345";
            case "help_uptime" -> "⏱ 运行时间\n用法：/uptime";
            case "help_ping" -> "📶 ping\n用法：/ping <主机>\n例如：/ping baidu.com";
            case "help_deploy" -> "🚀 部署\n用法：/deploy <环境>\n例如：/deploy test";
            default -> null;
        };
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Object health() {
        return Map.of("status", "ok", "service", "IntelligenTxtSystem");
    }
}
