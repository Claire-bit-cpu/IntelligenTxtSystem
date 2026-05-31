package com.example.IntelligentRobot.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指令执行上下文
 * 包含执行指令所需的所有信息
 *
 * 新增：支持上下文感知（多轮对话）
 */
@Data
public class CommandContext {

    /**
     * 指令名称
     */
    private String commandName;

    /**
     * 指令参数（去除指令名后的剩余部分）
     */
    private String args;

    /**
     * 发送者信息
     */
    private FeishuSender sender;

    /**
     * 消息 ID
     */
    private String messageId;

    /**
     * 原始消息
     */
    private String rawMessage;

    /**
     * 群聊 ID
     */
    private String chatId;

    /**
     * 消息中被 @ 的成员列表（来自飞书消息的 mentions 字段）
     */
    private List<MessageContent.Mention> mentions;

    /**
     * 用户 ID（用于上下文管理）
     */
    private String userId;

    /**
     * 填充后的参数（上下文自动填充后的完整参数）
     * key: 参数名, value: 参数值
     */
    private Map<String, Object> filledParams;

    /**
     * 是否有上下文支持
     */
    private boolean contextSupported = false;

    /**
     * 上下文类型
     */
    private String contextType;

    /**
     * 任务 ID（用于状态跟踪）
     * 命令处理器可以通过此 ID 更新任务进度
     */
    private String taskId;

    /**
     * 是否已确认（二次确认机制）
     * handler 检查此字段，true 时跳过确认直接执行
     */
    private boolean confirmed = false;

    /**
     * 确认令牌（二次确认机制）
     * 首次调用时生成，用户回复 "确认 <token>" 时传入
     */
    private String confirmToken;

    /**
     * 获取参数数组（按空格分割）
     */
    public String[] getArgsArray() {
        if (args == null || args.trim().isEmpty()) {
            return new String[0];
        }
        return args.trim().split("\\s+");
    }

    /**
     * 获取指定位置的参数
     */
    public String getArg(int index) {
        String[] argsArray = getArgsArray();
        if (index >= 0 && index < argsArray.length) {
            return argsArray[index];
        }
        return null;
    }

    /**
     * 获取参数数量
     */
    public int getArgCount() {
        return getArgsArray().length;
    }

    /**
     * 获取填充后的参数（如果有的话）
     */
    public Map<String, Object> getFilledParams() {
        if (filledParams == null) {
            filledParams = new ConcurrentHashMap<>();
        }
        return filledParams;
    }

    /**
     * 设置填充参数
     */
    public void setFilledParam(String key, Object value) {
        getFilledParams().put(key, value);
    }

    /**
     * 获取填充参数
     */
    public Object getFilledParam(String key) {
        return getFilledParams().get(key);
    }

    /**
     * 检查是否有填充参数
     */
    public boolean hasFilledParam(String key) {
        return getFilledParams().containsKey(key) && getFilledParams().get(key) != null;
    }

    /**
     * 获取用户 ID（从 sender 中提取）
     */
    public String getUserId() {
        if (userId != null && !userId.isEmpty()) {
            return userId;
        }
        if (sender != null) {
            // 优先使用 user_id，如果没有则使用 open_id
            String senderUserId = sender.getOpenId();
            if (senderUserId != null && !senderUserId.isEmpty()) {
                return senderUserId;
            }
        }
        // 降级：使用 chatId + 默认用户
        return chatId != null ? chatId : "default";
    }
}
