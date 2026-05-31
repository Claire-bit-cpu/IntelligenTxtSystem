# AI 智能理解功能 - 快速上手指南

## 5分钟快速上手

### 步骤1：确认配置

确保以下配置已正确设置：

1. **通义千问API Key**：在`application.yaml`或环境变量中配置`qianyu.api-key`
2. **Redis配置**：确保Redis已正确配置并运行
3. **AI功能开关**：确保`ai.enabled=true`（默认开启）

### 步骤2：启动服务

```bash
# 使用Maven构建并运行
./mvnw spring-boot:run

# 或者使用Java运行jar包
java -jar target/IntelligenTxtSystem-0.0.1-SNAPSHOT.jar
```

### 步骤3：在飞书群聊中测试

1. 在飞书群聊中@你的机器人
2. 输入自然语言消息，如：
   ```
   @智能助手 今天天气怎么样
   @智能助手 帮我看看代码
   @智能助手 我想创建一个JIRA任务
   ```
3. 机器人应该能理解你的意图并给出相应回复

### 步骤4：查看对话历史

机器人会记住最近的对话历史（最多50条），可以用于多轮对话：

```
用户：@智能助手 北京天气怎么样
机器人：北京今天晴天，温度25-30℃

用户：@智能助手 那上海呢？
机器人：上海今天多云，温度23-28℃
```

### 步骤5：清空对话历史（可选）

如果需要重新开始对话，可以使用`/clear_history`指令：

```
/clear_history
```

## 常用指令

### 1. 测试AI理解功能

```
/test_ai <消息>
```

示例：
```
/test_ai 我想查天气
/test_ai 帮我看看代码
```

### 2. 清空对话历史

```
/clear_history
```

别名：`/clear`、`/清空历史`、`/清除对话`

### 3. 查看帮助

```
/help
```

## 通过API测试

如果你不想在飞书群聊中测试，也可以通过API直接测试AI理解功能：

### 1. 测试AI理解功能

```bash
curl "http://localhost:8082/api/ai/test?message=我想查天气&userId=test_user&chatId=test_chat"
```

### 2. 查看对话历史

```bash
curl "http://localhost:8082/api/ai/history?userId=test_user&chatId=test_chat"
```

### 3. 清空对话历史

```bash
curl "http://localhost:8082/api/ai/clear?userId=test_user&chatId=test_chat"
```

## 故障排查

### 问题1：AI理解功能不工作

**症状**：@机器人发送自然语言消息，机器人不响应或返回错误

**解决方法**：
1. 检查`ai.enabled`配置是否为`true`
2. 检查通义千问API Key是否正确配置
3. 检查Redis是否正常运行
4. 查看日志文件，确认是否有错误信息

### 问题2：对话历史不保存

**症状**：对话历史没有保存，多轮对话不工作

**解决方法**：
1. 检查Redis是否正常运行
2. 检查Redis Key是否正确（`ctx:history:{chatId}:{userId}`）
3. 查看日志文件，确认是否有保存失败的错误信息

### 问题3：飞书机器人不响应

**症状**：在飞书群聊中@机器人，机器人不响应

**解决方法**：
1. 检查飞书应用配置是否正确
2. 检查飞书应用权限是否开启（接收消息、发送消息等）
3. 检查事件订阅是否配置正确（消息接收地址等）
4. 查看日志文件，确认是否有飞书API调用错误

## 高级配置

### 自定义系统提示词

如果你想定制AI理解行为，可以配置系统提示词：

```yaml
ai:
  system-prompt: "你是一个专业的智能助手，擅长理解用户意图并执行相应操作。"
```

或者通过环境变量配置：

```bash
AI_SYSTEM_PROMPT="你是一个专业的智能助手，擅长理解用户意图并执行相应操作。"
```

### 调整对话历史保存策略

默认情况下，对话历史保存最近50条消息，有效期7天。如果你需要调整，可以修改`AiUnderstandingService.java`中的以下常量：

```java
private static final long HISTORY_TTL_DAYS = 7;  // 有效期（天）
private static final int MAX_HISTORY = 50;         // 最大保存条数
```

## 下一步

- 阅读`docs/AI智能理解功能说明.md`了解详细功能说明
- 阅读`docs/AI智能理解功能部署指南.md`了解部署详情
- 阅读`docs/AI智能理解功能实现总结.md`了解技术实现细节

## 获取帮助

如果遇到问题，可以：
1. 查看本文档的故障排查部分
2. 查看日志文件，确认错误信息
3. 联系项目管理员或开发团队