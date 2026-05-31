package com.example.IntelligentRobot.dto;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户上下文对象
 * 存储单个用户的单个上下文类型的状态
 */
public class UserContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 上下文类型（如 "weather", "gitlog", "review"）
     */
    private String contextType;

    /**
     * 上下文参数（局部参数）
     */
    private Map<String, Object> params;

    /**
     * 创建时间戳
     */
    private long createTime;

    /**
     * 最后访问时间戳
     */
    private long lastAccessTime;

    /**
     * 超时时间（分钟）
     */
    private int timeoutMinutes;

    public UserContext() {
        this.createTime = System.currentTimeMillis();
        this.lastAccessTime = System.currentTimeMillis();
        this.timeoutMinutes = 5;
        this.params = new ConcurrentHashMap<>();
    }

    public UserContext(String contextType, Map<String, Object> params, int timeoutMinutes) {
        this();
        this.contextType = contextType;
        this.params = params != null ? params : new ConcurrentHashMap<>();
        this.timeoutMinutes = timeoutMinutes;
    }

    // ===== Getter / Setter =====

    public String getContextType() { return contextType; }
    public void setContextType(String contextType) { this.contextType = contextType; }

    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public long getLastAccessTime() { return lastAccessTime; }
    public void setLastAccessTime(long lastAccessTime) { this.lastAccessTime = lastAccessTime; }

    public int getTimeoutMinutes() { return timeoutMinutes; }
    public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }

    /**
     * 更新最后访问时间（续期）
     */
    public void updateAccessTime() {
        this.lastAccessTime = System.currentTimeMillis();
    }

    /**
     * 检查上下文是否过期
     */
    public boolean isExpired() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastAccessTime;
        long timeoutMillis = timeoutMinutes * 60 * 1000L;
        return elapsed > timeoutMillis;
    }

    /**
     * 获取剩余有效时间（秒）
     */
    public long getRemainingSeconds() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastAccessTime;
        long timeoutMillis = timeoutMinutes * 60 * 1000L;
        long remaining = timeoutMillis - elapsed;
        return Math.max(0, remaining / 1000);
    }

    /**
     * 设置参数
     */
    public void setParam(String key, Object value) {
        this.params.put(key, value);
        updateAccessTime();
    }

    /**
     * 获取参数
     */
    public Object getParam(String key) {
        return this.params.get(key);
    }

    /**
     * 检查是否有指定参数
     */
    public boolean hasParam(String key) {
        return this.params.containsKey(key) && this.params.get(key) != null;
    }
}
