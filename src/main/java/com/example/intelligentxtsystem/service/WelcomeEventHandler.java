package com.example.intelligentxtsystem.service;

import com.example.intelligentxtsystem.client.FeishuClient;
import com.example.intelligentxtsystem.config.WelcomeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 新成员入群欢迎事件处理器
 *
 * 监听飞书事件：
 *   - im.chat.member.bot.added_v1  （机器人被加入群聊 / 有新成员通过机器人邀请入群）
 *   - im.chat.member.user.added_v1   （群成员增加，更常用）
 *
 * 功能：
 *   1. 解析事件中的新成员列表
 *   2. 防刷屏：同一群聊冷却时间内只发一条欢迎消息
 *   3. 批量合并：一次多人入群时合并为一条消息
 */
@Service
public class WelcomeEventHandler {

    private static final Logger log = LoggerFactory.getLogger(WelcomeEventHandler.class);

    /**
     * 需要监听的事件类型
     */
    public static final String EVENT_USER_ADDED  = "im.chat.member.user.added_v1";
    public static final String EVENT_BOT_ADDED   = "im.chat.member.bot.added_v1";
    public static final String EVENT_INVITED_V1  = "im.chat.member.bot.invited_v1";

    private final FeishuClient feishuClient;
    private final WelcomeConfig welcomeConfig;
    private final ObjectMapper objectMapper;
    private final TaskExecutor lowPriorityEventExecutor;

    /**
     * 冷却记录：chatId -> 上次发送欢迎消息的时间
     * 防止短时间内重复发送欢迎消息（如批量导入成员）
     */
    private final Map<String, Instant> cooldownRecord = new ConcurrentHashMap<>();

    /**
     * 待发送队列：chatId -> （成员列表，入群时间）
     * 用于批量合并：在极短时间内（如 3 秒内）入群的多名成员合并为一条消息
     */
    private final Map<String, PendingWelcome> pendingWelcomeMap = new ConcurrentHashMap<>();

    /**
     * 批量合并窗口（毫秒）
     * 在此时间窗口内的入群成员合并为一条消息
     */
    @Value("${welcome.batch-window-ms:3000}")
    private long batchWindowMs;

    @Autowired
    public WelcomeEventHandler(FeishuClient feishuClient,
                              WelcomeConfig welcomeConfig,
                              ObjectMapper objectMapper,
                              @Qualifier("lowPriorityEventExecutor") TaskExecutor lowPriorityEventExecutor) {
        this.feishuClient = feishuClient;
        this.welcomeConfig = welcomeConfig;
        this.objectMapper = objectMapper;
        this.lowPriorityEventExecutor = lowPriorityEventExecutor;
    }

    /**
     * 处理入群事件（由 WebhookController 调用）
     *
     * @param eventType 事件类型
     * @param event     事件体（Map 结构，来自飞书回调 JSON）
     */
    @SuppressWarnings("unchecked")
    public void handleMemberAdded(String eventType, Map<String, Object> event) {
        if (!welcomeConfig.isEnabled()) {
            log.debug("欢迎功能已禁用，忽略入群事件");
            return;
        }

        if (event == null) {
            log.warn("入群事件体为空");
            return;
        }

        // 提取群聊 ID
        String chatId = (String) event.get("chat_id");
        if (chatId == null || chatId.isBlank()) {
            log.warn("入群事件中缺少 chat_id");
            return;
        }

        // 提取新成员列表
        List<Map<String, Object>> members = extractMembers(event);
        if (members.isEmpty()) {
            log.debug("入群事件中无新成员信息，eventType={}", eventType);
            return;
        }

        log.info("检测到新成员入群: chatId={}, 人数={}, eventType={}",
                chatId, members.size(), eventType);

        // 获取成员姓名（用于欢迎消息）
        List<MemberInfo> memberInfos = resolveMemberNames(members);

        // 检查冷却
        if (isInCooldown(chatId)) {
            log.info("群聊 {} 欢迎消息在冷却中，跳过", chatId);
            return;
        }

        // 批量合并处理
        PendingWelcome pending = pendingWelcomeMap.get(chatId);
        if (pending != null) {
            // 已有待发送消息，合并
            pending.members.addAll(memberInfos);
            log.info("合并入群成员: chatId={}, 当前待发送人数={}",
                    chatId, pending.members.size());
            return;
        }

        // 首次入群，创建待发送任务，延迟 BATCH_WINDOW_MS 后发送
        PendingWelcome newPending = new PendingWelcome(memberInfos);
        pendingWelcomeMap.put(chatId, newPending);

        // 延迟发送（给批量合并留出时间窗口）
        scheduleWelcomeMessage(chatId, newPending);
    }

