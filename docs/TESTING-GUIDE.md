# 可扩展指令框架 - 测试指南

## 1. 编译项目

### 方法一：使用 Maven 命令

```bash
cd "f:\IDEA project\IntelligenTxtSystem"
mvn clean compile -DskipTests
```

### 方法二：使用 IDE

1. 右键项目 → Maven → Reload Project
2. 等待依赖下载完成
3. 检查是否有编译错误

## 2. 启动应用

### 方法一：使用 IDE

1. 找到主类 `IntelligenTxtSystemApplication.java`
2. 右键 → Run 'IntelligenTxtSystemApplication'

### 方法二：使用 Maven

```bash
cd "f:\IDEA project\IntelligenTxtSystem"
mvn spring-boot:run
```

## 3. 查看启动日志

启动后，查看控制台输出，应该看到类似这样的日志：

```
INFO  c.e.i.service.CommandScanner - 开始扫描指令...
INFO  c.e.i.service.CommandRegistry - 注册指令: /ping - 检测服务器连通性
INFO  c.e.i.service.CommandRegistry - 注册指令: /uptime - 查看系统运行时间
INFO  c.e.i.service.CommandRegistry - 注册指令: /jira - JIRA 任务管理工具
INFO  c.e.i.service.CommandRegistry - 注册指令: /gitlog - 查看 Git 提交日志
INFO  c.e.i.service.CommandScanner - 指令扫描完成，共注册 4 个指令
```

**如果没有看到这些日志，说明指令没有注册成功，请检查：**
1. 类是否添加了 `@Component` 注解
2. 方法是否添加了 `@Command` 注解
3. 方法签名是否正确（`public String 方法名(CommandContext context)`）

## 4. 运行单元测试

### 运行 QuickTest

```bash
cd "f:\IDEA project\IntelligenTxtSystem"
mvn test -Dtest=QuickTest
```

### 运行 CommandFrameworkTest

```bash
cd "f:\IDEA project\IntelligenTxtSystem"
mvn test -Dtest=CommandFrameworkTest
```

## 5. 在飞书中测试

### 测试 /ping 指令

```
/ping google.com
```

**预期结果：**
```
✅ **google.com** 可达
延迟: 123ms
状态码: 200
```

### 测试 /uptime 指令

```
/uptime
```

**预期结果：**
```
⏱️ 系统运行时间:
0 天 1 小时 30 分钟
```

### 测试 /hello 指令（如果存在）

```
/hello 世界
```

**预期结果：**
```
👋 你好，世界！
```

### 测试 /help 指令

```
/help
```

**预期结果：**
```
📖 指令列表

**/ping**
  检测服务器连通性
  用法: /ping <host>

**/uptime**
  查看系统运行时间
  用法: /uptime
...
```

### 测试未知指令

```
/unknown
```

**预期结果：**
```
❌ 未知指令: /unknown

💡 使用 /help 查看所有可用指令
```

## 6. 添加新指令并测试

### 步骤 1：创建新的 Handler 类

在 `src/main/java/com/example/intelligentxtsystem/service/handler/` 目录下创建 `HelloCommandHandler.java`：

```java
package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.annotation.Command;
import com.example.intelligentxtsystem.dto.CommandContext;
import org.springframework.stereotype.Component;

@Component
public class HelloCommandHandler {
    
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

### 步骤 2：重启应用

重启后，查看启动日志，应该看到：
```
INFO  c.e.i.service.CommandRegistry - 注册指令: /hello - 打招呼
```

### 步骤 3：测试新指令

在飞书中发送：
```
/hello 世界
```

预期结果：
```
👋 你好，世界！
```

## 7. 常见问题排查

### 问题 1：指令没有注册

**症状：** 启动日志中没有显示指令注册信息

**排查步骤：**
1. 检查类是否有 `@Component` 注解
2. 检查方法是否有 `@Command` 注解
3. 检查包路径是否在 Spring Boot 的扫描范围内（默认是主类所在包及其子包）
4. 查看是否有其他错误信息

### 问题 2：指令执行报错

**症状：** 发送指令后，返回错误信息

**排查步骤：**
1. 检查 `CommandScanner.validateMethodSignature()` 是否通过
2. 检查方法内部是否有异常
3. 查看应用日志，找到异常堆栈

### 问题 3：参数解析错误

**症状：** 无法正确获取指令参数

**解决方法：**
```java
// 正确获取参数
String[] args = context.getArgsArray();  // 获取参数数组
String arg0 = context.getArg(0);         // 获取第一个参数

// 错误示例
String args = context.getArgs();  // 返回的是字符串，不是数组
```

## 8. 下一步

1. **迁移现有指令**：将 `DevOpsHandler` 中的指令逐步迁移到新框架
2. **添加更多指令**：根据需求添加新的指令
3. **完善功能**：实现鉴权、异步执行等功能

## 9. 参考文档

- `docs/COMMAND-FRAMEWORK.md` - 使用指南
- `docs/FRAMEWORK-SUMMARY.md` - 实现总结
- `docs/TESTING-GUIDE.md` - 本文档
