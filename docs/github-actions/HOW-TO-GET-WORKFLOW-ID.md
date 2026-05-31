# 如何获取 GitHub Actions 工作流的 workflow_id

## 方法1：使用工作流文件名（推荐）

**最简单的方法**：直接使用工作流文件名（包含 `.yml` 或 `.yaml` 后缀）

示例：
```yaml
# 在 application.yaml 中配置
github:
  deploy:
    dev: Claire-bit-cpu/Test:deploy-dev.yml:develop
    #                         ^^^^^^^^^^^^^^^^ 这就是 workflow_id
```

**优点**：
- ✅ 简单直观
- ✅ 易于维护
- ✅ 文件名改变时需要同步更新配置

---

## 方法2：使用 GitHub API 查询工作流 ID

### 2.1 使用 curl 命令

```bash
# 列出仓库的所有工作流
curl -H "Authorization: Bearer YOUR_GITHUB_TOKEN" \
     -H "Accept: application/vnd.github.v3+json" \
     https://api.github.com/repos/Claire-bit-cpu/Test/actions/workflows

# 示例响应
{
  "total_count": 3,
  "workflows": [
    {
      "id": 12345678,              ← 这就是 workflow_id（数字）
      "name": "Deploy to Dev",
      "path": ".github/workflows/deploy-dev.yml",
      "state": "active"
    },
    {
      "id": 87654321,              ← 另一个 workflow_id
      "name": "Deploy to Prod",
      "path": ".github/workflows/deploy-prod.yml",
      "state": "active"
    }
  ]
}
```

### 2.2 使用 GitHub CLI（gh）

```bash
# 安装 GitHub CLI
# Windows: winget install GitHub.cli
# macOS: brew install gh
# Linux: sudo apt install gh

# 登录
gh auth login

# 列出工作流
gh workflow list --repo Claire-bit-cpu/Test

# 示例输出
12345678  Deploy to Dev   active  deploy-dev.yml
87654321  Deploy to Prod  active  deploy-prod.yml
#         ^^^^^^^^^^^^ 这就是 workflow_id
```

### 2.3 在 Java 代码中使用

```java
// 在 GitHubClient.java 中添加方法
public List<Map<String, Object>> listWorkflows(String owner, String repo) {
    String url = apiUrl + "/repos/" + owner + "/" + repo + "/actions/workflows";
    
    HttpHeaders headers = new HttpHeaders();
    headers.set("Authorization", "Bearer " + token);
    headers.set("Accept", "application/vnd.github.v3+json");
    
    HttpEntity<Void> entity = new HttpEntity<>(headers);
    
    try {
        ResponseEntity<Map> response = restTemplate.exchange(
            url, 
            HttpMethod.GET, 
            entity, 
            Map.class
        );
        
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> workflows = 
                (List<Map<String, Object>>) response.getBody().get("workflows");
            return workflows;
        }
    } catch (Exception e) {
        logger.error("获取工作流列表失败", e);
    }
    return null;
}
```

调用示例：
```java
List<Map<String, Object>> workflows = gitHubClient.listWorkflows("Claire-bit-cpu", "Test");
if (workflows != null) {
    for (Map<String, Object> workflow : workflows) {
        System.out.println("ID: " + workflow.get("id") + 
                          ", Name: " + workflow.get("name") + 
                          ", File: " + workflow.get("path"));
    }
}
```

---

## 方法3：从 GitHub 网站获取

1. 打开仓库页面：https://github.com/Claire-bit-cpu/Test
2. 点击 **Actions** 标签页
3. 在左侧工作流列表中，点击你想要的工作流
4. 查看浏览器地址栏，URL 格式如下：
   ```
   https://github.com/Claire-bit-cpu/Test/actions/workflows/deploy-dev.yml
   #                                                          ^^^^^^^^^^^^^^^^ 这就是 workflow_id（文件名）
   
   # 或者（旧版本 GitHub）
   https://github.com/Claire-bit-cpu/Test/actions/workflows/12345678
   #                                                      ^^^^^^^^ 这就是 workflow_id（数字ID）
   ```

---

## workflow_id 的三种形式

GitHub API 接受以下三种形式的 `workflow_id`：

| 形式 | 示例 | 推荐度 |
|------|------|--------|
| **文件名** | `deploy-dev.yml` | ⭐⭐⭐⭐⭐（最推荐） |
| **数字 ID** | `12345678` | ⭐⭐⭐⭐（稳定，但不直观） |
| **文件名（不含后缀）** | `deploy-dev` | ⭐⭐⭐（不推荐，可能冲突） |

### 在 API 调用中的使用

```java
// 使用文件名（推荐）
gitHubClient.triggerWorkflow("Claire-bit-cpu", "Test", "deploy-dev.yml", "develop", null);

// 使用数字 ID
gitHubClient.triggerWorkflow("Claire-bit-cpu", "Test", "12345678", "develop", null);
```

---

## 在 IntelligenTxtSystem 中的配置示例

### 配置方式1：在 application.yaml 中配置（推荐）

```yaml
github:
  deploy:
    # 格式：环境名 = owner/repo:工作流文件名:分支名
    dev: Claire-bit-cpu/Test:deploy-dev.yml:develop
    test: Claire-bit-cpu/Test:deploy-test.yml:test
    staging: Claire-bit-cpu/Test:deploy-staging.yml:staging
    prod: Claire-bit-cpu/Test:deploy-prod.yml:main
```

### 配置方式2：使用数字 ID（更稳定）

```yaml
github:
  deploy:
    # 使用数字 ID（从 API 获取）
    dev: Claire-bit-cpu/Test:12345678:develop
    test: Claire-bit-cpu/Test:87654321:test
```

---

## 常见问题

### Q1: 文件名改了怎么办？
**A**: 同步更新 `application.yaml` 中的配置即可。

### Q2: 数字 ID 会改变吗？
**A**: 不会。数字 ID 是 GitHub 分配的唯一标识，即使文件名改变，ID 也不会变。

### Q3: 推荐使用哪种方式？
**A**: **推荐使用文件名**（如 `deploy-dev.yml`），因为：
- 直观易懂
- 便于维护
- 与代码仓库同步

### Q4: 如何查看当前配置的工作流 ID？
**A**: 使用飞书命令：
```
/github workflow Claire-bit-cpu/Test deploy-dev.yml develop
```

---

## 参考链接

- [GitHub Actions API 文档](https://docs.github.com/en/rest/actions/workflows)
- [触发工作流 API](https://docs.github.com/en/rest/actions/workflows#create-a-workflow-dispatch-event)
- [GitHub CLI 文档](https://cli.github.com/manual/)
