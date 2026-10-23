package com.sentence;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentence.llm.DeepSeekClient;
import com.sentence.review.ReviewStore;
import com.sentence.words.CustomBankStore;
import com.sentence.words.Dictionary;
import com.sentence.words.WordBankRepository;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.image.WritableImage;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;
import netscape.javascript.JSObject;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * JavaFX 版启动器：窗口里放一个 WebView，直接加载项目里原有的
 * index.html / styles.css / review-core.js / renderer.js —— 这些文件一行都不用改。
 *
 * Java 侧只做两件事：
 *   1. 注入一段桥接脚本，提供 window.api（原 preload.js 的 9 个 IPC 方法）；
 *   2. 把复习进度从 localStorage 换成 Java 持久化（renderer 无感知）。
 */
public final class App extends Application {
    private static CliArgs cli = new CliArgs();
    private static Settings settings;

    private Stage stage;
    private WebView view;
    private WebEngine engine;
    private JsBridge bridge;
    private ReviewStore reviewStore;
    private FileChannel instanceLockChannel;
    private FileLock instanceLock;

    public static void main(String[] args) {
        cli = CliArgs.parse(args);
        if (cli.debug) {
            System.setProperty("sentence.debug", "true");
        }
        if (cli.selfTest) {
            System.exit(SelfTest.run(cli));
        }
        if (cli.liveTest) {
            System.exit(LiveTest.run(cli));
        }
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        settings = Settings.forArgs(cli);
        System.out.println("[java] " + settings);
        try {
            settings.ensureDataDir();
        } catch (IOException e) {
            System.err.println("创建数据目录失败: " + e.getMessage());
        }

        if (!acquireInstanceLock()) {
            System.out.println("已有实例在运行，本次启动退出。");
            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                    "「造句学英语」已经在运行了。", ButtonType.OK);
            alert.setTitle("造句学英语");
            alert.setHeaderText(null);
            alert.showAndWait();
            Platform.exit();
            return;
        }

        CustomBankStore custom = new CustomBankStore(settings.dataDir);
        WordBankRepository repository = new WordBankRepository(settings.appRoot, custom);
        reviewStore = new ReviewStore(settings.dataDir);
        reviewStore.loadOrImport(settings.dataDir.resolve("Local Storage").resolve("leveldb"));
        DeepSeekClient llm = new DeepSeekClient(settings);
        Dictionary dictionary = Dictionary.load(settings.appRoot.resolve("TOEFL_abridged.txt"));

        view = new WebView();
        view.setContextMenuEnabled(false);
        engine = view.getEngine();
        engine.setJavaScriptEnabled(true);

        bridge = new JsBridge(engine, primaryStage, settings, llm, repository, custom, reviewStore,
                dictionary, settings.interactive);

