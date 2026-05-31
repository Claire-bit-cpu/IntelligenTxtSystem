package com.example.IntelligentRobot.service;

import com.example.IntelligentRobot.client.QwenClient;
import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.dto.FeishuSender;
import com.example.IntelligentRobot.dto.MessageContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 智能理解服务
 * 对@机器人但非/开头的自然语言消息，调用LLM识别意图后分类处理
 *
 * 三类意图：
 * - command：执行命令
 * - chat：直接回复对话
 * - clarify：追问（信息不足）
 */
@Service
public class AiUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(AiUnderstandingService.class);

    /**
     * Redis Key 前缀：对话历史
     * Key 格式：ctx:history:{chatId}:{userId}
     * Value：Redis List，每个元素是一条消息的 JSON
     * TTL：5分钟（对话结束5分钟后自动清理，避免数据长久占用内存）
     */
    private static final String HISTORY_PREFIX = "ctx:history:";
    private static final long HISTORY_TTL_MINUTES = 5;
    private static final int MAX_HISTORY = 50;

    private final QwenClient qwenClient;
    private final CommandRegistry commandRegistry;
    private final ContextManager contextManager;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${ai.enabled:true}")
    private boolean aiEnabled;

    @Value("${ai.system-prompt:}")
    private String systemPrompt;

    public AiUnderstandingService(QwenClient qwenClient,
                                 CommandRegistry commandRegistry,
                                 ContextManager contextManager,
                                 StringRedisTemplate stringRedisTemplate,
                                 ObjectMapper objectMapper) {
        this.qwenClient = qwenClient;
        this.commandRegistry = commandRegistry;
        this.contextManager = contextManager;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        log.info("AI 智能理解服务初始化完成");
    }

    /**
     * 处理自然语言消息（Level 4）
     * @param text     消息文本（已移除@机器人占位符）
     * @param sender   发送者信息
     * @param chatId   群聊/私聊 ID
     * @param mentions 被@的成员列表
     * @return 处理结果，null 表示无法处理
     */
    public String processNaturalLanguage(String text,
                                        FeishuSender sender,
                                        String chatId,
                                        List<MessageContent.Mention> mentions) {
        if (!aiEnabled) {
            log.debug("AI 理解功能未启用，跳过处理");
            return null;
        }

        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        String userId = sender.getId();  // 使用 getId() 获取用户ID
        String trimmedText = text.trim();

        log.info("Level 4：AI 理解自然语言消息: user={}, text={}", userId, trimmedText);

        try {
            // 1. 构建 AI 所需的完整上下文
            String aiContext = buildContextForAi(userId, chatId);

            // 2. 调用 LLM 进行意图识别
            String intentJson = recognizeIntent(trimmedText, aiContext);

            // 3. 解析意图
            IntentResult intent = parseIntentResult(intentJson);

            log.info("【意图识别结果】type={}, command={}, args={}, reason={}, reply={}",
                    intent.getType(), intent.getCommand(), intent.getArgs(), 
                    intent.getReason(), intent.getReply());

            // 4. 根据意图类型处理
            String result = handleIntent(intent, trimmedText, sender, chatId, mentions);
            
            log.info("【意图处理完成】type={}, result={}", 
                    intent.getType(), 
                    result != null ? result.substring(0, Math.min(100, result.length())) : "null");
            
            return result;

        } catch (Exception e) {
            log.error("AI 理解处理失败", e);
            return "⚠️ AI 理解服务暂时不可用，请使用 / 开头的指令";
        }
    }

    /**
     * 构建 AI 所需的完整上下文
     * 包含：对话历史、用户全局参数、局部上下文
     */
    private String buildContextForAi(String userId, String chatId) {
        StringBuilder sb = new StringBuilder();

        // 1. 对话历史（最近 10 条）
        List<String> history = getConversationHistory(chatId, userId, 10);
        if (!history.isEmpty()) {
            sb.append("【对话历史】（最近10条）\n");
            for (String h : history) {
                sb.append(h).append("\n");
            }
            sb.append("\n");
        }

        // 2. 用户全局参数
        Map<String, Object> globalParams = contextManager.getGlobalParams(userId);
        if (!globalParams.isEmpty()) {
            sb.append("【用户全局参数】\n");
            globalParams.forEach((k, v) -> sb.append(k).append("=").append(v).append("\n"));
            sb.append("\n");
        }

        // 3. 用户局部上下文（遍历所有类型）
        // 这里简单处理：只展示活跃的上下文类型
        sb.append("【可用指令列表】\n");
        commandRegistry.getAllCommandNames().forEach(cmd -> sb.append("/").append(cmd).append("\n"));

        return sb.toString();
    }

    /**
     * 调用 LLM 进行意图识别
     * @return JSON 字符串，包含 type/command/args/reason 字段
     */
    private String recognizeIntent(String userMessage, String context) throws Exception {
        String prompt = buildIntentPrompt(userMessage, context);

        log.debug("意图识别 Prompt:\n{}", prompt);

        // 调用通义千问，要求返回 JSON
        String response = qwenClient.answerQuestion(prompt);

        log.debug("意图识别响应:\n{}", response);

        // 提取 JSON（可能包含在markdown代码块中）
        return extractJsonFromResponse(response);
    }

    /**
     * 构建意图识别的 Prompt
     */
    private String buildIntentPrompt(String userMessage, String context) {
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            return String.format("""
                    %s
                    
                    【当前用户消息】
                    %s
                    
                    【上下文信息】
                    %s
                    
                    请严格按照上述 JSON 格式返回意图识别结果。
                    """, systemPrompt, userMessage, context);
        }

        // 默认 Prompt（严格版本）
        return String.format("""
                你是一个智能助手，负责理解用户的自然语言消息，并识别其意图。
                
                你的任务是分析用户消息，判断用户想要执行什么操作，然后返回 JSON 格式的结果。
                
                【可用指令列表】
                /help - 查看帮助
                /weather - 查询天气（支持上下文感知）
                /translate - 中英互译
                /schedule - 创建日程（用法：/schedule 2026-05-30 15:00 会议名）
                /updateschedule - 修改日程（用法：/updateschedule 17:00 或 /updateschedule 2026-05-30 17:00）
                /ai - AI 智能问答
                /search - 搜索文档（/search 关键词，管理员可用 /search sync 同步索引）
                /repo - 查看 GitHub 仓库信息
                /pr - 查看 GitHub PR 详情
                /createbranch - 创建 Git 分支
                /gitlog - 查看 Git 提交日志
                /gitdiff - 查看 Git 提交差异
                /review - 代码审查
                /mergestatus - 查看 PR 状态
                /github - GitHub Actions 管理
                /gitlab - GitLab CI/CD 管理
                /deploy - 触发部署
                /jira - JIRA 任务管理
                /monitor - 服务监控
                /ping - Ping 检测
                /uptime - 查看系统运行时间
                /myid - 获取用户 ID
                /group - 创建群组
                
                【意图类型】
                1. command - 用户想执行某个指令
                2. chat - 用户只是闲聊或提问，不需要执行指令
                3. clarify - 信息不足，需要追问
                
                【重要规则 —— 严格遵守，不得违反】
                
                规则1：【必须识别为 command 的情况】
                   - 用户说"修改日程"、"改日程"、"修改会议"、"改会议" → command: updateschedule
                   - 用户说"创建日程"、"新建日程"、"添加日程" → command: schedule
                   - 用户说"帮我把日程改到X点"、"把会议改到下午X点" → command: updateschedule
                   - 用户说"查看仓库"、"查看PR"、"创建分支"、"查看日志"等 → 对应 command
                   - 用户消息包含动词："修改"、"改"、"创建"、"新建"、"添加"、"查看"、"显示"、"触发"、"启动"、"运行" → 优先识别为 command
                
                规则2：【绝对禁止】绝不能将 command 类型识别为 chat 类型！
                   - 如果用户明显想执行某个操作（修改、创建、查看、触发等），type 必须是 command
                   - 绝不能自己编造执行结果！你只是意图识别器，不是执行器！
                   - 错误示例："用户说修改日程，我识别为chat并回复'已修改'" → 这是严重错误！
                   - 正确做法：识别为 command: updateschedule，让系统去真正执行
                
                规则3：【参数提取】对于 updateschedule 指令：
                   - 从用户消息中提取时间（如"下午18:00" → "18:00"）
                   - 如果有具体日期，格式为"2026-05-30 18:00"
                   - 只有时间没有日期时，只返回时间（如"18:00"）
                
                规则4：【自我检查】在返回 JSON 之前，必须依次回答以下问题：
                   Q1：用户是否想执行某个操作？如果是 → type 必须是 command
                   Q2：用户消息是否包含操作动词（修改/创建/查看/触发等）？如果是 → type 必须是 command
                   Q3：我是否把 command 误判为 chat 了？如果是 → 立即纠正为 command
                   Q4：如果不确定，倾向于识别为 command，而不是 chat！
                
                【输出格式】
                必须严格按照以下 JSON 格式返回（不要有任何其他内容，不要有markdown代码块标记）：
                {
                  "type": "command|chat|clarify",
                  "command": "指令名（仅 command 类型需要，如 updateschedule、schedule）",
                  "args": "指令参数（仅 command 类型需要，如 18:00）",
                  "reason": "简要说明判断依据（10字以内）",
                  "reply": "直接回复内容（仅 chat/clarify 类型需要）"
                }
                
                【当前用户消息】
                %s
                
                【上下文信息】
                %s
                
                【最后确认】
                请再次检查你的答案：
                1. type 字段是否正确？是否应该是 command 而不是 chat？
                2. 如果用户想执行操作，type 必须是 command！
                3. 你只是意图识别器，不要编造执行结果！
                
                请严格按照上述 JSON 格式返回。
                """, userMessage, context);
    }

    /**
     * 从 LLM 响应中提取 JSON 字符串
     */
    private String extractJsonFromResponse(String response) {
        if (response == null) return "{}";

        // 移除 markdown 代码块标记
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        // 确保是 JSON 对象
        if (!cleaned.startsWith("{")) {
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
        }

        return cleaned;
    }

    /**
     * 解析意图识别结果
     */
    private IntentResult parseIntentResult(String json) {
        try {
            IntentResult result = objectMapper.readValue(json, IntentResult.class);
            if (result.getType() == null) {
                result.setType("chat"); // 默认视为闲聊
            }
            return result;
        } catch (Exception e) {
            log.warn("解析意图 JSON 失败: {}", e.getMessage());
            // 返回默认的 chat 意图
            IntentResult fallback = new IntentResult();
            fallback.setType("chat");
            fallback.setReply("抱歉，我没太理解您的意思，能再说一遍吗？");
            return fallback;
        }
    }

    /**
     * 根据意图类型处理
     */
    private String handleIntent(IntentResult intent,
                                String originalText,
                                FeishuSender sender,
                                String chatId,
                                List<MessageContent.Mention> mentions) {
        String type = intent.getType();

        switch (type) {
            case "command":
                return handleCommandIntent(intent, originalText, sender, chatId, mentions);

            case "chat":
                return handleChatIntent(intent, originalText);

            case "clarify":
                return handleClarifyIntent(intent);

            default:
                log.warn("未知意图类型: {}", type);
                return "抱歉，我不知道该怎么处理您的消息";
        }
    }

    /**
     * 处理 command 意图：执行指令
     */
    private String handleCommandIntent(IntentResult intent,
                                       String originalText,
                                       FeishuSender sender,
                                       String chatId,
                                       List<MessageContent.Mention> mentions) {
        String commandName = intent.getCommand();
        String args = intent.getArgs();

        if (commandName == null || commandName.isEmpty()) {
            log.warn("【command意图】未指定指令名，intent={}", intent);
            return "抱歉，我没理解您想执行什么指令，请明确告诉我";
        }

        // 检查指令是否存在
        if (!commandRegistry.hasCommand(commandName)) {
            log.warn("【command意图】指令不存在: /{}, 可用指令={}", commandName, commandRegistry.getAllCommandNames());
            return String.format("抱歉，我没有 /%s 这个指令。输入 /help 查看可用指令", commandName);
        }

        log.info("【command意图】开始执行指令: /{} {}", commandName, args);

        // 构造 CommandContext 并执行
        CommandContext context = new CommandContext();
        context.setCommandName(commandName);
        context.setArgs(args != null ? args : "");
        context.setSender(sender);
        context.setChatId(chatId);
        context.setRawMessage(originalText);
        context.setMentions(mentions);

        try {
            Object result = commandRegistry.execute(commandName, context);
            log.info("【command意图】指令执行完成: /{} {}, result={}", 
                    commandName, args, 
                    result != null ? result.toString().substring(0, Math.min(100, result.toString().length())) : "null");
            return result != null ? result.toString() : null;
        } catch (Exception e) {
            log.error("【command意图】执行指令失败: /" + commandName, e);
            return "⚠️ 执行指令失败：" + e.getMessage();
        }
    }

    /**
     * 处理 chat 意图：直接回复
     */
    private String handleChatIntent(IntentResult intent, String originalText) {
        String reply = intent.getReply();
        if (reply != null && !reply.isEmpty()) {
            // 【安全检测】检查 reply 是否包含虚假的执行结果
            // 如果 AI 谎称执行了某个操作（如"已修改"、"已更新"），则拒绝这个回复
            if (containsFakeExecution(reply)) {
                log.warn("【安全检测】AI 生成了虚假执行结果，已拒绝。reply={}", reply);
                return "⚠️ 我检测到您的消息可能是想执行某个操作，但我没有真正执行它。\n\n" +
                       "请使用 / 开头的指令来执行操作，例如：\n" +
                       "• /updateschedule 18:00 - 修改日程\n" +
                       "• /schedule 2026-05-30 15:00 会议 - 创建日程\n\n" +
                       "💡 输入 /help 查看所有可用指令";
            }
            return reply;
        }

        // 没有 reply 字段，调用 AI 生成回复
        try {
            String aiReply = qwenClient.answerQuestion(originalText);
            
            // 【安全检测】检查 AI 生成的回复是否包含虚假执行结果
            if (containsFakeExecution(aiReply)) {
                log.warn("【安全检测】AI 生成的回复包含虚假执行结果，已拒绝。aiReply={}", aiReply);
                return "⚠️ 我无法执行您的请求，请使用 / 开头的指令。\n\n" +
                       "💡 输入 /help 查看所有可用指令";
            }
            
            return aiReply;
        } catch (Exception e) {
            log.warn("AI 生成回复失败", e);
            return "抱歉，我暂时无法回复您的消息";
        }
    }

    /**
     * 检测文本是否包含虚假的执行结果
     * 如果 AI 谎称执行了某个操作（如"已修改"、"已更新"），返回 true
     */
    private boolean containsFakeExecution(String text) {
        if (text == null) return false;
        
        String[] fakeKeywords = {
            "已修改", "已更新", "已创建", "已删除", "已取消", "已更改",
            "修改成功", "更新成功", "创建成功", "删除成功",
            "日程已更新", "日程已修改", "任务已创建",
            "已将", "已经修改", "已经更新", "已经创建"
        };
        
        for (String keyword : fakeKeywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 处理 clarify 意图：追问
     */
    private String handleClarifyIntent(IntentResult intent) {
        String reply = intent.getReply();
        if (reply != null && !reply.isEmpty()) {
            return reply;
        }
        return "抱歉，我没太理解您的意思，能再说详细一点吗？";
    }

    /**
     * 获取对话历史
     * @param chatId  群聊/私聊 ID
     * @param userId  用户 ID
     * @param limit   返回最近 N 条
     * @return 对话历史列表（格式："用户：xxx" 或 "机器人：xxx"）
     */
    public List<String> getConversationHistory(String chatId, String userId, int limit) {
        String key = HISTORY_PREFIX + chatId + ":" + userId;

        try {
            // Redis List：lrange 获取最近 limit 条
            List<String> rawHistory = stringRedisTemplate.opsForList().range(key, -limit, -1);
            if (rawHistory == null || rawHistory.isEmpty()) {
                return new ArrayList<>();
            }

            // 反转顺序（Redis 存储是先进的在前面，我们要最新的在前面）
            List<String> result = new ArrayList<>();
            for (int i = rawHistory.size() - 1; i >= 0; i--) {
                result.add(rawHistory.get(i));
            }

            return result;

        } catch (Exception e) {
            log.warn("获取对话历史失败: chatId={}, userId={}", chatId, userId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 保存对话历史
     * @param chatId     群聊/私聊 ID
     * @param userId     用户 ID
     * @param userMessage 用户消息
     * @param botReply    机器人回复
     */
    public void saveConversationHistory(String chatId, String userId,
                                       String userMessage, String botReply) {
        String key = HISTORY_PREFIX + chatId + ":" + userId;

        try {
            // 构造历史记录
            String userRecord = "用户：" + userMessage;
            String botRecord = "机器人：" + botReply;

            // 写入 Redis List
            stringRedisTemplate.opsForList().rightPush(key, userRecord);
            stringRedisTemplate.opsForList().rightPush(key, botRecord);

            // 控制长度：只保留最近 MAX_HISTORY 条
            Long size = stringRedisTemplate.opsForList().size(key);
            if (size != null && size > MAX_HISTORY * 2) { // 每条对话有2行
                stringRedisTemplate.opsForList().trim(key, -MAX_HISTORY * 2, -1);
            }

            // 设置 TTL（5分钟，用户连续对话时会自动续期）
            stringRedisTemplate.expire(key, HISTORY_TTL_MINUTES, java.util.concurrent.TimeUnit.MINUTES);

        } catch (Exception e) {
            log.warn("保存对话历史失败: chatId={}, userId={}", chatId, userId, e);
        }
    }

    /**
     * 清空对话历史
     */
    public void clearConversationHistory(String chatId, String userId) {
        String key = HISTORY_PREFIX + chatId + ":" + userId;
        stringRedisTemplate.delete(key);
        log.debug("清空对话历史: chatId={}, userId={}", chatId, userId);
    }

    /**
     * 意图识别结果 DTO
     */
    public static class IntentResult {
        private String type;    // command | chat | clarify
        private String command; // 指令名（command 类型）
        private String args;    // 指令参数（command 类型）
        private String reason;  // 判断依据
        private String reply;   // 回复内容（chat/clarify 类型）

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }

        public String getArgs() { return args; }
        public void setArgs(String args) { this.args = args; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }

        public String getReply() { return reply; }
        public void setReply(String reply) { this.reply = reply; }
    }
}
