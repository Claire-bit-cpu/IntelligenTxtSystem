package com.example.intelligentxtsystem.controller;

import com.example.intelligentxtsystem.service.ApprovalService;
import com.example.intelligentxtsystem.service.MessageProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 飞书 Webhook 回调入口
 * 处理所有飞书事件
 */
@RestController
@RequestMapping("/feishu")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MessageProcessor messageProcessor;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private com.example.intelligentxtsystem.client.FeishuClient feishuClient;

    @Autowired
    private com.example.intelligentxtsystem.service.WelcomeEventHandler welcomeEventHandler;

    /**
     * 飞书回调入口（仅接受 POST 请求）
     * 飞书 URL 验证要求：
     *   请求: {"type": "url_verification", "challenge": "xxx"}
     *   响应: {"challenge": "xxx"}  (Content-Type: application/json)
     */
    @PostMapping(value = "/webhook",
            consumes = "application/json",
            produces = "application/json")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestBody String rawBody
    ) {
        log.info("收到飞书请求: rawBody={}", rawBody);

        try {
            // 解析 JSON
            Map<String, Object> body = objectMapper.readValue(rawBody, Map.class);

            // ===== URL 验证请求（必须最优先处理）=====
            if ("url_verification".equals(body.get("type"))) {
                Object challenge = body.get("challenge");
                if (challenge == null) {
                    log.error("URL 验证请求缺少 challenge 字段");
                    return ResponseEntity.badRequest().build();
                }
                log.info("处理 URL 验证请求, challenge={}", challenge);
                // 直接返回 {"challenge": "xxx"}，无任何额外包装
                Map<String, Object> result = new HashMap<>();
                result.put("challenge", challenge);
                return ResponseEntity.ok(result);
            }

            // ===== 处理业务事件 =====
            if (body.containsKey("header")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> header = (Map<String, Object>) body.get("header");
                String eventType = (String) header.get("event_type");
                log.info("收到飞书事件: event_type={}", eventType);

                if ("im.message.receive_v1".equals(eventType)) {
                    messageProcessor.processMessageEvent(body);
                }

                // 新成员入群欢迎事件
                if (isMemberAddedEvent(eventType)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> event = (Map<String, Object>) body.get("event");
                    log.info("收到入群事件: event_type={}, event={}", eventType, event);
                    welcomeEventHandler.handleMemberAdded(eventType, event);
                }

                if ("approval.instance.state_change_v4".equals(eventType)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> event = (Map<String, Object>) body.get("event");
                    log.info("收到审批事件: event={}", event);
                    approvalService.handleApprovalStateChange(event);
                }

                if ("card.action.trigger".equals(eventType)) {
                    handleCardAction(body);
                }
            }

            // 立即返回 200，不等待处理完成
            Map<String, Object> ok = new HashMap<>();
            ok.put("code", 0);
            ok.put("msg", "ok");
            return ResponseEntity.ok(ok);

        } catch (Exception e) {
            log.error("处理请求异常", e);
            Map<String, Object> err = new HashMap<>();
            err.put("code", 500);
            err.put("msg", "服务器内部错误");
            return ResponseEntity.status(500).body(err);
        }
    }

    /**
     * 处理 GET 请求到 /feishu/webhook（防止被当作静态资源）
     * 返回 405 Method Not Allowed
     */
    @GetMapping("/webhook")
    public ResponseEntity<Map<String, Object>> handleWebhookGet() {
        log.warn("收到 GET 请求到 /feishu/webhook，仅支持 POST");
        Map<String, Object> err = new HashMap<>();
        err.put("code", 405);
        err.put("msg", "Webhook 只支持 POST 请求");
        return ResponseEntity.status(405).body(err);
    }

    /**
     * 处理卡片按钮回调（card.action.trigger）
     * 飞书事件结构：
     *   event.operator.operator_id = 点击用户的 open_id
     *   event.context.open_chat_id = 消息所在群聊的 chat_id
     */
    @SuppressWarnings("unchecked")
    private void handleCardAction(Map<String, Object> body) {
        if (body == null) return;

        Map<String, Object> event = (Map<String, Object>) body.get("event");
        if (event == null) return;

        // 获取 action.value.action
        Map<String, Object> action = (Map<String, Object>) event.get("action");
        if (action == null) return;

        Map<String, Object> value = (Map<String, Object>) action.get("value");
        if (value == null) return;

        String actionName = (String) value.get("action");
        if (actionName == null) return;

        // 获取用户 open_id（点击按钮的人）
        String openId = null;
        Map<String, Object> operator = (Map<String, Object>) event.get("operator");
        if (operator != null) {
            openId = (String) operator.get("operator_id");
        }

        // 获取群聊 chat_id（消息所在的群）
        String chatId = null;
        Map<String, Object> context = (Map<String, Object>) event.get("context");
        if (context != null) {
            chatId = (String) context.get("open_chat_id");
        }

        log.info("卡片按钮回调: action={}, openId={}, chatId={}", actionName, openId, chatId);

        String reply = getHelpReply(actionName);
        if (reply != null) {
            // 优先回复到群聊，否则私聊用户
            String receiveId = (chatId != null && !chatId.isEmpty()) ? chatId : openId;
            if (receiveId != null) {
                feishuClient.sendText(receiveId, reply);
                log.info("卡片按钮回调已回复: action={}, receiveId={}", actionName, receiveId);
            }
        }
    }

    /**
     * 判断是否为新成员入群事件
     */
    private boolean isMemberAddedEvent(String eventType) {
        return com.example.intelligentxtsystem.service.WelcomeEventHandler.EVENT_USER_ADDED.equals(eventType)
            || com.example.intelligentxtsystem.service.WelcomeEventHandler.EVENT_BOT_ADDED.equals(eventType)
            || com.example.intelligentxtsystem.service.WelcomeEventHandler.EVENT_INVITED_V1.equals(eventType);
    }

    /**
     * 根据 action 值返回对应指令的使用说明
     * 采用分层展示：第一层显示功能分类，第二层显示详细指令
     */
    private String getHelpReply(String actionName) {
        return switch (actionName) {
            // ==================== 基础功能 ====================
            case "help_weather" -> """
                🌤 **天气查询**
                
                **指令：**
                • `/weather <城市>` - 查询天气
                • 别名：`天气`、`wt`
                
                **示例：**
                • `/weather 北京`
                • `/weather 深圳`
                
                💡 支持上下文感知，第二次查询可直接用 `/weather`
                """;
            
            case "help_translate" -> """
                🌐 **翻译**
                
                **指令：**
                • `/translate <文本>` - 中英互译
                • 别名：`翻译`、`trans`
                
                **示例：**
                • `/translate Hello World`
                • `/translate 你好世界`
                
                💡 自动检测语言方向
                """;
            
            case "help_schedule" -> """
                📅 **日程管理**
                
                **创建日程：**
                • `/schedule <日期> <时间> <事件>` - 创建日程
                • 别名：`日程`、`创建日程`
                
                **修改日程：**
                • `/updateschedule <时间>` - 修改最近日程
                • 别名：`修改日程`、`更新日程`
                
                **示例：**
                • `/schedule 2024-01-15 15:00 团队周会`
                • `/schedule 2024-01-15 15:00 16:00 项目评审`
                • `/updateschedule 17:00`
                
                💡 创建日程后会保存，可直接用 `/updateschedule` 修改
                """;
            
            case "help_ai" -> """
                🤖 **AI 智能问答**
                
                **指令：**
                • `/AI <问题>` - AI智能问答
                • 别名：`AI`、`人工智能`
                
                **示例：**
                • `/AI 如何创建项目`
                • `/AI 帮我写一个Python函数`
                
                💡 支持多轮对话，可以连续提问
                """;
            
            // ==================== 企业功能 ====================
            case "help_group" -> """
                👥 **创建群组**
                
                **指令：**
                • `/group <群名> [@成员1 @成员2 ...]` - 创建群组
                
                **示例：**
                • `/group 项目组 @小张 @小王`
                • `/group 开发组`
                
                💡 需要先@成员，再发送指令
                """;
            
            case "help_search" -> """
                🔍 **搜索文档**
                
                **指令：**
                • `/search <关键词>` - 搜索知识库文档
                
                **示例：**
                • `/search 需求文档`
                • `/search API接口`
                
                💡 搜索飞书知识库中的文档
                """;
            
            case "help_myid" -> """
                🆔 **获取用户ID**
                
                **指令：**
                • `/myid` - 获取你的飞书用户ID
                • 别名：`我的id`、`userid`
                
                **用途：**
                • 获取 Open ID，用于配置权限白名单
                • 调试和排查问题
                
                💡 管理员可以将你的 Open ID 加入白名单
                """;
            
            // ==================== GitHub 功能 ====================
            case "help_github" -> """
                🐙 **GitHub 仓库/PR 管理**
                
                **查看仓库：**
                • `/repo owner/repo` - 查看仓库信息
                
                **查看PR：**
                • `/pr owner/repo PR号` - 查看PR详情
                
                **示例：**
                • `/repo Claire-bit-cpu/frontend-repo`
                • `/pr Claire-bit-cpu/backend-repo 123`
                
                💡 使用完整的 owner/repo 格式
                """;
            
            case "help_branch" -> """
                🌿 **分支管理**
                
                **创建分支：**
                • `/createbranch <别名> <新分支名> [源分支]` - 创建分支
                
                **查看提交日志：**
                • `/gitlog <别名> [条数] [分支]` - 查看提交历史
                
                **查看提交差异：**
                • `/gitdiff <别名> <commit_sha>` - 查看提交差异
                
                **示例：**
                • `/createbranch frontend feature/new-ui master`
                • `/gitlog frontend 10 main`
                • `/gitdiff frontend a1b2c3d4`
                
                💡 源分支默认为 master
                """;
            
            case "help_review" -> """
                🔍 **代码审查**
                
                **审查PR：**
                • `/cr <别名> <PR号>` - 审查PR代码
                • 别名：`review`、`代码审查`
                
                **审查提交：**
                • `/cr <别名> commit <SHA>` - 审查提交
                
                **示例：**
                • `/cr frontend 123`
                • `/cr backend commit a1b2c3d4`
                
                💡 使用AI自动审查代码质量、安全性、性能
                """;
            
            case "help_mergestatus" -> """
                🔀 **PR 状态查询**
                
                **指令：**
                • `/mergestatus <别名>` - 查看所有打开的PR
                • `/mergestatus <别名> <PR号>` - 查看特定PR详情
                
                **示例：**
                • `/mergestatus frontend`
                • `/mergestatus backend 123`
                
                💡 显示PR的合并状态、审查状态等
                """;
            
            // ==================== CI/CD 功能 ====================
            case "help_cicd" -> """
                ⚙️ **CI/CD 管理**
                
                **GitHub Actions：**
                • `/github workflow <别名> <工作流> <分支>` - 触发工作流
                • `/github status <别名> <run-id>` - 查询运行状态
                • `/github list <别名> [工作流]` - 列出最近运行
                • `/github cancel <别名> <run-id>` - 取消运行
                
                **GitLab CI：**
                • `/gitlab pipeline <项目> <分支>` - 触发流水线
                • `/gitlab status <项目> <pipeline-id>` - 查询状态
                • `/gitlab list <项目>` - 列出最近流水线
                • `/gitlab cancel <项目> <pipeline-id>` - 取消流水线
                
                **示例：**
                • `/github workflow frontend ci.yml main`
                • `/gitlab pipeline my-project feature-branch`
                
                💡 需要配置 CI/CD 权限
                """;
            
            // ==================== DevOps 工具 ====================
            case "help_uptime" -> """
                ⏱ **运行时间**
                
                **指令：**
                • `/uptime` - 查看系统运行时间
                
                **显示信息：**
                • 系统启动时间
                • 已运行时长
                • 当前时间
                
                💡 用于检查系统是否正常运行
                """;
            
            case "help_ping" -> """
                📶 **Ping 检测**
                
                **指令：**
                • `/ping <主机>` - 检测主机连通性
                
                **示例：**
                • `/ping baidu.com`
                • `/ping 192.168.1.1`
                
                💡 检测主机是否可达
                """;
            
            case "help_deploy" -> """
                🚀 **部署**
                
                **指令：**
                • `/deploy <环境>` - 触发部署
                
                **示例：**
                • `/deploy test` - 部署到测试环境
                • `/deploy prod` - 部署到生产环境
                
                💡 需要配置部署权限
                """;
            
            case "help_monitor" -> """
                📊 **服务监控**
                
                **指令：**
                • `/monitor <服务名>` - 查询服务健康状态
                • 别名：`监控`
                
                **示例：**
                • `/monitor api-service`
                • `/monitor web-app`
                
                💡 显示服务的运行状态、响应时间等
                """;
            
            case "help_jira" -> """
                📋 **JIRA 任务管理**
                
                **查询任务：**
                • `/jira <任务编号>` - 查询任务详情
                • 别名：`jira`
                
                **创建任务：**
                • `/jira create <项目> <标题>` - 创建Bug工单
                
                **搜索任务：**
                • `/jira search <JQL>` - 搜索任务
                
                **示例：**
                • `/jira PROJ-123`
                • `/jira create PROJ 登录页面BUG`
                • `/jira search project=PROJ AND status=Open`
                
                💡 需要配置 JIRA 连接信息
                """;
            
            // ==================== 系统工具 ====================
            case "help_test" -> """
                🧪 **测试指令**
                
                **指令：**
                • `/test` - 测试系统功能
                • `/myid` - 获取用户ID
                
                **用途：**
                • 验证系统是否正常工作
                • 调试和排查问题
                
                💡 仅用于测试环境
                """;
            
            default -> null;
        };
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "IntelligenTxtSystem");
    }
}
