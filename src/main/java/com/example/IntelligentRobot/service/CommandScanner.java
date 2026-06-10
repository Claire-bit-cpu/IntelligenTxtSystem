package com.example.IntelligentRobot.service;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.dto.CommandDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 指令扫描器
 * 启动时自动扫描并注册带有 @Command 注解的方法
 * 
 * 使用 ApplicationRunner 替代 ContextRefreshedEvent，
 * 确保在 Spring 上下文完全初始化后再扫描，避免循环依赖和事件未触发的问题
 */
@Component
public class CommandScanner implements ApplicationRunner {
    
    private static final Logger log = LoggerFactory.getLogger(CommandScanner.class);
    
    private final ApplicationContext applicationContext;
    private final CommandRegistry commandRegistry;
    
    /**
     * 注入 ApplicationContext 和 CommandRegistry
     * 使用构造函数注入，Spring 会自动处理依赖顺序
     */
    public CommandScanner(ApplicationContext applicationContext, CommandRegistry commandRegistry) {
        this.applicationContext = applicationContext;
        this.commandRegistry = commandRegistry;
    }
    
    /**
     * 在 Spring 上下文完全初始化后执行扫描（ApplicationRunner 在 bean 初始化后执行）
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("开始扫描指令...");
        
        int count = 0;
        
        // 扫描所有 Spring Bean，查找带有 @Command 注解的方法
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
            
            count += registerCommandsInClass(targetClass, bean);
        }
        
        log.info("指令扫描完成，共注册 {} 个指令", count);
    }
    
    /**
     * 注册类中的所有 @Command 方法
     */
    private int registerCommandsInClass(Class<?> clazz, Object bean) {
        int count = 0;
        
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Command.class)) {
                registerCommand(method, bean);
                count++;
            }
        }
        
        return count;
    }
    
    /**
     * 注册单个指令
     */
    private void registerCommand(Method method, Object bean) {
        Command annotation = method.getAnnotation(Command.class);

        // 验证方法签名
        validateMethodSignature(method);

        // 创建指令定义
        CommandDefinition definition = new CommandDefinition();
        definition.setName(annotation.name());
        definition.setDescription(annotation.description());
        // 兼容旧的 requiresAuth 和新的 permissionLevel（字符串形式）
        String levelStr = annotation.permissionLevel();
        if (annotation.requiresAuth()) {
            // 旧注解兼容：requiresAuth=true 等价于 DEVELOPER
            levelStr = "DEVELOPER";
        }
        definition.setPermissionLevel(levelStr);
        definition.setRequiresAuth(annotation.requiresAuth()); // 保留兼容
        definition.setAliases(annotation.aliases());
        definition.setUsage(annotation.usage());

        // 上下文相关属性
        definition.setSupportsContext(annotation.supportsContext());
        definition.setContextType(annotation.contextType());
        definition.setGlobalParams(annotation.globalParams());
        definition.setLocalParams(annotation.localParams());
        definition.setContextTimeout(annotation.contextTimeout());
        definition.setIndependent(annotation.independent());

        definition.setMethod(method);
        definition.setBean(bean);

        // 注册到注册表
        commandRegistry.register(definition);
    }
    
    /**
     * 验证方法签名
     */
    private void validateMethodSignature(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        
        if (parameterTypes.length != 1 || !parameterTypes[0].equals(CommandContext.class)) {
            throw new IllegalStateException(
                String.format("方法 %s.%s 的签名不正确，应该是: public String %s(CommandContext context)",
                    method.getDeclaringClass().getSimpleName(),
                    method.getName(),
                    method.getName())
            );
        }
        
        if (method.getReturnType() != String.class) {
            log.warn("方法 {}.{} 的返回值不是 String，建议返回 String 类型",
                method.getDeclaringClass().getSimpleName(),
                method.getName());
        }
    }
}
