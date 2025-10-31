package com.crimsonwarpedcraft.exampleplugin;

import com.crimsonwarpedcraft.exampleplugin.bridge.PlatformChatBridge;
import com.crimsonwarpedcraft.exampleplugin.bridge.PlatformChatBridge.ChatMessage;
import com.crimsonwarpedcraft.exampleplugin.bridge.PlatformChatBridge.Registration;
import com.crimsonwarpedcraft.exampleplugin.bridge.PlatformChatBridge.SubscriberMilestone;
import com.crimsonwarpedcraft.exampleplugin.bridge.PlatformChatBridge.SubscriberNotification;
import com.crimsonwarpedcraft.exampleplugin.bridge.TikTokChatBridge;
import com.crimsonwarpedcraft.exampleplugin.bridge.YouTubeChatBridge;
import com.crimsonwarpedcraft.exampleplugin.command.NightPunishCommand;
import com.crimsonwarpedcraft.exampleplugin.command.TikTokIntegrationCommand;
import com.crimsonwarpedcraft.exampleplugin.command.YouTubeIntegrationCommand;
import com.crimsonwarpedcraft.exampleplugin.service.WorldResetScheduler; // Added from codex branch
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.papermc.lib.PaperLib;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Example plugin entry point that wires the YouTube bridge behaviours together.
 */
public class ExamplePlugin extends JavaPlugin {

  private static final String DEFAULT_LISTENER_SCRIPT = "python/chat_listener.py";

  private enum StreamPlatform {
    YOUTUBE("youtube", "YouTube", "example.ytstream.monitor"),
    TIKTOK("tiktok", "TikTok", "example.ttstream.monitor");

    private final String id;
    private final String displayName;
    private final String monitorPermission;

    StreamPlatform(String id, String displayName, String monitorPermission) {
      this.id = id;
      this.displayName = displayName;
      this.monitorPermission = monitorPermission;
    }

    String id() {
      return id;
    }

    String displayName() {
      return displayName;
    }

    String monitorPermission() {
      return monitorPermission;
    }

    static StreamPlatform fromId(String candidate) {
      if (candidate == null || candidate.isBlank()) {
        return null;
      }
      String normalized = candidate.trim().toLowerCase(Locale.ROOT);
      return switch (normalized) {
        case "tiktok", "tt", "tik_tok" -> TIKTOK;
        case "youtube", "yt" -> YOUTUBE;
        default -> null;
      };
    }
  }

  private final AtomicLong messageSequence = new AtomicLong();

  // Fields from codex branch
  private WorldResetScheduler worldResetScheduler;

  // Fields from main branch
  private YouTubeChatBridge youtubeBridge;
  private TikTokChatBridge tikTokBridge;
  private final EnumMap<StreamPlatform, PlatformChatBridge> activeBridges =
      new EnumMap<>(StreamPlatform.class);
  private final List<Registration> registrations = new ArrayList<>();
  private final SecureRandom secureRandom = new SecureRandom();
  private final EnumMap<StreamPlatform, BridgeSettings> platformSettings =
      new EnumMap<>(StreamPlatform.class);
  private final EnumMap<StreamPlatform, Long> knownSubscriberCounts =
      new EnumMap<>(StreamPlatform.class);
  private final EnumMap<StreamPlatform, Long> lastCelebratedMilestones =
      new EnumMap<>(StreamPlatform.class);

  // Fields for the external Python listener bridge
  private final EnumMap<StreamPlatform,
          com.crimsonwarpedcraft.exampleplugin.service.YouTubeChatBridge>
      listenerProcesses = new EnumMap<>(StreamPlatform.class);
  private ListenerSettings youtubeListenerSettings;
  private ListenerSettings tikTokListenerSettings;
  private final EnumMap<StreamPlatform, String> listenerScriptPaths =
      new EnumMap<>(StreamPlatform.class);
  private NightPunishCommand nightPunishCommand;

  @Override
  public void onEnable() {
    PaperLib.suggestPaper(this);

    saveDefaultConfig();

    reloadConfig();
    loadSettingsFromConfig();
    loadSubscriberState();
    ensureListenerScriptAvailable();

    for (StreamPlatform platform : StreamPlatform.values()) {
      listenerProcesses.put(
          platform,
          new com.crimsonwarpedcraft.exampleplugin.service.YouTubeChatBridge(
              this,
              platform.displayName(),
              (message, targetIgn) ->
                  handleIncomingListenerMessage(platform, message, targetIgn)));
    }

    // Logic from codex branch
    worldResetScheduler = new WorldResetScheduler(this);
    worldResetScheduler.start();

    // Logic from main branch
    youtubeBridge = new YouTubeChatBridge(this);
    tikTokBridge = new TikTokChatBridge(this);
    activeBridges.put(StreamPlatform.YOUTUBE, youtubeBridge);
    activeBridges.put(StreamPlatform.TIKTOK, tikTokBridge);

    bindBridge(StreamPlatform.YOUTUBE, youtubeBridge);
    bindBridge(StreamPlatform.TIKTOK, tikTokBridge);

    registerCommands();
    restartMonitoring();

    getLogger().info("Stream bridges initialised. Awaiting events from listener processes.");
  }

  @Override
  public void onDisable() {
    // Logic from codex branch
    if (worldResetScheduler != null) {
      worldResetScheduler.cancel();
    }

    if (nightPunishCommand != null) {
      nightPunishCommand.shutdown();
      nightPunishCommand = null;
    }

    if (!listenerProcesses.isEmpty()) {
      List<com.crimsonwarpedcraft.exampleplugin.service.YouTubeChatBridge> processes =
          new ArrayList<>(listenerProcesses.values());
      listenerProcesses.clear();
      for (com.crimsonwarpedcraft.exampleplugin.service.YouTubeChatBridge process : processes) {
        stopListenerProcessAsync(process, null);
      }
    }

    // Logic from main branch
    registrations.forEach(Registration::close);
    registrations.clear();
    youtubeBridge = null;
    tikTokBridge = null;
    activeBridges.clear();
    platformSettings.clear();
    knownSubscriberCounts.clear();
    lastCelebratedMilestones.clear();
    listenerScriptPaths.clear();
  }

  // Methods below coordinate the YouTube bridge behaviours

  /** Returns the active YouTube chat bridge instance. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Bridge is intentionally shared so other components can subscribe to events.")
  public YouTubeChatBridge getYouTubeChatBridge() {
    return youtubeBridge;
  }

  /** Returns the active TikTok chat bridge instance. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Bridge is intentionally shared so other components can subscribe to events.")
  public TikTokChatBridge getTikTokChatBridge() {
    return tikTokBridge;
  }

  private void registerCommands() {
    PluginCommand command = getCommand("ytstream");
    if (command == null) {
      getLogger()
          .warning("Failed to register /ytstream command; command not defined in plugin.yml");
    } else {
      YouTubeIntegrationCommand executor = new YouTubeIntegrationCommand(this);
      command.setExecutor(executor);
      command.setTabCompleter(executor);
    }

    PluginCommand tiktokCommand = getCommand("ttstream");
    if (tiktokCommand == null) {
      getLogger()
          .warning("Failed to register /ttstream command; command not defined in plugin.yml");
    } else {
      TikTokIntegrationCommand tikTokExecutor = new TikTokIntegrationCommand(this);
      tiktokCommand.setExecutor(tikTokExecutor);
      tiktokCommand.setTabCompleter(tikTokExecutor);
    }

    PluginCommand nightCommand = getCommand("nightpunish");
    if (nightCommand == null) {
      getLogger()
          .warning(
              "Failed to register /nightpunish command; command not defined in plugin.yml");
    } else {
      nightPunishCommand = new NightPunishCommand(this);
      nightCommand.setExecutor(nightPunishCommand);
      nightCommand.setTabCompleter(nightPunishCommand);
    }
  }

  /** Restarts the external Python listener process using the cached configuration. */
  public void restartMonitoring() {
    if (listenerProcesses.isEmpty()) {
      return;
    }

    for (StreamPlatform platform : StreamPlatform.values()) {
      restartMonitoring(platform);
    }
  }

