# 可扩展指令框架 - 实现总结

## 已完成的工作

### 1. 核心注解 - `@Command`

**文件位置：** `src/main/java/com/example/intelligentxtsystem/annotation/Command.java`

**功能：**
- 标记指令处理函数
- 支持定义指令名、描述、是否需要鉴权、别名、使用帮助

**使用示例：**
```java
@Command(
    name = "gitlog",
    description = "查看 Git 提交日志",
    requiresAuth = false,
    usage = "/gitlog <仓库别名> [条数] [分支]",
    aliases = {"log", "history"}
)
public String handleGitLog(CommandContext context) {
    // 指令处理逻辑
    return "提交日志...";
}
```

### 2. 指令上下文 - `CommandContext`

**文件位置：** `src/main/java/com/example/intelligentxtsystem/dto/CommandContext.java`

**功能：**
- 封装指令执行时的所有上下文信息
- 提供便捷的参数解析方法

**主要字段：**
- `commandName`: 指令名称
- `args`: 指令参数（去除指令名后的剩余部分）
- `sender`: 发送者信息（`FeishuSender` 对象）
- `messageId`: 消息 ID
- `rawMessage`: 原始消息

**常用方法：**
```java
String[] argsArray = context.getArgsArray();  // 获取参数数组
String arg0 = context.getArg(0);             // 获取指定位置的参数
int count = context.getArgCount();           // 获取参数数量
```

### 3. 指令定义 - `CommandDefinition`

**文件位置：** `src/main/java/com/example/intelligentxtsystem/dto/CommandDefinition.java`

**功能：**
- 存储指令的元数据（名称、描述、方法引用等）
- 提供指令匹配和执行功能

### 4. 指令注册表 - `CommandRegistry`

**文件位置：** `src/main/java/com/example/intelligentxtsystem/service/CommandRegistry.java`

**功能：**
- 管理所有已注册的指令
- 提供指令查找、执行、帮助文档生成等功能

**主要方法：**
```java
void register(CommandDefinition definition)  // 注册指令
CommandDefinition getCommand(String name)    // 获取指令定义
Object execute(String commandName, CommandContext context)  // 执行指令
List<CommandDefinition> getAllCommands()     // 获取所有指令
String generateHelp()                        // 生成帮助文档
```

### 5. 指令扫描器 - `CommandScanner`

**文件位置：** `src/main/java/com/example/intelligentxtsystem/service/CommandScanner.java`

**功能：**
- 应用启动时自动扫描所有 Spring Bean
- 查找带有 `@Command` 注解的方法并注册到 `CommandRegistry`

**扫描逻辑：**
1. 获取所有 Spring Bean
2. 遍历每个 Bean 的所有方法
3. 找到带有 `@Command` 注解的方法
4. 验证方法签名（必须是 `public String 方法名(CommandContext context)`）
5. 创建 `CommandDefinition` 并注册到 `CommandRegistry`

### 6. 消息分发器 - `MessageDispatcher`（已修改）

**文件位置：** `src/main/java/com/example/intelligentxtsystem/service/MessageDispatcher.java`

**修改内容：**
- 移除原有的基于 `CommandHandler` 接口的分发逻辑
- 改为使用 `CommandRegistry` 进行指令分发
- 支持 `/指令名` 和 `指令名` 两种格式
- 自动生成未知指令的帮助信息

## 使用示例

### 示例 1：创建简单的指令

**文件：** `src/main/java/com/example/intelligentxtsystem/service/handler/PingCommandHandler.java`

```java
@Component
public class PingCommandHandler {
    
    @Command(
        name = "ping",
        description = "检测服务器连通性",
        requiresAuth = false,
        usage = "/ping <host>"
    )
    public String handlePing(CommandContext context) {
        String host = context.getArg(0);
        
        if (host == null || host.trim().isEmpty()) {
            return "❌ 用法：/ping <host>";
        }
        
        // 执行 ping 逻辑
        return "✅ " + host + " 可达";
    }
}
```

### 示例 2：创建带子指令的复杂指令

**文件：** `src/main/java/com/example/intelligentxtsystem/service/handler/JiraCommandHandler.java`

```java
@Component
public class JiraCommandHandler {
    
    @Command(
        name = "jira",
        description = "JIRA 任务管理工具",
        requiresAuth = false,
        usage = "/jira <任务编号> | /jira create <项目> <标题> | /jira search <JQL>"
    )
    public String handleJira(CommandContext context) {
        String[] args = context.getArgsArray();
        
        if (args.length == 0) {
            return generateHelp();
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "create":
                return handleCreate(context);
            case "search":
                return handleSearch(context);
            default:
                // 假设是任务编号
                return getIssue(context.getArgs());
        }
    }
    
    private String handleCreate(CommandContext context) {
        // 创建任务逻辑
        return "✅ 任务已创建";
    }
    
    private String handleSearch(CommandContext context) {
        // 搜索任务逻辑
        return "🔍 搜索结果...";
    }
}
```

