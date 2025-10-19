package com.crimsonwarpedcraft.exampleplugin;

import com.crimsonwarpedcraft.exampleplugin.command.YouTubeIntegrationCommand;
import com.crimsonwarpedcraft.exampleplugin.service.YouTubeChatBridge;
import io.papermc.lib.PaperLib;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.logging.Level;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Primary plugin entry point that wires the YouTube chat integration.
 */
public class ExamplePlugin extends JavaPlugin {

  private static final String DEFAULT_LISTENER_SCRIPT = "python/chat_listener.py";

  private YouTubeChatBridge chatBridge;
  private String streamIdentifier;
  private String targetPlayerIgn;
  private int pollingIntervalSeconds;
  private String pythonExecutable;
  private String listenerScriptPath;

  /** {@inheritDoc} */
  @Override
  public void onEnable() {
    PaperLib.suggestPaper(this);

    saveDefaultConfig();
    loadSettingsFromConfig();
    ensureListenerScriptAvailable();

    chatBridge = new YouTubeChatBridge(this);
    registerCommands();
    restartMonitoring();
  }

  /** {@inheritDoc} */
  @Override
  public void onDisable() {
    if (chatBridge != null) {
      chatBridge.stop();
    }
  }

  /**
   * Loads configuration values from {@code config.yml} into cached fields.
   */
  public void loadSettingsFromConfig() {
    streamIdentifier = getConfig().getString("youtube.stream-identifier", "");
    targetPlayerIgn = getConfig().getString("youtube.target-player-ign", "");
    pollingIntervalSeconds = getConfig().getInt("youtube.polling-interval-seconds", 5);
    pythonExecutable = getConfig().getString("youtube.python-executable", "python3");
    listenerScriptPath =
        getConfig().getString("youtube.listener-script", DEFAULT_LISTENER_SCRIPT);
  }

  private void registerCommands() {
    PluginCommand command = getCommand("ytstream");
    if (command != null) {
      YouTubeIntegrationCommand executor = new YouTubeIntegrationCommand(this);
      command.setExecutor(executor);
      command.setTabCompleter(executor);
    } else {
      getLogger()
          .warning("Failed to register /ytstream command; command not defined in plugin.yml");
    }
  }

  /**
   * Restarts the external listener process using the current configuration.
   */
  public void restartMonitoring() {
    if (chatBridge == null) {
      return;
    }

    chatBridge.stop();
    chatBridge.start(
        pythonExecutable,
        getListenerScriptFile(),
        streamIdentifier,
        pollingIntervalSeconds,
        targetPlayerIgn);
  }

  private void ensureListenerScriptAvailable() {
    File scriptFile = getListenerScriptFile();
    if (scriptFile.exists()) {
      return;
    }

    File parent = scriptFile.getParentFile();
    if (!parent.exists() && !parent.mkdirs()) {
      getLogger()
          .log(Level.SEVERE, "Unable to create directories for listener script at {0}", parent);
      return;
    }

    try (InputStream input = getResource(DEFAULT_LISTENER_SCRIPT)) {
      if (input == null) {
        getLogger()
            .warning("Listener script resource python/chat_listener.py not found in jar.");
        return;
      }

      try (FileOutputStream output = new FileOutputStream(scriptFile)) {
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) > 0) {
          output.write(buffer, 0, read);
        }
      }
      getLogger()
          .info(
              "Extracted default YouTube listener script to " + scriptFile.getAbsolutePath());
    } catch (IOException e) {
      getLogger().log(Level.SEVERE, "Failed to extract listener script", e);
    }
  }

  private File getListenerScriptFile() {
    File dataFolder = getDataFolder();

    Path base;
    try {
      base = dataFolder.toPath().toRealPath().normalize();
    } catch (IOException ex) {
      base = dataFolder.toPath().toAbsolutePath().normalize();
    }

    Path candidatePath;
    try {
      candidatePath = base.resolve(listenerScriptPath).normalize();
    } catch (InvalidPathException ex) {
      getLogger().log(Level.WARNING, "Failed to resolve listener script path; using default", ex);
      return base.resolve(DEFAULT_LISTENER_SCRIPT).toFile();
    }

    if (!candidatePath.startsWith(base)) {
      getLogger()
          .warning(
              "Listener script path points outside the plugin data folder; using default "
                  + "script.");
      return base.resolve(DEFAULT_LISTENER_SCRIPT).toFile();
    }

    return candidatePath.toFile();
  }

  /**
   * Handles a single message emitted by the Python bridge.
   *
   * @param message the chat message to deliver
   * @param targetIgn the target player IGN, or {@code null}/{@code empty} to broadcast
   */
  public void handleIncomingYouTubeMessage(String message, String targetIgn) {
    if (message == null || message.isEmpty()) {
      return;
    }

    if (targetIgn != null && !targetIgn.isEmpty()) {
      Player player = getServer().getPlayerExact(targetIgn);
      if (player != null && player.hasPermission("example.ytstream.monitor")) {
        player.sendMessage(ChatColor.RED + "[YouTube] " + ChatColor.WHITE + message);
      }
    } else {
      for (Player player : getServer().getOnlinePlayers()) {
        if (player.hasPermission("example.ytstream.monitor")) {
          player.sendMessage(ChatColor.RED + "[YouTube] " + ChatColor.WHITE + message);
        }
      }
    }
  }

  /** Returns the configured YouTube stream identifier. */
  public String getStreamIdentifier() {
    return streamIdentifier;
  }

  /** Returns the configured target player IGN. */
  public String getTargetPlayerIgn() {
    return targetPlayerIgn;
  }

  /** Returns the polling interval in seconds for placeholder mode. */
  public int getPollingIntervalSeconds() {
    return pollingIntervalSeconds;
  }

  /** Returns the configured python executable path. */
  public String getPythonExecutable() {
    return pythonExecutable;
  }

  /** Returns the configured listener script path. */
  public String getListenerScriptPath() {
    return listenerScriptPath;
  }
}