        stage.setTitle("造句学英语");
        Scene scene = new Scene(view, 1100, 800);
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(600);

        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (Boolean.getBoolean("sentence.debug")) {
                System.out.println("[load] " + oldState + " -> " + newState);
            }
            if (newState == Worker.State.SUCCEEDED) {
                attachBridge();
            } else if (newState == Worker.State.FAILED) {
                Throwable error = engine.getLoadWorker().getException();
                System.err.println("页面加载失败: " + (error == null ? "未知错误" : error.getMessage()));
                if (error != null) error.printStackTrace();
            }
        });

        stage.show();
        // 必须在窗口显示后再加载：WebView 首次实现渲染时会重置引擎，
        // 若在 show() 之前 loadContent，加载会被取消（表现为空白页）。
        loadPage();

        if (cli.smokeTest) {
            runSmokeTest();
        }
    }

    @Override
    public void stop() {
        if (reviewStore != null) reviewStore.close();
        if (bridge != null) bridge.shutdown();
        releaseInstanceLock();
    }

    /**
     * 单实例：两个进程同时写 review-state.json 会互相覆盖。
     * 用数据目录里的 app.lock 做进程间排它锁；锁不可用时（例如只读目录）放行，不阻塞启动。
     */
    private boolean acquireInstanceLock() {
        try {
            Path lockFile = settings.dataDir.resolve("app.lock");
            instanceLockChannel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            instanceLock = instanceLockChannel.tryLock();
            return instanceLock != null;
        } catch (Exception e) {
            System.err.println("单实例锁不可用，继续启动: " + e.getMessage());
            return true;
        }
    }

    private void releaseInstanceLock() {
        Optional.ofNullable(instanceLock).ifPresent(lock -> {
            try {
                lock.release();
            } catch (IOException ignored) {
                // 释放失败不影响退出
            }
        });
        Optional.ofNullable(instanceLockChannel).ifPresent(channel -> {
            try {
                channel.close();
            } catch (IOException ignored) {
                // 关闭失败不影响退出
            }
        });
        instanceLock = null;
        instanceLockChannel = null;
    }

    // ==================== 页面加载 ====================

    private void loadPage() {
        Path index = settings.appRoot.resolve("index.html");
        String html;
        try {
            html = Files.readString(index, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 index.html 失败: " + index, e);
        }

        String core = resource("/bridge-core.js");
        String patch = resource("/bridge-patch.js");
        String snapshot = Json.write(reviewStore.snapshot());
        if (Boolean.getBoolean("sentence.debug")) {
            System.out.println("[java] storage snapshot (" + snapshot.length() + " chars): "
                    + snapshot.substring(0, Math.min(80, snapshot.length())));
        }

        String baseUrl = settings.appRoot.toUri().toString();
        if (!baseUrl.endsWith("/")) baseUrl = baseUrl + "/";

        // 注意：JavaFX 的 loadContent(html, baseUrl) 双参重载会取消加载，
        // 因此改用 <base href> + 单参 loadContent（已用 Probe 验证可行）。
        String smokeFlag = cli.smokeTest ? "<script>window.__smokeMode = true;</script>\n" : "";
        String early = smokeFlag + "<base href=\"" + baseUrl + "\">\n"
                + "<script>window.__bridgeStorageSnapshot = " + snapshot + ";</script>\n"
                + "<script>\n" + core + "\n</script>\n";
        int headTag = html.indexOf("<head>");
        if (headTag >= 0) {
            int insertAt = headTag + "<head>".length();
            html = html.substring(0, insertAt) + "\n" + early + html.substring(insertAt);
        } else {
            int firstScript = html.indexOf("<script");
            if (firstScript < 0) {
                throw new IllegalStateException("index.html 里既没有 <head> 也没有 <script>，无法注入桥接脚本");
            }
            html = html.substring(0, firstScript) + early + html.substring(firstScript);
        }

        String late = "<script>\n" + patch + "\n</script>\n";
        String rendererTag = "<script src=\"renderer.js\"></script>";
        int rendererAt = html.indexOf(rendererTag);
        if (rendererAt >= 0) {
            html = html.substring(0, rendererAt) + late + html.substring(rendererAt);
        } else {
            String coreTag = "<script src=\"review-core.js\"></script>";
            int coreAt = html.indexOf(coreTag);
            if (coreAt >= 0) {
                int insertAt = coreAt + coreTag.length();
                html = html.substring(0, insertAt) + "\n" + late + html.substring(insertAt);
            } else {
                System.err.println("警告: index.html 结构已变化，未找到 renderer.js / review-core.js 标签，"
                        + "复习进度将退回 WebView 原生 localStorage。");
            }
        }

        engine.loadContent(HtmlSupport.escapeAstral(html));
    }

    private void attachBridge() {
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember("__javaBridge", bridge);
            engine.executeScript("if (window.__bridgeAttach) { window.__bridgeAttach(); }");
            System.out.println("[java] 桥接已就绪, title=" + engine.getTitle());
        } catch (Exception e) {
            System.err.println("桥接注入失败: " + e.getMessage());
        }
    }

    private static String resource(String name) {
        try (InputStream in = App.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("缺少资源: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取资源失败: " + name, e);
        }
    }

    // ==================== 冒烟测试 ====================

    private void runSmokeTest() {
        Timeline timeline = new Timeline();
        int maxTicks = 200; // 200 * 250ms = 50s（复习要等大模型出题）
        int[] ticks = {0};

        KeyFrame frame = new KeyFrame(Duration.millis(250), event -> {
            ticks[0]++;
            String json = null;
            try {
                Object value = engine.executeScript(
                        "window.__smokeState ? window.__smokeState() : ''");
                json = value == null ? null : String.valueOf(value);
                if (Boolean.getBoolean("sentence.debug") && ticks[0] % 8 == 1) {
                    Object diag = engine.executeScript(
                            "document.readyState + '|' + document.documentURI + '|' + "
                                    + "(document.body ? document.body.innerHTML.length : -1) + '|' + "
                                    + "(window.__sentenceBridge ? 'bridge' : 'nobridge')");
                    System.out.println("[diag] " + diag);
                }
            } catch (Exception e) {
                System.err.println("读取冒烟状态失败: " + e.getMessage());
            }

            boolean ready = false;
            if (json != null && !json.isBlank()) {
                JsonNode node = Json.treeOrNull(json);
                if (node != null) {
                    boolean banksReady = node.path("bankOptions").asInt(0) > 0;
                    boolean reviewDone = !node.path("reviewVisible").asBoolean(false)
                            || node.path("reviewQuizVisible").asBoolean(false)
                            || node.path("reviewErrorVisible").asBoolean(false);
                    ready = banksReady && reviewDone;
                }
            }

            if (ready || ticks[0] >= maxTicks) {
                timeline.stop();
                finishSmoke(json, ticks[0] >= maxTicks);
            }
        });

        timeline.getKeyFrames().add(frame);
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    private void finishSmoke(String json, boolean timedOut) {
        String result;
        try {
            JsonNode node = json == null || json.isBlank() ? Json.object() : Json.tree(json);
            com.fasterxml.jackson.databind.node.ObjectNode obj =
                    node instanceof com.fasterxml.jackson.databind.node.ObjectNode o ? o : Json.object();
            obj.put("timedOut", timedOut);
            result = Json.write(obj);
        } catch (Exception e) {
            result = "{\"error\":" + Json.quote(e.getMessage()) + "}";
        }
        System.out.println("SMOKE_RESULT " + result);

        // 等一帧重绘后再截图：WebKit 的绘制是异步的，DOM 更新后立刻 snapshot 可能拿到旧画面。
        javafx.animation.PauseTransition pause =
                new javafx.animation.PauseTransition(Duration.millis(700));
        pause.setOnFinished(event -> {
            takeSnapshot();
            Platform.exit();
        });
        pause.play();
    }

    private void takeSnapshot() {
        if (cli.snapshot != null && !cli.snapshot.isBlank()) {
            try {
                WritableImage image = view.snapshot(null, null);
                Path out = Path.of(cli.snapshot).toAbsolutePath();
                if (out.getParent() != null) Files.createDirectories(out.getParent());
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", out.toFile());
                System.out.println("SNAPSHOT " + out);
            } catch (Exception e) {
                System.err.println("截图失败: " + e.getMessage());
            }
        }
    }
}
