/*
对应飞书发来的整个 JSON，是所有数据的“根对象”
一句话：“飞书消息的整体包裹”
 */


package com.example.IntelligentRobot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FeishuCallback {
    private String schema;     //表示xx？的版本号
    private FeishuHeader header;
    private FeishuEvent event;
    private String challenge;

}
