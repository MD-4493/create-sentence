package com.sentence.llm;

import java.util.List;

/** 提示词，逐字沿用原版 main.js，保证输出格式与现有 UI 渲染逻辑兼容。 */
public final class Prompts {
    private Prompts() {
    }

    public static final String SENTENCE_SYSTEM =
            "你是一个耐心的英语老师，专门为学习者造简单实用的例句，注重语境自然。";

    public static final String CORRECTION_SYSTEM =
            "你是一个耐心的英语老师，讲解清晰、语气鼓励、用例贴近生活。";

    public static final String REVIEW_SYSTEM =
            "你是一个严谨的英语老师，擅长出词义辨析选择题。你只输出合法 JSON。";

    public static final String BANK_SYSTEM =
            "你是一位英语词汇教学专家，只输出合法 JSON。";

    /** 生成例句（JSON：英文例句 + 中文翻译 + 词性/音标/释义）。 */
    public static String sentences(List<String> words) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下英语单词各造 1 个简单自然的英文例句，并给出中文翻译和这个词本身的词典信息。\n\n");
        sb.append("要求：\n");
        sb.append("1. 句子难度适合英语中级学习者（不要过于简单也不要太复杂）。\n");
        sb.append("2. 英文例句（en）里，把这个单词本身或它的变形用 **双星号** 标出。\n");
        sb.append("3. 中文翻译（zh）里，只把\"对应这个单词意思的那一小段\"用 **双星号** 标出，不要标整句；两处标出的必须是同一个词。\n");
        sb.append("4. pos 写这个词在句中的词性缩写（如 n. / v. / adj. / adv. / prep.）。\n");
        sb.append("5. ipa 写音标，用 /.../ 包裹（英式或美式均可），不要写中文。\n");
        sb.append("6. meaning 写这个词本身最核心的中文释义（2-4 个常用义项，用；分隔），不要为了句子通顺而改写；即使句中用的是引申义，也仍保留核心义项。\n");
        sb.append("7. 只输出 JSON，不要任何其他文字，格式：\n");
        sb.append("{\"sentences\":[{\"word\":\"原单词\",\"en\":\"英文例句，目标词用**标出**\",\"zh\":\"中文翻译，对应意思用**标出**\",\"pos\":\"v.\",\"ipa\":\"/əˈbændən/\",\"meaning\":\"放弃；抛弃\"}]}\n\n");
        sb.append("单词列表：\n");
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append("• ").append(words.get(i));
        }
        return sb.toString();
    }

    /** 纠错 + 用法提示。 */
    public static String correction(String sentence, List<String> keywords) {
        String keywordText = (keywords != null && !keywords.isEmpty())
                ? "\n\n参考关键词：" + String.join(", ", keywords) + "（不要求全部使用）"
                : "";
        return "请检查学习者造的英语句子，并按下面的格式给出反馈（用中文回复）。\n\n"
                + "学习者造的句子：" + sentence + keywordText + "\n\n"
                + "请按以下结构返回：\n\n"
                + "【语法检查】\n"
                + "- 如果有错误：指出错误并给出正确版本\n"
                + "- 如果没有错误：说明句子语法正确\n\n"
                + "【关键词用法】\n"
                + "- 如果句中包含关键词：解释该词在句中的具体用法\n"
                + "- 如果句中没有关键词：跳过这一节\n\n"
                + "【改进建议】\n"
                + "- 给出 1 个更自然/更地道的版本（如有需要）\n\n"
                + "【小贴士】\n"
                + "- 用 1-2 句话给一个相关的学习提示（如固定搭配、易错点、文化背景）";
    }

    /** 复习选择题。 */
    public static String reviewQuestions(List<String> words) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为下面每个英语单词出一道\"词义选择题\"。\n\n");
        sb.append("要求：\n");
        sb.append("1. 为每个单词写 1 个英文例句，难度适合中级学习者，句子里不能出现任何中文，也不能给翻译。\n");
        sb.append("2. 句中该单词的意思必须是它【最常用的意思】，并且上下文要足够清楚，让学习者能据此判断词义。\n");
        sb.append("3. 用 **双星号** 把句中的核心词（可以是变形，如复数、过去式）标出来。\n");
        sb.append("4. \"pos\" 写词性缩写（如 n. / v. / adj. / adv. / prep.）；如果该义项有多个词性，写最常用的那个。\n");
        sb.append("5. \"meaning\" 写该单词在句中的中文含义（简洁，例如\"建议；提议\"）。\n");
        sb.append("6. \"distractors\" 写 3 个错误的中文含义：看起来合理、词性风格与正确答案相近，但必须明确错误，不能是正确答案的近义说法。\n");
        sb.append("7. 只输出 JSON，不要任何其他文字，格式如下：\n");
        sb.append("{\"questions\":[{\"word\":\"原单词\",\"sentence\":\"英文例句，核心词用**标出**\",\"pos\":\"n.\",\"meaning\":\"正确中文含义\",\"distractors\":[\"错误含义1\",\"错误含义2\",\"错误含义3\"]}]}\n\n");
        sb.append("单词列表：\n");
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append("- ").append(words.get(i));
        }
        return sb.toString();
    }

    /** AI 生成词库。 */
    public static String wordBank(String description, int count) {
        return "请按下面的描述，列出 " + count + " 个英语词汇，用来给学习者扩充生词。\n\n"
                + "描述：" + description + "\n\n"
                + "要求：\n"
                + "1. 优先选择有一定难度、对学习者\"真正的生词\"价值高的词（高于基础词汇，避免 the、go、house 这类简单词）。\n"
                + "2. 全部用小写原形（动词原形、名词单数），不要重复，不要带释义或编号。\n"
                + "3. 只输出 JSON，格式：{\"name\":\"给这个词库起的简短中文名（10 字以内）\",\"words\":[\"word1\",\"word2\"]}";
    }
}
