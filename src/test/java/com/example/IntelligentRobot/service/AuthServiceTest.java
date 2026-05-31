package com.example.IntelligentRobot.service;

import com.example.IntelligentRobot.dto.PermissionLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuthService 鉴权服务测试
 * 
 * 测试覆盖：
 * 1. 管理员权限验证
 * 2. 开发者权限验证
 * 3. 无权限用户访问拒绝
 * 4. 白名单未配置时的安全行为
 * 5. 权限级别判断
 * 6. Open ID 脱敏
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "github.admin-open-ids=ou_admin1,ou_admin2",
    "github.developer-open-ids=ou_dev1,ou_dev2,ou_admin1"
})
public class AuthServiceTest {

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService();
        // 通过反射设置配置值（因为 @Value 在单元测试中不会自动注入）
        setField(authService, "adminOpenIdsConfig", "ou_admin1,ou_admin2");
        setField(authService, "developerOpenIdsConfig", "ou_dev1,ou_dev2,ou_admin1");
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 测试 1：管理员权限验证
     */
    @Test
    void testIsAdmin() {
        // 管理员应该在白名单中
        assertTrue(authService.isAdmin("ou_admin1"));
        assertTrue(authService.isAdmin("ou_admin2"));
        
        // 非管理员不应该有管理员权限
        assertFalse(authService.isAdmin("ou_dev1"));
        assertFalse(authService.isAdmin("ou_dev2"));
        assertFalse(authService.isAdmin("ou_unknown"));
        
        // 边界情况
        assertFalse(authService.isAdmin(null));
        assertFalse(authService.isAdmin(""));
        assertFalse(authService.isAdmin("default"));
    }

    /**
     * 测试 2：开发者权限验证（管理员自动拥有开发者权限）
     */
    @Test
    void testIsDeveloper() {
        // 开发者应该在白名单中
        assertTrue(authService.isDeveloper("ou_dev1"));
        assertTrue(authService.isDeveloper("ou_dev2"));
        
        // 管理员自动拥有开发者权限
        assertTrue(authService.isDeveloper("ou_admin1"));
        assertTrue(authService.isDeveloper("ou_admin2"));
        
        // 非开发者/管理员不应该有开发者权限
        assertFalse(authService.isDeveloper("ou_unknown"));
        
        // 边界情况
        assertFalse(authService.isDeveloper(null));
        assertFalse(authService.isDeveloper(""));
        assertFalse(authService.isDeveloper("default"));
    }

    /**
     * 测试 3：权限级别验证
     */
    @Test
    void testHasPermission() {
        // NONE 级别：所有用户都有权限
        assertTrue(authService.hasPermission("ou_admin1", PermissionLevel.NONE));
        assertTrue(authService.hasPermission("ou_dev1", PermissionLevel.NONE));
        assertTrue(authService.hasPermission("ou_unknown", PermissionLevel.NONE));
        assertTrue(authService.hasPermission(null, PermissionLevel.NONE));

        // DEVELOPER 级别：开发者和管理员有权限
        assertTrue(authService.hasPermission("ou_admin1", PermissionLevel.DEVELOPER));
        assertTrue(authService.hasPermission("ou_dev1", PermissionLevel.DEVELOPER));
        assertFalse(authService.hasPermission("ou_unknown", PermissionLevel.DEVELOPER));

        // ADMIN 级别：只有管理员有权限
        assertTrue(authService.hasPermission("ou_admin1", PermissionLevel.ADMIN));
        assertFalse(authService.hasPermission("ou_dev1", PermissionLevel.ADMIN));
        assertFalse(authService.hasPermission("ou_unknown", PermissionLevel.ADMIN));
    }

    /**
     * 测试 4：获取用户权限级别
     */
    @Test
    void testGetUserPermissionLevel() {
        assertEquals(PermissionLevel.ADMIN, authService.getUserPermissionLevel("ou_admin1"));
        assertEquals(PermissionLevel.DEVELOPER, authService.getUserPermissionLevel("ou_dev1"));
        assertEquals(PermissionLevel.NONE, authService.getUserPermissionLevel("ou_unknown"));
        assertEquals(PermissionLevel.NONE, authService.getUserPermissionLevel(null));
        assertEquals(PermissionLevel.NONE, authService.getUserPermissionLevel(""));
        assertEquals(PermissionLevel.NONE, authService.getUserPermissionLevel("default"));
    }

