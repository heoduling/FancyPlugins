package com.fancyinnovations.fancynpcsmodel.main;

import com.fancyinnovations.fancynpcsmodel.config.FancyNpcsModelConfigImpl;
import com.fancyinnovations.fancynpcsmodel.config.PublicNpcAccess;
import com.fancyinnovations.fancynpcsmodel.metrics.FNMMetrics;
import de.oliver.fancyanalytics.logger.ExtendedFancyLogger;
import de.oliver.fancyanalytics.logger.LogLevel;
import de.oliver.fancyanalytics.logger.appender.Appender;
import de.oliver.fancyanalytics.logger.appender.ConsoleAppender;
import de.oliver.fancyanalytics.logger.appender.JsonAppender;
import de.oliver.fancylib.VersionConfig;
import de.oliver.fancylib.logging.PluginMiddleware;
import de.oliver.fancylib.translations.Language;
import de.oliver.fancylib.translations.TextConfig;
import de.oliver.fancylib.translations.Translator;
import de.oliver.fancylib.translations.message.SimpleMessage;
import de.oliver.fancylib.versionFetcher.FancySpacesVersionFetcher;
import de.oliver.fancylib.versionFetcher.VersionFetcher;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static java.util.concurrent.CompletableFuture.supplyAsync;

public class FancyNpcsModelPlugin extends JavaPlugin implements Listener {

    private static FancyNpcsModelPlugin INSTANCE;
    private final ExtendedFancyLogger fancyLogger;

    private FancyNpcsModelConfigImpl fancyNpcsModelConfig;
    private VersionFetcher versionFetcher;
    private VersionConfig versionConfig;
    private volatile ComparableVersion newestVersion;
    private volatile Translator translator;
    private FNMMetrics metrics;
    private volatile Attachment attachment;
    private ModelAdminCommand adminCommand;
    private PublicNpcAccess publicNpcAccess;

    private record Attachment(ModelSession session, ModelSessionLoader loader) {}

