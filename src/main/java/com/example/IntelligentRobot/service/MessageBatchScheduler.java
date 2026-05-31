package com.example.IntelligentRobot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 消息合并定时调度器
 * 
 * 功能：定时检查合并队列，推送到期未推送的合并消息
 * 
 * 调度策略：
 * - 每 60 秒执行一次（避免消息延迟过长）
 * - 检查所有合并队列，将到期（超过合并窗口）的消息推送出去
 */
@Component
public class MessageBatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(MessageBatchScheduler.class);

    @Autowired(required = false)
    private MessageBatchService messageBatchService;

    /**
     * 每 60 秒执行一次，推送到期未推送的合并消息
     */
    @Scheduled(fixedDelay = 60000)
    public void flushExpiredBatches() {
        if (messageBatchService == null) {
            return;
        }
        log.debug("开始检查合并消息队列...");
        try {
            messageBatchService.flushAllBatches();
        } catch (Exception e) {
            log.error("合并消息推送失败", e);
        }
    }
}
