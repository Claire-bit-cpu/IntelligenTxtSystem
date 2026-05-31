package com.example.intelligentxtsystem.dto;

import java.util.List;

/**
 * 代码审查结果 DTO
 */
public class CodeReviewResult {
    private int score;              // 评分 0-100
    private List<String> problems;  // 问题列表
    private List<String> suggestions; // 修改建议
    private String fullText;         // 完整审查结果

    public CodeReviewResult() {
    }

    public CodeReviewResult(int score, List<String> problems, List<String> suggestions, String fullText) {
        this.score = score;
        this.problems = problems;
        this.suggestions = suggestions;
        this.fullText = fullText;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public List<String> getProblems() {
        return problems;
    }

    public void setProblems(List<String> problems) {
        this.problems = problems;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

    public String getFullText() {
        return fullText;
    }

    public void setFullText(String fullText) {
        this.fullText = fullText;
    }
}
