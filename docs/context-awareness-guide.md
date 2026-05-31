# 上下文感知功能使用指南

## 概述

上下文感知功能允许指令在多轮对话中保持状态，支持自动填充参数，提升用户体验。

## 核心概念

### 1. 全局参数 vs 局部参数

| 类型 | 作用域 | 生命周期 | 适用场景 |
|------|--------|----------|----------|
| **全局参数** | 跨指令复用 | 整个会话期间（直到用户清除） | 登录Token、用户偏好设置 |
| **局部参数** | 仅同类型指令复用 | 5分钟超时（可配置） | 查询城市、仓库别名、分支名 |

### 2. 强独立性指令

某些指令（如 `/jira 1234`）每次都是独立执行，不继承任何上下文。

设置 `independent = true` 即可。

## 使用步骤

### 步骤1：修改 `@Command` 注解

在 Handler 方法的 `@Command` 注解中添加上下文相关属性：

```java
@Command(
    name = "weather",
    description = "查询城市天气",
    usage = "/weather <城市名>",
    // === 上下文相关配置 ===
    supportsContext = true,        // 启用上下文感知
    contextType = "weather",      // 上下文类型标识
    localParams = {"city"},       // 局部参数列表
    contextTimeout = 5,           // 超时时间（分钟）
    independent = false           // 是否强独立
)
```

### 步骤2：在 Handler 中处理上下文参数

```java
@Command(
    name = "weather",
    supportsContext = true,
    contextType = "weather",
    localParams = {"city"}
)
public String handle(CommandContext context) {
    String city = null;

    // 1. 优先使用当前输入
    String inputCity = context.getArgs().trim();
    if (!inputCity.isEmpty()) {
        city = inputCity;
        context.setFilledParam("city", city);  // 保存到上下文
    }
    // 2. 当前输入为空，从上下文获取
    else if (context.isContextSupported() && context.hasFilledParam("city")) {
        city = (String) context.getFilledParam("city");
    }

    if (city == null || city.isEmpty()) {
        return "❌ 用法：/weather 城市名";
    }

    // 执行查询...
    return weatherService.getFormattedWeather(city);
}
```

### 步骤3：设置全局参数（可选）

如果指令需要全局参数（如 Token），可以在 Handler 中设置：

```java
// 设置全局参数（通过 ContextManager）
@Autowired
private ContextManager contextManager;

public String handle(CommandContext context) {
    String userId = context.getUserId();

    // 设置全局参数
    contextManager.setGlobalParam(userId, "token", "abc123");
    contextManager.setGlobalParam(userId, "username", "testuser");

    // 获取全局参数
    Map<String, Object> globalParams = contextManager.getGlobalParams(userId);
    String token = (String) globalParams.get("token");

    // 使用全局参数...
}
```

## 完整示例

### 示例1：天气查询（局部参数）

**第一次查询：**
```
用户: /weather 深圳
机器人: 深圳的天气是...
[系统内部：保存上下文 {user_123: {weather: {city: "深圳"}}}}
```

**第二次查询（不输入城市）：**
```
用户: /weather
机器人: 深圳的天气是...（自动复用深圳）
```

**切换指令（清空上下文）：**
```
用户: /help
机器人: 帮助信息...
[系统内部：清空 weather 上下文]
```

**再次查询天气（需要重新输入）：**
```
用户: /weather
机器人: ❌ 用法：/weather 城市名
```

### 示例2：Git 日志查询（多个局部参数）

**第一次查询：**
```
用户: /gitlog frontend 10 main
机器人: 显示 frontend 仓库 main 分支最近 10 条提交
[系统内部：保存上下文 {repoAlias: "frontend", limit: 10, branch: "main"}}
```

**第二次查询（只输入仓库）：**
```
用户: /gitlog
机器人: 显示 frontend 仓库 main 分支最近 10 条提交（复用所有参数）
```

**修改单个参数：**
```
用户: /gitlog frontend 20
机器人: 显示 frontend 仓库 main 分支最近 20 条提交（复用 repo 和 branch）
```

### 示例3：强独立性指令（不继承上下文）

```java
@Command(
    name = "jira",
    description = "查询 JIRA 问题",
    usage = "/jira <问题ID>",
    independent = true  // 强独立性，不继承任何上下文
)
public String handle(CommandContext context) {
    // 每次都是全新执行，不继承任何上下文
    String issueId = context.getArgs().trim();
    return jiraService.getIssue(issueId);
}
```

**交互示例：**
```
用户: /jira 1234
机器人: 显示 JIRA-1234 详情
[系统内部：不保存上下文]

用户: /jira
机器人: ❌ 用法：/jira <问题ID>
[不继承任何参数]
```

## 配置说明

### @Command 注解属性

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `supportsContext` | boolean | false | 是否支持上下文感知 |
| `contextType` | String | "" | 上下文类型标识（为空时使用指令名） |
| `globalParams` | String[] | {} | 全局参数列表 |
| `localParams` | String[] | {} | 局部参数列表 |
| `contextTimeout` | int | 5 | 上下文超时时间（分钟） |
| `independent` | boolean | false | 是否强独立性指令 |

### ContextManager 配置

上下文管理器会自动清理超时上下文，无需手动配置。

如果需要手动清理：

```java
// 清除指定用户的指定上下文
contextManager.clearLocalContext(userId, "weather");

// 清除指定用户的所有局部上下文
contextManager.clearAllLocalContexts(userId);

// 清除指定用户的全局参数
contextManager.clearGlobalParams(userId);

// 清除指定用户的所有上下文
contextManager.clearAll(userId);
```

## 测试

运行单元测试：

```bash
mvn test -Dtest=ContextManagerTest
```

## 注意事项

1. **不要过度依赖上下文**：有些指令（如 `/jira 1234`）应该设置为强独立性
2. **超时机制**：上下文默认5分钟超时，超时后自动清空
3. **参数优先级**：当前输入 > 局部上下文 > 全局参数
4. **切换指令清空**：切换到不同类型的指令时，会自动清空之前的局部上下文

## 故障排查

### 问题1：上下文没有生效

**原因**：忘记设置 `supportsContext = true`

**解决**：检查 `@Command` 注解是否设置了 `supportsContext = true`

### 问题2：参数没有自动填充

**原因**：忘记在 Handler 中调用 `context.getFilledParam()` 或 `context.setFilledParam()`

**解决**：确保在 Handler 中正确处理 `filledParams`

### 问题3：上下文没有超时清理

**原因**：`ContextManager` 的定时清理任务没有启动

**解决**：检查 `ContextManager` 是否被正确注入（添加 `@Component` 注解）

## 总结

通过上下文感知功能，可以大幅提升用户体验，减少重复输入。但需要注意：

1. 不要所有指令都启用上下文，只给需要多轮对话的指令启用
2. 强独立性指令一定要设置 `independent = true`
3. 及时清理不需要的上下文，避免内存泄漏
