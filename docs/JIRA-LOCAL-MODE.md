# JIRA 本地降级模式使用指南

## 功能概述

当无法连接 JIRA 云服务时，系统会自动启用**本地降级模式**，将任务记录到本地 Markdown 文件中，确保团队任务管理流程不中断。

---

## 工作原理

### 模式检测

系统启动时会自动检测 JIRA 配置：

```java
// 检测逻辑（JiraClient.java）
public boolean isConfigured() {
    return enabled && !username.isEmpty() && !apiToken.isEmpty() && !jiraUrl.contains("your-domain");
}

public boolean isLocalMode() {
    return !isConfigured();
}
```

**判断条件**：
- ✅ 已配置：`jira.enabled=true` + 有效的 `username` + 有效的 `apiToken` + 有效的 `jiraUrl`
- ⚠️ 本地模式：以上任一条件不满足

---

## 配置方式

### 方式1：禁用 JIRA（纯本地模式）

```yaml
# application.yaml
jira:
  enabled: false  # 禁用 JIRA 云服务
  local-fallback-file: ./local_tasks.md  # 本地任务文件位置
```

**效果**：
- 所有 `/jira` 指令都会写入本地文件
- 不尝试连接 JIRA 云服务

---

### 方式2：启用 JIRA 但配置无效（自动降级）

```yaml
# application.yaml
jira:
  enabled: true  # 启用 JIRA
  url: https://your-domain.atlassian.net  # 默认占位符（无效）
  username: ""  # 空用户名
  api-token: ""  # 空 API Token
  local-fallback-file: ./local_tasks.md
```

**效果**：
- 系统会先尝试连接 JIRA
- 如果连接失败，自动降级到本地模式
- 适合网络不稳定或临时无法访问 JIRA 的场景

---

### 方式3：正常连接 JIRA（云端模式）

```yaml
# application.yaml
jira:
  enabled: true
  url: https://your-company.atlassian.net  # 有效的 JIRA 地址
  username: your-email@company.com  # 有效的用户名
  api-token: xxxxxxxx  # 有效的 API Token
  local-fallback-file: ./local_tasks.md  # 降级时的备份文件
```

**效果**：
- 所有操作都发送到 JIRA 云服务
- 如果 API 调用失败，自动降级到本地模式（作为备份）

---

## 使用指南

### 1. 查询任务

#### 指令格式
```
/jira <任务编号>
```

#### 示例

**本地模式**：
```
用户: /jira PROJ-1

系统回复:
📋 本地任务详情（离线模式）

🔖 编号：PROJ-1
📂 来源：./local_tasks.md

📄 任务内容：
## PROJ-1

- **标题**: 修复登录Bug
- **类型**: Bug
- **状态**: 待处理
- **优先级**: 中
- **负责人**: 未分配
- **报告人**: 本地用户
- **创建时间**: 2026-05-27 14:30:00
- **描述**: 用户无法登录系统

---

💡 当前处于本地离线模式，已将任务记录至本地文件中
```

**云端模式**：
```
用户: /jira PROJ-123

系统回复:
📋 JIRA 任务详情

🔖 编号：PROJ-123
📝 标题：修复登录Bug
📊 状态：In Progress
⚡ 优先级：High
👤 负责人：张三
📝 报告人：李四
📅 创建时间：2026-05-20T10:00:00.000+0000
📄 描述：用户无法登录系统

🔗 链接：https://your-company.atlassian.net/browse/PROJ-123
```

---

### 2. 创建任务

#### 指令格式
```
/jira create <项目Key> <标题>
```

#### 示例

**本地模式**：
```
用户: /jira create PROJ 修复登录Bug

系统回复:
✅ 任务已记录到本地（离线模式）

🔖 编号：PROJ-1
📝 标题：修复登录Bug
📊 类型：Bug
📅 创建时间：2026-05-27 14:35:00
📂 文件位置：./local_tasks.md

💡 当前处于本地离线模式，已将任务记录至本地文件中
```

**云端模式**：
```
用户: /jira create PROJ 修复登录Bug

系统回复:
✅ JIRA 任务创建成功

🔖 编号：PROJ-123
📝 标题：修复登录Bug
📊 类型：Bug

🔗 链接：https://your-company.atlassian.net/browse/PROJ-123
```

---

### 3. 搜索任务

#### 指令格式
```
/jira search <JQL语句>
```

#### 示例

**本地模式**：
```
用户: /jira search assignee=currentUser()

系统回复:
📋 本地任务列表（离线模式）

• PROJ-1 - 修复登录Bug 【待处理】
• PROJ-2 - 优化查询性能 【进行中】

💡 当前处于本地离线模式，任务记录在本地文件中
```

**云端模式**：
```
用户: /jira search assignee=currentUser()

系统回复:
📋 JIRA 任务列表（共 5 条）

• PROJ-123 - 修复登录Bug 【In Progress】
• PROJ-124 - 优化查询性能 【To Do】
• PROJ-125 - 更新用户文档 【Done】
• PROJ-126 - 修复支付Bug 【In Progress】
• PROJ-127 - 添加新功能 【To Do】
```

