package com.sentence.words;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentence.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 词库目录：内置词库（项目自带 word-banks/）+ 自定义词库（用户数据目录）。
 * 行为对应原版 get-word-banks / get-words / delete-custom-bank。
 */
public final class WordBankRepository {
    private final Path banksDir;
    private final CustomBankStore custom;

    public WordBankRepository(Path appRoot, CustomBankStore custom) {
        this.banksDir = appRoot.resolve("word-banks");
        this.custom = custom;
    }

    /** 对应 get-word-banks：自定义词库在前（名称带 ⭐），内置词库在后。 */
    public List<Map<String, Object>> listBanks() {
        List<Map<String, Object>> customBanks = new ArrayList<>();
        for (CustomBankStore.Bank b : custom.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id);
            m.put("name", "⭐ " + b.name + "（" + b.words.size() + "）");
            m.put("custom", true);
            customBanks.add(m);
        }

        List<Map<String, Object>> builtin = new ArrayList<>();
        Path index = banksDir.resolve("index.json");
        try {
            JsonNode root = Json.tree(Files.readString(index, StandardCharsets.UTF_8));
            JsonNode arr = root.path("banks");
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", node.path("id").asText(""));
                    m.put("name", node.path("name").asText(""));
                    m.put("category", node.path("category").asText(""));
                    m.put("level", node.path("level").asText(""));
                    m.put("wordCount", node.path("wordCount").asInt(0));
                    m.put("file", node.path("file").asText(""));
                    m.put("description", node.path("description").asText(""));
                    builtin.add(m);
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("内置词库读取失败: " + e.getMessage());
        }

        List<Map<String, Object>> all = new ArrayList<>(customBanks);
        all.addAll(builtin);
        return all;
    }

    /** 对应 get-words。 */
    public Map<String, Object> getWords(String bankId) {
        if (bankId == null || bankId.isBlank()) {
            throw new IllegalArgumentException("词库 id 不能为空");
        }
        if (bankId.startsWith("custom:")) {
            CustomBankStore.Bank bank = custom.find(bankId);
            if (bank == null) throw new IllegalArgumentException("词库不存在: " + bankId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", bank.id);
            m.put("name", bank.name);
            m.put("words", bank.words);
            return m;
        }

        Path index = banksDir.resolve("index.json");
        try {
            JsonNode root = Json.tree(Files.readString(index, StandardCharsets.UTF_8));
            for (JsonNode node : root.path("banks")) {
                if (!bankId.equals(node.path("id").asText(""))) continue;
                String file = node.path("file").asText("");
                JsonNode data = Json.tree(Files.readString(banksDir.resolve(file), StandardCharsets.UTF_8));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", data.path("id").asText(bankId));
                m.put("name", data.path("name").asText(node.path("name").asText("")));
                List<String> words = new ArrayList<>();
                for (JsonNode w : data.path("words")) {
                    if (w.isTextual()) words.add(w.asText());
                }
                m.put("words", words);
                return m;
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("读取内置词库失败: " + e.getMessage(), e);
        }
        throw new IllegalArgumentException("词库不存在: " + bankId);
    }

    public CustomBankStore customStore() {
        return custom;
    }

    public Path banksDir() {
        return banksDir;
    }
}
