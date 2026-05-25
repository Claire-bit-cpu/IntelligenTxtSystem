package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.client.FeishuClient;
import com.example.intelligentxtsystem.dto.FeishuSender;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日程指令处理器
 */
@Component
public class ScheduleHandler implements CommandHandler {

    private final FeishuClient feishuClient;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private static final Pattern SCHEDULE_PATTERN_WITH_END =
            Pattern.compile("^(?:/schedule|日程)\\s*(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})\\s+(\\d{2}:\\d{2})\\s+(.+)");
    private static final Pattern SCHEDULE_PATTERN =
            Pattern.compile("^(?:/schedule|日程)\\s*(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})\\s+(.+)");

    public ScheduleHandler(FeishuClient feishuClient) {
        this.feishuClient = feishuClient;
    }

    @Override
    public boolean support(String text) {
        return text.startsWith("/schedule") || text.startsWith("日程") || text.startsWith("创建日程");
    }

    @Override
    public String handle(String text, FeishuSender sender, String chatId) {
        LocalDateTime endTime = null;
        Matcher matcherWithEnd = SCHEDULE_PATTERN_WITH_END.matcher(text);
        Matcher matcher = SCHEDULE_PATTERN.matcher(text);

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
            String userOpenId = sender != null ? sender.getOpenId() : null;
            String result = feishuClient.createCalendarEventWithAttendee(event, dateTime, endTime, userOpenId);
            if ("success".equals(result)) {
                String endInfo = endTime != null ? "\n⏰ 结束：" + endTime.format(TIME_FMT) : "";
                return String.format("""
                    📅 日程创建成功！
                    
                    📋 事件：%s
                    ⏰ 开始：%s%s
                    
                    ✅ 已添加到你的飞书日历
                    """, event, dateTimeStr, endInfo);
            } else {
                return String.format("""
                    ⚠️ 日程创建遇到问题
                    
                    📋 事件：%s
                    ⏰ 时间：%s
                    📌 原因：%s
                    
                    请检查飞书应用权限后重试
                    """, event, dateTimeStr, result);
            }
        } catch (DateTimeParseException e) {
            return "⚠️ 时间格式错误，请使用：YYYY-MM-DD HH:mm";
        }
    }
}