### 示例 3：迁移现有指令

**原指令（基于接口）：** `GitLogHandler.java`
**新指令（基于注解）：** `GitLogCommandHandler.java`

主要修改：
1. 移除 `implements CommandHandler`
2. 移除 `support()` 方法
3. 在 `handleGitLog()` 方法上添加 `@Command` 注解
4. 修改方法签名，接受 `CommandContext` 参数
5. 从 `context` 中获取参数

## 如何添加新指令

### 步骤 1：创建 Handler 类（可选）

在 `src/main/java/com/example/intelligentxtsystem/service/handler/` 目录下创建新的 Java 类。

### 步骤 2：添加 `@Command` 注解

在方法上添加 `@Command` 注解，并填写元数据。

### 步骤 3：实现指令逻辑

方法签名必须是：
```java
public String 方法名(CommandContext context) {
    // 指令处理逻辑
    return "结果";
}
```

### 步骤 4：重启应用

应用启动时会自动扫描并注册指令。

## 测试

### 单元测试

**文件：** `src/test/java/com/example/intelligentxtsystem/service/CommandFrameworkTest.java`

**测试用例：**
- 测试指令注册
- 测试指令执行
- 测试未知指令
- 测试指令别名
- 测试帮助文档生成

### 手动测试

在飞书中发送指令：
```
/ping google.com
/gitlog frontend 10 main
/jira PROJ-1
/help
```

## 常见问题

### Q1: 指令不生效？

**检查清单：**
- [ ] 类是否添加了 `@Component` 注解
- [ ] 方法是否添加了 `@Command` 注解
- [ ] 方法签名是否正确（`public String 方法名(CommandContext context)`）
- [ ] 是否重启了应用
- [ ] 查看启动日志，确认指令是否被注册

### Q2: 如何获取用户信息？

通过 `context.getSender()` 获取 `FeishuSender` 对象：
```java
FeishuSender sender = context.getSender();
String openId = sender.getOpenId();
String userId = sender.getUserId();
String displayName = sender.getDisplayName();
```

### Q3: 如何支持可选参数？

使用 `context.getArg()` 方法，并做空值检查：
```java
String branch = context.getArg(1);
if (branch == null) {
    branch = "main";  // 默认值
}
```

### Q4: 如何返回富文本？

返回支持 Markdown 格式的字符串：
```java
return "**粗体** *斜体* [链接](https://example.com)";
```

## 后续优化建议

1. **异步执行**：对于耗时操作，支持异步执行并返回"处理中"提示
2. **权限控制**：完善 `requiresAuth` 功能，实现基于用户角色的权限控制
3. **参数验证**：提供参数验证注解（如 `@Required`、`@Pattern`）
4. **插件机制**：支持从外部 JAR 文件加载指令（真正的插件系统）
5. **指令分组**：支持指令分组，生成更友好的帮助文档
6. **性能监控**：记录指令执行时间，用于性能优化

## 文件清单

### 核心框架
- `src/main/java/com/example/intelligentxtsystem/annotation/Command.java`
- `src/main/java/com/example/intelligentxtsystem/dto/CommandContext.java`
- `src/main/java/com/example/intelligentxtsystem/dto/CommandDefinition.java`
- `src/main/java/com/example/intelligentxtsystem/service/CommandRegistry.java`
- `src/main/java/com/example/intelligentxtsystem/service/CommandScanner.java`
- `src/main/java/com/example/intelligentxtsystem/service/MessageDispatcher.java`（已修改）

### 示例 Handler
- `src/main/java/com/example/intelligentxtsystem/service/handler/PingCommandHandler.java`
- `src/main/java/com/example/intelligentxtsystem/service/handler/JiraCommandHandler.java`
- `src/main/java/com/example/intelligentxtsystem/service/handler/GitLogCommandHandler.java`

### 测试
- `src/test/java/com/example/intelligentxtsystem/service/CommandFrameworkTest.java`

### 文档
- `docs/COMMAND-FRAMEWORK.md`（使用指南）
- `docs/FRAMEWORK-SUMMARY.md`（本文档）

---

**状态：** ✅ 框架核心功能已实现，可以开始使用了！

**下一步：**
1. 编译并启动应用，查看启动日志确认指令注册成功
2. 在飞书中测试新指令
3. 逐步迁移现有指令到新框架
4. 根据需求添加新指令
