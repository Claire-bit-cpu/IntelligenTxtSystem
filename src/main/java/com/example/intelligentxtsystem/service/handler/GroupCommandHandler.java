package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.annotation.Command;
import com.example.intelligentxtsystem.client.FeishuClient;
import com.example.intelligentxtsystem.dto.CommandContext;
import com.example.intelligentxtsystem.dto.MessageContent;
import com.example.intelligentxtsystem.service.ConfirmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 群组指令处理器（新框架版本）
 * 指令格式：/group <群名> @成员1 @成员2 ...
 * 示例：/group 项目组 @小张 @小王
 *
 * 成员校验逻辑：
 * 飞书消息的 mentions 字段只包含"真实存在的被 @ 成员"。
 * 如果用户在消息中 @ 了一个不存在的成员，飞书不会在 mentions 中生成对应条目。
 * 因此，通过对比 text 中的 @名称 和 mentions 列表，可以检测出"不存在的成员"。
 */
@Component
public class GroupCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(GroupCommandHandler.class);

    /**
     * 匹配 text 中的 @名称（如 @小张、@_user_1）
     */
    private static final Pattern MENTION_PATTERN = Pattern.compile("@(\\S+)");

    private final FeishuClient feishuClient;
    private final ConfirmService confirmService;

    @Autowired(required = false)
    public GroupCommandHandler(FeishuClient feishuClient, ConfirmService confirmService) {
        this.feishuClient = feishuClient;
        this.confirmService = confirmService;
    }

    @Command(
        name = "group",
        description = "创建飞书群组并添加成员（需二次确认）",
        permissionLevel = "ADMIN",
        usage = "/group <群名> [@成员1 @成员2 ...]"
    )
    public String handle(CommandContext context) {
        String args = context.getArgs().trim();
        String openId = context.getSender() != null ? context.getSender().getOpenId() : null;
        String chatId = context.getChatId();
        List<MessageContent.Mention> mentions = context.getMentions();

        // 如果已确认，直接执行建群
        if (context.isConfirmed()) {
            return executeCreateGroup(context, args, openId, mentions);
        }

        // 从 args 中提取群名（第一个 @_user_ 占位符之前的部分）
        String groupName = extractGroupName(args);

        if (groupName.isEmpty()) {
            return """
                    ❌ 用法：/group <群名> [@成员1 @成员2 ...]

                    📋 示例：
                    /group 项目组
                    /group 项目组 @小张 @小王
                    建群 前端开发群 @小李

                    💡 群名建议简洁明了
                    """;
        }

        if (groupName.length() > 50) {
            return "⚠️ 群名长度不能超过50个字符";
        }

        // 二次确认：存储待确认操作
        if (confirmService != null) {
            String memberNames = mentions != null ? mentions.stream()
                    .map(m -> m.getName() != null ? m.getName() : m.getKey())
                    .collect(java.util.stream.Collectors.joining("、")) : "";
            String summary = String.format("群名：%s，成员：%s", groupName, memberNames);
            String token = confirmService.storePendingAction(openId, chatId, "group", args, summary);
            return String.format("""
                    ⚠️ 敏感操作确认

                    📦 操作：创建飞书群组
                    📋 群名：%s
                    👥 成员：%s
                    👤 操作者：%s

                    ❗ 请输入以下命令确认创建：
                    `/group %s --confirm %s`

                    ⏰ 确认令牌有效期：5 分钟
                    💡 如需取消，请忽略此消息
                    """, groupName, memberNames.isEmpty() ? "（仅创建者）" : memberNames, maskOpenId(openId), args, token);
        }

        // Redis 不可用，直接执行（降级）
        log.warn("ConfirmService 不可用，跳过二次确认，直接执行建群");
        return executeCreateGroup(context, args, openId, mentions);
    }

    /**
     * 实际执行创建群组（二次确认后调用）
     */
    private String executeCreateGroup(CommandContext context, String args, String openId,
                                     List<MessageContent.Mention> mentions) {
        String groupName = extractGroupName(args);

        // 收集需要加入群的成员 openId 列表（包含创建者）
        List<String> memberOpenIds = new ArrayList<>();
        if (openId != null && !openId.isEmpty()) {
            memberOpenIds.add(openId);
        }

        // 处理 @ 的成员（过滤掉 bot 类型，只保留真实用户）
        if (mentions != null && !mentions.isEmpty()) {
            for (MessageContent.Mention mention : mentions) {
                String mentionId = mention.getId();
                String mentionedType = mention.getMentionedType();
                // 跳过 bot 和 chat 类型，只处理 user
                if ("bot".equals(mentionedType) || "chat".equals(mentionedType)) {
                    continue;
                }
                if (mentionId == null || mentionId.isEmpty()) {
                    continue;
                }
                // 避免重复添加创建者
                if (!memberOpenIds.contains(mentionId)) {
                    memberOpenIds.add(mentionId);
                }
            }
        }

        // 校验：检测 text 中被 @ 但 mentions 中不存在的成员（即不存在的成员）
        List<String> notFoundMembers = findNotFoundMembers(args, mentions);
        if (!notFoundMembers.isEmpty()) {
            return String.format(
                    "❌ 创建群组失败\n\n以下成员不存在或无权限访问：%s\n\n请确认成员姓名是否正确，或该成员是否已加入企业。",
                    formatList(notFoundMembers)
            );
        }

        log.info("创建群组: 群名={}, 成员数={}, 成员IDs={}", groupName, memberOpenIds.size(), memberOpenIds);

        String result = feishuClient.createGroup(groupName, memberOpenIds);

        if ("success".equals(result)) {
            StringBuilder sb = new StringBuilder();
            sb.append("✅ 群组创建成功！\n\n");
            sb.append("📋 群名：").append(groupName).append("\n");
            if (mentions != null && !mentions.isEmpty()) {
                sb.append("👥 已添加成员：");
                List<String> names = new ArrayList<>();
                for (MessageContent.Mention m : mentions) {
                    names.add(m.getName() != null ? m.getName() : m.getKey());
                }
                sb.append(String.join("、", names)).append("\n");
            }
            sb.append("\n💡 请在飞书中刷新查看新群组");
            return sb.toString();
        } else {
            return String.format("""
                        创建群组失败

                        📋 群名：%s
                        📌 原因：%s

                        请检查飞书应用权限后重试
                        """, groupName, result);
        }
    }

    /**
     * 脱敏 OpenId（日志用）
     */
    private String maskOpenId(String openId) {
        if (openId == null || openId.length() < 8) return "***";
        return openId.substring(0, 4) + "***" + openId.substring(openId.length() - 4);
    }

    /**
     * 从参数中提取群名
     * 飞书消息的 text 中，@ 成员会被替换为 @_user_1 这样的占位符，
     * 如 "/group 项目组 @_user_1 @_user_2"，
     * 群名是第一个占位符之前的部分。
     */
    private String extractGroupName(String args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        // 找到第一个 @_user_ 占位符的位置
        int placeholderIndex = args.indexOf("@_user_");
        if (placeholderIndex >= 0) {
            return args.substring(0, placeholderIndex).trim();
        }
        // 如果没有占位符，整个 args 就是群名
        return args.trim();
    }

    /**
     * 找出 text 中被 @ 但 mentions 中不存在的成员名称
     * （即飞书无法解析的成员，说明该成员不存在或无权限）
     *
     * 逻辑：
     * 1. 从原始消息 text 中提取所有 @xxx 名称
     * 2. 收集 mentions 中所有已知的 key（@_user_1 等）
     * 3. 如果 text 中的 @xxx 不以 @_user_ 开头且不在 mentions 的 name 中，
     *    则说明该成员不存在
     *
     * 注意：飞书在处理消息时，会把 @已知成员 替换成 @_user_1 占位符，
     * 并把对应信息放入 mentions。如果成员不存在，则不会生成占位符和 mention。
     * 因此，text 中如果还残留 @成员名（非 @_user_ 格式），说明该成员未被解析。
     */
    private List<String> findNotFoundMembers(String args, List<MessageContent.Mention> mentions) {
        List<String> notFound = new ArrayList<>();

        if (args == null || args.isEmpty()) {
            return notFound;
        }

        // 收集 mentions 中所有的 key（占位符）和 name（用户名）
        Set<String> mentionKeys = new HashSet<>();
        Set<String> mentionNames = new HashSet<>();
        if (mentions != null) {
            for (MessageContent.Mention m : mentions) {
                if (m.getKey() != null) mentionKeys.add(m.getKey());
                if (m.getName() != null) mentionNames.add(m.getName());
            }
        }

        // 在原始 text 中查找所有 @xxx
        // 注意：这里的 args 已经过处理（@机器人部分已移除），但 @成员 占位符仍在
        // 如果 text 中有 @小张 但飞书没生成 mention，说明小张不存在
        // 但这种情况在飞书的消息中实际上不会发生：
        //   如果成员存在，text 中的 @小张 会被替换成 @_user_1，并生成 mention
        //   如果成员不存在，text 中的 @小张 会保持原样
        // 所以：查找 text 中的 @xxx，如果 xxx 不是 _user_N 格式且不在 mentionNames 中，
        // 则说明该成员不存在。
        Matcher matcher = MENTION_PATTERN.matcher(args);
        while (matcher.find()) {
            String mentionText = matcher.group(1); // 去掉 @
            // 跳过已解析的占位符（@_user_N）
            if (mentionText.startsWith("_user_")) {
                continue;
            }
            // 如果不在已知的成员名称中，则认为不存在
            if (!mentionNames.contains(mentionText)) {
                notFound.add("@" + mentionText);
            }
        }

        return notFound;
    }

    private String formatList(List<String> items) {
        return String.join("、", items);
    }
}
