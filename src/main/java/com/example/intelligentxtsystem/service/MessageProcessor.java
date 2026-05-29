package com.example.intelligentxtsystem.service;

import com.example.intelligentxtsystem.client.FeishuClient;
import com.example.intelligentxtsystem.dto.FeishuCallback;
import com.example.intelligentxtsystem.dto.MessageContent;
import com.example.intelligentxtsystem.service.AiUnderstandingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异步消息处理器
 * 独立 Service 类，确保 @Async 通过 Spring 代理生效
 *
 * 群聊消息处理规则：
 *   - 群聊中（chat_id 以 "oc_" 开头）：只有@机器人时才处理并回复
 *   - 私聊中（chat_id 以 "om_" 开头）：正常处理所有消息
 */
@Service
public class MessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);

    /**
     * 飞书群聊 ID 前缀
     * 文档：https://open.feishu.cn/document/uAjLw4CM/ukTMukTMuYjLwUjMjMjM
     *   oc_xxx  → 群聊
     *   om_xxx  → 单聊（私聊）
     */
    private static final String GROUP_CHAT_PREFIX = "oc_";

    private final ConcurrentHashMap<String, Long> processedMessages = new ConcurrentHashMap<>();

    @Value("${dedup.window-ms}")
    private long dedupWindowMs;

    @Value("${dedup.cleanup-ms}")
    private long dedupCleanupMs;

    private final ObjectMapper objectMapper;
    private final FeishuClient feishuClient;
    private final MessageDispatcher messageDispatcher;
    private final AiUnderstandingService aiUnderstandingService;

    public MessageProcessor(ObjectMapper objectMapper, 
                           FeishuClient feishuClient, 
                           MessageDispatcher messageDispatcher,
                           AiUnderstandingService aiUnderstandingService) {
        this.objectMapper = objectMapper;
        this.feishuClient = feishuClient;
        this.messageDispatcher = messageDispatcher;
        this.aiUnderstandingService = aiUnderstandingService;
    }

    /**
     * 异步处理消息事件
     *
     * 处理逻辑：
     *   1. 解析消息内容
     *   2. 判断是群聊还是私聊
     *   3. 群聊：检查是否@了机器人，未@则直接返回（不回复）
     *   4. 私聊：正常处理
     *   5. 消息去重
     *   6. 分发并处理指令
     *   7. 发送回复
     */
    @Async("messageExecutor")
    public void processMessageEvent(Map<String, Object> body) {
        try {
            // 直接从原始 body 解析 content（兼容 content 是 String 或 Map 两种情况）
            @SuppressWarnings("unchecked")
            Map<String, Object> eventMap = (Map<String, Object>) body.get("event");
            if (eventMap == null) {
                log.warn("event 为空");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> messageMap = (Map<String, Object>) eventMap.get("message");
            if (messageMap == null) {
                log.warn("message 为空");
                return;
            }

            // 用 FeishuCallback 解析其他字段（messageId、chatId、sender）
            FeishuCallback callback = objectMapper.convertValue(body, FeishuCallback.class);
            String chatId = callback.getEvent().getMessage().getChatId();
            String messageId = callback.getEvent().getMessage().getMessageId();

            // ===== 群聊@检查：只有群聊且未@机器人时，直接返回 =====
            if (isGroupChat(chatId)) {
                // 从 message.mentions 解析 mentions
                List<MessageContent.Mention> mentions = parseMentions(messageMap);

                boolean botMentioned = isBotMentioned(mentions);
                if (!botMentioned) {
                    log.info("群聊消息未@机器人，跳过处理。chatId={}, messageId={}", chatId, messageId);
                    return;
                }
                log.info("群聊消息已@机器人，继续处理。chatId={}, messageId={}", chatId, messageId);
            } else {
                log.info("私聊消息，正常处理。chatId={}, messageId={}", chatId, messageId);
            }

            // 解析 content
            String contentJson;
            Object contentObj = messageMap.get("content");
            if (contentObj instanceof String s) {
                contentJson = s;
            } else if (contentObj instanceof Map) {
                contentJson = objectMapper.writeValueAsString(contentObj);
            } else {
                log.warn("无法解析 content，类型: {}", contentObj != null ? contentObj.getClass() : "null");
                return;
            }

            log.info("解析后 content JSON: {}", contentJson);

            MessageContent content = objectMapper.readValue(contentJson, MessageContent.class);
            String text = content.getText();

            // 从 message.mentions 解析 mentions（飞书将 mentions 放在 message 层级，而非 content 内部）
            List<MessageContent.Mention> mentions = parseMentions(messageMap);

            log.info("解析后 text: {}, mentions 数量: {}", text,
                    mentions != null ? mentions.size() : "null");

            if (text == null || text.isEmpty()) {
                return;
            }

            // 清洗 text：移除机器人自身的 @_user_N 占位符，确保以 / 开头能被 dispatch 识别
            String cleanedText = cleanBotMention(text, mentions);

            // 消息去重
            long now = System.currentTimeMillis();
            Long lastTime = processedMessages.putIfAbsent(messageId, now);
            if (lastTime != null) {
                if (now - lastTime < dedupWindowMs) {
                    log.info("消息 {} 已在 {}ms 前处理过，跳过", messageId, now - lastTime);
                    return;
                }
            }
            processedMessages.entrySet().removeIf(entry -> now - entry.getValue() > dedupCleanupMs);

            // 分发处理（传递 cleanedText 和 mentions）
            String reply = messageDispatcher.dispatch(cleanedText, callback.getEvent().getSender(), chatId, mentions);

            // 发送回复
            if (reply != null && reply.startsWith("__CARD__")) {
                String cardJson = reply.substring("__CARD__".length());
                feishuClient.sendCard(chatId, cardJson);
                // 卡片消息也保存对话历史
                aiUnderstandingService.saveConversationHistory(
                    chatId, 
                    callback.getEvent().getSender().getId(),  // 使用 getId() 获取用户ID
                    cleanedText, 
                    "[卡片消息]"
                );
            } else if (reply != null) {
                feishuClient.sendText(chatId, reply);
                // 保存对话历史到 Redis
                aiUnderstandingService.saveConversationHistory(
                    chatId, 
                    callback.getEvent().getSender().getId(),  // 使用 getId() 获取用户ID
                    cleanedText, 
                    reply
                );
            }

        } catch (Exception e) {
            log.error("异步处理消息事件异常", e);
        }
    }

    /**
     * 判断是否为群聊
     * 飞书 chat_id 规则：
     *   - oc_ 开头 → 群聊（Open Chat）
     *   - om_ 开头 → 私聊（Open Message）
     */
    private boolean isGroupChat(String chatId) {
        return chatId != null && chatId.startsWith(GROUP_CHAT_PREFIX);
    }

    /**
     * 检查消息中是否@了机器人
     * 飞书群聊消息的 mentions 中，mentioned_type = "bot" 表示@了机器人
     */
    private boolean isBotMentioned(List<MessageContent.Mention> mentions) {
        if (mentions == null || mentions.isEmpty()) {
            return false;
        }
        return mentions.stream()
                .anyMatch(m -> "bot".equals(m.getMentionedType()));
    }

    /**
     * 从 message.mentions 解析 mention 列表
     * 飞书将 mentions 放在 message 层级（与 content 同级），而非 content 内部
     * mentions[].id 是一个对象 {"open_id":"...","union_id":"..."}，需要提取 open_id
     */
    @SuppressWarnings("unchecked")
    private List<MessageContent.Mention> parseMentions(Map<String, Object> messageMap) {
        try {
            Object mentionsObj = messageMap.get("mentions");
            if (!(mentionsObj instanceof List<?> rawList) || rawList.isEmpty()) {
                return null;
            }

            return rawList.stream()
                    .filter(Objects::nonNull)
                    .map(m -> {
                        try {
                            Map<String, Object> mMap = (Map<String, Object>) m;
                            MessageContent.Mention mention = new MessageContent.Mention();
                            mention.setKey((String) mMap.get("key"));
                            mention.setName((String) mMap.get("name"));

                            // id 是对象 {"open_id":"...","union_id":"...","user_id":"..."}
                            Object idObj = mMap.get("id");
                            if (idObj instanceof Map<?, ?> idMap) {
                                String openId = (String) idMap.get("open_id");
                                mention.setId(openId);
                            }

                            // mentioned_type: "user" | "bot" | "chat"
                            String mentionedType = (String) mMap.get("mentioned_type");
                            mention.setMentionedType(mentionedType);

                            return mention;
                        } catch (Exception e) {
                            log.warn("解析单个 mention 失败: {}", m, e);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .filter(m -> m.getId() != null && !m.getId().isEmpty())
                    .toList();
        } catch (Exception e) {
            log.warn("解析 mentions 失败", e);
            return null;
        }
    }

    /**
     * 清洗 text 中的机器人自身 mention 占位符
     * 例如 "@_user_1 /group B @_user_2" → "/group B @_user_2"
     * 飞书在消息 text 中会把 @机器人 替换成 @_user_N 占位符，
     * 需要移除这些占位符，让 text 以 / 开头，以便 dispatch 正确识别指令。
     */
    private String cleanBotMention(String text, List<MessageContent.Mention> mentions) {
        if (text == null) return null;
        String cleaned = text;
        if (mentions != null) {
            for (MessageContent.Mention m : mentions) {
                if ("bot".equals(m.getMentionedType()) && m.getKey() != null) {
                    cleaned = cleaned.replace(m.getKey(), "").trim();
                }
            }
        }
        // 如果清洗后不以 / 开头，尝试找到第一个 / 之后的内容
        if (!cleaned.startsWith("/")) {
            int slashIndex = cleaned.indexOf("/");
            if (slashIndex >= 0) {
                cleaned = cleaned.substring(slashIndex).trim();
            }
        }
        return cleaned;
    }
}
