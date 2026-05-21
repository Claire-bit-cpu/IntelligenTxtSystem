package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.dto.FeishuSender;
import org.springframework.stereotype.Component;

/**
 * 帮助指令处理器
 * 指令格式：/help 或 help
 */
@Component
public class HelpHandler implements CommandHandler {

    private static final String HELP_TEXT = """
            🤖 超级助手机器人 - 指令列表

            📌 基础指令
            ─────────────────────────
            /weather <城市>         查询天气
            /translate <文本>       翻译（中英互译）
            /schedule <时间> <事件>  创建日程（可选结束时间）

            📌 企业指令
            ─────────────────────────
            /group <群名>      创建群组
            /search <问题>     知识搜索/问答

            📌 GitHub 指令
            ─────────────────────────
            /repo <owner/repo>     查看仓库信息
            /pr <owner/repo> <号>  查看PR信息
            /cr <owner/repo> <号>  代码审查

            📌 DevOps 工具
            ─────────────────────────
            /uptime          查看运行时间
            /ping <主机>     检测连通性
            /deploy <环境>   触发部署

            💡 使用示例
            /weather 北京
            /translate Hello
            /schedule 2024-01-15 15:00 团队会议
            /schedule 2024-01-15 15:00 16:00 项目评审
            /group 项目组
            /search 如何创建GitHub仓库
            /repo facebook/react
            /cr microsoft/vscode 12345
            /uptime
            /deploy prod

            📞 如需帮助，请联系管理员
            """;

    @Override
    public boolean support(String text) {
        String trimmed = text.trim().toLowerCase();
        return trimmed.equals("help") || trimmed.equals("/help");
    }

    @Override
    public String handle(String text, FeishuSender sender) {
        return HELP_TEXT;
    }
}
