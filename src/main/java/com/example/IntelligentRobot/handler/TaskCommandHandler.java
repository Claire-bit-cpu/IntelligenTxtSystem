package com.example.IntelligentRobot.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.service.TaskMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 任务监控面板指令处理器
 * 
 * 支持的指令：
 * - /task          查看任务监控面板（仅状态变化时推送）
 * - /task force    强制刷新任务监控面板
 * - /task refresh  强制刷新任务监控面板（发送新消息）
 * - /task reset    重置任务监控状态
 */
@Component
public class TaskCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(TaskCommandHandler.class);

    @Autowired(required = false)
    private TaskMonitorService taskMonitorService;

    /**
     * 查看任务监控面板
     * 
     * 使用方式：
     * - /task          查看面板（仅状态变化时推送）
     * - /task help     显示帮助信息
     */
    @Command(
            name = "task",
            description = "查看任务监控面板",
            permissionLevel = "NONE",  // 公开指令，无需鉴权
            usage = "/task [force|refresh|reset|help]"
    )
    public String handleTask(CommandContext context) {
        if (taskMonitorService == null) {
            return "❌ TaskMonitorService 未启用，请检查配置";
        }

        String args = context.getArgs();
        if (args == null) {
            args = "";
        }
        args = args.trim().toLowerCase();

        try {
            // 处理子命令
            if (args.isEmpty() || args.equals("status") || args.equals("view")) {
                // 默认行为：查看任务监控面板（仅状态变化时推送）
                Map<String, Object> result = taskMonitorService.pushMonitorCard(false);
                return formatResult(result, false);
            } else if (args.equals("force") || args.equals("f")) {
                // 强制推送
                Map<String, Object> result = taskMonitorService.pushMonitorCard(true);
                return formatResult(result, true);
            } else if (args.equals("refresh") || args.equals("r")) {
                // 刷新（发送新消息）
                Map<String, Object> result = taskMonitorService.triggerPush();
                return formatResult(result, true);
            } else if (args.equals("reset")) {
                // 重置监控状态
                taskMonitorService.resetMonitor();
                return "✅ 任务监控状态已重置，下次将发送新消息";
            } else if (args.equals("help") || args.equals("h")) {
                // 显示帮助
                return buildHelpText();
            } else {
                // 未知子命令
                return "❌ 未知参数: " + args + "\n\n" + buildHelpText();
            }
        } catch (Exception e) {
            log.error("执行 /task 指令失败", e);
            return "❌ 执行失败: " + e.getMessage();
        }
    }

    /**
     * 格式化推送结果
     */
    private String formatResult(Map<String, Object> result, boolean isForce) {
        if (result == null) {
            return "❌ 推送失败：未知错误";
        }

        Integer code = (Integer) result.get("code");
        String msg = (String) result.get("msg");
        Boolean skipped = (Boolean) result.get("skipped");

        if (code == 0) {
            // 成功
            if (Boolean.TRUE.equals(skipped)) {
                return "ℹ️ " + msg + "\n\n💡 使用 `/task force` 强制刷新";
            } else {
                String messageId = (String) result.get("messageId");
                Integer updateCount = (Integer) result.get("updateCount");
                
                StringBuilder sb = new StringBuilder();
                sb.append("✅ ").append(msg).append("\n\n");
                if (messageId != null) {
                    sb.append("📨 消息ID: `").append(messageId).append("`\n");
                }
                if (updateCount != null) {
                    sb.append("🔄 编辑次数: ").append(updateCount).append("/100\n");
                }
                return sb.toString();
            }
        } else {
            // 失败
            return "❌ " + msg;
        }
    }

    /**
     * 构建帮助文本
     */
    private String buildHelpText() {
        StringBuilder sb = new StringBuilder();
        sb.append("📖 **任务监控面板指令帮助**\n\n");
        sb.append("**基本用法：**\n");
        sb.append("• `/task` 或 `/task status` - 查看任务监控面板（仅状态变化时推送）\n");
        sb.append("• `/task force` 或 `/task f` - 强制刷新任务监控面板\n");
        sb.append("• `/task refresh` 或 `/task r` - 刷新面板（发送新消息）\n");
        sb.append("• `/task reset` - 重置监控状态\n");
        sb.append("• `/task help` 或 `/task h` - 显示此帮助信息\n\n");
        
        sb.append("**别名：**\n");
        sb.append("• `/tasks` - 等同于 `/task`\n");
        sb.append("• `/监控` - 等同于 `/task`\n");
        sb.append("• `/monitor` - 等同于 `/task`\n\n");
        
        sb.append("**说明：**\n");
        sb.append("• 默认模式下，只有任务状态变化时才推送消息\n");
        sb.append("• 强制刷新会忽略状态变化检测\n");
        sb.append("• 消息编辑次数接近 100 次时，会自动发送新消息\n");
        
        return sb.toString();
    }
}