  private void restartMonitoring(StreamPlatform platform) {
    com.crimsonwarpedcraft.exampleplugin.service.YouTubeChatBridge process =
        listenerProcesses.get(platform);
    if (process == null) {
      return;
    }

    ListenerSettings settings = getListenerSettings(platform);
    if (settings == null) {
      stopListenerProcessAsync(process, null);
      return;
    }

    final String listenerUrl = settings.listenerUrl();
    boolean useExternalListener = listenerUrl != null && !listenerUrl.isBlank();

    String streamIdentifier = settings.streamIdentifier();
    if (!useExternalListener && (streamIdentifier == null || streamIdentifier.isBlank())) {
      String warning =
          "No "
              + platform.displayName()
              + " stream identifier configured; skipping listener start.";
      getLogger().warning(warning);
      stopListenerProcessAsync(process, null);
      return;
    }

    final File listenerScript;
    if (!useExternalListener) {
      ensureListenerScriptAvailable(platform);
      listenerScript = getListenerScriptFile(platform);
    } else {
      listenerScript = null;
    }

    String targetIgn = settings.targetIgn();
    BridgeSettings platformSettings = getBridgeSettings(platform);
    if ((targetIgn == null || targetIgn.isBlank()) && platformSettings != null) {
      targetIgn = platformSettings.targetPlayer();
    }

    String finalTarget = targetIgn;
    stopListenerProcessAsync(
        process,
        () ->
            startListenerProcess(
                process,
                settings.pythonExecutable(),
                listenerScript,
                streamIdentifier,
                settings.pollingIntervalSeconds(),
                finalTarget,
                settings.streamlabsSocketToken(),
                listenerUrl));
  }

  private void bindBridge(StreamPlatform platform, PlatformChatBridge bridgeInstance) {
    if (bridgeInstance == null) {
      return;
    }

    BridgeSettings settings = platformSettings.get(platform);
    long interval = settings != null ? settings.subscriberMilestoneInterval() : 0L;
    bridgeInstance.setSubscriberMilestoneInterval(interval);

    registrations.add(
        bridgeInstance.registerChatListener(message -> handleChatMessage(platform, message)));
    registrations.add(
        bridgeInstance.registerSubscriberListener(
            notification -> handleSubscriberNotification(platform, notification)));
    registrations.add(
        bridgeInstance.registerMilestoneListener(
            milestone -> handleMilestone(platform, milestone)));
  }

  private void startListenerProcess(
      com.crimsonwarpedcraft.exampleplugin.service.YouTubeChatBridge process,
      String pythonExecutable,
      File listenerScript,
      String streamIdentifier,
      int pollingIntervalSeconds,
      String targetIgn,
      String streamlabsToken,
      String listenerUrl) {
    final File scriptRef = listenerScript;
    final String urlRef = listenerUrl;
    try {
      getServer()
          .getScheduler()
          .runTaskAsynchronously(
              this,
              () -> {
                try {
                  process.start(
                      pythonExecutable,
                      scriptRef,
                      streamIdentifier,
                      pollingIntervalSeconds,
                      targetIgn,
                      streamlabsToken,
                      urlRef);
                } catch (Exception e) {
                  getLogger().log(Level.SEVERE, "Failed to start listener process", e);
                }
              });
    } catch (IllegalStateException schedulerShutdown) {
      try {
        process.start(
            pythonExecutable,
            scriptRef,
            streamIdentifier,
            pollingIntervalSeconds,
            targetIgn,
            streamlabsToken,
            urlRef);
      } catch (Exception e) {
        getLogger().log(Level.SEVERE, "Failed to start listener process", e);
      }
    }
  }

  private void stopListenerProcessAsync(
      com.crimsonwarpedcraft.exampleplugin.service.YouTubeChatBridge process, Runnable afterStop) {
    if (process == null) {
      if (afterStop != null) {
        afterStop.run();
      }
      return;
    }

    Runnable stopTask =
        () -> {
          try {
            process.stop();
          } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to stop listener process cleanly", e);
          }

          if (afterStop != null) {
            afterStop.run();
          }
        };

