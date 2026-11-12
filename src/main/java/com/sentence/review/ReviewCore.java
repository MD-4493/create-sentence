package com.sentence.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentence.Json;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * 复习调度核心，逐条对应原版 review-core.js（纯函数，无 DOM 依赖）。
 *
 * 语义要点：
 *  - 每个词只存在于一个"天"里；第 N 天的桶在第 N+1 天到期（今天学的词明天才复习）；
 *  - 答对 → 按连续答对次数排到未来：2 / 4 / 7 / 15 / 30 / 60 天后；
 *  - 答错 → 排到明天重来一次，连续答对次数清零；
 *  - 每轮取最近到期的一天最多 5 个 + 最久没复习的一天最多 5 个（每轮最多 10 题），三轮一个循环；
 *  - 今天已经复习过的词存在 reviewedWords，避免同一天重复出题；
 *  - sources 记录每个词来自哪个词库，复习时可以只挑某几个词库。
 */
public final class ReviewCore {
    public static final String STORE_KEY = "learnedDays";
    public static final String LEGACY_KEY = "learnedWords";
    public static final int PER_SIDE = 5;
    public static final int ROUNDS_PER_CYCLE = 3;
    /** 答对后的间隔（天）：连续答对 1 次 → 2 天后，2 次 → 4 天后，……最多 60 天。 */
    public static final int[] INTERVALS = {2, 4, 7, 15, 30, 60};

    private ReviewCore() {
    }

    /** 持久化抽象，对应 Web Storage 的 getItem/setItem。 */
    public interface KeyValue {
        String get(String key);

        void put(String key, String value);

        void remove(String key);
    }

    /** 随机源，便于测试注入确定性序列。 */
    public interface Rng {
        double next();
    }

    public static final Rng SYSTEM_RNG = Math::random;

    public record Pick(String word, int day, String side) {
    }

    public record Move(Integer from, int to, int interval) {
    }

