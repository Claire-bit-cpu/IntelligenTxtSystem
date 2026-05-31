package com.example.IntelligentRobot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 飞书消息发送者 DTO
 * 用于解析飞书回调事件中的 sender 字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FeishuSender {
    
    /**
     * 发送者 ID 信息（嵌套对象）
     * 飞书回调格式：{"sender_id": {"open_id": "...", "user_id": "...", ...}}
     */
    private SenderId sender_id;
    
    /**
     * 发送者类型（可选，用于测试或手动构造对象）
     * 可能值："user", "bot", "chat" 等
     */
    private String senderType;

    /**
     * 内部静态类：发送者 ID 详情
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SenderId {
        private String open_id;
        private String user_id;
        private String union_id;
        private String id_type;
    }
    
    /**
     * 获取发送者的 open_id
     * 优先返回 open_id，如果为空则返回 user_id
     */
    public String getOpenId() {
        if (sender_id != null) {
            if (sender_id.getOpen_id() != null) {
                return sender_id.getOpen_id();
            }
            if (sender_id.getUser_id() != null) {
                return sender_id.getUser_id();
            }
        }
        return null;
    }

    /**
     * 获取发送者的 user_id
     */
    public String getUserId() {
        if (sender_id != null && sender_id.getUser_id() != null) {
            return sender_id.getUser_id();
        }
        return null;
    }

    /**
     * 获取发送者 ID（统一入口）
     * 优先使用 user_id，如果为空则使用 open_id
     */
    public String getId() {
        if (sender_id != null) {
            if (sender_id.getUser_id() != null) {
                return sender_id.getUser_id();
            }
            if (sender_id.getOpen_id() != null) {
                return sender_id.getOpen_id();
            }
        }
        return null;
    }
    
    /**
     * 手动设置发送者 ID（用于测试或手动构造对象）
     * @param senderId 发送者 ID（可以是 open_id 或 user_id）
     */
    public void setSenderId(String senderId) {
        if (this.sender_id == null) {
            this.sender_id = new SenderId();
        }
        // 优先设置为 user_id
        this.sender_id.setUser_id(senderId);
    }
    
    /**
     * 手动设置发送者类型（用于测试或手动构造对象）
     * @param senderType 发送者类型（"user", "bot", "chat" 等）
     */
    public void setSenderType(String senderType) {
        this.senderType = senderType;
    }
}
