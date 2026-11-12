package com.sentence;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentence.llm.DeepSeekClient;
import com.sentence.llm.JsonLoose;
import com.sentence.llm.LlmException;
import com.sentence.llm.Prompts;
import com.sentence.review.ReviewOps;
import com.sentence.review.ReviewStore;
import com.sentence.words.CustomBankStore;
import com.sentence.words.Dictionary;
import com.sentence.words.WordBankRepository;
import com.sentence.words.WordParser;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.scene.web.WebEngine;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IPC 桥：把 preload.js 暴露的 9 个 window.api 方法实现成 Java 方法。
 *
 * 线程模型：JS 同步调用 call() → 丢到 worker 线程池执行 → 结果回到 FX 线程，
 * 通过 window.__bridgeResolve(id, ok, payload) 兑现 Promise。这样网络/磁盘 IO 不会卡界面。
 */
public final class JsBridge {
    @FunctionalInterface
    private interface Handler {
        Object handle(JsonNode args) throws Exception;
    }

    private final WebEngine engine;
    private final Stage owner;
    private final boolean interactive;
    private final DeepSeekClient llm;
    private final WordBankRepository repository;
    private final CustomBankStore custom;
    private final ReviewStore store;
    private final Dictionary dictionary;

    private final ExecutorService worker = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ipc-worker");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Handler> handlers = new LinkedHashMap<>();

    public JsBridge(WebEngine engine, Stage owner, Settings settings,
                    DeepSeekClient llm, WordBankRepository repository,
                    CustomBankStore custom, ReviewStore store, Dictionary dictionary,
                    boolean interactive) {
        this.engine = engine;
        this.owner = owner;
        this.llm = llm;
        this.repository = repository;
        this.custom = custom;
        this.store = store;
        this.dictionary = dictionary;
        this.interactive = interactive;
        registerHandlers();
    }

    private void registerHandlers() {
        handlers.put("generateSentences", args -> {
            List<String> words = stringList(args);
            if (words.isEmpty()) throw new IllegalArgumentException("请提供至少一个单词");
            String content = llm.chat(Prompts.SENTENCE_SYSTEM, Prompts.sentences(words), 0.7, true);
            List<Map<String, Object>> list = parseSentences(content);
            if (!list.isEmpty()) return applyDictionary(list, words, dictionary);
            // 看着像 JSON 却解析不出例句：报错让用户重试，别把 JSON 原文渲染到页面上。
            if (content.trim().startsWith("{")) {
                throw new LlmException("例句生成格式异常（模型没有返回可用的 JSON），请重试一次。");
            }
            // 真·纯文本（旧格式）才退回前端的整段渲染。
            return content;
        });

        handlers.put("correctSentence", args -> {
            String sentence = text(args, "sentence");
            if (sentence == null || sentence.trim().isEmpty()) {
                throw new IllegalArgumentException("请先输入一个句子");
            }
            List<String> keywords = stringList(args == null ? null : args.get("keywords"));
            return llm.chat(Prompts.CORRECTION_SYSTEM, Prompts.correction(sentence, keywords), 0.5, false);
        });

        handlers.put("getWordBanks", args -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("banks", repository.listBanks());
            return result;
        });

        handlers.put("getWords", args -> {
            if (args == null || !args.isTextual()) throw new IllegalArgumentException("词库 id 缺失");
            return repository.getWords(args.asText());
        });

        handlers.put("generateReviewQuestions", args -> {
            List<String> words = stringList(args);
            if (words.isEmpty()) throw new IllegalArgumentException("没有需要复习的单词");
            String content = llm.chat(Prompts.REVIEW_SYSTEM, Prompts.reviewQuestions(words), 0.5, true);
            return parseReviewQuestions(content);
        });

        handlers.put("addCustomWords", args -> {
            List<String> words = WordParser.parse(args == null ? "" : args.asText(""), true);
            if (words.isEmpty()) throw new IllegalArgumentException("没有识别到英文单词");
            return upsertResult(custom.upsert(CustomBankStore.MY_WORDS_ID, "我的生词本", words, true));
        });

