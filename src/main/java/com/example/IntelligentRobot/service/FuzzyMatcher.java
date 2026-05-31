package com.example.IntelligentRobot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 模糊匹配器
 * 使用 Levenshtein Distance（编辑距离）和 Jaro-Winkler 相似度
 * 实现指令的模糊匹配，用于"您是不是想说 /xxx？"提示
 */
@Component
public class FuzzyMatcher {

    private static final Logger log = LoggerFactory.getLogger(FuzzyMatcher.class);

    /**
     * 模糊匹配阈值（相似度 >= 此值且 < 1.0 时触发提示）
     * 范围 [0.0, 1.0]
     */
    @Value("${fuzzy-match.threshold:0.70}")
    private double threshold;

    /**
     * 是否启用模糊匹配
     */
    @Value("${fuzzy-match.enabled:true}")
    private boolean enabled;

    /**
     * 最大建议指令数量
     */
    @Value("${fuzzy-match.max-suggestions:3}")
    private int maxSuggestions;

    /**
     * 对所有已注册指令进行模糊匹配，返回相似度最高的指令列表
     *
     * @param input        用户输入的指令名（不含 / 前缀）
     * @param allCommands  所有已注册指令名（含别名）
     * @return             按相似度排序的匹配结果，空列表表示无匹配
     */
    public List<MatchResult> match(String input, Set<String> allCommands) {
        if (!enabled || input == null || input.isBlank()) {
            return Collections.emptyList();
        }

        String normalizedInput = normalize(input);
        if (normalizedInput.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Double> scores = new HashMap<>();

        for (String cmd : allCommands) {
            String normalizedCmd = normalize(cmd);
            if (normalizedCmd.isEmpty()) continue;

            // 使用 Jaro-Winkler（对前缀错误更敏感，适合指令匹配）
            double jwScore = jaroWinklerSimilarity(normalizedInput, normalizedCmd);

            // 使用 Levenshtein 归一化相似度（作为补充）
            double lvScore = 1.0 - (double) levenshteinDistance(normalizedInput, normalizedCmd)
                    / Math.max(normalizedInput.length(), normalizedCmd.length());

            // 取两者最大值（更宽松的匹配）
            double finalScore = Math.max(jwScore, lvScore);

            if (finalScore >= threshold && finalScore < 1.0) {
                scores.put(cmd, finalScore);
            }
        }

        // 按相似度降序排序
        List<MatchResult> results = new ArrayList<>();
        scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(maxSuggestions)
                .forEach(e -> results.add(new MatchResult(e.getKey(), e.getValue())));

        return results;
    }

    /**
     * Jaro-Winkler 相似度算法
     * 对前缀匹配的惩罚更小，适合短字符串（如指令名）的模糊匹配
     */
    private double jaroWinklerSimilarity(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;

        int len1 = s1.length();
        int len2 = s2.length();

        if (len1 == 0 || len2 == 0) return 0.0;

        int matchDistance = Math.max(len1, len2) / 2 - 1;
        if (matchDistance < 0) matchDistance = 0;

        boolean[] s1Matches = new boolean[len1];
        boolean[] s2Matches = new boolean[len2];

        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, len2);
            boolean found = false;
            for (int j = start; j < end; j++) {
                if (!s2Matches[j] && s1.charAt(i) == s2.charAt(j)) {
                    s1Matches[i] = true;
                    s2Matches[j] = true;
                    matches++;
                    found = true;
                    break;
                }
            }
        }

        if (matches == 0) return 0.0;

        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (s1Matches[i]) {
                while (!s2Matches[k]) k++;
                if (s1.charAt(i) != s2.charAt(k)) transpositions++;
                k++;
            }
        }

        double jaro = ((double) matches / len1
                + (double) matches / len2
                + (double) (matches - transpositions / 2) / matches) / 3.0;

        // Winkler 调整：对共同前缀给予奖励
        int prefixLen = 0;
        int maxPrefix = Math.min(4, Math.min(len1, len2));
        while (prefixLen < maxPrefix && s1.charAt(prefixLen) == s2.charAt(prefixLen)) {
            prefixLen++;
        }

        double winkler = jaro + prefixLen * 0.1 * (1.0 - jaro);
        return Math.min(winkler, 1.0);
    }

    /**
     * Levenshtein 编辑距离
     * 计算从一个字符串变成另一个字符串所需的最少单字符编辑操作数
     */
    private int levenshteinDistance(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();
        int[][] dp = new int[len1 + 1][len2 + 1];

        for (int i = 0; i <= len1; i++) dp[i][0] = i;
        for (int j = 0; j <= len2; j++) dp[0][j] = j;

        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        dp[i - 1][j] + 1,      // 删除
                        Math.min(
                                dp[i][j - 1] + 1,  // 插入
                                dp[i - 1][j - 1] + cost // 替换
                        )
                );
            }
        }

        return dp[len1][len2];
    }

    /**
     * 归一化：转小写，去除非字母数字字符
     */
    private String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * 匹配结果
     *
     * @param commandName 匹配的指令名
     * @param similarity  相似度 [0.0, 1.0]
     */
    public record MatchResult(String commandName, double similarity) {
        @Override
        public String toString() {
            return String.format("/%s (相似度: %.0f%%)", commandName, similarity * 100);
        }
    }
}
