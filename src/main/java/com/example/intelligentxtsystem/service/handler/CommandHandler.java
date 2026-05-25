package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.dto.FeishuSender;

/**
 * 命令处理器接口，是整个指令系统的最小可扩展单元
 */
public interface CommandHandler {
    boolean support(String text);
    String handle(String text, FeishuSender sender, String chatId);
}
