package com.example.intelligentxtsystem.service;

import com.example.intelligentxtsystem.client.FeishuClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 搜索索引定时同步调度器
 * - 启动后延迟1分钟执行首次全量同步
 * - 之后按配置间隔定时全量同步
 * - 支持手动触发同步
 */
@Component
@EnableScheduling
public class SearchSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SearchSyncScheduler.class);

    private final FeishuClient feishuClient;
    private final SearchIndexService indexService;
    private final ObjectMapper objectMapper;

    @Value("${search.max-sync-messages-per-chat:500}")
    private int maxMessagesPerChat;

    private final AtomicBoolean syncing = new AtomicBoolean(false);

    // 文件/文档类消息类型
    private static final Set<String> FILE_TYPES = Set.of(
            "file", "doc", "docx", "sheet", "bitable", "wiki", "slides"
    );

    public SearchSyncScheduler(FeishuClient feishuClient, SearchIndexService indexService, ObjectMapper objectMapper) {
        this.feishuClient = feishuClient;
        this.indexService = indexService;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动后延迟1分钟执行首次同步
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        new Thread(() -> {
            try {
                Thread.sleep(60000); // 等待应用完全就绪
                log.info("启动后首次同步开始...");
                fullSync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "search-sync-startup").start();
    }

    /**
     * 定时全量同步（默认每2小时）
     */
    @Scheduled(fixedDelayString = "${search.sync-interval-ms:7200000}")
    public void scheduledSync() {
        fullSync();
    }

    /**
     * 手动触发同步（异步执行）
     *
     * @return true=已开始同步，false=正在同步中
     */
    public boolean triggerSyncAsync() {
        if (!syncing.compareAndSet(false, true)) {
            return false; // 已在同步中
        }
        new Thread(() -> {
            try {
                fullSync();
            } finally {
                syncing.set(false);
            }
        }, "search-sync-manual").start();
        return true;
    }

    /**
     * 是否正在同步
     */
    public boolean isSyncing() {
        return syncing.get();
    }

    /**
     * 全量同步：飞书 API → SQLite FTS5
     */
    private void fullSync() {
        if (!syncing.compareAndSet(false, true)) {
            log.info("同步已在进行中，跳过");
            return;
        }

        try {
            log.info("开始全量同步...");
            long startTime = System.currentTimeMillis();

            List<SearchIndexService.IndexDoc> docs = new ArrayList<>();

            // 1. 同步群聊消息
            syncGroupMessages(docs);

            // 2. 同步知识库文档
            syncWikiDocuments(docs);

            // 3. 重建索引
            indexService.rebuildIndex(docs);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("全量同步完成，共 {} 条文档，耗时 {}ms", docs.size(), elapsed);
        } catch (Exception e) {
            log.error("全量同步失败", e);
        } finally {
            syncing.set(false);
        }
    }

    /**
     * 同步群聊消息：获取机器人所在群聊 → 逐群拉取消息 → 解析为 IndexDoc
     */
    @SuppressWarnings("unchecked")
    private void syncGroupMessages(List<SearchIndexService.IndexDoc> docs) {
        List<Map<String, Object>> chats = feishuClient.listBotChats();
        log.info("发现 {} 个群聊，开始同步消息", chats.size());

        for (Map<String, Object> chat : chats) {
            String chatId = (String) chat.get("chat_id");
            String chatName = (String) chat.getOrDefault("name", "");
            if (chatId == null) continue;

            try {
                List<Map<String, Object>> messages = feishuClient.fetchChatMessages(chatId, maxMessagesPerChat);
                log.debug("群聊[{}]获取到 {} 条消息", chatName, messages.size());

                for (Map<String, Object> item : messages) {
                    String msgType = (String) item.getOrDefault("msg_type", "");
                    String createTime = (String) item.getOrDefault("create_time", "");
                    String messageId = (String) item.getOrDefault("message_id", "");

                    // 解析消息体
                    Map<String, Object> contentMap = parseMessageContent(item);
                    String senderType = "";
                    Object senderObj = item.get("sender");
                    if (senderObj instanceof Map senderMap) {
                        senderType = (String) senderMap.getOrDefault("sender_type", "");
                    }

                    String title;
                    String content;
                    String source;
                    String extra;

                    if (FILE_TYPES.contains(msgType)) {
                        // 文件/文档类消息
                        String fileName = extractFileName(contentMap, msgType);
                        if (fileName == null) continue;
                        title = fileName;
                        content = fileName;
                        source = "group_file";
                        extra = String.format("{\"file_type\":\"%s\",\"sender_type\":\"%s\",\"chat_name\":\"%s\"}",
                                msgType, senderType, escapeJson(chatName));
                    } else if ("text".equals(msgType)) {
                        // 文本消息
                        String text = contentMap != null ? (String) contentMap.getOrDefault("text", "") : "";
                        text = cleanText(text);
                        if (text.isEmpty() || text.startsWith("/") || text.length() < 3) continue;
                        title = text.length() > 60 ? text.substring(0, 60) + "..." : text;
                        content = text;
                        source = "group_text";
                        extra = String.format("{\"sender_type\":\"%s\",\"chat_name\":\"%s\"}",
                                senderType, escapeJson(chatName));
                    } else {
                        // 其他类型暂不索引
                        continue;
                    }

                    String formattedTime = formatTimestamp(createTime);
                    docs.add(new SearchIndexService.IndexDoc(title, content, source, messageId, chatId, extra, formattedTime));
                }
            } catch (Exception e) {
                log.warn("同步群聊消息失败 chatId={}", chatId, e);
            }
        }
    }

    /**
     * 同步知识库文档
     */
    @SuppressWarnings("unchecked")
    private void syncWikiDocuments(List<SearchIndexService.IndexDoc> docs) {
        try {
            List<Map<String, Object>> wikiDocs = feishuClient.fetchAllWikiDocuments();
            log.info("获取到 {} 条知识库文档", wikiDocs.size());

            for (Map<String, Object> doc : wikiDocs) {
                String title = (String) doc.getOrDefault("title", "");
                String spaceName = (String) doc.getOrDefault("space_name", "");
                String nodeToken = (String) doc.getOrDefault("node_token", "");
                String objType = (String) doc.getOrDefault("obj_type", "");

                if (title.isEmpty()) continue;

                String extra = String.format("{\"space_name\":\"%s\",\"obj_type\":\"%s\",\"node_token\":\"%s\"}",
                        escapeJson(spaceName), objType, nodeToken);

                docs.add(new SearchIndexService.IndexDoc(title, title, "wiki", nodeToken, "", extra, ""));
            }
        } catch (Exception e) {
            log.warn("同步知识库文档失败", e);
        }
    }

    // ========== 解析辅助方法 ==========

    /**
     * 解析飞书消息体：body.content（嵌套 JSON 字符串）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMessageContent(Map<String, Object> item) {
        Object bodyObj = item.get("body");
        try {
            Map<String, Object> bodyMap = null;
            if (bodyObj instanceof String s && !s.isEmpty()) {
                bodyMap = objectMapper.readValue(s, Map.class);
            } else if (bodyObj instanceof Map m) {
                bodyMap = m;
            }
            if (bodyMap != null) {
                Object contentObj = bodyMap.get("content");
                if (contentObj instanceof String cs && !cs.isEmpty()) {
                    return objectMapper.readValue(cs, Map.class);
                } else if (contentObj instanceof Map cm) {
                    return cm;
                }
            }
        } catch (Exception e) {
            // 忽略解析失败
        }
        return null;
    }

    /**
     * 从消息内容中提取文件名
     */
    private String extractFileName(Map<String, Object> contentMap, String msgType) {
        if (contentMap == null) return null;
        return switch (msgType) {
            case "file" -> (String) contentMap.getOrDefault("file_name", null);
            case "doc", "docx", "sheet" -> {
                String t = (String) contentMap.getOrDefault("title", null);
                yield t != null ? t : (String) contentMap.getOrDefault("file_name", null);
            }
            case "wiki" -> {
                String t = (String) contentMap.getOrDefault("title", null);
                yield t != null ? t : "Wiki文档";
            }
            default -> (String) contentMap.getOrDefault("file_name",
                    contentMap.getOrDefault("title", null) instanceof String s ? s : null);
        };
    }

    /**
     * 清理文本消息（去除@提及、HTML标签等）
     */
    private String cleanText(String text) {
        if (text == null) return "";
        return text
                .replaceAll("@_user_\\d+\\s*", "")  // 去除 @提及
                .replaceAll("<[^>]+>", "")            // 去除 HTML 标签
                .replaceAll("&\\w+;", "")             // 去除 HTML 实体
                .trim();
    }

    /**
     * 格式化时间戳
     */
    private String formatTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) return "";
        try {
            long ts = Long.parseLong(timestamp);
            Instant instant = Instant.ofEpochMilli(ts);
            return ZonedDateTime.ofInstant(instant, ZoneId.of("Asia/Shanghai"))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (NumberFormatException e) {
            return timestamp;
        }
    }

    /**
     * JSON 字符串转义
     */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
