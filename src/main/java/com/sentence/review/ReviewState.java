package com.sentence.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习记录（对应 localStorage 键 learnedDays）。
 *
 * {
 *   currentDay: 20,            // 今天是"第 20 天"
 *   lastDate: "2026-10-08",    // 上次打开软件的日期
 *   days: { 1: ["word", ...], 21: [...] },   // 每个值是"计划在第 N 天复习"的词
 *   sources: { "word": "cefr-a1" },           // 这个词来自哪个词库（复习时按词库筛）
 *   steps: { "word": 2 },      // 连续答对次数，决定下次间隔（2/4/7/15/30/60 天）
 *   reviewedDay: 20,
 *   reviewedWords: ["word", ...]
 * }
 */
public final class ReviewState {
    public int currentDay = 1;
    public String lastDate = "";
    public final Map<Integer, List<String>> days = new LinkedHashMap<>();
    public final Map<String, Integer> steps = new LinkedHashMap<>();
    public final Map<String, String> sources = new LinkedHashMap<>();
    public int reviewedDay;
    public List<String> reviewedWords = new ArrayList<>();
}
