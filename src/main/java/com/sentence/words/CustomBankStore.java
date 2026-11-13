package com.sentence.words;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentence.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 自定义词库存储（文件位置沿用旧版数据目录）：
 *   %APPDATA%\create-sentence\custom-banks.json
 * 结构: { "banks": [ { "id": "...", "name": "...", "words": ["..."] } ] }
 */
public final class CustomBankStore {
    public static final String MY_WORDS_ID = "custom:my-words";

    public static final class Bank {
        public String id;
        public String name;
        public List<String> words = new ArrayList<>();

        Bank() {
        }

        Bank(String id, String name, List<String> words) {
            this.id = id;
            this.name = name;
            this.words = words;
        }
    }

    public static final class UpsertResult {
        public final String id;
        public final int added;
        public final int total;

        UpsertResult(String id, int added, int total) {
            this.id = id;
            this.added = added;
            this.total = total;
        }
    }

    private final Path file;
    private List<Bank> banks;
    private final Object lock = new Object();

    public CustomBankStore(Path dataDir) {
        this.file = dataDir.resolve("custom-banks.json");
    }

    public Path file() {
        return file;
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    public List<Bank> list() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(load()));
        }
    }

    public Bank find(String id) {
        synchronized (lock) {
            for (Bank b : load()) {
                if (b.id.equals(id)) return b;
            }
            return null;
        }
    }

    /** 与原版 upsertBank 等价。 */
    public UpsertResult upsert(String id, String name, List<String> words, boolean merge) {
        synchronized (lock) {
            List<Bank> all = load();
            for (Bank exist : all) {
                if (!exist.id.equals(id)) continue;
                Set<String> set = new LinkedHashSet<>(exist.words);
                int before = set.size();
                set.addAll(words);
                exist.words = merge ? new ArrayList<>(set) : new ArrayList<>(words);
                exist.name = name;
                save(all);
                return new UpsertResult(id, set.size() - before, exist.words.size());
            }
            Bank bank = new Bank(id, name, new ArrayList<>(words));
            all.add(bank);
            save(all);
            return new UpsertResult(id, words.size(), words.size());
        }
    }

    public void delete(String id) {
        synchronized (lock) {
            List<Bank> all = load();
            all.removeIf(b -> b.id.equals(id));
            save(all);
        }
    }

    private List<Bank> load() {
        if (banks != null) return banks;
        banks = new ArrayList<>();
        if (!Files.isRegularFile(file)) return banks;
        try {
            JsonNode root = Json.tree(Files.readString(file, StandardCharsets.UTF_8));
            JsonNode arr = root.path("banks");
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    if (!node.isObject()) continue;
                    Bank bank = new Bank();
                    bank.id = node.path("id").asText("");
                    bank.name = node.path("name").asText("");
                    JsonNode words = node.path("words");
                    if (words.isArray()) {
                        for (JsonNode w : words) {
                            if (w.isTextual()) bank.words.add(w.asText());
                        }
                    }
                    if (!bank.id.isEmpty()) banks.add(bank);
                }
            }
        } catch (Exception e) {
            System.err.println("读取自定义词库失败（按空库处理）: " + e.getMessage());
        }
        return banks;
    }

    private void save(List<Bank> all) {
        ObjectNode root = Json.object();
        ArrayNode arr = root.putArray("banks");
        for (Bank bank : all) {
            ObjectNode node = arr.addObject();
            node.put("id", bank.id);
            node.put("name", bank.name);
            ArrayNode words = node.putArray("words");
            for (String w : bank.words) words.add(w);
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.pretty(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            banks = all;
        } catch (IOException e) {
            throw new IllegalStateException("保存自定义词库失败: " + e.getMessage(), e);
        }
    }
}
