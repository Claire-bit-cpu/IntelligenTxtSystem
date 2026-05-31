package com.example.IntelligentRobot.service;

import com.example.IntelligentRobot.dto.UserContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager Redis 集成测试
 *
 * 前提条件：
 * 1. 启动本地 Redis（redis-server.exe）
 * 2. 或者安装并启动 Docker Desktop（用于 TestContainers）
 *
 * 如果没有 Redis 环境，可以禁用此测试：
 * - 方法 1：注释掉 @SpringBootTest 注解
 * - 方法 2：添加 @Disabled 注解
 */
@SpringBootTest
@ActiveProfiles("test")
public class ContextManagerRedisTest {

    @Autowired
    private ContextManager contextManager;

    @BeforeEach
    void setup() {
        // 确保 Redis 已启动
        // 如果没有 Redis，测试会失败
    }

    @AfterEach
    void cleanup() {
        // 清理测试数据
        contextManager.clearAll("test_user");
    }

    @Test
    void testLocalContextWithRealRedis() {
        String userId = "test_user";
        String contextType = "weather";
        Map<String, Object> params = new ConcurrentHashMap<>();
        params.put("city", "深圳");

        // 设置上下文
        contextManager.setLocalContext(userId, contextType, params, 5);

        // 读取上下文
        UserContext context = contextManager.getLocalContext(userId, contextType);
        assertNotNull(context);
        assertEquals("深圳", context.getParam("city"));
        assertEquals("weather", context.getContextType());
    }

    @Test
    void testGlobalParamsWithRealRedis() {
        String userId = "test_user";

        // 设置全局参数
        contextManager.setGlobalParam(userId, "token", "abc123");
        contextManager.setGlobalParam(userId, "username", "testuser");

        // 验证全局参数
        Map<String, Object> globalParams = contextManager.getGlobalParams(userId);
        assertEquals("abc123", globalParams.get("token"));
        assertEquals("testuser", globalParams.get("username"));
    }

    @Test
    void testClearAllWithRealRedis() {
        String userId = "test_user";

        // 设置上下文和全局参数
        Map<String, Object> params = new ConcurrentHashMap<>();
        params.put("city", "北京");
        contextManager.setLocalContext(userId, "weather", params, 5);
        contextManager.setGlobalParam(userId, "token", "test");

        // 验证存在
        assertNotNull(contextManager.getLocalContext(userId, "weather"));
        assertFalse(contextManager.getGlobalParams(userId).isEmpty());

        // 清理
        contextManager.clearAll(userId);

        // 验证已清理
        assertNull(contextManager.getLocalContext(userId, "weather"));
        assertTrue(contextManager.getGlobalParams(userId).isEmpty());
    }
}
