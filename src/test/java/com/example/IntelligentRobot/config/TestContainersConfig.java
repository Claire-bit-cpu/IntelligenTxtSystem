package com.example.IntelligentRobot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * TestContainers 配置
 * 为集成测试提供 Redis 等外部服务容器
 * 
 * 仅在激活 "testcontainers" profile 时生效：
 * - 有 Docker 环境：使用 -Dspring.profiles.active=test,testcontainers
 * - 无 Docker 环境：使用 -Dspring.profiles.active=test（使用本地 Redis）
 */
@Configuration
@Profile("testcontainers")
public class TestContainersConfig {

    /**
     * Redis 容器
     * 使用静态容器，避免每个测试类都启动新的容器
     */
    @Bean
    public GenericContainer<?> redisContainer() {
        GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redis.start();
        return redis;
    }
}