    /**
     * 测试 5：权限拒绝时的友好错误信息
     */
    @Test
    void testCheckPermissionWithMessage() {
        // 有权限时返回 null
        assertNull(authService.checkPermissionWithMessage("ou_admin1", PermissionLevel.ADMIN));
        assertNull(authService.checkPermissionWithMessage("ou_admin1", PermissionLevel.DEVELOPER));
        assertNull(authService.checkPermissionWithMessage("ou_dev1", PermissionLevel.DEVELOPER));
        assertNull(authService.checkPermissionWithMessage("ou_dev1", PermissionLevel.NONE));

        // 无权限时返回友好错误信息
        String adminMsg = authService.checkPermissionWithMessage("ou_dev1", PermissionLevel.ADMIN);
        assertNotNull(adminMsg);
        assertTrue(adminMsg.contains("仅管理员"));

        String devMsg = authService.checkPermissionWithMessage("ou_unknown", PermissionLevel.DEVELOPER);
        assertNotNull(devMsg);
        assertTrue(devMsg.contains("开发者"));

        // 无法识别用户时
        String unknownMsg = authService.checkPermissionWithMessage(null, PermissionLevel.DEVELOPER);
        assertNotNull(unknownMsg);
        assertTrue(unknownMsg.contains("无法识别用户身份"));
    }

    /**
     * 测试 6：白名单未配置时的安全行为（fail-secure）
     */
    @Test
    void testWhiteListNotConfigured() {
        AuthService emptyAuthService = new AuthService();
        setField(emptyAuthService, "adminOpenIdsConfig", "");
        setField(emptyAuthService, "developerOpenIdsConfig", "");

        // 白名单为空时，应该拒绝所有鉴权请求
        assertFalse(emptyAuthService.hasPermission("ou_admin1"));
        assertFalse(emptyAuthService.hasPermission("ou_dev1"));
        assertFalse(emptyAuthService.hasPermission("ou_anyone"));
    }

    /**
     * 测试 7：requirePermission 抛出异常
     */
    @Test
    void testRequirePermissionThrowsException() {
        // 有权限时不应该抛出异常
        assertDoesNotThrow(() -> 
            authService.requirePermission("ou_admin1", PermissionLevel.ADMIN));
        assertDoesNotThrow(() -> 
            authService.requirePermission("ou_admin1", PermissionLevel.DEVELOPER));
        assertDoesNotThrow(() -> 
            authService.requirePermission("ou_dev1", PermissionLevel.DEVELOPER));

        // 无权限时应该抛出 SecurityException
        assertThrows(SecurityException.class, () -> 
            authService.requirePermission("ou_dev1", PermissionLevel.ADMIN));
        assertThrows(SecurityException.class, () -> 
            authService.requirePermission("ou_unknown", PermissionLevel.DEVELOPER));
    }

    /**
     * 测试 8：Open ID 脱敏（通过日志验证）
     */
    @Test
    void testMaskOpenId() {
        AuthService testService = new AuthService();
        
        // 使用反射调用私有方法 maskOpenId
        try {
            var maskMethod = AuthService.class.getDeclaredMethod("maskOpenId", String.class);
            maskMethod.setAccessible(true);

            // 正常 Open ID：ou_abc123def456 → ou_a***f456
            String masked = (String) maskMethod.invoke(testService, "ou_abc123def456");
            assertTrue(masked.startsWith("ou_a"), "脱敏后的 Open ID 应该以 ou_a 开头");
            assertTrue(masked.endsWith("f456"), "脱敏后的 Open ID 应该以 f456 结尾");
            assertTrue(masked.contains("***"), "脱敏后的 Open ID 应该包含 ***");

            // 短 Open ID：应该返回 ***
            assertEquals("***", maskMethod.invoke(testService, "short"));
            assertEquals("***", maskMethod.invoke(testService, new Object[]{null}));
            assertEquals("***", maskMethod.invoke(testService, ""));
        } catch (Exception e) {
            fail("反射调用失败: " + e.getMessage());
        }
    }

    private java.lang.reflect.Method getMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            return clazz.getDeclaredMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
