package com.example.IntelligentRobot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FeishuHeader {

        @JsonProperty("event_type")
        private String eventType;
    }
