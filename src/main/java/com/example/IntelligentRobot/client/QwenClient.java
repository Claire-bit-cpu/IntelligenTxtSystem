package com.example.IntelligentRobot.client;

import com.example.IntelligentRobot.dto.CodeReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通义千问 API 客户端
 * 用于翻译等功能
 */
@Component
@ConfigurationProperties(prefix = "qianwen")
public class QwenClient {

    private static final Logger logger = LoggerFactory.getLogger(QwenClient.class);

    private String apiKey;
    private String apiUrl;
    private String model;
    private int maxDiffLength;

    private final RestTemplate restTemplate;

    public QwenClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // Getter 和 Setter
    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxDiffLength() {
        return maxDiffLength;
    }

    public void setMaxDiffLength(int maxDiffLength) {
        this.maxDiffLength = maxDiffLength;
    }

    /**
     * 使用通义千问进行翻译（支持多语言）
     *
     * @param text        待翻译文本
     * @param sourceLang  源语言（中文、英语、日语、韩语）
     * @param targetLang  目标语言（中文、英语、日语、韩语）
     * @return 翻译结果
     */
    public String translate(String text, String sourceLang, String targetLang) {
        logger.info("QwenClient.translate() 被调用: {} → {}, text: {}", sourceLang, targetLang, text);
        
        // 构建提示词
        String prompt = buildTranslatePrompt(text, sourceLang, targetLang);
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
            logger.error("翻译异常", e);
            return "⚠️ 翻译服务暂时不可用：" + e.getMessage();
        }
    }

    /**
     * 构建翻译提示词（多语言版本）
     */
    private String buildTranslatePrompt(String text, String sourceLang, String targetLang) {
        // 如果源语言和目标语言相同，无需翻译
        if (sourceLang.equals(targetLang)) {
            return text;
        }

        // 构建翻译指令
        String sourceLangEng = convertToEnglish(sourceLang);
        String targetLangEng = convertToEnglish(targetLang);
        
        return String.format("""
                请将以下%s文本翻译成%s，只返回翻译结果，不要任何解释：
                
                %s
                """, sourceLang, targetLang, text);
    }

    /**
     * 将中文语言名转换为英文（用于提示词）
     */
    private String convertToEnglish(String lang) {
        return switch (lang) {
            case "中文" -> "中文";
            case "英语" -> "英文";
            case "日语" -> "日文";
            case "韩语" -> "韩文";
            default -> lang;
        };
    }

    /**
     * 调用通义千问 API
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callQwen(String prompt) {
        logger.info("开始调用通义千问 API");

        Map<String, Object> body = Map.of(
                "model", model,
                "input", Map.of("prompt", prompt)
        );

        var headers = new org.springframework.http.HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        var entity = new org.springframework.http.HttpEntity<>(body, headers);

        logger.info("API 请求 URL: " + apiUrl);

        var response = restTemplate.postForEntity(apiUrl, entity, Map.class);

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
     * 构建代码审查提示词（结构化输出）
     */
    private String buildCodeReviewPrompt(String diff, String prInfo) {
        // 限制 diff 长度，避免超出 API 限制
        String truncatedDiff = diff.length() > maxDiffLength ? diff.substring(0, maxDiffLength) + "\n... (代码过长，已截断)" : diff;

        return String.format("""
                你是一位资深代码审查专家，请对以下代码变更进行代码审查。

                PR/提交基本信息：
                %s

                代码差异：
                ```
                %s
                ```

                请从以下方面给出审查意见（用中文回复）：
                1. 代码质量：是否有明显 bug、逻辑错误
                2. 安全性：是否存在安全风险（如 SQL 注入、XSS、敏感信息泄露等）
                3. 性能：是否有性能问题
                4. 可维护性：代码是否清晰、易维护
                5. 改进建议：具体的优化建议

                请按以下格式返回结果（必须严格按照格式）：

                【评分】：给出 0-100 的整数评分，100 分为完美
                【问题列表】：列出发现的问题（每个问题一行，如果没有问题写"无"）
                【修改建议】：给出具体的修改建议（每条建议一行，如果没有建议写"无"）
                【详细分析】：给出详细的审查分析

                如果没有任何问题，请在【问题列表】写"无"，在【修改建议】写"无"，并给出较高的评分（90-100）。
                """, prInfo, truncatedDiff);
    }

    /**
     * 使用通义千问进行代码审查（返回结构化结果）
     *
     * @param diff     代码差异
     * @param prInfo   PR/提交基本信息
     * @return 结构化审查结果
     */
    public CodeReviewResult reviewCodeStructured(String diff, String prInfo) {
        logger.info("QwenClient.reviewCodeStructured() 被调用");

        String prompt = buildCodeReviewPrompt(diff, prInfo);
        logger.info("代码审查 Prompt 长度: {}", prompt.length());

        try {
            var response = callQwen(prompt);

            if (response != null && response.containsKey("output")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = (Map<String, Object>) response.get("output");
                String resultText = (String) output.get("text");
                logger.info("代码审查结果: {}", resultText);

                // 解析结构化结果
                return parseCodeReviewResult(resultText);
            }

            logger.warn("响应格式异常，未找到 output 字段");
            return createDefaultReviewResult("代码审查结果解析失败，请稍后重试");
        } catch (Exception e) {
            logger.warn("代码审查异常: {}", e.getMessage());
            return createDefaultReviewResult("代码审查服务暂时不可用，请稍后再试");
        }
    }

    /**
     * 解析 AI 返回的结构化审查结果
     */
    private CodeReviewResult parseCodeReviewResult(String text) {
        int score = 70; // 默认评分
        List<String> problems = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        String fullText = text;

        // 解析评分
        if (text.contains("【评分】")) {
            try {
                String scoreLine = text.lines()
                        .filter(line -> line.contains("【评分】"))
                        .findFirst()
                        .orElse("");
                // 提取数字
                String scoreStr = scoreLine.replaceAll(".*?(\\d+).*", "$1");
                score = Integer.parseInt(scoreStr);
                // 确保评分在 0-100 范围内
                score = Math.max(0, Math.min(100, score));
            } catch (Exception e) {
                logger.warn("解析评分失败: {}", e.getMessage());
            }
        }

        // 解析问题列表
        if (text.contains("【问题列表】")) {
            try {
                String[] lines = text.split("\n");
                boolean inProblems = false;
                for (String line : lines) {
                    if (line.contains("【问题列表】")) {
                        inProblems = true;
                        continue;
                    }
                    if (line.contains("【") && line.contains("】") && !line.contains("【问题列表】")) {
                        inProblems = false;
                    }
                    if (inProblems && !line.trim().isEmpty()) {
                        String cleaned = line.replaceAll("^\\s*[-*\\d.]+\\s*", "").trim();
                        if (!cleaned.isEmpty() && !cleaned.equals("无")) {
                            problems.add(cleaned);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("解析问题列表失败: {}", e.getMessage());
            }
        }

        // 解析修改建议
        if (text.contains("【修改建议】")) {
            try {
                String[] lines = text.split("\n");
                boolean inSuggestions = false;
                for (String line : lines) {
                    if (line.contains("【修改建议】")) {
                        inSuggestions = true;
                        continue;
                    }
                    if (line.contains("【") && line.contains("】") && !line.contains("【修改建议】")) {
                        inSuggestions = false;
                    }
                    if (inSuggestions && !line.trim().isEmpty()) {
                        String cleaned = line.replaceAll("^\\s*[-*\\d.]+\\s*", "").trim();
                        if (!cleaned.isEmpty() && !cleaned.equals("无")) {
                            suggestions.add(cleaned);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("解析修改建议失败: {}", e.getMessage());
            }
        }

        return new CodeReviewResult(score, problems, suggestions, fullText);
    }

    /**
     * 创建默认的审查结果（当解析失败时使用）
     */
    private CodeReviewResult createDefaultReviewResult(String message) {
        List<String> problems = new ArrayList<>();
        problems.add(message);
        return new CodeReviewResult(0, problems, new ArrayList<>(), message);
    }
}
