package com.sentence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentence.llm.DeepSeekClient;
import com.sentence.llm.JsonLoose;
import com.sentence.llm.LlmException;
import com.sentence.review.LegacyImporter;
import com.sentence.review.ReviewCore;
import com.sentence.review.ReviewOps;
import com.sentence.review.ReviewState;
import com.sentence.review.ReviewStore;
import com.sentence.words.CustomBankStore;
import com.sentence.words.WordBankRepository;
import com.sentence.words.WordParser;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** 不依赖测试框架的自动化自测：java -cp ... com.sentence.App --self-test */
public final class SelfTest {
    private static int checks;
    private static int failures;
    private static final List<String> failureMessages = new ArrayList<>();

    private SelfTest() {
    }

    public static int run(CliArgs cli) {
        System.out.println("=== Java 版自测 ===\n");
        Path temp = null;
        try {
            temp = Files.createTempDirectory("sentence-selftest");
            testWordParser();
            testJsonLoose();
            testHtmlSupport();
            testReviewCore();
            testReviewOps();
            testParityWithJs(cli);
            testCustomBankStore(temp.resolve("banks"));
            testWordBankRepository(cli, temp.resolve("repo"));
            testLegacyImporter(temp.resolve("leveldb"));
            testDeepSeekClient(cli, temp.resolve("llm"));
        } catch (Exception e) {
            fail("未捕获异常: " + e);
            e.printStackTrace();
        } finally {
            if (temp != null) deleteQuietly(temp);
        }

        System.out.println();
        System.out.println("检查项: " + checks + "，失败: " + failures);
        if (failures > 0) {
            System.out.println("\n失败详情:");
            failureMessages.forEach(m -> System.out.println("  - " + m));
            System.out.println("\n结果: FAIL");
            return 1;
        }
        System.out.println("\n结果: PASS");
        return 0;
    }

    // ==================== WordParser ====================

    private static void testWordParser() {
        section("WordParser");
        eq("中文行只取行首英文", WordParser.parse("abandon v. 放弃\nmitigate,减轻", true),
                List.of("abandon", "mitigate"));
        eq("带引号的行", WordParser.parse("\"abandon v. 放弃\"", true), List.of("abandon"));
        eq("空格分隔 + 去重 + 小写", WordParser.parse("Take Off, take  off; UPPER", true),
                List.of("take", "off", "upper"));
        eq("不按空格分隔时保留短语", WordParser.parse("take off, give up", false),
                List.of("take off", "give up"));
        eq("过滤数字与非法字符", WordParser.parse("123, a1, hi, 1a", false), List.of("hi"));
        eq("整行中文且行首无英文时丢弃", WordParser.parse("汉字", false), List.of());
        eq("中文行与英文行混排", WordParser.parse("汉字\nabc", false), List.of("abc"));
        eq("CRLF 换行", WordParser.parse("alpha\r\nbeta\r\n", true), List.of("alpha", "beta"));
        eq("超长单词被丢弃", WordParser.parse("a".repeat(41) + "\nok", false), List.of("ok"));
        eq("空输入", WordParser.parse("", true), List.of());
        eq("连字符与撇号", WordParser.parse("well-known, don't", false), List.of("well-known", "don't"));
    }

    // ==================== JsonLoose ====================

    private static void testJsonLoose() {
        section("JsonLoose");
        check("去掉 ```json 围栏", JsonLoose.parse("```json\n{\"a\":1}\n```").path("a").asInt() == 1);
        JsonNode node = JsonLoose.parse("前面废话 {\"b\":[1,2,3]} 后面废话");
        check("截取首尾大括号", node.path("b").size() == 3);
        JsonNode tricky = JsonLoose.parse("{\"s\":\"带 } 的字符串\"}");
        check("字符串里的括号不干扰", "带 } 的字符串".equals(tricky.path("s").asText()));
        boolean threw = false;
        try {
            JsonLoose.parse("完全不是 JSON");
        } catch (RuntimeException e) {
            threw = true;
        }
        check("非法内容抛异常", threw);
    }

