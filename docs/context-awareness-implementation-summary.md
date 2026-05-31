# 上下文感知功能实现总结

## 修改文件清单

### 1. 新增文件

| 文件 | 说明 |
|------|------|
| `src/main/java/com/example/intelligentxtsystem/service/ContextManager.java` | 上下文管理器，负责管理用户状态池 |
| `src/main/java/com/example/intelligentxtsystem/dto/UserContext.java` | 用户上下文对象，存储单个上下文类型的状态 |
| `src/test/java/com/example/intelligentxtsystem/service/ContextManagerTest.java` | 单元测试类 |
| `docs/context-awareness-guide.md` | 使用指南 |
| `docs/context-awareness-implementation-summary.md` | 本文档 |

### 2. 修改文件

| 文件 | 修改内容 |
|------|----------|
| `src/main/java/com/example/intelligentxtsystem/annotation/Command.java` | 新增上下文相关属性：`supportsContext`, `contextType`, `globalParams`, `localParams`, `contextTimeout`, `independent` |
| `src/main/java/com/example/intelligentxtsystem/dto/CommandContext.java` | 新增字段：`userId`, `filledParams`, `contextSupported`, `contextType` |
| `src/main/java/com/example/intelligentxtsystem/dto/CommandDefinition.java` | 新增字段：上下文相关属性 |
| `src/main/java/com/example/intelligentxtsystem/service/CommandRegistry.java` | 新增上下文处理逻辑：`execute()` 方法增加上下文感知 |
| `src/main/java/com/example/intelligentxtsystem/service/CommandScanner.java` | 新增：读取 `@Command` 注解的上下文属性 |
| `src/main/java/com/example/intelligentxtsystem/service/handler/WeatherCommandHandler.java` | 示例：演示如何使用上下文感知 |
| `src/main/java/com/example/intelligentxtsystem/service/handler/GitLogCommandHandler.java` | 示例：演示多参数上下文感知 |
| `src/main/java/com/example/intelligentxtsystem/service/handler/JiraCommandHandler.java` | 示例：设置为强独立性指令 |

## 核心功能

### 1. 上下文管理器（ContextManager）

**功能**：
- 维护用户状态池（全局参数 + 局部参数）
- 自动清理超时上下文（默认5分钟）
- 支持全局参数和局部参数的隔离

**存储结构**：
```java
// 全局参数：{user_id: {param_name: param_value}}
private final Map<String, Map<String, Object>> globalParams;

// 局部上下文：{user_id: {context_type: UserContext}}
private final Map<String, Map<String, UserContext>> localContexts;
```

**关键方法**：
- `getGlobalParams(userId)` - 获取全局参数
- `setGlobalParam(userId, key, value)` - 设置全局参数
- `getLocalContext(userId, contextType)` - 获取局部上下文（自动检查超时）
- `setLocalContext(userId, contextType, params, timeout)` - 设置局部上下文
- `autoFillParams(...)` - 自动填充参数（全局 + 局部）
- `clearAllLocalContexts(userId)` - 清空所有局部上下文（切换指令时调用）
- `clearAll(userId)` - 清空所有上下文

### 2. 用户上下文（UserContext）

**功能**：存储单个用户的单个上下文类型的状态

**字段**：
- `contextType` - 上下文类型（如 "weather", "gitlog"）
- `params` - 上下文参数（局部参数）
- `createTime` - 创建时间戳
- `lastAccessTime` - 最后访问时间戳（用于续期）
- `timeoutMinutes` - 超时时间（分钟）

**关键方法**：
- `updateAccessTime()` - 更新访问时间（续期）
- `isExpired()` - 检查是否过期
- `getRemainingSeconds()` - 获取剩余有效时间

### 3. 指令注册表（CommandRegistry）

**修改**：`execute()` 方法增加上下文处理逻辑

**处理流程**：
1. 检查指令是否独立（`independent=true`），如果是则直接执行
2. 检查指令是否支持上下文（`supportsContext=true`）
3. 如果支持，尝试从 `ContextManager` 获取上下文并填充参数
4. 执行指令
5. 如果指令支持上下文，保存当前参数到上下文
6. 如果切换到新指令，清空之前的局部上下文

