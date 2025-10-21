package com.beenycool.namespoofer;

import com.beenycool.namespoofer.config.ConfigManager;
import com.beenycool.namespoofer.config.NameSpooferConfig;
import com.beenycool.namespoofer.util.NameUtils;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.font.TextRenderer;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Arrays;

public final class NameSpooferMod implements ClientModInitializer {
    public static final String MOD_ID = "name_spoofer";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile NameSpooferConfig config;

    @Override
    public void onInitializeClient() {
        reloadConfig();
    }

    public static Logger getLogger() {
        return LOGGER;
    }

    public static NameSpooferConfig getConfig() {
        return config;
    }

    public static void reloadConfig() {
        NameSpooferConfig loaded = ConfigManager.load(LOGGER);
        config = loaded;
        NameUtils.updateSpoofs(loaded.getReplacements());
        validateTextRendererCompatibility();
    }

    private static void validateTextRendererCompatibility() {
        boolean hasStringDrawInternal = Arrays.stream(TextRenderer.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("drawInternal"))
            .anyMatch(NameSpooferMod::isStringDrawInternal);

        if (!hasStringDrawInternal) {
            LOGGER.warn("Unable to locate TextRenderer#drawInternal(String, ...). Name spoofing may be incompatible with this version of Minecraft.");
            if (config != null && config.isFailSoft()) {
                NameUtils.setEnabled(false);
                LOGGER.warn("Fail-soft mode enabled; spoofing has been disabled to avoid crashing.");
            } else {
                throw new IllegalStateException("Missing compatible TextRenderer#drawInternal overload.");
            }
        } else {
            NameUtils.setEnabled(true);
        }
    }

    private static boolean isStringDrawInternal(Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        return parameters.length > 0 && parameters[0] == String.class;
    }
}
