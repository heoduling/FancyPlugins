package com.fancyinnovations.fancynpcsmodel.main;

import org.bukkit.Bukkit;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

public final class ModelLoggingLifecycle {
    private static final List<ExecutorService> ANALYTICS_EXECUTORS = new ArrayList<>();
    private static ExecutorService loggerExecutor;
    private static Thread loggerHook;
    private static HttpClient httpClient;
    private static boolean closed;

    private ModelLoggingLifecycle() {}

    // Called by this JAR's patched library initializers.
    public static synchronized void registerLogger(Runtime runtime, Thread hook, ExecutorService executor) {
        if (closed) {
            executor.shutdown();
            return;
        }
        runtime.addShutdownHook(hook);
        loggerHook = hook;
        loggerExecutor = executor;
    }

    public static synchronized void captureExecutor(ExecutorService executor) {
        if (closed) {
            stopExecutor(executor);
        } else {
            ANALYTICS_EXECUTORS.add(executor);
        }
    }

    public static synchronized HttpClient analyticsHttpClient() throws IOException {
        if (closed) {
            throw new IOException("FancyNpcsModel analytics is stopped");
        }
        // The HTTP selector must not schedule cleanup on the SDK executor we shut down.
        if (httpClient == null) httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return httpClient;
    }

    static synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        HttpClient client = httpClient;
        httpClient = null;
        if (client != null) client.shutdownNow();
        ANALYTICS_EXECUTORS.forEach(ModelLoggingLifecycle::stopExecutor);
        ANALYTICS_EXECUTORS.clear();
        if (loggerExecutor != null) {
            loggerExecutor.shutdown();
            loggerExecutor = null;
        }
        if (loggerHook != null && !Bukkit.isStopping()) {
            try {
                Runtime.getRuntime().removeShutdownHook(loggerHook);
            } catch (IllegalStateException ignored) {
                // The JVM is already running shutdown hooks.
            }
        }
        loggerHook = null;
    }

    private static void stopExecutor(ExecutorService executor) {
        for (Runnable pending : executor.shutdownNow()) {
            if (pending instanceof Future<?> future) {
                future.cancel(false);
            }
        }
        if (executor instanceof ThreadPoolExecutor pool) {
            pool.setThreadFactory(Executors.defaultThreadFactory());
        }
    }
}
