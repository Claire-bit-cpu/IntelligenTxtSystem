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
     * 获取发消息人的 ID（包含 open_id 和 user_id）
     */
    @Command(
            name = "myid",
            aliases = {"我的id", "whoami"},
            description = "获取你的飞书用户ID（Open ID 和 User ID）",
            usage = "/myid",
            requiresAuth = false
    )
    public String handleMyId(CommandContext context) {
        if (context.getSender() == null || context.getSender().getSender_id() == null) {
            return "❌ 无法获取你的用户信息，请确认消息来源有效。";
        }

        String openId = context.getSender().getOpenId();
        String userId = context.getSender().getUserId();
        String idUsedForAuth = context.getUserId(); // 实际用于权限检查的 ID

        log.info("用户查询自己的ID: openId={}, userId={}, authId={}", 
                maskId(openId), maskId(userId), maskId(idUsedForAuth));

        return String.format(
                "✅ **你的飞书用户信息**\n\n" +
                "**Open ID：** `%s`\n" +
                "**User ID：** `%s`\n" +
                "**权限检查使用的 ID：** `%s`\n\n" +
                "💡 **权限配置说明：**\n" +
                "• 如果权限检查使用的是 Open ID，请将上方 **Open ID** 添加到权限名单\n" +
                "• 如果权限检查使用的是 User ID，请将上方 **User ID** 添加到权限名单\n" +
                "• 使用 API：`POST /api/auth/users/add` 添加用户到权限组\n\n" +
                "🔍 **调试信息：**\n" +
                "• getUserId() 返回：`%s`\n" +
                "• 这就是权限检查时实际使用的 ID",
                openId != null ? openId : "（无）",
                userId != null ? userId : "（无）",
                idUsedForAuth != null ? idUsedForAuth : "（无）",
                idUsedForAuth != null ? idUsedForAuth : "（无）"
        );
    }

    /**
     * 脱敏 ID（日志用）
     */
    private String maskId(String id) {
        if (id == null || id.length() < 8) return "***";
        return id.substring(0, 4) + "***" + id.substring(id.length() - 4);
    }
}
