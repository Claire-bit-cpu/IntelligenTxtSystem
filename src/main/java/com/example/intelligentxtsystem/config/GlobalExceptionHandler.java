package com.example.intelligentxtsystem.config;

import com.example.intelligentxtsystem.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 全局异常处理器
 * 统一处理未捕获异常，避免返回500错误堆栈给调用方
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Autowired
    private NotificationService notificationService;

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleException(Exception e) {
        log.error("未捕获异常", e);

        // 发送异常预警通知
        try {
            String detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            String timestamp = java.time.LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            notificationService.sendNotification(
                    "⚠️ 系统异常预警\n⏰ 时间: " + timestamp + "\n📝 详情: " + detail
            );
        } catch (Exception ex) {
            log.error("发送异常预警通知失败", ex);
        }

        return Map.of("code", 500, "msg", "服务器内部错误");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(IllegalArgumentException e) {
        log.warn("请求参数错误: {}", e.getMessage());
        return Map.of("code", 400, "msg", e.getMessage());
    }
}
