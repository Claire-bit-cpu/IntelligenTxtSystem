package com.example.intelligentxtsystem.service.handler;

import com.example.intelligentxtsystem.client.GitHubClient;
import com.example.intelligentxtsystem.config.GitHubConfig;
import com.example.intelligentxtsystem.dto.FeishuSender;
import com.example.intelligentxtsystem.service.GitPermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 创建 Git 分支（需要写权限）
 * 指令格式：/createbranch <仓库别名> <新分支名> [源分支]
 * 示例：/createbranch frontend feature/new-ui master
 */
@Component
public class CreateBranchHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CreateBranchHandler.class);

    private final GitHubClient gitHubClient;
    private final GitHubConfig gitHubConfig;
    private final GitPermissionService permissionService;

    public CreateBranchHandler(GitHubClient gitHubClient, GitHubConfig gitHubConfig, GitPermissionService permissionService) {
        this.gitHubClient = gitHubClient;
        this.gitHubConfig = gitHubConfig;
        this.permissionService = permissionService;
    }

    @Override
    public boolean support(String text) {
        return text.trim().toLowerCase().startsWith("/createbranch");
    }

    @Override
    public String handle(String text, FeishuSender sender, String chatId) {
        // 权限检查
        String openId = sender.getOpenId();
        if (openId == null || !permissionService.hasWritePermission(openId)) {
            return "❌ 权限不足！创建分支需要管理员或开发者权限。\n请联系管理员将你的飞书账号加入白名单。";
        }

        String[] parts = text.trim().split("\\s+");
        if (parts.length < 3) {
            return "❌ 用法：/createbranch <仓库别名> <新分支名> [源分支]\n示例：/createbranch frontend feature/new-ui master";
        }

        String repoAlias = parts[1];
        String branchName = parts[2];
        String ref = (parts.length >= 4) ? parts[3] : "master";

        String repoFullName = gitHubConfig.getRepoAliases().get(repoAlias);
        if (repoFullName == null) {
            return "❌ 未知的仓库别名：「" + repoAlias + "」\n可用别名：" + gitHubConfig.getRepoAliases().keySet();
        }

        // 解析 owner/repo
        String[] repoParts = repoFullName.split("/");
        if (repoParts.length != 2) {
            return "❌ 仓库配置格式错误，应为 owner/repo";
        }
        String owner = repoParts[0];
        String repo = repoParts[1];

        try {
            // 获取源分支的最新 commit SHA
            String sourceSha = gitHubClient.getBranchSha(owner, repo, ref);
            if (sourceSha == null) {
                return "❌ 获取源分支「" + ref + "」的 SHA 失败，请检查分支是否存在";
            }

            // 创建新分支
            Map<String, Object> result = gitHubClient.createBranch(owner, repo, branchName, sourceSha);

            if (result == null) {
                return "❌ 创建分支失败，请检查参数或权限";
            }

            return "✅ 分支创建成功！\n📌 仓库：" + repoAlias + "\n🌿 新分支：`" + branchName + "`\n📎 基于：" + ref + " (" + sourceSha.substring(0, 8) + ")";

        } catch (Exception e) {
            log.error("创建分支失败", e);
            return "❌ 创建失败：" + e.getMessage();
        }
    }
}