---

## 本地任务文件格式

本地任务文件（`local_tasks.md`）采用结构化 Markdown 格式：

```markdown
# 本地任务列表

> 当前处于本地离线模式，任务记录在此文件中

## PROJ-1

- **标题**: 修复登录Bug
- **类型**: Bug
- **状态**: 待处理
- **优先级**: 中
- **负责人**: 未分配
- **报告人**: 本地用户
- **创建时间**: 2026-05-27 14:30:00
- **描述**: 用户无法登录系统

---

## PROJ-2

- **标题**: 优化查询性能
- **类型**: Story
- **状态**: 进行中
- **优先级**: 高
- **负责人**: 张三
- **报告人**: 本地用户
- **创建时间**: 2026-05-27 15:00:00
- **描述**: 查询接口响应时间过长

---
```

---

## 任务编号生成规则

本地任务编号格式：`<项目Key>-<序号>`

**示例**：
- 第1个任务：`PROJ-1`
- 第2个任务：`PROJ-2`
- 第10个任务：`PROJ-10`

**注意**：
- 序号从 `1` 开始，自动递增
- 即使删除了中间的任务，序号也不会重复利用
- 项目Key来自创建任务时的参数（如 `PROJ`）

---

## 迁移到云端 JIRA

当团队可以访问 JIRA 云服务时，可以将本地任务导入到云端：

### 步骤1：配置 JIRA 连接

```yaml
# application.yaml
jira:
  enabled: true
  url: https://your-company.atlassian.net
  username: your-email@company.com
  api-token: xxxxxxxx
```

### 步骤2：手动导入本地任务

1. 打开本地任务文件（`local_tasks.md`）
2. 逐个查看任务详情
3. 使用 `/jira create <项目> <标题>` 在云端创建对应任务
4. 根据需要更新任务状态和负责人

### 步骤3：验证迁移结果

```
# 搜索云端任务
/jira search project=PROJ

# 对比本地文件，确保没有遗漏
```

---

## 常见问题

### Q1: 如何强制使用本地模式？

**A**: 设置 `jira.enabled=false` 或使用无效的配置（空用户名/API Token）。

---

### Q2: 本地任务文件在哪里？

**A**: 默认位置是项目根目录下的 `local_tasks.md`，可通过配置修改：

```yaml
jira:
  local-fallback-file: ./backlog.md  # 自定义路径
```

---

### Q3: 本地模式支持哪些功能？

**A**: 目前支持：
- ✅ 创建任务（`/jira create`）
- ✅ 查询任务（`/jira <任务编号>`）
- ✅ 搜索任务（`/jira search`）

暂不支持（计划中）：
- ⏳ 更新任务状态
- ⏳ 添加任务评论
- ⏳ 任务分配

---

### Q4: 如何查看本地任务文件？

**A**: 直接用文本编辑器打开：

```bash
# Windows
notepad local_tasks.md

# macOS/Linux
cat local_tasks.md
# 或使用编辑器
code local_tasks.md  # VS Code
```

---

### Q5: 本地任务文件可以多人共享吗？

**A**: 可以！将 `local_tasks.md` 提交到 Git 仓库即可：

```bash
git add local_tasks.md
git commit -m "更新本地任务记录"
git push
```

**注意**：
- 多人同时编辑可能会产生冲突，需要手动合并
- 建议团队成员在创建任务前先 `git pull` 获取最新版本

---

## 技术细节

### 类结构

```
JiraClient.java
├── isConfigured()          # 检测是否配置了有效的 JIRA
├── isLocalMode()           # 检测是否处于本地降级模式
├── getIssue()              # 查询任务（自动选择模式）
│   ├── getIssueFromLocal() # 从本地文件读取
│   └── ...                # 调用 JIRA API
├── createIssue()           # 创建任务（自动选择模式）
│   └── createIssueLocally() # 写入本地文件
└── searchIssues()         # 搜索任务（自动选择模式）
    └── searchIssuesLocally() # 从本地文件搜索
```

### 降级触发条件

1. **配置检测失败**：
   - `jira.enabled=false`
   - `jira.username` 为空
   - `jira.api-token` 为空
   - `jira.url` 包含默认的 `your-domain`

2. **API 调用失败**：
   - 网络连接超时
   - 认证失败（401/403）
   - 其他 HTTP 错误

### 文件锁机制（未来优化）

当前版本不支持并发写入，未来计划添加文件锁：

```java
// 伪代码
FileLock lock = channel.lock();
try {
    // 写入文件
} finally {
    lock.release();
}
```

---

## 参考资料

- [JIRA REST API 文档](https://developer.atlassian.com/cloud/jira/platform/rest/v3/intro/)
- [GitHub Markdown 规范](https://github.github.com/gfm/)
- [Spring Boot 外部化配置](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config)
