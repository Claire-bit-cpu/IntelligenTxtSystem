/*
 * 服务监控客户端（Prometheus/Grafana）
 * 支持：查询 Prometheus 指标、Grafana 仪表盘链接
 */
package com.example.intelligentxtsystem.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Component
public class MonitorClient {

    private static final Logger log = LoggerFactory.getLogger(MonitorClient.class);

    @Value("${prometheus.url:http://localhost:9090}")
    private String prometheusUrl;

    @Value("${prometheus.enabled:false}")
    private boolean prometheusEnabled;

    @Value("${grafana.url:http://localhost:3000}")
    private String grafanaUrl;

    @Value("${grafana.api-key:}")
    private String grafanaApiKey;

    @Value("${grafana.enabled:false}")
    private boolean grafanaEnabled;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public MonitorClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询 Prometheus 指标
     * @param query PromQL 查询语句
     * @param time 查询时间（可选，默认当前时间）
     */
    public String queryPrometheus(String query, String time) {
        if (!prometheusEnabled) {
            return "⚠️ Prometheus 集成未启用，请配置 prometheus.enabled=true";
        }

        try {
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append(String.format("%s/api/v1/query?query=%s",
                    prometheusUrl, java.net.URLEncoder.encode(query, "UTF-8")));

            if (time != null && !time.isEmpty()) {
                urlBuilder.append("&time=").append(time);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    urlBuilder.toString(), HttpMethod.GET, entity, String.class);

            JsonNode result = objectMapper.readTree(response.getBody());
            String status = result.get("status").asText();

            if (!"success".equals(status)) {
                return "❌ 查询失败: " + result.get("error").asText();
            }

            JsonNode data = result.get("data");
            JsonNode results = data.get("result");

            if (results.size() == 0) {
                return "⚠️ 未找到匹配的指标数据";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("📊 Prometheus 查询结果\n\n查询: %s\n\n", query));

            for (JsonNode item : results) {
                JsonNode metric = item.get("metric");
                JsonNode value = item.get("value");

                sb.append("📌 指标:\n");
                metric.fields().forEachRemaining(entry -> {
                    if (!"__name__".equals(entry.getKey())) {
                        sb.append(String.format("  %s: %s\n", entry.getKey(), entry.getValue().asText()));
                    }
                });

                if (value != null && value.size() >= 2) {
                    sb.append(String.format("  值: %s\n", value.get(1).asText()));
                }
                sb.append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("Prometheus 查询失败: query={}", query, e);
            return "❌ 查询失败: " + e.getMessage();
        }
    }

    /**
     * 获取服务健康状态
     * 通过 Prometheus 查询 up 指标
     * @param serviceName 服务名称（Prometheus job 名称）
     */
    public String getServiceHealth(String serviceName) {
        if (!prometheusEnabled) {
            return "⚠️ Prometheus 集成未启用";
        }

        String query = String.format("up{job=\"%s\"}", serviceName);
        return queryPrometheus(query, null);
    }

    /**
     * 获取服务错误率（最近 5 分钟）
     * 假设有 http_requests_total 指标
     * @param serviceName 服务名称
     */
    public String getErrorRate(String serviceName) {
        if (!prometheusEnabled) {
            return "⚠️ Prometheus 集成未启用";
        }

        // 计算最近 5 分钟的错误率
        // 错误率 = (5xx 请求数 / 总请求数) * 100
        String query = String.format(
                "sum(rate(http_requests_total{job=\"%s\",status=~\"5..\"}[5m])) / " +
                "sum(rate(http_requests_total{job=\"%s\"}[5m])) * 100",
                serviceName, serviceName);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            String url = String.format("%s/api/v1/query?query=%s",
                    prometheusUrl, java.net.URLEncoder.encode(query, "UTF-8"));

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            JsonNode result = objectMapper.readTree(response.getBody());

            if (!"success".equals(result.get("status").asText())) {
                return "❌ 查询错误率失败";
            }

            JsonNode results = result.get("data").get("result");

            if (results.size() == 0) {
                return String.format(
                        "📊 服务错误率监控\n\n服务: %s\n\n⚠️ 未找到该服务的指标数据\n\n" +
                        "💡 提示：请确保 Prometheus 中有 http_requests_total 指标",
                        serviceName);
            }

            double errorRate = results.get(0).get("value").get(1).asDouble();

            String statusEmoji = errorRate < 1 ? "✅" : (errorRate < 5 ? "⚠️" : "❌");
            String statusText = errorRate < 1 ? "正常" : (errorRate < 5 ? "警告" : "异常");

            return String.format(
                    "📊 服务错误率监控\n\n" +
                    "🔧 服务: %s\n" +
                    "%s 状态: %s\n" +
                    "📈 错误率: %.2f%%\n" +
                    "⏱ 统计窗口: 最近 5 分钟\n\n" +
                    "🔗 Grafana: %s/dashboard",
                    serviceName, statusEmoji, statusText, errorRate, grafanaUrl);
        } catch (Exception e) {
            log.error("查询错误率失败", e);
            return "❌ 查询失败: " + e.getMessage();
        }
    }

