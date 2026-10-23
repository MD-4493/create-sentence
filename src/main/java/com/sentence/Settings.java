package com.sentence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时配置：应用根目录（原前端文件所在目录）、数据目录、API 配置。
 *
 * 数据目录默认与 Electron 版保持一致（%APPDATA%\create-sentence），
 * 这样已有的 custom-banks.json 可以原样被 Java 版复用。
 */
public final class Settings {
    public static final String DEFAULT_MODEL = "deepseek-flash";
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";

    public final Path appRoot;
    public final Path dataDir;
    public final String apiKey;
    public final String baseUrl;
    public final String model;
    public final boolean interactive;

    private Settings(Path appRoot, Path dataDir, String apiKey, String baseUrl, String model, boolean interactive) {
        this.appRoot = appRoot;
        this.dataDir = dataDir;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.interactive = interactive;
    }

    public static Settings forArgs(CliArgs args) {
        Path appRoot = resolveAppRoot(args.appRoot);
        Path dataDir = resolveDataDir(args.dataDir);
        Map<String, String> env = readEnvFile(appRoot.resolve(".env"));
        if (env.isEmpty()) {
            env = readEnvFile(Paths.get("").toAbsolutePath().resolve(".env"));
        }
        if (env.isEmpty()) {
            // 打包后也可把 .env 放在数据目录（%APPDATA%\create-sentence\.env）
            env = readEnvFile(dataDir.resolve(".env"));
        }

        String apiKey = firstNonBlank(
                System.getenv("DEEPSEEK_API_KEY"),
                env.get("DEEPSEEK_API_KEY"));
        String baseUrl = firstNonBlank(
                System.getenv("DEEPSEEK_BASE_URL"),
                env.get("DEEPSEEK_BASE_URL"),
                DEFAULT_BASE_URL);
        String model = firstNonBlank(
                args.model,
                System.getenv("DEEPSEEK_MODEL"),
                env.get("DEEPSEEK_MODEL"),
                DEFAULT_MODEL);

        return new Settings(appRoot, dataDir, apiKey, trimTrailingSlash(baseUrl), model, !args.noDialogs);
    }

    /** 前端文件所在目录：必须包含 index.html。 */
    private static Path resolveAppRoot(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return normalize(explicit);
        }
        Path cwd = Paths.get("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("index.html"))) {
            return cwd.normalize();
        }
        // 开发目录：java-port\ 下，前端在 java-port\app\
        if (Files.isRegularFile(cwd.resolve("app").resolve("index.html"))) {
            return cwd.resolve("app").normalize();
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("index.html"))) {
            return parent.normalize();
        }
        if (parent != null && Files.isRegularFile(parent.resolve("app").resolve("index.html"))) {
            return parent.resolve("app").normalize();
        }
        // 打包后（jpackage app-image）：index.html 与 app.jar 同目录
        Path fromJar = appRootFromCodeSource();
        if (fromJar != null) {
            return fromJar;
        }
        throw new IllegalStateException(
                "找不到 index.html。请用 --app-root=<前端目录> 指定（开发目录下前端在 java-port\\app）"
                        + "，当前目录: " + cwd);
    }

    private static Path appRootFromCodeSource() {
        try {
            var source = Settings.class.getProtectionDomain().getCodeSource();
            if (source == null) return null;
            Path location = Paths.get(source.getLocation().toURI());
            Path dir = Files.isDirectory(location) ? location : location.getParent();
            if (dir != null && Files.isRegularFile(dir.resolve("index.html"))) {
                return dir.normalize();
            }
        } catch (Exception ignored) {
            // 取不到代码位置时按"未找到"处理
        }
        return null;
    }

    private static Path resolveDataDir(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return normalize(explicit);
        }
        String override = System.getProperty("sentence.dataDir");
        if (override != null && !override.isBlank()) {
            return normalize(override);
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData, "create-sentence").toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".create-sentence").toAbsolutePath().normalize();
    }

    private static Path normalize(String p) {
        return Paths.get(p).toAbsolutePath().normalize();
    }

    private static Map<String, String> readEnvFile(Path file) {
        Map<String, String> map = new HashMap<>();
        if (file == null || !Files.isRegularFile(file)) return map;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                String key = t.substring(0, eq).trim();
                String value = t.substring(eq + 1).trim();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                map.put(key, value);
            }
        } catch (IOException e) {
            System.err.println("读取 .env 失败: " + e.getMessage());
        }
        return map;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c.trim();
        }
        return null;
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public void ensureDataDir() throws IOException {
        Files.createDirectories(dataDir);
    }

    @Override
    public String toString() {
        return "Settings{appRoot=" + appRoot + ", dataDir=" + dataDir
                + ", model=" + model + ", baseUrl=" + baseUrl
                + ", apiKey=" + (hasApiKey() ? "已配置" : "缺失") + "}";
    }

    /** 供测试使用：显式构造。 */
    static Settings of(Path appRoot, Path dataDir, String apiKey, String baseUrl, String model, boolean interactive) {
        return new Settings(appRoot, dataDir, apiKey, baseUrl, model, interactive);
    }
}
