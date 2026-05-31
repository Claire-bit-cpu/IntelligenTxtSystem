package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.service.AiUnderstandingService;
import org.springframework.stereotype.Component;

/**
 * 清空对话历史指令处理器
 * 指令格式：/clear_history
 * 用于清空当前用户的对话历史，重新开始新对话
 */
@Component
public class ClearHistoryHandler {

    private final AiUnderstandingService aiUnderstandingService;

    public ClearHistoryHandler(AiUnderstandingService aiUnderstandingService) {
        this.aiUnderstandingService = aiUnderstandingService;
    }

    @Command(
        name = "clear_history",
        description = "清空对话历史",
        usage = "/clear_history"
    )
    public String handle(CommandContext context) {
        String userId = context.getSender().getId();  // 使用 getId() 获取用户ID
        String chatId = context.getChatId();

        try {
            aiUnderstandingService.clearConversationHistory(chatId, userId);
            return "✅ 对话历史已清空，我们可以重新开始对话了！";
        } catch (Exception e) {
            return "⚠️ 清空对话历史失败：" + e.getMessage();
        }
    }
}