    /**
     * 获取服务关键指标汇总
     * @param serviceName 服务名称
     */
    public String getServiceMetrics(String serviceName) {
        if (!prometheusEnabled) {
            return "⚠️ Prometheus 集成未启用";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 服务监控汇总\n\n🔧 服务: %s\n\n", serviceName));

        // 1. 服务健康状态
        try {
            String healthQuery = String.format("up{job=\"%s\"}", serviceName);
            String url = String.format("%s/api/v1/query?query=%s",
                    prometheusUrl, java.net.URLEncoder.encode(healthQuery, "UTF-8"));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            JsonNode result = objectMapper.readTree(response.getBody());

            if ("success".equals(result.get("status").asText())) {
                JsonNode results = result.get("data").get("result");
                if (results.size() > 0) {
                    int up = results.get(0).get("value").get(1).asInt();
                    sb.append(String.format("💚 健康状态: %s\n", up == 1 ? "正常" : "异常"));
                } else {
                    sb.append("💚 健康状态: ⚠️ 未找到实例\n");
                }
            }
        } catch (Exception e) {
            sb.append("💚 健康状态: ❌ 查询失败\n");
        }

        // 2. 请求率（QPS）
        try {
            String qpsQuery = String.format("sum(rate(http_requests_total{job=\"%s\"}[5m]))", serviceName);
            String url = String.format("%s/api/v1/query?query=%s",
                    prometheusUrl, java.net.URLEncoder.encode(qpsQuery, "UTF-8"));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            JsonNode result = objectMapper.readTree(response.getBody());

            if ("success".equals(result.get("status").asText())) {
                JsonNode results = result.get("data").get("result");
                if (results.size() > 0) {
                    double qps = results.get(0).get("value").get(1).asDouble();
                    sb.append(String.format("⚡ 请求速率: %.2f QPS\n", qps));
                }
            }
        } catch (Exception e) {
            sb.append("⚡ 请求速率: ⚠️ 无数据\n");
        }

        // 3. 错误率
        String errorRateResult = getErrorRate(serviceName);
        // 从错误率结果中提取数值
        try {
            String errorQuery = String.format(
                    "sum(rate(http_requests_total{job=\"%s\",status=~\"5..\"}[5m])) / " +
                    "sum(rate(http_requests_total{job=\"%s\"}[5m])) * 100",
                    serviceName, serviceName);
            String url = String.format("%s/api/v1/query?query=%s",
                    prometheusUrl, java.net.URLEncoder.encode(errorQuery, "UTF-8"));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            JsonNode result = objectMapper.readTree(response.getBody());

            if ("success".equals(result.get("status").asText())) {
                JsonNode results = result.get("data").get("result");
                if (results.size() > 0) {
                    double errorRate = results.get(0).get("value").get(1).asDouble();
                    sb.append(String.format("📈 错误率: %.2f%%\n", errorRate));
                } else {
                    sb.append("📈 错误率: 0.00%% (无错误)\n");
                }
            }
        } catch (Exception e) {
            sb.append("📈 错误率: ⚠️ 无数据\n");
        }

        // 4. 响应时间 P95
        try {
            String latencyQuery = String.format(
                    "histogram_quantile(0.95, sum(rate(http_request_duration_seconds_bucket{job=\"%s\"}[5m])) by (le))",
                    serviceName);
            String url = String.format("%s/api/v1/query?query=%s",
                    prometheusUrl, java.net.URLEncoder.encode(latencyQuery, "UTF-8"));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            JsonNode result = objectMapper.readTree(response.getBody());

            if ("success".equals(result.get("status").asText())) {
                JsonNode results = result.get("data").get("result");
                if (results.size() > 0) {
                    double p95 = results.get(0).get("value").get(1).asDouble() * 1000; // 转为毫秒
                    sb.append(String.format("⏱ 响应时间 P95: %.2f ms\n", p95));
                }
            }
        } catch (Exception e) {
            sb.append("⏱ 响应时间 P95: ⚠️ 无数据\n");
        }

        sb.append(String.format("\n🔗 监控面板: %s/dashboard\n", grafanaUrl));
        sb.append(String.format("🔗 Prometheus: %s\n", prometheusUrl));

        return sb.toString();
    }

    /**
     * 查询 Grafana 仪表盘
     */
    public String searchGrafanaDashboards(String query) {
        if (!grafanaEnabled) {
            return "⚠️ Grafana 集成未启用";
        }

        try {
            String url = String.format("%s/api/search?query=%s&type=dash-db",
                    grafanaUrl, java.net.URLEncoder.encode(query, "UTF-8"));

            HttpHeaders headers = buildGrafanaHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            JsonNode results = objectMapper.readTree(response.getBody());

            if (results.size() == 0) {
                return "⚠️ 未找到匹配的仪表盘";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📊 Grafana 仪表盘\n\n");

            for (JsonNode dashboard : results) {
                String title = dashboard.get("title").asText();
                String uid = dashboard.get("uid").asText();
                sb.append(String.format("• %s\n  🔗 %s/d/%s\n\n", title, grafanaUrl, uid));
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("Grafana 查询失败", e);
            return "❌ 查询失败: " + e.getMessage();
        }
    }

    private HttpHeaders buildGrafanaHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (grafanaApiKey != null && !grafanaApiKey.isEmpty()) {
            headers.set("Authorization", "Bearer " + grafanaApiKey);
        }

        return headers;
    }

    public boolean isPrometheusEnabled() {
        return prometheusEnabled;
    }

    public boolean isGrafanaEnabled() {
        return grafanaEnabled;
    }
}
