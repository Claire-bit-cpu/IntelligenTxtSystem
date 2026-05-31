package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.annotation.Command;
import com.example.intelligentxtsystem.client.FeishuClient;
import com.example.intelligentxtsystem.dto.CommandContext;
import org.springframework.stereotype.Component;

/**
 * 帮助指令处理器（新框架版本）
 * 指令格式：/help 或 help
 * 返回交互式卡片（通过特殊前缀 __CARD__ 标记）
 */
@Component
public class HelpCommandHandler {

    @Command(
        name = "help",
        description = "查看所有可用指令的帮助文档",
        usage = "/help"
    )
    public String handle(CommandContext context) {
        // 用特殊前缀标记这是卡片消息，MessageProcessor 会识别并发送卡片
        return "__CARD__" + FeishuClient.buildHelpCard();
    }
}
