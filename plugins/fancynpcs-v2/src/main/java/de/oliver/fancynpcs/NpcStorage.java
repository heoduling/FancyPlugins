package de.oliver.fancynpcs;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

final class NpcStorage {
    private final Path file;
    private final Logger logger;
    private final Object commitLock;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "FancyNpcs-Storage");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean aborted;

    // Writer-owned retry state. A delete always supersedes an older update for its ID.
    private final Set<String> deletions = new LinkedHashSet<>();
    private final Map<String, Consumer<YamlConfiguration>> updates = new LinkedHashMap<>();

    NpcStorage(Path file, Logger logger) {
        this.file = file.toAbsolutePath().normalize();
        this.logger = logger;
        // An old instance may be timing out while a new classloader opens the same file.
        this.commitLock = this.file.toString().intern();
    }

    <T> CompletableFuture<T> submit(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Could not complete NPC storage operation for " + file, e);
                throw new CompletionException(e);
            }
        }, writer);
    }

    void delete(String id) {
        updates.remove(id);
        deletions.add(id);
    }

    void update(String id, Consumer<YamlConfiguration> patch) {
        updates.put(id, patch);
    }

    void flush() throws IOException, InvalidConfigurationException {
        flush(false);
    }

    void flush(boolean force) throws IOException, InvalidConfigurationException {
        if (!force && deletions.isEmpty() && updates.isEmpty()) {
            return;
        }
        YamlConfiguration config = read();
        deletions.forEach(id -> config.set("npcs." + id, null));
        updates.values().forEach(patch -> patch.accept(config));
        replace(config.saveToString());
        deletions.clear();
        updates.clear();
    }

    YamlConfiguration read() throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        final String content;
        synchronized (commitLock) {
            try {
                content = Files.readString(file);
            } catch (NoSuchFileException e) {
                if (Files.notExists(file, NOFOLLOW_LINKS)) {
                    return config;
                }
                throw e;
            }
        }
        config.loadFromString(content);
        if (config.isSet("npcs") && !config.isConfigurationSection("npcs")) {
            throw new InvalidConfigurationException("The npcs key must be a mapping");
        }
        return config;
    }

    private void replace(String content) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), ".npcs-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, WRITE)) {
                ByteBuffer data = StandardCharsets.UTF_8.encode(content);
                while (data.hasRemaining()) {
                    channel.write(data);
                }
                channel.force(true);
            }
            synchronized (commitLock) {
                if (aborted || Thread.currentThread().isInterrupted()) {
                    throw new IOException("NPC storage stopped before commit");
                }
                Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    void shutdown() {
        writer.shutdown();
        try {
            if (writer.awaitTermination(10, TimeUnit.SECONDS)) {
                return;
            }
            logger.severe("Timed out draining NPC storage; changes may not have been saved.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.severe("Interrupted draining NPC storage; changes may not have been saved.");
        }
        aborted = true;
        writer.shutdownNow();
    }
}
