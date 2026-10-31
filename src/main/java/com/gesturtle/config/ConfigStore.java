package com.gesturtle.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads and saves the app's own configuration (library location, window
 * geometry). Lives outside the library root since it must be readable before
 * any library is chosen.
 */
public class ConfigStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path configFile;

    public ConfigStore() {
        this(appDir().resolve("config.json"));
    }

    /** Exposed for tests, so they can point at a temp file instead of the real per-OS config dir. */
    public ConfigStore(Path configFile) {
        this.configFile = configFile;
    }

    /** The per-OS folder for the app's own files (config, logs), outside any library. */
    public static Path appDir() {
        return configDir("gesturtle");
    }

    private static Path configDir(String name) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = appData != null ? Path.of(appData) : Path.of(home, "AppData", "Roaming");
            return base.resolve(name);
        } else if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", name);
        } else {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            Path base = xdg != null ? Path.of(xdg) : Path.of(home, ".config");
            return base.resolve(name);
        }
    }

    public AppConfig load() {
        if (!Files.isRegularFile(configFile)) {
            return new AppConfig();
        }
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            AppConfig config = GSON.fromJson(reader, AppConfig.class);
            return config != null ? patchMissingFields(config) : new AppConfig();
        } catch (IOException | JsonParseException e) {
            // A damaged file (cut short, hand-edited) mustn't stop the app starting. It's kept beside the
            // new one, since it may still hold settings worth recovering by hand.
            Logger.getLogger("com.gesturtle").log(Level.WARNING, "Could not read " + configFile + "; using defaults", e);
            try {
                Files.copy(configFile, configFile.resolveSibling("config.json.bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            return new AppConfig();
        }
    }

    /**
     * Gson instantiates via an unsafe allocator, not AppConfig's constructor,
     * so a field absent from an older config.json (e.g. "theme", added after
     * some configs were already saved) comes back null instead of picking up
     * its source-level default. Patch those back in here, once, on load.
     */
    private static AppConfig patchMissingFields(AppConfig config) {
        AppConfig defaults = new AppConfig();
        if (config.theme == null) config.theme = defaults.theme;
        if (config.recentLibraries == null) config.recentLibraries = defaults.recentLibraries;
        if (config.soundVolume == null) config.soundVolume = defaults.soundVolume;
        if (config.topographyOpacity == null) config.topographyOpacity = defaults.topographyOpacity;
        if (config.shortcuts == null) config.shortcuts = defaults.shortcuts;
        return config;
    }

    public void save(AppConfig config) {
        try {
            Files.createDirectories(configFile.getParent());
            // Written whole to a temp file, then swapped in, so a crash mid-save leaves the old file, not half a new one.
            Path temp = configFile.resolveSibling(configFile.getFileName() + ".part");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
            try {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config", e);
        }
    }
}
