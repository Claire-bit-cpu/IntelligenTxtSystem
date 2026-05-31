package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.annotation.Command;
import com.example.intelligentxtsystem.client.FeishuClient;
import com.example.intelligentxtsystem.dto.CommandContext;
import com.example.intelligentxtsystem.service.SearchIndexService;
import com.example.intelligentxtsystem.service.SearchSyncScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识库搜索指令处理器（新框架版本）
 */
@Component
public class SearchCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(SearchCommandHandler.class);

    private final FeishuClient feishuClient;
    private final SearchIndexService indexService;
    private final SearchSyncScheduler syncScheduler;
    private final ObjectMapper objectMapper;

    public SearchCommandHandler(FeishuClient feishuClient, SearchIndexService indexService,
                         SearchSyncScheduler syncScheduler, ObjectMapper objectMapper) {
        this.feishuClient = feishuClient;
        this.indexService = indexService;
        this.syncScheduler = syncScheduler;
        this.objectMapper = objectMapper;
    }

    private boolean isGroupAdmin(String senderOpenId, String chatId) {
        if (senderOpenId == null || chatId == null) return false;
        java.util.Set<String> adminIds = feishuClient.getChatAdminIds(chatId);
        return adminIds.contains(senderOpenId);
    }

    @Command(
        name = "search",
        description = "搜索群内文件和知识库",
        usage = "/search <关键词>"
    )
    public String handle(CommandContext context) {
        String keyword = context.getArgs().trim();
        String chatId = context.getChatId();
        String senderOpenId = context.getSender() != null ? context.getSender().getOpenId() : null;

        if (keyword.isEmpty()) {
            return """
                    ❹ 用法：/search <关键词>
                    
                    📋 示例：
                    /search 项目规范
                    搜索 需求文档
                    查询 API接口
                    
                    💡 搜索群内文件、飞书知识库及本地索引
                    """;
        }

        if (keyword.length() > 200) {
            return "⚠️ 关键词长度不能超过200个字符";
        }

        if ("sync".equalsIgnoreCase(keyword) || "status".equalsIgnoreCase(keyword)) {
            if (senderOpenId == null) {
                return """
                        ❹ 无法验证权限
                        
                        🔒 请确保在群聊中使用此命令
                        
                        💡 如果问题持续，请联系开发者
                        """;
            }
            log.info("检查用户权限: senderOpenId={}, chatId={}", senderOpenId, chatId);
            if (!isGroupAdmin(senderOpenId, chatId)) {
                return """
                        ❹ 权限不足
                        
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

            String groupResult = feishuClient.searchGroupFiles(chatId, keyword);
            if ("PERMISSION_DENIED".equals(groupResult)) {
                permissionDenied = true;
            } else if (groupResult != null) {
                if (found) result.append("\n");
                result.append("📂 群内文件（实时）：\n").append(groupResult);
                found = true;
            }

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
                        • im:message.group_msg:readonly
                        • wiki:wiki:readonly
                        
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

    private String formatIndexDocInfo(SearchIndexService.IndexDoc doc) {
        StringBuilder sb = new StringBuilder();
        String sourceLabel = switch (doc.source()) {
            case "group_file" -> "群文件";
            case "group_text" -> "群消息";
            case "wiki" -> "知识库";
            default -> doc.source();
        };
        sb.append(String.format("   📁 来源：%s\n", sourceLabel));
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
        } catch (Exception e) {}
        if (!doc.createdTime().isEmpty()) {
            sb.append(String.format("   🕐 时间：%s\n", doc.createdTime()));
        }
        return sb.toString();
    }
}
