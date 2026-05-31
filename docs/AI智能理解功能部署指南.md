# AI 智能理解功能部署指南

## 部署前准备

### 1. 环境要求

- **JDK**：17+
- **Redis**：6.0+（用于存储对话历史）
- **Maven**：3.8+（用于构建项目）
- **网络**：能够访问通义千问API（https://dashscope.aliyuncs.com）

### 2. 配置准备

#### 2.1 通义千问API Key

1. 访问阿里云通义千问控制台：https://dashscope.aliyun.com/
2. 创建API Key
3. 记录API Key，用于后续配置

#### 2.2 Redis配置

确保Redis服务器正常运行，并记录以下信息：
- Redis主机地址
- Redis端口
- Redis密码（如果有）
- Redis数据库编号

#### 2.3 飞书机器人配置

确保飞书机器人已正确配置：
- 飞书应用App ID
- 飞书应用App Secret
- 飞书应用权限（接收消息、发送消息等）

## 部署步骤

### 步骤1：克隆代码

```bash
git clone <仓库地址>
cd IntelligenTxtSystem
```

### 步骤2：配置环境变量

创建`.env`文件或在系统中设置环境变量：

```bash
# 通义千问配置
QIANYU_API_KEY=<你的通义千问API Key>
QIANYU_API_URL=https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation
QIANYU_MODEL=qwen-turbo

# Redis配置
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DATABASE=0

# 飞书配置
FEISHU_APP_ID=<你的飞书应用App ID>
FEISHU_APP_SECRET=<你的飞书应用App Secret>
FEISHU_API_BASE_URL=https://open.feishu.cn/open-apis

# AI智能理解配置
AI_ENABLED=true
AI_SYSTEM_PROMPT=

# 服务端口
SERVER_PORT=8082
```

### 步骤3：构建项目

```bash
# 使用Maven构建项目
./mvnw clean package -DskipTests

# 或者如果使用本地Maven
mvn clean package -DskipTests
```

构建成功后，会在`target/`目录下生成`IntelligenTxtSystem-0.0.1-SNAPSHOT.jar`文件。

### 步骤4：运行项目

```bash
# 使用Java运行jar包
java -jar target/IntelligenTxtSystem-0.0.1-SNAPSHOT.jar

# 或者指定环境变量运行
java -jar \
  -DQIANYU_API_KEY=<你的API Key> \
  -DREDIS_HOST=localhost \
  -DSERVER_PORT=8082 \
  target/IntelligenTxtSystem-0.0.1-SNAPSHOT.jar
```

### 步骤5：验证部署

1. **检查服务是否启动**：
   ```bash
   curl http://localhost:8082/api/redis/monitor/ping
   ```
   应该返回Redis连接成功的信息。

2. **测试AI理解功能**：
   ```bash
   curl "http://localhost:8082/api/ai/test?message=我想查天气&userId=test_user&chatId=test_chat"
   ```
   应该返回AI理解结果。

3. **在飞书群聊中测试**：
   - 在飞书群聊中@机器人
   - 输入自然语言消息，如"今天天气怎么样"
   - 检查机器人是否正确响应

## 配置说明

### application.yaml 配置

在`src/main/resources/application.yaml`中可以配置以下参数：

```yaml
# 通义千问配置
qianyu:
  api-key: ${QIANYU_API_KEY:}
  api-url: ${QIANYU_API_URL:https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation}
  model: ${QIANYU_MODEL:qwen-turbo}
  max-diff-length: ${QIANYU_MAX_DIFF_LENGTH:8000}

# Redis配置
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      database: ${REDIS_DATABASE:0}

# AI智能理解配置
ai:
  enabled: ${AI_ENABLED:true}
  system-prompt: ${AI_SYSTEM_PROMPT:}
```

## 故障排查

### 1. AI理解功能不工作

**症状**：@机器人发送自然语言消息，机器人不响应或返回错误

**排查步骤**：
1. 检查`ai.enabled`配置是否为`true`
2. 检查通义千问API Key是否正确配置
3. 检查Redis是否正常运行
4. 查看日志文件，确认是否有错误信息

