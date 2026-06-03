package com.example.IntelligentRobot.service;

import com.example.IntelligentRobot.task.AsyncTaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 事件异步处理器
 * 将Webhook的业务逻辑异步化，快速释放Web线程
 * 
 * 设计说明：
 * 1. 所有耗时操作（AI调用、第三方API调用）都在此类中异步执行
 * 2. 集成幂等性检查，防止重复处理
 * 3. 统一的异常处理和日志记录
 */
@Service
public class EventAsyncProcessor {

    private static final Logger log = LoggerFactory.getLogger(EventAsyncProcessor.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MessageProcessor messageProcessor;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private WelcomeEventHandler welcomeEventHandler;

    @Autowired
    private com.example.IntelligentRobot.client.FeishuClient feishuClient;

    @Autowired
    private IdempotentService idempotentService;

    /**
     * 更新任务进度（供内部类调用）
     * @param taskId 任务ID
     * @param progress 进度（0-100）
     * @param statusMsg 状态描述信息
     */
    public void updateTaskProgress(String taskId, int progress, String statusMsg) {
        if (taskId == null) return;
        AsyncTaskStatus.updateTaskProgress(taskId, progress, statusMsg);
        log.debug("任务进度更新: taskId={}, progress={}%, statusMsg={}", taskId, progress, statusMsg);
    }

    /**
     * 异步处理消息接收事件
     * 
     * @param body 事件内容
     * @param eventId 事件ID（用于幂等性检查）
     * @param taskId 任务ID（用于状态跟踪）
     */
    @Async("highPriorityEventExecutor")
    public void processMessageEventAsync(Map<String, Object> body, String eventId, String taskId) {
        log.debug("开始异步处理消息事件: eventId={}, taskId={}", eventId, taskId);
        
        try {
            // 更新进度（MessageProcessor 会负责后续细粒度更新）
            AsyncTaskStatus.updateTaskProgress(taskId, 20, "开始处理消息");
            
            // 调用 MessageProcessor，它会负责细粒度状态更新
            messageProcessor.processMessageEvent(body, taskId);
            
            // 注意：完成状态由 MessageProcessor 内部标记，这里不再重复标记
            log.debug("消息事件处理完成: eventId={}, taskId={}", eventId, taskId);
        } catch (Exception e) {
            log.error("消息事件处理失败: eventId={}, taskId={}", eventId, taskId, e);
            // 标记失败（只有在 MessageProcessor 没有标记失败时才生效）
            AsyncTaskStatus.markFailed(taskId, e.getMessage());
            // 处理失败，移除幂等键，允许重试
            idempotentService.removeIdempotentKey(eventId);
        }
    }

    /**
     * 异步处理新成员入群事件
     * 
     * @param eventType 事件类型
     * @param event 事件内容
     * @param eventId 事件ID（用于幂等性检查）
     * @param taskId 任务ID（用于状态跟踪）
     */
    @Async("highPriorityEventExecutor")
    public void processMemberAddedEventAsync(String eventType, Map<String, Object> event, String eventId, String taskId) {
        log.debug("开始异步处理入群事件: eventType={}, eventId={}, taskId={}", eventType, eventId, taskId);
        
        try {
            // 开始处理 (10%)
            this.updateTaskProgress(taskId, 10, "开始处理入群事件");
            
            // 核心逻辑执行中 (50%)
            this.updateTaskProgress(taskId, 50, "正在处理欢迎消息");
            welcomeEventHandler.handleMemberAdded(eventType, event);
            
            // 结果格式化 (80%)
            this.updateTaskProgress(taskId, 80, "欢迎消息已发送，任务即将完成");
            
            // 完成 (100%)
            AsyncTaskStatus.markCompleted(taskId, "入群事件处理完成");
            log.debug("入群事件处理完成: eventId={}, taskId={}", eventId, taskId);
        } catch (Exception e) {
            log.error("入群事件处理失败: eventType={}, eventId={}, taskId={}", eventType, eventId, taskId, e);
            AsyncTaskStatus.markFailed(taskId, e.getMessage());
            idempotentService.removeIdempotentKey(eventId);
        }
    }

    /**
     * 异步处理审批状态变更事件
     * 
     * @param event 事件内容
     * @param eventId 事件ID（用于幂等性检查）
     * @param taskId 任务ID（用于状态跟踪）
     */
    @Async("highPriorityEventExecutor")
    public void processApprovalEventAsync(Map<String, Object> event, String eventId, String taskId) {
        log.debug("开始异步处理审批事件: eventId={}, taskId={}", eventId, taskId);
        
        try {
            // 开始处理 (10%)
            this.updateTaskProgress(taskId, 10, "开始处理审批事件");
            
            // 核心逻辑执行中 (50%)
            this.updateTaskProgress(taskId, 50, "正在查询审批状态并发送通知");
            approvalService.handleApprovalStateChange(event);
            
            // 结果格式化 (80%)
            this.updateTaskProgress(taskId, 80, "审批通知已发送，任务即将完成");
            
            // 完成 (100%)
            AsyncTaskStatus.markCompleted(taskId, "审批事件处理完成");
            log.debug("审批事件处理完成: eventId={}, taskId={}", eventId, taskId);
        } catch (Exception e) {
            log.error("审批事件处理失败: eventId={}, taskId={}", eventId, taskId, e);
            AsyncTaskStatus.markFailed(taskId, e.getMessage());
            idempotentService.removeIdempotentKey(eventId);
        }
    }

    /**
     * 异步处理卡片按钮点击事件
     * 
     * @param body 事件内容
     * @param eventId 事件ID（用于幂等性检查）
     * @param taskId 任务ID（用于状态跟踪）
     */
    @Async("highPriorityEventExecutor")
    public void processCardActionAsync(Map<String, Object> body, String eventId, String taskId) {
        log.debug("开始异步处理卡片按钮事件: eventId={}, taskId={}", eventId, taskId);
        
        try {
            // 开始处理 (10%)
            this.updateTaskProgress(taskId, 10, "开始处理卡片按钮事件");
            
            // 核心逻辑执行中 (50%)
            this.updateTaskProgress(taskId, 50, "正在解析按钮动作并生成回复");
            handleCardAction(body);
            
            // 结果格式化 (80%)
            this.updateTaskProgress(taskId, 80, "回复已生成，任务即将完成");
            
            // 完成 (100%)
            AsyncTaskStatus.markCompleted(taskId, "卡片按钮事件处理完成");
            log.debug("卡片按钮事件处理完成: eventId={}, taskId={}", eventId, taskId);
        } catch (Exception e) {
            log.error("卡片按钮事件处理失败: eventId={}, taskId={}", eventId, taskId, e);
            AsyncTaskStatus.markFailed(taskId, e.getMessage());
            idempotentService.removeIdempotentKey(eventId);
        }
    }

    /**
     * 处理卡片按钮回调（私有方法）
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

        // 获取用户 open_id
        String openId = null;
        Map<String, Object> operator = (Map<String, Object>) event.get("operator");
        if (operator != null) {
            openId = (String) operator.get("operator_id");
        }

        // 获取群聊 chat_id
        String chatId = null;
        Map<String, Object> context = (Map<String, Object>) event.get("context");
        if (context != null) {
            chatId = (String) context.get("open_chat_id");
        }

        log.info("卡片按钮回调: action={}, openId={}, chatId={}", actionName, openId, chatId);

        String reply = getHelpReply(actionName);
        if (reply != null) {
            String receiveId = (chatId != null && !chatId.isEmpty()) ? chatId : openId;
            if (receiveId != null) {
                feishuClient.sendText(receiveId, reply);
                log.info("卡片按钮回调已回复: action={}, receiveId={}", actionName, receiveId);
            }
        }
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
                • `天气 <城市>` - 中文别名指令
                
                **示例：**
                • `/weather 北京`
                • `/weather 深圳`
                • `天气 上海`
                
                **功能特性：**
                • 支持上下文感知（5分钟内复用上次城市）
                • 第二次查询可直接用 `/weather`
                • 显示温度、天气状况、风力等信息
                
                💡 5分钟内再次发送 `/weather` 会自动复用上次的城市
                """;
            
            case "help_translate" -> """
                🌐 **翻译**
                
                **指令：**
                • `/translate <文本>` - 中英互译
                • `翻译 <文本>` - 中文别名指令
                
                **示例：**
                • `/translate Hello World`
                • `/translate 你好世界`
                • `翻译 How are you`
                
                **功能特性：**
                • 自动检测语言方向（中文→英文，英文→中文）
                • 支持长短文本翻译
                
                💡 自动检测语言方向，无需指定源语言
                """;
            
            case "help_schedule" -> """
                📅 **日程管理**
                
                **创建日程：**
                • `/schedule <日期> <时间> <事件>` - 创建日程
                • `/schedule <日期> <开始时间> <结束时间> <事件>` - 创建带结束时间的日程
                
                **修改日程：**
                • `/updateschedule <时间>` - 修改最近日程到指定时间
                • `/updateschedule <日期> <时间>` - 指定日期和时间
                
                **示例：**
                • `/schedule 2024-01-15 15:00 团队周会`
                • `/schedule 2024-01-15 15:00 16:00 项目评审`
                • `/updateschedule 17:00`
                • `/updateschedule 2024-01-20 14:00`
                
                💡 创建日程后会自动保存，可直接用 `/updateschedule` 修改时间
                """;
            
            case "help_ai" -> """
                🤖 **AI 智能问答**
                
                **指令：**
                • `/AI <问题>` - AI智能问答
                
                **示例：**
                • `/AI 如何创建项目`
                • `/AI 帮我写一个Python函数`
                • `/AI Java数组怎么用`
                
                **功能特性：**
                • 支持多轮对话，可以连续提问
                • 自动记住上下文
                • 支持技术问答、代码生成、文档编写等
                
                💡 可以接着上一轮的问题继续问，无需重复背景
                """;
            
            // ==================== 企业功能 ====================
            case "help_group" -> """
                👥 **创建群组**
                
                **指令：**
                • `/group <群名> [@成员1 @成员2 ...]` - 创建群组（需二次确认）
                • `建群 <群名> [@成员1 @成员2 ...]` - 中文别名指令
                
                **示例：**
                • `/group 项目组 @小张 @小王`
                • `/group 开发组`
                • `建群 前端开发群 @小李`
                
                **功能说明：**
                • 创建群组需要二次确认（防误操作）
                • 支持批量添加多个成员
                • 自动跳过不存在的成员
                
                💡 需要先@成员，再发送指令
                💡 仅管理员可用
                """;
            
            case "help_search" -> """
                🔍 **搜索文档**
                
                **搜索文档：**
                • `/search <关键词>` - 搜索群内文件和知识库
                
                **管理功能（仅群管理员）：**
                • `/search sync` - 手动同步搜索索引
                • `/search status` - 查看索引状态
                
                **示例：**
                • `/search 需求文档`
                • `/search API接口`
                • `/search sync`
                
                💡 支持搜索群内文件、飞书知识库及本地索引
                💡 首次使用请先执行 `/search sync` 建立索引
                """;
            
            case "help_myid" -> """
                🆔 **获取用户ID**
                
                **指令：**
                • `/myid` - 获取你的飞书用户Open ID
                • `我的id` - 中文别名指令
                
                **用途：**
                • 获取 Open ID，用于配置权限白名单
                • 调试和排查问题
                • 查看自己的用户标识
                
                **返回信息：**
                • Open ID（飞书用户唯一标识）
                • User ID（飞书用户ID）
                
                💡 管理员可以将你的 Open ID 加入白名单
                💡 Open ID 格式类似：ou_xxxxxxxxxxxxxxxx
                """;
            
            // ==================== GitHub 功能 ====================
            case "help_github" -> """
                🐙 **GitHub 仓库/PR 管理**
                
                **查看仓库：**
                • `/repo <别名或owner/repo>` - 查看仓库信息
                
                **查看PR：**
                • `/pr <别名或owner/repo> <PR号>` - 查看PR详情
                
                **示例：**
                • `/repo Claire-bit-cpu/frontend-repo`
                • `/pr Claire-bit-cpu/backend-repo 123`
                
                💡 **更多 GitHub 功能：**
                • 🌿 分支管理 - 点击「🌿 分支管理」按钮
                • 🔍 代码审查 - 点击「🔍 代码审查」按钮
                • 🔀 PR状态查询 - 点击「🔀 PR状态」按钮
                • ⚙️ CI/CD - 点击「⚙️ CI/CD」按钮
                """;
            
            case "help_branch" -> """
                🌿 **分支管理**
                
                **创建分支（需二次确认）：**
                • `/createbranch <别名> <新分支名> [源分支]` - 创建分支
                
                **查看提交日志（支持上下文感知）：**
                • `/gitlog <别名> [条数] [分支]` - 查看提交历史
                
                **查看提交差异：**
                • `/gitdiff <别名> <commit_sha>` - 查看提交差异
                
                **示例：**
                • `/createbranch frontend feature/new-ui master`
                • `/gitlog frontend 10 main`
                • `/gitlog frontend` - 使用默认参数
                • `/gitdiff frontend a1b2c3d4`
                
                **功能说明：**
                • 创建分支需要二次确认（防误操作）
                • `/gitlog` 支持上下文感知（5分钟内复用参数）
                • 源分支默认为 master
                
                💡 支持使用仓库别名（需在配置中定义）
                💡 `/gitlog` 第二次使用可省略参数
                """;
            
            case "help_review" -> """
                🔍 **代码审查**

                **审查PR：**
                • `/review <别名> <PR号>` - 审查PR代码

                **审查提交：**
                • `/review <别名> commit <SHA>` - 审查提交
                • `/review <别名> <SHA>` - 自动识别为commit hash

                **示例：**
                • `/review frontend 123`
                • `/review frontend commit a1b2c3d4`
                • `/review frontend a1b2c3d4` - 自动识别为commit

                **审查内容：**
                • 代码质量评分
                • 安全问题检测
                • 性能问题分析
                • 修改建议

                💡 使用AI自动审查代码质量、安全性、性能
                💡 支持PR和Commit两种审查模式
                """;
            
            case "help_mergestatus" -> """
                🔀 **PR 状态查询**
                
                **查看所有打开的PR：**
                • `/mergestatus <别名>` - 查看所有打开的PR
                
                **查看特定PR详情：**
                • `/mergestatus <别名> <PR号>` - 查看特定PR详情
                
                **示例：**
                • `/mergestatus frontend`
                • `/mergestatus backend 123`
                
                **显示信息：**
                • PR 标题和编号
                • 源分支和目标分支
                • PR 状态（open/closed/merged）
                • 作者信息
                • 创建时间
                • PR 链接
                
                💡 显示PR的合并状态、审查状态等
                💡 支持使用仓库别名（需在配置中定义）
                """;
            
            // ==================== CI/CD 功能 ====================
            case "help_cicd" -> """
                ⚙️ **CI/CD 管理**
                
                **GitHub Actions：**
                • `/github workflow <别名或owner/repo> <工作流> <分支>` - 触发工作流
                • `/github status <别名或owner/repo> <run-id>` - 查询运行状态
                • `/github list <别名或owner/repo> [工作流]` - 列出最近运行
                • `/github cancel <别名或owner/repo> <run-id>` - 取消运行
                
                **部署功能：**
                • `/deploy <环境>` - 触发部署（需二次确认）
                • 支持环境：dev/test/staging/prod
                
                **示例：**
                • `/github workflow frontend ci.yml main`
                • `/github workflow Claire-bit-cpu/Test deploy-dev.yml develop`
                • `/github list frontend`
                • `/deploy test` - 部署到测试环境
                • `/deploy prod` - 部署到生产环境
                
                💡 需要配置 GitHub Token
                💡 支持使用仓库别名（需在配置中定义）
                💡 部署需要配置 github.deploy 环境变量
                """;
            
            // ==================== DevOps 工具 ====================
            case "help_uptime" -> """
                ⏱ **运行时间**
                
                **指令：**
                • `/uptime` - 查看系统运行时间
                • `运行时间` - 中文别名指令
                
                **显示信息：**
                • 系统当前时间
                • 已运行时长（天/小时/分钟/秒）
                • 系统状态
                
                **用途：**
                • 检查系统是否正常运行
                • 查看系统稳定性
                • 排查系统重启问题
                
                💡 用于检查系统是否正常运行
                💡 运行时间越长，系统越稳定
                """;
            
            case "help_ping" -> """
                📶 **Ping 检测**
                
                **指令：**
                • `/ping <主机>` - 检测主机连通性
                • `/ping <主机>:<端口>` - 检测端口连通性
                • `/ping http://<主机>` - 检测HTTP连通性
                
                **示例：**
                • `/ping baidu.com`
                • `/ping 192.168.1.1`
                • `/ping google.com:443`
                • `/ping https://github.com`
                
                **检测项目：**
                • ICMP Ping（网络连通性）
                • TCP 端口检测（端口开放状态）
                • HTTP/HTTPS 检测（状态码和响应时间）
                
                💡 自动进行多项检测，全面诊断连通性
                """;
            
            case "help_deploy" -> """
                🚀 **部署**
                
                **指令：**
                • `/deploy <环境>` - 触发部署（需二次确认）
                
                **可用环境：**
                • `dev` - 开发环境
                • `test` - 测试环境
                • `staging` - 预发布环境
                • `prod` / `production` - 生产环境
                
                **示例：**
                • `/deploy test` - 部署到测试环境
                • `/deploy prod` - 部署到生产环境
                
                **功能说明：**
                • 部署操作需要二次确认（防误操作）
                • 支持通过 `github.deploy` 配置环境变量定义部署映射
                • 未配置 GitHub 时进入模拟模式
                
                💡 生产环境部署需要额外确认
                💡 需要配置 GitHub Token 和部署配置
                """;
            
            case "help_monitor" -> """
                📊 **服务监控**
                
                **指令：**
                • `/monitor <服务名>` - 查询服务健康状态
                
                **示例：**
                • `/monitor api-service`
                • `/monitor web-app`
                • `/monitor backend-api`
                
                **监控指标：**
                • 服务健康状态（UP/DOWN）
                • 错误率（Error Rate）
                • 请求速率（Request Rate）
                • 响应时间（Response Time）
                
                💡 需要配置 Prometheus 地址
                💡 可选配置 Grafana 地址查看仪表盘
                """;
            
            case "help_jira" -> """
                📋 **JIRA 任务管理**
                
                **查询任务：**
                • `/jira <任务编号>` - 查询任务详情
                
                **创建任务：**
                • `/jira create <项目KEY> <标题>` - 创建Bug工单
                
                **搜索任务：**
                • `/jira search <JQL>` - 搜索任务（JQL语法）
                
                **示例：**
                • `/jira PROJ-123`
                • `/jira create PROJ 登录页面BUG`
                • `/jira search assignee=currentUser()`
                • `/jira search project=PROJ AND status=Open`
                
                **常用JQL：**
                • `assignee=currentUser()` - 我的任务
                • `status=Open` - 打开的任务
                • `priority=High` - 高优先级
                
                💡 需要配置 JIRA 连接信息
                💡 未配置时自动进入本地降级模式（使用本地文件存储）
                """;
            
            // ==================== 系统工具 ====================
            case "help_test" -> """
                🧪 **测试指令**
                
                **指令：**
                • `/test` - 测试系统功能
                
                **用途：**
                • 验证系统是否正常工作
                • 调试和排查问题
                • 测试指令解析和权限校验
                
                **测试项目：**
                • 指令注册检查
                • 权限校验测试
                • 上下文管理测试
                
                💡 仅用于测试环境
                💡 普通用户也可用（测试权限校验）
                """;
            
            default -> null;
        };
    }
}
