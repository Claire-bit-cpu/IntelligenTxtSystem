package com.example.IntelligentRobot.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * JIRA 客户端本地降级模式测试
 */
public class JiraClientLocalModeTest {

    private JiraClient jiraClient;
    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        objectMapper = new ObjectMapper();
        
        // 创建 JiraClient 实例（手动注入依赖）
        jiraClient = new JiraClient(restTemplate, objectMapper);
        
        // 使用反射设置私有字段（模拟 @Value 注入）
        setField(jiraClient, "jiraUrl", "https://your-domain.atlassian.net");
        setField(jiraClient, "username", "");  // 空用户名 = 本地模式
        setField(jiraClient, "apiToken", "");   // 空 Token = 本地模式
        setField(jiraClient, "enabled", false); // 禁用 = 本地模式
        setField(jiraClient, "localFallbackFile", tempDir.resolve("local_tasks.md").toString());
    }

    /**
     * 测试1：检测本地模式
     */
    @Test
    void testLocalModeDetection() {
        // 验证处于本地模式
        assertTrue(jiraClient.isLocalMode(), "应该处于本地模式");
        assertFalse(jiraClient.isConfigured(), "应该未配置");
    }

    /**
     * 测试2：创建本地任务
     */
    @Test
    void testCreateIssueLocally() {
        // 执行创建任务
        String result = jiraClient.createBug("PROJ", "测试任务", "这是一个测试");

        // 验证返回信息
        assertNotNull(result, "返回结果不应为空");
        assertTrue(result.contains("✅ 任务已记录到本地"), "应该提示已记录到本地");
        assertTrue(result.contains("PROJ-1"), "应该生成任务编号 PROJ-1");
        assertTrue(result.contains("测试任务"), "应该包含任务标题");
        assertTrue(result.contains("离线模式"), "应该提示离线模式");

        // 验证文件已创建
        Path taskFile = tempDir.resolve("local_tasks.md");
        assertTrue(Files.exists(taskFile), "任务文件应该已创建");

        // 验证文件内容
        try {
            String content = Files.readString(taskFile);
            assertTrue(content.contains("## PROJ-1"), "文件应该包含任务编号");
            assertTrue(content.contains("测试任务"), "文件应该包含任务标题");
            assertTrue(content.contains("待处理"), "文件应该包含默认状态");
        } catch (Exception e) {
            fail("读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 测试3：创建多个任务（编号递增）
     */
    @Test
    void testCreateMultipleIssues() {
        // 创建第1个任务
        String result1 = jiraClient.createBug("PROJ", "任务1", "描述1");
        assertTrue(result1.contains("PROJ-1"), "第1个任务编号应该是 PROJ-1");

        // 创建第2个任务
        String result2 = jiraClient.createBug("PROJ", "任务2", "描述2");
        assertTrue(result2.contains("PROJ-2"), "第2个任务编号应该是 PROJ-2");

        // 创建第3个任务
        String result3 = jiraClient.createBug("PROJ", "任务3", "描述3");
        assertTrue(result3.contains("PROJ-3"), "第3个任务编号应该是 PROJ-3");

        // 验证文件内容
        try {
            String content = Files.readString(tempDir.resolve("local_tasks.md"));
            assertTrue(content.contains("## PROJ-1"), "文件应该包含 PROJ-1");
            assertTrue(content.contains("## PROJ-2"), "文件应该包含 PROJ-2");
            assertTrue(content.contains("## PROJ-3"), "文件应该包含 PROJ-3");
        } catch (Exception e) {
            fail("读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 测试4：查询本地任务
     */
    @Test
    void testGetIssueFromLocal() {
        // 先创建任务
        jiraClient.createBug("PROJ", "测试任务", "描述");

        // 查询任务
        String result = jiraClient.getIssue("PROJ-1");

        // 验证返回信息
        assertNotNull(result, "返回结果不应为空");
        assertTrue(result.contains("📋 本地任务详情"), "应该提示本地任务详情");
        assertTrue(result.contains("PROJ-1"), "应该包含任务编号");
        assertTrue(result.contains("离线模式"), "应该提示离线模式");
    }

    /**
     * 测试5：查询不存在的任务
     */
    @Test
    void testGetNonExistentIssue() {
        // 查询不存在的任务
        String result = jiraClient.getIssue("PROJ-999");

        // 验证返回信息
        assertNotNull(result, "返回结果不应为空");
        assertTrue(result.contains("❌ 未找到任务"), "应该提示未找到任务");
    }

    /**
     * 测试6：搜索本地任务
     */
    @Test
    void testSearchIssuesLocally() {
        // 创建多个任务
        jiraClient.createBug("PROJ", "任务1", "描述1");
        jiraClient.createBug("PROJ", "任务2", "描述2");
        jiraClient.createBug("PROJ", "任务3", "描述3");

        // 搜索任务
        String result = jiraClient.searchIssues("project=PROJ", 10);

        // 验证返回信息
        assertNotNull(result, "返回结果不应为空");
        assertTrue(result.contains("📋 本地任务列表"), "应该提示本地任务列表");
        assertTrue(result.contains("PROJ-1"), "应该包含 PROJ-1");
        assertTrue(result.contains("PROJ-2"), "应该包含 PROJ-2");
        assertTrue(result.contains("PROJ-3"), "应该包含 PROJ-3");
    }

    /**
     * 辅助方法：使用反射设置私有字段
     */
    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            fail("设置字段失败: " + fieldName + ", error: " + e.getMessage());
        }
    }
}
