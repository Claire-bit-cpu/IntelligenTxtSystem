package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.annotation.Command;
import com.example.intelligentxtsystem.dto.CommandContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 运行时间指令处理器（新框架版本）
 * 指令格式：/uptime
 */
@Component
public class UptimeCommandHandler {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private final long startTime = System.currentTimeMillis();

    @Command(
        name = "uptime",
        description = "查看系统运行时间",
        usage = "/uptime"
    )
    public String handle(CommandContext context) {
        long uptimeMs = System.currentTimeMillis() - startTime;
        long days = uptimeMs / (1000 * 60 * 60 * 24);
        long hours = (uptimeMs % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60);
        long minutes = (uptimeMs % (1000 * 60 * 60)) / (1000 * 60);
        long seconds = (uptimeMs % (1000 * 60)) / 1000;

        String now = LocalDateTime.now(ZONE).format(FORMATTER);

        return String.format("""
                📊 系统状态

                🕐 当前时间：%s
                ⏱  运行时间：%d天 %d小时 %d分钟 %d秒
                ✅ 状态：正常运行
                """, now, days, hours, minutes, seconds);
    }
}
