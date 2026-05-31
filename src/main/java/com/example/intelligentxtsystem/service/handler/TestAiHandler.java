package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.annotation.Command;
import com.example.intelligentxtsystem.dto.CommandContext;
import com.example.intelligentxtsystem.service.AiUnderstandingService;
import org.springframework.stereotype.Component;

/**
 * 测试 AI 智能理解功能
 * 指令格式：/test_ai <消息>
 * 用于测试 AI 是否能正确理解自然语言消息
 */
@Component
public class TestAiHandler {

    private final AiUnderstandingService aiUnderstandingService;

    public TestAiHandler(AiUnderstandingService aiUnderstandingService) {
        this.aiUnderstandingService = aiUnderstandingService;
    }

    @Command(
        name = "test_ai",
        description = "测试 AI 智能理解功能",
        usage = "/test_ai <消息>"
    )
    public String handle(CommandContext context) {
        String message = context.getArgs().trim();

        if (message.isEmpty()) {
            return """
                    ❌ 用法：/test_ai <消息>
                    
                    📋 示例：
                    /test_ai 我想查天气
                    /test_ai 帮我看看代码
                    
                    💡 这个指令用于测试 AI 是否能正确理解自然语言消息
                    """;
        }

        try {
            String result = aiUnderstandingService.processNaturalLanguage(
                message,
                context.getSender(),
                context.getChatId(),
                context.getMentions()
            );

            if (result != null) {
                return "🤖 AI 理解结果：\n\n" + result;
            } else {
                return "⚠️ AI 无法理解这条消息，建议直接使用 / 开头的指令";
            }

        } catch (Exception e) {
            return "⚠️ 测试失败：" + e.getMessage();
        }
    }
}
