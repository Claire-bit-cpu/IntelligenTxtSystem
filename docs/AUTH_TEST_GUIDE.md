# 鉴权功能测试指南

## 1. 单元测试（推荐）

### 运行 AuthServiceTest
```bash
./mvnw test -Dtest=AuthServiceTest
```

**测试覆盖：**
- ✅ 管理员权限验证
- ✅ 开发者权限验证
- ✅ 权限级别判断
- ✅ 白名单未配置时的安全行为（fail-secure）
- ✅ 权限拒绝时的友好错误信息
- ✅ Open ID 脱敏

### 运行 CommandAuthTest
```bash
./mvnw test -Dtest=CommandAuthTest
```

**测试覆盖：**
- ✅ 公开指令对所有用户开放
- ✅ 鉴权指令对授权用户正常执行
- ✅ 鉴权指令对未授权用户拒绝执行
- ✅ 无法识别用户身份时拒绝执行
- ✅ 白名单未配置时拒绝所有鉴权请求
- ✅ 帮助文档正确标注需要鉴权的指令
- ✅ 指令别名继承鉴权设置

---

## 2. 集成测试（需要 Redis）

### 启动本地 Redis
```bash
# Windows
redis-server.exe redis.windows.conf

# Linux/Mac
redis-server
```

### 运行所有测试
```bash
./mvnw test -Dtest=AuthServiceTest,CommandAuthTest
```

---

## 3. 手动测试（真实环境）

### 步骤 1：配置白名单

在 `application.yaml` 中配置管理员和开发者白名单：

```yaml
github:
  admin-open-ids: "ou_你的管理员OpenID"
  developer-open-ids: "ou_你的开发者OpenID"
```

或通过环境变量：
```bash
export GITHUB_ADMIN_OPEN_IDS="ou_你的管理员OpenID"
export GITHUB_DEVELOPER_OPEN_IDS="ou_你的开发者OpenID"
```

### 步骤 2：标记需要鉴权的指令

在指令 Handler 上使用 `@Command` 注解：

```java
@Command(
    name = "deploy",
    description = "部署服务",
    requiresAuth = true  // 需要鉴权
)
public String handleDeploy(CommandContext context) {
    return "部署完成";
}
```

### 步骤 3：测试鉴权

#### 场景 1：授权用户执行指令
```
用户（管理员 Open ID）→ 飞书 → /deploy
预期结果：✅ 执行成功
```

#### 场景 2：未授权用户执行指令
```
用户（未知 Open ID）→ 飞书 → /deploy
预期结果：❌ 返回权限不足提示
```

#### 场景 3：白名单未配置
```yaml
github:
  admin-open-ids: ""
  developer-open-ids: ""
```

```
任何用户 → 飞书 → /deploy
预期结果：❌ 返回权限不足提示（fail-secure）
```

---

## 4. 飞书 Open ID 获取方法

### 方法 1：通过飞书开放平台
1. 登录 [飞书开放平台](https://open.feishu.cn/)
2. 进入「通讯录」→「成员管理」
3. 查看成员详情，获取 `open_id`

### 方法 2：通过 API 查询
```bash
curl -X GET "https://open.feishu.cn/open-apis/contact/v3/users/{user_id}" \
  -H "Authorization: Bearer YOUR_APP_TOKEN"
```

### 方法 3：通过日志获取
1. 让测试用户发送任意消息给机器人
2. 查看应用日志，找到类似这样的输出：
```
解析成功: chatId=oc_xxx, messageId=om_xxx
```
3. 在日志中搜索 `openId=` 或 `sender_id`，找到用户的 Open ID

---

## 5. 预期行为对照表

| 场景 | 白名单配置 | 用户 Open ID | 指令 requiresAuth | 预期结果 |
|------|-----------|--------------|-------------------|----------|
| 1 | 已配置 | 管理员 | true | ✅ 执行成功 |
| 2 | 已配置 | 开发者 | true | ✅ 执行成功 |
| 3 | 已配置 | 未知用户 | true | ❌ 权限不足 |
| 4 | 已配置 | 任意用户 | false | ✅ 执行成功 |
| 5 | 未配置 | 任意用户 | true | ❌ 权限不足 |
| 6 | 未配置 | 任意用户 | false | ✅ 执行成功 |
| 7 | 已配置 | null | true | ❌ 无法识别用户身份 |
| 8 | 已配置 | "default" | true | ❌ 无法识别用户身份 |

---

## 6. 日志验证

### 成功的鉴权请求
```
INFO  c.e.i.s.CommandRegistry - 指令 deploy 需要鉴权，检查用户权限: openId=ou_a***xxx
DEBUG c.e.i.s.AuthService - 开发者权限检查: openId=ou_a***xxx, result=true
DEBUG c.e.i.s.CommandRegistry - 鉴权通过: openId=ou_a***xxx, command=deploy
```

### 拒绝的鉴权请求
```
INFO  c.e.i.s.CommandRegistry - 指令 deploy 需要鉴权，检查用户权限: openId=ou_a***xxx
WARN  c.e.i.s.CommandRegistry - 权限拒绝: 用户 ou_a***xxx 无权执行指令 deploy
WARN  c.e.i.s.MessageDispatcher - 权限拒绝: /deploy，taskId: xxx，原因: 权限不足：此指令需要开发者或以上权限。
```

### 白名单未配置
```
WARN  c.e.i.s.AuthService - 权限白名单未配置（admin-open-ids 和 developer-open-ids 均为空），拒绝所有鉴权请求
```

---

## 7. 常见问题排查

### 问题 1：所有用户都被拒绝
**原因：** 白名单配置错误（环境变量未生效）

**解决：**
1. 检查环境变量是否正确设置：
   ```bash
   echo %GITHUB_ADMIN_OPEN_IDS%
   echo %GITHUB_DEVELOPER_OPEN_IDS%
   ```
2. 或在 `application.yaml` 中直接配置（测试环境）

### 问题 2：Open ID 不匹配
**原因：** Open ID 大小写敏感，或包含了额外字符

**解决：**
1. 检查日志中的 Open ID 是否精确匹配
2. 确保没有 trailing spaces
3. 使用 `trim()` 处理输入

### 问题 3：指令仍然执行，没有鉴权
**原因：** 指令未设置 `requiresAuth = true`

**解决：**
1. 检查 `@Command` 注解是否设置了 `requiresAuth = true`
2. 检查 `CommandDefinition` 的 `requiresAuth` 字段是否为 `true`

---

## 8. 扩展测试（可选）

### 测试不同的权限级别

修改 `CommandRegistry.java` 中的鉴权逻辑，支持更细粒度的权限控制：

```java
// 在 @Command 注解中添加 permissionLevel 属性
@Command(
    name = "deploy",
    description = "部署服务",
    requiresAuth = true,
    permissionLevel = "ADMIN"  // 仅管理员可执行
)
```

然后在 `CommandRegistry.java` 中：

```java
if (definition.isRequiresAuth()) {
    String userOpenId = context.getUserId();
    PermissionLevel requiredLevel = definition.getPermissionLevel();
    
    String permissionDeniedMsg = authService.checkPermissionWithMessage(
        userOpenId, 
        PermissionLevel.valueOf(requiredLevel)
    );
    
    if (permissionDeniedMsg != null) {
        throw new SecurityException(permissionDeniedMsg);
    }
}
```

运行扩展测试：
```bash
./mvnw test -Dtest=AuthServiceTest,CommandAuthTest,CommandPermissionLevelTest
```
