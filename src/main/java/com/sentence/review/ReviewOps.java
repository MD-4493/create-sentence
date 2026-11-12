package com.sentence.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentence.Json;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 复习调度的运行时入口：前端（review-core.js）通过 JsBridge.reviewOp 调到这里。
 *
 * 入参和返回值都是 JSON 字符串，状态结构与 localStorage 里的 learnedDays 一致。
 * 这样 Java 负责真正的调度计算，前端只保留一份“参考实现”用于 Node 对拍。
 */
public final class ReviewOps {
    private ReviewOps() {
    }

    public static String handle(String op, String payloadJson) {
        try {
            JsonNode args = Json.tree(payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson);
            ReviewState state = ReviewCore.sanitize(args.get("state"));
            if (state == null) return null;
            switch (op == null ? "" : op) {
                case "pickRound": {
                    List<ReviewCore.Pick> picks = ReviewCore.pickRound(
                            state, ReviewCore.reviewedSet(state), null, allow(state, args));
                    ArrayNode out = Json.array();
                    for (ReviewCore.Pick p : picks) {
                        ObjectNode item = Json.object();
                        item.put("word", p.word());
                        item.put("day", p.day());
                        item.put("side", p.side());
                        out.add(item);
                    }
                    return Json.write(out);
                }
                case "applyResult": {
                    String word = args.path("word").asText("");
                    boolean correct = args.path("correct").asBoolean(false);
                    ReviewCore.Move move = ReviewCore.applyResult(state, word, correct);
                    ObjectNode out = Json.object();
                    out.set("state", ReviewCore.toJson(state));
                    if (move.from() == null) out.putNull("from");
                    else out.put("from", move.from());
                    out.put("to", move.to());
                    out.put("interval", move.interval());
                    return Json.write(out);
                }
                case "remaining": {
                    int count = ReviewCore.remainingCount(state, ReviewCore.reviewedSet(state), allow(state, args));
                    ObjectNode out = Json.object();
                    out.put("count", count);
                    return Json.write(out);
                }
                case "dueCounts": {
                    Set<String> reviewed = ReviewCore.reviewedSet(state);
                    ObjectNode counts = Json.object();
                    for (int d = 1; d <= state.currentDay - 1; d++) {
                        for (String w : state.days.getOrDefault(d, List.of())) {
                            if (reviewed.contains(w)) continue;
                            String bank = bankOf(state, w);
                            counts.put(bank, counts.path(bank).asInt(0) + 1);
                        }
                    }
                    ObjectNode out = Json.object();
                    out.set("counts", counts);
                    return Json.write(out);
                }
                case "markLearned": {
                    List<String> words = new ArrayList<>();
                    for (JsonNode w : args.path("words")) {
                        if (w.isTextual()) words.add(w.asText());
                    }
                    String bankId = args.path("bankId").isTextual() ? args.path("bankId").asText() : null;
                    ReviewCore.markLearned(state, words, bankId);
                    ObjectNode out = Json.object();
                    out.set("state", ReviewCore.toJson(state));
                    return Json.write(out);
                }
                case "markReviewed": {
                    ReviewCore.markReviewed(state, args.path("word").asText(""));
                    ObjectNode out = Json.object();
                    out.set("state", ReviewCore.toJson(state));
                    return Json.write(out);
                }
                default:
                    return null;
            }
        } catch (Throwable t) {
            System.err.println("reviewOp 失败 (" + op + "): " + t.getMessage());
            return null;
        }
    }

    /** allowedBanks 缺省/空数组表示不筛选；否则只允许这些词库（空字符串代表“未分类”）。 */
    private static Predicate<String> allow(ReviewState state, JsonNode args) {
        JsonNode banks = args.get("allowedBanks");
        if (banks == null || !banks.isArray()) return null;
        Set<String> allowed = new LinkedHashSet<>();
        for (JsonNode b : banks) {
            if (b.isTextual()) allowed.add(b.asText());
        }
        if (allowed.isEmpty()) return null;
        return w -> allowed.contains(bankOf(state, w));
    }

    private static String bankOf(ReviewState state, String word) {
        String id = state.sources.get(word);
        return (id == null || id.isEmpty() || "unknown".equals(id)) ? "" : id;
    }
}