    // ==================== HtmlSupport ====================

    private static void testHtmlSupport() {
        section("HtmlSupport（emoji 编码兜底）");
        eq("emoji 转成数字实体", HtmlSupport.escapeAstral("<p>📚</p>"), "<p>&#x1F4DA;</p>");
        eq("BMP 中文原样保留", HtmlSupport.escapeAstral("<b>中文</b>"), "<b>中文</b>");
        check("script 块内不转义", HtmlSupport.escapeAstral("<script>var a='📚';</script>").contains("📚"));
        check("style 块内不转义", HtmlSupport.escapeAstral("<style>/* 📚 */</style>").contains("📚"));
        eq("多个 emoji", HtmlSupport.escapeAstral("🎲🔍"),
                "&#x1F3B2;&#x1F50D;");
    }

    // ==================== ReviewCore ====================

    private static void testReviewCore() {
        section("ReviewCore");
        MapKeyValue kv = new MapKeyValue();
        ReviewState state = ReviewCore.load(kv, "2026-01-01");
        check("首次打开是第 1 天", state.currentDay == 1 && "2026-01-01".equals(state.lastDate));
        check("load 会立即保存", kv.get(ReviewCore.STORE_KEY) != null);

        ReviewState sameDay = ReviewCore.load(kv, "2026-01-01");
        check("同一天不再加天", sameDay.currentDay == 1);

        ReviewState nextDay = ReviewCore.load(kv, "2026-01-02");
        check("跨天 +1", nextDay.currentDay == 2);

        ReviewCore.markLearned(nextDay, List.of("alpha", "beta"));
        check("新学单词进入今天", nextDay.days.get(2).equals(List.of("alpha", "beta")));

        ReviewCore.markReviewed(nextDay, "alpha");
        ReviewCore.save(kv, nextDay);
        ReviewState reloaded = ReviewCore.load(kv, "2026-01-02");
        check("当天已复习记录保留", reloaded.reviewedWords.contains("alpha"));

        ReviewCore.markLearned(nextDay, List.of("gamma"), "custom:test");
        ReviewCore.save(kv, nextDay);
        ReviewState reloaded2 = ReviewCore.load(kv, "2026-01-02");
        check("词库归属会持久化", "custom:test".equals(reloaded2.sources.get("gamma")));

        ReviewState thirdDay = ReviewCore.load(kv, "2026-01-03");
        check("新的一天清空已复习", thirdDay.reviewedWords.isEmpty());

        ReviewState st = new ReviewState();
        st.currentDay = 4;
        st.days.put(1, new ArrayList<>(List.of("a1", "a2", "a3", "a4")));
        st.days.put(2, new ArrayList<>(List.of("b1")));
        st.days.put(3, new ArrayList<>(List.of("c1", "c2")));

        ReviewCore.Move fixed = ReviewCore.applyResult(st, "b1", true);
        check("答对间隔 2 天、落到第 5 个桶", fixed.from() == 2 && fixed.to() == 5 && fixed.interval() == 2);
        check("答对后单词搬到第 5 个桶", st.days.get(5).contains("b1") && !st.days.get(2).contains("b1"));

        ReviewCore.Move worsened = ReviewCore.applyResult(st, "b1", false);
        check("答错排到明天（落在今天这个桶）", worsened.to() == 4 && worsened.interval() == 1);

        ReviewCore.Move capped = ReviewCore.applyResult(st, "b1", false);
        check("答错后仍在明天（今天这个桶）", capped.to() == 4);

        ReviewState grow = new ReviewState();
        grow.currentDay = 10;
        grow.days.put(9, new ArrayList<>(List.of("g1")));
        ReviewCore.Move g1 = ReviewCore.applyResult(grow, "g1", true);
        ReviewCore.Move g2 = ReviewCore.applyResult(grow, "g1", true);
        ReviewCore.Move g3 = ReviewCore.applyResult(grow, "g1", true);
        check("间隔逐次拉长 2 → 4 → 7", g1.interval() == 2 && g2.interval() == 4 && g3.interval() == 7);
        check("连续答对次数推进到 3", grow.steps.getOrDefault("g1", 0) == 3);
        check("答错后连续次数清零", ReviewCore.applyResult(grow, "g1", false).interval() == 1
                && grow.steps.getOrDefault("g1", -1) == 0);

        int remaining = ReviewCore.remainingCount(st, Set.of());
        check("剩余可复习数量", remaining == 6);

        List<ReviewCore.Pick> picks = ReviewCore.pickRound(st, Set.of(), () -> 0.0);
        check("最近一天 2 个 + 最远一天 4 个", picks.size() == 6);
        check("最近侧来自第 3 天", picks.subList(0, 2).stream().allMatch(p -> p.day() == 3));
        check("最远侧来自第 1 天", picks.subList(2, 6).stream().allMatch(p -> p.day() == 1));

        // 按词库过滤：只复习 bankA 的词
        ReviewState own = new ReviewState();
        own.currentDay = 5;
        own.days.put(1, new ArrayList<>(List.of("a1", "a2", "b1")));
        own.sources.put("a1", "bankA");
        own.sources.put("a2", "bankA");
        own.sources.put("b1", "bankB");
        java.util.function.Predicate<String> onlyA = w -> "bankA".equals(own.sources.get(w));
        check("按词库统计剩余", ReviewCore.remainingCount(own, Set.of(), onlyA) == 2);
        check("不筛选时统计全部", ReviewCore.remainingCount(own, Set.of()) == 3);
        List<ReviewCore.Pick> onlyAPicks = ReviewCore.pickRound(own, Set.of(), () -> 0.0, onlyA);
        check("按词库筛选后只出 bankA 的词",
                onlyAPicks.size() == 2 && onlyAPicks.stream().allMatch(p -> "bankA".equals(own.sources.get(p.word()))));

        ReviewCore.markLearned(own, List.of("c1"), "bankB");
        check("学习时记录词库归属", "bankB".equals(own.sources.get("c1")) && own.days.get(5).contains("c1"));
        ReviewCore.markLearned(own, List.of("c2"));
        check("不传词库时保留原归属", !own.sources.containsKey("c2"));

        ReviewState legacy = ReviewCore.migrateLegacy(Json.tree(
                "{\"alpha\":{\"lastSeen\":\"2026-10-01T02:00:00.000Z\"},"
                        + "\"beta\":{\"lastSeen\":\"2026-10-02T02:00:00.000Z\"}}"));
        check("旧版记录迁移成两天", legacy != null && legacy.currentDay == 2
                && legacy.days.get(1).equals(List.of("alpha")));

        check("旧版记录为空时返回 null",
                ReviewCore.migrateLegacy(Json.tree("{}")) == null);

        ReviewState round = ReviewCore.sanitize(ReviewCore.toJson(st));
        check("状态 JSON 往返一致", round != null && round.currentDay == st.currentDay
                && round.days.equals(st.days) && round.sources.equals(st.sources)
                && round.steps.equals(st.steps));
    }

