# 上下文感知功能验证指南

## 验证步骤

### 1. 编译验证

运行以下命令验证项目是否能正常编译：

```bash
cd "f:\IDEA project\IntelligenTxtSystem"
mvn compile
```

**预期结果**：编译成功，无错误。

### 2. 单元测试

运行 `ContextManagerTest` 验证上下文管理功能：

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

**预期结果**：所有测试通过。

### 3. 功能验证

#### 验证1：天气查询（局部参数）

**步骤**：
1. 启动项目
2. 发送 `/weather 深圳` 查询天气
3. 5分钟内再次发送 `/weather`（不输入城市）
4. 观察是否自动复用深圳

**预期结果**：
- 第一次查询：正常显示深圳天气
- 第二次查询：自动复用深圳，无需重新输入

#### 验证2：Git 日志查询（多参数上下文）

**步骤**：
1. 发送 `/gitlog frontend 10 main` 查询提交日志
2. 发送 `/gitlog`（不输入任何参数）
3. 观察是否自动复用所有参数

**预期结果**：
- 第一次查询：正常显示 frontend 仓库 main 分支最近 10 条提交
- 第二次查询：自动复用所有参数，显示相同结果

#### 验证3：切换指令清空上下文

**步骤**：
1. 发送 `/weather 深圳` 查询天气
2. 发送 `/help` 切换指令
3. 发送 `/weather`（不输入城市）
4. 观察是否提示输入城市

**预期结果**：
- 切换指令后，weather 上下文被清空
- 再次查询天气时，提示输入城市

#### 验证4：强独立性指令（JIRA）

**步骤**：
1. 发送 `/jira PROJ-123` 查询 JIRA 问题
2. 发送 `/jira`（不输入问题编号）
3. 观察是否提示输入问题编号

**预期结果**：
- JIRA 指令是强独立性的，不继承任何上下文
- 每次都需要输入完整参数

#### 验证5：上下文超时

**步骤**：
1. 发送 `/weather 深圳` 查询天气
2. 等待 5 分钟以上
3. 发送 `/weather`（不输入城市）
4. 观察是否提示输入城市

**预期结果**：
- 超过 5 分钟超时时间后，上下文自动清空
- 再次查询天气时，提示输入城市

### 4. 代码验证

#### 验证 @Command 注解

检查 `src/main/java/com/example/intelligentxtsystem/annotation/Command.java` 是否包含以下属性：

```java
boolean supportsContext() default false;
String contextType() default "";
String[] globalParams() default {};
String[] localParams() default {};
int contextTimeout() default 5;
boolean independent() default false;
```

#### 验证 ContextManager

检查 `src/main/java/com/example/intelligentxtsystem/service/ContextManager.java` 是否包含以下方法：

- `getGlobalParams(userId)`
- `setGlobalParam(userId, key, value)`
- `getLocalContext(userId, contextType)`
- `setLocalContext(userId, contextType, params, timeout)`
- `autoFillParams(...)`
- `clearLocalContext(userId, contextType)`
- `clearAllLocalContexts(userId)`
- `clearGlobalParams(userId)`
- `clearAll(userId)`

#### 验证 CommandRegistry

检查 `src/main/java/com/example/intelligentxtsystem/service/CommandRegistry.java` 的 `execute()` 方法是否包含上下文处理逻辑。

### 5. 日志验证

启动项目后，观察日志输出：

**正常日志**：
```
INFO  c.e.i.s.CommandScanner - 开始扫描指令...
INFO  c.e.i.s.CommandScanner - 指令扫描完成，共注册 X 个指令
INFO  c.e.i.s.ContextManager - 上下文管理器初始化完成，定时清理任务已启动
DEBUG c.e.i.s.ContextManager - 设置局部上下文: user=xxx, type=weather, params={city=深圳}
DEBUG c.e.i.s.ContextManager - 从局部上下文填充: city=深圳
DEBUG c.e.i.s.ContextManager - 清除所有局部上下文: user=xxx
```

**异常日志**（如果有）：
```
WARN  c.e.i.s.ContextManager - 清理超时上下文: user=xxx, type=weather
```

## 常见问题

### 问题1：编译错误

**原因**：缺少依赖或语法错误

**解决**：
1. 检查所有修改的文件是否有语法错误
2. 运行 `mvn clean compile` 重新编译
3. 检查 IDE 是否有错误提示

### 问题2：上下文没有生效

**原因**：
1. 忘记设置 `supportsContext = true`
2. Handler 中没有正确处理 `filledParams`

**解决**：
1. 检查 `@Command` 注解是否设置了 `supportsContext = true`
2. 检查 Handler 中是否调用了 `context.getFilledParam()` 和 `context.setFilledParam()`
3. 查看日志，确认上下文是否被正确保存和读取

### 问题3：参数没有自动填充

**原因**：`localParams` 配置错误或未调用 `autoFillParams()`

**解决**：
1. 检查 `@Command` 注解的 `localParams` 是否包含需要填充的参数名
2. 检查 `CommandRegistry.execute()` 方法是否调用了 `contextManager.autoFillParams()`
3. 在 Handler 中手动处理 `filledParams`

### 问题4：上下文没有超时清理

**原因**：`ContextManager` 的定时清理任务没有启动

**解决**：
1. 检查 `ContextManager` 是否添加了 `@Component` 注解
2. 检查 Spring 是否正确注入了 `ContextManager`
3. 查看日志，确认定时清理任务是否启动

## 总结

如果以上验证步骤都通过，说明上下文感知功能已经正确实现并生效。

**核心功能验证**：
1. ✅ 局部参数自动填充
2. ✅ 全局参数跨指令复用
3. ✅ 切换指令清空局部上下文
4. ✅ 强独立性指令不继承上下文
5. ✅ 上下文超时自动清理

**向后兼容性验证**：
1. ✅ 未启用上下文的指令保持原有行为
2. ✅ 启用上下文的指令在不使用上下文时也能正常工作
3. ✅ 不影响其他功能

## 下一步

1. 为更多指令启用上下文感知（如 `/review`, `/search` 等）
2. 考虑使用 Redis 存储上下文（支持多实例部署）
3. 增加上下文切换提示（让用户知道当前使用的上下文）
4. 增加 `/clear` 指令手动清除上下文
