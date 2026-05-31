package com.example.IntelligentRobot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageContent {
    private String text;
    /**
     * 飞书消息中的 @mention 列表
     * 每个 mention 包含：key（占位符如 @_user_1）、id（用户 open_id）、name（用户名）
     */
    private List<Mention> mentions;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Mention {
        /**
         * 在 text 中的占位符，如 "@_user_1"
         */
        private String key;
        /**
         * 用户 ID（open_id 或 user_id）
         */
        private String id;
        /**
         * 用户姓名
         */
        private String name;
        /**
         * 提及类型：user / bot / chat
         */
        private String mentionedType;
    }
}
