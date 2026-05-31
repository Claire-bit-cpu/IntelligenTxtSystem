package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.service.WeatherService;
import org.springframework.stereotype.Component;

/**
 * 天气指令处理器（新框架版本）
 * 指令格式：/weather 城市名 或 天气 城市名
 *
 * 支持上下文感知：
 * - 第一次查询：/weather 深圳
 * - 第二次查询：/weather（自动复用深圳）
 * - 超时时间：5分钟
 */
@Component
public class WeatherCommandHandler {

    private final WeatherService weatherService;

    public WeatherCommandHandler(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WeatherCommandHandler.class);

    @Command(
        name = "weather",
        description = "查询城市天气（支持上下文感知）",
        usage = "/weather <城市名>",
        supportsContext = true,
        contextType = "weather",
        localParams = {"raw_input"},  // 使用 raw_input 作为上下文参数（与 CommandRegistry.parseCurrentParams 一致）
        contextTimeout = 5
    )
    public String handle(CommandContext context) {
        String city = null;

        // 优先从 filledParams 中读取 raw_input（支持上下文感知）
        if (context.isContextSupported() && context.hasFilledParam("raw_input")) {
            Object cityObj = context.getFilledParam("raw_input");
            city = cityObj != null ? String.valueOf(cityObj) : null;
            log.info("从上下文获取城市: {} (原始类型: {})", city, cityObj != null ? cityObj.getClass().getName() : "null");
        }

        // 如果上下文没有，从 args 解析（第一次输入）
        if (city == null || city.isEmpty()) {
            String inputCity = context.getArgs().trim();
            if (!inputCity.isEmpty()) {
                city = inputCity;
                context.setFilledParam("raw_input", city);  // 保存到上下文
                log.info("用户输入城市: {}", city);
            }
        }

        if (city == null || city.isEmpty()) {
            return "❌ 用法：/weather 城市名\n例如：/weather 北京";
        }

        try {
            String result = weatherService.getFormattedWeather(city);
            return result + "\n\n💡 提示：5分钟内再次发送 /weather 会自动复用「" + city + "」";
        } catch (Exception e) {
            return "⚠️ 天气查询失败，请稍后再试";
        }
    }
}