    try {
      getServer().getScheduler().runTaskAsynchronously(this, stopTask);
    } catch (IllegalStateException schedulerShutdown) {
      stopTask.run();
    }
  }

  private void ensureListenerScriptAvailable() {
    for (StreamPlatform platform : StreamPlatform.values()) {
      ensureListenerScriptAvailable(platform);
    }
  }

  private void ensureListenerScriptAvailable(StreamPlatform platform) {
    String configuredPath = listenerScriptPaths.get(platform);
    if (configuredPath == null || configuredPath.isBlank()) {
      configuredPath = DEFAULT_LISTENER_SCRIPT;
      listenerScriptPaths.put(platform, configuredPath);
    }

    File scriptFile = getListenerScriptFile(platform);
    if (scriptFile.exists()) {
      return;
    }

    File parent = scriptFile.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      getLogger()
          .log(
              Level.SEVERE,
              "Unable to create directories for listener script at {0}",
              parent);
      return;
    }

    try (InputStream input = getResource(DEFAULT_LISTENER_SCRIPT)) {
      if (input == null) {
        getLogger().warning("Listener script resource python/chat_listener.py not found in jar.");
        return;
      }

      Files.copy(input, scriptFile.toPath());
      if (!scriptFile.setExecutable(true) && !scriptFile.canExecute()) {
        getLogger()
            .warning(
                "Extracted listener script but failed to mark it executable at "
                    + scriptFile.getAbsolutePath());
      }
      getLogger()
          .info(
              "Extracted default listener script for "
                  + platform.displayName()
                  + " to "
                  + scriptFile.getAbsolutePath());
    } catch (IOException e) {
      getLogger().log(Level.SEVERE, "Failed to extract listener script", e);
    }
  }

  private File getListenerScriptFile(StreamPlatform platform) {
    File dataFolder = getDataFolder();
    if (!dataFolder.exists() && !dataFolder.mkdirs()) {
      getLogger()
          .log(
              Level.SEVERE,
              "Unable to create plugin data folder at {0}",
              dataFolder.getAbsolutePath());
    }

    Path base;
    try {
      base = dataFolder.toPath().toRealPath().normalize();
    } catch (IOException ex) {
      base = dataFolder.toPath().toAbsolutePath().normalize();
    }

    String configuredPath = listenerScriptPaths.get(platform);
    if (configuredPath == null || configuredPath.isBlank()) {
      configuredPath = DEFAULT_LISTENER_SCRIPT;
      listenerScriptPaths.put(platform, configuredPath);
    }

    Path candidate;
    try {
      candidate = base.resolve(configuredPath).normalize();
    } catch (InvalidPathException ex) {
      getLogger().log(Level.WARNING, "Failed to resolve listener script path; using default", ex);
      return base.resolve(DEFAULT_LISTENER_SCRIPT).toFile();
    }

    if (!candidate.startsWith(base)) {
      getLogger()
          .warning(
              "Listener script path points outside the plugin data folder; using default script.");
      return base.resolve(DEFAULT_LISTENER_SCRIPT).toFile();
    }

    return candidate.toFile();
  }

  private void runOnMainThread(Runnable task) {
    if (task == null) {
      return;
    }
    if (Bukkit.isPrimaryThread()) {
      task.run();
    } else {
      getServer().getScheduler().runTask(this, task);
    }
  }

  private String applyTitlePlaceholders(
      String template,
      String donor,
      Double amount,
      String formattedAmount,
      String currency,
      String message,
      int tntCount) {
    if (template == null) {
      return "";
    }

    final String safeDonor = donor == null ? "" : donor;
    final String numericAmount =
        amount == null ? "" : String.format(Locale.US, "%.2f", amount);
    final String safeCurrency = currency == null ? "" : currency.toUpperCase(Locale.ROOT);
    final String safeMessage = message == null ? "" : message;
    final String formatted = formatAmountText(amount, currency, formattedAmount);

    String result =
        template
            .replace("{donor}", safeDonor)
            .replace("{amount}", numericAmount)
            .replace("{formatted_amount}", formatted)
            .replace("{currency}", safeCurrency)
            .replace("{message}", safeMessage)
            .replace("{tnt_count}", Integer.toString(Math.max(0, tntCount)));

    return ChatColor.translateAlternateColorCodes('&', result);
  }

  private String applyMilestoneTitlePlaceholders(
      String template, @NotNull SubscriberMilestone milestone, int tntCount) {
    if (template == null) {
      return "";
    }

    long totalSubscribers = Math.max(0L, milestone.totalSubscribers());
    long interval = Math.max(0L, milestone.milestoneInterval());
    int safeTntCount = Math.max(0, tntCount);

    String result = template;
    result = result.replace("{total_subscribers}", Long.toString(totalSubscribers));
    result = result.replace("{milestone_interval}", Long.toString(interval));
    result = result.replace("{tnt_count}", Integer.toString(safeTntCount));

    return ChatColor.translateAlternateColorCodes('&', result);
  }

  private String formatAmountText(
      Double amount, String currency, String formattedAmountFromSource) {
    if (formattedAmountFromSource != null && !formattedAmountFromSource.isBlank()) {
      return formattedAmountFromSource;
    }
    if (amount == null) {
      return "";
    }

    String numeric = String.format(Locale.US, "%.2f", amount);
    if (currency == null || currency.isBlank()) {
      return numeric;
    }
    return numeric + " " + currency.toUpperCase(Locale.ROOT);
  }

  /** Handles chat lines emitted by the Python listener. */
  public void handleIncomingYouTubeMessage(String message, String targetIgn) {
    handleIncomingListenerMessage(StreamPlatform.YOUTUBE, message, targetIgn);
  }

  /** Handles chat lines emitted by the TikTok listener. */
  public void handleIncomingTikTokMessage(String message, String targetIgn) {
    handleIncomingListenerMessage(StreamPlatform.TIKTOK, message, targetIgn);
  }

  private void handleIncomingListenerMessage(
      StreamPlatform platform, String message, String targetIgn) {
    if (message == null || message.isBlank()) {
      return;
    }

    String trimmed = message.trim();
    if (handleStructuredListenerPayload(trimmed, targetIgn, platform)) {
      return;
    }

    String author = platform.displayName();
    String content = trimmed;

    int separatorIndex = trimmed.indexOf(':');
    if (separatorIndex > 0) {
      String potentialAuthor = trimmed.substring(0, separatorIndex).trim();
      String potentialMessage = trimmed.substring(separatorIndex + 1).trim();
      if (!potentialAuthor.isEmpty()) {
        author = potentialAuthor;
        content = potentialMessage;
      }
    }

    if (content.isEmpty()) {
      return;
    }

    publishChatMessage(platform, author, content, Instant.now(), null, targetIgn);
  }

  private boolean handleStructuredListenerPayload(
      String payload, String targetIgn, StreamPlatform fallbackPlatform) {
    JsonObject root;
    try {
      JsonElement parsed = JsonParser.parseString(payload);
      if (!parsed.isJsonObject()) {
        return false;
      }
      root = parsed.getAsJsonObject();
    } catch (JsonSyntaxException ex) {
      return false;
    }

    String type = jsonString(root, "type");
    StreamPlatform platform =
        root.has("platform")
            ? StreamPlatform.fromId(jsonString(root, "platform"))
            : fallbackPlatform;
    if (platform == null) {
      String candidate = jsonString(root, "platform");
      String fallbackName = fallbackPlatform != null ? fallbackPlatform.displayName() : "default";
      if (candidate == null || candidate.isBlank()) {
        String warning =
            "Listener payload missing platform identifier; defaulting to " + fallbackName + ".";
        getLogger().warning(warning);
      } else {
        String warning =
            "Listener payload specified unknown platform '"
                + candidate
                + "'; defaulting to "
                + fallbackName
                + ".";
        getLogger().warning(warning);
      }
      platform = fallbackPlatform;
      if (platform == null) {
        return false;
      }
    }
    if (type == null || type.isBlank()) {
      return false;
    }

    switch (type.toLowerCase(Locale.ROOT)) {
      case "chat" -> {
        handleStructuredChat(platform, root, targetIgn);
        return true;
      }
      case "subscriber" -> {
        handleStructuredSubscriber(platform, root);
        return true;
      }
      case "donation" -> {
        handleStructuredDonation(platform, root);
        return true;
      }
      case "milestone" -> {
        handleStructuredMilestone(platform, root);
        return true;
      }
      case "log", "status", "heartbeat" -> {
        handleStructuredLog(platform, root, Level.INFO);
        return true;
      }
      case "error" -> {
        handleStructuredLog(platform, root, Level.SEVERE);
        return true;
      }
      default -> {
        return false;
      }
    }
  }

  private void handleStructuredChat(
      StreamPlatform platform, JsonObject payload, String targetIgn) {
    String author =
        Objects.requireNonNullElse(jsonString(payload, "author"), platform.displayName());
    String message = jsonString(payload, "message");
    if (message == null || message.isBlank()) {
      return;
    }

    String channelId = jsonString(payload, "channelId");
    Instant timestamp = parseTimestamp(jsonString(payload, "timestamp"));

    publishChatMessage(platform, author, message, timestamp, channelId, targetIgn);
  }

  private void handleStructuredSubscriber(StreamPlatform platform, JsonObject payload) {
    PlatformChatBridge bridgeInstance = activeBridges.get(platform);
    if (bridgeInstance == null) {
      return;
    }

    String ign = jsonString(payload, "inGameName");
    if (ign == null || ign.isBlank()) {
      ign = jsonString(payload, "ign");
    }

    Long totalSubscribers = jsonLong(payload, "totalSubscribers");
    if (totalSubscribers == null) {
      totalSubscribers = jsonLong(payload, "subscriberCount");
    }
    if (totalSubscribers == null || totalSubscribers < 0) {
      totalSubscribers = getKnownSubscriberCount(platform) + 1;
    }

    String channelId = jsonString(payload, "channelId");
    Instant timestamp = parseTimestamp(jsonString(payload, "timestamp"));
    String defaultAuthor = platform.displayName() + " Subscriber";
    String author = Objects.requireNonNullElse(jsonString(payload, "author"), defaultAuthor);

    bridgeInstance.emitSubscriberNotification(
        new SubscriberNotification(author, ign, totalSubscribers, channelId, timestamp));
  }

  private void handleStructuredDonation(StreamPlatform platform, JsonObject payload) {
    BridgeSettings settings = getBridgeSettings(platform);
    if (settings == null || !settings.enabled) {
      return;
    }

    DonationSettings donationSettings = settings.donationSettings();
    if (donationSettings == null || !donationSettings.enabled()) {
      return;
    }

    OrbitalStrikeSettings orbitalStrike = donationSettings.orbitalStrike();
    if (orbitalStrike == null || !orbitalStrike.enabled()) {
      return;
    }

    Double amount = jsonDouble(payload, "amount");
    if (amount == null) {
      amount = jsonDouble(payload, "total");
    }
    if (amount == null || amount < orbitalStrike.minAmount()) {
      return;
    }

    String currency =
        Optional.ofNullable(jsonString(payload, "currency"))
            .map(String::trim)
            .orElse("");
    if (orbitalStrike.currency() != null && !orbitalStrike.currency().isBlank()) {
      if (currency.isBlank() || !orbitalStrike.currency().equalsIgnoreCase(currency)) {
        return;
      }
    }

    Optional<Player> target = resolveConfiguredPlayer(settings);
    if (target.isEmpty()) {
      return;
    }

    Player player = target.get();
    String donor = Objects.requireNonNullElse(jsonString(payload, "author"), "Supporter");
    String donorMessage = jsonString(payload, "message");
    String formattedAmount = jsonString(payload, "formattedAmount");

    OrbitalStrikeInvocation invocation =
        new OrbitalStrikeInvocation(
            platform,
            player,
            donor,
            donorMessage,
            formattedAmount,
            amount,
            currency,
            orbitalStrike);

    runOnMainThread(() -> triggerOrbitalStrike(invocation));
  }

  private void handleStructuredMilestone(StreamPlatform platform, JsonObject payload) {
    PlatformChatBridge bridgeInstance = activeBridges.get(platform);
    if (bridgeInstance == null) {
      return;
    }

    Long totalSubscribers = jsonLong(payload, "totalSubscribers");
    Long interval = jsonLong(payload, "milestoneInterval");
    if (totalSubscribers == null || interval == null) {
      return;
    }

    String channelId = jsonString(payload, "channelId");
    Instant timestamp = parseTimestamp(jsonString(payload, "timestamp"));

    bridgeInstance.emitMilestone(
        new SubscriberMilestone(totalSubscribers, interval, channelId, timestamp));
  }

  private void handleStructuredLog(
      StreamPlatform platform, JsonObject payload, Level fallbackLevel) {
    String message = jsonString(payload, "message");
    if (message == null || message.isBlank()) {
      return;
    }

    Level level = parseLogLevel(jsonString(payload, "level"), fallbackLevel);
    String prefixed = "[" + platform.displayName() + "] " + message;
    getLogger().log(level, prefixed);
  }

  private void publishChatMessage(
      StreamPlatform platform,
      String author,
      String content,
      Instant timestamp,
      String channelId,
      String targetIgn) {
    String resolvedAuthor =
        author == null || author.isBlank() ? platform.displayName() : author;
    PlatformChatBridge bridgeInstance = activeBridges.get(platform);
    if (bridgeInstance != null) {
      bridgeInstance.emitChatMessage(
          new ChatMessage(
              resolvedAuthor,
              content,
              messageSequence.incrementAndGet(),
              timestamp,
              channelId));
    }

    deliverChatToPlayers(platform, resolvedAuthor, content, targetIgn);
  }

  private void deliverChatToPlayers(
      StreamPlatform platform, String author, String content, String targetIgn) {
    String resolvedAuthor =
        author == null || author.isBlank() ? platform.displayName() : author;
    String label = platform.displayName();
    String formatted;
    if (resolvedAuthor.equals(label)) {
      formatted = ChatColor.RED + "[" + label + "] " + ChatColor.WHITE + content;
    } else {
      formatted =
          ChatColor.RED
              + "["
              + label
              + "] "
              + ChatColor.YELLOW
              + resolvedAuthor
              + ChatColor.WHITE
              + ": "
              + content;
    }

    String permission = platform.monitorPermission();
    if (targetIgn != null && !targetIgn.isBlank()) {
      Player player = Bukkit.getPlayerExact(targetIgn);
      if (player != null && player.isOnline() && player.hasPermission(permission)) {
        player.sendMessage(formatted);
        return;
      }
    }

    Bukkit.getOnlinePlayers().stream()
        .filter(player -> player.hasPermission(permission))
        .forEach(player -> player.sendMessage(formatted));
  }

  private BridgeSettings getBridgeSettings(StreamPlatform platform) {
    return platformSettings.get(platform);
  }

  private ListenerSettings getListenerSettings(StreamPlatform platform) {
    return platform == StreamPlatform.TIKTOK ? tikTokListenerSettings : youtubeListenerSettings;
  }

  private long getKnownSubscriberCount(StreamPlatform platform) {
    return knownSubscriberCounts.getOrDefault(platform, 0L);
  }

  private boolean updateKnownSubscriberCount(StreamPlatform platform, long totalSubscribers) {
    long current = getKnownSubscriberCount(platform);
    long updated = Math.max(current, totalSubscribers);
    if (updated != current) {
      knownSubscriberCounts.put(platform, updated);
      return true;
    }
    return false;
  }

  private long getLastCelebratedMilestone(StreamPlatform platform) {
    return lastCelebratedMilestones.getOrDefault(platform, 0L);
  }

  private void recordLastCelebratedMilestone(StreamPlatform platform, long milestone) {
    lastCelebratedMilestones.put(platform, Math.max(0L, milestone));
  }

  private Level parseLogLevel(String candidate, Level fallbackLevel) {
    if (candidate == null || candidate.isBlank()) {
      return fallbackLevel;
    }

    return switch (candidate.toLowerCase(Locale.ROOT)) {
      case "trace", "debug" -> Level.FINE;
      case "info" -> Level.INFO;
      case "warn", "warning" -> Level.WARNING;
      case "error", "severe" -> Level.SEVERE;
      default -> fallbackLevel;
    };
  }

  private Instant parseTimestamp(String candidate) {
    if (candidate == null || candidate.isBlank()) {
      return Instant.now();
    }
    try {
      return Instant.parse(candidate.trim());
    } catch (DateTimeParseException ex) {
      return Instant.now();
    }
  }

  private String jsonString(JsonObject object, String member) {
    if (!object.has(member)) {
      return null;
    }
    JsonElement element = object.get(member);
    if (element == null || element.isJsonNull()) {
      return null;
    }
    try {
      return element.getAsString();
    } catch (ClassCastException | IllegalStateException ex) {
      return null;
    }
  }

  private Long jsonLong(JsonObject object, String member) {
    if (!object.has(member)) {
      return null;
    }

    JsonElement element = object.get(member);
    if (element == null || element.isJsonNull()) {
      return null;
    }

    try {
      if (element.isJsonPrimitive()) {
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
          return primitive.getAsLong();
        }
        if (primitive.isString()) {
          String value = primitive.getAsString();
          if (value == null || value.isBlank()) {
            return null;
          }
          String trimmed = value.trim();
          if (trimmed.isEmpty()) {
            return null;
          }
          try {
            return Long.parseLong(trimmed);
          } catch (NumberFormatException ex) {
            try {
              return (long) Double.parseDouble(trimmed);
            } catch (NumberFormatException ignored) {
              return null;
            }
          }
        }
      }
    } catch (NumberFormatException | ClassCastException | IllegalStateException ex) {
      return null;
    }

    return null;
  }

  private Double jsonDouble(JsonObject object, String member) {
    if (!object.has(member)) {
      return null;
    }

    JsonElement element = object.get(member);
    if (element == null || element.isJsonNull()) {
      return null;
    }

    try {
      if (element.isJsonPrimitive()) {
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
          return primitive.getAsDouble();
        }
        if (primitive.isString()) {
          String value = primitive.getAsString();
          String trimmed = value.trim();
          if (trimmed.isEmpty()) {
            return null;
          }
          return Double.parseDouble(trimmed);
        }
      }
    } catch (NumberFormatException | ClassCastException | IllegalStateException ex) {
      return null;
    }

    return null;
  }

  private void handleChatMessage(StreamPlatform platform, ChatMessage message) {
    BridgeSettings settings = getBridgeSettings(platform);
    if (settings == null
        || !settings.enabled
        || !settings.chatEnabled
        || !settings.chatTntEnabled) {
      return;
    }

    String lowerMessage = message.message().toLowerCase(Locale.ROOT);
    if (!lowerMessage.contains(settings.chatTntCommand)) {
      return;
    }

    Optional<Player> target = resolveConfiguredPlayer(settings);
    if (target.isEmpty()) {
      return;
    }

    Player player = target.get();
    if (spawnSingleTnt(settings, player.getLocation())) {
      player
          .getWorld()
          .playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.0f);
    }
  }

  private void handleSubscriberNotification(
      StreamPlatform platform, SubscriberNotification notification) {
    BridgeSettings settings = getBridgeSettings(platform);
    if (settings == null) {
      return;
    }

    boolean subscriberCountChanged =
        updateKnownSubscriberCount(platform, notification.totalSubscribers());
    if (subscriberCountChanged) {
      persistSubscriberState(platform);
    }

    if (!settings.enabled || !settings.subscriberKillEnabled) {
      return;
    }

    String ign = notification.inGameName();
    if (ign == null || ign.isBlank()) {
      ign = settings.targetPlayer;
    }
    if (ign == null || ign.isBlank()) {
      return;
    }

    final String finalIgn = ign;
    Bukkit.getScheduler()
        .runTask(
            this,
            () -> {
              Player target = Bukkit.getPlayerExact(finalIgn);
              if (target == null || !target.isOnline()) {
                return;
              }
              if (!isWorldAllowed(target.getWorld(), settings)) {
                return;
              }
              target.setHealth(0.0);
            });
  }

  private void handleMilestone(StreamPlatform platform, SubscriberMilestone milestone) {
    BridgeSettings settings = getBridgeSettings(platform);
    if (settings == null) {
      return;
    }

    updateKnownSubscriberCount(platform, milestone.totalSubscribers());

    if (!settings.enabled || !settings.milestoneEnabled) {
      return;
    }

    long lastMilestone = getLastCelebratedMilestone(platform);
    if (milestone.totalSubscribers() <= lastMilestone) {
      return;
    }

    Optional<Player> target = resolveConfiguredPlayer(settings);
    if (target.isEmpty()) {
      return;
    }

    Player player = target.get();
    spawnMilestoneCelebration(settings, player, milestone);
    recordLastCelebratedMilestone(platform, milestone.totalSubscribers());
    persistSubscriberState(platform);
  }

  private Optional<Player> resolveConfiguredPlayer(BridgeSettings settings) {
    if (settings == null || settings.targetPlayer == null || settings.targetPlayer.isBlank()) {
      return Optional.empty();
    }
    Player player = Bukkit.getPlayerExact(settings.targetPlayer);
    if (player == null || !player.isOnline()) {
      return Optional.empty();
    }
    if (!isWorldAllowed(player.getWorld(), settings)) {
      return Optional.empty();
    }
    if (!player.getLocation().getChunk().isLoaded()) {
      return Optional.empty();
    }
    return Optional.of(player);
  }

  private boolean isWorldAllowed(World world, BridgeSettings settings) {
    if (settings == null) {
      return false;
    }
    return settings.allowedWorlds.isEmpty()
        || settings.allowedWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
  }

  private boolean spawnSingleTnt(BridgeSettings settings, Location baseLocation) {
    Location spawnLocation =
        baseLocation.clone().add(new Vector(0, settings.chatTntVerticalOffset(), 0));
    if (!canSpawnTnt(spawnLocation, settings)) {
      return false;
    }
    spawnPrimedTnt(spawnLocation, settings.chatTntFuseTicks());
    return true;
  }

  private boolean canSpawnTnt(Location location, BridgeSettings settings) {
    World world = location.getWorld();
    if (world == null) {
      return false;
    }
    if (!isWorldAllowed(world, settings)) {
      return false;
    }
    if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
      return false;
    }
    if (!world.getWorldBorder().isInside(location)) {
      return false;
    }
    int minY = world.getMinHeight();
    int maxY = world.getMaxHeight();
    if (location.getY() < minY || location.getY() > maxY) {
      return false;
    }
    return true;
  }

  private void spawnPrimedTnt(Location location, int fuseTicks) {
    World world = location.getWorld();
    if (world == null) {
      return;
    }
    world.spawn(
        location,
        TNTPrimed.class,
        tnt -> {
          int adjustedFuseTicks = Math.max(0, fuseTicks);
          tnt.setFuseTicks(adjustedFuseTicks);
        });
  }

  private void spawnMilestoneCelebration(
      BridgeSettings settings, Player player, SubscriberMilestone milestone) {
    MilestoneSettings milestoneSettings = settings.milestoneSettings;
    if (milestoneSettings.tntCount() <= 0) {
      return;
    }

    Location baseLocation = player.getLocation();
    if (!canSpawnTnt(baseLocation, settings)) {
      return;
    }

    List<Location> spawnLocations = new ArrayList<>();
    for (int i = 0; i < milestoneSettings.tntCount(); i++) {
      double angle = secureRandom.nextDouble() * Math.PI * 2.0;
      double distance = secureRandom.nextDouble() * milestoneSettings.radius();
      double offsetX = Math.cos(angle) * distance;
      double offsetZ = Math.sin(angle) * distance;
      Location location =
          new Location(
              baseLocation.getWorld(),
              baseLocation.getX() + offsetX,
              baseLocation.getY(),
              baseLocation.getZ() + offsetZ);
      if (canSpawnTnt(location, settings)) {
        spawnLocations.add(location);
      }
    }

    if (spawnLocations.isEmpty()) {
      getLogger()
          .log(
              Level.FINE,
              "Milestone {0} could not spawn TNT due to safety checks",
              milestone.totalSubscribers());
      return;
    }

    String mainTitle =
        applyMilestoneTitlePlaceholders(
            milestoneSettings.titleMain(), milestone, spawnLocations.size());
    String subTitle =
        applyMilestoneTitlePlaceholders(
            milestoneSettings.titleSubtitle(), milestone, spawnLocations.size());
    if (!mainTitle.isEmpty() || !subTitle.isEmpty()) {
      player.sendTitle(
          mainTitle,
          subTitle,
          milestoneSettings.titleFadeIn(),
          milestoneSettings.titleStay(),
          milestoneSettings.titleFadeOut());
    }

    new BukkitRunnable() {
      private int index = 0;

      @Override
      public void run() {
        if (!player.isOnline() || !player.getWorld().equals(baseLocation.getWorld())) {
          cancel();
          return;
        }

        int spawnedThisTick = 0;
        while (index < spawnLocations.size()
            && spawnedThisTick < milestoneSettings.perTick()) {
          Location location = spawnLocations.get(index++);
          spawnPrimedTnt(location, milestoneSettings.fuseTicks());
          spawnedThisTick++;
        }

        if (index >= spawnLocations.size()) {
          cancel();
        }
      }
    }.runTaskTimer(this, 0L, milestoneSettings.tickInterval());
  }

  private void triggerOrbitalStrike(OrbitalStrikeInvocation invocation) {
    Player player = invocation.player();
    if (player == null || !player.isOnline()) {
      return;
    }

    Location baseLocation = player.getLocation();
    BridgeSettings bridgeSettings = getBridgeSettings(invocation.platform());
    if (bridgeSettings == null) {
      return;
    }
    if (!isWorldAllowed(baseLocation.getWorld(), bridgeSettings)) {
      return;
    }

    World world = baseLocation.getWorld();
    if (world == null) {
      return;
    }

    OrbitalStrikeSettings orbital = invocation.settings();
    double cappedRadius = Math.max(0.0D, orbital.radius());
    double baseY =
        Math.max(
            world.getMinHeight() + 1,
            Math.min(world.getMaxHeight() - 1, baseLocation.getY() + orbital.verticalOffset()));

    List<Location> spawnLocations = new ArrayList<>();
    int desired = Math.max(1, orbital.tntCount());
    for (int i = 0; i < desired; i++) {
      double angle = desired == 1 ? 0.0D : (Math.PI * 2.0D * i) / desired;
      double distance = cappedRadius <= 0.001D ? 0.0D : cappedRadius;
      double offsetX = Math.cos(angle) * distance;
      double offsetZ = Math.sin(angle) * distance;
      Location candidate =
          new Location(world, baseLocation.getX() + offsetX, baseY, baseLocation.getZ() + offsetZ);
      if (canSpawnTnt(candidate, bridgeSettings)) {
        spawnLocations.add(candidate);
      }
    }

    if (spawnLocations.isEmpty()) {
      getLogger()
          .log(
              Level.FINE,
              "Orbital strike skipped for donor {0}: unable to find safe spawn locations.",
              invocation.donor());
      return;
    }

    String mainTitle =
        applyTitlePlaceholders(
            orbital.titleMain(),
            invocation.donor(),
            invocation.amount(),
            invocation.formattedAmount(),
            invocation.currency(),
            invocation.donorMessage(),
            spawnLocations.size());
    String subTitle =
        applyTitlePlaceholders(
            orbital.titleSubtitle(),
            invocation.donor(),
            invocation.amount(),
            invocation.formattedAmount(),
            invocation.currency(),
            invocation.donorMessage(),
            spawnLocations.size());

    if (!mainTitle.isEmpty() || !subTitle.isEmpty()) {
      player.sendTitle(
          mainTitle,
          subTitle,
          orbital.titleFadeIn(),
          orbital.titleStay(),
          orbital.titleFadeOut());
    }

    new BukkitRunnable() {
      private int index = 0;

      @Override
      public void run() {
        if (!player.isOnline() || !player.getWorld().equals(world)) {
          cancel();
          return;
        }

        int spawned = 0;
        while (index < spawnLocations.size() && spawned < orbital.waveSize()) {
          Location location = spawnLocations.get(index++);
          spawnPrimedTnt(location, orbital.fuseTicks());
          spawned++;
        }

        if (index >= spawnLocations.size()) {
          cancel();
        }
      }
    }.runTaskTimer(this, 0L, orbital.tickInterval());
  }

  /** Reloads cached configuration values from {@code config.yml}. */
  public void loadSettingsFromConfig() {
    FileConfiguration config = getConfig();
    BridgeSettings youtube = BridgeSettings.from(config, "youtube-bridge");
    BridgeSettings tiktok = BridgeSettings.from(config, "tiktok-bridge");
    platformSettings.put(StreamPlatform.YOUTUBE, youtube);
    platformSettings.put(StreamPlatform.TIKTOK, tiktok);

    youtubeListenerSettings = ListenerSettings.from(config, "youtube");
    tikTokListenerSettings = ListenerSettings.from(config, "tiktok");
    listenerScriptPaths.put(StreamPlatform.YOUTUBE, youtubeListenerSettings.listenerScript());
    listenerScriptPaths.put(StreamPlatform.TIKTOK, tikTokListenerSettings.listenerScript());

    if (youtubeBridge != null) {
      youtubeBridge.setSubscriberMilestoneInterval(youtube.subscriberMilestoneInterval());
    }
    if (tikTokBridge != null) {
      tikTokBridge.setSubscriberMilestoneInterval(tiktok.subscriberMilestoneInterval());
    }
  }

  /** Runs a series of sanity checks to ensure the integration is ready to use. */
  public SelfTestResult runIntegrationSelfTest() {
    List<String> messages = new ArrayList<>();
    boolean passed = true;

    BridgeSettings youtubeSettings = getBridgeSettings(StreamPlatform.YOUTUBE);

    passed &= checkBridgeSettings(messages, youtubeSettings);
    passed &= checkBridgeInitialisation(messages);
    passed &= checkListenerProcess(messages);
    passed &= checkListenerConfiguration(messages);
    passed &= checkTargetPlayer(messages, youtubeSettings);

    return new SelfTestResult(passed, List.copyOf(messages));
  }

  /** Attempts to trigger the orbital strike routine using the configured settings. */
  public OrbitalStrikeDemoResult runOrbitalStrikeDemo() {
    List<String> messages = new ArrayList<>();

    BridgeSettings youtubeSettings = getBridgeSettings(StreamPlatform.YOUTUBE);
    if (youtubeSettings == null) {
      messages.add(
          ChatColor.RED
              + "Bridge settings are unavailable; reload the configuration first.");
      return new OrbitalStrikeDemoResult(false, messages);
    }

    if (!youtubeSettings.enabled()) {
      messages.add(ChatColor.RED + "Bridge features are disabled in config.yml.");
      return new OrbitalStrikeDemoResult(false, messages);
    }

    DonationSettings donationSettings = youtubeSettings.donationSettings();
    if (donationSettings == null || !donationSettings.enabled()) {
      messages.add(ChatColor.RED + "Donation reactions are disabled in config.yml.");
      return new OrbitalStrikeDemoResult(false, messages);
    }

    OrbitalStrikeSettings orbitalStrike = donationSettings.orbitalStrike();
    if (orbitalStrike == null || !orbitalStrike.enabled()) {
      messages.add(ChatColor.RED + "Orbital strike behaviour is disabled in config.yml.");
      return new OrbitalStrikeDemoResult(false, messages);
    }

    Optional<Player> target = resolveConfiguredPlayer(youtubeSettings);
    if (target.isEmpty()) {
      messages.add(
          ChatColor.RED + "No eligible target player is online for the orbital strike test.");
      return new OrbitalStrikeDemoResult(false, messages);
    }

    Player player = target.get();
    messages.add(
        ChatColor.GREEN
            + "Launching orbital strike test targeting "
            + ChatColor.YELLOW
            + player.getName()
            + ChatColor.GREEN
            + ".");

    double demoAmount = Math.max(orbitalStrike.minAmount(), 1.0D);
    String currency = Objects.requireNonNullElse(orbitalStrike.currency(), "");
    String formattedAmount = formatAmountText(demoAmount, currency, null);

    double finalAmount = demoAmount;
    String finalCurrency = currency;
    String finalFormattedAmount = formattedAmount;

    OrbitalStrikeInvocation invocation =
        new OrbitalStrikeInvocation(
            StreamPlatform.YOUTUBE,
            player,
            "Debug Supporter",
            "This is only a test of the orbital strike cannon.",
            finalFormattedAmount,
            finalAmount,
            finalCurrency,
            orbitalStrike);

    runOnMainThread(() -> triggerOrbitalStrike(invocation));

    return new OrbitalStrikeDemoResult(true, messages);
  }

  private boolean checkBridgeSettings(List<String> messages, BridgeSettings settings) {
    if (settings == null) {
      messages.add(ChatColor.RED + "Bridge settings could not be loaded from config.yml.");
      return false;
    }

    if (settings.enabled()) {
      messages.add(ChatColor.GREEN + "Bridge features are enabled in config.yml.");
    } else {
      messages.add(ChatColor.YELLOW + "Bridge features are currently disabled in config.yml.");
    }
    return true;
  }

  private boolean checkBridgeInitialisation(List<String> messages) {
    if (youtubeBridge == null) {
      messages.add(ChatColor.RED + "YouTube chat bridge has not been initialised.");
      return false;
    }

    messages.add(ChatColor.GREEN + "YouTube chat bridge is initialised.");
    return true;
  }

  private boolean checkListenerProcess(List<String> messages) {
    com.crimsonwarpedcraft.exampleplugin.service.YouTubeChatBridge process =
        listenerProcesses.get(StreamPlatform.YOUTUBE);
    if (process == null) {
      messages.add(ChatColor.RED + "Listener process is not available. Monitoring cannot start.");
      return false;
    }

    if (process.isRunning()) {
      messages.add(ChatColor.GREEN + "Listener process handler is ready and running.");
      return true;
    }

    messages.add(
        ChatColor.RED
            + "Listener process handler is available but not running. "
            + "Restart it with /ytstream start.");
    return false;
  }

  private boolean checkListenerConfiguration(List<String> messages) {
    if (youtubeListenerSettings == null) {
      messages.add(ChatColor.RED + "Listener settings are missing from config.yml.");
      return false;
    }

    boolean passed = true;
    String listenerUrl = youtubeListenerSettings.listenerUrl();
    boolean usingExternal = listenerUrl != null && !listenerUrl.isBlank();
    String streamIdentifier = youtubeListenerSettings.streamIdentifier();

    if (usingExternal) {
      messages.add(
          ChatColor.GREEN
              + "External listener URL configured: "
              + ChatColor.YELLOW
              + listenerUrl
              + ChatColor.GREEN
              + ".");
      return true;
    }

    if (streamIdentifier == null || streamIdentifier.isBlank()) {
      messages.add(
          ChatColor.RED
              + "No YouTube stream identifier configured. Set one with /ytstream setchat.");
      passed = false;
    } else {
      messages.add(
          ChatColor.GREEN
              + "Stream identifier configured: "
              + ChatColor.YELLOW
              + streamIdentifier
              + ChatColor.GREEN
              + ".");
    }

    File scriptFile = getListenerScriptFile(StreamPlatform.YOUTUBE);
    if (scriptFile.exists()) {
      if (scriptFile.canExecute()) {
        messages.add(
            ChatColor.GREEN
                + "Listener script present and executable at "
                + ChatColor.YELLOW
                + scriptFile.getAbsolutePath()
                + ChatColor.GREEN
                + ".");
      } else {
        messages.add(
            ChatColor.YELLOW
                + "Listener script present at "
                + ChatColor.GOLD
                + scriptFile.getAbsolutePath()
                + ChatColor.YELLOW
                + " but is not marked executable.");
      }
    } else {
      messages.add(
          ChatColor.RED
              + "Listener script could not be found at "
              + ChatColor.YELLOW
              + scriptFile.getAbsolutePath()
              + ChatColor.RED
              + ".");
      passed = false;
    }

    return passed;
  }

  private boolean checkTargetPlayer(List<String> messages, BridgeSettings settings) {
    String targetIgn = youtubeListenerSettings != null ? youtubeListenerSettings.targetIgn() : "";
    String fallbackTarget = settings != null ? settings.targetPlayer() : "";
    String effectiveTarget =
        (targetIgn != null && !targetIgn.isBlank()) ? targetIgn : fallbackTarget;

    if (effectiveTarget == null || effectiveTarget.isBlank()) {
      messages.add(
          ChatColor.RED
              + "No target player configured. Set one with /ytstream settarget or in config.yml.");
      return false;
    }

    messages.add(
        ChatColor.GREEN
            + "Target player configured as "
            + ChatColor.YELLOW
            + effectiveTarget
            + ChatColor.GREEN
            + ".");
    return true;
  }

  /** Result of {@link #runIntegrationSelfTest()}. */
  public static final class SelfTestResult {
    private final boolean passed;
    private final List<String> messages;

    /**
     * Creates a new immutable self-test result.
     *
     * @param passed whether the self-test succeeded
     * @param messages diagnostic messages gathered during the test
     */
    public SelfTestResult(boolean passed, List<String> messages) {
      this.passed = passed;
      this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /** Returns {@code true} when all checks succeeded. */
    public boolean passed() {
      return passed;
    }

    /** Returns the immutable list of diagnostic messages. */
    public List<String> messages() {
      return messages;
    }
  }

  /** Result of {@link #runOrbitalStrikeDemo()}. */
  public static final class OrbitalStrikeDemoResult {
    private final boolean triggered;
    private final List<String> messages;

    /**
     * Creates a new immutable orbital strike demo result.
     *
     * @param triggered whether the strike routine was started
     * @param messages diagnostic messages gathered during the attempt
     */
    public OrbitalStrikeDemoResult(boolean triggered, List<String> messages) {
      this.triggered = triggered;
      this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /** Returns {@code true} when the strike routine was triggered. */
    public boolean triggered() {
      return triggered;
    }

    /** Returns the immutable list of diagnostic messages. */
    public List<String> messages() {
      return messages;
    }
  }

  private record OrbitalStrikeInvocation(
      StreamPlatform platform,
      Player player,
      String donor,
      String donorMessage,
      String formattedAmount,
      Double amount,
      String currency,
      OrbitalStrikeSettings settings) {}

  private void loadSubscriberState() {
    FileConfiguration config = getConfig();
    loadSubscriberState(config, StreamPlatform.YOUTUBE, "youtube-bridge-state");
    loadSubscriberState(config, StreamPlatform.TIKTOK, "tiktok-bridge-state");
  }

  private void loadSubscriberState(
      FileConfiguration config, StreamPlatform platform, String sectionKey) {
    ConfigurationSection state = config.getConfigurationSection(sectionKey);
    long known = 0L;
    long milestone = 0L;
    if (state != null) {
      known = state.getLong("known-subscriber-count", 0L);
      milestone = state.getLong("last-celebrated-milestone", 0L);
    }
    knownSubscriberCounts.put(platform, known);
    lastCelebratedMilestones.put(platform, milestone);
  }

  private void persistSubscriberState(StreamPlatform platform) {
    FileConfiguration config = getConfig();
    String sectionKey =
        platform == StreamPlatform.TIKTOK ? "tiktok-bridge-state" : "youtube-bridge-state";
    ConfigurationSection state = config.getConfigurationSection(sectionKey);
    if (state == null) {
      state = config.createSection(sectionKey);
    }
    state.set("known-subscriber-count", getKnownSubscriberCount(platform));
    state.set("last-celebrated-milestone", getLastCelebratedMilestone(platform));
    saveConfig();
  }

  private record ListenerSettings(
      String streamIdentifier,
      String targetIgn,
      int pollingIntervalSeconds,
      String pythonExecutable,
      String listenerScript,
      String listenerUrl,
      String streamlabsSocketToken) {

    static ListenerSettings from(FileConfiguration config, String sectionKey) {
      ConfigurationSection root = config.getConfigurationSection(sectionKey);
      if (root == null) {
        root = config.createSection(sectionKey);
      }

      String streamIdentifier =
          Optional.ofNullable(root.getString("stream-identifier"))
              .map(String::trim)
              .orElse("");
      String targetIgn =
          Optional.ofNullable(root.getString("target-player-ign"))
              .map(String::trim)
              .orElse("");
      int pollingInterval = Math.max(1, root.getInt("polling-interval-seconds", 5));
      String pythonExecutable =
          Optional.ofNullable(root.getString("python-executable"))
              .map(String::trim)
              .filter(value -> !value.isEmpty())
              .orElse("python3");
      String listenerScript =
          Optional.ofNullable(root.getString("listener-script"))
              .map(String::trim)
              .filter(value -> !value.isEmpty())
              .orElse(DEFAULT_LISTENER_SCRIPT);
      String listenerUrl =
          Optional.ofNullable(root.getString("listener-url"))
              .map(String::trim)
              .filter(value -> !value.isEmpty())
              .orElse("");
      String environmentToken =
          Optional.ofNullable(System.getenv("STREAMLABS_SOCKET_TOKEN"))
              .map(String::trim)
              .filter(value -> !value.isEmpty())
              .orElse(null);
      String streamlabsSocketToken =
          environmentToken != null
              ? environmentToken
              : Optional.ofNullable(root.getString("streamlabs-socket-token"))
                  .map(String::trim)
                  .orElse("");

      return new ListenerSettings(
          streamIdentifier,
          targetIgn,
          pollingInterval,
          pythonExecutable,
          listenerScript,
          listenerUrl,
          streamlabsSocketToken);
    }
  }

  private record BridgeSettings(
      boolean enabled,
      String targetPlayer,
      Set<String> allowedWorlds,
      boolean chatEnabled,
      boolean chatTntEnabled,
      String chatTntCommand,
      int chatTntFuseTicks,
      double chatTntVerticalOffset,
      boolean subscriberKillEnabled,
      boolean milestoneEnabled,
      long subscriberMilestoneInterval,
      MilestoneSettings milestoneSettings,
      DonationSettings donationSettings) {

    static BridgeSettings from(FileConfiguration config, String sectionKey) {
      ConfigurationSection root = config.getConfigurationSection(sectionKey);
      if (root == null) {
        root = config.createSection(sectionKey);
      }

      final boolean enabled = root.getBoolean("enabled", true);
      final String targetPlayer = Objects.requireNonNullElse(root.getString("target-player"), "");

      Set<String> allowedWorlds = new HashSet<>();
      for (String world : root.getStringList("allowed-worlds")) {
        if (world != null && !world.isBlank()) {
          allowedWorlds.add(world.toLowerCase(Locale.ROOT));
        }
      }

      ConfigurationSection chat = root.getConfigurationSection("chat");
      if (chat == null) {
        chat = root.createSection("chat");
      }
      final boolean chatEnabled = chat.getBoolean("enabled", true);
      ConfigurationSection chatTnt = chat.getConfigurationSection("tnt");
      if (chatTnt == null) {
        chatTnt = chat.createSection("tnt");
      }
      final boolean chatTntEnabled = chatTnt.getBoolean("enabled", true);
      final String chatCommand = Objects.requireNonNullElse(chatTnt.getString("command"), "!tnt");
      final String chatTntCommand = chatCommand.toLowerCase(Locale.ROOT);
      final int chatTntFuseTicks = chatTnt.getInt("fuse-ticks", 60);
      final double chatTntVerticalOffset = chatTnt.getDouble("vertical-offset", 0.0);

      ConfigurationSection subscribers = root.getConfigurationSection("subscribers");
      if (subscribers == null) {
        subscribers = root.createSection("subscribers");
      }
      final boolean subscriberKillEnabled = subscribers.getBoolean("kill-enabled", true);

      ConfigurationSection milestone = subscribers.getConfigurationSection("milestone");
      if (milestone == null) {
        milestone = subscribers.createSection("milestone");
      }
      final boolean milestoneEnabled = milestone.getBoolean("enabled", true);
      final long milestoneInterval = milestone.getLong("interval", 100L);
      final int tntCount = milestone.getInt("tnt-count", 100);
      final double radius = milestone.getDouble("radius", 6.0D);
      final int fuseTicks = milestone.getInt("fuse-ticks", 80);
      final int perTick = Math.max(1, milestone.getInt("per-tick", 10));
      final long tickInterval = Math.max(1L, milestone.getLong("tick-interval", 2L));
      ConfigurationSection milestoneTitle = milestone.getConfigurationSection("title");
      if (milestoneTitle == null) {
        milestoneTitle = milestone.createSection("title");
      }
      final String milestoneTitleMain =
          Objects.requireNonNullElse(
              milestoneTitle.getString("main"),
              "&b{total_subscribers} Subscribers!");
      final String milestoneTitleSubtitle =
          Objects.requireNonNullElse(
              milestoneTitle.getString("subtitle"),
              "&eMilestone interval reached!");
      final int milestoneTitleFadeIn =
          Math.max(0, milestoneTitle.getInt("fade-in", 10));
      final int milestoneTitleStay = Math.max(0, milestoneTitle.getInt("stay", 60));
      final int milestoneTitleFadeOut =
          Math.max(0, milestoneTitle.getInt("fade-out", 20));

      ConfigurationSection donations = root.getConfigurationSection("donations");
      if (donations == null) {
        donations = root.createSection("donations");
      }
      final boolean donationsEnabled = donations.getBoolean("enabled", true);
      ConfigurationSection orbitalStrike = donations.getConfigurationSection("orbital-strike");
      if (orbitalStrike == null) {
        orbitalStrike = donations.createSection("orbital-strike");
      }
      final boolean orbitalStrikeEnabled = orbitalStrike.getBoolean("enabled", true);
      final double orbitalStrikeMinAmount = orbitalStrike.getDouble("min-amount", 5.0D);
      final String orbitalStrikeCurrency =
          Optional.ofNullable(orbitalStrike.getString("currency"))
              .map(String::trim)
              .filter(value -> !value.isEmpty())
              .map(value -> value.toUpperCase(Locale.ROOT))
              .orElse("");
      final int orbitalStrikeTntCount = Math.max(1, orbitalStrike.getInt("tnt-count", 100));
      final double orbitalStrikeVerticalOffset = orbitalStrike.getDouble("vertical-offset", 25.0D);
      final double orbitalStrikeRadius = Math.max(0.0D, orbitalStrike.getDouble("radius", 6.0D));
      final int orbitalStrikeFuseTicks = Math.max(0, orbitalStrike.getInt("fuse-ticks", 60));
      final int orbitalStrikeWaveSize = Math.max(1, orbitalStrike.getInt("wave-size", 20));
      final long orbitalStrikeTickInterval =
          Math.max(1L, orbitalStrike.getLong("tick-interval", 2L));
      ConfigurationSection orbitalStrikeTitle = orbitalStrike.getConfigurationSection("title");
      if (orbitalStrikeTitle == null) {
        orbitalStrikeTitle = orbitalStrike.createSection("title");
      }
      final String orbitalStrikeTitleMain =
          Objects.requireNonNullElse(
              orbitalStrikeTitle.getString("main"),
              "&c{donor} armed the Orbital Strike Cannon!");
      final String orbitalStrikeTitleSubtitle =
          Objects.requireNonNullElse(
              orbitalStrikeTitle.getString("subtitle"),
              "&eBrace for {tnt_count} TNT!");
      final int orbitalStrikeTitleFadeIn =
          Math.max(0, orbitalStrikeTitle.getInt("fade-in", 10));
      final int orbitalStrikeTitleStay = Math.max(0, orbitalStrikeTitle.getInt("stay", 40));
      final int orbitalStrikeTitleFadeOut =
          Math.max(0, orbitalStrikeTitle.getInt("fade-out", 20));

      DonationSettings donationSettings =
          new DonationSettings(
              donationsEnabled,
              new OrbitalStrikeSettings(
                  orbitalStrikeEnabled,
                  orbitalStrikeMinAmount,
                  orbitalStrikeCurrency,
                  orbitalStrikeTntCount,
                  orbitalStrikeVerticalOffset,
                  orbitalStrikeRadius,
                  orbitalStrikeFuseTicks,
                  orbitalStrikeWaveSize,
                  orbitalStrikeTickInterval,
                  orbitalStrikeTitleMain,
                  orbitalStrikeTitleSubtitle,
                  orbitalStrikeTitleFadeIn,
                  orbitalStrikeTitleStay,
                  orbitalStrikeTitleFadeOut));

      return new BridgeSettings(
          enabled,
          targetPlayer,
          allowedWorlds,
          chatEnabled,
          chatTntEnabled,
          chatTntCommand,
          chatTntFuseTicks,
          chatTntVerticalOffset,
          subscriberKillEnabled,
          milestoneEnabled,
          milestoneInterval,
          new MilestoneSettings(
              tntCount,
              radius,
              fuseTicks,
              perTick,
              tickInterval,
              milestoneTitleMain,
              milestoneTitleSubtitle,
              milestoneTitleFadeIn,
              milestoneTitleStay,
              milestoneTitleFadeOut),
          donationSettings);
    }
  }

  private record MilestoneSettings(
      int tntCount,
      double radius,
      int fuseTicks,
      int perTick,
      long tickInterval,
      String titleMain,
      String titleSubtitle,
      int titleFadeIn,
      int titleStay,
      int titleFadeOut) {}

  private record DonationSettings(boolean enabled, OrbitalStrikeSettings orbitalStrike) {}

  private record OrbitalStrikeSettings(
      boolean enabled,
      double minAmount,
      String currency,
      int tntCount,
      double verticalOffset,
      double radius,
      int fuseTicks,
      int waveSize,
      long tickInterval,
      String titleMain,
      String titleSubtitle,
      int titleFadeIn,
      int titleStay,
      int titleFadeOut) {}

  /**
   * Helper used by tests or debug scripts to emulate an incoming chat message without the
   * external Python process.
   */
  public void simulateChatMessage(String author, String message) {
    PlatformChatBridge bridge = youtubeBridge;
    if (bridge == null) {
      return;
    }
    bridge.emitChatMessage(
        new ChatMessage(author, message, 0L, Instant.now(), null));
  }

  /**
   * Helper used by tests or debug scripts to emulate a subscriber notification without the
   * external Python process.
   */
  public void simulateSubscriber(String author, String ign, long totalSubscribers) {
    PlatformChatBridge bridge = youtubeBridge;
    if (bridge == null) {
      return;
    }
    bridge.emitSubscriberNotification(
        new SubscriberNotification(author, ign, totalSubscribers, null, Instant.now()));
  }
}
