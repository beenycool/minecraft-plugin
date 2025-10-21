package com.beenycool.namespoofer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("name_spoofer.json");

    private ConfigManager() {
    }

    public static NameSpooferConfig load(Logger logger) {
        if (Files.notExists(CONFIG_PATH)) {
            NameSpooferConfig config = NameSpooferConfig.createDefault();
            save(config, logger);
            return config;
        }

        try (BufferedReader reader = Files.newBufferedReader(CONFIG_PATH)) {
            NameSpooferConfig config = GSON.fromJson(reader, NameSpooferConfig.class);
            if (config == null) {
                config = NameSpooferConfig.createDefault();
            }
            config.normalize();
            return config;
        } catch (IOException | JsonParseException exception) {
            logger.error("Failed to load Name Spoofer config. Using defaults.", exception);
            NameSpooferConfig fallback = NameSpooferConfig.createDefault();
            save(fallback, logger);
            return fallback;
        }
    }

    public static void save(NameSpooferConfig config, Logger logger) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException exception) {
            logger.error("Failed to save Name Spoofer config.", exception);
        }
    }
}
