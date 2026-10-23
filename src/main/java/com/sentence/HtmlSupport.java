package com.sentence;

/**
 * HTML 预处理。
 *
 * JavaFX 的 WebEngine.loadContent(String) 在传递非 BMP 字符（emoji 等代理对）时
 * 会编码成非法 UTF-8，页面上变成 U+FFFD（显示为方框）。
 * 这里把代理对改写成 HTML 数字实体（纯 ASCII），由 HTML 解析器还原；
 * <script> 与 <style> 是原始文本元素，实体不会被解码，因此原样跳过。
 */
public final class HtmlSupport {
    private HtmlSupport() {
    }

    public static String escapeAstral(String html) {
        if (html == null || html.isEmpty()) return "";
        StringBuilder out = new StringBuilder(html.length() + 32);
        int i = 0;
        while (i < html.length()) {
            boolean script = html.regionMatches(true, i, "<script", 0, 7);
            boolean style = html.regionMatches(true, i, "<style", 0, 6);
            if (script || style) {
                String close = script ? "</script" : "</style";
                int closeAt = indexOfIgnoreCase(html, close, i + 1);
                if (closeAt < 0) {
                    out.append(html, i, html.length());
                    break;
                }
                int endTag = html.indexOf('>', closeAt);
                if (endTag < 0) endTag = html.length() - 1;
                out.append(html, i, endTag + 1);
                i = endTag + 1;
                continue;
            }
            int cp = html.codePointAt(i);
            if (cp > 0xFFFF) {
                out.append("&#x").append(Integer.toHexString(cp).toUpperCase()).append(';');
            } else {
                out.append((char) cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        int max = haystack.length() - needle.length();
        for (int i = Math.max(0, from); i <= max; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }
}
