package com.example.intelligentxtsystem.service;

import com.example.intelligentxtsystem.dto.CommandContext;
import com.example.intelligentxtsystem.dto.CommandDefinition;
import com.example.intelligentxtsystem.dto.FeishuSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CommandRegistry 鉴权逻辑集成测试
 * 
 * 测试覆盖：
 * 1. 需要鉴权的指令对未授权用户拒绝执行
 * 2. 需要鉴权的指令对授权用户正常执行
 * 3. 不需要鉴权的指令对所有用户开放
 * 4. 权限拒绝时抛出 SecurityException
 * 5. 帮助文档正确标注需要鉴权的指令
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "github.admin-open-ids=ou_admin1,ou_admin2",
    "github.developer-open-ids=ou_dev1,ou_dev2,ou_admin1"
})
public class CommandAuthTest {

    @Autowired
    private CommandRegistry commandRegistry;

    @Autowired
    private ContextManager contextManager;

    @Autowired
    private AuthService authService;

    /**
     * 测试 1：不需要鉴权的指令对所有用户开放
     */
    @Test
    void testPublicCommandNoAuthRequired() {
        // 注册一个公开指令
        CommandDefinition publicCmd = createTestCommand("publiccmd", false);
        commandRegistry.register(publicCmd);

        // 任何用户都可以执行
        CommandContext context = createContext("ou_anyone");
        Object result = commandRegistry.execute("publiccmd", context);
        assertEquals("executed", result);
    }

    /**
     * 测试 2：需要鉴权的指令对开发者用户正常执行
     */
    @Test
    void testAuthCommandWithDeveloperPermission() {
        // 注册一个需要鉴权的指令
        CommandDefinition authCmd = createTestCommand("authcmd", true);
        commandRegistry.register(authCmd);

        // 开发者可以执行
        CommandContext devContext = createContext("ou_dev1");
        Object result = commandRegistry.execute("authcmd", devContext);
        assertEquals("executed", result);
    }

    /**
     * 测试 3：需要鉴权的指令对管理员用户正常执行
     */
    @Test
    void testAuthCommandWithAdminPermission() {
        CommandDefinition authCmd = createTestCommand("authcmd", true);
        commandRegistry.register(authCmd);

        // 管理员可以执行
        CommandContext adminContext = createContext("ou_admin1");
        Object result = commandRegistry.execute("authcmd", adminContext);
        assertEquals("executed", result);
    }

