# 可扩展指令框架使用指南

## 概述

本项目实现了一个基于注解扫描的可扩展指令框架，允许开发者通过简单的注解来注册新指令，无需修改核心代码。

## 核心组件

### 1. `@Command` 注解

用于标记指令处理函数，实现自动注册。

**参数说明：**
- `name`: 指令名称（不含 `/` 前缀）
- `description`: 指令描述
- `requiresAuth`: 是否需要鉴权（默认 `false`）
- `aliases`: 指令别名（支持多个）
- `usage`: 使用帮助信息

**使用示例：**
```java
@Command(
    name = "gitlog",
    description = "查看 Git 提交日志",
    requiresAuth = true,
    aliases = {"log", "history"},
    usage = "/gitlog [分支名]"
)
public String handleGitLog(CommandContext context) {
    // 指令处理逻辑
    return "提交日志...";
}
```

### 2. `CommandContext` - 指令执行上下文

包含执行指令所需的所有信息：

- `commandName`: 指令名称
- `args`: 指令参数（去除指令名后的剩余部分）
- `sender`: 发送者信息（`FeishuSender` 对象）
- `messageId`: 消息 ID
- `rawMessage`: 原始消息

**常用方法：**
```java
// 获取参数数组（按空格分割）
String[] args = context.getArgsArray();

// 获取指定位置的参数
String branch = context.getArg(0);

// 获取参数数量
int count = context.getArgCount();
```

### 3. `CommandRegistry` - 指令注册表

管理所有已注册的指令，提供以下功能：
- 注册指令
- 查找指令
- 执行指令
- 生成帮助文档

### 4. `CommandScanner` - 指令扫描器

启动时自动扫描并注册带有 `@Command` 注解的方法。

## 如何添加新指令

### 方法一：在现有 Handler 中添加

1. 在任意 `@Component` 标记的类中，添加带有 `@Command` 注解的方法
2. 方法签名必须是：`public String 方法名(CommandContext context)`
3. 重启应用，指令自动注册

**示例：添加 `/hello` 指令**

```java
package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.annotation.Command;
import com.example.intelligentxtsystem.dto.CommandContext;
import org.springframework.stereotype.Component;

@Component
public class MyCommandHandler {

    @Command(
        name = "hello",
        description = "打招呼",
        usage = "/hello [名字]"
    )
    public String handleHello(CommandContext context) {
        String name = context.getArg(0);
        if (name != null) {
            return "👋 你好，" + name + "！";
        }
        return "👋 你好！";
    }
}
```

### 方法二：创建独立的 Handler 类

1. 创建新的 Java 类，放在 `service/handler/` 目录下
2. 使用 `@Component` 注解标记类
3. 在类中添加带有 `@Command` 注解的方法

**示例：创建 `/calc` 计算器指令**

```java
package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.annotation.Command;
import com.example.intelligentxtsystem.dto.CommandContext;
import org.springframework.stereotype.Component;

@Component
public class CalculatorCommandHandler {

    @Command(
        name = "calc",
        description = "简单计算器",
        usage = "/calc <表达式>",
        aliases = {"calculate", "计算"}
    )
    public String handleCalc(CommandContext context) {
        String expression = context.getArgs();
        
        if (expression == null || expression.trim().isEmpty()) {
            return "❌ 用法：/calc <表达式>\n示例：/calc 1 + 2 * 3";
        }
        
        try {
            // 注意：实际应用中应该使用安全的表达式解析器
            // 这里只是示例
            String result = evaluateExpression(expression);
            return "🧮 计算结果: " + result;
        } catch (Exception e) {
            return "❌ 计算失败: " + e.getMessage();
        }
    }
    
    private String evaluateExpression(String expr) {
        // 简单的表达式计算（实际应用中请使用脚本引擎或表达式解析库）
        return "（示例）";
    }
}
```

## 高级用法

### 1. 指令别名

可以为一个指令设置多个别名：

```java
@Command(
    name = "jira",
    description = "JIRA 任务管理",
    aliases = {"jira", "issue", "bug"}
)
public String handleJira(CommandContext context) {
    // 使用 /jira、/issue 或 /bug 都可以触发这个指令
}
```

### 2. 鉴权指令

某些指令需要鉴权才能执行：

```java
@Command(
    name = "admin",
    description = "管理员指令",
    requiresAuth = true  // 需要鉴权
)
public String handleAdmin(CommandContext context) {
    // 只有授权用户才能执行
    return "🔧 管理员指令执行成功";
}
```

### 3. 子指令

支持子指令（如 `/jira create`、`/jira search`）：

```java
@Command(
    name = "jira",
    description = "JIRA 任务管理",
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
```

## 框架工作流程

1. **启动扫描**：应用启动时，`CommandScanner` 自动扫描所有 Spring Bean
2. **注册指令**：找到带有 `@Command` 注解的方法，注册到 `CommandRegistry`
3. **消息接收**：用户发送消息（如 `/gitlog`）
4. **指令匹配**：`MessageDispatcher` 根据指令名在 `CommandRegistry` 中查找对应的指令
5. **执行指令**：找到指令后，创建 `CommandContext`，调用指令方法
6. **返回结果**：将指令方法的返回值返回给用户

## 调试技巧

### 查看已注册的指令

访问 Spring Boot Actuator 端点（如果已配置），或查看启动日志：

```
INFO  c.e.i.service.CommandScanner - 开始扫描指令...
INFO  c.e.i.service.CommandRegistry - 注册指令: /hello - 打招呼
INFO  c.e.i.service.CommandRegistry - 注册指令: /calc - 简单计算器
INFO  c.e.i.service.CommandScanner - 指令扫描完成，共注册 2 个指令
```

### 测试指令

在飞书中发送指令：
```
/hello 世界
/calc 1 + 2
/jira PROJ-1
```

## 最佳实践

1. **指令命名**：使用小写字母，避免特殊字符
2. **参数解析**：使用 `context.getArgsArray()` 解析参数
3. **错误处理**：捕获异常并返回友好的错误信息
4. **帮助信息**：提供清晰的 `usage` 和 `description`
5. **返回值**：返回 String 类型，支持 Markdown 格式
6. **性能考虑**：避免在指令方法中进行耗时操作，可以考虑异步处理

## 常见问题

### Q: 指令不生效？
A: 检查以下几点：
- 类是否添加了 `@Component` 注解
- 方法是否添加了 `@Command` 注解
- 方法签名是否正确（`public String 方法名(CommandContext context)`）
- 是否重启了应用

### Q: 如何获取用户信息？
A: 通过 `context.getSender()` 获取 `FeishuSender` 对象，包含用户 ID、名称等信息

### Q: 如何发送富文本消息？
A: 返回支持 Markdown 格式的字符串，飞书会自动渲染

## 扩展阅读

- [Spring Boot 官方文档](https://spring.io/projects/spring-boot)
- [Java 注解官方教程](https://docs.oracle.com/javase/tutorial/java/annotations/)
- [飞书开放平台文档](https://open.feishu.cn/document/)