### 4. @Command 注解

**新增属性**：

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `supportsContext` | boolean | false | 是否支持上下文感知 |
| `contextType` | String | "" | 上下文类型标识（为空时使用指令名） |
| `globalParams` | String[] | {} | 全局参数列表 |
| `localParams` | String[] | {} | 局部参数列表 |
| `contextTimeout` | int | 5 | 上下文超时时间（分钟） |
| `independent` | boolean | false | 是否强独立性指令 |

## 使用示例

### 示例1：天气查询（简单上下文）

**WeatherCommandHandler.java**：
```java
@Command(
    name = "weather",
    description = "查询城市天气（支持上下文感知）",
    usage = "/weather <城市名>",
    aliases = {"天气", "wt"},
    supportsContext = true,
    contextType = "weather",
    localParams = {"city"},
    contextTimeout = 5
)
public String handle(CommandContext context) {
    String city = null;

    // 优先使用当前输入
    String inputCity = context.getArgs().trim();
    if (!inputCity.isEmpty()) {
        city = inputCity;
        context.setFilledParam("city", city);
    }
    // 当前输入为空，从上下文获取
    else if (context.isContextSupported() && context.hasFilledParam("city")) {
        city = (String) context.getFilledParam("city");
    }

    if (city == null || city.isEmpty()) {
        return "❌ 用法：/weather 城市名";
    }

    try {
        String result = weatherService.getFormattedWeather(city);
        return result + "\n\n💡 提示：5分钟内再次发送 /weather 会自动复用「" + city + "」";
    } catch (Exception e) {
        return "⚠️ 天气查询失败，请稍后再试";
    }
}
```

**交互示例**：
```
用户: /weather 深圳
机器人: 深圳的天气是...
[系统内部：保存上下文 {city: "深圳"}]

用户: /weather
机器人: 深圳的天气是...（自动复用深圳）
```

### 示例2：Git 日志查询（多参数上下文）

**GitLogCommandHandler.java**：
```java
@Command(
    name = "gitlog",
    description = "查看 Git 提交日志（支持上下文感知）",
    usage = "/gitlog <仓库别名> [条数] [分支]",
    supportsContext = true,
    contextType = "gitlog",
    localParams = {"repoAlias", "limit", "branch"},
    contextTimeout = 5
)
public String handle(CommandContext context) {
    String args = context.getArgs().trim();

    // 尝试从上下文获取参数
    String repoAlias = null;
    Integer limit = null;
    String branch = null;

    // 优先使用当前输入
    if (!args.isEmpty()) {
        String[] parts = args.split("\\s+");
        if (parts.length >= 1) repoAlias = parts[0];
        if (parts.length >= 2) limit = Integer.parseInt(parts[1]);
        if (parts.length >= 3) branch = parts[2];
    }

    // 如果当前输入缺失，从上下文填充
    if (context.isContextSupported()) {
        if (repoAlias == null && context.hasFilledParam("repoAlias")) {
            repoAlias = (String) context.getFilledParam("repoAlias");
        }
        if (limit == null && context.hasFilledParam("limit")) {
            limit = (Integer) context.getFilledParam("limit");
        }
        if (branch == null && context.hasFilledParam("branch")) {
            branch = (String) context.getFilledParam("branch");
        }
    }

    // 设置默认值
    if (repoAlias == null) {
        return "❌ 用法：/gitlog <仓库别名> [条数] [分支]";
    }
    if (limit == null) limit = 10;

    // 保存参数到上下文
    context.setFilledParam("repoAlias", repoAlias);
    context.setFilledParam("limit", limit);
    if (branch != null) context.setFilledParam("branch", branch);

    // 执行查询...
}
```

**交互示例**：
```
用户: /gitlog frontend 10 main
机器人: 显示 frontend 仓库 main 分支最近 10 条提交
[系统内部：保存上下文 {repoAlias: "frontend", limit: 10, branch: "main"}]

用户: /gitlog
机器人: 显示 frontend 仓库 main 分支最近 10 条提交（复用所有参数）
```