        handlers.put("importWordBank", args -> {
            String path = Fx.call(this::chooseFile);
            if (path == null) return null;
            Path file = Path.of(path);
            List<String> words = WordParser.parse(Files.readString(file, StandardCharsets.UTF_8), false);
            if (words.isEmpty()) throw new IllegalArgumentException("文件里没有识别到英文单词");
            String name = baseName(file);
            Map<String, Object> result = upsertResult(
                    custom.upsert("custom:file-" + name, name, words, false));
            result.put("name", name);
            return result;
        });

        handlers.put("generateWordBank", args -> {
            String description = text(args, "description");
            if (description == null || description.trim().isEmpty()) {
                throw new IllegalArgumentException("请先描述你想要的词库");
            }
            int count = clamp(parseInt(text(args, "count"), 100), 10, 200);
            String content = llm.chat(Prompts.BANK_SYSTEM, Prompts.wordBank(description.trim(), count), 0.7, true);
            JsonNode data = JsonLoose.parse(content);
            List<String> raw = new ArrayList<>();
            for (JsonNode w : data.path("words")) {
                if (w.isTextual()) raw.add(w.asText());
            }
            List<String> words = WordParser.parse(String.join("\n", raw), false);
            if (words.isEmpty()) throw new IllegalArgumentException("没有生成出有效的单词，请换个描述再试");
            String name = data.path("name").asText(description).trim();
            if (name.length() > 20) name = name.substring(0, 20);
            Map<String, Object> result = upsertResult(
                    custom.upsert("custom:ai-" + System.currentTimeMillis(), name, words, false));
            result.put("name", name);
            return result;
        });

