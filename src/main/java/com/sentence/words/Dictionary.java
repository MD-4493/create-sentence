package com.sentence.words;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地词典：解析项目根目录的 TOEFL_abridged.txt。
 *
 * 行格式：word   [音标]   vt. 释义1,释义2  vi. 释义3
 * 一个词可以有多条义项，每条带自己的词性（"vt. vi. 邻接" 这种连续词性会合并成 "vt./vi."）；
 * 音标沿用文件里的写法（如 [ə'beit]）原样展示，不做改写。
 */
public final class Dictionary {
    public record Sense(String pos, String meaning) {
    }

    public record Entry(String ipa, List<Sense> senses) {
    }

    private static final Pattern LINE =
            Pattern.compile("^(\\S+)\\s+\\[([^\\]]*)\\]+(?:\\s+(.*))?$");
    /** 词性缩写；长的放前面，避免 "adj." 被 "a." 抢先匹配。 */
    private static final Pattern POS =
            Pattern.compile("(?:^|\\s)((?:vt|vi|adj|adv|prep|conj|pron|aux|art|num|int|pl|ad|n|v|a)\\.)");

    private final Map<String, Entry> entries = new HashMap<>();

    public static Dictionary load(Path file) {
        Dictionary dict = new Dictionary();
        if (file == null || !Files.isRegularFile(file)) {
            System.err.println("词典文件不存在，例句只使用模型给出的词性/音标/释义: " + file);
            return dict;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                dict.parseLine(line);
            }
        } catch (IOException e) {
            System.err.println("词典读取失败: " + e.getMessage());
        }
        return dict;
    }

    public Entry lookup(String word) {
        if (word == null) return null;
        return entries.get(word.trim().toLowerCase(Locale.ROOT));
    }

    public int size() {
        return entries.size();
    }

    private void parseLine(String raw) {
        String line = raw.strip();
        if (line.isEmpty()) return;
        Matcher m = LINE.matcher(line);
        if (!m.matches()) return;
        List<Sense> senses = splitSenses(m.group(3) == null ? "" : m.group(3));
        if (senses.isEmpty()) return;
        entries.put(m.group(1).toLowerCase(Locale.ROOT), new Entry(m.group(2).strip(), senses));
    }

    private static List<Sense> splitSenses(String text) {
        List<Integer> markerStarts = new ArrayList<>();
        List<Integer> contentStarts = new ArrayList<>();
        List<String> pos = new ArrayList<>();
        Matcher m = POS.matcher(text);
        while (m.find()) {
            pos.add(m.group(1));
            markerStarts.add(m.start() + m.group(0).indexOf(m.group(1)));
            contentStarts.add(m.end());
        }

        List<Sense> out = new ArrayList<>();
        if (markerStarts.isEmpty()) {
            String meaning = text.strip();
            if (!meaning.isEmpty()) out.add(new Sense("", meaning));
            return out;
        }

        StringBuilder pending = new StringBuilder();
        for (int i = 0; i < markerStarts.size(); i++) {
            int end = i + 1 < markerStarts.size() ? markerStarts.get(i + 1) : text.length();
            String meaning = text.substring(contentStarts.get(i), end).strip();
            if (meaning.isEmpty()) {
                if (pending.length() > 0) pending.append('/');
                pending.append(pos.get(i));
                continue;
            }
            String prefix = pending.length() > 0 ? pending + "/" + pos.get(i) : pos.get(i);
            out.add(new Sense(prefix, meaning));
            pending.setLength(0);
        }
        return out;
    }
}
