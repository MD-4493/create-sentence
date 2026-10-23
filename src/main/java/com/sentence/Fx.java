package com.sentence;

import javafx.application.Platform;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** JavaFX 线程工具：后台线程需要弹窗时回到 FX 线程并等待结果。 */
public final class Fx {
    private Fx() {
    }

    public static <T> T call(Supplier<T> supplier) {
        if (Platform.isFxApplicationThread()) {
            return supplier.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.join();
    }

    public static void run(Runnable runnable) {
        if (Platform.isFxApplicationThread()) runnable.run();
        else Platform.runLater(runnable);
    }
}
