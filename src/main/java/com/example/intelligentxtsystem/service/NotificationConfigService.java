package com.example.intelligentxtsystem.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 通知配置服务
 * 管理需要接收通知的群聊列表，数据存储在 SQLite 中
 */
@Service
public class NotificationConfigService {

    private static final Logger log = LoggerFactory.getLogger(NotificationConfigService.class);

    @Value("${notification.db-path:./notification.db}")
    private String dbPath;

    private static final String TABLE_NAME = "notification_config";

    @PostConstruct
    public void init() {
        try {
            // 确保数据库目录存在
            Path path = Paths.get(dbPath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            // 创建表
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        chat_id TEXT NOT NULL UNIQUE,
                        chat_name TEXT,
                        description TEXT,
                        enabled INTEGER DEFAULT 1,
                        created_at TEXT DEFAULT (datetime('now', 'localtime')),
                        updated_at TEXT DEFAULT (datetime('now', 'localtime'))
                    )
                    """.formatted(TABLE_NAME));
                log.info("通知配置数据库初始化完成: {}", dbPath);
            }
        } catch (Exception e) {
            log.error("通知配置数据库初始化失败", e);
        }
    }

    private Connection getConnection() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    /**
     * 添加通知群聊
     */
    public void addChat(String chatId, String chatName, String description) {
        String sql = "INSERT OR REPLACE INTO " + TABLE_NAME +
                " (chat_id, chat_name, description, enabled, updated_at) VALUES (?, ?, ?, 1, datetime('now', 'localtime'))";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, chatId);
            pstmt.setString(2, chatName);
            pstmt.setString(3, description);
            pstmt.executeUpdate();
            log.info("添加通知群聊: {}", chatId);
        } catch (Exception e) {
            log.error("添加通知群聊失败: {}", chatId, e);
            throw new RuntimeException("添加通知群聊失败", e);
        }
    }

    /**
     * 移除通知群聊
     */
    public void removeChat(String chatId) {
        String sql = "DELETE FROM " + TABLE_NAME + " WHERE chat_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, chatId);
            pstmt.executeUpdate();
            log.info("移除通知群聊: {}", chatId);
        } catch (Exception e) {
            log.error("移除通知群聊失败: {}", chatId, e);
            throw new RuntimeException("移除通知群聊失败", e);
        }
    }

    /**
     * 启用/禁用通知群聊
     */
    public void setEnabled(String chatId, boolean enabled) {
        String sql = "UPDATE " + TABLE_NAME + " SET enabled = ?, updated_at = datetime('now', 'localtime') WHERE chat_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, enabled ? 1 : 0);
            pstmt.setString(2, chatId);
            pstmt.executeUpdate();
            log.info("设置通知群聊状态: {}={}", chatId, enabled);
        } catch (Exception e) {
            log.error("设置通知群聊状态失败: {}", chatId, e);
            throw new RuntimeException("设置状态失败", e);
        }
    }

    /**
     * 获取所有启用的通知群聊ID列表
     */
    public List<String> getEnabledChatIds() {
        List<String> chatIds = new ArrayList<>();
        String sql = "SELECT chat_id FROM " + TABLE_NAME + " WHERE enabled = 1";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                chatIds.add(rs.getString("chat_id"));
            }
        } catch (Exception e) {
            log.error("查询启用的通知群聊失败", e);
        }
        return chatIds;
    }

    /**
     * 获取所有通知群聊配置
     */
    public List<NotificationConfig> getAllConfigs() {
        List<NotificationConfig> configs = new ArrayList<>();
        String sql = "SELECT * FROM " + TABLE_NAME + " ORDER BY created_at DESC";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                NotificationConfig config = new NotificationConfig();
                config.setId(rs.getLong("id"));
                config.setChatId(rs.getString("chat_id"));
                config.setChatName(rs.getString("chat_name"));
                config.setDescription(rs.getString("description"));
                config.setEnabled(rs.getInt("enabled") == 1);
                config.setCreatedAt(rs.getString("created_at"));
                config.setUpdatedAt(rs.getString("updated_at"));
                configs.add(config);
            }
        } catch (Exception e) {
            log.error("查询通知群聊配置失败", e);
        }
        return configs;
    }

    /**
     * 通知配置实体类
     */
    public static class NotificationConfig {
        private Long id;
        private String chatId;
        private String chatName;
        private String description;
        private boolean enabled;
        private String createdAt;
        private String updatedAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getChatId() { return chatId; }
        public void setChatId(String chatId) { this.chatId = chatId; }

        public String getChatName() { return chatName; }
        public void setChatName(String chatName) { this.chatName = chatName; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    }

    /**
     * ChatConfig 是 NotificationConfig 的别名，保持 API 兼容
     */
    public static class ChatConfig extends NotificationConfig {
    }

    /**
     * 获取所有群聊配置（ChatConfig 形式，用于 API 兼容）
     */
    public List<ChatConfig> getAllChatConfigs() {
        List<NotificationConfig> configs = getAllConfigs();
        List<ChatConfig> result = new ArrayList<>();
        for (NotificationConfig c : configs) {
            ChatConfig cc = new ChatConfig();
            cc.setId(c.getId());
            cc.setChatId(c.getChatId());
            cc.setChatName(c.getChatName());
            cc.setDescription(c.getDescription());
            cc.setEnabled(c.isEnabled());
            cc.setCreatedAt(c.getCreatedAt());
            cc.setUpdatedAt(c.getUpdatedAt());
            result.add(cc);
        }
        return result;
    }

    /**
     * 获取所有启用的群聊配置（ChatConfig 形式）
     */
    public List<ChatConfig> getEnabledChatConfigs() {
        List<ChatConfig> all = getAllChatConfigs();
        List<ChatConfig> result = new ArrayList<>();
        for (ChatConfig c : all) {
            if (c.isEnabled()) {
                result.add(c);
            }
        }
        return result;
    }

    /**
     * 启用群聊配置（按 ID）
     */
    public boolean enableChatConfig(Long id) {
        try {
            String sql = "UPDATE " + TABLE_NAME + " SET enabled = 1, updated_at = datetime('now', 'localtime') WHERE id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, id);
                int rows = pstmt.executeUpdate();
                if (rows > 0) {
                    log.info("启用群聊配置: id={}", id);
                    return true;
                }
                return false;
            }
        } catch (Exception e) {
            log.error("启用群聊配置失败: id={}", id, e);
            return false;
        }
    }

    /**
     * 禁用群聊配置（按 ID）
     */
    public boolean disableChatConfig(Long id) {
        try {
            String sql = "UPDATE " + TABLE_NAME + " SET enabled = 0, updated_at = datetime('now', 'localtime') WHERE id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, id);
                int rows = pstmt.executeUpdate();
                if (rows > 0) {
                    log.info("禁用群聊配置: id={}", id);
                    return true;
                }
                return false;
            }
        } catch (Exception e) {
            log.error("禁用群聊配置失败: id={}", id, e);
            return false;
        }
    }

    /**
     * 删除群聊配置（按 ID）
     */
    public boolean deleteChatConfig(Long id) {
        try {
            String sql = "DELETE FROM " + TABLE_NAME + " WHERE id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, id);
                int rows = pstmt.executeUpdate();
                if (rows > 0) {
                    log.info("删除群聊配置: id={}", id);
                    return true;
                }
                return false;
            }
        } catch (Exception e) {
            log.error("删除群聊配置失败: id={}", id, e);
            return false;
        }
    }
}
