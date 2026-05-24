package com.example.intelligentxtsystem.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 通知群聊配置服务（数据级配置）
 * 支持动态增删群聊、按业务类型绑定
 */
@Service
public class NotificationConfigService {

    private static final Logger log = LoggerFactory.getLogger(NotificationConfigService.class);

    @Value("${search.index-path:./search-index.db}")
    private String dbPath;

    private Connection conn;

    /**
     * 群聊配置记录
     */
    public record ChatConfig(
            Long id,
            String chatId,
            String chatName,
            String businessType,
            String description,
            Boolean enabled,
            LocalDateTime createdTime,
            LocalDateTime updatedTime
    ) {}

    @PostConstruct
    public void init() throws SQLException {
        String url = "jdbc:sqlite:" + dbPath;
        conn = DriverManager.getConnection(url);
        // 设置繁忙超时，避免并发操作时立即报 SQLITE_BUSY
        try (var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA busy_timeout=5000");
        }
        createTables();
        log.info("通知配置数据库初始化完成");
    }

    @PreDestroy
    public void destroy() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            log.warn("关闭通知配置数据库异常", e);
        }
    }

    private void createTables() throws SQLException {
        // 创建群聊配置表
        try (var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS notification_chat_config (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id TEXT NOT NULL UNIQUE,
                    chat_name TEXT,
                    business_type TEXT NOT NULL DEFAULT 'all',
                    description TEXT,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    created_time TEXT NOT NULL,
                    updated_time TEXT NOT NULL
                )
            """);
        }

        // 创建索引
        try (var stmt = conn.createStatement()) {
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_config_enabled ON notification_chat_config(enabled)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_config_business ON notification_chat_config(business_type)");
        }
    }

    /**
     * 添加群聊配置（人工调用，默认启用）
     * @return 添加成功返回配置ID，失败返回null
     */
    public Long addChatConfig(String chatId, String chatName, String businessType, String description) {
        return addChatConfig(chatId, chatName, businessType, description, true);
    }

    /**
     * 添加群聊配置（完整参数）
     * @param enabled 是否启用
     * @return 添加成功返回配置ID，失败返回null
     */
    public Long addChatConfig(String chatId, String chatName, String businessType, String description, boolean enabled) {
        String now = LocalDateTime.now().toString();
        String sql = """
            INSERT INTO notification_chat_config(chat_id, chat_name, business_type, description, enabled, created_time, updated_time)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, chatId);
            ps.setString(2, chatName);
            ps.setString(3, businessType != null ? businessType : "all");
            ps.setString(4, description);
            ps.setInt(5, enabled ? 1 : 0);
            ps.setString(6, now);
            ps.setString(7, now);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    Long id = rs.getLong(1);
                    log.info("添加群聊配置成功: id={}, chatId={}, chatName={}, businessType={}, enabled={}",
                            id, chatId, chatName, businessType, enabled);
                    return id;
                }
            }
        } catch (SQLException e) {
            if (e.getMessage().contains("UNIQUE")) {
                log.warn("群聊配置已存在: chatId={}", chatId);
            } else {
                log.error("添加群聊配置失败: chatId={}", chatId, e);
            }
        }
        return null;
    }

    /**
     * 从飞书事件自动注册群聊（默认禁用）
     * 如果群聊已存在，只更新群名称（如果提供了新的名称）
     * 
     * @param chatId 群聊ID（必须从飞书事件获取）
     * @param chatName 群聊名称（可选）
     * @return true表示新注册或已存在，false表示操作失败
     */
    public boolean registerChatFromEvent(String chatId, String chatName) {
        // 先检查是否已存在
        ChatConfig existing = getChatConfigByChatId(chatId);
        
        if (existing != null) {
            // 已存在：如果提供了新的群名称，则更新
            if (chatName != null && !chatName.isEmpty() && !chatName.equals(existing.chatName())) {
                updateChatConfig(existing.id(), chatName, null, null, null);
                log.info("更新群聊名称: chatId={}, oldName={}, newName={}", chatId, existing.chatName(), chatName);
            }
            log.debug("群聊已注册: chatId={}, enabled={}", chatId, existing.enabled());
            return true;
        }

        // 不存在：自动注册（默认禁用）
        Long id = addChatConfig(chatId, chatName, "all", "自动注册自飞书事件（默认禁用）", false);
        if (id != null) {
            log.info("自动注册新群聊: chatId={}, chatName={}, 默认禁用", chatId, chatName);
            return true;
        }
        
        log.error("自动注册群聊失败: chatId={}", chatId);
        return false;
    }

    /**
     * 更新群聊配置
     */
    public boolean updateChatConfig(Long id, String chatName, String businessType, String description, Boolean enabled) {
        StringBuilder sql = new StringBuilder("UPDATE notification_chat_config SET updated_time = ?");
        List<Object> params = new ArrayList<>();
        params.add(LocalDateTime.now().toString());

        if (chatName != null) {
            sql.append(", chat_name = ?");
            params.add(chatName);
        }
        if (businessType != null) {
            sql.append(", business_type = ?");
            params.add(businessType);
        }
        if (description != null) {
            sql.append(", description = ?");
            params.add(description);
        }
        if (enabled != null) {
            sql.append(", enabled = ?");
            params.add(enabled ? 1 : 0);
        }

        sql.append(" WHERE id = ?");
        params.add(id);

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("更新群聊配置成功: id={}", id);
                return true;
            } else {
                log.warn("群聊配置不存在: id={}", id);
                return false;
            }
        } catch (SQLException e) {
            log.error("更新群聊配置失败: id={}", id, e);
            return false;
        }
    }

    /**
     * 删除群聊配置
     */
    public boolean deleteChatConfig(Long id) {
        String sql = "DELETE FROM notification_chat_config WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("删除群聊配置成功: id={}", id);
                return true;
            } else {
                log.warn("群聊配置不存在: id={}", id);
                return false;
            }
        } catch (SQLException e) {
            log.error("删除群聊配置失败: id={}", id, e);
            return false;
        }
    }

    /**
     * 删除群聊配置（按chatId）
     */
    public boolean deleteChatConfigByChatId(String chatId) {
        String sql = "DELETE FROM notification_chat_config WHERE chat_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, chatId);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("删除群聊配置成功: chatId={}", chatId);
                return true;
            } else {
                log.warn("群聊配置不存在: chatId={}", chatId);
                return false;
            }
        } catch (SQLException e) {
            log.error("删除群聊配置失败: chatId={}", chatId, e);
            return false;
        }
    }

    /**
     * 启用群聊配置
     */
    public boolean enableChatConfig(Long id) {
        return updateChatConfig(id, null, null, null, true);
    }

    /**
     * 禁用群聊配置
     */
    public boolean disableChatConfig(Long id) {
        return updateChatConfig(id, null, null, null, false);
    }

    /**
     * 根据ID查询群聊配置
     */
    public ChatConfig getChatConfigById(Long id) {
        String sql = "SELECT * FROM notification_chat_config WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapToChatConfig(rs);
                }
            }
        } catch (SQLException e) {
            log.error("查询群聊配置失败: id={}", id, e);
        }
        return null;
    }

    /**
     * 根据chatId查询群聊配置
     */
    public ChatConfig getChatConfigByChatId(String chatId) {
        String sql = "SELECT * FROM notification_chat_config WHERE chat_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapToChatConfig(rs);
                }
            }
        } catch (SQLException e) {
            log.error("查询群聊配置失败: chatId={}", chatId, e);
        }
        return null;
    }

    /**
     * 查询所有群聊配置
     */
    public List<ChatConfig> getAllChatConfigs() {
        List<ChatConfig> results = new ArrayList<>();
        String sql = "SELECT * FROM notification_chat_config ORDER BY created_time DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapToChatConfig(rs));
            }
        } catch (SQLException e) {
            log.error("查询所有群聊配置失败", e);
        }
        return results;
    }

    /**
     * 查询启用的群聊配置
     */
    public List<ChatConfig> getEnabledChatConfigs() {
        List<ChatConfig> results = new ArrayList<>();
        String sql = "SELECT * FROM notification_chat_config WHERE enabled = 1 ORDER BY created_time DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapToChatConfig(rs));
            }
        } catch (SQLException e) {
            log.error("查询启用的群聊配置失败", e);
        }
        return results;
    }

    /**
     * 根据业务类型查询启用的群聊配置
     * @param businessType 业务类型，传入"all"会匹配所有类型
     */
    public List<ChatConfig> getEnabledChatConfigsByBusinessType(String businessType) {
        List<ChatConfig> results = new ArrayList<>();
        String sql = """
            SELECT * FROM notification_chat_config
            WHERE enabled = 1 AND (business_type = ? OR business_type = 'all')
            ORDER BY created_time DESC
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, businessType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapToChatConfig(rs));
                }
            }
        } catch (SQLException e) {
            log.error("查询业务类型群聊配置失败: businessType={}", businessType, e);
        }
        return results;
    }

    /**
     * 获取所有启用的群聊ID（用于发送通知）
     * @param businessType 业务类型
     */
    public List<String> getEnabledChatIds(String businessType) {
        List<String> chatIds = new ArrayList<>();
        List<ChatConfig> configs = getEnabledChatConfigsByBusinessType(businessType);
        for (ChatConfig config : configs) {
            chatIds.add(config.chatId());
        }
        return chatIds;
    }

    private ChatConfig mapToChatConfig(ResultSet rs) throws SQLException {
        return new ChatConfig(
                rs.getLong("id"),
                rs.getString("chat_id"),
                rs.getString("chat_name"),
                rs.getString("business_type"),
                rs.getString("description"),
                rs.getInt("enabled") == 1,
                parseDateTime(rs.getString("created_time")),
                parseDateTime(rs.getString("updated_time"))
        );
    }

    private LocalDateTime parseDateTime(String str) {
        if (str == null || str.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(str);
        } catch (Exception e) {
            return null;
        }
    }
}
