package com.example.intelligentxtsystem.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 通义千问 API 客户端
 * 用于翻译等功能
 */
@Component
public class QwenClient {

    private static final Logger logger = LoggerFactory.getLogger(QwenClient.class);

    @Value("${qianyu.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    private static final String API_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";

    /**
     * 使用通义千问进行翻译（中英互译）
     *
     * @param text 待翻译文本
     * @return 翻译结果
     */
    public String translate(String text) {
        logger.info("QwenClient.translate() 被调用: " + text);
        
        // 构建提示词
        String prompt = buildTranslatePrompt(text);
        logger.info("翻译 Prompt: " + prompt);

        try {
            // 调用通义千问 API，直接获取响应体
            var response = callQwen(prompt);
            logger.info("API 响应体: " + response);

            // 解析响应 - 响应直接包含 output 字段
            if (response != null && response.containsKey("output")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) response.get("output");
                String result = (String) output.get("text");
                logger.info("翻译结果: " + result);
                return result;
            }

            logger.warn("响应格式异常，未找到 output 字段");
            return "⚠️ 翻译结果解析失败";
        } catch (Exception e) {
            logger.warn("翻译异常: " + e.getMessage());
            e.printStackTrace();
            return "⚠️ 翻译服务暂时不可用：" + e.getMessage();
        }
    }

    /**
     * 构建翻译提示词
     */
    private String buildTranslatePrompt(String text) {
        boolean isEnglish = text.matches("^[a-zA-Z].*");

        if (isEnglish) {
            return String.format("""
                    请将以下英文翻译成中文，只返回翻译结果，不要任何解释：
                    
                    %s
                    """, text);
        } else {
            return String.format("""
                    请将以下中文翻译成英文，只返回翻译结果，不要任何解释：
                    
                    %s
                    """, text);
        }
    }

    /**
     * 调用通义千问 API
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callQwen(String prompt) {
        logger.info("开始调用通义千问 API");

        Map<String, Object> body = Map.of(
                "model", "qwen-turbo",
                "input", Map.of("prompt", prompt)
        );

        var headers = new org.springframework.http.HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        var entity = new org.springframework.http.HttpEntity<>(body, headers);

        logger.info("API 请求 URL: " + API_URL);
        logger.info("API 请求体: " + body);

        var response = restTemplate.postForEntity(API_URL, entity, Map.class);

        logger.info("API 响应状态: " + response.getStatusCode());

        if (response.getStatusCode().is2xxSuccessful()) {
            return response.getBody();
        }

        return null;
    }

    /**
     * 使用通义千问回答问题
     *
     * @param question 问题
     * @return 回答
     */
    public String answerQuestion(String question) {
        logger.info("QwenClient.answerQuestion() 被调用: " + question);

        String prompt = buildQuestionPrompt(question);
        logger.info("问答 Prompt: " + prompt);

        try {
            var response = callQwen(prompt);
            logger.info("API 响应体: " + response);

            if (response != null && response.containsKey("output")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) response.get("output");
                String result = (String) output.get("text");
                logger.info("问答结果: " + result);
                return result;
            }

            logger.warn("响应格式异常，未找到 output 字段");
            return "抱歉，我暂时无法回答这个问题";
        } catch (Exception e) {
            logger.warn("问答异常: " + e.getMessage());
            return "抱歉，服务暂时不可用，请稍后再试";
        }
    }

    /**
     * 构建问答提示词
     */
    private String buildQuestionPrompt(String question) {
        return String.format("""
                你是一个智能助手，请用简洁清晰的方式回答以下问题。如果问题不清晰，请说明需要更多信息。

                问题：%s
                """, question);
    }

    /**
     * 使用通义千问进行代码审查
     *
     * @param diff  PR的代码差异
     * @param prInfo PR基本信息
     * @return 审查结果
     */
    public String reviewCode(String diff, String prInfo) {
        logger.info("QwenClient.reviewCode() 被调用");

        String prompt = buildCodeReviewPrompt(diff, prInfo);
        logger.info("代码审查 Prompt 长度: {}", prompt.length());

        try {
            var response = callQwen(prompt);

            if (response != null && response.containsKey("output")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) response.get("output");
                String result = (String) output.get("text");
                logger.info("代码审查结果: {}", result);
                return result;
            }

            logger.warn("响应格式异常，未找到 output 字段");
            return "代码审查结果解析失败，请稍后重试";
        } catch (Exception e) {
            logger.warn("代码审查异常: {}", e.getMessage());
            return "代码审查服务暂时不可用，请稍后再试";
        }
    }

    /**
     * 构建代码审查提示词
     */
    private String buildCodeReviewPrompt(String diff, String prInfo) {
        // 限制 diff 长度，避免超出 API 限制
        String truncatedDiff = diff.length() > 8000 ? diff.substring(0, 8000) + "\n... (代码过长，已截断)" : diff;

        return String.format("""
                你是一位资深代码审查专家，请对以下 Pull Request 进行代码审查。

                PR 基本信息：
                %s

                代码差异：
                ```
                %s
                ```

                请从以下方面给出审查意见（用中文回复）：
                1. 代码质量：是否有明显 bug、逻辑错误
                2. 安全性：是否存在安全风险（如 SQL 注入、XSS 等）
                3. 性能：是否有性能问题
                4. 可维护性：代码是否清晰、易维护
                5. 改进建议：具体的优化建议

                请简洁明了地给出审查结论，如果没有问题就说明代码看起来不错。
                """, prInfo, truncatedDiff);
    }
}
