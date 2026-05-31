package com.example.IntelligentRobot.annotation;

import java.lang.annotation.*;

/**
 * 指令注解
 * 用于标记指令处理函数，实现自动注册
 *
 * 使用示例：
 * {@code
 * @Command(
 *     name = "gitlog",
 *     description = "查看 Git 提交日志",
 *     requiresAuth = true,
 *     supportsContext = true,
 *     contextType = "gitlog",
 *     localParams = {"branch", "file"},
 *     contextTimeout = 5
 * )
 * public String handleGitLog(CommandContext context) {
 *     return "提交日志...";
 * }
 * }
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Command {

    /**
     * 指令名称（不含 / 前缀）
     */
    String name();

    /**
     * 指令描述
     */
    String description() default "";

    /**
     * 是否需要鉴权（已废弃，请使用 permissionLevel）
     * @deprecated 使用 permissionLevel 代替
     */
    boolean requiresAuth() default false;

    /**
     * 权限级别（字符串形式，避免 Lombok/编译问题）
     * "NONE"      - 无需鉴权（公开指令）
     * "DEVELOPER" - 需要开发者权限（读写操作）
     * "ADMIN"     - 需要管理员权限（敏感操作）
     */
    String permissionLevel() default "NONE";

    /**
     * 指令别名（支持多个）
     */
    String[] aliases() default {};

    /**
     * 使用帮助信息
     */
    String usage() default "";

    /**
     * 是否支持上下文感知（多轮对话）
     * 默认 false：每次都是独立指令
     * true：支持复用上一轮的参数
     */
    boolean supportsContext() default false;

    /**
     * 上下文类型标识
     * 用于区分不同的上下文场景（如 "weather", "gitlog", "review"）
     * 为空时默认使用指令名称
     */
    String contextType() default "";

    /**
     * 全局参数列表（跨指令复用）
     * 例如：{"-g", "--global"}
     * 全局参数在整个会话期间都有效（直到用户明确清除）
     */
    String[] globalParams() default {};

    /**
     * 局部参数列表（仅本轮有效）
     * 例如：{"city", "branch", "repo"}
     * 局部参数只在同类型上下文中有效
     */
    String[] localParams() default {};

    /**
     * 上下文超时时间（分钟）
     * 超过该时间没有继续使用该上下文，自动清空
     * 默认 5 分钟
     */
    int contextTimeout() default 5;

    /**
     * 是否强独立性指令
     * true：每次都是全新执行，不继承任何上下文
     * 适用于 /jira 1234 这类指令
     */
    boolean independent() default false;
}