    /**
     * 测试 4：需要鉴权的指令对未授权用户拒绝执行
     */
    @Test
    void testAuthCommandWithoutPermission() {
        CommandDefinition authCmd = createTestCommand("authcmd", true);
        commandRegistry.register(authCmd);

        // 未授权用户应该被拒绝
        CommandContext unauthorizedContext = createContext("ou_unknown");
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            commandRegistry.execute("authcmd", unauthorizedContext);
        });

        assertNotNull(exception.getMessage());
        assertTrue(exception.getMessage().contains("权限不足"));
    }

    /**
     * 测试 5：无法识别用户身份时拒绝执行
     */
    @Test
    void testAuthCommandWithUnknownUser() {
        CommandDefinition authCmd = createTestCommand("authcmd", true);
        commandRegistry.register(authCmd);

        // 用户 ID 为 null 或 default 时应该被拒绝
        CommandContext unknownContext = createContext(null);
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            commandRegistry.execute("authcmd", unknownContext);
        });

        assertNotNull(exception.getMessage());
    }

    /**
     * 测试 6：白名单未配置时拒绝所有鉴权请求（fail-secure）
     */
    @Test
    void testAuthCommandWithEmptyWhitelist() {
        // 创建一个白名单为空的 AuthService
        AuthService emptyAuthService = new AuthService();
        setField(emptyAuthService, "adminOpenIdsConfig", "");
        setField(emptyAuthService, "developerOpenIdsConfig", "");

        // 创建使用空白名单的 CommandRegistry
        CommandRegistry testRegistry = new CommandRegistry(contextManager, emptyAuthService);
        CommandDefinition authCmd = createTestCommand("authcmd", true);
        testRegistry.register(authCmd);

        // 即使用户 ID 在正常情况下有权限，白名单为空时也应该被拒绝
        CommandContext context = createContext("ou_admin1");
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            testRegistry.execute("authcmd", context);
        });

        assertNotNull(exception.getMessage());
    }

    /**
     * 测试 7：帮助文档正确标注需要鉴权的指令
     */
    @Test
    void testHelpDocumentShowsAuthRequired() {
        // 注册公开指令
        CommandDefinition publicCmd = createTestCommand("publiccmd", false);
        commandRegistry.register(publicCmd);

        // 注册需要鉴权的指令
        CommandDefinition authCmd = createTestCommand("authcmd", true);
        commandRegistry.register(authCmd);

        String help = commandRegistry.generateHelp();
        
        // 帮助文档应该包含鉴权标注
        assertTrue(help.contains("/publiccmd"));
        assertTrue(help.contains("/authcmd"));
        assertTrue(help.contains("需要鉴权"));
    }

    /**
     * 测试 8：权限检查不区分大小写（Open ID 精确匹配）
     */
    @Test
    void testOpenIdCaseSensitive() {
        CommandDefinition authCmd = createTestCommand("authcmd", true);
        commandRegistry.register(authCmd);

        // Open ID 大小写敏感，错误的大小写应该被拒绝
        CommandContext wrongCaseContext = createContext("OU_ADMIN1"); // 大写
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            commandRegistry.execute("authcmd", wrongCaseContext);
        });

        assertNotNull(exception.getMessage());
    }

    /**
     * 测试 9：指令别名继承鉴权设置
     */
    @Test
    void testAliasInheritsAuthSetting() {
        // 创建带别名的需要鉴权的指令（使用传统方式，因为 CommandDefinition 没有 builder）
        CommandDefinition authCmd = new CommandDefinition();
        authCmd.setName("maincmd");
        authCmd.setDescription("主指令");
        authCmd.setAliases(new String[]{"aliascmd"});
        authCmd.setRequiresAuth(true);
        // 设置一个测试执行方法（捕获受检异常）
        try {
            authCmd.setMethod(TestCommandHandler.class.getMethod("handle", CommandContext.class));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("测试方法不存在", e);
        }
        authCmd.setBean(new TestCommandHandler());

        commandRegistry.register(authCmd);

        // 通过别名执行时也应该检查鉴权
        CommandContext unauthorizedContext = createContext("ou_unknown");
        SecurityException exception = assertThrows(SecurityException.class, () -> {
            commandRegistry.execute("aliascmd", unauthorizedContext);
        });

        assertNotNull(exception.getMessage());

        // 授权用户可以通过别名执行
        CommandContext adminContext = createContext("ou_admin1");
        Object result = commandRegistry.execute("aliascmd", adminContext);
        assertEquals("executed", result);
    }

    // ========== 辅助方法 ==========

    /**
     * 创建测试指令定义
     */
    private CommandDefinition createTestCommand(String name, boolean requiresAuth) {
        CommandDefinition cmd = new CommandDefinition();
        cmd.setName(name);
        cmd.setDescription("测试指令");
        cmd.setRequiresAuth(requiresAuth);
        cmd.setAliases(new String[]{});
        
        // 设置一个测试执行方法（捕获受检异常，转换为运行时异常）
        try {
            cmd.setMethod(TestCommandHandler.class.getMethod("handle", CommandContext.class));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("测试方法不存在", e);
        }
        cmd.setBean(new TestCommandHandler());
        
        return cmd;
    }

    /**
     * 创建测试上下文
     */
    private CommandContext createContext(String openId) {
        CommandContext context = new CommandContext();
        context.setCommandName("test");
        context.setArgs("");

        FeishuSender sender = new FeishuSender();
        if (openId != null) {
            FeishuSender.SenderId senderId = new FeishuSender.SenderId();
            senderId.setOpen_id(openId);
            senderId.setUser_id(openId);
            sender.setSender_id(senderId);
        }
        context.setSender(sender);

        // 设置 userId（会从 sender 中提取）
        if (openId != null) {
            context.setUserId(openId);
        }

        return context;
    }

    /**
     * 通过反射设置私有字段
     */
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
     * 测试指令处理器（内部类）
     */
    public static class TestCommandHandler {
        public String handle(CommandContext context) {
            return "executed";
        }
    }
}
