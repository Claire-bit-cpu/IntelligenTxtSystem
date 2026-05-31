package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.QwenClient;
import com.example.IntelligentRobot.dto.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 翻译指令处理器（新框架版本）
 * 指令格式：/translate <文本> 或 翻译 <文本>
 * 使用通义千问进行中英互译
 */
@Component
public class TranslateCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(TranslateCommandHandler.class);
    
    private final QwenClient qwenClient;

    public TranslateCommandHandler(QwenClient qwenClient) {
        this.qwenClient = qwenClient;
    }

    @Command(
        name = "translate",
        description = "中英互译",
        usage = "/translate <文本>"
    )
    public String handle(CommandContext context) {
        String content = context.getArgs().trim();

        if (content.isEmpty()) {
            return "❌ 用法：/translate <文本>\n例如：/translate Hello";
        }

        if (content.length() > 500) {
            return "⚠️ 文本长度不能超过 500 字符";
        }

        try {
            String translated = qwenClient.translate(content);
            log.info("翻译结果: {}", translated);
            return "🌐 翻译结果：\n" + translated;
        } catch (Exception e) {
            log.warn("翻译异常: {}", e.getMessage());
            return "⚠️ 翻译服务暂时不可用，请稍后再试";
        }
    }
}
