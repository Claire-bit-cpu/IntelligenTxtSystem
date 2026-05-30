package com.example.intelligentxtsystem.service;

import com.example.intelligentxtsystem.client.FeishuClient;
import com.example.intelligentxtsystem.dto.FeishuCallback;
import com.example.intelligentxtsystem.dto.FeishuSender;
import com.example.intelligentxtsystem.dto.MessageContent;
import com.example.intelligentxtsystem.service.AiUnderstandingService;
import com.example.intelligentxtsystem.task.AsyncTaskStatus;
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
     *
     * @param body 事件内容
     * @param taskId 任务ID（用于状态跟踪，可为null）
     */
    @Async("messageExecutor")
    public void processMessageEvent(Map<String, Object> body, String taskId) {
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
            String chatId = null;
            String messageId = null;
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

            // ===== 更新任务状态：开始处理 (10%) =====
            updateMessageTaskProgress(taskId, 10, "消息解析完成，开始处理");

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

            // ===== 更新任务状态：核心逻辑执行中 (50%) =====
            updateMessageTaskProgress(taskId, 50, "正在分发处理指令");

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
            if (taskId != null && !taskId.isEmpty()) {
                String taskStartMsg = String.format(
                        "⏳ 任务已提交，正在处理中...\n\n" +
                        "🆔 任务ID: `%s`\n" +
                        "📊 查询进度: `GET /api/task/%s`\n" +
                        "💡 稍后将自动回复处理结果",
                        taskId, taskId
                );
                // 发送通知并保存 messageId，用于后续原地更新进度
                String startMsgId = feishuClient.sendText(chatId, taskStartMsg);
                if (startMsgId != null) {
                    AsyncTaskStatus.setMessageId(taskId, startMsgId);
                    // 保存 chatId，用于更新失败时发送新消息
                    AsyncTaskStatus.setChatId(taskId, chatId);
                    log.info("已发送任务开始通知并保存 messageId: taskId={}, chatId={}, messageId={}", taskId, chatId, startMsgId);
                } else {
                    log.warn("任务开始通知发送失败，无法获取 messageId: taskId={}, chatId={}", taskId, chatId);
                }
            }

            // 分发处理（传递 cleanedText、mentions 和 taskId）
            String reply = messageDispatcher.dispatch(cleanedText, sender, chatId, mentions, taskId);

            // ===== 更新任务状态：结果格式化 (80%) =====
            updateMessageTaskProgress(taskId, 80, "指令处理完成，准备发送回复");

            // 发送回复
            if (reply != null && reply.startsWith("__CARD__")) {
                String cardJson = reply.substring("__CARD__".length());
                feishuClient.sendCard(chatId, cardJson);
                // 卡片消息也保存对话历史
                aiUnderstandingService.saveConversationHistory(
                    chatId, 
                    sender.getId(),  // 使用 getId() 获取用户ID
                    cleanedText, 
                    "[卡片消息]"
                );
            } else if (reply != null) {
                feishuClient.sendText(chatId, reply);
                // 保存对话历史到 Redis
                aiUnderstandingService.saveConversationHistory(
                    chatId,
                    sender.getId(),  // 使用 getId() 获取用户ID
                    cleanedText,
                    reply
                );
            }

            // ===== 更新任务状态：完成 (100%) =====
            updateMessageTaskProgress(taskId, 100, "回复已发送，任务完成");

            // 标记任务完成并计算耗时
            if (taskId != null && !taskId.isEmpty()) {
                try {
                    AsyncTaskStatus task = AsyncTaskStatus.get(taskId);
                    if (task != null && task.getCreatedAt() != null) {
                        long duration = java.time.Duration.between(task.getCreatedAt(), java.time.LocalDateTime.now()).toMillis();
                        AsyncTaskStatus.setDuration(taskId, duration);
                    }
                    AsyncTaskStatus.markCompleted(taskId, "处理成功");
                    AsyncTaskStatus.appendLog(taskId, "任务完成");
                    // 最终更新飞书消息为完成状态
                    updateFeishuMessageFinal(taskId, true, null);
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
                    AsyncTaskStatus.appendLog(taskId, "任务失败: " + e.getMessage());
                    // 更新飞书消息为失败状态
                    updateFeishuMessageFinal(taskId, false, e.getMessage());
                } catch (Exception ex) {
                    log.warn("标记任务失败状态时出错: taskId={}", taskId, ex);
                }
            }
        }
    }

    /**
     * 最终更新飞书消息（任务完成或失败时调用）
     * 如果更新失败（如达到编辑次数上限），自动发送新消息
     * @param taskId 任务ID
     * @param success 是否成功
     * @param errorMsg 错误信息（失败时）
     */
    private void updateFeishuMessageFinal(String taskId, boolean success, String errorMsg) {
        try {
            AsyncTaskStatus task = AsyncTaskStatus.get(taskId);
            if (task == null || task.getMessageId() == null) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            if (success) {
                sb.append("✅ 任务完成\n\n");
                sb.append("🆔 任务ID: `").append(taskId).append("`\n");
                sb.append("📊 最终进度: 100%\n");
                if (task.getDurationMs() != null) {
                    sb.append("⏱ 耗时: ").append(task.getDurationMs()).append("ms\n");
                }
            } else {
                sb.append("❌ 任务失败\n\n");
                sb.append("🆔 任务ID: `").append(taskId).append("`\n");
                sb.append("📝 错误: ").append(errorMsg != null ? errorMsg : "未知错误").append("\n");
            }
            String contentJson = "{\"text\":\"" + sb.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
            
            // 尝试更新消息
            boolean updateSuccess = feishuClient.updateMessage(task.getMessageId(), contentJson, "text");
            if (!updateSuccess) {
                // 更新失败（可能是编辑次数上限），发送新消息
                log.info("最终消息更新失败，发送新消息: taskId={}, oldMessageId={}", taskId, task.getMessageId());
                String chatId = task.getChatId();
                if (chatId != null) {
                    String finalText = sb.toString();
                    String newMessageId = feishuClient.sendText(chatId, finalText);
                    if (newMessageId != null) {
                        // 更新任务中的 messageId
                        AsyncTaskStatus.setMessageId(taskId, newMessageId);
                        log.info("最终消息已更新为新消息: taskId={}, newMessageId={}", taskId, newMessageId);
                    }
                } else {
                    log.warn("无法获取 chatId，无法发送新消息: taskId={}", taskId);
                }
            }
        } catch (Exception e) {
            log.warn("最终更新飞书消息失败: taskId={}, error={}", taskId, e.getMessage());
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
     * 更新任务进度（辅助方法，避免重复代码）
     * @param taskId 任务ID（可为null）
     * @param progress 进度（0-100）
     * @param statusMsg 状态描述信息
     */
    private void updateMessageTaskProgress(String taskId, int progress, String statusMsg) {
        if (taskId == null || taskId.isEmpty()) {
            return;
        }
        try {
            AsyncTaskStatus.updateTaskProgress(taskId, progress, statusMsg);
            AsyncTaskStatus.appendLog(taskId, statusMsg + " (" + progress + "%)");
            // 同时更新飞书侧消息（带频率控制，避免飞书API限流）
            updateFeishuMessageThrottled(taskId, progress, statusMsg);
        } catch (Exception e) {
            log.warn("更新任务进度失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    /**
     * 飞书消息更新频率控制（内存缓存，key=taskId）
     * 同一任务最多每 2 秒更新一次飞书消息，避免触发限流
     */
    private final ConcurrentHashMap<String, Long> lastFeishuUpdateTime = new ConcurrentHashMap<>();
    private static final long FEISHU_UPDATE_INTERVAL_MS = 2000; // 2秒

    private void updateFeishuMessageThrottled(String taskId, int progress, String statusMsg) {
        long now = System.currentTimeMillis();
        Long lastTime = lastFeishuUpdateTime.get(taskId);
        if (lastTime != null && (now - lastTime) < FEISHU_UPDATE_INTERVAL_MS) {
            return; // 距上次更新不足2秒，跳过
        }
        // 进度达到关键节点时强制更新（不受频率限制）
        if (progress >= 100 || progress == 0 || statusMsg.contains("失败")) {
            // 强制更新，清除时间限制
        }
        lastFeishuUpdateTime.put(taskId, now);
        updateFeishuMessage(taskId, progress, statusMsg);
        // 任务完成或失败时，清理缓存
        if (progress >= 100 || statusMsg.contains("失败")) {
            lastFeishuUpdateTime.remove(taskId);
        }
    }

    /**
     * 更新飞书侧消息（原地更新，不发送新消息）
     * 通过 updateMessage API 更新之前发送的任务通知消息
     * 如果更新失败（如达到编辑次数上限），自动发送新消息
     */
    private void updateFeishuMessage(String taskId, int progress, String statusMsg) {
        try {
            AsyncTaskStatus task = AsyncTaskStatus.get(taskId);
            if (task == null || task.getMessageId() == null) {
                return;
            }
            // 构建进度文本消息
            String progressText = buildProgressText(taskId, progress, statusMsg);
            // content 必须是 JSON 字符串: {"text":"..."}
            String contentJson = "{\"text\":\"" + progressText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
            
            // 尝试更新消息
            boolean updateSuccess = feishuClient.updateMessage(task.getMessageId(), contentJson, "text");
            if (!updateSuccess) {
                // 更新失败（可能是编辑次数上限），发送新消息
                log.info("任务消息更新失败，发送新消息: taskId={}, oldMessageId={}", taskId, task.getMessageId());
                // 从 task 获取 chatId（比 ThreadLocal 更可靠）
                String chatId = task.getChatId();
                if (chatId != null) {
                    String newMessageId = feishuClient.sendText(chatId, progressText);
                    if (newMessageId != null) {
                        // 更新任务中的 messageId
                        AsyncTaskStatus.setMessageId(taskId, newMessageId);
                        log.info("任务消息已更新为新消息: taskId={}, newMessageId={}", taskId, newMessageId);
                    }
                } else {
                    log.warn("无法获取 chatId，无法发送新消息: taskId={}", taskId);
                }
            }
        } catch (Exception e) {
            log.warn("更新飞书消息失败: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    /**
     * 构建进度文本（用于飞书消息更新）
     */
    private String buildProgressText(String taskId, int progress, String statusMsg) {
        StringBuilder sb = new StringBuilder();
        sb.append("⏳ 任务处理中...\n\n");
        sb.append("🆔 任务ID: `").append(taskId).append("`\n");
        sb.append("📊 进度: ").append(progress).append("%\n");
        sb.append("📝 状态: ").append(statusMsg).append("\n\n");
        // 进度条
        int bars = progress / 10;
        sb.append("[");
        for (int i = 0; i < 10; i++) {
            sb.append(i < bars ? "█" : "░");
        }
        sb.append("] ").append(progress).append("%\n");
        return sb.toString();
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
