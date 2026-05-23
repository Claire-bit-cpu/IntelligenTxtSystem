package com.example.intelligentxtsystem.service;

import com.example.intelligentxtsystem.dto.FeishuSender;
import com.example.intelligentxtsystem.service.handler.CommandHandler;
import org.springframework.stereotype.Service;

import java.util.List;

/*
消息分发器，根据指令类型交给正确的处理器
 */
@Service
public class MessageDispatcher {

    private final List<CommandHandler> handlers;

    public MessageDispatcher(List<CommandHandler> handlers) {
        this.handlers = handlers;
    }

    public String dispatch(String text, FeishuSender sender, String chatId) {
        for (CommandHandler handler : handlers) {
            if (handler.support(text)) {
                return handler.handle(text, sender, chatId);
            }
        }
        return "我不认识这个命令";
    }
}