package de.oliver.fancynpcs.utils;

import de.oliver.fancyanalytics.api.FancyAnalyticsAPI;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Logger;

public final class FancyAnalyticsCleanup {
    private static Thread loggerHook;
    private static ExecutorService loggerExecutor;
    private static ExecutorService apiExecutor;
    private static ExecutorService sdkExecutor;
    private static HttpClient httpClient;
    private static boolean closed;

    private FancyAnalyticsCleanup() {
    }

    // Called only by the patched initializers in this plugin's shaded JAR.
    public static synchronized void registerLoggerHook(Runtime runtime, Thread hook, ExecutorService executor) {
        if (closed) {
            executor.shutdown();
            return;
        }
        runtime.addShutdownHook(hook);
        loggerHook = hook;
        loggerExecutor = executor;
    }

    public static synchronized void captureApiExecutor(ExecutorService executor) {
        if (closed) {
            stopExecutor(executor);
        } else {
            apiExecutor = executor;
        }
    }

    public static synchronized void captureSdkExecutor(ExecutorService executor) {
        if (closed) {
            stopExecutor(executor);
        } else {
            sdkExecutor = executor;
        }
    }

    public static synchronized void initializeHttpClient(HttpClient.Builder builder) {
        if (!closed) {
            httpClient = builder.build();
        }
    }

    public static synchronized HttpClient httpClient() throws IOException {
        if (httpClient == null) {
            throw new IOException("FancyAnalytics HTTP client is unavailable");
        }
        return httpClient;
    }

    public static synchronized void close(FancyAnalyticsAPI analytics, Logger pluginLogger, Logger serverLogger,
                                          boolean serverStopping) {
        if (closed) {
            return;
        }
        closed = true;
        var client = httpClient;
        httpClient = null;
        if (client != null) {
            // Abort outstanding sends without waiting. The SDK must not keep the facade alive.
            client.shutdownNow();
        }
        if (analytics != null) {
            // Disable this instance's metrics and FancyLogger middleware without changing the saved config.
            analytics.getConfig().setSendMetrics(false);
            analytics.getConfig().sendErrors(false);
            var handler = analytics.getExceptionHandler();
            Logger.getGlobal().removeHandler(handler);
            pluginLogger.removeHandler(handler);
            serverLogger.removeHandler(handler);
        }

        stopExecutor(apiExecutor);
        stopExecutor(sdkExecutor);

        // Let queued log lines drain. On a full server stop, keep the original JVM-exit wait.
        if (loggerExecutor != null) {
            loggerExecutor.shutdown();
        }
        if (loggerHook != null && !serverStopping) {
            try {
                Runtime.getRuntime().removeShutdownHook(loggerHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown has already begun; the original hook will finish the drain.
            }
        }
        apiExecutor = null;
        sdkExecutor = null;
        loggerExecutor = null;
        loggerHook = null;
    }

    private static void stopExecutor(ExecutorService executor) {
        if (executor != null) {
            for (Runnable pending : executor.shutdownNow()) {
                if (pending instanceof Future<?> future) {
                    future.cancel(false);
                }
            }
            // HttpClient's JDK cleaner can still hold a terminated executor.
            if (executor instanceof ThreadPoolExecutor pool) {
                pool.setThreadFactory(Executors.defaultThreadFactory());
            }
        }
    }
}
