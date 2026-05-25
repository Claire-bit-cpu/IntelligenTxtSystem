package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.client.FeishuClient;
import com.example.intelligentxtsystem.dto.FeishuSender;
import org.springframework.stereotype.Component;

/**
 * 群组指令处理器
 * 指令格式：/group <群名> 或 建群 <群名>
 */
@Component
public class GroupHandler implements CommandHandler {

    private final FeishuClient feishuClient;

    public GroupHandler(FeishuClient feishuClient) {
        this.feishuClient = feishuClient;
    }

    @Override
    public boolean support(String text) {
        return text.startsWith("/group") || text.startsWith("建群") || text.startsWith("创建群");
    }

    @Override
    public String handle(String text, FeishuSender sender, String chatId) {
        String groupName = text
                .replaceAll("^(/group|建群|创建群)\\s*", "")
                .trim();

        if (groupName.isEmpty()) {
            return """
                    ❌ 用法：/group <群名>
                    
                    📋 示例：
                    /group 项目组
                    建群 前端开发群
                    
                    💡 群名建议简洁明了
                    """;
        }

        if (groupName.length() > 50) {
            return "⚠️ 群名长度不能超过50个字符";
        }

        String openId = sender != null ? sender.getOpenId() : null;
        String result = feishuClient.createGroup(groupName, openId);

        if ("success".equals(result)) {
            return String.format("""
                        ✅ 群组创建成功！
                        
                        📋 群名：%s
                        
                        💡 请在飞书中刷新查看新群组
                        """, groupName);
        } else {
            return String.format("""
                        ⚠️ 群组创建失败
                        
                        📋 群名：%s
                        📌 原因：%s
                        
                        请检查飞书应用权限后重试
                        """, groupName, result);
        }
    }
}
