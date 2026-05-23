package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.client.QwenClient;
import com.example.intelligentxtsystem.dto.FeishuSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 翻译指令处理器
 * 指令格式：/translate <文本> 或 翻译 <文本>
 * 使用通义千问进行中英互译
 */
@Component
public class TranslateHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(TranslateHandler.class);
    private final QwenClient qwenClient;

    public TranslateHandler(QwenClient qwenClient) {
        this.qwenClient = qwenClient;
    }

    @Override
    public boolean support(String text) {
        return text.startsWith("/translate") || text.startsWith("翻译");
    }

    @Override
    public String handle(String text, FeishuSender sender, String chatId) {
        String content = text
                .replaceAll("^(/translate|翻译)\\s*", "")
                .trim();

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
