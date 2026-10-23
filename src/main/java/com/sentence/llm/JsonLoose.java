package com.sentence.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentence.Json;

/**
 * 宽松 JSON 解析，对应原版 parseJsonLoose：
 * 去掉 ```json 围栏，截取第一个 '{' 到最后一个 '}'，再解析。
 */
public final class JsonLoose {
    private static final String FENCE_HEAD = "^```(?:json)?\\s*";
    private static final String FENCE_TAIL = "\\s*```$";

    private JsonLoose() {
    }

    public static JsonNode parse(String text) {
        String t = text == null ? "" : text.trim();
        t = t.replaceFirst("(?is)" + FENCE_HEAD, "").replaceFirst("(?s)" + FENCE_TAIL, "");
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            t = t.substring(start, end + 1);
        }
        return Json.tree(t);
    }
}
