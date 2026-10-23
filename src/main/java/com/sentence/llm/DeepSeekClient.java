package com.sentence.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sentence.Json;
import com.sentence.Settings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * DeepSeek 客户端：直接用 JDK 的 HttpClient 调 OpenAI 兼容接口，
 * 不需要 openai 这类 SDK，也没有任何额外依赖。
 */
public final class DeepSeekClient {
    private final HttpClient http;
    private final Settings settings;

    public DeepSeekClient(Settings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String chat(String system, String user, double temperature, boolean jsonMode) {
        if (!settings.hasApiKey()) {
            throw new LlmException("DEEPSEEK_API_KEY 未配置。请在项目根目录的 .env 文件中填入你的 DeepSeek API key。");
        }

        ObjectNode body = Json.object();
        body.put("model", settings.model);
        ArrayNode messages = body.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", system);
        ObjectNode usr = messages.addObject();
        usr.put("role", "user");
        usr.put("content", user);
        body.put("temperature", temperature);
        if (jsonMode) {
            body.putObject("response_format").put("type", "json_object");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(settings.baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + settings.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = sendWithRetry(request);

        int status = response.statusCode();
        String text = response.body() == null ? "" : response.body();
        if (status != 200) {
            throw new LlmException(describeError(status, text));
        }

        JsonNode root = Json.treeOrNull(text);
        if (root == null) {
            throw new LlmException("接口返回了无法解析的内容：" + snippet(text));
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new LlmException("接口没有返回内容：" + snippet(text));
        }
        return content.asText();
    }

    /**
     * 与原 openai SDK 类似的重试：网络异常或 429/5xx 时重试一次。
     * 4xx（除 429）属于请求本身的问题，重试没有意义。
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request) {
        IOException lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (attempt < 2 && retryable(response.statusCode())) {
                    System.err.println("DeepSeek 返回 " + response.statusCode() + "，600ms 后重试一次");
                    sleep(600);
                    continue;
                }
                return response;
            } catch (IOException e) {
                lastError = e;
                if (attempt < 2) {
                    System.err.println("网络请求失败（" + e.getMessage() + "），600ms 后重试一次");
                    sleep(600);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("请求被中断", e);
            }
        }
        throw new LlmException("网络请求失败：" + (lastError == null ? "未知错误" : lastError.getMessage()),
                lastError);
    }

    private static boolean retryable(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String describeError(int status, String body) {
        String detail = snippet(body);
        return switch (status) {
            case 401 -> "API Key 无效或已过期（401）。请检查 .env 中的 DEEPSEEK_API_KEY。";
            case 402 -> "账户余额不足（402）。请到 DeepSeek 控制台充值。";
            case 403 -> "没有访问权限（403）。";
            case 404 -> "接口或模型不存在（404）。请检查模型名（当前: " + settings.model + "）。";
            case 429 -> "请求过于频繁（429），请稍后再试。";
            default -> status >= 500
                    ? "DeepSeek 服务端错误（" + status + "），请稍后再试。"
                    : "请求失败（" + status + "）：" + detail;
        };
    }

    private static String snippet(String text) {
        if (text == null) return "";
        String t = text.strip();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    public String model() {
        return settings.model;
    }
}
