package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.annotation.Command;
import com.example.intelligentxtsystem.dto.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Ping 指令处理器（新框架版本）
 * 指令格式：/ping <主机>
 */
@Component
public class PingCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(PingCommandHandler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private static final Pattern PING_PATTERN = Pattern.compile("^(\\S+)");

    @Value("${devops.ping-timeout-ms:5000}")
    private int pingTimeoutMs;

    @Value("${devops.http-timeout-ms:10000}")
    private int httpTimeoutMs;

    @Command(
        name = "ping",
        description = "检测服务器连通性",
        usage = "/ping <主机地址>"
    )
    public String handle(CommandContext context) {
        String host = context.getArgs().trim();

        if (host.isEmpty()) {
            return "❌ 请指定主机地址\n用法：/ping <主机>\n示例：/ping baidu.com";
        }

        StringBuilder result = new StringBuilder();
        result.append(String.format("🔍 Ping 检测\n\n🎯 目标：%s\n\n", host));

        // 清理主机名（移除 http:// 前缀、路径和端口号）
        String cleanHost = host.replaceAll("^https?://", "").replaceAll("/.*$", "");
        // 去除端口号（用于 ICMP 和 HTTP 检测）
        cleanHost = cleanHost.replaceAll(":\\d+$", "");
        // 去除 IPv6 地址的方括号（保留接口标识 %15 等）
        cleanHost = cleanHost.replaceAll("^\\[(.+)\\]$", "$1");

        // 判断是否为 IP 地址（IPv4 或 IPv6）
        boolean isIpAddress = isIpAddress(cleanHost);

        // 1. ICMP Ping（域名/IP 连通性）
        try {
            long start = System.currentTimeMillis();
            InetAddress address = InetAddress.getByName(cleanHost);
            boolean reachable = address.isReachable(pingTimeoutMs);
            long pingTime = System.currentTimeMillis() - start;

            if (reachable) {
                result.append(String.format("📡 ICMP：✅ 可达（%dms）\n", pingTime));
            } else {
                result.append("📡 ICMP：❌ 不可达\n");
            }
        } catch (Exception e) {
            result.append("📡 ICMP：❌ 解析失败（").append(e.getMessage()).append("）\n");
        }

        // 2. TCP 端口检测（如果指定了端口，如 host:port）
        if (host.contains(":") && !isLikelyIPv6WithoutPort(cleanHost)) {
            String[] parts = host.replaceAll("^\\[", "").replaceAll("\\].*$", "").split(":", 2);
            if (parts.length == 2) {
                try {
                    int port = Integer.parseInt(parts[1].replaceAll(".*:", "").replaceAll("/.*$", ""));
                    String tcpHost = parts[0].replaceAll("^https?://", "");
                    boolean tcpReachable = testTcpPort(tcpHost, port);
                    result.append(String.format("🔌 TCP %d：%s\n", port, tcpReachable ? "✅ 端口开放" : "❌ 端口关闭"));
                } catch (NumberFormatException ignored) {
                    // 端口解析失败，跳过
                }
            }
        }

        // 3. HTTP 检测（仅当明确指定 http:// 或 https:// 时进行）
        if (host.startsWith("http://") || host.startsWith("https://")) {
            try {
                String url = host;

                long start = System.currentTimeMillis();
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(httpTimeoutMs);
                conn.setReadTimeout(httpTimeoutMs);
                int code = conn.getResponseCode();
                long httpTime = System.currentTimeMillis() - start;
                conn.disconnect();

                result.append(String.format("🌐 HTTP：✅ 状态码 %d（%dms）\n", code, httpTime));
            } catch (Exception e) {
                result.append("🌐 HTTP：❌ 连接失败（").append(e.getMessage()).append("）\n");
            }
        } else if (!isIpAddress) {
            // 不是 IP 地址，先尝试 HTTP，再尝试 HTTPS
            // 使用原始 host（保留端口），而不是剥掉端口的 cleanHost
            String hostForHttp = host.replaceAll("^https?://", "").replaceAll("/.*$", "");
            try {
                String url = "http://" + hostForHttp;
                long start = System.currentTimeMillis();
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(httpTimeoutMs);
                conn.setReadTimeout(httpTimeoutMs);
                int code = conn.getResponseCode();
                long httpTime = System.currentTimeMillis() - start;
                conn.disconnect();

                result.append(String.format("🌐 HTTP：✅ 状态码 %d（%dms）\n", code, httpTime));
            } catch (Exception e) {
                // HTTP 失败，尝试 HTTPS
                try {
                    String url = "https://" + hostForHttp;
                    long start = System.currentTimeMillis();
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(httpTimeoutMs);
                    conn.setReadTimeout(httpTimeoutMs);
                    int code = conn.getResponseCode();
                    long httpTime = System.currentTimeMillis() - start;
                    conn.disconnect();

                    result.append(String.format("🌐 HTTPS：✅ 状态码 %d（%dms）\n", code, httpTime));
                } catch (Exception e2) {
                    result.append("🌐 HTTP：❌ 连接失败（").append(e2.getMessage()).append("）\n");
                }
            }
        }

        result.append("\n🕐 检测时间：").append(LocalDateTime.now(ZONE).format(FORMATTER));
        return result.toString();
    }

    /**
     * 判断字符串是否为 IP 地址（IPv4 或 IPv6）
     */
    private boolean isIpAddress(String host) {
        String cleaned = host.replaceAll("%\\d+$", "").replaceAll(":\\d+$", "");
        if (cleaned.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
            return true;
        }
        if (cleaned.contains(":")) {
            try {
                InetAddress.getByName(cleaned);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 判断是否是 IPv6 地址（没有端口的情况）
     */
    private boolean isLikelyIPv6WithoutPort(String host) {
        return host.contains(":") && host.split(":").length >= 2;
    }

    /**
     * TCP 端口检测
     */
    private boolean testTcpPort(String host, int port) {
        try {
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress(host, port), pingTimeoutMs);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
