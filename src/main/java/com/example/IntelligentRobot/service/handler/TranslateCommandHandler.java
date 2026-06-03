package com.example.IntelligentRobot.service.handler;

import com.example.IntelligentRobot.annotation.Command;
import com.example.IntelligentRobot.client.QwenClient;
import com.example.IntelligentRobot.dto.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 翻译指令处理器（新框架版本）
 * 指令格式：
 *   /translate <文本> - 自动检测语言，非中文→中文，中文→英语
 *   /translate <目标语言> <文本> - 自动检测源语言，翻译为指定语言
 *   /translate <源语言> <目标语言> <文本> - 指定源语言和目标语言
 * 支持语言：中文、英语、日语、韩语
 */
@Component
public class TranslateCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(TranslateCommandHandler.class);
    
    private final QwenClient qwenClient;

    // 支持的语言列表
    private static final List<String> SUPPORTED_LANGUAGES = Arrays.asList("中文", "英语", "日语", "韩语");

    public TranslateCommandHandler(QwenClient qwenClient) {
        this.qwenClient = qwenClient;
    }

    @Command(
        name = "translate",
        description = "多语言翻译（支持中文、英语、日语、韩语互译）",
        usage = "/translate <文本> 或 /translate <目标语言> <文本> 或 /translate <源语言> <目标语言> <文本>"
    )
    public String handle(CommandContext context) {
        String content = context.getArgs().trim();

        if (content.isEmpty()) {
            return buildUsageHelp();
        }

        if (content.length() > 500) {
            return "⚠️ 文本长度不能超过 500 字符";
        }

        try {
            // 解析命令参数
            TranslateParams params = parseTranslateParams(content);
            
            // 执行翻译
            String translated = qwenClient.translate(
                params.getText(), 
                params.getSourceLang(), 
                params.getTargetLang()
            );
            
            log.info("翻译结果: {} → {}: {}", params.getSourceLang(), params.getTargetLang(), translated);
            
            return String.format("🌐 翻译结果（%s → %s）：\n%s", 
                params.getSourceLang(), params.getTargetLang(), translated);
        } catch (IllegalArgumentException e) {
            return "❌ " + e.getMessage() + "\n\n" + buildUsageHelp();
        } catch (Exception e) {
            log.warn("翻译异常: {}", e.getMessage());
            return "⚠️ 翻译服务暂时不可用，请稍后再试";
        }
    }

    /**
     * 解析翻译参数
     */
    private TranslateParams parseTranslateParams(String content) {
        String[] parts = content.split("\\s+", 4); // 最多分成4部分
        
        if (parts.length < 2) {
            // 只有文本：/translate <文本>
            String text = content;
            String sourceLang = detectLanguage(text);
            String targetLang = determineTargetLang(sourceLang);
            return new TranslateParams(sourceLang, targetLang, text);
        }

        // 尝试解析第一个词是否为语言名
        String firstWord = parts[0];
        if (isLanguageName(firstWord)) {
            // /translate <目标语言> <文本>
            String targetLang = firstWord;
            String text = content.substring(firstWord.length()).trim();
            
            if (text.isEmpty()) {
                throw new IllegalArgumentException("缺少待翻译文本");
            }
            
            String sourceLang = detectLanguage(text);
            return new TranslateParams(sourceLang, targetLang, text);
        }

        // 尝试解析前两个词是否为语言名
        if (parts.length >= 3) {
            String firstPart = parts[0];
            String secondPart = parts[1];
            
            if (isLanguageName(firstPart) && isLanguageName(secondPart)) {
                // /translate <源语言> <目标语言> <文本>
                String sourceLang = firstPart;
                String targetLang = secondPart;
                String text = content.substring((firstPart + " " + secondPart).length()).trim();
                
                if (text.isEmpty()) {
                    throw new IllegalArgumentException("缺少待翻译文本");
                }
                
                if (sourceLang.equals(targetLang)) {
                    throw new IllegalArgumentException("源语言和目标语言不能相同");
                }
                
                return new TranslateParams(sourceLang, targetLang, text);
            }
        }

        // 只有文本：/translate <文本>
        String text = content;
        String sourceLang = detectLanguage(text);
        String targetLang = determineTargetLang(sourceLang);
        return new TranslateParams(sourceLang, targetLang, text);
    }

    /**
     * 检测文本语言（简单规则）
     */
    private String detectLanguage(String text) {
        // 检测是否包含中文字符
        if (text.matches(".*[\\u4e00-\\u9fa5].*")) {
            return "中文";
        }
        // 检测是否包含日文字符（平假名、片假名）
        if (text.matches(".*[\\u3040-\\u30ff].*")) {
            return "日语";
        }
        // 检测是否包含韩文字符
        if (text.matches(".*[\\uac00-\\ud7af].*")) {
            return "韩语";
        }
        // 默认视为英语
        return "英语";
    }

    /**
     * 根据源语言确定默认目标语言
     * 规则：非中文→中文，中文→英语
     */
    private String determineTargetLang(String sourceLang) {
        if ("中文".equals(sourceLang)) {
            return "英语";
        } else {
            return "中文";
        }
    }

    /**
     * 判断是否为支持的语言名
     */
    private boolean isLanguageName(String word) {
        return SUPPORTED_LANGUAGES.contains(word);
    }

    /**
     * 构建使用帮助
     */
    private String buildUsageHelp() {
        return """
               📖 翻译命令用法：
               
               1️⃣ /translate <文本>
                  - 自动检测语言，非中文→中文，中文→英语
                  - 例：/translate Hello
                  - 例：/translate 你好
               
               2️⃣ /translate <目标语言> <文本>
                  - 自动检测源语言，翻译为指定语言
                  - 支持语言：中文、英语、日语、韩语
                  - 例：/translate 日语 Hello
                  - 例：/translate 中文 こんにちは
               
               3️⃣ /translate <源语言> <目标语言> <文本>
                  - 指定源语言和目标语言
                  - 例：/translate 英语 日语 Hello
                  - 例：/translate 中文 韩语 你好
               
               ⚠️ 文本长度不能超过 500 字符
               """;
    }

    /**
     * 翻译参数内部类
     */
    private static class TranslateParams {
        private final String sourceLang;
        private final String targetLang;
        private final String text;

        public TranslateParams(String sourceLang, String targetLang, String text) {
            this.sourceLang = sourceLang;
            this.targetLang = targetLang;
            this.text = text;
        }

        public String getSourceLang() {
            return sourceLang;
        }

        public String getTargetLang() {
            return targetLang;
        }

        public String getText() {
            return text;
        }
    }
}
