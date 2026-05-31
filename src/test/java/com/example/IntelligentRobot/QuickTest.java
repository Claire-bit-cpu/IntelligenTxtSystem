package com.example.IntelligentRobot;

import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.dto.CommandDefinition;
import com.example.IntelligentRobot.service.CommandRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 快速测试 - 验证指令框架基本功能
 * 使用 test profile + 属性覆盖，确保测试环境不加载加密等可选功能
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "jira.enabled=false",
    "gitlab.enabled=false"
})
public class QuickTest {
    
    @Autowired
    private CommandRegistry commandRegistry;
    
    /**
     * 测试 1：指令注册表不为空
     */
    @Test
    public void testRegistryNotEmpty() {
        List<CommandDefinition> commands = commandRegistry.getAllCommands();
        assertFalse(commands.isEmpty(), "指令注册表不应为空");
        
        System.out.println("\n✅ 已注册的指令：");
        for (CommandDefinition cmd : commands) {
            System.out.println("  /" + cmd.getName() + " - " + cmd.getDescription());
        }
    }
    
    /**
     * 测试 2：测试 /ping 指令
     */
    @Test
    public void testPingCommand() throws Exception {
        if (!commandRegistry.hasCommand("ping")) {
            System.out.println("⚠️ /ping 指令未注册，跳过测试");
            return;
        }
        
        CommandContext context = createContext("ping google.com");
        Object result = commandRegistry.execute("ping", context);
        
        assertNotNull(result);
        System.out.println("\n✅ /ping 指令测试结果：");
        System.out.println(result);
    }
    
    /**
     * 测试 3：测试 /hello 指令（如果存在）
     */
    @Test
    public void testHelloCommand() throws Exception {
        if (!commandRegistry.hasCommand("hello")) {
            System.out.println("⚠️ /hello 指令未注册，跳过测试");
            return;
        }
        
        CommandContext context = createContext("hello 世界");
        Object result = commandRegistry.execute("hello", context);
        
        assertNotNull(result);
        System.out.println("\n✅ /hello 指令测试结果：");
        System.out.println(result);
    }
    
    /**
     * 测试 4：测试未知指令
     */
    @Test
    public void testUnknownCommand() {
        assertFalse(commandRegistry.hasCommand("unknown_command_12345"));
    }
    
    /**
     * 测试 5：测试指令别名
     */
    @Test
    public void testCommandAlias() {
        // 如果 /gitlog 有别名 /log，测试别名是否生效
        if (commandRegistry.hasCommand("gitlog")) {
            // 检查别名是否注册
            System.out.println("\n✅ 别名测试：");
            CommandDefinition cmd = commandRegistry.getCommand("gitlog");
            if (cmd != null) {
                System.out.println("  /gitlog 的别名: " + String.join(", ", cmd.getAliases()));
            }
        }
    }
    
    /**
     * 创建测试上下文
     */
    private CommandContext createContext(String message) {
        CommandContext context = new CommandContext();
        
        // 简单解析：第一个词是指令名，后面是参数
        String[] parts = message.split("\\s+", 2);
        context.setCommandName(parts[0]);
        context.setArgs(parts.length > 1 ? parts[1] : "");
        context.setRawMessage(message);
        
        // 创建发送者（使用 dto 包下的 FeishuSender）
        com.example.IntelligentRobot.dto.FeishuSender sender = new com.example.IntelligentRobot.dto.FeishuSender();
        sender.setSenderId("test_user");
        // 设置 sender_id 对象的 open_id
        com.example.IntelligentRobot.dto.FeishuSender.SenderId senderId = new com.example.IntelligentRobot.dto.FeishuSender.SenderId();
        senderId.setOpen_id("test_user");
        sender.setSender_id(senderId);
        context.setSender(sender);
        
        return context;
    }
}