### 示例3：JIRA 查询（强独立性指令）

**JiraCommandHandler.java**：
```java
@Command(
    name = "jira",
    description = "JIRA 任务管理（强独立性指令）",
    usage = "/jira <任务编号>",
    aliases = {"jira"},
    independent = true  // 强独立性：每次都是全新执行
)
public String handle(CommandContext context) {
    // 每次都是全新执行，不继承任何上下文
    String issueKey = context.getArgs().trim();

    if (issueKey.isEmpty()) {
        return "❌ 用法：/jira <任务编号>";
    }

    return jiraClient.getIssue(issueKey);
}
```

**交互示例**：
```
用户: /jira PROJ-123
机器人: 显示 PROJ-123 详情
[系统内部：不保存上下文]

用户: /jira
机器人: ❌ 用法：/jira <任务编号>
[不继承任何参数]
```

## 测试

运行单元测试：
```bash
mvn test -Dtest=ContextManagerTest
```

**测试用例**：
1. 局部上下文的基本存储和读取
2. 自动填充参数（局部参数）
3. 当前输入优先于上下文
4. 全局参数的跨指令复用
5. 切换指令时清空局部上下文
6. 上下文超时
7. 强独立性指令不影响上下文
8. 全局参数和局部参数的隔离

## 注意事项

### 1. 不要过度依赖上下文

- 只给需要多轮对话的指令启用上下文
- 强独立性指令一定要设置 `independent = true`

### 2. 超时机制

- 上下文默认5分钟超时
- 每次访问会自动续期（`updateAccessTime()`）
- 超时后自动清空

### 3. 参数优先级

**优先级从高到低**：
1. 当前输入参数
2. 局部上下文参数
3. 全局参数

### 4. 切换指令清空

- 切换到不同类型的指令时，会自动清空之前的局部上下文
- 强独立性指令会清空所有之前的局部上下文
- 全局参数不会被清空（除非手动调用 `clearGlobalParams()`）

### 5. 内存管理

- `ContextManager` 会自动清理超时上下文（定时任务，每分钟检查一次）
- 如果用户量很大，考虑使用 Redis 替代内存存储

## 后续优化建议

### 1. 使用 Redis 存储上下文

当前实现使用内存存储，适合单机部署。如果需要多实例部署，建议使用 Redis：

```java
@Component
public class RedisContextManager extends ContextManager {
    // 使用 Redis 存储上下文
    // 实现分布式场景下的上下文共享
}
```

### 2. 增加上下文切换提示

在回复中提示用户当前使用的上下文：

```java
if (context.isContextSupported() && context.hasFilledParam("city")) {
    String city = (String) context.getFilledParam("city");
    return "🌤️ 深圳的天气是...\n\n💡 当前使用上下文：城市=" + city + "（5分钟内有效）";
}
```

### 3. 支持上下文手动清除

增加 `/clear` 指令手动清除上下文：

```java
@Command(name = "clear", description = "清除上下文")
public String handle(CommandContext context) {
    String userId = context.getUserId();
    contextManager.clearAll(userId);
    return "✅ 已清除所有上下文";
}
```

## 总结

通过本次修改，项目增加了上下文感知能力，支持：
1. **多轮连贯操作**：用户无需重复输入相同参数
2. **全局参数和局部参数的隔离**：全局参数跨指令复用，局部参数仅本轮有效
3. **超时机制**：5分钟无操作自动清空上下文
4. **强独立性指令**：某些指令（如 `/jira 1234`）不继承任何上下文

**不影响现有功能**：
- 所有修改都向后兼容
- 未启用上下文的指令保持原有行为
- 启用上下文的指令在不使用上下文时也能正常工作（如第一次查询）

## 验证步骤

1. 启动项目
2. 发送 `/weather 深圳` 查询天气
3. 5分钟内再次发送 `/weather`（不输入城市），应该自动复用深圳
4. 发送 `/help` 切换指令，上下文应该被清空
5. 再次发送 `/weather`，应该提示输入城市
6. 发送 `/jira 1234`，应该强独立执行，不继承任何上下文

**预期结果**：所有功能正常工作，上下文感知生效。
