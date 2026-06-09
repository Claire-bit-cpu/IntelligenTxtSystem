# Nacos 配置中心集成指南

## 一、集成完成内容

### 1. 依赖添加（pom.xml）
- `spring-cloud-starter-alibaba-nacos-config`：Nacos配置中心客户端
- `spring-cloud-starter-bootstrap`：支持bootstrap.yaml配置文件
- Spring Cloud依赖管理：2023.0.1
- Spring Cloud Alibaba依赖管理：2023.0.1.0

### 2. 配置文件
- **bootstrap.yaml**：Nacos连接配置（优先级最高）
- **application.yaml**：本地默认配置（兜底配置）

### 3. 配置类动态刷新
已添加 `@RefreshScope` 注解的类：
- `FeishuProperties`：飞书配置
- `WelcomeConfig`：欢迎消息配置
- `GitLabConfig`：GitLab配置
- `GitHubConfig`：GitHub配置
- `QwenClient`：通义千问配置

## 二、Nacos 服务器安装与启动

### 方式1：本地启动（推荐用于开发）

1. 下载Nacos：https://github.com/alibaba/nacos/releases
2. 解压后进入bin目录
3. 启动命令（单机模式）：
   ```bash
   # Linux/Mac
   sh startup.sh -m standalone
   
   # Windows
   startup.cmd -m standalone
   ```
4. 访问控制台：http://localhost:8848/nacos
   - 默认用户名/密码：nacos/nacos

### 方式2：Docker启动

```bash
docker run -d \
  --name nacos-standalone \
  -p 8848:8848 \
  -e MODE=standalone \
  nacos/nacos-server:latest
```

## 三、Nacos 配置迁移步骤

### 1. 创建命名空间（环境隔离）

在Nacos控制台：
1. 左侧菜单 → 命名空间 → 新建命名空间
2. 创建三个命名空间：
   - `dev`：开发环境
   - `test`：测试环境
   - `prod`：生产环境
3. 记录命名空间ID（用于配置 `NACOS_NAMESPACE` 环境变量）

### 2. 创建配置文件

在Nacos控制台：
1. 左侧菜单 → 配置管理 → 配置列表
2. 选择命名空间（如 `dev`）
3. 点击「+」新建配置
4. 配置参数：
   - **Data ID**：`IntelligentRobot.yaml`（对应 spring.application.name + file-extension）
   - **Group**：`DEFAULT_GROUP`（或自定义组名）
   - **配置格式**：YAML
   - **配置内容**：使用下方模板（从 `docs/NACOS_CONFIG_TEMPLATE.yaml` 复制）

### 3. 配置内容模板

完整的配置模板已生成在 `docs/NACOS_CONFIG_TEMPLATE.yaml`，直接复制内容到Nacos即可。

**模板与 .env 的对应关系**：

| .env 变量名 | Nacos YAML 路径 | 说明 |
|---|---|---|
| `FEISHU_APP_ID` | `feishu.app-id` | 飞书应用ID |
| `FEISHU_APP_SECRET` | `feishu.app-secret` | 飞书应用密钥 |
| `FEISHU_ENCRYPT_KEY` | `feishu.encrypt-key` | 飞书加密密钥 |
| `FEISHU_APPROVAL_DEFINITION_CODE` | `feishu.approval-definition-code` | 审批定义Code |
| `REDIS_HOST` | `spring.data.redis.host` | Redis主机 |
| `REDIS_PORT` | `spring.data.redis.port` | Redis端口 |
| `REDIS_PASSWORD` | `spring.data.redis.password` | Redis密码 |
| `REDIS_DATABASE` | `spring.data.redis.database` | Redis数据库 |
| `QIANWEN_API_KEY` | `qianwen.api-key` | 通义千问API Key |
| `QIANWEN_MODEL` | `qianwen.model` | 通义千问模型 |
| `QIANWEN_MAX_DIFF_LENGTH` | `qianwen.max-diff-length` | 最大diff长度 |
| `GITHUB_TOKEN` | `github.token` | GitHub Token |
| `GITHUB_WEBHOOK_SECRET` | `github.webhook-secret` | Webhook密钥 |
| `GITHUB_REPO_ALIASES` | `github.repo-aliases` | 仓库别名 |
| `GITHUB_ADMIN_OPEN_IDS` | `github.admin-open-ids` | 管理员Open ID |
| `GITHUB_DEVELOPER_OPEN_IDS` | `github.developer-open-ids` | 开发者Open ID |
| `GITLAB_TOKEN` | `gitlab.token` | GitLab Token |
| `GITLAB_ENABLED` | `gitlab.enabled` | GitLab开关 |
| `JIRA_URL` | `jira.url` | JIRA地址 |
| `JIRA_API_TOKEN` | `jira.api-token` | JIRA API Token |
| `AMAP_KEY` | `amap.key` | 高德地图API Key |
| `NOTIFICATION_DB_PATH` | `notification.db-path` | 通知数据库路径 |
| `SEARCH_INDEX_PATH` | `search.index-path` | 搜索索引路径 |
| `SERVER_PORT` | `server.port` | 服务端口 |

