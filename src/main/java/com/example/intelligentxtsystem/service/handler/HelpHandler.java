package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.client.FeishuClient;
import com.example.intelligentxtsystem.dto.FeishuSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 帮助指令处理器（交互式卡片菜单）
 * 指令格式：/help 或 help
 *
 * 功能：
 * 1. 发送交互式卡片菜单，展示功能分类
 * 2. 用户点击分类按钮后，通过卡片回调更新菜单内容
 * 3. 支持返回主菜单，实现动态交互
 *
 * 注意：卡片 JSON 通过 Map 动态构建，避免手写 JSON 字符串的转义问题
 */
@Component
public class HelpHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(HelpHandler.class);

    private final FeishuClient feishuClient;

    public HelpHandler(FeishuClient feishuClient) {
        this.feishuClient = feishuClient;
    }

    @Override
    public boolean support(String text) {
        String trimmed = text.trim().toLowerCase();
        return trimmed.equals("help") || trimmed.equals("/help");
    }

    @Override
    public String handle(String text, FeishuSender sender, String chatId) {
        // 发送主菜单卡片
        Map<String, Object> card = buildMainMenuCardMap();
        String cardJson = FeishuClient.mapToJson(card);
        feishuClient.sendCard(chatId, cardJson);
        return null; // 不发送文本消息
    }

    /**
     * 根据分类构建二级菜单卡片（供 WebhookController 回调时使用）
     * 返回 Map 对象，由调用方序列化为 JSON
     */
    public Map<String, Object> buildSubMenuCardMap(String action) {
        return switch (action) {
            case "help_search"      -> buildSearchHelpMap();
            case "help_group_file"  -> buildGroupFileHelpMap();
            case "help_ai"          -> buildAiHelpMap();
            case "help_weather"     -> buildWeatherHelpMap();
            case "help_translate"   -> buildTranslateHelpMap();
            case "help_schedule"    -> buildScheduleHelpMap();
            case "help_group"       -> buildGroupHelpMap();
            case "help_github"      -> buildGithubHelpMap();
            case "help_devops"      -> buildDevopsHelpMap();
            case "help_tips"        -> buildTipsHelpMap();
            case "main_menu"        -> buildMainMenuCardMap();
            default                 -> buildMainMenuCardMap();
        };
    }

    // ==================== 主菜单 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildMainMenuCardMap() {
        Map<String, Object> card = new LinkedHashMap<>();
        Map<String, Object> config = Map.of("wide_screen_mode", true);
        card.put("config", config);

        // header
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", Map.of("tag", "plain_text", "content", "\uD83E\uDD16 超级助手机器人 - 帮助菜单"));
        header.put("subtitle", Map.of("tag", "plain_text", "content", "请选择您想了解的功能分类"));
        card.put("header", header);

        // elements
        List<Object> elements = new ArrayList<>();

        // 说明文字
        elements.add(Map.of(
                "tag", "div",
                "text", Map.of("tag", "lark_md", "content",
                        "\uD83D\uDC4B 你好！我是智能助手，可以帮你完成多种任务。\n请点击下方按钮了解具体功能：")
        ));
        elements.add(Map.of("tag", "hr"));

        // 按钮行：知识库搜索 + 群文件搜索
        elements.add(buildButtonRow(
                Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", "\uD83D\uDCDA 知识库搜索"), "value", "{\"action\": \"help_search\"}", "type", "primary"),
                Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", "\uD83D\uDCC2 群文件搜索"), "value", "{\"action\": \"help_group_file\"}", "type", "primary")
        ));

        // 按钮行：AI 问答 + 天气查询
        elements.add(buildButtonRow(
                Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", "\uD83E\uDD16 AI 问答"), "value", "{\"action\": \"help_ai\"}", "type", "primary"),
                Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", "\uD83C\uDF24\uFE0F 天气查询"), "value", "{\"action\": \"help_weather\"}", "type", "default")
        ));

        // 按钮行：翻译 + 日程管理
        elements.add(buildButtonRow(
                Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", "\uD83C\uDF10 翻译"), "value", "{\"action\": \"help_translate\"}", "type", "default"),
                Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", "\uD83D\uDCC5 日程管理"), "value", "{\"action\": \"help_schedule\"}", "type", "default")
        ));

        // 按钮行：群组管理 + GitHub
        elements.add(buildButtonRow(
                Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", "\uD83D\uDC65 群组管理"), "value", "{\"action\": \"help_group\"}", "type", "default"),
                Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", "\uD83D\uDC19 GitHub"), "value", "{\"action\": \"help_github\"}", "type", "default")
        ));

        // 按钮行：运维工具 + 使用技巧
        elements.add(buildButtonRow(
                Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", "\uD83D\uDD27 运维工具"), "value", "{\"action\": \"help_devops\"}", "type", "default"),
                Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", "\u2753 使用技巧"), "value", "{\"action\": \"help_tips\"}", "type", "warning")
        ));

        elements.add(Map.of("tag", "hr"));

        // 底部提示
        elements.add(Map.of(
                "tag", "note",
                "elements", List.of(Map.of("tag", "lark_md", "content", "\uD83D\uDCA1 点击上方按钮了解各功能的具体用法"))
        ));

        card.put("elements", elements);
        return card;
    }

    // ==================== 二级菜单 - 知识库搜索 ====================

    private Map<String, Object> buildSearchHelpMap() {
        return buildHelpCard(
                "\uD83D\uDCDA 知识库搜索 - 使用帮助",
                "\u641C\u7D22\u98DE\u4E66\u77E5\u8BC6\u5E93\u6587\u6863",
                "**功能说明**\n搜索机器人有权访问的所有飞书知识库文档。\n\n**指令格式**\n- `/search <关键词>`\n- `搜索 <关键词>`\n- `查询 <关键词>`\n\n**使用示例**\n- `/search 项目规范`\n- `搜索 需求文档`\n- `查询 API接口`\n\n**管理命令**（仅管理员）\n- `/search sync` - 同步索引\n- `/search status` - 查看索引状态\n- `/search refresh_admins` - 刷新管理员列表",
                "help_search"
        );
    }

    // ==================== 二级菜单 - 群文件搜索 ====================

    private Map<String, Object> buildGroupFileHelpMap() {
        return buildHelpCard(
                "\uD83D\uDCC2 群文件搜索 - 使用帮助",
                "\u641C\u7D22\u5F53\u524D\u7FA4\u5185\u7684\u6587\u4EF6\u548C\u6587\u6863",
                "**功能说明**\n实时搜索当前群聊中分享的文件和文档消息。\n\n**支持的文件类型**\n- \uD83D\uDCC4 文档 (doc/docx)\n- \uD83D\uDCCA 表格 (sheet)\n- \uD83D\uDCCB 多维表格 (bitable)\n- \uD83D\uDCD1 Wiki 文档\n- \uD83D\uDCC1 普通文件 (file)\n\n**使用方法**\n使用 `/search <关键词>` 即可同时搜索群文件和知识库。\n\n**注意事项**\n需要管理员在飞书开放平台添加 `im:message.group_msg:readonly` 权限。",
                "help_group_file"
        );
    }

    // ==================== 二级菜单 - AI 问答 ====================

    private Map<String, Object> buildAiHelpMap() {
        return buildHelpCard(
                "\uD83E\uDD16 AI 问答 - 使用帮助",
                "\u8C03\u7528\u901A\u4E49\u5343\u95EE AI \u8FDB\u884C\u667A\u80FD\u95EE\u7B54",
                "**功能说明**\n调用通义千问 AI 模型，回答你的问题。\n\n**指令格式**\n- `/AI <问题>`\n\n**使用示例**\n- `/AI 如何创建GitHub仓库`\n- `/AI 解释一下RESTful API`\n- `/AI 帮我写一段Python代码`\n\n**注意事项**\n问题长度不能超过200个字符。",
                "help_ai"
        );
    }

    // ==================== 二级菜单 - 天气查询 ====================

    private Map<String, Object> buildWeatherHelpMap() {
        return buildHelpCard(
                "\uD83C\uDF24\uFE0F 天气查询 - 使用帮助",
                "\u67E5\u8BE2\u6307\u5B9A\u57CE\u5E02\u7684\u5929\u6C14\u4FE1\u606F",
                "**功能说明**\n查询指定城市的当前天气和未来天气预报。\n\n**指令格式**\n- `/weather <城市名>`\n\n**使用示例**\n- `/weather 北京`\n- `/weather 上海`\n- `/weather 深圳`\n\n**数据来源**\n高德天气 API",
                "help_weather"
        );
    }

    // ==================== 二级菜单 - 翻译 ====================

    private Map<String, Object> buildTranslateHelpMap() {
        return buildHelpCard(
                "\uD83C\uDF10 翻译 - 使用帮助",
                "\u4E2D\u82F1\u6587\u4E92\u8BD1",
                "**功能说明**\n自动检测语言并进行中英文互译。\n\n**指令格式**\n- `/translate <文本>`\n\n**使用示例**\n- `/translate Hello World`\n- `/translate 你好世界`\n\n**支持语言**\n- 中文 → 英文\n- 英文 → 中文",
                "help_translate"
        );
    }

    // ==================== 二级菜单 - 日程管理 ====================

    private Map<String, Object> buildScheduleHelpMap() {
        return buildHelpCard(
                "\uD83D\uDCC5 日程管理 - 使用帮助",
                "\u521B\u5EFA\u65E5\u5386\u65E5\u7A0B",
                "**功能说明**\n在机器人的日历中创建日程，并可邀请参与者。\n\n**指令格式**\n- `/schedule <时间> <事件>`\n- `/schedule <开始时间> <结束时间> <事件>`\n\n**时间格式**\n- `yyyy-MM-dd HH:mm`\n\n**使用示例**\n- `/schedule 2024-01-15 15:00 团队会议`\n- `/schedule 2024-01-15 15:00 16:00 项目评审`\n\n**注意事项**\n时间必须是未来时间，且格式正确。",
                "help_schedule"
        );
    }

    // ==================== 二级菜单 - 群组管理 ====================

    private Map<String, Object> buildGroupHelpMap() {
        return buildHelpCard(
                "\uD83D\uDC65 群组管理 - 使用帮助",
                "\u521B\u5EFA\u65B0\u7684\u98DE\u4E66\u7FA4\u7EC4",
                "**功能说明**\n创建一个新的飞书群组，并将你添加为群成员。\n\n**指令格式**\n- `/group <群名>`\n\n**使用示例**\n- `/group 项目组`\n- `/group 产品开发团队`\n\n**注意事项**\n- 群名不能为空\n- 创建后需要手动邀请其他成员",
                "help_group"
        );
    }

    // ==================== 二级菜单 - GitHub ====================

    private Map<String, Object> buildGithubHelpMap() {
        return buildHelpCard(
                "\uD83D\uDC19 GitHub 工具 - 使用帮助",
                "\u67E5\u8BE2 GitHub \u4ED3\u5E93\u548C PR \u4FE1\u606F",
                "**功能说明**\n查询 GitHub 仓库信息、PR 详情，并进行代码审查。\n\n**指令格式**\n- `/repo <owner/repo>` - 查看仓库信息\n- `/pr <owner/repo> <号>` - 查看 PR 信息\n- `/cr <owner/repo> <号>` - 代码审查\n\n**使用示例**\n- `/repo facebook/react`\n- `/pr microsoft/vscode 12345`\n- `/cr torvalds/linux 1`",
                "help_github"
        );
    }

    // ==================== 二级菜单 - 运维工具 ====================

    private Map<String, Object> buildDevopsHelpMap() {
        return buildHelpCard(
                "\uD83D\uDD27 运维工具 - 使用帮助",
                "\u5E38\u7528\u7684 DevOps \u5DE5\u5177\u96C6",
                "**功能说明**\n提供常用的运维工具，帮助排查问题。\n\n**指令格式**\n- `/uptime` - 查看机器人运行时间\n- `/ping <主机>` - 检测主机连通性\n- `/deploy <环境>` - 触发部署（需配置）\n\n**使用示例**\n- `/uptime`\n- `/ping 8.8.8.8`\n- `/ping www.baidu.com`\n- `/deploy prod`",
                "help_devops"
        );
    }

    // ==================== 二级菜单 - 使用技巧 ====================

    private Map<String, Object> buildTipsHelpMap() {
        return buildHelpCard(
                "\u2753 使用技巧 - 常见问题",
                "\u8BA9\u4F60\u66F4\u9AD8\u6548\u5730\u4F7F\u7528\u673A\u5668\u4EBA",
                "**\uD83D\uDCA1 使用技巧**\n\n1. **@提及使用命令**\n   在群聊中使用命令时，需要 @机器人 后再输入命令。\n\n2. **搜索技巧**\n   - 关键词尽量简短精准\n   - 可以搜索群文件和知识库\n   - 管理员可以手动同步索引\n\n3. **权限说明**\n   - 部分命令仅群管理员可用\n   - 搜索功能需要相应权限\n\n4. **反馈问题**\n   如遇问题，请联系管理员或开发者。",
                "help_tips"
        );
    }

    // ==================== 通用构建方法 ====================

    /**
     * 构建一个标准的二级帮助卡片
     *
     * @param title       卡片标题
     * @param subtitle    卡片副标题
     * @param mdContent   lark_md 内容（支持 \n 换行）
     * @param currentAction 当前分类的 action 标识（用于日志）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildHelpCard(String title, String subtitle, String mdContent, String currentAction) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true));

        // header
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", Map.of("tag", "plain_text", "content", title));
        header.put("subtitle", Map.of("tag", "plain_text", "content", subtitle));
        card.put("header", header);

        // elements
        List<Object> elements = new ArrayList<>();

        // 帮助内容
        elements.add(Map.of(
                "tag", "div",
                "text", Map.of("tag", "lark_md", "content", mdContent)
        ));

        elements.add(Map.of("tag", "hr"));

        // 返回主菜单按钮
        elements.add(Map.of(
                "tag", "action",
                "actions", List.of(
                        Map.of("tag", "button",
                                "text", Map.of("tag", "plain_text", "content", "\uD83D\uDD19 返回主菜单"),
                                "value", "{\"action\": \"main_menu\"}",
                                "type", "default")
                )
        ));

        card.put("elements", elements);
        return card;
    }

    /**
     * 构建一行两个按钮的 action 元素
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildButtonRow(Map<String, Object>... buttons) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tag", "action");
        row.put("layout", "bisected");
        row.put("actions", List.of(buttons));
        return row;
    }
}
