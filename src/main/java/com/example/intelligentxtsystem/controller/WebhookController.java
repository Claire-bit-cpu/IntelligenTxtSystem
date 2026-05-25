package com.example.intelligentxtsystem.controller;

import com.example.intelligentxtsystem.service.ApprovalService;
import com.example.intelligentxtsystem.service.MessageProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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
    private MessageProcessor messageProcessor;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private com.example.intelligentxtsystem.client.FeishuClient feishuClient;

    /**
     * 飞书回调入口（仅接受 POST 请求）
     * 飞书 URL 验证要求：
     *   请求: {"type": "url_verification", "challenge": "xxx"}
     *   响应: {"challenge": "xxx"}  (Content-Type: application/json)
     */
    @PostMapping(value = "/webhook",
            consumes = "application/json",
            produces = "application/json")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestBody String rawBody
    ) {
        log.info("收到飞书请求: rawBody={}", rawBody);

        try {
            // 解析 JSON
            Map<String, Object> body = objectMapper.readValue(rawBody, Map.class);

            // ===== URL 验证请求（必须最优先处理）=====
            if ("url_verification".equals(body.get("type"))) {
                Object challenge = body.get("challenge");
                if (challenge == null) {
                    log.error("URL 验证请求缺少 challenge 字段");
                    return ResponseEntity.badRequest().build();
                }
                log.info("处理 URL 验证请求, challenge={}", challenge);
                // 直接返回 {"challenge": "xxx"}，无任何额外包装
                Map<String, Object> result = new HashMap<>();
                result.put("challenge", challenge);
                return ResponseEntity.ok(result);
            }

            // ===== 处理业务事件 =====
            if (body.containsKey("header")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> header = (Map<String, Object>) body.get("header");
                String eventType = (String) header.get("event_type");
                log.info("收到飞书事件: event_type={}", eventType);

                if ("im.message.receive_v1".equals(eventType)) {
                    messageProcessor.processMessageEvent(body);
                }

                if ("approval.instance.state_change_v4".equals(eventType)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> event = (Map<String, Object>) body.get("event");
                    log.info("收到审批事件: event={}", event);
                    approvalService.handleApprovalStateChange(event);
                }

                if ("card.action.trigger".equals(eventType)) {
                    handleCardAction(body);
                }
            }

            // 立即返回 200，不等待处理完成
            Map<String, Object> ok = new HashMap<>();
            ok.put("code", 0);
            ok.put("msg", "ok");
            return ResponseEntity.ok(ok);

        } catch (Exception e) {
            log.error("处理请求异常", e);
            Map<String, Object> err = new HashMap<>();
            err.put("code", 500);
            err.put("msg", "服务器内部错误");
            return ResponseEntity.status(500).body(err);
        }
    }

    /**
     * 处理卡片按钮回调（card.action.trigger）
     * 飞书事件结构：
     *   event.operator.operator_id = 点击用户的 open_id
     *   event.context.open_chat_id = 消息所在群聊的 chat_id
     */
    @SuppressWarnings("unchecked")
    private void handleCardAction(Map<String, Object> body) {
        if (body == null) return;

        Map<String, Object> event = (Map<String, Object>) body.get("event");
        if (event == null) return;

        // 获取 action.value.action
        Map<String, Object> action = (Map<String, Object>) event.get("action");
        if (action == null) return;

        Map<String, Object> value = (Map<String, Object>) action.get("value");
        if (value == null) return;

        String actionName = (String) value.get("action");
        if (actionName == null) return;

        // 获取用户 open_id（点击按钮的人）
        String openId = null;
        Map<String, Object> operator = (Map<String, Object>) event.get("operator");
        if (operator != null) {
            openId = (String) operator.get("operator_id");
        }

        // 获取群聊 chat_id（消息所在的群）
        String chatId = null;
        Map<String, Object> context = (Map<String, Object>) event.get("context");
        if (context != null) {
            chatId = (String) context.get("open_chat_id");
        }

        log.info("卡片按钮回调: action={}, openId={}, chatId={}", actionName, openId, chatId);

        String reply = getHelpReply(actionName);
        if (reply != null) {
            // 优先回复到群聊，否则私聊用户
            String receiveId = (chatId != null && !chatId.isEmpty()) ? chatId : openId;
            if (receiveId != null) {
                feishuClient.sendText(receiveId, reply);
                log.info("卡片按钮回调已回复: action={}, receiveId={}", actionName, receiveId);
            }
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
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "IntelligenTxtSystem");
    }
}
