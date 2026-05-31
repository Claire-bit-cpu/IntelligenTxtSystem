package com.example.IntelligentRobot.service;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.dto.CommandContext;
import com.example.IntelligentRobot.dto.CommandDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 指令扫描器
 * 启动时自动扫描并注册带有 @Command 注解的方法
 * 
 * 使用 ContextRefreshedEvent 避免在 @PostConstruct 时产生循环依赖
 * 注意：不在构造函数中注入 CommandRegistry，而是在 onApplicationEvent 中通过 ApplicationContext 获取，避免循环依赖
 */
@Component
public class CommandScanner implements ApplicationListener<ContextRefreshedEvent> {
    
    private static final Logger log = LoggerFactory.getLogger(CommandScanner.class);
    
    private final ApplicationContext applicationContext;
    
    /**
     * 只注入 ApplicationContext，不注入 CommandRegistry，避免循环依赖
     */
    public CommandScanner(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
    
    /**
     * 在 Spring 上下文刷新完成后执行扫描
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // 确保只执行一次（避免父子容器重复执行）
        if (event.getApplicationContext().getParent() != null) {
            return;
        }
        
        log.info("开始扫描指令...");
        
        // 延迟获取 CommandRegistry，避免在构造函数中注入导致循环依赖
        CommandRegistry commandRegistry = applicationContext.getBean(CommandRegistry.class);
        
        int count = 0;
        
        // 扫描所有 Spring Bean，查找带有 @Command 注解的方法
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
            
            count += registerCommandsInClass(targetClass, bean, commandRegistry);
        }
        
        log.info("指令扫描完成，共注册 {} 个指令", count);
    }
    
    /**
     * 注册类中的所有 @Command 方法
     */
    private int registerCommandsInClass(Class<?> clazz, Object bean, CommandRegistry commandRegistry) {
        int count = 0;
        
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Command.class)) {
                registerCommand(method, bean, commandRegistry);
                count++;
            }
        }
        
        return count;
    }
    
    /**
     * 注册单个指令
     */
    private void registerCommand(Method method, Object bean, CommandRegistry commandRegistry) {
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