        handlers.put("deleteCustomBank", args -> {
            String id = args == null ? "" : args.asText("");
            if (!id.startsWith("custom:")) throw new IllegalArgumentException("内置词库不能删除");
            custom.delete(id);
            return Boolean.TRUE;
        });
    }

    // ==================== 被 JS 调用的入口 ====================

    /** 由 bridge-core.js 调用：window.__javaBridge.call(id, method, argsJson)。 */
    public void call(String id, String method, String argsJson) {
        worker.submit(() -> {
            try {
                Handler handler = handlers.get(method);
                if (handler == null) throw new IllegalArgumentException("未知调用: " + method);
                JsonNode args = (argsJson == null || argsJson.isEmpty() || "null".equals(argsJson))
                        ? null : Json.tree(argsJson);
                Object result = handler.handle(args);
                complete(id, true, Json.write(result));
            } catch (Throwable t) {
                String message = t.getMessage() == null || t.getMessage().isBlank()
                        ? t.getClass().getSimpleName()
                        : t.getMessage();
                if (!(t instanceof LlmException) && !(t instanceof IllegalArgumentException)) {
                    t.printStackTrace();
                }
                complete(id, false, message);
            }
        });
    }

    /**
     * 复习调度的同步入口：由 review-core.js 通过 window.__javaBridge.reviewOp(op, payload) 调用。
     * 纯内存计算，直接跑在 FX 线程上；返回 JSON 字符串，失败返回 null 交给前端回退 JS 实现。
     */
    public String reviewOp(String op, String payloadJson) {
        return ReviewOps.handle(op, payloadJson);
    }

    /** 前端 localStorage 的写穿：只保留在内存并异步落盘，不阻塞 FX 线程。 */
    public void putStorage(String key, String value) {
        if (key == null) return;
        if (value == null) store.remove(key);
        else store.put(key, value);
    }

    public void log(String level, String message) {
        System.out.println("[web " + level + "] " + message);
    }

    public void alert(String message) {
        System.out.println("[alert] " + message);
        if (!interactive) return;
        Platform.runLater(() -> {
            try {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.initOwner(owner);
                alert.setTitle("提示");
                alert.setHeaderText(null);
                alert.setContentText(message);
                alert.show();
            } catch (Exception e) {
                System.err.println("显示提示框失败: " + e.getMessage());
            }
        });
    }

    /** 同步确认框：JS 的 confirm() 需要立即拿到布尔值。 */
    public boolean confirm(String message) {
        if (!interactive) return true;
        try {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.initOwner(owner);
            alert.setTitle("确认");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
        } catch (Exception e) {
            System.err.println("确认框失败（按取消处理）: " + e.getMessage());
            return false;
        }
    }

    public String chooseFile() {
        if (!interactive) return null;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择词库文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "文本 / CSV", "*.txt", "*.csv", "*.tsv", "*.md"));
        File file = chooser.showOpenDialog(owner);
        return file == null ? null : file.getAbsolutePath();
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    // ==================== 内部工具 ====================

    private void complete(String id, boolean ok, String payload) {
        if (Boolean.getBoolean("sentence.debug")) {
            System.out.println("[bridge] " + id + " ok=" + ok + " payload="
                    + (payload == null ? "null" : payload.substring(0, Math.min(120, payload.length()))));
        }
        String script = "window.__bridgeResolve(" + Json.quote(id) + "," + ok + "," + Json.quote(payload) + ");";
        Platform.runLater(() -> {
            try {
                engine.executeScript(script);
            } catch (Exception e) {
                System.err.println("回传 JS 失败: " + e.getMessage());
            }
        });
    }

    /**
     * 本地词典优先：命中 TOEFL_abridged 就用文件里的音标、词性和各条释义，
     * 词典里没有的词（比如 CEFR 基础词）保持模型给的 pos/ipa/meaning。
     */
    private static List<Map<String, Object>> applyDictionary(List<Map<String, Object>> list,
                                                             List<String> requested,
                                                             Dictionary dictionary) {
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> item = list.get(i);
            String word = item.get("word") == null ? null : item.get("word").toString();
            Dictionary.Entry entry = dictionary.lookup(word);
            if (entry == null && i < requested.size()) entry = dictionary.lookup(requested.get(i));
            if (entry == null) continue;
            item.put("ipa", "[" + entry.ipa() + "]");
            List<Map<String, String>> senses = new ArrayList<>();
            for (Dictionary.Sense s : entry.senses()) {
                Map<String, String> sense = new LinkedHashMap<>();
                sense.put("pos", s.pos());
                sense.put("meaning", s.meaning());
                senses.add(sense);
            }
            item.put("senses", senses);
            if (word == null || word.isBlank()) {
                item.put("word", i < requested.size() ? requested.get(i) : "");
            }
        }
        return list;
    }

    /** 解析例句 JSON；解析失败或没有有效条目时返回空列表（调用方退回纯文本）。 */
    private static List<Map<String, Object>> parseSentences(String content) {
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode data;
        try {
            data = JsonLoose.parse(content);
        } catch (Exception e) {
            return out;
        }
        for (JsonNode s : data.path("sentences")) {
            if (s == null || !s.isObject()) continue;
            if (!s.path("en").isTextual() || s.path("en").asText().isBlank()) continue;
            if (!s.path("zh").isTextual() || s.path("zh").asText().isBlank()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("word", s.path("word").isTextual() ? s.path("word").asText().trim() : "");
            item.put("en", s.path("en").asText().trim());
            item.put("zh", s.path("zh").asText().trim());
            item.put("pos", s.path("pos").isTextual() ? s.path("pos").asText().trim() : "");
            item.put("ipa", s.path("ipa").isTextual() ? s.path("ipa").asText().trim() : "");
            item.put("meaning", s.path("meaning").isTextual() ? s.path("meaning").asText().trim() : "");
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> parseReviewQuestions(String content) {
        JsonNode data = JsonLoose.parse(content);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode q : data.path("questions")) {
            if (q == null || !q.isObject()) continue;
            if (!q.path("word").isTextual() || !q.path("sentence").isTextual() || !q.path("meaning").isTextual()) {
                continue;
            }
            JsonNode distractors = q.path("distractors");
            if (!distractors.isArray() || distractors.size() < 3) continue;
            List<String> wrong = new ArrayList<>();
            for (int i = 0; i < 3; i++) wrong.add(distractors.get(i).asText());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("word", q.path("word").asText());
            item.put("sentence", q.path("sentence").asText());
            item.put("pos", q.path("pos").isTextual() ? q.path("pos").asText() : "");
            item.put("meaning", q.path("meaning").asText());
            item.put("distractors", wrong);
            out.add(item);
        }
        return out;
    }

    private static Map<String, Object> upsertResult(CustomBankStore.UpsertResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", result.id);
        map.put("added", result.added);
        map.put("total", result.total);
        return map;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) out.add(item.asText());
            }
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
