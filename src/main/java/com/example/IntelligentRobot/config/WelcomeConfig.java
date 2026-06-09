package com.example.IntelligentRobot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 新成员入群欢迎配置
 *
 * 配置前缀：welcome
 * 对应 application.yml 中的 welcome: 配置段
 */
@Component
@RefreshScope
@ConfigurationProperties(prefix = "welcome")
public class WelcomeConfig {

    /**
     * 是否启用入群欢迎功能
     */
    private boolean enabled = true;

    /**
     * 欢迎消息模板
     * 支持占位符：
     *   {{name}}  - 成员姓名（纯文本，无法 @ 到用户）
     *   {{count}} - 本次入群人数
     *   {{chat}}  - 群名称
     */
    private String template = "👋 欢迎 {{name}} 加入本群！\n\n"
            + "我是飞书智能机器人，可以帮助你提升研发效率。\n"
            + "请@我，并输入 /help 查看我能为您提供的服务。";

    /**
     * 批量入群时，合并发送的最小人数阈值
     * 当一次入群人数 >= 此值时，合并为一条消息
     */
    private int batchThreshold = 2;

    /**
     * 批量欢迎消息模板（仅当人数 >= batchThreshold 时使用）
     * 支持占位符：
     *   {{names}} - 成员姓名列表（逗号分隔）
     *   {{count}} - 入群人数
     *   {{chat}}  - 群名称
     */
    private String batchTemplate = "👋 欢迎新成员加入本群：{{names}}\n\n"
            + "我是飞书智能机器人，可以帮助你提升研发效率。\n"
            + "请@我，并输入 /help 查看我能为您提供的服务。";

    /**
     * 防刷屏：同一群聊两次欢迎消息的最小间隔
     * application.yml 中配置为毫秒长整数（如 30000）
     */
    private long cooldown = 30 * 1000L;

    // Getters and Setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public int getBatchThreshold() {
        return batchThreshold;
    }

    public void setBatchThreshold(int batchThreshold) {
        this.batchThreshold = batchThreshold;
    }

    public String getBatchTemplate() {
        return batchTemplate;
    }

    public void setBatchTemplate(String batchTemplate) {
        this.batchTemplate = batchTemplate;
    }

    public long getCooldown() {
        return cooldown;
    }

    public void setCooldown(long cooldown) {
        this.cooldown = cooldown;
    }
}
