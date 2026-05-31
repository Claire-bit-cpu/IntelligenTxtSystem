package com.example.IntelligentRobot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 天气查询服务
 */
@Service
public class WeatherService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${amap.key}")
    private String amapKey;

    public WeatherService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取原始 JSON 响应
     */
    public String getWeather(String city) {
        String url = "https://restapi.amap.com/v3/weather/weatherInfo"
                + "?city=" + city
                + "&key=" + amapKey
                + "&extensions=base";

        return restTemplate.getForObject(url, String.class);
    }

    /**
     * 获取格式化的天气信息
     */
    public String getFormattedWeather(String city) {
        try {
            String json = getWeather(city);
            JsonNode root = objectMapper.readTree(json);

            String status = root.path("status").asText();
            if (!"1".equals(status)) {
                return "⚠️ 城市不存在或天气服务暂时不可用";
            }

            JsonNode lives = root.path("lives");
            if (lives.isArray() && lives.size() > 0) {
                JsonNode weather = lives.get(0);

                String province = weather.path("province").asText();
                String cityName = weather.path("city").asText();
                String weatherType = weather.path("weather").asText();
                String temperature = weather.path("temperature").asText();
                String wind = weather.path("winddirection").asText();
                String windSpeed = weather.path("windpower").asText();
                String humidity = weather.path("humidity").asText();

                // 天气图标映射
                String emoji = getWeatherEmoji(weatherType);

                return String.format("""
                        %s %s 天气

                        🏙️ 城市：%s %s
                        🌡️ 温度：%s°C
                        🌤️ 天气：%s
                        💨 风力：%s %s级
                        💧 湿度：%s%%

                        ⏰ 更新时间：%s
                        """,
                        emoji, cityName,
                        province, cityName,
                        temperature,
                        weatherType,
                        wind, windSpeed,
                        humidity,
                        weather.path("reporttime").asText()
                );
            }

            return "⚠️ 未获取到天气数据";

        } catch (Exception e) {
            return "⚠️ 天气查询失败，请稍后再试";
        }
    }

    /**
     * 根据天气类型返回表情图标
     */
    private String getWeatherEmoji(String weatherType) {
        return switch (weatherType) {
            case "晴" -> "☀️";
            case "多云" -> "⛅";
            case "阴" -> "☁️";
            case "小雨", "中雨", "大雨", "暴雨" -> "🌧️";
            case "小雪", "中雪", "大雪", "暴雪" -> "❄️";
            case "雾", "霾" -> "🌫️";
            case "雷阵雨" -> "⛈️";
            case "阵雨" -> "🌦️";
            default -> "🌤️";
        };
    }
}
