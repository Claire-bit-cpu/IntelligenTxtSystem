package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.annotation.Command;
import com.example.intelligentxtsystem.client.FeishuClient;
import com.example.intelligentxtsystem.dto.CommandContext;
import com.example.intelligentxtsystem.service.ContextManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 修改日程指令处理器
 * 支持修改最近创建的日程时间
 * 
 * 使用方式：
 * 1. /updateschedule 17:00 - 修改最近日程到17:00
 * 2. /updateschedule 2026-05-30 17:00 - 指定日期和时间
 * 3. 通过AI自然语言：把刚才的会议改到下午5点
 */
@Component
public class UpdateScheduleCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(UpdateScheduleCommandHandler.class);

    private final FeishuClient feishuClient;
    private final ContextManager contextManager;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public UpdateScheduleCommandHandler(FeishuClient feishuClient, ContextManager contextManager) {
        this.feishuClient = feishuClient;
        this.contextManager = contextManager;
    }

    @Command(
        name = "updateschedule",
        description = "修改已创建的飞书日程",
        usage = "/updateschedule <新时间> [事件名]"
    )
    public String handle(CommandContext context) {
        String args = context.getArgs().trim();
        String userId = context.getSender() != null ? context.getSender().getId() : "";

        if (args.isEmpty()) {
            return """
                    ❌ 用法：/updateschedule <新时间> [事件名]
                    
                    📅 示例：
                    /updateschedule 17:00
                    /updateschedule 2026-05-30 17:00
                    /updateschedule 17:00 会议1
                    
                    💡 时间格式：HH:mm 或 yyyy-MM-dd HH:mm
                    💡 需要先创建日程，才能修改
                    """;
        }

        // 解析新时间
        LocalDateTime newStartTime = parseTime(args);
        if (newStartTime == null) {
            return "⚠️ 时间格式错误，请使用：HH:mm 或 yyyy-MM-dd HH:mm";
        }

        // 从上下文获取最近创建的日程信息
        Map<String, Object> globalParams = contextManager.getGlobalParams(userId);
        String eventId = globalParams != null ? (String) globalParams.get("last_event_id") : null;
        String eventName = globalParams != null ? (String) globalParams.get("last_event_name") : null;

        // 检查参数中是否指定了事件名
        String[] parts = args.split("\\s+");
        if (parts.length > 1) {
            // 最后一部分可能是事件名（如果不是时间格式）
            String lastPart = parts[parts.length - 1];
            if (!lastPart.matches("\\d{2}:\\d{2}") && !lastPart.matches("\\d{4}-\\d{2}-\\d{2}")) {
                eventName = lastPart;
            }
        }

        if (eventId == null || eventId.isEmpty()) {
            return """
                    ⚠️ 没有找到最近创建的日程
                    
                    💡 请先使用 /schedule 创建日程
                    💡 创建后可以使用本命令修改
                    """;
        }

        try {
            // 计算结束时间（默认1小时后）
            LocalDateTime newEndTime = newStartTime.plusHours(1);

            log.info("修改日程: eventId={}, eventName={}, newStartTime={}", 
                    eventId, eventName, newStartTime);

            // 调用飞书API修改日程
            String result = feishuClient.updateCalendarEvent(eventId, eventName, newStartTime, newEndTime);

            if ("success".equals(result)) {
                // 更新上下文中的时间
                contextManager.setGlobalParam(userId, "last_event_time", 
                        String.valueOf(System.currentTimeMillis()));
                
                return String.format("""
                    ✅ 日程修改成功！
                    
                    📋 事件：%s
                    ⏰ 新时间：%s
                    
                    ✅ 已更新到你的飞书日历
                    """, eventName != null ? eventName : "未命名", newStartTime.format(DT_FMT));
            } else {
                return String.format("""
                    ⚠️ 日程修改失败
                    
                    📌 原因：%s
                    
                    💡 可能原因：
                    • 日程已被删除
                    • 权限不足
                    • 网络问题
                    
                    请稍后重试或手动在飞书中修改
                    """, result);
            }
        } catch (Exception e) {
            log.error("修改日程异常: eventId={}", eventId, e);
            return "⚠️ 修改日程异常：" + e.getMessage();
        }
    }

    /**
     * 解析时间字符串
     * 支持格式：HH:mm 或 yyyy-MM-dd HH:mm
     */
    private LocalDateTime parseTime(String text) {
        // 去除事件名，只保留时间部分
        String timeStr = text.split("\\s+")[0];
        
        // 尝试完整格式 yyyy-MM-dd HH:mm
        try {
            String dateTimeStr = text.length() >= 16 ? text.substring(0, 16) : text;
            return LocalDateTime.parse(dateTimeStr, DT_FMT);
        } catch (DateTimeParseException e) {
            // 忽略
        }

        // 尝试仅时间格式 HH:mm，使用今天的日期
        try {
            LocalDateTime time = LocalDateTime.parse(
                    java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + " " + timeStr,
                    DT_FMT
            );
            // 如果时间已过，使用明天
            if (time.isBefore(LocalDateTime.now())) {
                time = time.plusDays(1);
            }
            return time;
        } catch (DateTimeParseException e) {
            // 忽略
        }

        return null;
    }
}
