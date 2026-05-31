package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.dto.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 测试指令处理器
 * 用于验证指令扩展框架是否正常工作
 */
@Component
public class TestCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(TestCommandHandler.class);

    /**
     * 获取发消息人的 ID
     */
    @Command(
            name = "myid",
            description = "获取你的飞书用户ID",
            usage = "/myid",
            requiresAuth = false
    )
    public String handleMyId(CommandContext context) {
        String openId = context.getSender().getOpenId();

        if (openId == null) {
            return "❌ 无法获取你的用户ID，请确认消息来源有效。";
        }

        log.info("用户查询自己的ID: {}", openId);

        return String.format(
                "✅ 你的飞书用户信息：\n\n**Open ID：** `%s`\n\n💡 这就是你的唯一标识，可以用于统计、鉴权等场景。",
                openId
        );
    }
}
