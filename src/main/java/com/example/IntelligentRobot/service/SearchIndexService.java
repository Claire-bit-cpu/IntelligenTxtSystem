package com.example.IntelligentRobot.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 嵌入式搜索引擎 - 基于 SQLite FTS5 (trigram 分词器)
 * 支持中文全文检索，毫秒级响应
 */
@Component
public class SearchIndexService {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexService.class);

    @Value("${search.index-path:./search-index.db}")
    private String indexPath;

    private Connection conn;

    /**
     * 索引文档记录
     */
    public record IndexDoc(
            String title,
            String content,
            String source,      // group_file / group_text / wiki
            String sourceId,    // message_id / node_token
            String chatId,
            String extra,       // JSON: file_type, space_name, node_token, sender_type 等
            String createdTime
    ) {}

    @PostConstruct
    public void init() throws SQLException {
        String url = "jdbc:sqlite:" + indexPath;
        conn = DriverManager.getConnection(url);
        // WAL 模式提升并发性能（必须关闭 Statement，否则 ResultSet 占用连接导致 SQLITE_BUSY）
        try (var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
        }
        // 设置繁忙超时，避免并发操作时立即报 SQLITE_BUSY
        try (var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA busy_timeout=5000");
        }
        createTables();
        log.info("搜索索引数据库初始化完成: {}, 已索引文档数: {}", indexPath, getDocumentCount());
    }

    @PreDestroy
    public void destroy() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            log.warn("关闭索引数据库异常", e);
        }
    }

    private void createTables() throws SQLException {
        // FTS5 虚拟表，使用 trigram 分词器支持中文
        // title/content 被索引，其余字段 UNINDEXED 仅存储
        try (var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS doc_fts USING fts5(
                    title, content,
                    source UNINDEXED,
                    source_id UNINDEXED,
                    chat_id UNINDEXED,
                    extra UNINDEXED,
                    created_time UNINDEXED,
                    tokenize='trigram'
                )
            """);
        }

        // 同步元信息表
        try (var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sync_meta (
                    key TEXT PRIMARY KEY,
                    value TEXT
                )
            """);
        }
    }

    /**
     * 全量重建索引（清空后重写）
     */
    public synchronized void rebuildIndex(List<IndexDoc> docs) {
        try {
            conn.setAutoCommit(false);
            try {
                // 清空旧索引
                conn.createStatement().execute("DELETE FROM doc_fts");

                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO doc_fts(title, content, source, source_id, chat_id, extra, created_time) VALUES (?,?,?,?,?,?,?)"
                );

                for (IndexDoc doc : docs) {
                    ps.setString(1, nullToEmpty(doc.title()));
                    ps.setString(2, nullToEmpty(doc.content()));
                    ps.setString(3, nullToEmpty(doc.source()));
                    ps.setString(4, nullToEmpty(doc.sourceId()));
                    ps.setString(5, nullToEmpty(doc.chatId()));
                    ps.setString(6, nullToEmpty(doc.extra()));
                    ps.setString(7, nullToEmpty(doc.createdTime()));
                    ps.addBatch();

                    // 每500条执行一次批次，防止内存溢出
                    if (docs.size() > 500 && docs.indexOf(doc) % 500 == 499) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();

                // 记录同步时间
                String now = java.time.LocalDateTime.now().toString();
                conn.createStatement().executeUpdate(
                        "INSERT OR REPLACE INTO sync_meta(key, value) VALUES('last_sync', '" + now + "')"
                );
                conn.createStatement().executeUpdate(
                        "INSERT OR REPLACE INTO sync_meta(key, value) VALUES('last_count', '" + docs.size() + "')"
                );

                conn.commit();
                log.info("索引重建完成，共 {} 条文档", docs.size());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("索引重建失败", e);
        }
    }

    /**
     * 全文搜索
     *
     * @param keyword 搜索关键词
     * @param limit   最大返回条数
     * @return 匹配的文档列表（按相关度排序）
     */
    public List<IndexDoc> search(String keyword, int limit) {
        List<IndexDoc> results = new ArrayList<>();
        if (keyword == null || keyword.isBlank()) return results;

        try {
            PreparedStatement ps;
            if (keyword.length() >= 3) {
                // FTS5 MATCH（trigram 分词，支持中文子串匹配）
                String escaped = keyword.replace("\"", "\"\"");
                ps = conn.prepareStatement(
                        "SELECT title, content, source, source_id, chat_id, extra, created_time " +
                                "FROM doc_fts WHERE doc_fts MATCH ? ORDER BY rank LIMIT ?"
                );
                ps.setString(1, "\"" + escaped + "\"");
                ps.setInt(2, limit);
            } else {
                // 短关键词（1-2字）降级为 LIKE 模糊匹配
                String like = "%" + keyword + "%";
                ps = conn.prepareStatement(
                        "SELECT title, content, source, source_id, chat_id, extra, created_time " +
                                "FROM doc_fts WHERE title LIKE ? OR content LIKE ? LIMIT ?"
                );
                ps.setString(1, like);
                ps.setString(2, like);
                ps.setInt(3, limit);
            }

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                results.add(new IndexDoc(
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getString("source"),
                        rs.getString("source_id"),
                        rs.getString("chat_id"),
                        rs.getString("extra"),
                        rs.getString("created_time")
                ));
            }
        } catch (SQLException e) {
            log.error("搜索失败: keyword={}", keyword, e);
        }
        return results;
    }

    /**
     * 获取已索引文档数
     */
    public int getDocumentCount() {
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT count(*) FROM doc_fts")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return -1;
        }
    }

    /**
     * 获取上次同步时间
     */
    public String getLastSyncTime() {
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT value FROM sync_meta WHERE key='last_sync'")) {
            return rs.next() ? rs.getString(1) : "从未同步";
        } catch (SQLException e) {
            return "未知";
        }
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
