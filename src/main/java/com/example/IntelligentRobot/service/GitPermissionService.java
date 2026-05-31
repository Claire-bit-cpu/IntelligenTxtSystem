/*
 * Git 操作权限服务
 * 通过飞书 API 查询用户角色，控制 Git 敏感操作权限
 */
package com.example.IntelligentRobot.service;

import com.example.IntelligentRobot.client.FeishuClient;
import com.example.IntelligentRobot.config.GitHubConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 权限规则（可扩展）：
 * - 只读操作（gitlog, gitdiff）：所有用户
 * - 写操作（createbranch, mergestatus）：需要 admin 或 developer 角色
 *
 * 角色查询方式：
 * 1. 配置文件白名单：从 GitHub 配置中读取管理员和开发者 OpenID
 * 2. 飞书通讯录 API：获取用户在企业中的角色（扩展功能）
 */
@Service
public class GitPermissionService {

    private static final Logger log = LoggerFactory.getLogger(GitPermissionService.class);

    @Autowired
    private GitHubConfig gitHubConfig;

    private final FeishuClient feishuClient;

    public GitPermissionService(FeishuClient feishuClient) {
        this.feishuClient = feishuClient;
    }

    /**
     * 检查用户是否有写权限（创建分支、查看 PR 等）
     * @param openId 飞书用户 Open ID
     * @return true 如果有写权限
     */
    public boolean hasWritePermission(String openId) {
        // 方式1：从配置文件读取白名单（简单快速）
        if (isInConfigWhitelist(openId)) {
            return true;
        }

        // 方式2：调用飞书通讯录 API 查询用户角色（需要额外权限）
        // 这里留作扩展，默认返回 false
        log.warn("用户 {} 不在白名单中，拒绝写操作", openId);
        return false;
    }

    /**
     * 检查配置白名单
     */
    private boolean isInConfigWhitelist(String openId) {
        String adminOpenIds = gitHubConfig.getAdminOpenIds();
        String developerOpenIds = gitHubConfig.getDeveloperOpenIds();

        if (adminOpenIds == null || developerOpenIds == null) {
            log.warn("管理员或开发者白名单未配置");
            return false;
        }

        String[] adminIds = adminOpenIds.split(",");
        String[] devIds = developerOpenIds.split(",");

        for (String id : adminIds) {
            if (id.trim().equals(openId)) return true;
        }
        for (String id : devIds) {
            if (id.trim().equals(openId)) return true;
        }
        return false;
    }

    /**
     * （扩展方法）调用飞书 API 查询用户角色
     * 需要权限：contact:user.base:read
     * API: GET /open-apis/contact/v3/users/{user_id}
     */
    public String getUserRoleFromFeishu(String openId) {
        try {
            String url = feishuClient.getApiBaseUrl() + "/contact/v3/users/" + openId;
            String response = feishuClient.getWithToken(url);
            log.info("飞书用户角色查询响应: {}", response);
            // 解析 response 获取角色信息
            // 这里需要根据实际返回结构解析
            return "unknown";
        } catch (Exception e) {
            log.error("查询用户角色失败: openId={}", openId, e);
            return "unknown";
        }
    }
}
