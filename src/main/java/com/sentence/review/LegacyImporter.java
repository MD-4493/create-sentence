package com.sentence.review;

import com.sentence.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 从旧版 localStorage（LevelDB）里抢救学习记录。
 *
 * 原理：localStorage 把 "键\x01值" 以明文写进 leveldb 的 .log/.ldb 文件，
 * 而本应用每次都是整份 JSON 覆盖写入，所以"文件里最后一次出现的 learnedDays"就是当前进度。
 * 这里只读不写，找不到就返回空 map，不影响启动。
 */
public final class LegacyImporter {
    private static final String[] KEYS = {"learnedDays", "learnedWords"};

    private LegacyImporter() {
    }

    public static Map<String, String> fromLevelDb(Path leveldbDir) {
        Map<String, String> found = new LinkedHashMap<>();
        if (leveldbDir == null || !Files.isDirectory(leveldbDir)) return found;

        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(leveldbDir)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            return found;
        }
        files.sort(Comparator.comparing(p -> {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }));

        for (Path file : files) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                continue;
            }
            if (bytes.length == 0) continue;
            // 用 ISO-8859-1 保持"一字节一字符"，便于按偏移量还原 UTF-8 子串
            String text = new String(bytes, StandardCharsets.ISO_8859_1);

            for (String key : KEYS) {
                String candidate = lastJsonAfter(text, key);
                if (candidate == null) continue;
                if (Json.treeOrNull(candidate) == null) continue;
                found.put(key, candidate);
            }
        }
        return found;
    }

    private static String lastJsonAfter(String text, String key) {
        String marker = key + "\u0001";
        int idx = text.lastIndexOf(marker);
        if (idx < 0) {
            idx = text.lastIndexOf(key);
            if (idx < 0) return null;
        }
        int start = text.indexOf('{', idx);
        if (start < 0) return null;
        int end = balancedEnd(text, start);
        if (end < 0) return null;

        String raw = text.substring(start, end + 1);
        byte[] utf8 = raw.getBytes(StandardCharsets.ISO_8859_1);
        return new String(utf8, StandardCharsets.UTF_8);
    }

    /** 从 start 处的 '{' 开始找配对的 '}'，跳过字符串内的括号与转义。 */
    private static int balancedEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
