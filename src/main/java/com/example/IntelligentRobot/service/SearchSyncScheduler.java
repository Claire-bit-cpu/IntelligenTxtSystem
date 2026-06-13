package com.example.IntelligentRobot.service;

import com.example.IntelligentRobot.client.FeishuClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.core.task.TaskExecutor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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

    @Value("${search.sync-chat-ids:}")
    private String syncChatIdsConfig;

    @Value("${search.sync-wiki-space-ids:}")
    private String syncWikiSpaceIdsConfig;

    @Value("${search.startup-delay-ms:60000}")
    private long startupDelayMs;

    @Value("${search.api-call-delay-ms:100}")
    private long apiCallDelayMs;

    private final AtomicBoolean syncing = new AtomicBoolean(false);

    private final NotificationService notificationService;

    private final TaskExecutor lowPriorityEventExecutor;

    public SearchSyncScheduler(FeishuClient feishuClient, SearchIndexService indexService, ObjectMapper objectMapper, NotificationService notificationService,
                             @Qualifier("lowPriorityEventExecutor") TaskExecutor lowPriorityEventExecutor) {
        this.feishuClient = feishuClient;
        this.indexService = indexService;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.lowPriorityEventExecutor = lowPriorityEventExecutor;
    }

    /**
     * 注入低优先级线程池（用于搜索同步等后台任务）
     */

    // 文件/文档类消息类型
    private static final Set<String> FILE_TYPES = Set.of(
            "file", "doc", "docx", "sheet", "bitable", "wiki", "slides"
    );

    /**
     * 启动后延迟1分钟执行首次同步（不发送通知）
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        lowPriorityEventExecutor.execute(() -> {
            try {
                Thread.sleep(startupDelayMs); // 等待应用完全就绪
                log.info("启动后首次同步开始（不发送通知）...");
                fullSyncWithoutNotification();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * 定时全量同步（默认每5小时）
     */
    @Scheduled(fixedDelayString = "${search.sync-interval-ms:18000000}")
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
        lowPriorityEventExecutor.execute(() -> {
            try {
                fullSync();
            } finally {
                syncing.set(false);
            }
        });
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

        boolean success = false;
        String errorMessage = null;
        int docCount = 0;
        long startTime = System.currentTimeMillis();

        try {
            log.info("开始全量同步...");

            List<SearchIndexService.IndexDoc> docs = new ArrayList<>();

            // 1. 同步群聊消息
            syncGroupMessages(docs);

            // 2. 同步知识库文档
            syncWikiDocuments(docs);

            // 3. 同步云文档
            syncDriveDocuments(docs);

            // 4. 重建索引
            indexService.rebuildIndex(docs);

            docCount = docs.size();
            success = true;

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("全量同步完成，共 {} 条文档，耗时 {}ms", docCount, elapsed);
        } catch (Exception e) {
            errorMessage = e.getMessage();
            log.error("全量同步失败", e);
        } finally {
            syncing.set(false);
        }

        // 同步完成后发送通知（只在有实际数据时才发送成功通知）
        long elapsed = System.currentTimeMillis() - startTime;
        if (success) {
            if (docCount > 0) {
                notificationService.sendNotification(
                        "✅ 搜索索引同步完成\n⏰ 耗时: " + elapsed + "ms\n📝 详情: 已索引 " + docCount + " 条数据"
                );
            } else {
                log.info("同步完成但未索引到任何数据，跳过通知发送");
            }
        } else {
            // 失败时也发送通知（用于排查问题）
            notificationService.sendNotification(
                    "❌ 搜索索引同步失败\n⏰ 耗时: " + elapsed + "ms\n📝 详情: " + (errorMessage != null ? errorMessage : "未知错误")
            );
        }
    }

    /**
     * 手动触发同步（异步执行，不发送通知）
     * 用于应用启动时的首次同步
     */
    public boolean triggerSyncAsyncWithoutNotification() {
        if (!syncing.compareAndSet(false, true)) {
            return false; // 已在同步中
        }
        lowPriorityEventExecutor.execute(() -> {
            try {
                fullSyncWithoutNotification();
            } finally {
                syncing.set(false);
            }
        });
        return true;
    }

    /**
     * 全量同步：飞书 API → SQLite FTS5（不发送通知）
     */
    private void fullSyncWithoutNotification() {
        if (!syncing.compareAndSet(false, true)) {
            log.info("同步已在进行中，跳过");
            return;
        }

        try {
            long startTime = System.currentTimeMillis();
            log.info("开始全量同步（不发送通知）...");

            List<SearchIndexService.IndexDoc> docs = new ArrayList<>();

            // 1. 同步群聊消息
            syncGroupMessages(docs);

            // 2. 同步知识库文档
            syncWikiDocuments(docs);

            // 3. 同步云文档
            syncDriveDocuments(docs);

            // 4. 重建索引
            indexService.rebuildIndex(docs);

            int docCount = docs.size();
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("全量同步完成（不发送通知），共 {} 条文档，耗时 {}ms", docCount, elapsed);
        } catch (Exception e) {
            log.error("全量同步失败（不发送通知）", e);
        } finally {
            syncing.set(false);
        }
    }

    /**
     * 同步群聊消息：支持自动发现模式（auto/*）或指定群聊ID
     * - 空配置：跳过群聊同步
     * - auto/*：自动同步机器人加入的所有群聊
     * - 具体ID列表：同步指定的群聊
     *
     * 同步内容：
     * 1. 群聊消息历史中的文件/文档消息
     * 2. 群文件仓库中的所有文件
     * 3. 普通文本消息（可选，默认开启）
     */
    @SuppressWarnings("unchecked")
    private void syncGroupMessages(List<SearchIndexService.IndexDoc> docs) {
        // 空值检查：如果配置为空或仅包含空白字符，跳过同步
        if (syncChatIdsConfig == null || syncChatIdsConfig.isBlank()) {
            log.info("未配置同步群聊（search.sync-chat-ids），跳过群聊同步");
            return;
        }

        Set<String> targetChatIds;

        // 判断是否为自动发现模式
        String config = syncChatIdsConfig.trim();
        boolean autoDiscover = "auto".equalsIgnoreCase(config) || "*".equals(config);

        if (autoDiscover) {
            // 自动发现：获取机器人所在的所有群聊
            log.debug("自动发现模式：获取机器人所在的所有群聊...");
            java.util.List<Map<String, Object>> chats = feishuClient.listBotChats();
            targetChatIds = chats.stream()
                    .map(chat -> (String) chat.get("chat_id"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            log.info("自动发现 {} 个群聊", targetChatIds.size());
        } else {
            // 手动配置模式：解析配置的群聊ID列表
            targetChatIds = parseConfigIds(syncChatIdsConfig);
            if (targetChatIds.isEmpty()) {
                log.info("未配置同步群聊（search.sync-chat-ids），跳过群聊同步");
                return;
            }
        }

        log.info("开始同步 {} 个群聊的消息", targetChatIds.size());

            for (String chatId : targetChatIds) {
                try {
                    int itemCount = 0;

                    // 1. 同步群聊消息历史中的文件/文档消息
                    List<Map<String, Object>> messages = feishuClient.fetchChatMessages(chatId, maxMessagesPerChat);
                    log.debug("群聊[{}]获取到 {} 条消息", chatId, messages.size());

                    String chatName = chatId; // 可扩展：从API获取群名

                    for (Map<String, Object> item : messages) {
                        String msgType = (String) item.getOrDefault("msg_type", "");
                        String createTime = (String) item.getOrDefault("create_time", "");
                        String messageId = (String) item.getOrDefault("message_id", "");

                        // 处理文件/文档类消息
                        if (FILE_TYPES.contains(msgType)) {
                            Map<String, Object> contentMap = parseMessageContent(item);
                            String fileName = extractFileName(contentMap, msgType);
                            if (fileName == null) continue;

                            String senderType = "";
                            Object senderObj = item.get("sender");
                            if (senderObj instanceof Map senderMap) {
                                senderType = (String) senderMap.getOrDefault("sender_type", "");
                            }

                            String extra = String.format("{\"file_type\":\"%s\",\"sender_type\":\"%s\",\"chat_name\":\"%s\"}",
                                    msgType, senderType, escapeJson(chatName));

                            String formattedTime = formatTimestamp(createTime);
                            docs.add(new SearchIndexService.IndexDoc(fileName, fileName, "group_file", messageId, chatId, extra, formattedTime));
                            itemCount++;
                        }

                    }

                    log.info("群聊[{}]同步完成，共索引 {} 条文件类内容（从消息历史）", chatId, itemCount);
                } catch (Exception e) {
                    log.warn("同步群聊失败 chatId={}", chatId, e);
                }
            }
    }

    /**
     * 同步知识库文档：支持自动发现模式（auto/*）或指定空间ID
     * - 空配置：跳过知识库同步
     * - auto/*：自动同步机器人可访问的所有知识库空间
     * - 具体ID列表：同步指定的知识库空间
     *
     * 改进：获取文档正文内容，而不仅仅是标题
     */
    @SuppressWarnings("unchecked")
    private void syncWikiDocuments(List<SearchIndexService.IndexDoc> docs) {
        // 空值检查：如果配置为空或仅包含空白字符，跳过同步
        if (syncWikiSpaceIdsConfig == null || syncWikiSpaceIdsConfig.isBlank()) {
            log.info("未配置同步知识库（search.sync-wiki-space-ids），跳过知识库同步");
            return;
        }

        Set<String> targetSpaceIds;

        // 判断是否为自动发现模式
        String config = syncWikiSpaceIdsConfig.trim();
        boolean autoDiscover = "auto".equalsIgnoreCase(config) || "*".equals(config);

        if (autoDiscover) {
            // 自动发现：获取机器人可访问的所有知识库空间
            log.debug("自动发现模式：获取机器人可访问的所有知识库空间...");
            java.util.List<Map<String, Object>> spaces = feishuClient.listWikiSpaces();
            targetSpaceIds = spaces.stream()
                    .map(space -> (String) space.get("space_id"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            log.info("自动发现 {} 个知识库空间", targetSpaceIds.size());
        } else {
            // 手动配置模式：解析配置的知识库空间ID列表
            targetSpaceIds = parseConfigIds(syncWikiSpaceIdsConfig);
            if (targetSpaceIds.isEmpty()) {
                log.info("未配置同步知识库（search.sync-wiki-space-ids），跳过知识库同步");
                return;
            }
        }

        log.info("开始同步 {} 个知识库空间", targetSpaceIds.size());

        // 飞书知识库 obj_type 非文档类型（只排除文件夹等容器节点）
        Set<String> excludeObjTypes = Set.of("folder");
        // 支持的文档类型（可获取正文内容）
        Set<String> supportedDocTypes = Set.of("doc", "docx", "sheet", "bitable", "mindnote");

        int docCount = 0;
        for (String spaceId : targetSpaceIds) {
            try {
                List<Map<String, Object>> nodes = feishuClient.fetchWikiNodesBySpaceId(spaceId);
                log.debug("知识库空间[{}]获取到 {} 个节点", spaceId, nodes.size());

                // 获取空间名称
                String spaceName = feishuClient.getWikiSpaceName(spaceId);

                for (Map<String, Object> node : nodes) {
                    String title = (String) node.getOrDefault("title", "");
                    String nodeToken = (String) node.getOrDefault("node_token", "");
                    String objType = (String) node.getOrDefault("obj_type", "");

                    if (title.isEmpty()) continue;
                    if (excludeObjTypes.contains(objType)) continue;

                    // 获取文档内容（如果是支持的文档类型）
                    String content = title; // 默认使用标题
                    if (supportedDocTypes.contains(objType) && nodeToken != null && !nodeToken.isEmpty()) {
                        log.debug("获取知识库文档内容: title={}, objType={}, nodeToken={}", title, objType, nodeToken);
                        String docContent = feishuClient.getWikiDocumentContent(objType, nodeToken);
                        if (docContent != null && !docContent.isEmpty()) {
                            content = docContent;
                            log.debug("成功获取文档内容，长度={}", docContent.length());
                        }
                    }

                    String extra = String.format("{\"space_name\":\"%s\",\"obj_type\":\"%s\",\"node_token\":\"%s\"}",
                            escapeJson(spaceName), objType, nodeToken != null ? nodeToken : "");

                    docs.add(new SearchIndexService.IndexDoc(title, content, "wiki", nodeToken != null ? nodeToken : title, "", extra, ""));
                    docCount++;

                    // 避免频繁调用 API，添加延迟
                    try {
                        Thread.sleep(apiCallDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (Exception e) {
                log.warn("同步知识库文档失败 spaceId={}", spaceId, e);
            }
        }
        log.info("知识库同步完成，共 {} 个文档", docCount);
    }

    /**
     * 同步云文档
     * 调用飞书 API：GET /drive/v1/files 获取云文档列表
     * 然后调用 GET /docx/v1/documents/{document_id} 获取文档内容
     */
    @SuppressWarnings("unchecked")
    private void syncDriveDocuments(List<SearchIndexService.IndexDoc> docs) {
        log.info("开始同步云文档...");

        try {
            java.util.List<Map<String, Object>> files = feishuClient.listDriveFiles();
            if (files == null || files.isEmpty()) {
                log.info("未找到云文档");
                return;
            }

            log.debug("获取到 {} 个云文档", files.size());

            int docCount = 0;
            for (Map<String, Object> file : files) {
                String fileName = (String) file.getOrDefault("name", "");
                String fileToken = (String) file.getOrDefault("token", "");
                String fileType = (String) file.getOrDefault("type", "");
                String fileId = (String) file.getOrDefault("file_id", "");

                if (fileName.isEmpty()) continue;

                // 只处理文档类型（doc/docx）
                if (!"doc".equals(fileType) && !"docx".equals(fileType) && !"sheet".equals(fileType)) {
                    continue;
                }

                // 获取文档内容
                String content = fileName; // 默认使用文件名
                if ("docx".equals(fileType) && fileToken != null) {
                    log.debug("获取云文档内容: fileName={}, fileType={}, fileToken={}", fileName, fileType, fileToken);
                    String docContent = feishuClient.getDocumentContent(fileToken);
                    if (docContent != null && !docContent.isEmpty()) {
                        content = docContent;
                        log.debug("成功获取云文档内容，长度={}", docContent.length());
                    }
                }

                String extra = String.format("{\"file_type\":\"%s\",\"file_token\":\"%s\",\"file_id\":\"%s\"}",
                        fileType, fileToken, fileId);

                docs.add(new SearchIndexService.IndexDoc(fileName, content, "drive", fileToken, "", extra, ""));
                docCount++;

                // 避免频繁调用 API，添加延迟
                try {
                    Thread.sleep(apiCallDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            log.info("云文档同步完成，共 {} 个文档", docCount);
        } catch (Exception e) {
            log.error("同步云文档失败", e);
        }
    }

    /**
     * 解析逗号分隔的 ID 配置
     */
    private Set<String> parseConfigIds(String config) {
        if (config == null || config.isBlank()) return Set.of();
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
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

        switch (msgType) {
            case "file":
                return (String) contentMap.getOrDefault("file_name", null);
            case "doc", "docx", "sheet": {
                String t = (String) contentMap.getOrDefault("title", null);
                return t != null ? t : (String) contentMap.getOrDefault("file_name", null);
            }
            case "wiki": {
                String t = (String) contentMap.getOrDefault("title", null);
                return t != null ? t : "Wiki文档";
            }
            default:
                Object file_name = contentMap.getOrDefault("file_name", null);
                Object title = contentMap.getOrDefault("title", null);
                if (file_name instanceof String s) return s;
                if (title instanceof String s) return s;
                return null;
        }
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