    // ==================== 运行时入口 ReviewOps ====================

    private static void testReviewOps() {
        section("ReviewOps（前端实际调用的入口）");
        String state = "{\"currentDay\":5,\"lastDate\":\"2026-01-01\","
                + "\"days\":{\"1\":[\"a1\",\"a2\"],\"2\":[\"b1\"]},"
                + "\"sources\":{\"a1\":\"bankA\",\"a2\":\"bankA\",\"b1\":\"bankB\"},"
                + "\"steps\":{},\"reviewedDay\":5,\"reviewedWords\":[]}";

        JsonNode counts = Json.tree(ReviewOps.handle("dueCounts", "{\"state\":" + state + "}")).path("counts");
        check("dueCounts 按词库统计", counts.path("bankA").asInt() == 2 && counts.path("bankB").asInt() == 1);

        JsonNode picks = Json.tree(ReviewOps.handle("pickRound",
                "{\"state\":" + state + ",\"allowedBanks\":[\"bankB\"]}"));
        check("pickRound 只出指定词库", picks.size() == 1
                && "b1".equals(picks.get(0).path("word").asText()));

        JsonNode apply = Json.tree(ReviewOps.handle("applyResult",
                "{\"state\":" + state + ",\"word\":\"b1\",\"correct\":true}"));
        check("applyResult 返回新状态与间隔", apply.path("interval").asInt() == 2
                && apply.path("state").path("days").path("6").toString().contains("b1"));

        JsonNode learned = Json.tree(ReviewOps.handle("markLearned",
                "{\"state\":" + state + ",\"words\":[\"c1\"],\"bankId\":\"bankC\"}"));
        check("markLearned 写回词库归属",
                "bankC".equals(learned.path("state").path("sources").path("c1").asText()));

        JsonNode reviewed = Json.tree(ReviewOps.handle("markReviewed",
                "{\"state\":" + state + ",\"word\":\"a1\"}"));
        check("markReviewed 写入当天已复习",
                reviewed.path("state").path("reviewedWords").toString().contains("a1"));

        check("未知 op 返回 null", ReviewOps.handle("nope", "{\"state\":" + state + "}") == null);
    }

