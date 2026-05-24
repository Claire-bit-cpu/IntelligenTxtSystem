/*
核心发送类,发送到飞书群中
 */

package com.example.intelligentxtsystem.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
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

        String url = apiBaseUrl + "/auth/v3/tenant_access_token/internal";
        Map<String, String> body = Map.of(
                "app_id", appId,
                "app_secret", appSecret
        );

        ResponseEntity<Map> response = restTemplate.postForEntity(url, body, Map.class);
        Map<String, Object> result = response.getBody();

        tenantAccessToken = (String) result.get("tenant_access_token");
        int expire = (int) result.get("expire");
        expireTime = System.currentTimeMillis() + expire * 1000L - tokenBufferSeconds * 1000L;
    }

    /**
     * 发送文本消息
     * @param receiveId 群ID (oc_xxxx) 或 用户OpenID (ou_xxxx)
     */
    public void sendText(String receiveId, String text) {
        ensureToken();

        String url = apiBaseUrl + "/im/v1/messages?receive_id_type=chat_id";

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
        restTemplate.postForEntity(url, entity, String.class);
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
    public String createCalendarEventWithAttendee(String summary, LocalDateTime startTime, LocalDateTime endTime, String attendeeId) {
        ensureToken();

        // 获取机器人日历ID（如果还没有）
        if (calendarId == null) {
            calendarId = getPrimaryCalendarId();
            log.info("获取到的日历ID: {}", calendarId);
            if (calendarId == null) {
                return "获取日历失败";
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
            return "创建日程失败";
        }

        // 如果有参与者，单独添加
        if (attendeeId != null && !attendeeId.isEmpty()) {
            String addResult = addAttendeeToEvent(eventId, attendeeId);
            if (!"success".equals(addResult)) {
                log.warn("添加参与者失败，但日程已创建: {}", addResult);
            }
        }

        return "success";
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
     * 创建群组
     *
     * @param groupName 群组名称
     * @param creatorOpenId 创建者的 open_id，会自动加入群聊
     * @return 创建结果（成功返回 "success"，失败返回原因）
     */
    public String createGroup(String groupName, String creatorOpenId) {
        ensureToken();

        String url = apiBaseUrl + "/im/v1/chats";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", groupName);
        body.put("chat_mode", "group");
        body.put("chat_type", "public");
        if (creatorOpenId != null && !creatorOpenId.isEmpty()) {
            body.put("user_id_list", java.util.List.of(creatorOpenId));
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            String responseBody = response.getBody();
            log.info("创建群组API响应: {}", responseBody);
            log.info("HTTP状态码: {}", response.getStatusCode());

            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

            // 检查 code
            String code = String.valueOf(result.get("code"));
            String msg = String.valueOf(result.get("msg"));
            log.info("API返回 code: {}, msg: {}", code, msg);

            // 检查 data 中是否有群组信息
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            if (data != null) {
                String chatId = (String) data.get("chat_id");
                log.info("创建的群组 chat_id: {}", chatId);
                if (chatId != null && !chatId.isEmpty()) {
                    return "success";
                }
            }

            // 即使 code 是 0，如果没有 chat_id 也算失败
            return "创建失败: " + msg + " (code=" + code + ")";
        } catch (Exception e) {
            log.error("创建群组异常", e);
            return "调用异常: " + e.getMessage();
        }
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

        do {
            String url = apiBaseUrl + "/wiki/v2/spaces?page_size=50";
            if (pageToken != null) {
                url += "&page_token=" + pageToken;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tenantAccessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                String responseBody = response.getBody();
                log.debug("获取知识库空间响应: {}", responseBody);

                Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

                if (!"0".equals(String.valueOf(result.get("code")))) {
                    log.warn("获取知识库空间失败: code={}, msg={}", result.get("code"), result.get("msg"));
                    break;
                }

                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data == null) break;

                java.util.List<Map<String, Object>> items = (java.util.List<Map<String, Object>>) data.get("items");
                if (items != null) allSpaces.addAll(items);

                boolean hasMore = Boolean.TRUE.equals(data.get("has_more"));
                pageToken = hasMore ? (String) data.get("page_token") : null;
            } catch (Exception e) {
                log.error("获取知识库空间异常", e);
                break;
            }
        } while (pageToken != null);

        log.info("获取到 {} 个知识库空间", allSpaces.size());
        return allSpaces;
    }

    /**
     * 获取知识库空间下的所有文档节点列表（递归获取所有子节点）
     * API: GET /wiki/v2/spaces/{space_id}/nodes
     * 权限: wiki:wiki:readonly
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> fetchWikiNodesBySpaceId(String spaceId) {
        java.util.List<Map<String, Object>> allNodes = new java.util.ArrayList<>();
        
        // 递归获取所有节点（包括子文件夹中的文档）
        fetchWikiNodesRecursive(spaceId, null, allNodes);
        
        log.info("获取知识库所有节点 spaceId={}, 共 {} 个", spaceId, allNodes.size());
        return allNodes;
    }

    /**
     * 递归获取知识库节点（包括子文件夹）
     */
    @SuppressWarnings("unchecked")
    private void fetchWikiNodesRecursive(String spaceId, String parentNodeToken, 
                                        java.util.List<Map<String, Object>> resultList) {
        String pageToken = null;

        do {
            StringBuilder urlBuilder = new StringBuilder(
                    apiBaseUrl + "/wiki/v2/spaces/" + spaceId + "/nodes?page_size=50"
            );
            if (parentNodeToken != null) {
                urlBuilder.append("&parent_node_token=").append(parentNodeToken);
            }
            if (pageToken != null) {
                urlBuilder.append("&page_token=").append(pageToken);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tenantAccessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        urlBuilder.toString(), HttpMethod.GET, entity, String.class
                );
                String responseBody = response.getBody();
                log.debug("获取知识库节点响应 spaceId={}, parentNodeToken={}: {}", 
                         spaceId, parentNodeToken, responseBody);

                Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

                if (!"0".equals(String.valueOf(result.get("code")))) {
                    log.warn("获取知识库节点失败: code={}, msg={}", result.get("code"), result.get("msg"));
                    break;
                }

                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data == null) break;

                java.util.List<Map<String, Object>> items = (java.util.List<Map<String, Object>>) data.get("items");
                if (items != null) {
                    for (Map<String, Object> node : items) {
                        resultList.add(node);
                        
                        // 如果是文件夹，递归获取其子节点
                        String objType = (String) node.getOrDefault("obj_type", "");
                        if ("folder".equals(objType)) {
                            String childNodeToken = (String) node.getOrDefault("node_token", "");
                            if (!childNodeToken.isEmpty()) {
                                log.debug("递归获取子文件夹节点: {}", childNodeToken);
                                fetchWikiNodesRecursive(spaceId, childNodeToken, resultList);
                            }
                        }
                    }
                }

                boolean hasMore = Boolean.TRUE.equals(data.get("has_more"));
                pageToken = hasMore ? (String) data.get("page_token") : null;
            } catch (Exception e) {
                log.error("获取知识库节点异常 spaceId={}, parentNodeToken={}", spaceId, parentNodeToken, e);
                break;
            }
        } while (pageToken != null);
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
     * 发送卡片消息（交互式卡片）
     * @param receiveId 接收者ID（群ID或用户OpenID）
     * @param cardJson 卡片JSON字符串（interactive类型）
     */
    public void sendCard(String receiveId, String cardJson) {
        ensureToken();

        String url = apiBaseUrl + "/im/v1/messages?receive_id_type=chat_id";

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
            log.debug("发送卡片消息响应: {}", response.getBody());
        } catch (Exception e) {
            log.error("发送卡片消息失败 receiveId={}", receiveId, e);
        }
    }

    /**
     * 发送消息（通用方法）
     * @param message 消息体 Map，包含 receive_id, msg_type, content 等字段
     */
    public void sendMessage(Map<String, Object> message) {
        ensureToken();

        String url = apiBaseUrl + "/im/v1/messages?receive_id_type=chat_id";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenantAccessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(message, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            log.debug("发送消息响应: {}", response.getBody());
        } catch (Exception e) {
            log.error("发送消息失败 message={}", message, e);
        }
    }

    /**
     * 将 Map 转换为 JSON 字符串（供 HelpHandler 等使用）
     */
    public static String mapToJson(Map<String, Object> map) {
        try {
            return new ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException("Map转JSON失败", e);
        }
    }

    /**
     * 转义 JSON 字符串中的特殊字符
     */
    private String escapeJson(String text) {
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

        do {
            String url = apiBaseUrl + "/drive/v1/files?page_size=50";
            if (pageToken != null) {
                url += "&page_token=" + pageToken;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tenantAccessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                String responseBody = response.getBody();
                log.debug("获取云文档列表响应: {}", responseBody);

                Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);

                if (!"0".equals(String.valueOf(result.get("code")))) {
                    log.warn("获取云文档列表失败: code={}, msg={}", result.get("code"), result.get("msg"));
                    break;
                }

                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data == null) break;

                java.util.List<Map<String, Object>> files = (java.util.List<Map<String, Object>>) data.get("files");
                if (files != null) allFiles.addAll(files);

                boolean hasMore = Boolean.TRUE.equals(data.get("has_more"));
                pageToken = hasMore ? (String) data.get("page_token") : null;
            } catch (Exception e) {
                log.error("获取云文档列表异常", e);
                break;
            }
        } while (pageToken != null);

        log.info("获取到 {} 个云文档", allFiles.size());
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