package com.example.IntelligentRobot.service;

import com.example.IntelligentRobot.client.FeishuClient;
import com.example.IntelligentRobot.dto.FeishuCallback;
import com.example.IntelligentRobot.dto.FeishuSender;
import com.example.IntelligentRobot.dto.MessageContent;
import com.example.IntelligentRobot.task.AsyncTaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
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

    @Value("${dedup.window-ms:30000}")
    private long dedupWindowMs;

    @Value("${dedup.cleanup-ms:600000}")
    private long dedupCleanupMs;

    /**
     * 定时清理 processedMessages，防止内存泄漏
     * 每10分钟清理一次超过2倍去重窗口期的记录
     */
    @Scheduled(fixedDelay = 600000)
    public void cleanupProcessedMessages() {
        long now = System.currentTimeMillis();
        int before = processedMessages.size();
        processedMessages.entrySet().removeIf(entry ->
            now - entry.getValue() > dedupWindowMs * 2
        );
        int after = processedMessages.size();
        if (before != after) {
            log.debug("清理 processedMessages: {} -> {}", before, after);
        }
    }

    @Value("${task.slow-threshold-ms:3000}")
    private long slowTaskThresholdMs;

    private final ObjectMapper objectMapper;
    private final FeishuClient feishuClient;
    private final MessageDispatcher messageDispatcher;
    private final AiUnderstandingService aiUnderstandingService;
    private final ConfirmService confirmService;

    public MessageProcessor(ObjectMapper objectMapper,
                           FeishuClient feishuClient,
                           MessageDispatcher messageDispatcher,
                           AiUnderstandingService aiUnderstandingService,
                           ConfirmService confirmService) {
        this.objectMapper = objectMapper;
        this.feishuClient = feishuClient;
        this.messageDispatcher = messageDispatcher;
        this.aiUnderstandingService = aiUnderstandingService;
        this.confirmService = confirmService;
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
     *
     * @param body 事件内容
     * @param taskId 任务ID（用于状态跟踪，可为null）
     */
    @Async("messageExecutor")
    public void processMessageEvent(Map<String, Object> body, String taskId) {
        // 提前声明 chatId/messageId，使 catch 块也能访问
        String chatId = null;
        String messageId = null;

        try {
            // 直接从原始 body 解析 content（兼容 content 是 String 或 Map 两种情况）
            @SuppressWarnings("unchecked")
            Map<String, Object> eventMap = (Map<String, Object>) body.get("event");
            if (eventMap == null) {
                log.warn("event 为空, body keys: {}", body.keySet());
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> messageMap = (Map<String, Object>) eventMap.get("message");
            if (messageMap == null) {
                log.warn("message 为空");
                return;
            }

            // 兼容两种结构：
            // 1. 标准结构：{"header": {...}, "event": {...}}
            // 2. 扁平结构（解密后）：{"event_id": "...", "event_type": "...", "event": {...}}
            FeishuCallback callback = null;
            
            try {
                callback = objectMapper.convertValue(body, FeishuCallback.class);
                if (callback.getEvent() != null && callback.getEvent().getMessage() != null) {
                    chatId = callback.getEvent().getMessage().getChatId();
                    messageId = callback.getEvent().getMessage().getMessageId();
                }
            } catch (Exception e) {
                log.warn("FeishuCallback 转换失败，尝试直接从 eventMap 解析: {}", e.getMessage());
            }
            
            // 如果转换失败，直接从 eventMap 解析
            if (chatId == null || messageId == null) {
                log.info("从 eventMap 直接解析 chatId 和 messageId");
                chatId = (String) messageMap.get("chat_id");
                messageId = (String) messageMap.get("message_id");
            }
            
            if (chatId == null || messageId == null) {
                log.error("无法解析 chatId 或 messageId");
                return;
            }
            
            log.info("解析成功: chatId={}, messageId={}", chatId, messageId);

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

            // 获取 sender 信息（兼容标准结构和扁平结构）
            FeishuSender sender = null;
            if (callback != null && callback.getEvent() != null && callback.getEvent().getSender() != null) {
                sender = callback.getEvent().getSender();
            } else {
                // 从 eventMap 直接解析 sender
                @SuppressWarnings("unchecked")
                Map<String, Object> eventMapForSender = (Map<String, Object>) body.get("event");
                if (eventMapForSender != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> senderMap = (Map<String, Object>) eventMapForSender.get("sender");
                    if (senderMap != null) {
                        sender = objectMapper.convertValue(senderMap, FeishuSender.class);
                    }
                }
            }
            
            if (sender == null) {
                log.warn("无法解析 sender，使用默认值");
                sender = new FeishuSender();
                sender.setSenderId("unknown");
            }

            // 如果 taskId 不为空，发送任务开始通知（包含 taskId，方便用户查询进度）
            // 只有慢任务（预计耗时超过阈值）才发送通知，避免简单指令刷屏
            if (taskId != null && !taskId.isEmpty()) {
                // 先标记任务为"可能慢任务"，后续完成时用实际耗时判断
                AsyncTaskStatus.setChatId(taskId, chatId);
                // 默认不发送开始通知，等任务完成时用实际耗时判断
                log.info("任务已创建，将在完成时根据耗时决定是否推送通知: taskId={}, chatId={}", taskId, chatId);
            }

            // ===== 处理确认/取消消息（二次确认交互）=====
            String confirmReply = handleConfirmMessage(text, sender, chatId, taskId);
            if (confirmReply != null) {
                feishuClient.sendText(chatId, confirmReply);
                // 确认消息处理完毕，跳过后续分发
                return;
            }

            // 分发处理（传递 cleanedText、mentions 和 taskId）
            String reply = messageDispatcher.dispatch(cleanedText, sender, chatId, mentions, taskId);

            // 发送回复（添加安全检测）
            if (reply != null && reply.startsWith("__CARD__")) {
                String cardJson = reply.substring("__CARD__".length());
                // 卡片消息也做安全检测
                if (containsFakeExecutionInProcessor(cardJson)) {
                    cardJson = "{\"config\":{\"wide_screen_mode\":true},\"header\":{\"title\":{\"tag\":\"plain_text\",\"content\":\"⚠️ 安全检测\"}},\"elements\":[{\"tag\":\"div\",\"text\":{\"tag\":\"ltext\",\"content\":\"检测到虚假执行结果，已拦截\"}}]}";
                }
                feishuClient.sendCard(chatId, cardJson);
                // 卡片消息也保存对话历史
                aiUnderstandingService.saveConversationHistory(
                    chatId, 
                    sender.getId(),  // 使用 getId() 获取用户ID
                    cleanedText, 
                    "[卡片消息]"
                );
            } else if (reply != null) {
                // 【安全检测】检查回复是否包含虚假执行结果
                if (containsFakeExecutionInProcessor(reply)) {
                    log.warn("【安全检测】回复包含虚假执行结果，已拦截。reply={}", reply.substring(0, Math.min(100, reply.length())));
                    reply = "⚠️ 我检测到回复内容可能包含虚假的执行结果。\n\n" +
                            "请使用 / 开头的指令来执行操作，例如：\n" +
                            "• /schedule 2026-05-30 15:00 会议 - 创建日程\n" +
                            "• /updateschedule 18:00 - 修改日程\n\n" +
                            "💡 输入 /help 查看所有可用指令";
                }
                feishuClient.sendText(chatId, reply);
                // 保存对话历史到 Redis
                aiUnderstandingService.saveConversationHistory(
                    chatId,
                    sender.getId(),  // 使用 getId() 获取用户ID
                    cleanedText,
                    reply
                );
            }

            // 标记任务完成
            if (taskId != null && !taskId.isEmpty()) {
                try {
                    AsyncTaskStatus.markCompleted(taskId, "处理成功");
                } catch (Exception ex) {
                    log.warn("标记任务完成时出错: taskId={}", taskId, ex);
                }
            }

        } catch (Exception e) {
            log.error("异步处理消息事件异常: taskId={}", taskId, e);
            // 标记任务失败
            if (taskId != null && !taskId.isEmpty()) {
                try {
                    AsyncTaskStatus.markFailed(taskId, e.getMessage());
                } catch (Exception ex) {
                    log.warn("标记任务失败状态时出错: taskId={}", taskId, ex);
                }
            }
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

    /**
     * 处理确认/取消消息（二次确认交互）
     * 支持以下格式：
     *   - "确认 <token>"
     *   - "取消 <token>"
     *   - "confirm <token>"
     *   - "cancel <token>"
     *
     * @return 回复消息（非 null 表示已处理，调用方应直接发送回复）
     */
    private String handleConfirmMessage(String text, FeishuSender sender, String chatId, String taskId) {
        if (text == null || confirmService == null) return null;

        String trimmed = text.trim();
        String openId = sender != null ? sender.getOpenId() : null;

        // 匹配：确认 <token> 或 取消 <token>
        java.util.regex.Pattern confirmPattern = java.util.regex.Pattern.compile("^(确认|confirm)\\s+(\\w+)$", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern cancelPattern = java.util.regex.Pattern.compile("^(取消|cancel)\\s+(\\w+)$", java.util.regex.Pattern.CASE_INSENSITIVE);

        java.util.regex.Matcher confirmMatcher = confirmPattern.matcher(trimmed);
        if (confirmMatcher.find()) {
            String token = confirmMatcher.group(2);
            log.info("收到确认消息: token={}, openId={}", token, maskOpenId(openId));

            ConfirmService.PendingAction action = confirmService.consume(token, openId, chatId);
            if (action != null) {
                log.info("二次确认成功，执行操作: command={}, args={}", action.getCommandName(), action.getArgs());
                // 直接构造已确认的 CommandContext，执行指令，不再走 --confirm 解析流程
                com.example.IntelligentRobot.dto.CommandContext confirmContext =
                        new com.example.IntelligentRobot.dto.CommandContext();
                confirmContext.setCommandName(action.getCommandName());
                confirmContext.setArgs(action.getArgs());
                confirmContext.setSender(sender);
                confirmContext.setChatId(chatId);
                confirmContext.setConfirmed(true);
                confirmContext.setConfirmToken(token);

                if (taskId != null && !taskId.isEmpty()) {
                    com.example.IntelligentRobot.task.TaskContext.setTaskId(taskId);
                }
                try {
                    Object result = messageDispatcher.getCommandRegistry().execute(action.getCommandName(), confirmContext);
                    return result != null ? result.toString() : "✅ 操作执行完成";
                } catch (Exception e) {
                    log.error("执行确认操作失败: token={}", token, e);
                    return "❌ 执行确认操作失败: " + e.getMessage();
                } finally {
                    com.example.IntelligentRobot.task.TaskContext.clear();
                }
            } else {
                return "⚠️ 确认令牌无效或已过期。\n💡 确认令牌有效期为 5 分钟，请重新执行操作。";
            }
        }

        java.util.regex.Matcher cancelMatcher = cancelPattern.matcher(trimmed);
        if (cancelMatcher.find()) {
            String token = cancelMatcher.group(2);
            log.info("收到取消消息: token={}, openId={}", token, maskOpenId(openId));
            // 尝试删除待确认操作
            try {
                confirmService.consume(token, openId, chatId);
            } catch (Exception e) {
                // 忽略
            }
            return "✅ 操作已取消。";
        }

        return null; // 不是确认/取消消息，继续正常分发
    }

    private String maskOpenId(String openId) {
        if (openId == null || openId.length() < 8) return "***";
        return openId.substring(0, 4) + "***" + openId.substring(openId.length() - 4);
    }

    /**
     * 检测文本中是否包含虚假执行结果关键词
     * 用于防止 AI 生成虚假的执行成功消息
     * 
     * 关键：只检测 AI 明确说自己执行了操作的情况，不检测正常的执行成功消息
     * 例如：
     *   - 虚假："我已创建了群组"、"我已经为您添加了日程"
     *   - 正常："群组创建成功！"、"日程已添加"
     * 
     * @param text 要检测的文本
     * @return 如果包含虚假执行结果关键词返回 true，否则返回 false
     */
    private boolean containsFakeExecutionInProcessor(String text) {
        if (text == null) return false;
        
        String lowerText = text.toLowerCase();
        
        // 操作类动词
        String[] operationVerbs = {"创建", "修改", "更新", "删除", "取消", "更改", "添加", "移除"};
        
        // 模式1：AI 说"我已" + 操作类动词 + "了"
        // 例如："我已修改了"、"我已经创建了"
        for (String verb : operationVerbs) {
            if (lowerText.contains("我已" + verb + "了") || 
                lowerText.contains("我已经" + verb + "了")) {
                return true;
            }
        }
        
        // 模式2：AI 说"已经为您" + 操作类动词
        // 例如："已经为您修改"、"已为您创建"
        for (String verb : operationVerbs) {
            if (lowerText.contains("已经为您" + verb) || 
                lowerText.contains("已为您" + verb)) {
                return true;
            }
        }
        
        // 模式3：AI 说"已帮您" + 操作类动词
        // 例如："已帮您修改"、"已经帮您创建"
        for (String verb : operationVerbs) {
            if (lowerText.contains("已帮您" + verb) || 
                lowerText.contains("已经帮您" + verb)) {
                return true;
            }
        }
        
        // 模式4：AI 说"我已成功" + 操作类动词（暗示自己执行了操作）
        // 例如："我已成功创建"、"我已经成功添加"
        for (String verb : operationVerbs) {
            if (lowerText.contains("我已成功" + verb) || 
                lowerText.contains("我已经成功" + verb)) {
                return true;
            }
        }
        
        return false;
    }
}