    // ==================== 与原版 JS 对拍 ====================

    private static void testParityWithJs(CliArgs cli) {
        section("与 review-core.js 对拍");
        if (cli.parityFile == null || cli.parityFile.isBlank()) {
            System.out.println("  跳过（未提供 --parity-file）");
            return;
        }
        Path file = Path.of(cli.parityFile);
        if (!Files.isRegularFile(file)) {
            fail("对拍文件不存在: " + file);
            return;
        }
        JsonNode expected;
        try {
            expected = Json.tree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            fail("读取对拍文件失败: " + e.getMessage());
            return;
        }

        MapKeyValue kv = new MapKeyValue();
        ObjectNode actual = Json.object();

        ReviewState s = ReviewCore.load(kv, "2026-01-01");
        actual.set("load1", stateJson(s));

        ReviewCore.markLearned(s, List.of("alpha", "beta", "gamma"));
        actual.set("learned", stateJson(s));

        s = ReviewCore.load(kv, "2026-01-02");
        actual.set("load2", stateJson(s));

        ReviewCore.markLearned(s, List.of("delta"));
        s = ReviewCore.load(kv, "2026-01-02");

        ArrayNode moves = actual.putArray("moves");
        moves.add(moveJson(ReviewCore.applyResult(s, "alpha", true)));
        moves.add(moveJson(ReviewCore.applyResult(s, "beta", false)));

        s = ReviewCore.load(kv, "2026-01-03");
        moves.add(moveJson(ReviewCore.applyResult(s, "delta", true)));

        actual.put("remaining", ReviewCore.remainingCount(s, Set.of()));

        double[] seq = {0.2, 0.8, 0.5, 0.1};
        int[] index = {0};
        ReviewCore.Rng rng = () -> seq[(index[0]++) % seq.length];
        ArrayNode picks = actual.putArray("picks");
        for (ReviewCore.Pick p : ReviewCore.pickRound(s, Set.of(), rng)) {
            ObjectNode item = picks.addObject();
            item.put("word", p.word());
            item.put("day", p.day());
            item.put("side", p.side());
        }
        actual.set("finalDays", daysJson(s));

        for (String key : List.of("load1", "learned", "load2", "moves", "remaining", "picks", "finalDays")) {
            JsonNode exp = expected.get(key);
            JsonNode act = actual.get(key);
            boolean same = exp == null ? act == null : exp.equals(act);
            check("对拍字段 " + key, same);
            if (!same) {
                System.out.println("    JS:  " + exp);
                System.out.println("    Java:" + act);
            }
        }
    }

    private static ObjectNode moveJson(ReviewCore.Move move) {
        ObjectNode node = Json.object();
        if (move.from() == null) node.putNull("from");
        else node.put("from", move.from());
        node.put("to", move.to());
        node.put("interval", move.interval());
        return node;
    }

    private static ObjectNode stateJson(ReviewState state) {
        ObjectNode node = Json.object();
        node.put("currentDay", state.currentDay);
        node.put("lastDate", state.lastDate);
        node.set("days", daysJson(state));
        return node;
    }

