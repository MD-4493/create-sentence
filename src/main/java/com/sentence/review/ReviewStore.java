package com.sentence.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentence.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 学习记录的 Java 侧持久化。
 *
 * 前端仍然调用 ReviewCore.load(localStorage) / save(localStorage, state)，
 * 但 index.html 注入的桥接脚本会把这两个调用的存储参数替换成本类背后的 JSON 文件，
 * 因此 renderer.js / review-core.js 一行都不用改。
 *
 * 文件：数据目录/review-state.json，结构 { "learnedDays": "...", "learnedWords": "..." }
 */
public final class ReviewStore implements ReviewCore.KeyValue {
    private final Path file;
    private final Map<String, String> values = new LinkedHashMap<>();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "review-store-writer");
        t.setDaemon(true);
        return t;
    });
    private boolean closed;

    public ReviewStore(Path dataDir) {
        this.file = dataDir.resolve("review-state.json");
    }

    public synchronized void loadOrImport(Path legacyLevelDbDir) {
        if (Files.isRegularFile(file)) {
            try {
                JsonNode root = Json.tree(Files.readString(file, StandardCharsets.UTF_8));
                if (root.isObject()) {
                    root.fields().forEachRemaining(e -> {
                        if (e.getValue().isTextual()) values.put(e.getKey(), e.getValue().asText());
                    });
                }
                return;
            } catch (Exception e) {
                System.err.println("读取 review-state.json 失败，尝试旧版导入: " + e.getMessage());
            }
        }
        Map<String, String> imported = LegacyImporter.fromLevelDb(legacyLevelDbDir);
        if (!imported.isEmpty()) {
            values.putAll(imported);
            System.out.println("已从旧版 Electron 数据导入学习记录: " + imported.keySet());
            flushNow();
        }
    }

    @Override
    public synchronized String get(String key) {
        return values.get(key);
    }

    @Override
    public synchronized void put(String key, String value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
        scheduleFlush();
    }

    @Override
    public synchronized void remove(String key) {
        values.remove(key);
        scheduleFlush();
    }

    public synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(values);
    }

    private synchronized void scheduleFlush() {
        if (closed) {
            flushNow();
            return;
        }
        writer.schedule(this::flushNow, 250, TimeUnit.MILLISECONDS);
    }

    public synchronized void flushNow() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.pretty(new LinkedHashMap<>(values)), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("保存学习记录失败: " + e.getMessage());
        }
    }

    public synchronized void close() {
        closed = true;
        flushNow();
        writer.shutdown();
    }

    public Path file() {
        return file;
    }
}
