package com.sentence;

import com.sentence.llm.DeepSeekClient;

/** 真实调用一次 DeepSeek，用于验证 key / 端点 / 模型名是否可用（消耗极少 tokens）。 */
public final class LiveTest {
    private LiveTest() {
    }

    public static int run(CliArgs cli) {
        Settings settings = Settings.forArgs(cli);
        System.out.println("端点: " + settings.baseUrl);
        System.out.println("模型: " + settings.model);
        System.out.println("Key : " + (settings.hasApiKey() ? "已配置" : "缺失"));
        try {
            String reply = new DeepSeekClient(settings)
                    .chat("你是一个测试助手。", "请只回复两个字：正常", 0.0, false);
            String oneLine = reply.replace("\r", " ").replace("\n", " ").trim();
            System.out.println("LIVE_OK " + (oneLine.length() > 80 ? oneLine.substring(0, 80) : oneLine));
            return 0;
        } catch (RuntimeException e) {
            System.out.println("LIVE_FAIL " + e.getMessage());
            return 1;
        }
    }
}
