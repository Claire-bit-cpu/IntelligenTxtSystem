package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.MonitorClient;
import com.example.IntelligentRobot.dto.CommandContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 监控指令处理器（新框架版本）
 * 指令格式：/monitor [服务名]
 */
@Component
public class MonitorCommandHandler {

    @Autowired(required = false)
    private MonitorClient monitorClient;

    /** 默认服务名，对应 Prometheus 的 job_name */
    @Value("${monitor.default-service:${spring.application.name:IntelligentRobot}}")
    private String defaultService;

    @Command(
        name = "monitor",
        description = "查询服务健康状态",
        usage = "/monitor [服务名]"
    )
    public String handle(CommandContext context) {
        String service = context.getArgs().trim();

        // 如果用户没有输入服务名，使用默认服务名
        if (service.isEmpty()) {
            service = defaultService;
        }

        if (monitorClient == null) {
            return """
                    ⚠️ 监控集成未启用

                    请配置以下环境变量：
                    • prometheus.url - Prometheus 地址
                    • prometheus.enabled - 设置为 true
                    • grafana.url - Grafana 地址（可选）

                    💡 可获取服务健康状态、错误率、请求速率等指标
                    """;
        }

        if (!monitorClient.isPrometheusEnabled()) {
            return "⚠️ Prometheus 集成未启用，请配置 prometheus.enabled=true";
        }

        // 获取服务综合指标
        return monitorClient.getServiceMetrics(service);
    }
}
