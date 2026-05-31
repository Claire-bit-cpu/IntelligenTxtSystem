package com.example.IntelligentRobot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FeishuMessage {
    @JsonProperty("message_id")
    private String messageId;

    @JsonProperty("chat_id")
    private String chatId;

    @JsonProperty("message_type")
    private String messageType;

    private String content;
}