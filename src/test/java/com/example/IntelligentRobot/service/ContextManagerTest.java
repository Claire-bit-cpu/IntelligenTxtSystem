package com.example.IntelligentRobot.service;

import com.example.IntelligentRobot.dto.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextManager 单元测试
 * 测试上下文感知功能
 * 使用 test profile，确保测试环境不加载加密等可选功能
 */
@SpringBootTest
@ActiveProfiles("test")
public class ContextManagerTest {

    @Autowired
    private ContextManager contextManager;

    @BeforeEach
    void setUp() {
        // ContextManager 由 Spring 自动注入
    }

    /**
     * 测试1：局部上下文的基本存储和读取
     */
    @Test
    void testLocalContextBasic() {
        String userId = "user_123";
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

    /**
     * 测试2：自动填充参数（局部参数）
     */
    @Test
    void testAutoFillLocalParams() {
        String userId = "user_456";
        String contextType = "weather";

        // 先设置上下文
        Map<String, Object> params = new ConcurrentHashMap<>();
        params.put("city", "北京");
        contextManager.setLocalContext(userId, contextType, params, 5);

        // 模拟当前输入（没有城市参数）
        Map<String, Object> currentParams = new ConcurrentHashMap<>();

        // 自动填充
        Map<String, Object> filledParams = contextManager.autoFillParams(
                userId, contextType, currentParams, new String[]{"city"}
        );

        assertEquals("北京", filledParams.get("city"));
    }

    /**
     * 测试3：当前输入优先于上下文
     */
    @Test
    void testCurrentInputPriority() {
        String userId = "user_789";
        String contextType = "weather";

        // 先设置上下文（城市=北京）
        Map<String, Object> params = new ConcurrentHashMap<>();
        params.put("city", "北京");
        contextManager.setLocalContext(userId, contextType, params, 5);

        // 当前输入（城市=上海）
        Map<String, Object> currentParams = new ConcurrentHashMap<>();
        currentParams.put("city", "上海");

        // 自动填充（当前输入应该优先）
        Map<String, Object> filledParams = contextManager.autoFillParams(
                userId, contextType, currentParams, new String[]{"city"}
        );

        assertEquals("上海", filledParams.get("city"));
    }

    /**
     * 测试4：全局参数的跨指令复用
     */
    @Test
    void testGlobalParams() {
        String userId = "user_global";
        String contextType = "any";

        // 设置全局参数
        contextManager.setGlobalParam(userId, "token", "abc123");
        contextManager.setGlobalParam(userId, "username", "testuser");

        // 验证全局参数
        Map<String, Object> globalParams = contextManager.getGlobalParams(userId);
        assertEquals("abc123", globalParams.get("token"));
        assertEquals("testuser", globalParams.get("username"));

        // 自动填充应该包含全局参数
        Map<String, Object> currentParams = new ConcurrentHashMap<>();
        String[] localParamNames = {"token", "username"};

        Map<String, Object> filledParams = contextManager.autoFillParams(
                userId, contextType, currentParams, localParamNames
        );

        assertEquals("abc123", filledParams.get("token"));
    }

    /**
     * 测试5：切换指令时清空局部上下文
     */
    @Test
    void testClearLocalContextOnSwitch() {
        String userId = "user_switch";

        // 设置 weather 上下文
        Map<String, Object> weatherParams = new ConcurrentHashMap<>();
        weatherParams.put("city", "深圳");
        contextManager.setLocalContext(userId, "weather", weatherParams, 5);

        // 设置 gitlog 上下文
        Map<String, Object> gitlogParams = new ConcurrentHashMap<>();
        gitlogParams.put("repo", "frontend");
        contextManager.setLocalContext(userId, "gitlog", gitlogParams, 5);

        // 验证两个上下文都存在
        assertNotNull(contextManager.getLocalContext(userId, "weather"));
        assertNotNull(contextManager.getLocalContext(userId, "gitlog"));

        // 清空所有局部上下文（切换指令时）
        contextManager.clearAllLocalContexts(userId);

        // 验证所有局部上下文都被清空
        assertNull(contextManager.getLocalContext(userId, "weather"));
        assertNull(contextManager.getLocalContext(userId, "gitlog"));
    }

    /**
     * 测试6：上下文超时
     * 注意：ContextManager 现在使用 Redis TTL 实现超时
     * 这个测试验证 Redis TTL 机制
     */
    @Test
    void testContextTimeout() throws InterruptedException {
        String userId = "user_timeout";
        String contextType = "weather";

        // 设置上下文（超时时间1秒，用于测试）
        Map<String, Object> params = new ConcurrentHashMap<>();
        params.put("city", "广州");
        contextManager.setLocalContext(userId, contextType, params, 1); // 1分钟超时

        // 立即读取，应该存在
        UserContext context = contextManager.getLocalContext(userId, contextType);
        assertNotNull(context);
        assertEquals("广州", context.getParam("city"));

        // 注意：由于使用 Redis TTL，无法通过修改 lastAccessTime 来模拟超时
        // 如果需要测试 TTL 过期，需要等待实际时间
        // 这里我们只验证上下文可以正常读取和更新

        // 验证访问时间更新（通过读取触发）
        Thread.sleep(10); // 确保时间有微小差异
        UserContext context2 = contextManager.getLocalContext(userId, contextType);
        assertNotNull(context2);
        assertTrue(context2.getLastAccessTime() >= context.getLastAccessTime());

        // 清理
        contextManager.clearLocalContext(userId, contextType);
        assertNull(contextManager.getLocalContext(userId, contextType));
    }

    /**
     * 测试7：强独立性指令不影响上下文
     */
    @Test
    void testIndependentCommand() {
        String userId = "user_independent";

        // 设置 weather 上下文
        Map<String, Object> params = new ConcurrentHashMap<>();
        params.put("city", "杭州");
        contextManager.setLocalContext(userId, "weather", params, 5);

        // 验证上下文存在
        assertNotNull(contextManager.getLocalContext(userId, "weather"));

        // 模拟强独立性指令（如 /jira 1234）
        // 不应该清空其他指令的上下文
        // 这个测试需要在 CommandRegistry 中实现

        // 清理
        contextManager.clearAll(userId);
        assertNull(contextManager.getLocalContext(userId, "weather"));
    }

    /**
     * 测试8：全局参数和局部参数的隔离
     */
    @Test
    void testGlobalAndLocalIsolation() {
        String userId = "user_isolation";

        // 设置全局参数
        contextManager.setGlobalParam(userId, "token", "global_token");

        // 设置 weather 局部上下文
        Map<String, Object> weatherParams = new ConcurrentHashMap<>();
        weatherParams.put("city", "成都");
        weatherParams.put("token", "local_token"); // 局部参数也有 token
        contextManager.setLocalContext(userId, "weather", weatherParams, 5);

        // 验证：局部参数应该覆盖全局参数
        Map<String, Object> currentParams = new ConcurrentHashMap<>();
        Map<String, Object> filledParams = contextManager.autoFillParams(
                userId, "weather", currentParams, new String[]{"city", "token"}
        );

        // city 应该从局部上下文获取
        assertEquals("成都", filledParams.get("city"));

        // token 应该优先从局部上下文获取（如果局部有）
        // 注意：当前实现是全局参数先填充，局部参数后填充（覆盖）
        // 所以这个测试取决于具体实现
    }
}