    private static ObjectNode daysJson(ReviewState state) {
        ObjectNode days = Json.object();
        for (Map.Entry<Integer, List<String>> e : state.days.entrySet()) {
            ArrayNode arr = days.putArray(String.valueOf(e.getKey()));
            e.getValue().forEach(arr::add);
        }
        return days;
    }

    // ==================== 词库 ====================

    private static void testCustomBankStore(Path dir) throws IOException {
        section("CustomBankStore");
        Files.createDirectories(dir);
        CustomBankStore store = new CustomBankStore(dir);

        CustomBankStore.UpsertResult first =
                store.upsert(CustomBankStore.MY_WORDS_ID, "我的生词本", List.of("alpha", "beta", "gamma"), true);
        check("新建词库 added=3", first.added == 3 && first.total == 3);

        CustomBankStore.UpsertResult merged =
                store.upsert(CustomBankStore.MY_WORDS_ID, "我的生词本", List.of("gamma", "delta"), true);
        check("合并去重 added=1", merged.added == 1 && merged.total == 4);

        CustomBankStore.UpsertResult replaced =
                store.upsert("custom:file-demo", "demo", List.of("x", "y"), false);
        check("非合并模式 added=2", replaced.added == 2 && replaced.total == 2);

        check("文件已落盘", Files.isRegularFile(dir.resolve("custom-banks.json")));

        CustomBankStore reloaded = new CustomBankStore(dir);
        check("重新加载后仍在", reloaded.find(CustomBankStore.MY_WORDS_ID) != null
                && reloaded.find("custom:file-demo").words.size() == 2);

        reloaded.delete("custom:file-demo");
        check("删除生效", reloaded.find("custom:file-demo") == null);
    }

    private static void testWordBankRepository(CliArgs cli, Path dataDir) throws IOException {
        section("WordBankRepository");
        Files.createDirectories(dataDir);
        Settings settings = Settings.forArgs(cli);
        CustomBankStore custom = new CustomBankStore(dataDir);
        WordBankRepository repo = new WordBankRepository(settings.appRoot, custom);

        List<Map<String, Object>> banks = repo.listBanks();
        check("内置词库数量 = 4", banks.size() == 4);
        check("第一个是 cefr-a1", "cefr-a1".equals(banks.get(0).get("id")));

        Map<String, Object> words = repo.getWords("cefr-a1");
        check("cefr-a1 有单词", words.get("words") instanceof List<?> list && !list.isEmpty());

        custom.upsert("custom:demo", "演示库", List.of("alpha"), true);
        List<Map<String, Object>> withCustom = repo.listBanks();
        check("自定义词库排在最前且带 ⭐", withCustom.size() == 5
                && String.valueOf(withCustom.get(0).get("name")).startsWith("⭐"));
        check("自定义词库可读取",
                ((List<?>) repo.getWords("custom:demo").get("words")).size() == 1);

        boolean threw = false;
        try {
            repo.getWords("自定义不存在");
        } catch (RuntimeException e) {
            threw = true;
        }
        check("不存在的词库抛异常", threw);
    }

    private static void testLegacyImporter(Path dir) throws IOException {
        section("LegacyImporter");
        Files.createDirectories(dir);
        String first = "{\"currentDay\":2,\"lastDate\":\"2026-10-07\",\"days\":{\"1\":[\"alpha\"]}}";
        String second = "{\"currentDay\":3,\"lastDate\":\"2026-10-08\",\"days\":{\"1\":[\"interview\"],\"2\":[\"up\"]}}";
        String content = "META:file://\u0001_file://\u0001learnedDays\u0001" + first
                + "\u0001later\u0001_file://\u0001learnedDays\u0001" + second + "\u0001tail";
        Files.write(dir.resolve("000003.log"), content.getBytes(StandardCharsets.ISO_8859_1));

        Map<String, String> imported = LegacyImporter.fromLevelDb(dir);
        check("能读出 learnedDays", imported.containsKey("learnedDays"));
        JsonNode state = Json.tree(imported.get("learnedDays"));
        check("取的是最后一次写入的值", state.path("currentDay").asInt() == 3);
        check("单词完整", state.path("days").path("1").get(0).asText().equals("interview"));

        check("目录不存在时安全返回",
                LegacyImporter.fromLevelDb(dir.resolve("nope")).isEmpty());
    }