    public static String localDateStr(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    // ==================== 读写 ====================

    /**
     * 打开软件时调用：读取状态；如果已经不是同一天，则"新的一天"+1。
     * 等价于原版 load(storage, today)。
     */
    public static ReviewState load(KeyValue storage, String today) {
        String day = (today == null || today.isBlank()) ? localDateStr(LocalDate.now()) : today;
        ReviewState state = null;

        String raw = storage.get(STORE_KEY);
        if (raw != null && !raw.isBlank()) {
            state = sanitize(Json.treeOrNull(raw));
        }
        if (state == null) {
            JsonNode legacy = null;
            String legacyRaw = storage.get(LEGACY_KEY);
            if (legacyRaw != null && !legacyRaw.isBlank()) {
                legacy = Json.treeOrNull(legacyRaw);
            }
            state = migrateLegacy(legacy);
            if (state == null) {
                state = new ReviewState();
                state.currentDay = 1;
                state.lastDate = day;
            }
        }
        if (!day.equals(state.lastDate)) {
            state.currentDay += 1;
            state.lastDate = day;
        }
        if (state.reviewedDay != state.currentDay || state.reviewedWords == null) {
            state.reviewedDay = state.currentDay;
            state.reviewedWords = new ArrayList<>();
        }
        save(storage, state);
        return state;
    }

    public static void save(KeyValue storage, ReviewState state) {
        storage.put(STORE_KEY, Json.write(toJson(state)));
    }

    /** 状态序列化：前端与 Java 之间用 JSON 往返，字段与 localStorage 里的结构一致。 */
    public static ObjectNode toJson(ReviewState state) {
        ObjectNode root = Json.object();
        root.put("currentDay", state.currentDay);
        root.put("lastDate", state.lastDate == null ? "" : state.lastDate);
        ObjectNode days = root.putObject("days");
        for (Map.Entry<Integer, List<String>> e : state.days.entrySet()) {
            ArrayNode arr = days.putArray(String.valueOf(e.getKey()));
            for (String w : e.getValue()) arr.add(w);
        }
        root.put("reviewedDay", state.reviewedDay);
        ArrayNode reviewed = root.putArray("reviewedWords");
        for (String w : state.reviewedWords) reviewed.add(w);
        ObjectNode steps = root.putObject("steps");
        for (Map.Entry<String, Integer> e : state.steps.entrySet()) {
            steps.put(e.getKey(), e.getValue());
        }
        ObjectNode sources = root.putObject("sources");
        for (Map.Entry<String, String> e : state.sources.entrySet()) {
            sources.put(e.getKey(), e.getValue());
        }
        return root;
    }

    public static ReviewState sanitize(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        JsonNode cdNode = node.get("currentDay");
        if (cdNode == null || !cdNode.isIntegralNumber()) return null;
        int currentDay = cdNode.asInt();
        if (currentDay < 1) return null;

        ReviewState state = new ReviewState();
        state.currentDay = currentDay;
        state.lastDate = node.path("lastDate").asText("");

        JsonNode days = node.get("days");
        if (days != null && days.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = days.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getValue().isArray()) continue;
                int day;
                try {
                    day = Integer.parseInt(e.getKey().trim());
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (day < 1) continue;
                List<String> words = new ArrayList<>();
                for (JsonNode w : e.getValue()) {
                    if (w.isTextual()) words.add(w.asText());
                }
                state.days.put(day, words);
            }
        }

        JsonNode reviewed = node.get("reviewedWords");
        if (reviewed != null && reviewed.isArray()) {
            for (JsonNode w : reviewed) {
                if (w.isTextual()) state.reviewedWords.add(w.asText());
            }
        }
        JsonNode steps = node.get("steps");
        if (steps != null && steps.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = steps.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().isIntegralNumber() && e.getValue().asInt() >= 0) {
                    state.steps.put(e.getKey(), e.getValue().asInt());
                }
            }
        }
        JsonNode sources = node.get("sources");
        if (sources != null && sources.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = sources.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().isTextual()) state.sources.put(e.getKey(), e.getValue().asText());
            }
        }
        state.reviewedDay = node.path("reviewedDay").asInt(0);
        return state;
    }

    /** 旧版 learnedWords（带 lastSeen 时间戳）迁移到"第 N 天"结构。 */
    public static ReviewState migrateLegacy(JsonNode legacy) {
        if (legacy == null || !legacy.isObject()) return null;
        TreeMap<LocalDate, List<String>> byDate = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = legacy.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode value = e.getValue();
            if (value == null || !value.isObject()) continue;
            JsonNode lastSeen = value.get("lastSeen");
            if (lastSeen == null || lastSeen.isNull()) continue;
            LocalDate date = parseDate(lastSeen.asText(""));
            if (date == null) continue;
            byDate.computeIfAbsent(date, k -> new ArrayList<>()).add(e.getKey());
        }
        if (byDate.isEmpty()) return null;

        ReviewState state = new ReviewState();
        int day = 0;
        for (Map.Entry<LocalDate, List<String>> e : byDate.entrySet()) {
            state.days.put(++day, e.getValue());
        }
        state.currentDay = day;
        state.lastDate = byDate.lastKey().toString();
        return state;
    }

    private static LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate();
        } catch (Exception ignored) {
            // 继续尝试其它格式
        }
        try {
            return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate();
        } catch (Exception ignored) {
            // 继续尝试其它格式
        }
        try {
            return LocalDateTime.parse(iso).toLocalDate();
        } catch (Exception ignored) {
            return null;
        }
    }

    // ==================== 查询与搬运 ====================

    public static Set<String> reviewedSet(ReviewState state) {
        return new HashSet<>(state.reviewedWords == null ? List.of() : state.reviewedWords);
    }

    public static void markReviewed(ReviewState state, String word) {
        if (state.reviewedWords == null) state.reviewedWords = new ArrayList<>();
        if (!state.reviewedWords.contains(word)) state.reviewedWords.add(word);
    }

    public static Integer findDay(ReviewState state, String word) {
        for (Map.Entry<Integer, List<String>> e : state.days.entrySet()) {
            if (e.getValue().contains(word)) return e.getKey();
        }
        return null;
    }

    private static void removeWord(ReviewState state, String word) {
        for (Map.Entry<Integer, List<String>> e : state.days.entrySet()) {
            e.setValue(new ArrayList<>(e.getValue()));
            e.getValue().removeIf(word::equals);
        }
    }

    /** 把单词放进第 day 天（先从原来的天里拿走）。 */
    public static void placeWord(ReviewState state, String word, int day) {
        removeWord(state, word);
        state.days.computeIfAbsent(day, k -> new ArrayList<>()).add(word);
    }

    /** 学习新词（造句环节）：单词记入"今天"，并记下它来自哪个词库。 */
    public static void markLearned(ReviewState state, List<String> words) {
        markLearned(state, words, null);
    }

    public static void markLearned(ReviewState state, List<String> words, String bankId) {
        for (String w : words) {
            placeWord(state, w, state.currentDay);
            if (bankId != null && !bankId.isBlank()) state.sources.put(w, bankId);
        }
    }

    public static Set<String> learnedSet(ReviewState state) {
        Set<String> all = new HashSet<>();
        for (List<String> words : state.days.values()) all.addAll(words);
        return all;
    }

    // ==================== 选词 ====================

    private static List<String> unreviewedIn(ReviewState state, int day, Set<String> reviewed,
                                             Set<String> exclude) {
        return unreviewedIn(state, day, reviewed, exclude, null);
    }

    /** allow 为 null 时不过滤；否则只保留 allow 返回 true 的词。 */
    private static List<String> unreviewedIn(ReviewState state, int day, Set<String> reviewed,
                                             Set<String> exclude, Predicate<String> allow) {
        List<String> source = state.days.getOrDefault(day, List.of());
        List<String> out = new ArrayList<>();
        for (String w : source) {
            if (reviewed.contains(w)) continue;
            if (exclude != null && exclude.contains(w)) continue;
            if (allow != null && !allow.test(w)) continue;
            out.add(w);
        }
        return out;
    }

    /** 实际最近的、仍有未复习单词的一天（第 1 天 ~ 今天的前一天）。 */
    public static Integer nearestDay(ReviewState state, Set<String> reviewed, Set<String> exclude) {
        return nearestDay(state, reviewed, exclude, null);
    }

    public static Integer nearestDay(ReviewState state, Set<String> reviewed, Set<String> exclude,
                                     Predicate<String> allow) {
        for (int d = state.currentDay - 1; d >= 1; d--) {
            if (!unreviewedIn(state, d, reviewed, exclude, allow).isEmpty()) return d;
        }
        return null;
    }

    /** 实际最远的、仍有未复习单词的一天。 */
    public static Integer farthestDay(ReviewState state, Set<String> reviewed, Set<String> exclude) {
        return farthestDay(state, reviewed, exclude, null);
    }

    public static Integer farthestDay(ReviewState state, Set<String> reviewed, Set<String> exclude,
                                      Predicate<String> allow) {
        for (int d = 1; d <= state.currentDay - 1; d++) {
            if (!unreviewedIn(state, d, reviewed, exclude, allow).isEmpty()) return d;
        }
        return null;
    }

    static List<String> sample(List<String> source, int n, Rng rng) {
        List<String> a = new ArrayList<>(source);
        for (int i = a.size() - 1; i > 0; i--) {
            int j = (int) Math.floor(rng.next() * (i + 1));
            Collections.swap(a, i, j);
        }
        return new ArrayList<>(a.subList(0, Math.min(n, a.size())));
    }

    /** 选一轮的词：最近一天最多 5 个 + 最远一天最多 5 个（每轮最多 10 题）。 */
    public static List<Pick> pickRound(ReviewState state, Set<String> reviewed, Rng rng) {
        return pickRound(state, reviewed, rng, null);
    }

    /** allow 为 null 时不过滤；按词库复习时传入。 */
    public static List<Pick> pickRound(ReviewState state, Set<String> reviewed, Rng rng,
                                       Predicate<String> allow) {
        Rng random = rng == null ? SYSTEM_RNG : rng;
        List<Pick> picks = new ArrayList<>();
        Set<String> chosen = new HashSet<>();

        Integer near = nearestDay(state, reviewed, chosen, allow);
        if (near != null) {
            for (String w : sample(unreviewedIn(state, near, reviewed, chosen, allow), PER_SIDE, random)) {
                picks.add(new Pick(w, near, "near"));
                chosen.add(w);
            }
        }
        Integer far = farthestDay(state, reviewed, chosen, allow);
        if (far != null) {
            for (String w : sample(unreviewedIn(state, far, reviewed, chosen, allow), PER_SIDE, random)) {
                picks.add(new Pick(w, far, "far"));
                chosen.add(w);
            }
        }
        return picks;
    }

    /**
     * 答对：按连续答对次数排到未来（2 → 4 → 7 → 15 → 30 → 60 天后）；
     * 答错：排到明天重来一次，连续答对次数清零。
     * to = currentDay + interval - 1：第 N 天的桶要到第 N+1 天才到期。
     */
    public static Move applyResult(ReviewState state, String word, boolean correct) {
        Integer from = findDay(state, word);
        int step = state.steps.getOrDefault(word, 0);
        int interval;
        if (correct) {
            interval = INTERVALS[Math.min(step, INTERVALS.length - 1)];
            state.steps.put(word, Math.min(step + 1, INTERVALS.length - 1));
        } else {
            interval = 1;
            state.steps.put(word, 0);
        }
        int to = state.currentDay + interval - 1;
        placeWord(state, word, to);
        return new Move(from, to, interval);
    }

    /** 还剩多少个本次未复习的可复习单词（allow 可按词库过滤）。 */
    public static int remainingCount(ReviewState state, Set<String> reviewed) {
        return remainingCount(state, reviewed, null);
    }

    public static int remainingCount(ReviewState state, Set<String> reviewed, Predicate<String> allow) {
        int n = 0;
        for (int d = 1; d <= state.currentDay - 1; d++) {
            n += unreviewedIn(state, d, reviewed, null, allow).size();
        }
        return n;
    }
}
