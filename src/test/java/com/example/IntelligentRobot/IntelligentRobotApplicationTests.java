package com.example.IntelligentRobot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 应用上下文加载测试
 * 使用 test profile，禁用加密等可选功能
 */
@SpringBootTest
@ActiveProfiles("test")
class IntelligentRobotApplicationTests {

    @Test
    void contextLoads() {
    }

}