    // ==================== DeepSeek 客户端 ====================

    private static void testDeepSeekClient(CliArgs cli, Path dataDir) throws IOException {
        section("DeepSeekClient（本地假服务器）");
        Files.createDirectories(dataDir);
        AtomicReference<String> lastAuth = new AtomicReference<>();
        AtomicReference<String> lastBody = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger failRemaining = new java.util.concurrent.atomic.AtomicInteger();
        int[] status = {200};

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int code = status[0];
            if (failRemaining.getAndDecrement() > 0) code = 503;
            String payload = code == 200
                    ? "{\"choices\":[{\"message\":{\"content\":\"hello from stub\"}}]}"
                    : "{\"error\":{\"message\":\"invalid key\"}}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            Settings settings = Settings.forArgs(cli);
            int port = server.getAddress().getPort();
            Settings stub = Settings.of(settings.appRoot, dataDir, "sk-test",
                    "http://127.0.0.1:" + port, "test-model", false);
            DeepSeekClient client = new DeepSeekClient(stub);

            String content = client.chat("system-prompt", "user-prompt", 0.7, true);
            check("解析出 content", "hello from stub".equals(content));
            check("带上 Bearer 认证", "Bearer sk-test".equals(lastAuth.get()));
            JsonNode body = Json.tree(lastBody.get());
            check("请求体模型正确", "test-model".equals(body.path("model").asText()));
            check("请求体含 response_format", "json_object".equals(body.path("response_format").path("type").asText()));
            check("消息顺序为 system,user",
                    "system".equals(body.path("messages").get(0).path("role").asText())
                            && "user".equals(body.path("messages").get(1).path("role").asText()));
            check("温度传递", Math.abs(body.path("temperature").asDouble() - 0.7) < 1e-9);

            status[0] = 401;
            requests.set(0);
            String message = null;
            try {
                client.chat("s", "u", 0.5, false);
            } catch (LlmException e) {
                message = e.getMessage();
            }
            check("401 映射成中文提示", message != null && message.contains("401"));
            check("401 不重试", requests.get() == 1);

            status[0] = 200;
            failRemaining.set(1);
            requests.set(0);
            String retried = client.chat("s", "u", 0.5, false);
            check("503 后重试成功", "hello from stub".equals(retried));
            check("503 恰好重试一次", requests.get() == 2);
        } finally {
            server.stop(0);
        }
    }

    // ==================== 断言与工具 ====================

    private static void section(String name) {
        System.out.println("\n[" + name + "]");
    }

    private static void check(String name, boolean condition) {
        checks++;
        if (condition) {
            System.out.println("  PASS  " + name);
        } else {
            System.out.println("  FAIL  " + name);
            fail(name);
        }
    }

    private static void eq(String name, Object actual, Object expected) {
        checks++;
        if (expected.equals(actual)) {
            System.out.println("  PASS  " + name);
        } else {
            System.out.println("  FAIL  " + name + " 期望=" + expected + " 实际=" + actual);
            fail(name + " 期望=" + expected + " 实际=" + actual);
        }
    }

    private static void fail(String message) {
        failures++;
        failureMessages.add(message);
    }

    private static void deleteQuietly(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 清理失败不影响结果
                        }
                    });
        } catch (IOException ignored) {
            // 清理失败不影响结果
        }
    }

    /** 内存版 Web Storage。 */
    private static final class MapKeyValue implements ReviewCore.KeyValue {
        private final Map<String, String> map = new LinkedHashMap<>();

        @Override
        public String get(String key) {
            return map.get(key);
        }

        @Override
        public void put(String key, String value) {
            map.put(key, value);
        }

        @Override
        public void remove(String key) {
            map.remove(key);
        }
    }
}
