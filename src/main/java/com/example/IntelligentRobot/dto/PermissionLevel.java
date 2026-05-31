package com.example.IntelligentRobot.dto;

/**
 * 权限级别枚举
 * - NONE: 不需要鉴权（公开指令）
 * - DEVELOPER: 需要开发者权限（读写操作）
 * - ADMIN: 需要管理员权限（敏感操作）
 */
public enum PermissionLevel {
    NONE,      // 无需鉴权
    DEVELOPER, // 开发者及以上
    ADMIN      // 仅管理员
}