### 4. 环境变量配置

在部署环境中设置以下环境变量：

```bash
# 必填：Nacos服务器地址
NACOS_SERVER_ADDR=localhost:8848

# 必填：命名空间ID（从Nacos控制台获取）
NACOS_NAMESPACE=dev

# 可选：配置分组
NACOS_GROUP=DEFAULT_GROUP

# 可选：如果Nacos开启了认证
NACOS_USERNAME=nacos
NACOS_PASSWORD=nacos
```

## 四、配置优先级

配置加载优先级（从高到低）：
1. Nacos配置中心（最高优先级）
2. 本地 `application.yaml`
3. 环境变量（`${ENV_VAR:default}` 语法）
4. 默认值

**示例**：
- 如果Nacos中有 `feishu.app-id`，则使用Nacos的值
- 如果Nacos中没有，则使用 `application.yaml` 中的 `${FEISHU_APP_ID:}` 占位符
- 如果环境变量 `FEISHU_APP_ID` 有值，则使用环境变量的值
- 如果都没有，则使用默认值（空字符串）

## 五、.env / application.yaml / Nacos 的关系

```
┌─────────────────────────────────────────────────────────────┐
│                   配置来源层级（优先级从高到低）                      │
├─────────────────────────────────────────────────────────────┤
│  1️⃣  Nacos 配置中心（最高优先级）                               │
│     └─ 集中管理，动态刷新，覆盖所有环境配置                       │
│                                                             │
│  2️⃣  application.yaml（本地配置文件）                          │
│     └─ ${ENV_VAR:default} 读取环境变量                        │
│                                                             │
│  3️⃣  系统环境变量（来自 .env 或手动设置）                      │
│     └─ .env 文件定义的变量需要加载到系统环境                    │
│                                                             │
│  4️⃣  默认值（最低优先级）                                     │
│     └─ ${ENV_VAR:默认值} 中的 "默认值" 部分                    │
└─────────────────────────────────────────────────────────────┘

集成 Nacos 后：
  .env 文件 ──❌ 不再需要（敏感配置全部迁移到 Nacos）
  application.yaml ──▶ 变成兜底配置（Nacos 没有的配置才使用）
  Nacos ──▶ 配置中心，统一管理所有环境配置
```

## 六、动态配置刷新测试

### 测试步骤

1. 启动应用，观察日志中是否有Nacos连接成功的信息
2. 修改Nacos中的配置（如 `feishu.app-id`）
3. 点击Nacos控制台的「发布」按钮
4. 应用会在3-5秒内自动刷新配置
5. 查看应用日志，应该看到配置刷新事件

### 验证动态刷新

创建一个测试接口来验证配置是否动态刷新：

```java
@RestController
@RequestMapping("/api/config")
public class ConfigTestController {
    
    @Autowired
    private FeishuProperties feishuProperties;
    
    @GetMapping("/feishu-app-id")
    public String getFeishuAppId() {
        return feishuProperties.getAppId();
    }
}
```

访问 `http://localhost:8082/api/config/feishu-app-id`，修改Nacos配置后再次访问，值应该已更新。

## 七、常见问题

### 1. 启动时报错：无法连接Nacos

**原因**：Nacos服务器未启动或地址配置错误

**解决**：
- 检查Nacos是否启动：访问 http://localhost:8848/nacos
- 检查 `NACOS_SERVER_ADDR` 环境变量是否正确

### 2. 配置不生效

**原因**：Data ID或Group不匹配

**解决**：
- 确保Data ID为 `IntelligentRobot.yaml`（注意大小写）
- 确保Group与 `bootstrap.yaml` 中的 `group` 配置一致

### 3. 动态刷新不生效

**原因**：配置类未添加 `@RefreshScope` 注解

**解决**：
- 检查配置类是否有 `@RefreshScope` 注解
- 确保配置是通过 `@ConfigurationProperties` 注入的

## 八、生产环境建议

1. **开启Nacos认证**：修改Nacos的 `application.properties`，开启认证功能
2. **使用集群模式**：生产环境部署Nacos集群，保证高可用
3. **配置加密**：敏感配置（如密码）使用Nacos的配置加密功能
4. **命名空间隔离**：严格使用命名空间隔离不同环境
5. **配置回滚**：Nacos支持配置版本管理和一键回滚
6. **权限控制**：为不同团队配置不同的访问权限

## 九、答辩说明

### 技术亮点
1. **配置集中管理**：所有环境配置统一在Nacos管理，避免配置分散
2. **动态配置刷新**：修改配置无需重启应用，提高可用性
3. **环境隔离**：使用命名空间实现dev/test/prod环境隔离
4. **配置优先级清晰**：Nacos > 本地配置 > 环境变量 > 默认值

### 配置流转
```
Nacos配置中心 → bootstrap.yaml → application.yaml → @ConfigurationProperties → Java Bean
```

### 动态刷新原理
- Nacos客户端监听配置变更
- 配置变更时触发 `RefreshEvent`
- `@RefreshScope` 标注的Bean会被重新创建，注入最新配置
