package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.FeishuClient;
import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.service.ContextManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日程指令处理器（新框架版本）
 * 创建日程后会保存 event_id 到 Redis，供后续修改日程使用
 */
@Component
public class ScheduleCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ScheduleCommandHandler.class);

    private final FeishuClient feishuClient;
    private final ContextManager contextManager;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private static final Pattern SCHEDULE_PATTERN_WITH_END =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})\\s+(\\d{2}:\\d{2})\\s+(.+)");
    private static final Pattern SCHEDULE_PATTERN =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})\\s+(.+)");

    // Redis Key 前缀：保存用户最近创建的日程信息
    private static final String SCHEDULE_KEY_PREFIX = "schedule:recent:";

    public ScheduleCommandHandler(FeishuClient feishuClient, ContextManager contextManager) {
        this.feishuClient = feishuClient;
        this.contextManager = contextManager;
    }

    @Command(
        name = "schedule",
        description = "创建飞书日程",
        usage = "/schedule <日期> <时间> <事件>"
    )
    public String handle(CommandContext context) {
        log.info("ScheduleCommandHandler.handle() 被调用，原始参数: {}", context.getArgs());
        
        String args = context.getArgs().trim();
        String senderOpenId = context.getSender() != null ? context.getSender().getOpenId() : null;
        String userId = context.getSender() != null ? context.getSender().getId() : "";

        LocalDateTime endTime = null;
        Matcher matcherWithEnd = SCHEDULE_PATTERN_WITH_END.matcher(args);
        Matcher matcher = SCHEDULE_PATTERN.matcher(args);
        
        log.info("解析参数: args={}, senderOpenId={}, userId={}", args, senderOpenId, userId);

        String dateTimeStr;
        String event;

        if (matcherWithEnd.find()) {
            dateTimeStr = matcherWithEnd.group(1) + " " + matcherWithEnd.group(2);
            String endTimeStr = matcherWithEnd.group(1) + " " + matcherWithEnd.group(3);
            event = matcherWithEnd.group(4);
            try {
                endTime = LocalDateTime.parse(endTimeStr, DT_FMT);
            } catch (DateTimeParseException e) {
                return "⚠️ 结束时间格式错误，请使用：HH:mm";
            }
        } else if (matcher.find()) {
            dateTimeStr = matcher.group(1) + " " + matcher.group(2);
            event = matcher.group(3);
        } else {
            return """
                    ❌ 用法：/schedule <时间> <事件>
                    
                    📅 示例：
                    /schedule 2024-01-15 15:00 团队周会
                    /schedule 2024-01-15 15:00 16:00 项目评审
                    
                    💡 时间格式：YYYY-MM-DD HH:mm
                    💡 可选指定结束时间
                    """;
        }

        try {
            LocalDateTime dateTime = LocalDateTime.parse(dateTimeStr, DT_FMT);
            if (dateTime.isBefore(LocalDateTime.now())) {
                return "⚠️ 不能创建过去时间的日程";
            }
            if (endTime != null && endTime.isBefore(dateTime)) {
                return "⚠️ 结束时间不能早于开始时间";
            }
            
            // 创建日程，现在返回 eventId（而不是 "success"）
            String eventId = feishuClient.createCalendarEventWithAttendee(event, dateTime, endTime, senderOpenId);
            
            if (eventId != null && !eventId.isEmpty()) {
                // 保存 event_id 到上下文，供后续修改日程使用
                saveRecentSchedule(userId, eventId, event);
                
                String endInfo = endTime != null ? "\n⏰ 结束：" + endTime.format(TIME_FMT) : "";
                return String.format("""
                    📅 日程创建成功！
                    
                    📋 事件：%s
                    ⏰ 开始：%s%s
                    
                    ✅ 已添加到你的飞书日历
                    💡 提示：可以使用 /updateschedule 17:00 修改时间
                    """, event, dateTimeStr, endInfo);
            } else {
                return String.format("""
                    ⚠️ 日程创建遇到问题
                    
                    📋 事件：%s
                    ⏰ 时间：%s
                    📌 原因：创建失败，请检查飞书应用权限
                    
                    请检查飞书应用权限后重试
                    """, event, dateTimeStr);
            }
        } catch (DateTimeParseException e) {
            return "⚠️ 时间格式错误，请使用：YYYY-MM-DD HH:mm";
        }
    }

    /**
     * 保存最近创建的日程信息到 Redis
     * 供修改日程时使用
     */
    private void saveRecentSchedule(String userId, String eventId, String eventName) {
        try {
            // 使用 ContextManager 保存全局参数
            contextManager.setGlobalParam(userId, "last_event_id", eventId);
            contextManager.setGlobalParam(userId, "last_event_name", eventName);
            contextManager.setGlobalParam(userId, "last_event_time", 
                    String.valueOf(System.currentTimeMillis()));
            
            log.info("保存日程信息到上下文: userId={}, eventId={}, eventName={}", 
                    userId, eventId, eventName);
        } catch (Exception e) {
            log.error("保存日程信息失败: userId={}", userId, e);
        }
    }
}