    public FancyNpcsModelPlugin() {
        INSTANCE = this;

        Appender consoleAppender = new ConsoleAppender("[{loggerName}] ({threadName}) {logLevel}: {message}");
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date(System.currentTimeMillis()));
        File logsFile = new File("plugins/FancyNpcsModel/logs/FNM-logs-" + date + ".txt");
        if (!logsFile.exists()) {
            try {
                logsFile.getParentFile().mkdirs();
                logsFile.createNewFile();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        JsonAppender jsonAppender = new JsonAppender(false, false, true, logsFile.getPath());
        this.fancyLogger = new ExtendedFancyLogger(
                "FancyNpcsModel",
                LogLevel.INFO,
                List.of(consoleAppender, jsonAppender),
                List.of(new PluginMiddleware(this))
        );
    }

    public static FancyNpcsModelPlugin get() {
        return INSTANCE;
    }

    @Override
    public void onLoad() {
        fancyLogger.info("Loading FancyNpcsModel version %s...".formatted(getDescription().getVersion()));

        // Config
        fancyNpcsModelConfig = new FancyNpcsModelConfigImpl();
        fancyNpcsModelConfig.init();
        fancyNpcsModelConfig.reload();
        publicNpcAccess = new PublicNpcAccess(getDataFolder().toPath(), getLogger());

        LogLevel logLevel;
        try {
            logLevel = LogLevel.valueOf(fancyNpcsModelConfig.getLogLevel());
        } catch (IllegalArgumentException e) {
            logLevel = LogLevel.INFO;
        }
        fancyLogger.setCurrentLevel(logLevel);

        // Version checking
        versionFetcher = new FancySpacesVersionFetcher("FancyNpcsModel");
        versionConfig = new VersionConfig(this, versionFetcher);
        versionConfig.load();

        // Translator
        registerTranslator();

        // Metrics
        metrics = new FNMMetrics();

        fancyLogger.info("Successfully loaded FancyNpcsModel version %s".formatted(getDescription().getVersion()));
    }

    @Override
    public void onEnable() {
        fancyLogger.info("Enabling FancyNpcsModel version %s...".formatted(getDescription().getVersion()));

        if (!fancyNpcsModelConfig.areVersionNotificationsMuted()) {
            checkForNewerVersion();
        }
        if (versionConfig.isDevelopmentBuild()) {
            fancyLogger.warn("""
                    
                    --------------------------------------------------
                    You are using a development build of FancyNpcsModel.
                    Please be aware that there might be bugs in this version.
                    If you find any bugs, please report them on our discord server (https://discord.gg/ZUgYCEJUEx).
                    Read more about the risks of using a development build here: https://fancyinnovations.com/docs/general/development-guidelines/versioning#build
                    --------------------------------------------------
                    """);
        }

        metrics.register();
        Bukkit.getPluginManager().registerEvents(this, this);
        Plugin host = Bukkit.getPluginManager().getPlugin("FancyNpcs");
        if (host != null && host.isEnabled()) attach(host);

        adminCommand = new ModelAdminCommand();
        adminCommand.register();

        fancyLogger.info("Successfully enabled FancyNpcsModel version %s".formatted(getDescription().getVersion()));
    }

    @Override
    public void onDisable() {
        if (publicNpcAccess != null) publicNpcAccess.close();
        if (metrics != null) metrics.detachErrorLogging();
        try {
            try {
                detach(true);
            } finally {
                if (adminCommand != null) {
                    adminCommand.detach();
                    adminCommand = null;
                }
            }
            fancyLogger.info("Model integration stopped; pending carrier repairs run on their owning regions.");
        } finally {
            try {
                ModelProviderRelease.release(this);
            } finally {
                ModelLoggingLifecycle.close();
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDependencyDisable(PluginDisableEvent event) {
        Attachment current = attachment;
        if (current != null && event.getPlugin() == current.session().host()) detach(false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDependencyEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("FancyNpcs") && attachment == null) {
            try {
                attach(event.getPlugin());
            } catch (RuntimeException failure) {
                getLogger().log(java.util.logging.Level.SEVERE, "Could not attach to the new FancyNpcs instance", failure);
            }
        }
    }

    private synchronized void attach(Plugin host) {
        if (attachment != null) return;
        ModelSessionLoader loader;
        try {
            loader = new ModelSessionLoader(getFile().toURI().toURL(), getClass().getClassLoader(), host.getClass().getClassLoader());
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException("Could not locate the model integration classes", e);
        }

        ModelSession session = null;
        try {
            Class<?> type = loader.loadClass("com.fancyinnovations.fancynpcsmodel.integration.NpcModelSession");
            session = (ModelSession) type.getConstructor(FancyNpcsModelPlugin.class, Plugin.class).newInstance(this, host);
            session.start();
            attachment = new Attachment(session, loader);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            try {
                if (session != null) session.stop(false);
            } finally {
                closeLoader(loader);
            }
            throw new IllegalStateException("Could not start the FancyNpcs model integration", failure);
        }
    }

    private synchronized void detach(boolean restoreCarriers) {
        Attachment current = attachment;
        attachment = null;
        if (current == null) return;
        try {
            current.session().stop(restoreCarriers);
        } finally {
            closeLoader(current.loader());
        }
    }

    private void closeLoader(ModelSessionLoader loader) {
        try {
            loader.close();
        } catch (java.io.IOException e) {
            getLogger().log(java.util.logging.Level.WARNING, "Could not close the model integration JAR", e);
        }
    }


    public void registerTranslator() {
        String bundledChinese = "languages/" + FancyNpcsModelConfigImpl.DEFAULT_LANGUAGE + ".yml";
        if (!new File(getDataFolder(), bundledChinese).exists()) saveResource(bundledChinese, false);

        TextConfig text = new TextConfig(
                "#ffcc24", // color to highlight important information
                "gray", // text color for regular messages
                "#81E366",
                "#E3CA66",
                "#E36666",
                "<italic:false><color:#ba8813>[</color><gradient:#ffae00:#fffb00:#ffae00>FancyNpcsModel</gradient><color:#ba8813>]</color> <gray>"
        );

        Translator next = new Translator(text);
        next.loadLanguages(getDataFolder().getAbsolutePath());
        String configured = fancyNpcsModelConfig.getLanguage();
        String selection = configured.equalsIgnoreCase("default") ? FancyNpcsModelConfigImpl.DEFAULT_LANGUAGE : configured;
        boolean builtInChinese = FancyNpcsModelConfigImpl.DEFAULT_LANGUAGE.equalsIgnoreCase(selection);
        // Preserve existing selections by language name; also accept file codes such as zh_cn.
        Language selected = next.getLanguages().stream()
                .filter(language -> builtInChinese ? language.getLanguageCode().equalsIgnoreCase(selection)
                        : language.getLanguageName().equals(selection)).findFirst()
                .orElseGet(() -> next.getLanguages().stream().filter(language -> language.getLanguageCode().equalsIgnoreCase(selection))
                        .findFirst().orElse(next.getFallbackLanguage()));
        if (FancyNpcsModelConfigImpl.DEFAULT_LANGUAGE.equalsIgnoreCase(selected.getLanguageCode())) {
            fillMissingChinese(selected, text, bundledChinese);
        }
        translator = next.setSelectedLanguage(selected);
    }

    private void fillMissingChinese(Language selected, TextConfig text, String resource) {
        try (var reader = new InputStreamReader(Objects.requireNonNull(getResource(resource)), StandardCharsets.UTF_8)) {
            var messages = Objects.requireNonNull(YamlConfiguration.loadConfiguration(reader).getConfigurationSection("messages"));
            for (String key : messages.getKeys(true)) {
                if (messages.isString(key) && selected.getMessage(key) == null) {
                    selected.addMessage(key, new SimpleMessage(text, messages.getString(key)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read bundled Simplified Chinese translations", e);
        }
    }

    private void checkForNewerVersion() {
        final var current = new ComparableVersion(versionConfig.getVersion());

        supplyAsync(getVersionFetcher()::fetchNewestVersion).thenApply(Objects::requireNonNull).whenComplete((newest, error) -> {
            if (error != null || !isEnabled()) return;
            newestVersion = newest;
            if (newest.compareTo(current) <= 0) return;

            fancyLogger.warn("""
                    
                    -------------------------------------------------------
                    You are not using the latest version of the FancyNpcsModel plugin.
                    Please update to the newest version (%s).
                    %s
                    -------------------------------------------------------
                    """.formatted(newest, getVersionFetcher().getDownloadUrl()));
        });
    }

    public ExtendedFancyLogger getFancyLogger() {
        return fancyLogger;
    }

    public boolean isModelActive() {
        return attachment != null;
    }

    ModelSession activeSession() {
        Attachment current = attachment;
        return current == null ? null : current.session();
    }

    public PublicNpcAccess publicNpcAccess() {
        return publicNpcAccess;
    }

    public void finishPublicNpcStartup(java.util.List<String> npcIds, java.nio.file.Path npcFile) {
        publicNpcAccess.finishStartup(npcIds, npcFile, versionConfig.getVersion(), metrics::reportPluginVersionUpdated);
    }

    public int modelCount() {
        Attachment current = attachment;
        return current == null ? 0 : current.session().modelCount();
    }

    public FancyNpcsModelConfigImpl getFancyNpcsModelConfig() {
        return fancyNpcsModelConfig;
    }

    public VersionFetcher getVersionFetcher() {
        return versionFetcher;
    }

    public VersionConfig getVersionConfig() {
        return versionConfig;
    }

    public ComparableVersion getNewestVersion() {
        return newestVersion;
    }

    public Translator getTranslator() {
        return translator;
    }

}
