package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.client.QwenClient;
import com.example.intelligentxtsystem.dto.FeishuSender;
import org.springframework.stereotype.Component;

/**
 * AI智能问答指令处理器
 * 指令格式：/AI <问题>
 * 使用通义千问进行智能问答
 */
@Component
public class AIHandler implements CommandHandler {

    private final QwenClient qwenClient;

    public AIHandler(QwenClient qwenClient) {
        this.qwenClient = qwenClient;
    }

    @Override
    public boolean support(String text) {
        return text.startsWith("/AI") || text.startsWith("/ai");
    }

    @Override
    public String handle(String text, FeishuSender sender, String chatId) {
        String query = text
                .replaceAll("^(/[Aa][Ii])\\s*", "")
                .trim();

        if (query.isEmpty()) {
            return """
                    ❌ 用法：/AI <问题>
                    
                    📋 示例：
                    /AI 如何创建项目
                    /AI Java数组怎么用
                    
                    💡 可以询问任何技术或业务问题
                    """;
        }

        if (query.length() > 1000) {
            return "⚠️ 问题长度不能超过1000个字符";
        }

        try {
            String answer = qwenClient.answerQuestion(query);
            return String.format("""
                    🤖 问题：%s
                    
                    💡 回答：
                    %s
                    """, query, answer);
        } catch (Exception e) {
            return "⚠️ AI服务暂时不可用，请稍后再试";
        }
    }
}
