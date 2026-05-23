package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.dto.FeishuSender;
import com.example.intelligentxtsystem.service.WeatherService;
import org.springframework.stereotype.Component;

/**
 * 天气指令处理器
 * 指令格式：/weather 城市名 或 天气 城市名
 */
@Component
public class WeatherHandler implements CommandHandler {

    private final WeatherService weatherService;

    public WeatherHandler(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Override
    public boolean support(String text) {
        return text.startsWith("/weather") || text.startsWith("天气");
    }

    @Override
    public String handle(String text, FeishuSender sender, String chatId) {
        String city = text
                .replaceAll("^(/weather|天气)\\s*", "")
                .trim();

        if (city.isEmpty()) {
            return "❌ 用法：/weather 城市名\n例如：/weather 北京";
        }

        try {
            return weatherService.getFormattedWeather(city);
        } catch (Exception e) {
            return "⚠️ 天气查询失败，请稍后再试";
        }
    }
}
