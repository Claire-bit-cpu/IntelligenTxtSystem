package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.client.FeishuClient;
import com.example.intelligentxtsystem.dto.FeishuSender;
import org.springframework.stereotype.Component;

/**
 * 帮助指令处理器
 * 指令格式：/help 或 help
 * 返回交互式卡片（通过特殊前缀 __CARD__ 标记）
 */
@Component
public class HelpHandler implements CommandHandler {

    @Override
    public boolean support(String text) {
        String trimmed = text.trim().toLowerCase();
        return trimmed.equals("help") || trimmed.equals("/help");
    }

    @Override
    public String handle(String text, FeishuSender sender, String chatId) {
        // 用特殊前缀标记这是卡片消息，MessageProcessor 会识别并发送卡片
        return "__CARD__" + FeishuClient.buildHelpCard();
    }
}
