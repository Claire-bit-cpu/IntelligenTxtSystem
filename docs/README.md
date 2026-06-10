# 智能机器人 (IntelligentRobot)

<div align="center">

**基于飞书开放平台的企业级智能助手机器人**

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen?style=flat&logo=spring)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-17-blue?style=flat&logo=openjdk)](https://www.oracle.com/java/)
[![Build Status](https://img.shields.io/badge/build-passing-brightgreen?style=flat)]()

</div>

---

##  目录 (Table of Contents)

1. [项目简介](#项目简介)
2. [核心功能模块](#核心功能模块)
   - [基础指令集](#1-基础指令集)
   - [企业效率集成](#2-企业效率集成)
   - [高级功能扩展](#3-高级功能扩展)
3. [技术架构](#技术架构)
4. [技术挑战与实现要点](#技术挑战与实现要点)
5. [快速开始 (Quick Start)](#快速开始-quick-start)
6. [指令大全 (Command List)](#指令大全-command-list)
7. [Webhook 配置教程](#webhook-配置教程)
8. [开发指南 (Developer Guide)](#开发指南-developer-guide)
9. [交付产物说明](#交付产物说明)
10. [配置说明 (Configuration)](#配置说明)

---

## 1.项目简介

**IntelligentRobot** 是一个基于 [飞书开放平台](https://open.feishu.cn/) 开发的智能助手机器人，旨在通过自然语言指令深度集成企业内部 DevOps 工具链与 AI 能力，全面提升研发团队协作效率与办公自动化水平。

### 核心特性

-  **AI 赋能**：集成通义千问，支持智能代码审查、自然语言理解、智能翻译
-  **DevOps 全链路**：GitHub+JIRA  集成
-  **智能降噪**：消息去重 + 消息合并，有效减少通知疲劳
-  **上下文感知**：支持多轮对话，自动记忆用户偏好和输入历史
-  **权限控制**：RBAC 模型，支持动态配置热更新
-  **配置中心**：集成 Nacos，支持配置集中管理、动态刷新、环境隔离
-  **高并发架构**：Webhook 快速响应（10ms 内），业务逻辑全异步执行
-  **可扩展框架**：基于注解的指令注册，支持动态加载和热插拔

###  

| 分类 | 技术 |
|------|------|
| **后端框架** | Spring Boot 3.2.5 |
| **编程语言** | Java 17 |
| **缓存** | Redis（Lettuce 客户端，连接池） |
| **搜索引擎** | SQLite FTS5（trigram 分词，毫秒级响应） |
| **AI 能力** | 通义千问 DashScope SDK |
| **监控** | Spring Boot Actuator |
| **配置中心** | Nacos（动态配置刷新） |
| **构建工具** | Maven |
| **JSON 处理** | Jackson |
| **简化代码** | Lombok |
| **测试** | JUnit 5 + TestContainers |

---

## 2.核心功能模块

### 2.1 基础指令集

基础指令集提供日常办公所需的常用功能，所有成员均可使用。

| 指令 | 语法示例 | 功能描述 |
|------|---------|---------|
| `/help` | `/help` | 查看所有可用指令的帮助文档（交互式卡片） |
| `/ping` | `/ping` | 检测服务器连通性 |
| `/uptime` | `/uptime` | 查看系统运行时间和健康状态 |
| `/weather` | `/weather 北京` | 查询城市天气（支持上下文记忆） |
| `/translate` | `/translate Hello` | 多语言翻译（中文、英语、日语、韩语互译） |
| `/schedule` | `/schedule 1400 开会` | 创建飞书日程（支持上下文记忆） |
| `/updateschedule` | `/updateschedule` | 修改已创建的飞书日程 |
| `/group` | `/group 新群` | 创建飞书群组并添加成员（**需二次确认**） |
| `/search` | `/search 技术方案` | 搜索群内文件和知识库（基于 SQLite FTS5） |
| `/myid` | `/myid` | 获取你的飞书用户 Open ID |
| `/clear` | `/clear` | 清空对话历史和上下文 |

### 2.2 企业效率集成

集成企业常用 DevOps 工具，赋能研发团队。

#### 2.2.1 JIRA 任务管理

| 指令 | 语法示例 | 功能描述 |
|------|---------|---------|
| `/jira` | `/jira PROJ-123` | 查询 JIRA 任务详情 |
| `/jira` | `/jira create PROJ 标题` | 创建新任务 |
| `/jira` | `/jira update PROJ-123 状态` | 更新任务状态 |

> **特性**：支持本地降级模式（无需 JIRA 服务器即可测试），配置 `jira.local-fallback-file=./local_tasks.md` 即可启用。

### 2.3. 高级功能扩展

#### 2.3.1 智能降噪功能 ⭐

**功能概述**：通过消息去重和消息合并技术，有效减少通知疲劳，提升团队协作效率。

##### 核心特性

| 特性 | 说明 | 适用场景 |
|------|------|----------|
| **消息去重** | 短时间内（默认5分钟）相同内容的消息只推送一次 | 避免重复告警、重复操作通知 |
| **消息合并** | 短时间内（默认5分钟）同类消息合并为一条摘要推送 | 批量操作、频繁事件通知 |
| **立即发送** | 关键通知（如 Push 事件）跳过合并队列，确保消息顺序 | GitHub Push 通知需在代码审查通知之前到达 |

##### 降噪策略配置

```yaml
# application-prod.yaml
notification:
  noise-reduction-enabled: true  # 启用智能降噪（默认 true）
  
  dedup:
    window-seconds: 300  # 去重窗口：5分钟（300秒）
  
  batch:
    enabled: true  # 启用消息合并（默认 true）
    window-seconds: 43200  # 合并窗口：12小时
    threshold: 5  # 合并阈值：达到5条立即推送合并摘要
```

##### 已集成降噪的指令

| 指令/功能 | 事件类型 | 降噪类型 | 去重窗口 | 说明 |
|-----------|---------|---------|----------|------|
| `/ai <问题>` | AI_QUERY | **去重** | 30秒 | 避免用户重复提问相同问题 |
| `/deploy <环境>` | DEPLOY | 去重 + 合并 | 5分钟 | 避免频繁部署产生大量通知 |
| `/createbranch <仓库> <分支>` | BRANCH_CREATE | 去重 + 合并 | 5分钟 | 批量创建分支时合并通知 |
| `/review <仓库> <PR号>` | CODE_REVIEW | 去重 + 合并 | 5分钟 | 避免重复审查同一代码 |
| `/cr <仓库> <PR号>` | CODE_REVIEW | 去重 + 合并 | 5分钟 | 兼容旧命令，与 `/review` 共用降噪 |
| GitHub Push 事件 | GITHUB_PUSH | **立即发送** | - | 跳过合并队列，确保 Push 通知在代码审查通知之前到达 |
| GitHub PR 事件 | GITHUB_PR | 去重 + 合并 | 5分钟 | PR 通知合并 |
| 自动代码审查完成 | CODE_REVIEW | 去重 + 合并 | 5分钟 | 与手动审查共用降噪 |

##### 降噪效果演示

**场景1：AI 指令去重（避免重复提问）**

```bash
# 用户重复发送相同问题
用户: @智能机器人 /AI 苹果为什么是红的？
机器人: 🤖 问题：苹果为什么是红的？\n💡 回答：苹果变红是因为...

用户: @智能机器人 /AI 苹果为什么是红的？（30秒内重复）
机器人: ⚠️ 您刚才已经问过相同的问题，请等待回答或换个问题问问看～
```

**场景2：部署通知合并（减少通知疲劳）**

```bash
# 5分钟内执行5次部署
用户: /deploy dev
用户: /deploy dev
用户: /deploy dev
用户: /deploy dev
用户: /deploy dev

# 前4次不会立即发送通知
# 第5次触发合并摘要，收到一条汇总消息：
📊 **部署事件汇总**（过去 5 分钟）

1. 🚀 部署已触发（重复 5 次）

💡 共 5 条 DEPLOY 事件
```

**场景3：GitHub Push 通知顺序保证**

```bash
# push 代码到仓库
git push origin main

# 观察飞书群消息顺序（已修复）：
# 1. ✅ 先收到：🚀 GitHub Push 通知（立即发送，跳过合并队列）
# 2. ⏳ 后收到：🔍 自动代码审查完成
```

##### 如何验证降噪功能

**方法1：查看应用日志**

```bash
# 实时查看降噪日志
tail -f logs/intelligent-robot.log | grep -E "去重|合并|降噪"

# 示例输出：
# Detected duplicate message, blocked: chatId=oc_***xxxx, eventType=DEPLOY
# Message added to batch queue: chatId=oc_***xxxx, eventType=DEPLOY, queue_length=3
# Batch summary pushed: chatId=oc_***xxxx, eventType=DEPLOY, message_count=5
```

**方法2：查看 Redis 中的数据**

```bash
# 连接 Redis
redis-cli

# 查看去重 Key（格式：notify:dedup:{chatId}:{eventType}:{hash}）
KEYS notify:dedup:*

# 查看合并队列 Key（格式：notify:batch:queue:{chatId}:{eventType}）
KEYS notify:batch:*

# 查看已推送标记 Key（格式：notify:batch:sent:{chatId}:{eventType}）
KEYS notify:batch:sent:*
```

**方法3：功能测试**

```bash
# 测试 AI 去重（30秒内重复提问相同问题）
@智能机器人 /AI 今天天气怎么样？
@智能机器人 /AI 今天天气怎么样？  # 应收到去重提示

# 测试部署通知合并（5分钟内执行5次部署）
/deploy dev
/deploy dev
/deploy dev
/deploy dev
/deploy dev  # 第5次应收到合并摘要

# 测试代码审查去重（5分钟内重复审查同一 PR）
/review frontend 42
/review frontend 42  # 相同内容，应被去重拦截
```

##### 开发者指南：如何为指令添加降噪功能

**步骤1：注入 NotificationService**

```java
@Component
public class YourCommandHandler {
    
    @Autowired(required = false)
    private NotificationService notificationService;
    
    // 构造函数也需要添加 NotificationService 参数
    public YourCommandHandler(..., NotificationService notificationService) {
        this.notificationService = notificationService;
    }
}
```

**步骤2：发送带降噪的通知**

```java
private void executeCommand(CommandContext context) {
    String result = doSomething();
    
    // 发送通知（带智能降噪）
    sendNotificationWithDeduplication(context, result);
    
    return result;
}

private void sendNotificationWithDeduplication(CommandContext context, String message) {
    String chatId = context.getChatId();
    if (chatId == null || chatId.isEmpty()) {
        return;
    }

    try {
        if (notificationService != null) {
            // 使用自定义事件类型，启用智能降噪（去重 + 合并）
            boolean success = notificationService.sendNotification(chatId, "YOUR_EVENT_TYPE", message);
            if (success) {
                log.info("通知已发送（带降噪）: chatId={}", maskChatId(chatId));
            } else {
                log.info("通知被降噪拦截（去重或合并中）");
            }
        } else {
            // NotificationService 不可用，降级处理（直接发送）
            log.warn("NotificationService 不可用，通知未启用智能降噪");
        }
    } catch (Exception e) {
        log.error("发送通知失败", e);
    }
}
```

**步骤3：配置降噪策略**

在 `application-prod.yaml` 中添加配置：

```yaml
notification:
  noise-reduction-enabled: true  # 启用智能降噪
  
  dedup:
    window-seconds: 300  # 去重窗口：5分钟
  
  batch:
    enabled: true
    window-seconds: 300  # 合并窗口：5分钟
    threshold: 5  # 合并阈值：达到5条立即推送
```

##### 降噪功能架构

```
┌─────────────────────────────────────────────────────────────┐
│                    指令执行流程                              │
│  User Command → CommandHandler → Business Logic           │
└───────────────────────────┬─────────────────────────────┘
                            │ 发送通知
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                NotificationService                            │
│  • sendNotification(chatId, eventType, content)          │
│  • sendUrgentNotification(chatId, eventType, content)    │
└───────────────────────────┬─────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              智能降噪流程（两步）                            │
│                                                             │
│  第一步：消息去重（MessageDedupService）                    │
│    • 计算消息内容 MD5 哈希                                  │
│    • 基于 (chatId + eventType + hash) 构建 Redis Key      │
│    • 在去重窗口时间内，相同哈希的消息只推送一次             │
│                                                             │
│  第二步：消息合并（MessageBatchService）                    │
│    • 按 (chatId + eventType) 分组，使用 Redis List 存储   │
│    • 每条消息加入队列时，检查是否达到合并阈值              │
│    • 达到阈值立即推送合并摘要；未达阈值等待定时任务推送    │
│    • 超过合并窗口时间，无论是否达到阈值都推送              │
└───────────────────────────┬─────────────────────────────┘
                            │ 通过降噪检查
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    FeishuClient                              │
│  • sendText(chatId, content)                             │
│  • sendCard(chatId, cardContent)                          │
└─────────────────────────────────────────────────────────────┘
```

##### 常见问题

**Q1：如何临时关闭降噪功能？**

A：修改配置文件：

```yaml
notification:
  noise-reduction-enabled: false  # 关闭智能降噪
```

或设置环境变量：

```bash
export NOTIFICATION_NOISE_REDUCTION_ENABLED=false
```

**Q2：去重窗口和合并窗口可以不同吗？**

A：可以。去重窗口由 `notification.dedup.window-seconds` 控制，合并窗口由 `notification.batch.window-seconds` 控制。建议设置为相同值（如都是5分钟）。

**Q3：如何调整合并阈值？**

A：修改配置文件：

```yaml
notification:
  batch:
    threshold: 3  # 降低阈值，达到3条就推送合并摘要
```

**Q4：为什么 Push 通知需要立即发送？**

A：因为 Push 通知和代码审查通知有顺序要求：Push 通知应该在代码审查通知之前到达。如果 Push 通知被合并延迟，会导致顺序颠倒。

**Q5：如何查看降噪效果？**

A：查看应用日志：

```bash
tail -f logs/intelligent-robot.log | grep -E "去重|合并"
```

或查看 Redis 中的数据：

```bash
redis-cli KEYS "notify:*"
```

##### 性能优化建议

1. **合理设置去重窗口**：太短（如1分钟）可能导致重复通知；太长（如1小时）可能漏掉重要通知。建议5-10分钟。
2. **合理设置合并阈值**：太低（如2条）可能导致频繁推送合并摘要；太高（如10条）可能导致通知延迟。建议5条。
3. **监控 Redis 内存使用**：去重 Key 和合并队列都存储在 Redis 中，需要确保 Redis 有足够内存。
4. **使用 Redis 过期策略**：去重 Key 会自动过期（基于去重窗口），合并队列也会自动过期（基于合并窗口），无需手动清理。

---

#### 2.4.1 Git 集成与代码操作

支持 GitHub 和 GitLab 两大平台，通过**仓库别名映射**简化操作。

| 指令 | 语法示例 | 功能描述 | 权限要求 |
|------|---------|---------|----------|
| `/repo` | `/repo frontend` | 查看仓库信息（星标、Fork、Issue 等） | DEVELOPER |
| `/gitlog` | `/gitlog frontend main` | 查看 Git 提交日志 | DEVELOPER |
| `/gitdiff` | `/gitdiff frontend abc123` | 查看 Git 提交差异 | DEVELOPER |
| `/pr` | `/pr frontend 42` | 查看 GitHub PR 信息 | DEVELOPER |
| `/mergestatus` | `/mergestatus frontend 42` | 查看 PR 合并状态（CI、冲突等） | DEVELOPER |
| `/createbranch` | `/createbranch frontend feature-xxx` | 创建 Git 分支（**需二次确认**） | ADMIN |
| `/deploy` | `/deploy dev` | 触发部署流程（**需二次确认**） | ADMIN |

> **仓库别名配置**：通过环境变量 `GITHUB_REPO_ALIASES=frontend=owner/repo,backend=owner/repo` 设置，之后即可用别名代替 `owner/repo`。

#### 2.4.2 自动代码审查 (Code Review)

利用 AI（通义千问）自动分析代码质量，支持手动触发和 Webhook 自动触发。

| 指令 | 语法示例 | 功能描述 |
|------|---------|---------|
| `/review` | `/review frontend 42` | 对指定 PR 进行 AI 代码审查 |

**审查维度**：
- ✅ 代码风格检测（命名规范、格式问题）
- ✅ 潜在 Bug 分析（空指针、资源泄漏等）
- ✅ 安全漏洞扫描（SQL 注入、XSS 等）
- ✅ 性能优化建议
- ✅ 单元测试覆盖建议

**报告格式**：
```
🔍 代码审查报告

**审查目标**：PR #42 (owner/repo)
**链接**：https://github.com/owner/repo/pull/42

**评分**：⚠️ 75/100 (良好)

**问题列表**：
1. 变量命名不符合规范
2. 缺少单元测试覆盖

**修改建议**：
1. 将 tempValue 重命名为 temperatureValue
2. 为 UserService 添加单元测试

**详细分析**：
（AI 生成的详细分析报告...）
```

**自动触发**：配置 `github.webhook.auto-review=true` 后，Push/PR 事件将自动触发 AI 审查并推送结果到飞书群。

#### 2.4.3 开发效率工具

| 工具 | 描述 |
|------|------|
| **异步任务状态跟踪** | 长时间任务（如部署）支持进度查询：`GET /api/task/{taskId}` |
| **消息去重** | 5 分钟窗口内相同内容的推送只通知一次 |
| **消息合并** | 5 分钟内的同类通知合并为一条摘要 |
| **新成员欢迎** | 自动发送欢迎消息并引导使用 `/help` |

#### 2.4.4 可扩展指令框架
基于自定义 `@Command` 注解的指令注册框架，支持：

- **自动扫描**：启动时自动扫描并注册所有标注 `@Command` 的 Bean
- **权限控制**：通过 `permission` 属性控制访问权限（NONE/DEVELOPER/ADMIN）
- **上下文感知**：通过 `contextType` 属性支持多轮对话
- **模糊匹配**：输入错误时自动提示相似指令（相似度 ≥ 70%）
- **动态加载**：支持运行时动态注册/注销指令

**示例：创建一个新指令**

```java
@Command(
    name = "mycommand",
    description = "我的自定义指令",
    permission = PermissionLevel.DEVELOPER,
    contextType = "mycommand"
)
@Component
public class MyCommandHandler implements CommandHandler {
    @Override
    public void execute(CommandContext context) {
        // 你的业务逻辑
        context.reply("指令执行成功！");
    }
}
```

---

## 3.技术架构

### 系统架构图

```
┌─────────────────────────────────────────────────────────────┐
│                       飞书开放平台                            │
│  (群聊消息 / @机器人 / 成员变更 / 审批事件)                    │
└───────────────────────────┬─────────────────────────────────┘
                            │ Webhook (HTTPS)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    WebhookController                        │
│  • 10ms 内快速返回 200（避免飞书超时重试）                      │
│  • 签名验证 (HMAC SHA256)                                    │
│  • 消息加解密 (AES)                                          │
│  • 幂等性控制 (基于 event_id)                                │
│  • 限流保护 (全局 QPS + IP 级别 QPS)                          │
└───────────────────────────┬─────────────────────────────────┘
                            │ 异步提交
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    EventAsyncProcessor                      │
│  • 高优先级线程池 (核心 10，最大 50)                           │
│  • 低优先级线程池 (核心 5，最大 20)                            │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                      MessageProcessor                        │
│  • 消息解析与去重                                              │
│  • @提及检测                                                  │
│  • 上下文加载                                                 │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                     MessageDispatcher                       │
│  四级响应策略：                                                │
│  1. Level 1: /开头 + 指令存在 → 精确匹配                       │
│  2. Level 2: /开头 + 指令不存在 → 模糊匹配提示                 │
│  3. Level 3: 非/开头 + 未@ → 静默丢弃                         │
│  4. Level 4: @机器人 + 非/开头 → AI 理解意图                   │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                      CommandRegistry                        │
│  • 指令路由                                                   │
│  • 权限校验                                                   │
│  • 二次确认拦截                                               │
│  • 上下文保存                                                 │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                     CommandHandler                           │
│  (具体指令实现：GitHandler, ReviewHandler, etc.)              │
└─────────────────────────────────────────────────────────────┘
```

### 数据流转

```
用户消息
    ↓
WebhookController (10ms 返回)
    ↓ (异步)
MessageProcessor (解析、去重)
    ↓
MessageDispatcher (四级分发)
    ↓
CommandRegistry (权限、确认)
    ↓
CommandHandler (业务逻辑)
    ↓
FeishuClient (发送回复)
```

---

## 4.技术挑战与实现要点

### 4.1 Webhook 签名验证与高并发事件处理

**挑战**：飞书 Webhook 要求在 10 秒内响应，且会进行超时重试（指数退避），高并发下容易产生重复处理。

**解决方案**：
- **快速响应**：WebhookController 在 10ms 内返回 200 OK，业务逻辑全异步执行
- **签名验证**：支持飞书签名验证（HMAC SHA256），防止伪造请求
- **幂等性控制**：基于 `event_id` 的 Redis 去重，24 小时过期
- **限流保护**：全局 QPS 限制（默认 1000）+ IP 级别 QPS 限制（默认 100）
- **线程池隔离**：高优先级线程池处理消息，低优先级线程池处理事件

```java
// 幂等性控制示例
String idempotentKey = "feishu:event:idempotent:" + eventId;
if (redisTemplate.hasKey(idempotentKey)) {
    log.warn("重复事件，已忽略: {}", eventId);
    return;
}
redisTemplate.opsForValue().set(idempotentKey, "1", Duration.ofHours(24));
```

### 4.2 异步任务状态跟踪

**挑战**：部署任务可能需要数分钟，用户需要查询进度或接收完成通知。

**解决方案**：
- **任务状态机**：`PENDING → RUNNING → SUCCESS/FAILED`
- **状态查询接口**：`GET /api/task/{taskId}` 查询任务状态和日志
- **回调通知**：任务完成时自动推送结果到飞书群
- **慢任务通知**：超过 3 秒的任务自动推送"处理中"通知
- **自动清理**：每 5 分钟清理 60 分钟前的已完成任务

```java
// 任务状态查询
@GetMapping("/api/task/{taskId}")
public AsyncTaskStatus getTaskStatus(@PathVariable String taskId) {
    return taskStatusService.getTaskStatus(taskId);
}
```

### 4.3 安全性：敏感操作二次确认

**挑战**：部署、创建分支等敏感操作必须有额外确认机制，防止误操作。

**解决方案**：
- **二次确认流程**：
  1. 用户触发敏感指令（/deploy、/createbranch、/group）
  2. 系统生成 `confirmToken`，存储操作信息到 Redis（TTL 5 分钟）
  3. 返回确认提示："是否执行 XXX？请回复 `/confirm <token>`"
  4. 用户回复确认指令
  5. 系统读取 Redis，执行操作

- **权限控制**：RBAC 模型，支持三个级别：
  - `NONE`：公开指令，所有用户可访问
  - `DEVELOPER`：需要开发者权限（读写操作）
  - `ADMIN`：需要管理员权限（敏感操作）

- **动态配置**：支持通过 API 动态更新权限名单，无需重启服务

```bash
# 添加用户到管理员名单
POST /api/auth/users
{
  "permissionLevel": "ADMIN",
  "openIds": ["ou_xxx"]
}
```

---

## 5.快速开始 (Quick Start)

### 前置要求

- **JDK 17** 或更高版本
- **Redis** 6.0 或更高版本
- **Maven** 3.6 或更高版本
- **飞书开放平台** 应用（需要 App ID 和 App Secret）

### 5.1 克隆项目

```bash
git clone https://github.com/your-username/IntelligentRobot.git
cd IntelligentRobot
```

### 5.2 配置环境变量

IntelligentRobot 支持两种配置方式：**本地 `.env` 文件** 和 **Nacos 配置中心**，推荐使用 Nacos 实现配置集中管理。

---

#### 方式一：使用 Nacos 配置中心（推荐生产环境）

Nacos 提供配置集中管理、动态刷新、环境隔离等能力，适合生产环境使用。

**步骤 1：启动 Nacos 服务器**

```bash
# 方式 A：本地单机启动
startup.cmd -m standalone

# 方式 B：Docker 启动
docker run -d --name nacos -p 8848:8848 -e MODE=standalone nacos/nacos-server:latest
```

访问控制台：http://localhost:8848/nacos（默认账号/密码：`nacos/nacos`）

**步骤 2：在 Nacos 中创建配置**

1. 登录 Nacos 控制台 → 命名空间 → 新建命名空间（`dev` / `test` / `prod`）
2. 配置管理 → 配置列表 → 点击「+」
3. 填写配置：
   - **Data ID**：`IntelligentRobot.yaml`
   - **Group**：`DEFAULT_GROUP`
   - **配置格式**：`YAML`
   - **配置内容**：从 `docs/NACOS_CONFIG_TEMPLATE.yaml` 复制，并填写实际值
4. 点击「发布」

**步骤 3：设置环境变量并启动**

```bash
# Windows (cmd)
set NACOS_SERVER_ADDR=localhost:8848
set NACOS_NAMESPACE=dev
java -jar target/IntelligentRobot-0.0.1-SNAPSHOT.jar

# Linux/Mac
export NACOS_SERVER_ADDR=localhost:8848
export NACOS_NAMESPACE=dev
java -jar target/IntelligentRobot-0.0.1-SNAPSHOT.jar
```

> 📖 完整 Nacos 集成指南请参考：`docs/NACOS_CONFIG_GUIDE.md`

---

#### 方式二：使用 `.env` 文件配置（适合本地开发）

1. **复制模板文件**：
   ```bash
   # Linux/Mac
   cp .env.example .env
   
   # Windows
   copy .env.example .env
   ```

2. **编辑 `.env` 文件**：
   - 用文本编辑器打开 `.env` 文件
   - 填写必填配置项（见下方列表）
   - 保存文件

3. **启动服务**：
   ```bash
   # Windows
   start.bat
   
   # Linux/Mac
   ./mvnw spring-boot:run
   ```

**【备选方法】直接设置环境变量**

如果不使用 `.env` 文件，也可以直接设置系统环境变量：

```bash
# Linux/Mac
export FEISHU_APP_ID=your_app_id
export FEISHU_APP_SECRET=your_app_secret

# Windows (cmd)
set FEISHU_APP_ID=your_app_id
set FEISHU_APP_SECRET=your_app_secret
```

---

#### 配置项说明

> 📋 **配置参考**：所有可用配置项及详细说明，请直接参考 `docs/NACOS_CONFIG_TEMPLATE.yaml` 文件。该文件包含了完整的配置模板，将所有 `your_xxx_here` 替换为实际值即可使用。

##### 【必填】飞书配置
```bash
# 飞书应用 ID（在飞书开放平台 → 应用管理 → 凭证与基础信息 中获取）
FEISHU_APP_ID=your_app_id

# 飞书应用密钥（同上）
FEISHU_APP_SECRET=your_app_secret

# 飞书加密密钥（可选，启用消息加密时需要）
FEISHU_ENCRYPT_KEY=your_encrypt_key
```

##### 【必填】Redis 配置
```bash
# Redis 主机地址（默认 localhost）
REDIS_HOST=localhost

# Redis 端口（默认 6379）
REDIS_PORT=6379

# Redis 密码（如无密码留空）
REDIS_PASSWORD=
```

##### 【可选】AI 配置（启用代码审查时需要）
```bash
# 通义千问 API Key（在阿里云 DashScope 控制台获取）
QIANWEN_API_KEY=your_qianwen_api_key

# 通义千问模型（默认 qwen-turbo）
QIANWEN_MODEL=qwen-turbo
```

##### 【可选】GitHub 配置（启用 Git 集成时需要）
```bash
# GitHub 个人访问令牌
GITHUB_TOKEN=your_github_token

# GitHub Webhook 密钥
GITHUB_WEBHOOK_SECRET=your_webhook_secret

# 仓库别名映射（格式：别名=owner/repo）
GITHUB_REPO_ALIASES=frontend=owner/repo,backend=owner/repo

# 管理员 Open ID（逗号分隔）
GITHUB_ADMIN_OPEN_IDS=ou_xxx,ou_yyy

# 开发者 Open ID（逗号分隔）
GITHUB_DEVELOPER_OPEN_IDS=ou_aaa,ou_bbb
```

##### 【可选】JIRA 配置
```bash
JIRA_URL=https://your-domain.atlassian.net
JIRA_USERNAME=your_email
JIRA_API_TOKEN=your_api_token
```

##### 【可选】高德天气 API
```bash
AMAP_KEY=your_amap_api_key
```

##### 【可选】其他配置
```bash
# 搜索引擎配置
SEARCH_INDEX_PATH=./search-index.db
SEARCH_STARTUP_DELAY_MS=30000
SEARCH_API_CALL_DELAY_MS=100

# 上下文管理器配置
CONTEXT_GLOBAL_PARAM_TTL_MINUTES=30

# 欢迎消息配置
WELCOME_BATCH_WINDOW_MS=5000

# 任务状态配置
TASK_STATUS_MAX_LOGS_LENGTH=10000
TASK_MONITOR_CHAT_ID=oc_xxx
```

> **提示**：完整的配置项说明请参考 `.env.example` 文件，包含所有可用配置项的详细说明和默认值。

### 5.3 构建项目

```bash
# Linux/Mac
./mvnw clean package -DskipTests

# Windows
mvnw.cmd clean package -DskipTests
```

### 5.4 启动服务

```bash
java -jar target/IntelligentRobot-0.0.1-SNAPSHOT.jar
```

服务默认运行在 `http://localhost:8082`。

### 5.5 验证部署

```bash
# 健康检查
curl http://localhost:8082/feishu/health

# 测试飞书 Webhook（模拟飞书事件）
curl -X POST http://localhost:8082/feishu/webhook \
  -H "Content-Type: application/json" \
  -d '{"header":{"event_id":"test-001"},"event":{"type":"im.message.receive_v1"}}'
```

---

## 6.指令大全 (Command List)

### 6.1权限说明

| 权限级别 | 说明 | 标识 |
|---------|------|------|
| **NONE** | 公开指令，所有用户可访问 | 🟢 |
| **DEVELOPER** | 需要开发者权限（读写操作） | 🟡 |
| **ADMIN** | 需要管理员权限（敏感操作） | 🔴 |

### 6.2完整指令表

| 指令 | 权限 | 上下文感知 | 二次确认 | 描述 |
|------|------|-----------|---------|------|
| `/help` | 🟢 | ❌ | ❌ | 查看帮助文档 |
| `/ping` | 🟢 | ❌ | ❌ | 连通性测试 |
| `/uptime` | 🟢 | ❌ | ❌ | 系统运行时间 |
| `/weather` | 🟢 | ✅ | ❌ | 天气查询 |
| `/translate` | 🟢 | ❌ | ❌ | 中英互译 |
| `/updateschedule` | 🟢 | ✅ | ❌ | 修改日程 |
| `/schedule` | 🟢 | ✅ | ❌ | 创建日程 |
| `/group` | 🟢 | ❌ | ✅ | 创建群组 |
| `/search` | 🟢 | ❌ | ❌ | 搜索知识库 |
| `/myid` | 🟢 | ❌ | ❌ | 获取用户 ID |
| `/clear` | 🟢 | ❌ | ❌ | 清空上下文 |
| `/test_ai` | 🟢 | ❌ | ❌ | 测试 AI 功能 |
| `/jira` | 🟡 | ❌ | ❌ | JIRA 任务管理 |
| `/github` | 🟡 | ❌ | ❌ | GitHub Actions 管理 |
| `/gitlab` | 🟡 | ❌ | ❌ | GitLab CI/CD 管理 |
| `/gitlog` | 🟡 | ✅ | ❌ | 查看提交日志 |
| `/gitdiff` | 🟡 | ✅ | ❌ | 查看代码差异 |
| `/createbranch` | 🔴 | ❌ | ✅ | 创建分支 |
| `/review` | 🟡 | ❌ | ❌ | 代码审查 |
| `/mergestatus` | 🟡 | ✅ | ❌ | 查看 PR 状态 |
| `/pr` | 🟡 | ✅ | ❌ | 查看 PR 信息 |
| `/repo` | 🟡 | ✅ | ❌ | 查看仓库信息 |
| `/deploy` | 🔴 | ❌ | ✅ | 触发部署（支持 --target 指定部署目标） |
| `/monitor` | 🟡 | ❌ | ❌ | 服务监控 |
| `/confirm` | 🟢 | ❌ | ❌ | 确认敏感操作 |

### 6.3上下文感知说明

支持上下文感知的指令会**记住用户上次输入的参数**，下次执行时如未提供参数，自动使用历史值。

**示例**：

```
用户: /weather 北京
机器人: 北京天气：晴，25°C

（5分钟内）
用户: /weather
机器人: 北京天气：多云，23°C  （自动使用"北京"）
```

上下文类型分为：
- **全局参数**：整个会话期间有效（如默认城市）
- **局部参数**：仅同类型指令有效（如 gitlog 的分支名）

上下文超时时间：**5 分钟**无活动自动清理。

---

## 6.4功能详细说明

### 6.4.1 翻译功能 (`/translate`)

#### 支持的翻译语言

- **中文** (中文)
- **英语** (English)
- **日语** (日本語)
- **韩语** (한국어)

#### 指令格式

1. **自动检测语言**：`/translate <文本>`
   - 系统自动检测源语言，并翻译为合适的目标语言
   - 规则：非中文 → 中文，中文 → 英语
   - 示例：`/translate Hello World` → `你好世界`
   - 示例：`/translate 你好` → `Hello`

2. **指定目标语言**：`/translate <目标语言> <文本>`
   - 自动检测源语言，翻译为指定语言
   - 支持语言：中文、英语、日语、韩语
   - 示例：`/translate 日语 Hello` → `こんにちは`
   - 示例：`/translate 中文 こんにちは` → `你好`

3. **指定源语言和目标语言**：`/translate <源语言> <目标语言> <文本>`
   - 精确控制翻译方向
   - 示例：`/translate 英语 日语 Hello` → `こんにちは`
   - 示例：`/translate 中文 韩语 你好` → `안녕하세요`

#### 使用示例

```
用户: /translate Hello World
机器人: 🌐 翻译结果（英语 → 中文）：
你好世界

用户: /translate 日语 你好
机器人: 🌐 翻译结果（中文 → 日语）：
こんにちは

用户: /translate 中文 韩语 안녕하세요
机器人: 🌐 翻译结果（韩语 → 中文）：
你好

用户: /translate 英语 韩语 Hello
机器人: 🌐 翻译结果（英语 → 韩语）：
안녕하세요
```

#### 功能特性

- ✅ 自动检测语言（中文、英语、日语、韩语）
- ✅ 支持任意语言组合互译
- ✅ 文本长度限制：500 字符
- ✅ 支持中文别名指令：`@机器人 翻译 <文本>`

#### 注意事项

- ⚠️ 源语言和目标语言不能相同
- ⚠️ 文本长度不能超过 500 字符
- ⚠️ 日语和韩语检测基于字符集（平假名、片假名、韩文字母）

---

### 6.4.2 部署功能 (`/deploy`)

#### 指令格式

1. **基本部署**：`/deploy <环境>`
   - 触发指定环境的部署流程
   - 需要二次确认（防误操作）
   - 示例：`/deploy dev` → 部署到开发环境

2. **指定部署目标**：`/deploy <环境> --target <目标>`
   - 部署到指定环境的特定目标服务器
   - 示例：`/deploy prod --target aliyun` → 部署到生产环境（阿里云）

#### 可用环境

| 环境参数 | 环境名称 | 说明 |
|---------|---------|------|
| `dev` | 开发环境 | 用于开发和测试 |
| `test` | 测试环境 | 用于集成测试 |
| `staging` | 预发布环境 | 用于预发布验证 |
| `prod` / `production` | 生产环境 | 用于正式发布（需要额外确认） |

#### 部署目标（--target 参数）

| 目标参数 | 说明 |
|---------|------|
| `default` | 默认服务器 |
| `server1` | 服务器1 |
| `server2` | 服务器2 |
| `aliyun` | 阿里云 |
| `aws` | AWS（亚马逊云） |
| `tencent` | 腾讯云 |

#### 使用示例

```
用户: /deploy dev
机器人: ⚠️ 敏感操作确认

📦 操作：部署到 dev 环境
📡 部署目标：default
👤 操作者：xxx
🕐 时间：2026-06-07 22:00:00

❗ 请输入以下命令确认部署：
`/deploy dev --confirm abc123`

⏰ 确认令牌有效期：5 分钟
💡 如需取消，请忽略此消息

用户: /deploy dev --confirm abc123
机器人: 🚀 部署已触发

📦 环境：dev
📡 部署目标：default
📂 仓库：Claire-bit-cpu/Test
🔧 工作流：deploy-dev.yml
👤 操作者：xxx
🕐 时间：2026-06-07 22:00:00
🆔 部署 ID：deploy-dev-20260607220000-abc123

📢 部署完成后将通过飞书通知您

🔗 GitHub Actions: Claire-bit-cpu/Test/actions
```

#### 部署目标示例

```
# 部署到开发环境（默认目标）
用户: /deploy dev

# 部署到测试环境（阿里云）
用户: /deploy test --target aliyun

# 部署到生产环境（腾讯云）
用户: /deploy prod --target tencent

# 部署到预发布环境（AWS）
用户: /deploy staging --target aws
```

#### 功能特性

- ✅ 二次确认机制（防误操作）
- ✅ 支持多个部署目标（default、server1、server2、aliyun、aws、tencent）
- ✅ 部署完成后自动发送飞书通知
- ✅ 生产环境需要额外确认
- ✅ 支持 GitHub Actions 回调通知（真正的部署成功/失败检测）
- ✅ 未配置 GitHub 时进入模拟模式（用于测试）

#### 配置说明

##### GitHub 配置（application.yaml）

```yaml
github:
  token: ${GITHUB_TOKEN:}  # GitHub Personal Access Token
  api-url: https://api.github.com
  webhook-secret: ${GITHUB_WEBHOOK_SECRET:}
  repo-aliases: ${GITHUB_REPO_ALIASES:}  # 仓库别名配置
  deploy: ${GITHUB_DEPLOY:}  # 部署配置
```

##### 部署配置示例

```yaml
github:
  deploy: "dev:Claire-bit-cpu/Test:deploy-dev.yml:develop,test:Claire-bit-cpu/Test:deploy-test.yml:test"
```

格式：`环境:仓库名:工作流文件名:分支名`

##### 回调配置（可选）

如果需要真正的部署成功/失败检测，配置回调：

```yaml
github:
  callback:
    url: ${GITHUB_CALLBACK_URL:}
    secret: ${GITHUB_CALLBACK_SECRET:}
```

#### 通知说明

部署完成后，系统会发送飞书通知：

- ✅ **部署成功**：`✅ Java 部署成功`
- ❌ **部署失败**：`❌ Java 部署失败`
-  环境、部署目标、仓库、分支、操作者、时间等详细信息

#### 注意事项

- ⚠️ 部署操作需要开发者权限（`DEVELOPER` 级别）
- ⚠️ 生产环境部署需要额外确认
- ⚠️ 确认令牌有效期：5 分钟
- ⚠️ 需要配置 `notification.default-chat-ids` 才能收到部署通知

---

## 7.Webhook 配置教程

### 7.1 飞书 Webhook 配置

#### 步骤 1：创建飞书应用

1. 访问 [飞书开放平台](https://open.feishu.cn/)
2. 点击「创建企业自建应用」
3. 填写应用名称和描述
4. 记录 `App ID` 和 `App Secret`

#### 步骤 2：配置机器人能力

1. 在应用管理页面，开启「机器人」能力
2. 配置权限：
   - `im:message` - 获取与发送消息
   - `im:message.group_at_msg` - 获取群聊中 @机器人 的消息
   - `im:chat` - 获取群信息
   - `contact:user.base` - 获取用户基本信息
   - `approval:approval` - 查看审批信息

#### 步骤 3：配置事件订阅

1. 在「事件订阅」页面，配置请求网址：
   ```
   https://your-domain.com/feishu/webhook
   ```
2. 飞书会发送验证请求，服务启动后自动通过验证
3. 订阅以下事件：
   - `im.message.receive_v1` - 接收消息
   - `im.chat.member.user.added_v1` - 成员入群
   - `im.chat.member.bot.added_v1` - 机器人入群
   - `approval.instance.state_change_v4` - 审批状态变更

#### 步骤 4：检查环境变量是否配置

```bash
export FEISHU_APP_ID=your_app_id
export FEISHU_APP_SECRET=your_app_secret
```

#### 步骤 5：测试

将机器人添加到群聊，@机器人 并输入 `/help`，如收到帮助卡片即配置成功。

### 2. GitHub Webhook 配置

#### 步骤 1：配置 GitHub Webhook

1. 打开 GitHub 仓库设置
2. 进入「Settings」→「Webhooks」→「Add webhook」
3. 配置：
   - **Payload URL**: `https://your-domain.com/github-webhook`
   - **Content type**: `application/json`
   - **Secret**: 设置一个密钥（与 `GITHUB_WEBHOOK_SECRET` 相同）
   - **Events**: 选择 `Push` 和 `Pull request`
4. 点击「Add webhook」

#### 步骤 2：配置环境变量

```bash
export GITHUB_TOKEN=your_github_personal_access_token
export GITHUB_WEBHOOK_SECRET=your_webhook_secret
export GITHUB_REPO_ALIASES=frontend=owner/repo
```

#### 步骤 3：测试

在配置的仓库中创建一个测试分支，如收到飞书通知即配置成功。

### 3. 启用加密通信（可选）

如需启用飞书消息加密：

1. 在飞书应用管理页面，点击「凭证与基础信息」
2. 点击「重置 Encrypt Key」获取加密密钥
3. 配置环境变量：

```bash
export FEISHU_ENCRYPT_KEY=your_encrypt_key
```

---

## 8.开发指南 (Developer Guide)

### 插件机制：如何添加自定义指令

IntelligentRobot 采用基于注解的指令注册框架，添加新指令非常简单。

#### 步骤 1：创建指令处理器类

在 `service/handler` 目录下创建新的 Handler 类：

```java
package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.dto.PermissionLevel;
import com.example.IntelligentRobot.service.handler.CommandHandler;
import org.springframework.stereotype.Component;

@Command(
    name = "mycommand",                          // 指令名称（小写）
    description = "这是我的自定义指令",               // 指令描述（显示在 /help 中）
    permission = PermissionLevel.DEVELOPER,       // 权限要求
    contextType = "mycommand",                   // 上下文类型（支持多轮对话）
    confirm = false                              // 是否需要二次确认
)
@Component
public class MyCommandHandler implements CommandHandler {
    
    @Override
    public void execute(CommandContext context) {
        // 1. 获取指令参数
        String[] args = context.getArgs();
        String userId = context.getUserId();
        String chatId = context.getChatId();
        
        // 2. 业务逻辑处理
        String result = doSomething(args);
        
        // 3. 回复用户
        context.reply(result);
        
        // 4. 保存上下文（如需要）
        if (args.length > 0) {
            context.setContext("lastInput", args[0]);
        }
    }
    
    private String doSomething(String[] args) {
        // 你的业务逻辑
        return "处理完成！";
    }
}
```

#### 步骤 2：重启服务

新指令会在服务启动时自动扫描并注册，无需额外配置。

```bash
# 重启服务
./mvnw spring-boot:run
```

#### 步骤 3：测试新指令

在飞书群中 @机器人 并输入：

```
/help  # 确认新指令已显示
/mycommand 参数1 参数2  # 测试新指令
```

### 高级功能

#### 1. 访问外部 API

```java
@Component
public class MyCommandHandler implements CommandHandler {
    
    @Autowired
    private RestTemplate restTemplate;
    
    @Override
    public void execute(CommandContext context) {
        String url = "https://api.example.com/data";
        String response = restTemplate.getForObject(url, String.class);
        context.reply(response);
    }
}
```

#### 2. 发送飞书消息

```java
@Autowired
private FeishuClient feishuClient;

@Override
public void execute(CommandContext context) {
    // 发送文本消息
    feishuClient.sendText(context.getChatId(), "Hello, World!");
    
    // 发送交互式卡片
    String card = buildCardJson();
    feishuClient.sendCard(context.getChatId(), card);
}
```

#### 3. 异步处理耗时任务

```java
@Override
public void execute(CommandContext context) {
    // 创建异步任务
    String taskId = taskStatusService.createTask(context.getUserId(), "我的任务");
    
    // 提交异步任务
    taskExecutor.execute(() -> {
        try {
            taskStatusService.updateStatus(taskId, TaskState.RUNNING);
            
            // 耗时操作
            Thread.sleep(5000);
            
            taskStatusService.updateStatus(taskId, TaskState.SUCCESS);
            feishuClient.sendText(context.getChatId(), "任务完成！");
        } catch (Exception e) {
            taskStatusService.updateStatus(taskId, TaskState.FAILED);
            feishuClient.sendText(context.getChatId(), "任务失败：" + e.getMessage());
        }
    });
    
    // 立即回复任务 ID
    context.reply("任务已提交，ID: " + taskId + "，使用 /task " + taskId + " 查询状态");
}
```

### 指令注解参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | String | ✅ | 指令名称，必须唯一 |
| `description` | String | ✅ | 指令描述，显示在 `/help` 中 |
| `permission` | PermissionLevel | ❌ | 权限要求，默认 `NONE` |
| `contextType` | String | ❌ | 上下文类型，默认 `""`（不支持上下文） |
| `confirm` | boolean | ❌ | 是否需要二次确认，默认 `false` |

---

## 9.交付产物说明

### 9.1 可部署的机器人后端

- **可执行 JAR**：`IntelligentRobot-0.0.1-SNAPSHOT.jar`
- **配置方式**：所有配置项均支持通过环境变量覆盖（无需修改配置文件）
- **可选生产配置**：`application-prod.yaml`（放在 JAR 同级目录，生产环境推荐使用）
- **启动脚本**：`mvnw` (Linux/Mac) / `mvnw.cmd` (Windows)

### 9.2 可公开测试的飞书群

- 将机器人添加到测试群聊
- 成员可 @机器人 测试所有指令
- 支持多群同时接入（通过 `chat_id` 区分）
- 加入测试群方法：
1. 先加入测试企业，点击链接 https://test-diiw8liupxq8.feishu.cn/invite/member/rES_3GR1zNQ
2. 再扫测试群二维码加入测试群
![测试群二维码](docs/测试群二维码.png)

### 9.3 测试视频（8~12 分钟）

- 视频链接：https://t.bilibili.com/1211213221707382791?share_source=pc_native
- 视频涵盖以下演示内容：

| 时间段 | 演示内容 |
|--------|---------|
| 0~1 分钟 | 项目介绍与架构概览 |
| 1~3 分钟 | 基础指令演示（`/help`, `/ping`, `/weather`, `/translate`） |
| 3~5 分钟 | Git 集成演示（`/repo`, `/gitlog`, `/gitdiff`, `/pr`） |
| 5~8 分钟 | 自动代码审查演示（手动触发 + Webhook 自动触发） |
| 8~10 分钟 | 部署触发演示（`/deploy`，含二次确认） |
| 10~12 分钟 | 高级功能演示（上下文感知、搜索、监控） |

### 9.4 文档清单

####  核心文档
- ✅ `docs/README.md` - 项目完整文档（本文档）
- ✅ `docs/DEPLOY_CONFIG.md` - 部署配置完全指南（含方案 A/B）
- ✅ `docs/NACOS_CONFIG_GUIDE.md` - Nacos 配置中心集成指南
- ✅ `docs/NACOS_CONFIG_TEMPLATE.yaml` - Nacos 配置模板

####  项目文档
- ✅ `docs/需求文档.md` - 项目需求文档
- ✅ `docs/答辩文档.md` - 项目答辩文档

####  配置示例
- ✅ `docs/deploy-dev.yml.example` - GitHub Actions 工作流示例

####  使用指南
- ✅ `docs/guides/JIRA-LOCAL-MODE.md` - JIRA 本地模式指南
- ✅ `docs/guides/智能降噪集成指南.md` - 智能降噪配置指南

####  资源文件
- ✅ `docs/测试群二维码.png` - 测试群二维码

---

## 配置说明

IntelligentRobot 支持两种配置方式：**本地配置文件（`.env` + `application.yaml`）** 和 **Nacos 配置中心**，两种方式可以同时使用，Nacos 配置优先级更高。

### 配置方式对比

| 特性 | 本地 `.env` | Nacos 配置中心 |
|------|-------------|----------------|
| 配置位置 | 本地文件 | 集中式服务器 |
| 动态刷新 | ❌ 需重启 | ✅ 无需重启 |
| 环境隔离 | 手动维护多套 | 命名空间自动隔离 |
| 敏感配置安全 | 本地文件风险 | 集中管理 + 权限控制 |
| 版本管理 | Git | 内置版本历史 + 一键回滚 |
| 推荐场景 | 本地开发 | 测试/生产环境 |

### 方式一：Nacos 配置中心（推荐）

<details>
<summary>点击查看 Nacos 配置说明</summary>

#### Nacos 配置优先级

配置加载优先级（从高到低）：
1. **Nacos 配置中心**（最高优先级，会覆盖本地配置）
2. **本地 `application.yaml`**（兜底配置）
3. **环境变量**（`${ENV_VAR:default}` 语法）
4. **默认值**（最低优先级）

#### Nacos 环境隔离

通过命名空间（Namespace）实现环境隔离：

| 命名空间 | 说明 | 对应 `NACOS_NAMESPACE` |
|---------|------|------------------------|
| `dev` | 开发环境 | `dev` |
| `test` | 测试环境 | `test` |
| `prod` | 生产环境 | `prod` |

同一个 JAR 包，通过环境变量切换配置：
```bash
# 开发环境启动
set NACOS_NAMESPACE=dev
java -jar IntelligentRobot.jar

# 生产环境启动
set NACOS_NAMESPACE=prod
java -jar IntelligentRobot.jar
```

#### Nacos 动态刷新

修改 Nacos 配置后点击「发布」，应用会在 **3~5 秒内自动刷新**，无需重启。

已支持动态刷新的配置类（`@RefreshScope`）：
- `FeishuProperties` - 飞书配置
- `WelcomeConfig` - 欢迎消息配置
- `GitLabConfig` - GitLab 配置
- `GitHubConfig` - GitHub 配置
- `QwenClient` - 通义千问配置

#### Nacos 环境变量

| 环境变量 | 必填 | 默认值 | 说明 |
|---------|------|--------|------|
| `NACOS_SERVER_ADDR` | ✅ | `localhost:8848` | Nacos 服务器地址 |
| `NACOS_NAMESPACE` | ✅ | `public` | 命名空间 ID |
| `NACOS_GROUP` | ❌ | `DEFAULT_GROUP` | 配置分组 |
| `NACOS_USERNAME` | ❌ | - | Nacos 用户名（开启认证后必填） |
| `NACOS_PASSWORD` | ❌ | - | Nacos 密码（开启认证后必填） |

#### Nacos 配置模板

完整的 Nacos 配置模板已生成在 `docs/NACOS_CONFIG_TEMPLATE.yaml`，直接复制内容到 Nacos 控制台即可。

`.env` 变量与 Nacos YAML 路径对应关系：

| `.env` 变量名 | Nacos YAML 路径 |
|---------------|-----------------|
| `FEISHU_APP_ID` | `feishu.app-id` |
| `FEISHU_APP_SECRET` | `feishu.app-secret` |
| `REDIS_HOST` | `spring.data.redis.host` |
| `QIANWEN_API_KEY` | `qianwen.api-key` |
| `GITHUB_TOKEN` | `github.token` |
| `AMAP_KEY` | `amap.key` |
| `SERVER_PORT` | `server.port` |

> 📖 完整 Nacos 集成指南请参考：`docs/NACOS_CONFIG_GUIDE.md`

</details>

### 方式二：本地配置文件

<details>
<summary>点击展开 application.yaml 配置说明</summary>

```yaml
# ==================== 基础配置 ====================
spring:
  application:
    name: IntelligentRobot
  main:
    allow-circular-references: true
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      database: ${REDIS_DATABASE:0}

# ==================== 飞书配置 ====================
feishu:
  app-id: ${FEISHU_APP_ID:}
  app-secret: ${FEISHU_APP_SECRET:}
  api-base-url: https://open.feishu.cn/open-apis
  encrypt-key: ${FEISHU_ENCRYPT_KEY:}
  approval-definition-code: ${FEISHU_APPROVAL_DEFINITION_CODE:}

# ==================== AI 配置 ====================
qianwen:
  api-key: ${QIANWEN_API_KEY:}
  api-url: https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation
  model: ${QIANWEN_MODEL:qwen-turbo}
  max-diff-length: ${QIANWEN_MAX_DIFF_LENGTH:8000}

# ==================== GitHub 配置 ====================
github:
  token: ${GITHUB_TOKEN:}
  api-url: ${GITHUB_API_URL:https://api.github.com}
  webhook-secret: ${GITHUB_WEBHOOK_SECRET:}
  webhook:
    auto-review: ${GITHUB_WEBHOOK_AUTO_REVIEW:true}
  repo-aliases: ${GITHUB_REPO_ALIASES:}
  admin-open-ids: ${GITHUB_ADMIN_OPEN_IDS:}
  developer-open-ids: ${GITHUB_DEVELOPER_OPEN_IDS:}
  deploy: ${GITHUB_DEPLOY:}

# ==================== GitLab 配置 ====================
gitlab:
  api-url: ${GITLAB_API_URL:https://gitlab.com/api/v4}
  token: ${GITLAB_TOKEN:}
  enabled: ${GITLAB_ENABLED:false}

# ==================== JIRA 配置 ====================
jira:
  url: https://your-domain.atlassian.net
  username: ${JIRA_USERNAME:}
  api-token: ${JIRA_API_TOKEN:}
  enabled: ${JIRA_ENABLED:false}
  local-fallback-file: ${JIRA_LOCAL_FALLBACK_FILE:./local_tasks.md}

# ==================== 通知配置 ====================
notification:
  db-path: ${NOTIFICATION_DB_PATH:./notification.db}
  default-chat-ids: ${NOTIFICATION_DEFAULT_CHAT_IDS:}
  noise-reduction-enabled: ${NOTIFICATION_NOISE_REDUCTION_ENABLED:true}

# ==================== 搜索配置 ====================
search:
  index-path: ${SEARCH_INDEX_PATH:./search-index.db}
  startup-delay-ms: ${SEARCH_STARTUP_DELAY_MS:30000}
  api-call-delay-ms: ${SEARCH_API_CALL_DELAY_MS:300}
  sync-interval-ms: ${SEARCH_SYNC_INTERVAL_MS:7200000}
  max-sync-messages-per-chat: ${SEARCH_MAX_SYNC_MESSAGES_PER_CHAT:500}
  sync-chat-ids: ${SEARCH_SYNC_CHAT_IDS:auto}
  sync-wiki-space-ids: ${SEARCH_SYNC_WIKI_SPACE_IDS:auto}

# ==================== 线程池配置 ====================
thread-pool:
  high-priority:
    core-size: ${THREAD_POOL_HIGH_CORE_SIZE:10}
    max-size: ${THREAD_POOL_HIGH_MAX_SIZE:50}
    queue-capacity: ${THREAD_POOL_HIGH_QUEUE_CAPACITY:1000}
  low-priority:
    core-size: ${THREAD_POOL_LOW_CORE_SIZE:5}
    max-size: ${THREAD_POOL_LOW_MAX_SIZE:20}
    queue-capacity: ${THREAD_POOL_LOW_QUEUE_CAPACITY:2000}

# ==================== 限流配置 ====================
ratelimit:
  global-qps: ${RATELIMIT_GLOBAL_QPS:1000}
  ip-qps: ${RATELIMIT_IP_QPS:100}
  enabled: ${RATELIMIT_ENABLED:true}
```

</details>

---

## 贡献指南

欢迎提交 Issue 和 Pull Request！

### 开发流程

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 打开 Pull Request

### 代码规范

- 遵循 [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/)
- 确保新增功能包含单元测试

---

## 联系方式

- **邮箱**:  3203264696@qq.com
- **QQ**:  3203264696

---

<div align="center">

**⭐ 如果这个项目对你有帮助，请给它一个星标！ ⭐**

Made with ❤️ by IntelligentRobot Team

</div>
