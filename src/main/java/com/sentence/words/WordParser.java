package com.sentence.words;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 词表解析，逐条对应原版 main.js 的 parseWords(text, spaceSeparated)。
 *
 * 规则：
 *  - 含中文的行（如 "abandon v. 放弃" 或 "abandon,放弃"）只取行首的英文单词；
 *  - 纯英文行按逗号/分号/制表符切分，允许短语（含空格）；spaceSeparated=true 时空格也作分隔。
 */
public final class WordParser {
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern LEADING_EN = Pattern.compile("^\\s*\"?([A-Za-z][A-Za-z'\\-]*)");
    private static final Pattern VALID = Pattern.compile("^[a-z][a-z'\\- ]*$");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u3000]+");
    private static final Pattern SEP_SPACE_OR_PUNCT = Pattern.compile("[\\s,;，；]+");
    private static final Pattern SEP_PUNCT = Pattern.compile("[,;\\t，；]");

    private WordParser() {
    }

    public static List<String> parse(String text, boolean spaceSeparated) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String source = text == null ? "" : text;
        for (String line : source.split("\\r?\\n", -1)) {
            if (CJK.matcher(line).find()) {
                Matcher m = LEADING_EN.matcher(line);
                if (m.find()) add(m.group(1), out, seen);
            } else {
                Pattern sep = spaceSeparated ? SEP_SPACE_OR_PUNCT : SEP_PUNCT;
                for (String part : sep.split(line, -1)) {
                    add(part, out, seen);
                }
            }
        }
        return out;
    }

    /** 与原版 add() 等价：规范空白、转小写、校验、去重并保序。 */
    private static void add(String raw, List<String> out, Set<String> seen) {
        if (raw == null) return;
        String word = WHITESPACE.matcher(raw.trim()).replaceAll(" ");
        word = word.toLowerCase(Locale.ROOT);
        if (!VALID.matcher(word).matches() || word.length() > 40 || seen.contains(word)) return;
        seen.add(word);
        out.add(word);
    }
}
