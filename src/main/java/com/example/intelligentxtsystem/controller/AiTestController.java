package com.example.intelligentxtsystem.controller;

import com.example.intelligentxtsystem.dto.FeishuSender;
import com.example.intelligentxtsystem.service.AiUnderstandingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 智能理解测试控制器
 * 
 * 接口列表：
 * 1. GET /api/ai/test      测试 AI 理解功能
 * 2. GET /api/ai/history   查看对话历史
 * 3. GET /api/ai/clear     清空对话历史
 */
@RestController
@RequestMapping("/api/ai")
public class AiTestController {

    private final AiUnderstandingService aiUnderstandingService;

    public AiTestController(AiUnderstandingService aiUnderstandingService) {
        this.aiUnderstandingService = aiUnderstandingService;
    }

    /**
     * 测试 AI 理解功能
     * 
     * 请求参数：
     * - message: 用户消息
     * - userId: 用户ID（可选，默认 test_user）
     * - chatId: 群聊ID（可选，默认 test_chat）
     * 
     * 示例：
     * GET /api/ai/test?message=我想查天气&userId=user1&chatId=chat1
     */
    @GetMapping("/test")
    public Map<String, Object> testAiUnderstanding(
            @RequestParam String message,
            @RequestParam(defaultValue = "test_user") String userId,
            @RequestParam(defaultValue = "test_chat") String chatId) {

        Map<String, Object> result = new HashMap<>();

        try {
            // 构造测试用的 FeishuSender
            FeishuSender sender = new FeishuSender();
            sender.setSenderId(userId);
            sender.setSenderType("user");

            // 调用 AI 理解服务
            String reply = aiUnderstandingService.processNaturalLanguage(
                    message,
                    sender,
                    chatId,
                    null  // 测试时没有 mentions
            );

            result.put("success", true);
            result.put("message", message);
            result.put("userId", userId);
            result.put("chatId", chatId);
            result.put("reply", reply != null ? reply : "AI 无法理解该消息");

            // 获取对话历史
            List<String> history = aiUnderstandingService.getConversationHistory(chatId, userId, 10);
            result.put("history", history);
            result.put("historyCount", history.size());

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 查看对话历史
     * 
     * 请求参数：
     * - userId: 用户ID（可选，默认 test_user）
     * - chatId: 群聊ID（可选，默认 test_chat）
     * - limit: 返回最近N条（可选，默认10）
     * 
     * 示例：
     * GET /api/ai/history?userId=user1&chatId=chat1&limit=5
     */
    @GetMapping("/history")
    public Map<String, Object> getHistory(
            @RequestParam(defaultValue = "test_user") String userId,
            @RequestParam(defaultValue = "test_chat") String chatId,
            @RequestParam(defaultValue = "10") int limit) {

        Map<String, Object> result = new HashMap<>();

        try {
            List<String> history = aiUnderstandingService.getConversationHistory(chatId, userId, limit);

            result.put("success", true);
            result.put("userId", userId);
            result.put("chatId", chatId);
            result.put("limit", limit);
            result.put("history", history);
            result.put("historyCount", history.size());

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 清空对话历史
     * 
     * 请求参数：
     * - userId: 用户ID（可选，默认 test_user）
     * - chatId: 群聊ID（可选，默认 test_chat）
     * 
     * 示例：
     * GET /api/ai/clear?userId=user1&chatId=chat1
     */
    @GetMapping("/clear")
    public Map<String, Object> clearHistory(
            @RequestParam(defaultValue = "test_user") String userId,
            @RequestParam(defaultValue = "test_chat") String chatId) {

        Map<String, Object> result = new HashMap<>();

        try {
            aiUnderstandingService.clearConversationHistory(chatId, userId);

            result.put("success", true);
            result.put("message", "对话历史已清空");
            result.put("userId", userId);
            result.put("chatId", chatId);

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }
}
