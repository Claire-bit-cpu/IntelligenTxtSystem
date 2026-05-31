package com.example.IntelligentRobot.feishu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class FeishuMessageService {

    private static final Logger log = LoggerFactory.getLogger(FeishuMessageService.class);

    @Autowired
    private FeishuProperties feishu;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    public void sendTextToGroup(String text) {
        sendTextToGroup(feishu.getChatId(), text);
    }

    public void sendTextToGroup(String chatId, String text) {
        if (chatId == null || chatId.isBlank()) {
            log.warn("chatId 为空，跳过发送消息: text={}", text);
            return;
        }

        String token = getTenantAccessToken();

        // 使用 ObjectMapper 正确构建 JSON，自动处理特殊字符转义
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("receive_id", chatId);
        bodyMap.put("msg_type", "text");

        Map<String, String> contentMap = new HashMap<>();
        contentMap.put("text", text);
        try {
            bodyMap.put("content", objectMapper.writeValueAsString(contentMap));
        } catch (JsonProcessingException e) {
            log.error("构建消息内容失败", e);
            return;
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(bodyMap);
        } catch (JsonProcessingException e) {
            log.error("构建请求体失败", e);
            return;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            restTemplate.postForEntity(
                    "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id",
                    new HttpEntity<>(body, headers),
                    String.class
            );
            log.info("飞书消息发送成功: chatId={}", chatId);
        } catch (Exception e) {
            log.error("发送飞书消息失败: chatId={}", chatId, e);
        }
    }

    private String getTenantAccessToken() {
        String body = """
        {
          "app_id": "%s",
          "app_secret": "%s"
        }
        """.formatted(feishu.getAppId(), feishu.getAppSecret());

        Map<?,?> resp = restTemplate.postForObject(
                "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal/",
                new HttpEntity<>(body, jsonHeader()),
                Map.class
        );

        return (String) resp.get("tenant_access_token");
    }

    private HttpHeaders jsonHeader() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
