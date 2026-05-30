package com.example.intelligentxtsystem.service;

import com.example.intelligentxtsystem.dto.CommandContext;
import com.example.intelligentxtsystem.dto.FeishuSender;
import com.example.intelligentxtsystem.dto.MessageContent;
import com.example.intelligentxtsystem.task.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 消息分发器（四级响应策略）
 * 
 * Level 1 (精确匹配)：/开头 + 指令存在 → 正常执行
 * Level 2 (模糊匹配)：/开头 + 指令不存在但相似度达标 → 提示"您是不是想说 /xxx？"
 * Level 3 (静默丢弃)：非/开头且未@机器人 → 返回 null（不回复）
 * Level 4 (AI 理解)：@机器人 + 非/开头 → 调用 AI 理解意图后处理
 */
@Service
public class MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);

    private final CommandRegistry commandRegistry;
    private final FuzzyMatcher fuzzyMatcher;
    private final AiUnderstandingService aiUnderstandingService;

    /**
     * 匹配 /指令名 或 指令名 的正则
     */
    private static final Pattern COMMAND_PATTERN = Pattern.compile("^(?:/)?(\\w+)(.*)$");

    /**
     * 无意义字符检测：连续重复字符或随机字符串（无有意义子串）
     */
    private static final Pattern NOISE_PATTERN = Pattern.compile("^[a-z]{5,}$");

    public MessageDispatcher(CommandRegistry commandRegistry,
                            FuzzyMatcher fuzzyMatcher,
                            AiUnderstandingService aiUnderstandingService) {
        this.commandRegistry = commandRegistry;
        this.fuzzyMatcher = fuzzyMatcher;
        this.aiUnderstandingService = aiUnderstandingService;
    }

    @PostConstruct
    public void init() {
        log.info("MessageDispatcher 初始化完成，已加载 {} 个指令",
                commandRegistry.getAllCommands().size());
    }

    /**
     * 分发消息（三级响应策略）
     * @param text   消息文本
     * @param sender 发送者信息
     * @param chatId 群聊 ID
     * @return 处理结果，null 表示静默丢弃
     */
    public String dispatch(String text, FeishuSender sender, String chatId) {
        return dispatch(text, sender, chatId, null, null);
    }

    /**
     * 分发消息（带 mentions，三级响应策略）
     * @param text     消息文本
     * @param sender   发送者信息
     * @param chatId   群聊 ID
     * @param mentions 被 @ 的成员列表（来自飞书消息的 mentions 字段）
     * @return 处理结果，null 表示静默丢弃
     */
    public String dispatch(String text, FeishuSender sender, String chatId,
                          java.util.List<MessageContent.Mention> mentions) {
        return dispatch(text, sender, chatId, mentions, null);
    }

    /**
     * 分发消息（带 taskId，支持细粒度状态更新）
     * @param text     消息文本
     * @param sender   发送者信息
     * @param chatId   群聊 ID
     * @param mentions 被 @ 的成员列表（来自飞书消息的 mentions 字段）
     * @param taskId   任务ID（用于状态跟踪，可为null）
     * @return 处理结果，null 表示静默丢弃
     */
    public String dispatch(String text, FeishuSender sender, String chatId,
                          java.util.List<MessageContent.Mention> mentions, String taskId) {
        if (text == null || text.trim().isEmpty()) {
            return null; // Level 3：空消息，静默丢弃
        }

        String trimmedText = text.trim();

        // 保存 taskId 到线程上下文（供命令处理器使用）
        if (taskId != null && !taskId.isEmpty()) {
            TaskContext.setTaskId(taskId);
        }

        // ===== Level 4：AI 智能理解（优先处理非/开头的消息）=====
        // 如果消息不以 / 开头，且是@机器人的消息，调用 AI 理解意图
        boolean isBotMentioned = mentions != null && mentions.stream()
                .anyMatch(m -> "bot".equals(m.getMentionedType()));
        
        if (!trimmedText.startsWith("/") && isBotMentioned) {
            log.info("Level 4：检测到@机器人的自然语言消息，调用 AI 理解");
            String aiResult = aiUnderstandingService.processNaturalLanguage(
                    trimmedText, sender, chatId, mentions);
            if (aiResult != null) {
                log.info("Level 4：AI 理解成功，返回结果");
                return aiResult;
            }
            // AI 无法理解时，返回友好提示（不再静默丢弃）
            return "🤖 我已收到您的消息，但暂时无法理解您的意图。\n\n💡 请使用 / 开头的指令，或输入 /help 查看可用指令";
        }

        // ===== Level 3：非@机器人的非指令消息，静默丢弃 =====
        if (!trimmedText.startsWith("/")) {
            log.debug("Level 3：非指令消息且未@机器人，静默丢弃: {}", trimmedText);
            TaskContext.clear();
            return null;
        }

        // 匹配指令
        Matcher matcher = COMMAND_PATTERN.matcher(trimmedText);
        if (!matcher.find()) {
            // 无法解析的结构（如纯符号），Level 3 静默丢弃
            log.debug("Level 3：无法解析的指令格式，静默丢弃: {}", trimmedText);
            return null;
        }

        String commandName = matcher.group(1).toLowerCase();
        String args = matcher.group(2).trim();

        // ===== Level 1：精确匹配 =====
        if (commandRegistry.hasCommand(commandName)) {
            log.info("Level 1：精确匹配 /{}", commandName);
            return executeCommand(commandName, args, sender, chatId, trimmedText, mentions, taskId);
        }

        // ===== Level 2：模糊匹配 =====
        List<FuzzyMatcher.MatchResult> suggestions = fuzzyMatcher.match(
                commandName, commandRegistry.getAllCommandNames());

        if (!suggestions.isEmpty()) {
            log.info("Level 2：模糊匹配 /{} → 建议: {}", commandName, suggestions);
            return generateFuzzySuggestion(commandName, suggestions);
        }

        // 无匹配建议：视为无效指令，返回未知指令提示（而非静默丢弃）
        log.debug("未知指令且无模糊匹配: /{}", commandName);
        return generateUnknownCommandHelp(commandName);
    }

    /**
     * 执行指令（无 mentions，兼容旧调用）
     */
    private String executeCommand(String commandName, String args,
                                 FeishuSender sender, String chatId, String rawMessage) {
        return executeCommand(commandName, args, sender, chatId, rawMessage, null, null);
    }

    /**
     * 执行指令（带 mentions，兼容旧调用）
     */
    private String executeCommand(String commandName, String args,
                                 FeishuSender sender, String chatId, String rawMessage,
                                 java.util.List<MessageContent.Mention> mentions) {
        return executeCommand(commandName, args, sender, chatId, rawMessage, mentions, null);
    }

    /**
     * 执行指令（带 mentions 和 taskId）
     */
    private String executeCommand(String commandName, String args,
                                 FeishuSender sender, String chatId, String rawMessage,
                                 java.util.List<MessageContent.Mention> mentions, String taskId) {
        CommandContext context = new CommandContext();
        context.setCommandName(commandName);
        context.setArgs(args);
        context.setSender(sender);
        context.setChatId(chatId);
        context.setRawMessage(rawMessage);
        context.setMentions(mentions);
        context.setTaskId(taskId); // 设置 taskId，供命令处理器使用

        try {
            log.info("执行指令: /{}，参数: {}，mentions: {}，taskId: {}", commandName, args,
                    mentions != null ? mentions.size() : 0, taskId);
            Object result = commandRegistry.execute(commandName, context);
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            log.error("执行指令失败: /{}，taskId: {}", commandName, taskId, e);
            return "❌ 执行指令失败: " + e.getMessage();
        } finally {
            // 清理线程上下文
            TaskContext.clear();
        }
    }

    /**
     * 生成模糊匹配提示
     * 例如："您是不是想说 /deploy？"
     */
    private String generateFuzzySuggestion(String input, List<FuzzyMatcher.MatchResult> suggestions) {
        if (suggestions.size() == 1) {
            String suggestedCmd = suggestions.get(0).commandName();
            return String.format(
                    "❓ 您是不是想说 /%s？\n\n💡 输入 /%s 执行该指令",
                    suggestedCmd, suggestedCmd
            );
        }

        // 多个建议
        StringBuilder sb = new StringBuilder();
        sb.append("❓ 未找到指令 /").append(input).append("，您是不是想说：\n\n");
        for (FuzzyMatcher.MatchResult r : suggestions) {
            sb.append(String.format("  • /%s\n", r.commandName()));
        }
        sb.append("\n💡 输入上述指令之一执行");
        return sb.toString();
    }

    /**
     * 生成未知指令的帮助信息
     */
    private String generateUnknownCommandHelp(String commandName) {
        return String.format(
                "❌ 未知指令: /%s\n\n💡 使用 /help 查看所有可用指令",
                commandName
        );
    }
}