    /**
     * 从事件中提取成员列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractMembers(Map<String, Object> event) {
        // im.chat.member.user.added_v1 事件结构：
        //   event.operators          -> 操作人列表
        //   event.object.chat_id     -> 群 ID
        //   成员信息在 event.users    -> 新成员列表  （部分事件格式）
        //   或 event.added_users     -> 新成员列表
        List<Map<String, Object>> result = new ArrayList<>();

        // 尝试 added_users
        Object addedUsers = event.get("added_users");
        if (addedUsers instanceof List) {
            result.addAll((List<Map<String, Object>>) addedUsers);
        }

        // 尝试 users
        if (result.isEmpty()) {
            Object users = event.get("users");
            if (users instanceof List) {
                result.addAll((List<Map<String, Object>>) users);
            }
        }

        // 尝试从 operators 提取（操作人 = 被邀请人，部分事件格式）
        if (result.isEmpty()) {
            Object operators = event.get("operators");
            if (operators instanceof List) {
                result.addAll((List<Map<String, Object>>) operators);
            }
        }

        return result;
    }

    /**
     * 解析成员姓名
     * 飞书事件中的成员信息格式：
     *   { "user_id": "...", "open_id": "...", "name": "..." }
     *   事件中通常不包含 name，需要通过 open_id 调用 API 获取
     */
    @SuppressWarnings("unchecked")
    private List<MemberInfo> resolveMemberNames(List<Map<String, Object>> members) {
        List<MemberInfo> result = new ArrayList<>();
        for (Map<String, Object> m : members) {
            String name = null;
            String openId = null;

            // 尝试直接获取 name
            name = (String) m.get("name");

            // 获取 open_id
            Object idObj = m.get("user_id");
            if (idObj instanceof Map) {
                Map<String, Object> idMap = (Map<String, Object>) idObj;
                openId = (String) idMap.get("open_id");
            }
            if (openId == null) {
                openId = (String) m.get("open_id");
            }

            // 如果事件中没有 name，通过 API 获取
            if ((name == null || name.isBlank()) && openId != null) {
                name = feishuClient.getUserName(openId);
            }

            if (name == null || name.isBlank()) {
                name = openId != null ? openId : "新成员";
            }

            result.add(new MemberInfo(openId, name));
        }
        return result;
    }

    /**
     * 检查是否在冷却期内
     */
    private boolean isInCooldown(String chatId) {
        Instant lastSent = cooldownRecord.get(chatId);
        if (lastSent == null) return false;

        long cooldownMs = welcomeConfig.getCooldown();
        Instant now = Instant.now();
        return lastSent.plusMillis(cooldownMs).isAfter(now);
    }

    /**
     * 延迟发送欢迎消息（给批量合并留出时间窗口）
     */
    private void scheduleWelcomeMessage(String chatId, PendingWelcome pending) {
        lowPriorityEventExecutor.execute(() -> {
            try {
                Thread.sleep(batchWindowMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // 从待发送队列中取出并移除
            PendingWelcome toSend = pendingWelcomeMap.remove(chatId);
            if (toSend == null || toSend.members.isEmpty()) {
                return;
            }

            // 更新冷却时间
            cooldownRecord.put(chatId, Instant.now());

            // 发送欢迎消息
            String message = buildWelcomeMessage(toSend.members);
            try {
                feishuClient.sendText(chatId, message);
                log.info("欢迎消息已发送: chatId={}, 人数={}", chatId, toSend.members.size());
            } catch (Exception e) {
                log.error("发送欢迎消息失败: chatId={}", chatId, e);
            }
        });
    }

    /**
     * 构建欢迎消息
     */
    private String buildWelcomeMessage(List<MemberInfo> members) {
        int count = members.size();
        String template = welcomeConfig.getTemplate();
        String batchTemplate = welcomeConfig.getBatchTemplate();
        int batchThreshold = welcomeConfig.getBatchThreshold();

        if (count >= batchThreshold && batchThreshold > 1) {
            // 使用批量模板
            String names = members.stream()
                    .map(m -> m.name())
                    .limit(10)  // 最多显示 10 人
                    .collect(java.util.stream.Collectors.joining("、"));
            if (count > 10) {
                names += " 等 " + count + " 人";
            }
            return batchTemplate
                    .replace("{{names}}", names)
                    .replace("{{count}}", String.valueOf(count))
                    .replace("{{chat}}", "");
        } else {
            // 单人模板
            String name = members.get(0).name();
            return template
                    .replace("{{name}}", name)
                    .replace("{{count}}", String.valueOf(count))
                    .replace("{{chat}}", "");
        }
    }

    /**
     * 成员信息
     * @param openId Open ID（可用于 @ 成员，需要发送卡片消息）
     * @param name   显示名称
     */
    private record MemberInfo(String openId, String name) {}

    /**
     * 待发送的欢迎消息（用于批量合并）
     */
    private static class PendingWelcome {
        final List<MemberInfo> members = new ArrayList<>();

        PendingWelcome(List<MemberInfo> initial) {
            this.members.addAll(initial);
        }
    }
}
