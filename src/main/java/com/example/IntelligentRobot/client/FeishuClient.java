/*
核心发送类,发送到飞书群中
 */

package com.example.IntelligentRobot.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class FeishuClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuClient.class);

    @Value("${feishu.app-id}")
    private String appId;

    @Value("${feishu.app-secret}")
    private String appSecret;

    @Value("${feishu.api-base-url}")
    private String apiBaseUrl;

    @Value("${feishu.token-buffer-seconds}")
    private int tokenBufferSeconds;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public FeishuClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    private String tenantAccessToken;
    private long expireTime = 0;

    /**
     * 获取 Tenant Access Token
     */
    private void ensureToken() {
        // 如果 Token 还没过期，直接返回
        if (tenantAccessToken != null && System.currentTimeMillis() < expireTime) {
            return;
        }

        log.info("正在获取飞书 token, appId={}, appSecret长度={}", appId, appSecret != null ? appSecret.length() : "null");
        String url = apiBaseUrl + "/auth/v3/tenant_access_token/internal";
        Map<String, String> body = Map.of(
                "app_id", appId,
                "app_secret", appSecret
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(url, body, Map.class);
        Map<String, Object> result = response.getBody();

        // 检查飞书 API 返回是否成功
        Object codeObj = result.get("code");
        if (codeObj != null && !"0".equals(String.valueOf(codeObj))) {
            String errorMsg = (String) result.get("msg");
            log.error("获取飞书 tenant_access_token 失败: code={}, msg={}", codeObj, errorMsg);
            throw new RuntimeException("获取飞书 token 失败: " + errorMsg);
        }

        tenantAccessToken = (String) result.get("tenant_access_token");
        if (tenantAccessToken == null) {
            log.error("飞书 API 返回数据中缺少 tenant_access_token: {}", result);
            throw new RuntimeException("飞书 API 返回数据中缺少 tenant_access_token");
        }

        Object expireObj = result.get("expire");
        if (expireObj == null) {
            log.warn("飞书 API 返回数据中缺少 expire 字段，使用默认值 7200 秒");
            expireObj = 7200; // 默认 2 小时
        }
        int expire = (expireObj instanceof Number) ? ((Number) expireObj).intValue() : Integer.parseInt(expireObj.toString());
        expireTime = System.currentTimeMillis() + expire * 1000L - tokenBufferSeconds * 1000L;
        log.info("飞书 tenant_access_token 获取成功，过期时间: {}", expireTime);
    }

    /**
     * 发送文本消息
     * @param receiveId 群ID (oc_xxxx) 或 用户OpenID (ou_xxxx)
     * @return 消息ID (message_id)，发送失败返回 null
     */
    public String sendText(String receiveId, String text) {
        ensureToken();

        // 规范化 receiveId：将 oc: 替换为 oc_（修复格式错误）
        if (receiveId != null && receiveId.startsWith("oc:")) {
            receiveId = "oc_" + receiveId.substring(3);
            log.warn("接收到格式错误的 chatId，已自动修复（oc: → oc_）: {}", receiveId);
        }

        // 根据 receiveId 格式自动判断类型
        String receiveIdType = receiveId.startsWith("oc_") ? "chat_id" : "open_id";
        String url = apiBaseUrl + "/im/v1/messages?receive_id_type=" + receiveIdType;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 飞书 API 要求 content 是 JSON 字符串，不是对象
        String content = "{\"text\":\"" + escapeJson(text) + "\"}";

        Map<String, Object> body = Map.of(
                "receive_id", receiveId,
                "msg_type", "text",
                "content", content
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            String responseBody = response.getBody();
            log.debug("sendText 响应: {}", responseBody);
            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
            if ("0".equals(String.valueOf(result.get("code")))) {
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data != null) {
                    String messageId = (String) data.get("message_id");
                    log.info("文本消息发送成功: receiveId={}, messageId={}", receiveId, messageId);
                    return messageId;
                }
            } else {
                log.error("文本消息发送失败: receiveId={}, 响应={}", receiveId, responseBody);
            }
        } catch (Exception e) {
            log.error("文本消息发送失败: receiveId={}", receiveId, e);
        }
        return null;
    }

    /**
     * 发送消息卡片（Interactive Card）
     * @param receiveId 接收人 open_id 或群 chat_id
     * @param cardJson   卡片 JSON 字符串（飞书消息卡片格式）
     * @return 消息ID (message_id)，发送失败返回 null
     */
    public String sendCard(String receiveId, String cardJson) {
        ensureToken();

        // 如果 receiveId 以 "oc_" 开头，则改用 chat_id 模式
        String receiveIdType = receiveId.startsWith("oc_") ? "chat_id" : "open_id";
        String url = apiBaseUrl + "/im/v1/messages?receive_id_type=" + receiveIdType;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "receive_id", receiveId,
                "msg_type", "interactive",
                "content", cardJson
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            String responseBody = response.getBody();
            log.debug("sendCard 响应: {}", responseBody);
            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
            if ("0".equals(String.valueOf(result.get("code")))) {
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data != null) {
                    String messageId = (String) data.get("message_id");
                    log.info("消息卡片发送成功: receiveId={}, messageId={}", receiveId, messageId);
                    return messageId;
                }
            }
            log.warn("消息卡片发送未返回 message_id: receiveId={}, response={}", receiveId, responseBody);
            return null;
        } catch (Exception e) {
            log.error("消息卡片发送失败: receiveId={}", receiveId, e);
            return null;
        }
    }

    /**
     * 更新已发送的消息（飞书 API: PUT /im/v1/messages/{message_id}）
     * 用于在飞书侧原地更新任务进度，而不是发送新消息
     *
     * @param messageId  要更新的消息ID（调用 sendText/sendCard 的返回值）
     * @param msgType    消息类型（"text" 或 "interactive"）
     * @param contentJson 新的消息内容（JSON 字符串，text类型或卡片）
     * @return true if success, false if edit limit reached or other failure
     */
    public boolean updateMessage(String messageId, String msgType, String contentJson) {
        if (messageId == null || messageId.isEmpty()) {
            log.warn("updateMessage 失败: messageId 为空");
            return false;
        }
        ensureToken();

        String url = apiBaseUrl + "/im/v1/messages/" + messageId;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 飞书 API 更新消息时，content 必须是双重序列化的 JSON 字符串
        // 即: {"msg_type":"interactive", "content": "{\"config\":...}"}
        // 使用 objectMapper 对 contentJson 进行转义，确保格式正确
        try {
            // 使用 Map 构建请求体，Jackson 会自动正确处理转义
            Map<String, Object> bodyMap = new java.util.HashMap<>();
            bodyMap.put("msg_type", msgType);
            bodyMap.put("content", contentJson);  // contentJson 是字符串，Jackson 会自动转义成 JSON 字符串
            String body = objectMapper.writeValueAsString(bodyMap);

            // 调试日志：打印请求体和 URL
            log.info("[DEBUG] updateMessage 请求 URL: {}", url);
            log.info("[DEBUG] updateMessage 请求体: {}", body);
            // 调试日志：打印请求体和 URL
            log.info("[DEBUG] updateMessage 请求 URL: {}", url);
            log.info("[DEBUG] updateMessage 请求体: {}", body);
            log.info("[DEBUG] updateMessage headers: Authorization=Bearer {}...", 
                tenantAccessToken != null && tenantAccessToken.length() > 10 ? 
                tenantAccessToken.substring(0, 10) + "..." : "null");

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            restTemplate.exchange(url, org.springframework.http.HttpMethod.PUT, entity, String.class);
            log.info("[DEBUG] 消息更新成功: messageId={}, msgType={}", messageId, msgType);
            return true;
        } catch (HttpClientErrorException e) {
            // 调试日志：打印完整错误响应
            String errorBody = e.getResponseBodyAsString();
            log.debug("[DEBUG] 消息更新失败 HTTP 状态码: {}", e.getStatusCode());
            log.debug("[DEBUG] 消息更新失败响应体: {}", errorBody);
            // 检查是否是编辑次数上限错误（230072）
            if (e.getStatusCode().value() == 400 && errorBody.contains("230072")) {
                log.warn("消息已达到编辑次数上限，需要发送新消息: messageId={}", messageId);
            } else {
                log.warn("消息更新失败: messageId={}, error={}", messageId, errorBody);
            }
            return false;
        } catch (Exception e) {
            // 调试日志：打印其他异常
            log.debug("[DEBUG] 消息更新失败（非HttpClientErrorException）: messageId={}", messageId, e);
            return false;
        }
    }

    /**
     * 构建审批结果消息卡片 JSON
     */
    public static String buildApprovalCard(String approvalName, String applicantName,
                                          String result, String comment, String detailUrl) {
        // result: "APPROVED" / "REJECTED"
        String resultText = "APPROVED".equalsIgnoreCase(result) ? "✅ 已通过" : "❌ 已拒绝";
        String resultColor = "APPROVED".equalsIgnoreCase(result) ? "green" : "red";

        return """
                {
                    "config": {"wide_screen_mode": true},
                    "header": {
                        "title": {"tag": "plain_text", "content": "审批结果通知"},
                        "template": "%s"
                    },
                    "elements": [
                        {"tag": "div", "text": {"tag": "lark_md", "content": "**审批名称：** %s"}},
                        {"tag": "div", "text": {"tag": "lark_md", "content": "**申请人：** %s"}},
                        {"tag": "div", "text": {"tag": "lark_md", "content": "**审批结果：** %s"}},
                        {"tag": "div", "text": {"tag": "lark_md", "content": "**审批意见：** %s"}},
                        {"tag": "action", "actions": [
                            {"tag": "button", "text": {"tag": "plain_text", "content": "查看详情"},
                             "url": "%s", "type": "default"}
                        ]}
                    ]
                }
                """.formatted(
                resultColor,
                escapeJson(approvalName),
                escapeJson(applicantName),
                resultText,
                escapeJson(comment != null ? comment : "无"),
                detailUrl != null ? detailUrl : ""
        );
    }

    /**
     * 带 Token 的 GET 请求（供其他 Service 调用飞书 API）
     */
    public String getWithToken(String url) {
        ensureToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, String.class).getBody();
    }

    private String calendarId;

    /**
     * 创建日历日程（创建到机器人自己的日历中，可选邀请用户参与）
     *
     * @param summary     日程标题
     * @param startTime   开始时间
     * @param endTime     结束时间（默认1小时后）
     * @param attendeeId  参与者 open_id（可选，为空则不邀请）
     * @return 创建结果
     */
    /**
     * 创建日历日程（创建到机器人自己的日历中，可选邀请用户参与）
     *
     * @param summary     日程标题
     * @param startTime   开始时间
     * @param endTime     结束时间（默认1小时后）
     * @param attendeeId  参与者 open_id（可选，为空则不邀请）
     * @return 创建成功返回 eventId，失败返回 null
     */
    public String createCalendarEventWithAttendee(String summary, LocalDateTime startTime, LocalDateTime endTime, String attendeeId) {
        ensureToken();

        // 获取机器人日历ID（如果还没有）
        if (calendarId == null) {
            calendarId = getPrimaryCalendarId();
            log.info("获取到的日历ID: {}", calendarId);
            if (calendarId == null) {
                log.error("获取日历ID失败，无法创建日程");
                return null;
            }
        }

        // 转换时间戳（飞书需要秒级时间戳）
        long startTimestamp = startTime.atZone(ZoneId.systemDefault()).toEpochSecond();
        long endTimestamp = endTime != null
                ? endTime.atZone(ZoneId.systemDefault()).toEpochSecond()
                : startTime.plusHours(1).atZone(ZoneId.systemDefault()).toEpochSecond();

        log.info("创建日程 - 标题: {}, 开始时间戳: {}, 结束时间戳: {}, 参与者: {}", summary, startTimestamp, endTimestamp, attendeeId);

        // 先创建日程（不带参与者）
        String eventId = createCalendarEventOnly(summary, startTimestamp, endTimestamp);
        
        if (eventId == null) {
            log.error("创建日程失败，eventId 为 null");
            return null;
        }

        log.info("日程创建成功，eventId: {}", eventId);

        // 如果有参与者，单独添加
        if (attendeeId != null && !attendeeId.isEmpty()) {
            String addResult = addAttendeeToEvent(eventId, attendeeId);
            if (!"success".equals(addResult)) {
                log.warn("添加参与者失败，但日程已创建: eventId={}, result={}", eventId, addResult);
            }
        }

        return eventId;
    }

    /**
     * 仅创建日程，不添加参与者
     */
    private String createCalendarEventOnly(String summary, long startTimestamp, long endTimestamp) {
        String url = apiBaseUrl + "/calendar/v4/calendars/" + calendarId + "/events";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "summary", summary,
                "start_time", Map.of(
                        "timestamp", String.valueOf(startTimestamp),
                        "timezone", "Asia/Shanghai"
                ),
                "end_time", Map.of(
                        "timestamp", String.valueOf(endTimestamp),
                        "timezone", "Asia/Shanghai"
                ),
                "location", Map.of(
                        "name", "飞书群"
                )
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            String responseBody = response.getBody();
            log.info("创建日程API响应: {}", responseBody);

            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                if (result != null && "0".equals(String.valueOf(result.get("code")))) {
                    Map<String, Object> data = (Map<String, Object>) result.get("data");
                    if (data != null) {
                        Map<String, Object> event = (Map<String, Object>) data.get("event");
                        if (event != null) {
                            return (String) event.get("event_id");
                        }
                    }
                }
                return null;
            }
            log.error("创建日程失败: {}", responseBody);
            return null;
        } catch (Exception e) {
            log.error("创建日程异常", e);
            return null;
        }
    }

    /**
     * 修改日历日程
     *
     * @param eventId     日程ID
     * @param summary     日程标题（可选，null则不修改）
     * @param startTime   开始时间（可选，null则不修改）
     * @param endTime     结束时间（可选，null则不修改）
     * @return 修改结果，"success"表示成功
     */
    public String updateCalendarEvent(String eventId, String summary, LocalDateTime startTime, LocalDateTime endTime) {
        ensureToken();

        if (calendarId == null) {
            calendarId = getPrimaryCalendarId();
            if (calendarId == null) {
                log.error("获取日历ID失败，无法修改日程");
                return "获取日历失败";
            }
        }

        String url = apiBaseUrl + "/calendar/v4/calendars/" + calendarId + "/events/" + eventId;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new java.util.HashMap<>();

        if (summary != null && !summary.isEmpty()) {
            body.put("summary", summary);
        }

        if (startTime != null) {
            long startTimestamp = startTime.atZone(ZoneId.systemDefault()).toEpochSecond();
            body.put("start_time", Map.of(
                    "timestamp", String.valueOf(startTimestamp),
                    "timezone", "Asia/Shanghai"
            ));
        }

        if (endTime != null) {
            long endTimestamp = endTime.atZone(ZoneId.systemDefault()).toEpochSecond();
            body.put("end_time", Map.of(
                    "timestamp", String.valueOf(endTimestamp),
                    "timezone", "Asia/Shanghai"
            ));
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            // 使用 PATCH 方法修改日程
            org.springframework.http.ResponseEntity<String> response = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.PATCH, entity, String.class);
            String responseBody = response.getBody();
            log.info("修改日程API响应: {}", responseBody);

            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                if (result != null && "0".equals(String.valueOf(result.get("code")))) {
                    return "success";
                }
                String errorMsg = String.valueOf(result.get("msg"));
                log.error("修改日程失败: code={}, msg={}", result.get("code"), errorMsg);
                return "修改失败: " + errorMsg;
            }
            return "HTTP错误: " + response.getStatusCode() + ", 响应: " + responseBody;
        } catch (Exception e) {
            log.error("修改日程异常 eventId={}", eventId, e);
            return "调用异常: " + e.getMessage();
        }
    }

    /**
     * 添加参与者到日程
     */
    private String addAttendeeToEvent(String eventId, String openId) {
        String url = apiBaseUrl + "/calendar/v4/calendars/" + calendarId + "/events/" + eventId + "/attendees";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "attendees", java.util.List.of(
                        Map.of(
                                "type", "user",
                                "user_id", openId
                        )
                ),
                "need_notification", true
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            String responseBody = response.getBody();
            log.info("添加参与者API响应: {}", responseBody);

            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                if (result != null && "0".equals(String.valueOf(result.get("code")))) {
                    return "success";
                }
                return "添加失败: " + result.get("msg");
            }
            return "HTTP错误: " + response.getStatusCode() + ", 响应: " + responseBody;
        } catch (Exception e) {
            log.error("添加参与者异常", e);
            return "调用异常: " + e.getMessage();
        }
    }

    /**
     * 获取应用的主日历ID
     */
    private String getPrimaryCalendarId() {
        String url = apiBaseUrl + "/calendar/v4/calendars";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> result = response.getBody();
                if (result != null && "0".equals(String.valueOf(result.get("code")))) {
                    Map<String, Object> data = (Map<String, Object>) result.get("data");
                    if (data != null && data.containsKey("calendar_list")) {
                        java.util.List<Map<String, Object>> calendars =
                                (java.util.List<Map<String, Object>>) data.get("calendar_list");
                        if (calendars != null && !calendars.isEmpty()) {
                            return (String) calendars.get(0).get("calendar_id");
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略异常
        }
        return null;
    }

    /**
     * 获取用户信息
     */
    @SuppressWarnings("unchecked")
    public String getUserId(String unionId) {
        ensureToken();

        String url = apiBaseUrl + "/contact/v3/users/" + unionId;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> result = response.getBody();
                if (result != null && "0".equals(String.valueOf(result.get("code")))) {
                    Map<String, Object> data = (Map<String, Object>) result.get("data");
                    Map<String, Object> user = (Map<String, Object>) data.get("user");
                    return (String) user.get("user_id");
                }
            }
        } catch (Exception e) {
            // 忽略异常
        }
        return null;
    }

    /**
     * 根据 open_id 获取用户姓名
     * @param openId 用户 open_id（格式：ou_xxxx）
     * @return 用户姓名，获取失败返回 openId 本身
     */
    @SuppressWarnings("unchecked")
    public String getUserName(String openId) {
        if (openId == null || openId.isBlank()) return "新成员";

        ensureToken();

        // 飞书 API：通过 open_id 查询用户，需要指定 user_id_type=open_id
        String url = apiBaseUrl + "/contact/v3/users/" + openId + "?user_id_type=open_id";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                Map<String, Object> result = response.getBody();
                if (result != null && "0".equals(String.valueOf(result.get("code")))) {
                    Map<String, Object> data = (Map<String, Object>) result.get("data");
                    Map<String, Object> user = (Map<String, Object>) data.get("user");
                    String name = (String) user.get("name");
                    return name != null && !name.isBlank() ? name : openId;
                }
            }
        } catch (Exception e) {
            log.warn("获取用户姓名失败: openId={}", openId, e);
        }
        return openId;
    }

    /**
     * 创建群组（批量加入成员）
     *
     * @param groupName     群组名称
     * @param memberOpenIds 成员 open_id 列表（包含创建者）
     * @return 创建结果（成功返回 "success"，失败返回原因）
     */
    @SuppressWarnings("unchecked")
    public String createGroup(String groupName, java.util.List<String> memberOpenIds) {
        ensureToken();

        String url = apiBaseUrl + "/im/v1/chats";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", groupName);
        body.put("chat_mode", "group");
        body.put("chat_type", "public");
        // 创建群时直接传入所有成员 open_id（避免单独调用 invite API 需要额外权限）
        if (memberOpenIds != null && !memberOpenIds.isEmpty()) {
            body.put("user_id_list", new java.util.ArrayList<>(memberOpenIds));
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            String responseBody = response.getBody();
            log.info("创建群组API响应: {}", responseBody);

            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

            String code = String.valueOf(result.get("code"));
            String msg = String.valueOf(result.get("msg"));

            if (!"0".equals(code)) {
                log.error("创建群组失败: code={}, msg={}", code, msg);
                return "创建失败: " + msg + " (code=" + code + ")";
            }

            Map<String, Object> data = (Map<String, Object>) result.get("data");
            if (data == null) {
                return "创建失败: 响应中缺少 data";
            }

            String chatId = (String) data.get("chat_id");
            if (chatId == null || chatId.isEmpty()) {
                return "创建失败: 响应中缺少 chat_id";
            }

            log.info("群组创建成功: chat_id={}, 群名={}, 成员数={}", chatId, groupName,
                    memberOpenIds != null ? memberOpenIds.size() : 0);

            return "success";
        } catch (Exception e) {
            log.error("创建群组异常", e);
            return "调用异常: " + e.getMessage();
        }
    }

    /**
     * 邀请成员加入群组
     * API: POST /im/v1/chats/{chat_id}/invite
     */
    @SuppressWarnings("unchecked")
    private String inviteGroupMembers(String chatId, java.util.List<String> memberOpenIds) {
        String url = apiBaseUrl + "/im/v1/chats/" + chatId + "/invite";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 构建 id_list（飞书 API 要求格式：{"id_list": ["ou_xxx"], "id_type": "open_id"}）
        java.util.List<String> idList = new java.util.ArrayList<>(memberOpenIds);
        Map<String, Object> body = Map.of(
                "id_list", idList,
                "id_type", "open_id"
        );
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            String responseBody = response.getBody();
            log.info("邀请成员API响应: {}", responseBody);

            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
            String code = String.valueOf(result.get("code"));
            if (!"0".equals(code)) {
                String msg = String.valueOf(result.get("msg"));
                return "邀请失败: " + msg + " (code=" + code + ")";
            }
            log.info("邀请成员成功: chatId={}, 成员数={}", chatId, memberOpenIds.size());
            return "success";
        } catch (Exception e) {
            log.error("邀请成员异常: chatId=" + chatId, e);
            return "邀请异常: " + e.getMessage();
        }
    }

    /**
     * 创建群组（单创建者版本，兼容旧调用）
     *
     * @param groupName      群组名称
     * @param creatorOpenId  创建者的 open_id
     * @return 创建结果
     */
    public String createGroup(String groupName, String creatorOpenId) {
        java.util.List<String> members = new java.util.ArrayList<>();
        if (creatorOpenId != null && !creatorOpenId.isEmpty()) {
            members.add(creatorOpenId);
        }
        return createGroup(groupName, members);
    }

    /**
     * 搜索群内文件/文档消息
     * 使用飞书消息列表API：GET /im/v1/messages
     * 需要权限：im:message.group_msg:readonly（读取群聊消息）
     *
     * 策略：列出群内最近消息，筛选文件/文档类型，按文件名匹配关键词
     *
     * @param chatId  群聊ID
     * @param keyword 搜索关键词
     * @return 匹配结果文本，无结果返回 null，权限不足返回 "PERMISSION_DENIED"
     */
    @SuppressWarnings("unchecked")
    public String searchGroupFiles(String chatId, String keyword) {
        if (chatId == null || chatId.isEmpty()) return null;
        ensureToken();

        String url = apiBaseUrl + "/im/v1/messages?container_id_type=chat&container_id=" + chatId
                + "&page_size=50&sort_type=ByCreateTimeDesc";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String responseBody = response.getBody();
            log.info("搜索群文件API响应: {}", responseBody);

            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

            if (!"0".equals(String.valueOf(result.get("code")))) {
                log.warn("搜索群文件失败: code={}, msg={}", result.get("code"), result.get("msg"));
                return null;
            }

            Map<String, Object> data = (Map<String, Object>) result.get("data");
            if (data == null) return null;

            java.util.List<Map<String, Object>> items = (java.util.List<Map<String, Object>>) data.get("items");
            if (items == null || items.isEmpty()) return null;

            // 文件/文档类消息类型
            java.util.Set<String> fileTypes = java.util.Set.of(
                    "file", "doc", "docx", "sheet", "bitable", "wiki", "slides"
            );

            StringBuilder sb = new StringBuilder();
            int count = 0;

            for (Map<String, Object> item : items) {
                if (count >= 5) break;
                String msgType = (String) item.getOrDefault("msg_type", "");
                if (!fileTypes.contains(msgType)) continue;

                // 解析消息体获取文件名
                // 飞书消息结构: body: { content: "{...}" }，content 是 JSON 字符串
                Object bodyObj = item.get("body");
                Map<String, Object> contentMap = null;
                try {
                    Map<String, Object> bodyMap = null;
                    if (bodyObj instanceof String s && !s.isEmpty()) {
                        bodyMap = objectMapper.readValue(s, Map.class);
                    } else if (bodyObj instanceof Map m) {
                        bodyMap = m;
                    }
                    // body 里的 content 字段才是实际的 JSON 字符串
                    if (bodyMap != null) {
                        Object contentObj = bodyMap.get("content");
                        if (contentObj instanceof String cs && !cs.isEmpty()) {
                            contentMap = objectMapper.readValue(cs, Map.class);
                        } else if (contentObj instanceof Map cm) {
                            contentMap = cm;
                        } else {
                            contentMap = bodyMap; // 兜底：content 不存在则直接用 bodyMap
                        }
                    }
                } catch (Exception e) {
                    // 忽略解析失败
                }

                // 提取文件名（不同消息类型字段不同）
                String fileName = extractFileName(contentMap, msgType);
                if (fileName == null) continue;

                // 关键词匹配（不区分大小写）
                if (!fileName.toLowerCase().contains(keyword.toLowerCase())) continue;

                String createTime = (String) item.getOrDefault("create_time", "");
                // 提取发送者信息
                String senderInfo = "";
                Object senderObj = item.get("sender");
                if (senderObj instanceof Map senderMap) {
                    String senderType = (String) senderMap.getOrDefault("sender_type", "");
                    senderInfo = "app".equals(senderType) ? "🤖 应用" : "👤 用户";
                }
                count++;
                sb.append(String.format("%d. %s\n", count, fileName));
                sb.append(String.format("   📁 类型：%s\n", msgType));
                if (!senderInfo.isEmpty()) {
                    sb.append(String.format("   %s\n", senderInfo));
                }
                if (!createTime.isEmpty()) {
                    try {
                        long ts = Long.parseLong(createTime);
                        java.time.Instant instant = java.time.Instant.ofEpochMilli(ts);
                        String timeStr = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.of("Asia/Shanghai"))
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                        sb.append(String.format("   🕐 时间：%s\n", timeStr));
                    } catch (NumberFormatException ignored) {}
                }
            }

            return sb.length() > 0 ? sb.toString() : null;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            String errorBody = e.getResponseBodyAsString();
            log.warn("搜索群文件权限不足: {}", errorBody);
            if (errorBody.contains("230027") || errorBody.contains("permission")) {
                return "PERMISSION_DENIED";
            }
            return null;
        } catch (Exception e) {
            log.error("搜索群文件异常 chatId={}", chatId, e);
            return null;
        }
    }

    /**
     * 从消息体中提取文件名
     */
    @SuppressWarnings("unchecked")
    private String extractFileName(Map<String, Object> bodyMap, String msgType) {
        if (bodyMap == null) return null;

        switch (msgType) {
            case "file":
                return (String) bodyMap.getOrDefault("file_name", null);
            case "doc", "docx": {
                // 文档标题可能在 title 字段
                String title = (String) bodyMap.getOrDefault("title", null);
                return title != null ? title : (String) bodyMap.getOrDefault("file_name", null);
            }
            case "sheet": {
                String title = (String) bodyMap.getOrDefault("title", null);
                return title != null ? title : (String) bodyMap.getOrDefault("file_name", null);
            }
            case "wiki": {
                // wiki 消息体通常有 title
                String title = (String) bodyMap.getOrDefault("title", null);
                return title != null ? title : "Wiki文档";
            }
            default:
                Object file_name = bodyMap.getOrDefault("file_name", null);
                Object title = bodyMap.getOrDefault("title", null);
                if (file_name instanceof String s) return s;
                if (title instanceof String s) return s;
                return null;
        }
    }

    /**
     * 搜索飞书知识库文档
     * 使用飞书知识库(Wiki)API搜索：支持 tenant_access_token
     * 需要权限：wiki:wiki:readonly（查看知识库）
     *
     * 搜索策略：
     * 1. 先列出机器人可访问的所有知识库空间
     * 2. 在每个空间中搜索标题匹配的文档节点
     * 3. 汇总结果返回
     *
     * @param keyword 搜索关键词
     * @return 搜索结果列表（格式化后的文本），无结果返回 null
     */
    @SuppressWarnings("unchecked")
    public String searchDocuments(String keyword) {
        ensureToken();

        // 第一步：获取知识库空间列表
        java.util.List<Map<String, Object>> spaces = listWikiSpaces();
        if (spaces == null || spaces.isEmpty()) {
            log.info("未找到可访问的知识库空间");
            return null;
        }

        // 第二步：在每个空间中搜索文档
        java.util.List<Map<String, Object>> matchedDocs = new java.util.ArrayList<>();
        for (Map<String, Object> space : spaces) {
            String spaceId = (String) space.get("space_id");
            String spaceName = (String) space.getOrDefault("name", "");
            if (spaceId == null) continue;

            java.util.List<Map<String, Object>> nodes = fetchWikiNodesBySpaceId(spaceId);
            if (nodes == null) continue;

            for (Map<String, Object> node : nodes) {
                String title = (String) node.getOrDefault("title", "");
                // 标题包含关键词即匹配（不区分大小写）
                if (title.toLowerCase().contains(keyword.toLowerCase())) {
                    Map<String, Object> match = new java.util.HashMap<>();
                    match.put("title", title);
                    match.put("space_name", spaceName);
                    match.put("node_token", node.getOrDefault("node_token", ""));
                    match.put("obj_type", node.getOrDefault("obj_type", ""));
                    matchedDocs.add(match);
                }
                if (matchedDocs.size() >= 10) break; // 最多收集10条
            }
            if (matchedDocs.size() >= 10) break;
        }

        if (matchedDocs.isEmpty()) {
            return null;
        }

        // 第三步：格式化输出
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map<String, Object> doc : matchedDocs) {
            if (count >= 5) break; // 最多展示5条
            String title = (String) doc.getOrDefault("title", "无标题");
            String spaceName = (String) doc.getOrDefault("space_name", "");
            String nodeToken = (String) doc.getOrDefault("node_token", "");
            String objType = (String) doc.getOrDefault("obj_type", "");
            count++;
            sb.append(String.format("%d. %s\n", count, title));
            if (!spaceName.isEmpty()) {
                sb.append(String.format("   📚 知识库：%s\n", spaceName));
            }
            if (!objType.isEmpty()) {
                sb.append(String.format("   📁 类型：%s\n", objType));
            }
            if (!nodeToken.isEmpty()) {
                sb.append(String.format("   🔗 https://feishu.cn/wiki/%s\n", nodeToken));
            }
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 获取知识库空间列表
     * API: GET /wiki/v2/spaces
     * 权限: wiki:wiki:readonly
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> listWikiSpaces() {
        java.util.List<Map<String, Object>> allSpaces = new java.util.ArrayList<>();
        String pageToken = null;

        // 检查 token 是否有效
        if (tenantAccessToken == null || tenantAccessToken.isEmpty()) {
            log.error("tenantAccessToken 为空，无法获取知识库空间！请检查飞书应用配置和权限。");
            return allSpaces;
        }

        do {
            String url = apiBaseUrl + "/wiki/v2/spaces?page_size=50";
            if (pageToken != null) {
                url += "&page_token=" + pageToken;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tenantAccessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            try {
                log.debug("正在请求知识库空间列表: {}", url);
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                String responseBody = response.getBody();
                log.info("获取知识库空间响应: {}", responseBody);  // 改为 INFO 级别，方便调试

                Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

                if (!"0".equals(String.valueOf(result.get("code")))) {
                    log.error("获取知识库空间失败: code={}, msg={}, 请检查应用是否开通了 wiki:wiki:readonly 权限",
                            result.get("code"), result.get("msg"));
                    break;
                }

                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data == null) {
                    log.warn("获取知识库空间返回 data 为 null");
                    break;
                }

                java.util.List<Map<String, Object>> items = (java.util.List<Map<String, Object>>) data.get("items");
                if (items != null) {
                    log.info("本页获取到 {} 个知识库空间", items.size());
                    allSpaces.addAll(items);
                } else {
                    log.info("本页 items 为 null");
                }

                boolean hasMore = Boolean.TRUE.equals(data.get("has_more"));
                pageToken = hasMore ? (String) data.get("page_token") : null;
                log.debug("是否有更多数据: {}, next_page_token: {}", hasMore, pageToken);
            } catch (Exception e) {
                log.error("获取知识库空间异常", e);
                break;
            }
        } while (pageToken != null);

        log.info("总共获取到 {} 个知识库空间", allSpaces.size());
        return allSpaces;
    }

    /**
     * 获取知识库空间下的文档节点列表（公开方法，用于指定空间同步）
     * API: GET /wiki/v2/spaces/{space_id}/nodes
     * 权限: wiki:wiki:readonly
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> fetchWikiNodesBySpaceId(String spaceId) {
        java.util.List<Map<String, Object>> allNodes = new java.util.ArrayList<>();
        String pageToken = null;

        do {
            String url = apiBaseUrl + "/wiki/v2/spaces/" + spaceId + "/nodes?page_size=50";
            if (pageToken != null) {
                url += "&page_token=" + pageToken;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tenantAccessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                String responseBody = response.getBody();
                log.debug("获取知识库节点响应 spaceId={}: {}", spaceId, responseBody);

                Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

                if (!"0".equals(String.valueOf(result.get("code")))) {
                    log.warn("获取知识库节点失败: code={}, msg={}", result.get("code"), result.get("msg"));
                    break;
                }

                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data == null) break;

                java.util.List<Map<String, Object>> items = (java.util.List<Map<String, Object>>) data.get("items");
                if (items != null) allNodes.addAll(items);

                boolean hasMore = Boolean.TRUE.equals(data.get("has_more"));
                pageToken = hasMore ? (String) data.get("page_token") : null;
            } catch (Exception e) {
                log.error("获取知识库节点异常 spaceId={}", spaceId, e);
                break;
            }
        } while (pageToken != null);

        log.debug("获取知识库节点 spaceId={}, 共 {} 个", spaceId, allNodes.size());
        return allNodes;
    }

    /**
     * 获取知识库空间名称
     * API: GET /wiki/v2/spaces/{space_id}
     */
    @SuppressWarnings("unchecked")
    public String getWikiSpaceName(String spaceId) {
        ensureToken();
        String url = apiBaseUrl + "/wiki/v2/spaces/" + spaceId;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            Map<String, Object> result = objectMapper.readValue(response.getBody(), Map.class);

            if (!"0".equals(String.valueOf(result.get("code")))) {
                log.warn("获取知识库空间名称失败: code={}, msg={}", result.get("code"), result.get("msg"));
                return "";
            }

            Map<String, Object> data = (Map<String, Object>) result.get("data");
            if (data == null) return "";

            Map<String, Object> space = (Map<String, Object>) data.get("space");
            return space != null ? (String) space.getOrDefault("name", "") : "";
        } catch (Exception e) {
            log.warn("获取知识库空间名称异常 spaceId={}", spaceId, e);
            return "";
        }
    }

    /**
     * 构建帮助中心交互式卡片 JSON（Schema 2.0）
     * 标题：🤖 机器人帮助中心（蓝色）
     * 点击按钮触发 card.action.trigger 回调，value.action 指定对应指令
     * 
     * 设计思路：分层展示
     * 1. 第一层：只显示功能分类按钮
     * 2. 第二层：用户点击后，通过回调返回详细指令
     */
    public static String buildHelpCard() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> card = new HashMap<>();
            card.put("config", Map.of("wide_screen_mode", true));

            // header
            Map<String, Object> header = new HashMap<>();
            header.put("title", Map.of("tag", "plain_text", "content", "🤖 机器人帮助中心"));
            header.put("template", "blue");
            card.put("header", header);

            // elements
            List<Map<String, Object>> elements = new ArrayList<>();

            // 提示文本
            elements.add(Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", 
                "👆 **请选择功能分类，查看详细指令**\n\n" +
                "💡 点击下方按钮，获取对应功能的使用说明")));

            elements.add(Map.of("tag", "hr"));

            // 基础功能
            elements.add(Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", "**📌 基础功能**")));
            elements.add(buildButtonGroup(
                    Map.of("action", "help_weather"), "🌤 天气查询", "primary",
                    Map.of("action", "help_translate"), "🌐 翻译", "primary",
                    Map.of("action", "help_schedule"), "📅 日程管理", "primary"
            ));

            elements.add(Map.of("tag", "hr"));

            // 企业功能
            elements.add(Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", "**🏢 企业功能**")));
            elements.add(buildButtonGroup(
                    Map.of("action", "help_group"), "👥 创建群组", "default",
                    Map.of("action", "help_search"), "🔍 搜索文档", "default",
                    Map.of("action", "help_ai"), "🤖 AI问答", "default"
            ));

            elements.add(Map.of("tag", "hr"));

            // GitHub 功能
            elements.add(Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", "**🐙 GitHub 功能**")));
            elements.add(buildButtonGroup(
                    Map.of("action", "help_github"), "📦 仓库/PR", "default",
                    Map.of("action", "help_branch"), "🌿 分支管理", "default",
                    Map.of("action", "help_review"), "🔍 代码审查", "default"
            ));

            elements.add(Map.of("tag", "hr"));

            // DevOps 工具
            elements.add(Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", "**🔧 DevOps 工具**")));
            elements.add(buildButtonGroup(
                    Map.of("action", "help_cicd"), "⚙️ CI/CD", "default",
                    Map.of("action", "help_deploy"), "🚀 部署", "primary",
                    Map.of("action", "help_monitor"), "📊 监控", "default"
            ));

            elements.add(Map.of("tag", "hr"));

            // 系统工具
            elements.add(Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", "**🛠️ 系统工具**")));
            elements.add(buildButtonGroup(
                    Map.of("action", "help_uptime"), "⏱ 运行时间", "default",
                    Map.of("action", "help_ping"), "📶 Ping", "default",
                    Map.of("action", "help_myid"), "🆔 我的ID", "default"
            ));

            card.put("elements", elements);
            return mapper.writeValueAsString(card);
        } catch (Exception e) {
            log.error("构建帮助卡片失败", e);
            return "{}";
        }
    }

    /**
     * 构建按钮组（辅助方法）
     */
    private static Map<String, Object> buildButtonGroup(
            Map<String, String> value1, String text1, String type1,
            Map<String, String> value2, String text2, String type2,
            Map<String, String> value3, String text3, String type3) {
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", text1), "value", value1, "type", type1));
        actions.add(Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", text2), "value", value2, "type", type2));
        actions.add(Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", text3), "value", value3, "type", type3));
        return Map.of("tag", "action", "actions", actions);
    }

    /**
     * 转义 JSON 字符串中的特殊字符（静态方法，供 buildApprovalCard 调用）
     */
    private static String escapeJson(String text) {
        if (text == null) return "";
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ==================== 搜索索引同步用方法 ====================

    /**
     * 获取机器人所在的群聊列表
     * API: GET /im/v1/chats（该 API 本身不返回 P2P 私聊，无需额外过滤）
     * 需要权限：im:chat:readonly
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> listBotChats() {
        ensureToken();
        java.util.List<Map<String, Object>> allChats = new java.util.ArrayList<>();
        String pageToken = null;

        do {
            String url = apiBaseUrl + "/im/v1/chats?page_size=50";
            if (pageToken != null) {
                url += "&page_token=" + pageToken;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tenantAccessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                String responseBody = response.getBody();
                log.info("获取群聊列表API响应: {}", responseBody);

                Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

                if (!"0".equals(String.valueOf(result.get("code")))) {
                    log.warn("获取群聊列表失败: code={}, msg={}", result.get("code"), result.get("msg"));
                    break;
                }

                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data == null) break;

                java.util.List<Map<String, Object>> items = (java.util.List<Map<String, Object>>) data.get("items");
                if (items != null) allChats.addAll(items);

                boolean hasMore = Boolean.TRUE.equals(data.get("has_more"));
                pageToken = hasMore ? (String) data.get("page_token") : null;
            } catch (Exception e) {
                log.error("获取群聊列表异常", e);
                break;
            }
        } while (pageToken != null);

        log.info("获取到 {} 个群聊", allChats.size());
        return allChats;
    }

    /**
     * 获取群聊消息（支持分页，用于索引同步）
     * API: GET /im/v1/messages
     *
     * @param chatId    群聊ID
     * @param maxCount  最多获取消息条数
     * @return 消息列表（原始 API 返回格式）
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> fetchChatMessages(String chatId, int maxCount) {
        ensureToken();
        java.util.List<Map<String, Object>> allItems = new java.util.ArrayList<>();
        String pageToken = null;

        do {
            String url = apiBaseUrl + "/im/v1/messages?container_id_type=chat&container_id=" + chatId
                    + "&page_size=50&sort_type=ByCreateTimeDesc";
            if (pageToken != null) {
                url += "&page_token=" + pageToken;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tenantAccessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                Map<String, Object> result = objectMapper.readValue(response.getBody(), Map.class);

                if (!"0".equals(String.valueOf(result.get("code")))) {
                    log.warn("获取群聊消息失败: code={}, msg={}", result.get("code"), result.get("msg"));
                    break;
                }

                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data == null) break;

                java.util.List<Map<String, Object>> items = (java.util.List<Map<String, Object>>) data.get("items");
                if (items != null) allItems.addAll(items);

                boolean hasMore = Boolean.TRUE.equals(data.get("has_more"));
                pageToken = hasMore ? (String) data.get("page_token") : null;
            } catch (Exception e) {
                log.error("获取群聊消息异常 chatId={}", chatId, e);
                break;
            }
        } while (pageToken != null && allItems.size() < maxCount);

        // 截断到 maxCount
        if (allItems.size() > maxCount) {
            allItems = allItems.subList(0, maxCount);
        }

        log.debug("获取群聊消息 chatId={}, 共 {} 条", chatId, allItems.size());
        return allItems;
    }

    /**
     * 获取所有知识库文档（公开方法，用于索引同步）
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> fetchAllWikiDocuments() {
        ensureToken();
        java.util.List<Map<String, Object>> allDocs = new java.util.ArrayList<>();

        java.util.List<Map<String, Object>> spaces = listWikiSpaces();
        if (spaces == null || spaces.isEmpty()) return allDocs;

        for (Map<String, Object> space : spaces) {
            String spaceId = (String) space.get("space_id");
            String spaceName = (String) space.getOrDefault("name", "");
            if (spaceId == null) continue;

            java.util.List<Map<String, Object>> nodes = fetchWikiNodesBySpaceId(spaceId);
            if (nodes == null) continue;

            for (Map<String, Object> node : nodes) {
                Map<String, Object> doc = new java.util.HashMap<>(node);
                doc.put("space_name", spaceName);
                allDocs.add(doc);
            }
        }

        log.info("获取到 {} 条知识库文档", allDocs.size());
        return allDocs;
    }

    // ==================== 云文档同步用方法 ====================

    /**
     * 获取云文档列表
     * API: GET /drive/v1/files
     * 权限: drive:drive:readonly
     *
     * @return 云文档列表
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> listDriveFiles() {
        ensureToken();
        java.util.List<Map<String, Object>> allFiles = new java.util.ArrayList<>();
        String pageToken = null;

        // 检查 token 是否有效
        if (tenantAccessToken == null || tenantAccessToken.isEmpty()) {
            log.error("tenantAccessToken 为空，无法获取云文档！请检查飞书应用配置和权限。");
            return allFiles;
        }

        do {
            String url = apiBaseUrl + "/drive/v1/files?page_size=50";
            if (pageToken != null) {
                url += "&page_token=" + pageToken;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tenantAccessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            try {
                log.debug("正在请求云文档列表: {}", url);
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                String responseBody = response.getBody();
                log.info("获取云文档列表响应: {}", responseBody);  // 改为 INFO 级别

                Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

                if (!"0".equals(String.valueOf(result.get("code")))) {
                    log.error("获取云文档列表失败: code={}, msg={}, 请检查应用是否开通了 drive:drive:readonly 权限",
                            result.get("code"), result.get("msg"));
                    break;
                }

                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data == null) {
                    log.warn("获取云文档列表返回 data 为 null");
                    break;
                }

                java.util.List<Map<String, Object>> files = (java.util.List<Map<String, Object>>) data.get("files");
                if (files != null) {
                    log.info("本页获取到 {} 个云文档", files.size());
                    allFiles.addAll(files);
                } else {
                    log.info("本页 files 为 null");
                }

                boolean hasMore = Boolean.TRUE.equals(data.get("has_more"));
                pageToken = hasMore ? (String) data.get("page_token") : null;
                log.debug("是否有更多数据: {}, next_page_token: {}", hasMore, pageToken);
            } catch (Exception e) {
                log.error("获取云文档列表异常", e);
                break;
            }
        } while (pageToken != null);

        log.info("总共获取到 {} 个云文档", allFiles.size());
        return allFiles;
    }

    /**
     * 获取文档内容（富文本）
     * API: GET /docx/v1/documents/{document_id}
     * 权限: docx:document:readonly
     *
     * @param documentId 文档ID
     * @return 文档内容（纯文本），获取失败返回 null
     */
    @SuppressWarnings("unchecked")
    public String getDocumentContent(String documentId) {
        ensureToken();

        // 方法1：尝试获取原始内容（纯文本）
        String rawContentUrl = apiBaseUrl + "/docx/v1/documents/" + documentId + "/raw_content";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(rawContentUrl, HttpMethod.GET, entity, String.class);
            String responseBody = response.getBody();
            log.debug("获取文档原始内容响应 documentId={}: {}", documentId, responseBody);

            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

            if ("0".equals(String.valueOf(result.get("code")))) {
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data != null) {
                    String content = (String) data.get("content");
                    if (content != null && !content.isEmpty()) {
                        log.debug("成功获取文档内容 documentId={}, 长度={}", documentId, content.length());
                        return content;
                    }
                }
            } else {
                log.warn("获取文档原始内容失败: code={}, msg={}", result.get("code"), result.get("msg"));
            }
        } catch (Exception e) {
            log.warn("获取文档原始内容异常 documentId={}", documentId, e);
        }

        // 方法2：如果原始内容接口失败，尝试获取文档块内容
        return getDocumentContentByBlocks(documentId);
    }

    /**
     * 通过文档块获取文档内容
     * API: GET /docx/v1/documents/{document_id}/blocks/{block_id}/children
     * 权限: docx:document:readonly
     *
     * @param documentId 文档ID
     * @return 文档内容（纯文本），获取失败返回 null
     */
    @SuppressWarnings("unchecked")
    private String getDocumentContentByBlocks(String documentId) {
        // 先获取文档的根块ID
        String docUrl = apiBaseUrl + "/docx/v1/documents/" + documentId;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(docUrl, HttpMethod.GET, entity, String.class);
            Map<String, Object> result = objectMapper.readValue(response.getBody(), Map.class);

            if (!"0".equals(String.valueOf(result.get("code")))) {
                log.warn("获取文档信息失败: code={}, msg={}", result.get("code"), result.get("msg"));
                return null;
            }

            Map<String, Object> data = (Map<String, Object>) result.get("data");
            if (data == null) return null;

            Map<String, Object> document = (Map<String, Object>) data.get("document");
            if (document == null) return null;

            String rootBlockId = (String) document.get("root_block_id");
            if (rootBlockId == null) return null;

            // 递归获取块内容
            StringBuilder contentBuilder = new StringBuilder();
            fetchBlockContent(documentId, rootBlockId, contentBuilder, 0);
            return contentBuilder.toString().trim();
        } catch (Exception e) {
            log.warn("通过块获取文档内容异常 documentId={}", documentId, e);
            return null;
        }
    }

    /**
     * 递归获取文档块内容
     */
    @SuppressWarnings("unchecked")
    private void fetchBlockContent(String documentId, String blockId, StringBuilder contentBuilder, int depth) {
        if (depth > 10) return; // 防止无限递归

        String url = apiBaseUrl + "/docx/v1/documents/" + documentId + "/blocks/" + blockId + "/children?page_size=50";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            Map<String, Object> result = objectMapper.readValue(response.getBody(), Map.class);

            if (!"0".equals(String.valueOf(result.get("code")))) {
                return;
            }

            Map<String, Object> data = (Map<String, Object>) result.get("data");
            if (data == null) return;

            java.util.List<Map<String, Object>> items = (java.util.List<Map<String, Object>>) data.get("items");
            if (items == null) return;

            for (Map<String, Object> block : items) {
                Map<String, Object> blockType = (Map<String, Object>) block.get("block_type");
                Map<String, Object> parent = (Map<String, Object>) block.get("parent");
                Map<String, Object> children = (Map<String, Object>) block.get("children");

                // 提取文本内容
                String textContent = extractTextFromBlock(block);
                if (textContent != null && !textContent.isEmpty()) {
                    contentBuilder.append(textContent).append("\n");
                }

                // 递归处理子块
                if (children != null && Boolean.TRUE.equals(children.get("has_more"))) {
                    String childBlockId = (String) block.get("block_id");
                    fetchBlockContent(documentId, childBlockId, contentBuilder, depth + 1);
                }
            }
        } catch (Exception e) {
            log.warn("获取文档块内容异常 blockId={}", blockId, e);
        }
    }

    /**
     * 从文档块中提取文本内容
     */
    @SuppressWarnings("unchecked")
    private String extractTextFromBlock(Map<String, Object> block) {
        if (block == null) return null;

        // 文本块
        Object textObj = block.get("text");
        if (textObj instanceof Map textMap) {
            Object elementsObj = textMap.get("elements");
            if (elementsObj instanceof java.util.List elements) {
                StringBuilder sb = new StringBuilder();
                for (Object elem : elements) {
                    if (elem instanceof Map elemMap) {
                        Object textRunObj = elemMap.get("text_run");
                        if (textRunObj instanceof Map textRunMap) {
                            Object contentObj = textRunMap.get("content");
                            if (contentObj instanceof String content) {
                                sb.append(content);
                            }
                        }
                    }
                }
                return sb.toString();
            }
        }

        // 标题块
        Object headingObj = block.get("heading");
        if (headingObj instanceof Map headingMap) {
            return extractTextFromBlock(headingMap);
        }

        return null;
    }

    /**
     * 获取知识库文档的内容
     * 知识库文档的 obj_type 可能是 "doc" 或 "docx"，需要调用相应的 API 获取内容
     *
     * @param objType    文档类型（doc/docx/sheet等）
     * @param objToken   文档token
     * @return 文档内容（纯文本），获取失败返回 null
     */
    @SuppressWarnings("unchecked")
    public String getWikiDocumentContent(String objType, String objToken) {
        if (objToken == null || objToken.isEmpty()) return null;

        switch (objType) {
            case "docx":
                return getDocumentContent(objToken);
            case "doc": {
                // 旧版文档 API
                ensureToken();
                String url = apiBaseUrl + "/doc/v2/documents/" + objToken + "/content";
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(tenantAccessToken);
                HttpEntity<Void> entity = new HttpEntity<>(headers);

                try {
                    ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                    Map<String, Object> result = objectMapper.readValue(response.getBody(), Map.class);

                    if ("0".equals(String.valueOf(result.get("code")))) {
                        Map<String, Object> data = (Map<String, Object>) result.get("data");
                        if (data != null) {
                            String content = (String) data.get("content");
                            if (content != null) {
                                // 解析 HTML 内容，提取纯文本
                                return cleanHtml(content);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("获取旧版文档内容异常 objToken={}", objToken, e);
                }
                return null;
            }
            default:
                log.debug("不支持的文档类型: {}", objType);
                return null;
        }
    }

    /**
     * 清理 HTML 标签，提取纯文本
     */
    private String cleanHtml(String html) {
        if (html == null) return "";
        return html
                .replaceAll("<[^>]+>", "")  // 去除 HTML 标签
                .replaceAll("&nbsp;", " ")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&amp;", "&")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#39;", "'")
                .trim();
    }

    /**
     * 获取群聊的管理员列表
     * API: GET /im/v1/chats/{chat_id}/members?member_type=owner,admin
     * 权限: im:chat:readonly
     *
     * @param chatId 群聊ID
     * @return 管理员OpenID列表，获取失败返回空列表
     */
    @SuppressWarnings("unchecked")
    public java.util.Set<String> getChatAdminIds(String chatId) {
        if (chatId == null || chatId.isEmpty()) {
            log.warn("获取群管理员失败: chatId为空");
            return java.util.Set.of();
        }
        
        ensureToken();

        String url = apiBaseUrl + "/im/v1/chats/" + chatId + "/members?member_type=owner,admin&page_size=50";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        java.util.Set<String> adminIds = new java.util.HashSet<>();
        
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String responseBody = response.getBody();
            log.info("获取群管理员响应 chatId={}: {}", chatId, responseBody);

            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

            if (!"0".equals(String.valueOf(result.get("code")))) {
                log.warn("获取群管理员失败: code={}, msg={}", result.get("code"), result.get("msg"));
                return java.util.Set.of();
            }

            Map<String, Object> data = (Map<String, Object>) result.get("data");
            if (data == null) {
                log.warn("获取群管理员失败: data为空");
                return java.util.Set.of();
            }

            java.util.List<Map<String, Object>> items = (java.util.List<Map<String, Object>>) data.get("items");
            if (items != null) {
                for (Map<String, Object> item : items) {
                    // 飞书API返回的成员ID字段是 member_id
                    String memberId = (String) item.getOrDefault("member_id", "");
                    if (!memberId.isEmpty()) {
                        adminIds.add(memberId);
                        log.debug("找到管理员: {}", memberId);
                    }
                }
            } else {
                log.warn("获取群管理员失败: items为空");
            }
            
            log.info("群聊[{}] 管理员数量: {}", chatId, adminIds.size());
            return adminIds;
        } catch (Exception e) {
            log.error("获取群管理员异常 chatId={}", chatId, e);
            return java.util.Set.of();
        }
    }
}