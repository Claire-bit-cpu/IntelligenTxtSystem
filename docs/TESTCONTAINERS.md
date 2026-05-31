# TestContainers 使用指南

## 概述

TestContainers 是一个 Java 库，用于在 JUnit 测试中自动启动 Docker 容器。这样可以：
- 避免本地环境依赖（无需手动安装 Redis、PostgreSQL 等）
- 提供隔离的测试环境（每个测试使用独立的容器）
- 确保测试的一致性（使用相同的容器版本）

## 已配置的容器

### Redis

用于测试 `ContextManager` 等依赖 Redis 的组件。

**使用示例**：

```java
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public class YourTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @BeforeAll
    static void setup() {
        // 动态配置 Redis 连接
        System.setProperty("spring.data.redis.host", redis.getHost());
        System.setProperty("spring.data.redis.port", String.valueOf(redis.getMappedPort(6379)));
    }
}
```

## 运行测试

### 前提条件

1. 安装 Docker Desktop（Windows/Mac）或 Docker Engine（Linux）
2. 启动 Docker 服务
3. 确保 Maven 可以下载 TestContainers 依赖（首次需要联网）

### 方式 1：使用 TestContainers（需要 Docker）

```bash
cd "F:\IDEA project\IntelligenTxtSystem"
mvn test -Dtest=ContextManagerRedisTest
```

### 方式 2：使用本地 Redis（无需 Docker）

如果你已经有本地 Redis，可以修改 `ContextManagerRedisTest.java`，移除 `@Testcontainers` 和 `@Container`，直接使用本地 Redis。

1. 启动本地 Redis（`redis-server.exe`）
2. 确保 `application-test.yaml` 中的 Redis 配置正确：
   ```yaml
   spring:
     data:
       redis:
         host: localhost
         port: 6379
   ```
3. 运行测试

### 方式 3：禁用 Redis 测试

如果既没有 Docker 也没有本地 Redis，可以禁用 `ContextManagerRedisTest`：

```java
@Disabled("需要 Redis 环境")
public class ContextManagerRedisTest {
    // ...
}
```

## 常用容器

### Redis

```java
@Container
static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);
```

### PostgreSQL

需要添加依赖：`org.testcontainers:postgresql`

```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("test")
        .withUsername("test")
        .withPassword("test");
```

### MySQL

需要添加依赖：`org.testcontainers:mysql`

```java
@Container
static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("test")
        .withUsername("test")
        .withPassword("test");
```

## 注意事项

1. **Docker 资源**：确保 Docker 分配足够的内存（建议 4GB+）
2. **测试速度**：容器启动需要时间，建议使用 `@Container` 静态容器（所有测试方法共享）
3. **端口冲突**：TestContainers 会自动分配可用端口，避免冲突
4. **CI/CD**：在 CI 环境中，确保 CI 服务器支持 Docker（如 GitHub Actions、GitLab CI）
5. **依赖下载**：首次运行会下载 Docker 镜像，需要较长时间

## 参考资料

- [TestContainers 官方文档](https://www.testcontainers.org/)
- [TestContainers Spring Boot 集成](https://www.testcontainers.org/quickstart/springboot_quickstart/)
- [TestContainers JUnit 5 支持](https://www.testcontainers.org/quickstart/junit_5_quickstart/)