**解决方法**：
- 确保环境变量`AI_ENABLED=true`
- 确保`QIANYU_API_KEY`配置正确
- 确保Redis服务器正常运行

### 2. 对话历史不保存

**症状**：对话历史没有保存，多轮对话不工作

**排查步骤**：
1. 检查Redis是否正常运行
2. 检查Redis Key是否正确（`ctx:history:{chatId}:{userId}`）
3. 查看日志文件，确认是否有保存失败的错误信息

**解决方法**：
- 确保Redis服务器正常运行
- 检查Redis配置是否正确
- 查看日志文件，确认是否有Redis连接错误

### 3. 飞书机器人不响应

**症状**：在飞书群聊中@机器人，机器人不响应

**排查步骤**：
1. 检查飞书应用配置是否正确
2. 检查飞书应用权限是否开启（接收消息、发送消息等）
3. 检查事件订阅是否配置正确（消息接收地址等）
4. 查看日志文件，确认是否有飞书API调用错误

**解决方法**：
- 确保飞书应用配置正确
- 确保飞书应用权限已开启
- 确保事件订阅配置正确
- 查看日志文件，确认是否有飞书API调用错误

## 性能优化

### 1. Redis优化

- **连接池配置**：根据并发量调整Redis连接池大小
- **内存优化**：定期清理过期的对话历史（已自动处理，TTL=7天）
- **网络优化**：确保Redis服务器与应用服务器之间的网络延迟低

### 2. 通义千问API优化

- **并发控制**：控制并发调用通义千问API的数量，避免限流
- **缓存结果**：对于相同的用户输入，可以考虑缓存AI回复（需注意隐私）
- **超时设置**：设置合理的API调用超时时间，避免长时间等待

### 3. 应用服务器优化

- **线程池配置**：根据并发量调整应用服务器的线程池大小
- **JVM优化**：根据服务器内存调整JVM堆内存大小
- **垃圾回收优化**：选择合适的垃圾回收器，减少GC停顿时间

## 安全考虑

1. **API Key安全**：不要将通义千问API Key硬编码在代码中，使用环境变量或配置中心
2. **Redis安全**：如果Redis有密码，确保密码强度足够，并定期更换
3. **网络安全**：确保Redis服务器只允许应用服务器访问，避免公网访问
4. **用户输入验证**：对用户输入进行验证，避免恶意输入导致系统异常
5. **敏感信息过滤**：在保存对话历史时，注意过滤敏感信息（如密码、token等）

## 监控与告警

### 1. 监控指标

- **API调用成功率**：通义千问API调用成功率
- **API调用延迟**：通义千问API调用延迟
- **Redis连接成功率**：Redis连接成功率
- **Redis操作延迟**：Redis操作延迟
- **飞书API调用成功率**：飞书API调用成功率

### 2. 告警策略

- **API调用失败率超过阈值**：告警通知管理员
- **API调用延迟超过阈值**：告警通知管理员
- **Redis连接失败**：告警通知管理员
- **服务异常重启**：告警通知管理员

## 备份与恢复

### 1. 配置备份

定期备份应用配置文件（`application.yaml`）和环境变量配置。

### 2. Redis数据备份

定期备份Redis数据（对话历史等），以防数据丢失。

### 3. 代码备份

定期备份代码仓库，确保可以快速回滚到之前的版本。

## 升级指南

### 1. 升级前准备

1. 备份当前配置文件
2. 备份当前代码
3. 在测试环境中测试新版本

### 2. 升级步骤

1. 停止当前服务
2. 替换新的jar包
3. 更新配置文件（如果有变化）
4. 启动新版本服务
5. 验证新版本功能是否正常

### 3. 回滚方案

如果新版本有问题，可以快速回滚到之前的版本：
1. 停止新版本服务
2. 恢复旧版本jar包
3. 恢复旧版本配置文件
4. 启动旧版本服务

## 支持与联系

如果遇到问题，可以：
1. 查看日志文件，确认错误信息
2. 查看本文档的故障排查部分
3. 联系项目管理员或开发团队