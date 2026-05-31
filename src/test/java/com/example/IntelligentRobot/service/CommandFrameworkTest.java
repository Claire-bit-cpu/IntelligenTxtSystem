package com.example.IntelligentRobot.service;

import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.dto.CommandDefinition;
import com.example.IntelligentRobot.dto.FeishuSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指令框架测试。
 * 使用 test profile，确保测试环境不加载加密等可选功能
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "jira.enabled=false",
    "gitlab.enabled=false"
})
public class CommandFrameworkTest {
    
    @Autowired
    private CommandRegistry commandRegistry;
    
    /**
     * 测试指令注册
     */
    @Test
    public void testCommandRegistration() {
        // 检查是否有指令被注册
        assertFalse(commandRegistry.getAllCommands().isEmpty(), 
            "应该有指令被注册");
        
        System.out.println("已注册的指令：");
        for (CommandDefinition cmd : commandRegistry.getAllCommandsSorted()) {
            System.out.println("  /" + cmd.getName() + " - " + cmd.getDescription());
        }
    }
    
    /**
     * 测试指令执行
     */
    @Test
    public void testCommandExecution() throws Exception {
        // 测试 /hello 指令（如果存在）
        if (commandRegistry.hasCommand("hello")) {
            CommandContext context = createTestContext("hello 世界");
            Object result = commandRegistry.execute("hello", context);
            
            assertNotNull(result);
            System.out.println("执行结果: " + result);
        }
    }
    
    /**
     * 测试未知指令
     */
    @Test
    public void testUnknownCommand() {
        assertFalse(commandRegistry.hasCommand("unknown_command_12345"));
    }
    
    /**
     * 测试指令别名
     */
    @Test
    public void testCommandAlias() {
        // 如果 /gitlog 有别名 /log，测试别名是否生效
        if (commandRegistry.hasCommand("gitlog")) {
            assertTrue(commandRegistry.hasCommand("log"), 
                "/gitlog 的别名 /log 应该生效");
        }
    }
    
    /**
     * 测试帮助文档生成
     */
    @Test
    public void testHelpGeneration() {
        String help = commandRegistry.generateHelp();
        
        assertNotNull(help);
        assertFalse(help.isEmpty());
        System.out.println("生成的帮助文档：\n" + help);
    }
    
    /**
     * 创建测试上下文
     */
    private CommandContext createTestContext(String message) {
        CommandContext context = new CommandContext();
        context.setCommandName("test");
        context.setArgs("");
        context.setRawMessage(message);
        
        FeishuSender sender = new FeishuSender();
        sender.setSenderId("test_user");
        // 设置 sender_id 对象的 open_id
        FeishuSender.SenderId senderId = new FeishuSender.SenderId();
        senderId.setOpen_id("test_user");
        senderId.setUser_id("test");
        sender.setSender_id(senderId);
        context.setSender(sender);
        
        return context;
    }
}
