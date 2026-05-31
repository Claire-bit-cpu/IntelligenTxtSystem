package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.annotation.Command;
import com.example.intelligentxtsystem.client.QwenClient;
import com.example.intelligentxtsystem.dto.CommandContext;
import com.example.intelligentxtsystem.service.AiUnderstandingService;
import org.springframework.stereotype.Component;

/**
 * AI智能问答指令处理器（新框架版本）
 * 指令格式：/AI <问题>
 * 使用通义千问进行智能问答（支持多轮对话）
 */
@Component
public class AICommandHandler {

    private final QwenClient qwenClient;
    private final AiUnderstandingService aiUnderstandingService;

    public AICommandHandler(QwenClient qwenClient, AiUnderstandingService aiUnderstandingService) {
        this.qwenClient = qwenClient;
        this.aiUnderstandingService = aiUnderstandingService;
    }

    @Command(
        name = "ai",
        description = "AI智能问答（支持多轮对话）",
        usage = "/ai <问题>"
    )
    public String handle(CommandContext context) {
        String query = context.getArgs().trim();

        if (query.isEmpty()) {
            return """
                    ❌ 用法：/AI <问题>
                    
                    📋 示例：
                    /AI 如何创建项目
                    /AI Java数组怎么用
                    
                    💡 可以询问任何技术或业务问题
                    💡 支持多轮对话，可以接着上一轮的问题继续问
                    """;
        }

        if (query.length() > 1000) {
            return "⚠️ 问题长度不能超过1000个字符";
        }

        try {
            // 获取用户ID和聊天ID
            String userId = context.getSender().getId();
            String chatId = context.getChatId();
            
            // 构建包含对话历史的提示词
            String prompt = buildPromptWithHistory(query, chatId, userId);
            
            // 调用AI获取回答
            String answer = qwenClient.answerQuestion(prompt);
            
            // 保存对话历史
            aiUnderstandingService.saveConversationHistory(chatId, userId, query, answer);

            return String.format("""
                    🤖 问题：%s
                    
                    💡 回答：
                    %s
                    """, query, answer);
        } catch (Exception e) {
            return "⚠️ AI服务暂时不可用，请稍后再试";
        }
    }

    /**
     * 构建包含对话历史的提示词
     */
    private String buildPromptWithHistory(String query, String chatId, String userId) {
        // 获取最近的对话历史（最近5轮）
        java.util.List<String> history = aiUnderstandingService.getConversationHistory(chatId, userId, 10);
        
        StringBuilder prompt = new StringBuilder();
        
        if (!history.isEmpty()) {
            prompt.append("你和一个用户正在进行多轮对话。以下是最近的对话记录：\n\n");
            
            // 历史记录格式是 "用户：xxx" 和 "机器人：xxx"
            // 需要转换成更清晰的格式
            for (String h : history) {
                if (h.startsWith("用户：")) {
                    prompt.append("用户：").append(h.substring(3)).append("\n");
                } else if (h.startsWith("机器人：")) {
                    prompt.append("助手：").append(h.substring(4)).append("\n");
                } else {
                    prompt.append(h).append("\n");
                }
            }
            
            prompt.append("\n基于以上对话历史，继续回答用户的新问题。注意要理解上下文，不要重复之前已经回答过的内容。\n\n");
        }
        
        prompt.append("用户的新问题：").append(query).append("\n\n请回答：");
        
        return prompt.toString();
    }
}
