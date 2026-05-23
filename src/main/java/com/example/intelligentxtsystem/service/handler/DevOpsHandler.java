package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.dto.FeishuSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DevOps 工具指令处理器
 * 指令格式：
 * /ping <host> - 检测服务器连通性
 * /uptime - 查看服务运行时间
 * /deploy <环境> - 模拟部署流程
 */
@Component
public class DevOpsHandler implements CommandHandler {

    private final long startTime = System.currentTimeMillis();

    @Value("${devops.ping-timeout-ms}")
    private int pingTimeoutMs;

    @Value("${devops.http-timeout-ms}")
    private int httpTimeoutMs;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // /ping 192.168.1.1 或 /ping http://example.com
    private static final Pattern PING_PATTERN =
            Pattern.compile("^(?:/ping|ping)\\s+([^\\s]+)");

    // /deploy prod
    private static final Pattern DEPLOY_PATTERN =
            Pattern.compile("^(?:/deploy|部署)\\s+(\\w+)");

    @Override
    public boolean support(String text) {
        return text.startsWith("/ping") || text.startsWith("/uptime") ||
               text.startsWith("/deploy") || text.startsWith("ping ") ||
               text.startsWith("部署 ");
    }

    @Override
    public String handle(String text, FeishuSender sender, String chatId) {
        // /uptime
        if (text.trim().equals("/uptime") || text.trim().equals("uptime")) {
            return handleUptime();
        }

        // /ping host
        Matcher pingMatcher = PING_PATTERN.matcher(text);
        if (pingMatcher.find()) {
            String host = pingMatcher.group(1);
            return handlePing(host);
        }

        // /deploy env
        Matcher deployMatcher = DEPLOY_PATTERN.matcher(text);
        if (deployMatcher.find()) {
            String env = deployMatcher.group(1);
            return handleDeploy(env, sender);
        }

        return """
                ❌ 用法：

                🔧 DevOps 工具
                ─────────────────────────
                /uptime        查看运行时间
                /ping <主机>   检测连通性
                /deploy <环境>  模拟部署流程

                💡 使用示例
                /uptime
                /ping baidu.com
                /ping http://example.com
                /deploy test
                """;
    }

    private String handleUptime() {
        long uptimeMs = System.currentTimeMillis() - startTime;
        long days = uptimeMs / (1000 * 60 * 60 * 24);
        long hours = (uptimeMs % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60);
        long minutes = (uptimeMs % (1000 * 60 * 60)) / (1000 * 60);
        long seconds = (uptimeMs % (1000 * 60)) / 1000;

        String now = LocalDateTime.now(ZONE).format(FORMATTER);

        return String.format("""
                📊 系统状态

                🕐 当前时间：%s
                ⏱️  运行时间：%d天 %d小时 %d分钟 %d秒
                ✅ 状态：正常运行
                """, now, days, hours, minutes, seconds);
    }

    private String handlePing(String host) {
        if (host == null || host.isEmpty()) {
            return "❌ 请指定主机地址";
        }

        StringBuilder result = new StringBuilder();
        result.append(String.format("🔍 Ping 检测\n\n🎯 目标：%s\n\n", host));

        // 1. ICMP Ping（域名/IP 连通性）
        try {
            String cleanHost = host.replaceAll("^https?://", "").replaceAll("/.*$", "");
            long start = System.currentTimeMillis();
            boolean reachable = InetAddress.getByName(cleanHost).isReachable(pingTimeoutMs);
            long pingTime = System.currentTimeMillis() - start;

            if (reachable) {
                result.append(String.format("📡 ICMP：✅ 可达（%dms）\n", pingTime));
            } else {
                result.append("📡 ICMP：❌ 不可达\n");
            }
        } catch (Exception e) {
            result.append("📡 ICMP：❌ 解析失败（").append(e.getMessage()).append("）\n");
        }

        // 2. HTTP 检测（如果输入的是 URL 或域名）
        if (host.startsWith("http://") || host.startsWith("https://") || !host.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
            try {
                String url = host.startsWith("http") ? host : "https://" + host;
                long start = System.currentTimeMillis();
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(httpTimeoutMs);
                conn.setReadTimeout(httpTimeoutMs);
                // 只获取响应码，不读body
                int code = conn.getResponseCode();
                long httpTime = System.currentTimeMillis() - start;
                conn.disconnect();

                result.append(String.format("🌐 HTTP：✅ 状态码 %d（%dms）\n", code, httpTime));
            } catch (Exception e) {
                result.append("🌐 HTTP：❌ 连接失败（").append(e.getMessage()).append("）\n");
            }
        }

        result.append("\n🕐 检测时间：").append(LocalDateTime.now(ZONE).format(FORMATTER));
        return result.toString();
    }

    private String handleDeploy(String env, FeishuSender sender) {
        String normalizedEnv = env.toLowerCase();

        if (!normalizedEnv.matches("^(dev|test|staging|prod|production)$")) {
            return """
                    ❌ 部署环境无效

                    可用环境：
                    • dev - 开发环境
                    • test - 测试环境
                    • staging - 预发布环境
                    • prod / production - 生产环境
                    """;
        }

        boolean isProd = normalizedEnv.equals("prod") || normalizedEnv.equals("production");
        String operator = sender != null ? sender.getOpenId() : "系统";
        String now = LocalDateTime.now(ZONE).format(FORMATTER);

        if (isProd) {
            return String.format("""
                    ⚠️ 生产环境部署确认

                    📦 环境：production
                    👤 操作者：%s
                    🕐 时间：%s

                    ❗ 生产环境部署需要额外确认！
                    当前为模拟模式，未执行实际部署操作。

                    如需接入真实部署，请配置 CI/CD 平台（如 Jenkins、GitHub Actions）
                    """, operator, now);
        }

        String envLabel = switch (normalizedEnv) {
            case "dev" -> "开发环境";
            case "test" -> "测试环境";
            case "staging" -> "预发布环境";
            default -> normalizedEnv;
        };

        return String.format("""
                🚀 模拟部署流程

                📦 环境：%s（%s）
                👤 操作者：%s
                🕐 时间：%s

                📋 模拟步骤：
                1. 代码拉取 ✓
                2. 构建打包 ✓
                3. 部署服务 ✓
                4. 健康检查 ✓

                ✅ 模拟部署完成

                💡 当前为模拟模式，未执行实际部署操作。
                如需接入真实部署，请配置 CI/CD 平台。
                """, normalizedEnv, envLabel, operator, now);
    }
}
