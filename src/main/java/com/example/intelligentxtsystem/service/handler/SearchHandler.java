package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.client.FeishuClient;
import com.example.intelligentxtsystem.dto.FeishuSender;
import com.example.intelligentxtsystem.service.SearchIndexService;
import com.example.intelligentxtsystem.service.SearchSyncScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库搜索指令处理器
 * 指令格式：/search <关键词> 或 搜索 <关键词> 或 查询 <关键词>
 *
 * 搜索来源：
 * 1. 飞书官方 API（实时搜索群文件 + 知识库）
 * 2. 本地搜索引擎（SQLite FTS5，支持中文全文检索）
 *
 * 管理命令（仅群管理员可用）：
 * /search sync   - 手动触发索引同步
 * /search status - 查看索引状态
 */
@Component
public class SearchHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(SearchHandler.class);

    private final FeishuClient feishuClient;
    private final SearchIndexService indexService;
    private final SearchSyncScheduler syncScheduler;
    private final ObjectMapper objectMapper;

    /**
     * 本地管理员缓存：存储所有群的管理员ID列表
     * Key: chatId
     * Value: 管理员OpenID集合（带过期时间）
     */
    private final Map<String, AdminListCacheEntry> adminListCache = new ConcurrentHashMap<>();

    /**
     * 管理员列表缓存有效期（秒）：30分钟
     * 每30分钟重新从API获取一次管理员列表
     */
    private static final long ADMIN_LIST_CACHE_DURATION_SECONDS = 1800;

    public SearchHandler(FeishuClient feishuClient, SearchIndexService indexService,
                         SearchSyncScheduler syncScheduler, ObjectMapper objectMapper) {
        this.feishuClient = feishuClient;
        this.indexService = indexService;
        this.syncScheduler = syncScheduler;
        this.objectMapper = objectMapper;
    }

    /**
     * 检查用户是否为群管理员（使用本地缓存的管理员列表）
     * 
     * @param senderOpenId 发送者的OpenID
     * @param chatId 群聊ID
     * @return true=是管理员，false=不是管理员或检查失败
     */
    private boolean isGroupAdmin(String senderOpenId, String chatId) {
        if (senderOpenId == null || chatId == null) {
            return false;
        }

        // 从本地缓存获取管理员列表
        Set<String> adminIds = getAdminIdsFromCache(chatId);
        return adminIds.contains(senderOpenId);
    }

    /**
     * 从本地缓存获取管理员ID列表（带自动刷新）
     */
    private Set<String> getAdminIdsFromCache(String chatId) {
        AdminListCacheEntry cachedEntry = adminListCache.get(chatId);

        // 缓存未命中或已过期，重新获取
        if (cachedEntry == null || cachedEntry.isExpired()) {
            log.info("管理员列表缓存未命中或已过期，重新获取: chatId={}", chatId);
            Set<String> adminIds = feishuClient.getChatAdminIds(chatId);
            adminListCache.put(chatId, new AdminListCacheEntry(adminIds));
            return adminIds;
        }

        log.debug("管理员列表缓存命中: chatId={}, 管理员数量={}", chatId, cachedEntry.adminIds.size());
        return cachedEntry.adminIds;
    }

    /**
     * 手动刷新指定群的管理员列表（供定时任务调用）
     */
    public void refreshAdminList(String chatId) {
        if (chatId == null || chatId.isEmpty()) {
            return;
        }

        log.info("手动刷新管理员列表: chatId={}", chatId);
        Set<String> adminIds = feishuClient.getChatAdminIds(chatId);
        adminListCache.put(chatId, new AdminListCacheEntry(adminIds));
    }

    /**
     * 清除所有管理员缓存（供定时任务批量刷新）
     */
    public void refreshAllAdminLists(Set<String> chatIds) {
        if (chatIds == null || chatIds.isEmpty()) {
            return;
        }

        log.info("批量刷新管理员列表: {} 个群", chatIds.size());
        for (String chatId : chatIds) {
            try {
                Set<String> adminIds = feishuClient.getChatAdminIds(chatId);
                adminListCache.put(chatId, new AdminListCacheEntry(adminIds));
            } catch (Exception e) {
                log.warn("刷新管理员列表失败: chatId={}", chatId, e);
            }
        }
    }

    /**
     * 管理员列表缓存条目
     */
    private static class AdminListCacheEntry {
        private final Set<String> adminIds;
        private final long expireTime; // 过期时间戳（秒）

        AdminListCacheEntry(Set<String> adminIds) {
            this.adminIds = adminIds != null ? adminIds : Set.of();
            this.expireTime = Instant.now().getEpochSecond() + ADMIN_LIST_CACHE_DURATION_SECONDS;
        }

        boolean isExpired() {
            return Instant.now().getEpochSecond() > expireTime;
        }
    }

    @Override
    public boolean support(String text) {
        return text.startsWith("/search") || text.startsWith("搜索") || text.startsWith("查询");
    }

    @Override
    public String handle(String text, FeishuSender sender, String chatId) {
        String keyword = text
                .replaceAll("^(/search|搜索|查询)\\s*", "")
                .trim();

        if (keyword.isEmpty()) {
            // 检查是否为群管理员，根据权限显示不同的帮助信息
            boolean isAdmin = false;
            String senderOpenId = sender != null ? sender.getOpenId() : null;
            if (senderOpenId != null && chatId != null) {
                isAdmin = isGroupAdmin(senderOpenId, chatId);
            }

            if (isAdmin) {
                // 管理员：显示所有指令
                return """
                        ❌ 用法：/search <关键词>
                        
                        📋 示例：
                        /search 项目规范
                        搜索 需求文档
                        查询 API接口
                        
                        🔧 管理命令：
                        /search sync         - 同步索引
                        /search status       - 索引状态
                        /search refresh_admins - 刷新管理员列表
                        
                        💡 搜索群内文件、飞书知识库及本地索引
                        """;
            } else {
                // 普通成员：只显示搜索指令
                return """
                        ❌ 用法：/search <关键词>
                        
                        📋 示例：
                        /search 项目规范
                        搜索 需求文档
                        查询 API接口
                        
                        💡 搜索群内文件、飞书知识库及本地索引
                        """;
            }
        }

        if (keyword.length() > 200) {
            return "⚠️ 关键词长度不能超过200个字符";
        }

        // 刷新管理员列表命令（需要管理员权限）
        if ("refresh_admins".equalsIgnoreCase(keyword)) {
            String senderOpenId = sender != null ? sender.getOpenId() : null;
            
            if (senderOpenId == null) {
                return """
                        ❌ 无法验证权限
                        
                        🔒 请确保在群聊中使用此命令
                        
                        💡 如果问题持续，请联系开发者
                        """;
            }
            
            // 检查是否为群管理员
            if (!isGroupAdmin(senderOpenId, chatId)) {
                return """
                        ❌ 权限不足
                        
                        🔒 /search refresh_admins 命令仅群管理员可用
                        
                        💡 请联系群管理员执行此操作
                        """;
            }
            
            // 刷新管理员列表
            refreshAdminList(chatId);
            
            return """
                    ✅ 管理员列表已刷新
                    
                    📊 当前群管理员数量：%d
                    
                    💡 权限缓存已清除，下次执行管理命令时将重新验证权限
                    """.formatted(getAdminIdsFromCache(chatId).size());
        }

        // 管理命令（需要管理员权限）
        if ("sync".equalsIgnoreCase(keyword) || "status".equalsIgnoreCase(keyword)) {
            // 检查是否为群管理员
            String senderOpenId = sender != null ? sender.getOpenId() : null;
            
            if (senderOpenId == null) {
                log.warn("无法获取发送者OpenID，拒绝执行管理命令");
                return """
                        ❌ 无法验证权限
                        
                        🔒 请确保在群聊中使用此命令
                        
                        💡 如果问题持续，请联系开发者
                        """;
            }
            
            log.info("检查用户权限: senderOpenId={}, chatId={}", senderOpenId, chatId);
            
            if (!isGroupAdmin(senderOpenId, chatId)) {
                log.warn("用户权限不足: senderOpenId={}, chatId={}", senderOpenId, chatId);
                return """
                        ❌ 权限不足
                        
                        🔒 /search sync 和 /search status 命令仅群管理员可用
                        
                        💡 请联系群管理员执行此操作
                        """;
            }

            log.info("用户权限验证通过: senderOpenId={}", senderOpenId);
            
            if ("sync".equalsIgnoreCase(keyword)) {
                return handleSync();
            } else {
                return handleStatus();
            }
        }

        try {
            StringBuilder result = new StringBuilder();
            boolean found = false;
            boolean permissionDenied = false;

            // 1. 搜索引擎（本地索引，速度快，支持全文检索）
            var indexResults = indexService.search(keyword, 5);
            if (!indexResults.isEmpty()) {
                result.append("🔎 索引搜索：\n");
                int idx = 0;
                for (var doc : indexResults) {
                    idx++;
                    result.append(String.format("%d. %s\n", idx, doc.title()));
                    result.append(formatIndexDocInfo(doc));
                }
                found = true;
            }

            // 2. 实时 API 搜索：群内文件/文档消息
            String groupResult = feishuClient.searchGroupFiles(chatId, keyword);
            if ("PERMISSION_DENIED".equals(groupResult)) {
                permissionDenied = true;
            } else if (groupResult != null) {
                if (found) result.append("\n");
                result.append("📂 群内文件（实时）：\n").append(groupResult);
                found = true;
            }

            // 3. 实时 API 搜索：飞书知识库
            String wikiResult = feishuClient.searchDocuments(keyword);
            if (wikiResult != null) {
                if (found) result.append("\n");
                result.append("📚 知识库（实时）：\n").append(wikiResult);
                found = true;
            }

            if (!found) {
                if (permissionDenied) {
                    return String.format("""
                            🔍 关键词：%s
                            
                            ⚠️ 缺少读取群消息权限，无法实时搜索群内文件
                            
                            📌 请管理员在飞书开放平台为应用添加以下权限：
                            • im:message.group_msg:readonly（读取群聊消息）
                            • wiki:wiki:readonly（查看知识库）
                            
                            添加后需重新发布应用版本
                            
                            💡 可使用本地索引搜索，输入 /search sync 同步索引
                            """, keyword);
                }
                return String.format("""
                        🔍 关键词：%s
                        
                        ❌ 未找到相关文档
                        
                        💡 建议：
                        1. 尝试更换关键词
                        2. 输入 /search sync 同步索引后再试
                        3. 检查文档是否已共享给机器人
                        """, keyword);
            }

            return String.format("""
                    🔍 关键词：%s
                    
                    📄 找到以下相关文档：
                    %s
                    💡 请在群聊中按时间查找对应文件
                    """, keyword, result);
        } catch (Exception e) {
            return "⚠️ 搜索服务暂时不可用，请稍后再试";
        }
    }

    /**
     * 处理 /search sync 命令
     */
    private String handleSync() {
        if (syncScheduler.isSyncing()) {
            return "⏳ 索引同步正在进行中，请稍后再试";
        }
        boolean started = syncScheduler.triggerSyncAsync();
        if (started) {
            return """
                    🔄 搜索索引同步已开始！
                    
                    ⏱️ 预计1-2分钟完成
                    📊 同步完成后可输入 /search status 查看状态
                    
                    💡 同步会拉取所有群聊消息和知识库文档建立本地索引
                    """;
        }
        return "⏳ 索引同步正在进行中，请稍后再试";
    }

    /**
     * 处理 /search status 命令
     */
    private String handleStatus() {
        int count = indexService.getDocumentCount();
        String lastSync = indexService.getLastSyncTime();
        boolean syncing = syncScheduler.isSyncing();

        return String.format("""
                📊 搜索索引状态
                
                📄 已索引文档数：%s
                🕐 上次同步：%s
                🔄 同步状态：%s
                
                💡 输入 /search sync 手动触发同步
                """,
                count >= 0 ? count : "未知",
                lastSync,
                syncing ? "同步中..." : "空闲"
        );
    }

    /**
     * 格式化索引搜索结果的附加信息
     */
    private String formatIndexDocInfo(SearchIndexService.IndexDoc doc) {
        StringBuilder sb = new StringBuilder();

        // 解析 extra JSON
        String sourceLabel = switch (doc.source()) {
            case "group_file" -> "群文件";
            case "group_text" -> "群消息";
            case "wiki" -> "知识库";
            default -> doc.source();
        };
        sb.append(String.format("   📁 来源：%s\n", sourceLabel));

        // 尝试解析 extra 获取更多详情
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> extra = objectMapper.readValue(doc.extra(), Map.class);

            if ("group_file".equals(doc.source())) {
                String fileType = (String) extra.getOrDefault("file_type", "");
                if (!fileType.isEmpty()) sb.append(String.format("   📋 类型：%s\n", fileType));
                String chatName = (String) extra.getOrDefault("chat_name", "");
                if (!chatName.isEmpty()) sb.append(String.format("   💬 群聊：%s\n", chatName));
            } else if ("group_text".equals(doc.source())) {
                String chatName = (String) extra.getOrDefault("chat_name", "");
                if (!chatName.isEmpty()) sb.append(String.format("   💬 群聊：%s\n", chatName));
            } else if ("wiki".equals(doc.source())) {
                String spaceName = (String) extra.getOrDefault("space_name", "");
                if (!spaceName.isEmpty()) sb.append(String.format("   📚 知识库：%s\n", spaceName));
                String nodeToken = (String) extra.getOrDefault("node_token", "");
                if (!nodeToken.isEmpty()) sb.append(String.format("   🔗 https://feishu.cn/wiki/%s\n", nodeToken));
            }
        } catch (Exception e) {
            // extra 解析失败，忽略
        }

        if (!doc.createdTime().isEmpty()) {
            sb.append(String.format("   🕐 时间：%s\n", doc.createdTime()));
        }

        return sb.toString();
    }
}
