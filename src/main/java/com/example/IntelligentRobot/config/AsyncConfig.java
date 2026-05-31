package com.example.IntelligentRobot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import org.springframework.core.task.TaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 * 用于飞书事件的高并发处理，防止慢操作导致webhook超时
 * 
 * 设计思路：
 * 1. 高优先级线程池：处理消息、审批等需要快速响应的事件
 * 2. 低优先级线程池：处理日志、统计等不需要实时响应的事件
 * 3. 自定义拒绝策略：记录拒绝次数并触发告警
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(WelcomeConfig.class)
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Value("${thread-pool.high-priority.core-size:10}")
    private int highPriorityCoreSize;

    @Value("${thread-pool.high-priority.max-size:50}")
    private int highPriorityMaxSize;

    @Value("${thread-pool.high-priority.queue-capacity:1000}")
    private int highPriorityQueueCapacity;

    @Value("${thread-pool.low-priority.core-size:5}")
    private int lowPriorityCoreSize;

    @Value("${thread-pool.low-priority.max-size:20}")
    private int lowPriorityMaxSize;

    @Value("${thread-pool.low-priority.queue-capacity:2000}")
    private int lowPriorityQueueCapacity;

    /**
     * 高优先级事件线程池
     * 处理：消息接收、审批回调、卡片按钮点击等需要快速响应的事件
     */
    @Bean("highPriorityEventExecutor")
    public TaskExecutor highPriorityEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：根据CPU核心数动态调整，保证快速响应
        int corePoolSize = Runtime.getRuntime().availableProcessors() * 2;
        executor.setCorePoolSize(Math.max(corePoolSize, highPriorityCoreSize));
        executor.setMaxPoolSize(highPriorityMaxSize);
        executor.setQueueCapacity(highPriorityQueueCapacity);
        executor.setThreadNamePrefix("high-priority-event-");
        // 使用自定义拒绝策略：记录拒绝次数并触发告警
        executor.setRejectedExecutionHandler(new HighPriorityRejectedPolicy());
        // 允许核心线程超时回收，节省资源
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        executor.initialize();
        
        log.info("高优先级事件线程池初始化完成: core={}, max={}, queue={}", 
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    /**
     * 低优先级事件线程池
     * 处理：日志上报、数据统计、搜索索引同步等不需要实时响应的事件
     */
    @Bean("lowPriorityEventExecutor")
    public TaskExecutor lowPriorityEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(lowPriorityCoreSize);
        executor.setMaxPoolSize(lowPriorityMaxSize);
        executor.setQueueCapacity(lowPriorityQueueCapacity);
        executor.setThreadNamePrefix("low-priority-event-");
        // 低优先级使用自定义丢弃策略，记录拒绝次数
        executor.setRejectedExecutionHandler(new LowPriorityRejectedPolicy());
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);
        executor.initialize();
        
        log.info("低优先级事件线程池初始化完成: core={}, max={}, queue={}", 
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    /**
     * 消息处理线程池（保留原有Bean名称兼容现有代码）
     * 实际委托给高优先级线程池
     */
    @Bean("messageExecutor")
    public TaskExecutor messageExecutor() {
        return highPriorityEventExecutor();
    }

    /**
     * 高优先级拒绝策略
     * 记录拒绝次数，触发告警，并执行 CallerRunsPolicy
     */
    public static class HighPriorityRejectedPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("高优先级任务被拒绝: {}", r.toString());
            
            // 执行 CallerRunsPolicy：让提交任务的线程自己执行
            new ThreadPoolExecutor.CallerRunsPolicy().rejectedExecution(r, executor);
        }
    }

    /**
     * 低优先级拒绝策略
     * 使用 DiscardPolicy（静默丢弃）
     */
    public static class LowPriorityRejectedPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            // 低优先级任务丢弃，不触发告警（避免告警风暴）
            log.warn("低优先级任务被丢弃: {}", r.toString());
        }
    }
}
