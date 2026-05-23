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
 * 指令格式：
 *   /schedule <时间> <事件>              （默认1小时）
 *   /schedule <开始时间> <结束时间> <事件> （指定结束时间）
 * 示例：
 *   /schedule 2024-01-15 15:00 团队会议
 *   /schedule 2024-01-15 15:00 16:00 项目评审
 */
@Component
public class ScheduleHandler implements CommandHandler {

    private final FeishuClient feishuClient;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    // /schedule 2024-01-15 15:00 16:00 团队会议（带结束时间）
    private static final Pattern SCHEDULE_PATTERN_WITH_END =
            Pattern.compile("^(?:/schedule|日程)\\s*(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2})\\s+(\\d{2}:\\d{2})\\s+(.+)");

    // /schedule 2024-01-15 15:00 团队会议（不带结束时间，默认1小时）
    private static final Pattern SCHEDULE_PATTERN =
            Pattern.compile("^(?:/schedule|日程)\\s*(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2})\\s+(.+)");

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

        // 先尝试匹配带结束时间的格式
        Matcher matcherWithEnd = SCHEDULE_PATTERN_WITH_END.matcher(text);
        Matcher matcher = SCHEDULE_PATTERN.matcher(text);

        String dateTimeStr;
        String event;

        if (matcherWithEnd.find()) {
            dateTimeStr = matcherWithEnd.group(1);
            String endTimeStr = matcherWithEnd.group(2);
            event = matcherWithEnd.group(3);
            try {
                endTime = LocalDateTime.parse(
                        dateTimeStr.substring(0, 10) + " " + endTimeStr, DT_FMT);
            } catch (DateTimeParseException e) {
                return "⚠️ 结束时间格式错误，请使用：HH:mm";
            }
        } else if (matcher.find()) {
            dateTimeStr = matcher.group(1);
            event = matcher.group(2);
        } else {
            return """
                    ❌ 用法：/schedule <时间> <事件>
                    
                    📅 示例：
                    /schedule 2024-01-15 15:00 团队周会
                    /schedule 2024-01-15 15:00 16:00 项目评审
                    
                    💡 时间格式：YYYY-MM-DD HH:mm
                    💡 可选指定结束时间：/schedule 日期 开始时间 结束时间 事件
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

            // 获取发送者的 open_id
            String userOpenId = sender != null ? sender.getOpenId() : null;

            // 调用飞书日历 API 创建日程（邀请用户参与）
            String result = feishuClient.createCalendarEventWithAttendee(event, dateTime, endTime, userOpenId);

            if ("success".equals(result)) {
                String endInfo = endTime != null
                        ? "\n⏰ 结束：" + endTime.format(TIME_FMT)
                        : "";
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
