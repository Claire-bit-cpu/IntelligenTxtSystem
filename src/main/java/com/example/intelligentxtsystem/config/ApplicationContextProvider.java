package com.example.intelligentxtsystem.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * ApplicationContext 提供者
 * 用于在非 Spring 管理的类中获取 Bean（如自定义 RejectedExecutionHandler）
 */
@Component
public class ApplicationContextProvider implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(ApplicationContextProvider.class);

    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        ApplicationContextProvider.applicationContext = applicationContext;
        log.info("ApplicationContext 已初始化");
    }

    /**
     * 获取 ApplicationContext
     */
    public static ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    /**
     * 根据类型获取 Bean
     */
    public static <T> T getBean(Class<T> clazz) {
        if (applicationContext == null) {
            log.warn("ApplicationContext 尚未初始化");
            return null;
        }
        try {
            return applicationContext.getBean(clazz);
        } catch (Exception e) {
            log.warn("获取 Bean 失败: clazz={}", clazz.getName(), e);
            return null;
        }
    }

    /**
     * 根据名称获取 Bean
     */
    public static Object getBean(String name) {
        if (applicationContext == null) {
            log.warn("ApplicationContext 尚未初始化");
            return null;
        }
        try {
            return applicationContext.getBean(name);
        } catch (Exception e) {
            log.warn("获取 Bean 失败: name={}", name, e);
            return null;
        }
    }
}
