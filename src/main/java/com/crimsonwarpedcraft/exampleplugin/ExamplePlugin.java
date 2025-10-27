package com.crimsonwarpedcraft.exampleplugin;

import com.crimsonwarpedcraft.exampleplugin.bridge.YouTubeChatBridge;
import com.crimsonwarpedcraft.exampleplugin.bridge.YouTubeChatBridge.ChatMessage;
import com.crimsonwarpedcraft.exampleplugin.bridge.YouTubeChatBridge.Registration;
import com.crimsonwarpedcraft.exampleplugin.bridge.YouTubeChatBridge.SubscriberMilestone;
import com.crimsonwarpedcraft.exampleplugin.bridge.YouTubeChatBridge.SubscriberNotification;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Example plugin entry point that wires the YouTube bridge behaviours together.
 */
public class ExamplePlugin extends JavaPlugin {

  private static final String DEFAULT_LISTENER_SCRIPT = "python/chat_listener.py";

  private final AtomicLong messageSequence = new AtomicLong();

  // Fields from codex branch
  private WorldResetScheduler worldResetScheduler;

  // Fields from main branch
  private YouTubeChatBridge bridge;
  private final List<Registration> registrations = new ArrayList<>();
  private final SecureRandom secureRandom = new SecureRandom();
  private BridgeSettings settings;
  @SuppressFBWarnings(
      value = "AT_NONATOMIC_64BIT_PRIMITIVE",
      justification = "Access occurs on the server main thread so atomicity is unnecessary.")
  private long knownSubscriberCount;
  private long lastCelebratedMilestone;

  // Fields for the external Python listener bridge
  private com.crimsonwarpedcraft.exampleplugin.service.YouTubeChatBridge listenerProcess;
  private ListenerSettings listenerSettings;
  private String listenerScriptPath;

  @Override
  public void onEnable() {
    PaperLib.suggestPaper(this);

    saveDefaultConfig();

    reloadConfig();
    loadSettingsFromConfig();
    loadSubscriberState();
    ensureListenerScriptAvailable();

    listenerProcess = new com.crimsonwarpedcraft.exampleplugin.service.YouTubeChatBridge(this);

    // Logic from codex branch
    worldResetScheduler = new WorldResetScheduler(this);
    worldResetScheduler.start();

    // Logic from main branch
    bridge = new YouTubeChatBridge(this);
    bridge.setSubscriberMilestoneInterval(settings.subscriberMilestoneInterval());
    registrations.add(bridge.registerChatListener(this::handleChatMessage));
    registrations.add(bridge.registerSubscriberListener(this::handleSubscriberNotification));
    registrations.add(bridge.registerMilestoneListener(this::handleMilestone));

    registerCommands();
    restartMonitoring();

    getLogger().info("YouTube bridge initialised. Awaiting events from Python listener.");
  }

  @Override
  public void onDisable() {
    // Logic from codex branch
    if (worldResetScheduler != null) {
      worldResetScheduler.cancel();
    }

    if (listenerProcess != null) {
      com.crimsonwarpedcraft.exampleplugin.service.YouTubeChatBridge process = listenerProcess;
      listenerProcess = null;
      stopListenerProcessAsync(process, null);
    }

    // Logic from main branch
    registrations.forEach(Registration::close);
    registrations.clear();
    bridge = null;
    knownSubscriberCount = 0L;
    lastCelebratedMilestone = 0L;
  }

  // Methods below coordinate the YouTube bridge behaviours

  /** Returns the active YouTube chat bridge instance. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Bridge is intentionally shared so other components can subscribe to events.")
  public YouTubeChatBridge getYouTubeChatBridge() {
    return bridge;
  }

  private void registerCommands() {
    PluginCommand command = getCommand("ytstream");
    if (command == null) {
      getLogger()
          .warning("Failed to register /ytstream command; command not defined in plugin.yml");
      return;
    }

    YouTubeIntegrationCommand executor = new YouTubeIntegrationCommand(this);
    command.setExecutor(executor);
    command.setTabCompleter(executor);
  }

  /** Restarts the external Python listener process using the cached configuration. */
  public void restartMonitoring() {
    if (listenerProcess == null) {
      return;
    }

    com.crimsonwarpedcraft.exampleplugin.service.YouTubeChatBridge process = listenerProcess;

    if (listenerSettings == null) {
      stopListenerProcessAsync(process, null);
      return;
    }

    final String listenerUrl = listenerSettings.listenerUrl();
    boolean useExternalListener = listenerUrl != null && !listenerUrl.isBlank();

    String streamIdentifier = listenerSettings.streamIdentifier();
    if (!useExternalListener && (streamIdentifier == null || streamIdentifier.isBlank())) {
      getLogger().warning("No YouTube stream identifier configured; skipping listener start.");
      stopListenerProcessAsync(process, null);
      return;
    }

    final File listenerScript;
    if (!useExternalListener) {
      ensureListenerScriptAvailable();
      listenerScript = getListenerScriptFile();
    } else {
      listenerScript = null;
    }

    String targetIgn = listenerSettings.targetIgn();
    if ((targetIgn == null || targetIgn.isBlank()) && settings != null) {
      targetIgn = settings.targetPlayer();
    }

    String finalTarget = targetIgn;
    stopListenerProcessAsync(
        process,
        () ->
            startListenerProcess(
                process,
                listenerSettings.pythonExecutable(),
                listenerScript,
                streamIdentifier,
                listenerSettings.pollingIntervalSeconds(),
                finalTarget,
                listenerSettings.streamlabsSocketToken(),
                listenerUrl));
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
    if (listenerScriptPath == null || listenerScriptPath.isBlank()) {
      listenerScriptPath = DEFAULT_LISTENER_SCRIPT;
    }

    File scriptFile = getListenerScriptFile();
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
          .info("Extracted default YouTube listener script to " + scriptFile.getAbsolutePath());
    } catch (IOException e) {
      getLogger().log(Level.SEVERE, "Failed to extract listener script", e);
    }
  }

  private File getListenerScriptFile() {
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

    String configuredPath = listenerScriptPath;
    if (configuredPath == null || configuredPath.isBlank()) {
      configuredPath = DEFAULT_LISTENER_SCRIPT;
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

  /** Handles chat lines emitted by the Python listener. */
  public void handleIncomingYouTubeMessage(String message, String targetIgn) {
    if (message == null || message.isBlank()) {
      return;
    }

    String trimmed = message.trim();
    if (handleStructuredListenerPayload(trimmed, targetIgn)) {
      return;
    }

    String author = "YouTube";
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

    publishChatMessage(author, content, Instant.now(), null, targetIgn);
  }

  private boolean handleStructuredListenerPayload(String payload, String targetIgn) {
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
    if (type == null || type.isBlank()) {
      return false;
    }

    switch (type.toLowerCase(Locale.ROOT)) {
      case "chat" -> {
        handleStructuredChat(root, targetIgn);
        return true;
      }
      case "subscriber" -> {
        handleStructuredSubscriber(root);
        return true;
      }
      case "milestone" -> {
        handleStructuredMilestone(root);
        return true;
      }
      case "log", "status", "heartbeat" -> {
        handleStructuredLog(root, Level.INFO);
        return true;
      }
      case "error" -> {
        handleStructuredLog(root, Level.SEVERE);
        return true;
      }
      default -> {
        return false;
      }
    }
  }

  private void handleStructuredChat(JsonObject payload, String targetIgn) {
    String author = Objects.requireNonNullElse(jsonString(payload, "author"), "YouTube");
    String message = jsonString(payload, "message");
    if (message == null || message.isBlank()) {
      return;
    }

    String channelId = jsonString(payload, "channelId");
    Instant timestamp = parseTimestamp(jsonString(payload, "timestamp"));

    publishChatMessage(author, message, timestamp, channelId, targetIgn);
  }

  private void handleStructuredSubscriber(JsonObject payload) {
    if (bridge == null) {
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
      totalSubscribers = knownSubscriberCount + 1;
    }

    String channelId = jsonString(payload, "channelId");
    Instant timestamp = parseTimestamp(jsonString(payload, "timestamp"));
    String author =
        Objects.requireNonNullElse(jsonString(payload, "author"), "YouTube Subscriber");

    bridge.emitSubscriberNotification(
        new SubscriberNotification(author, ign, totalSubscribers, channelId, timestamp));
  }

  private void handleStructuredMilestone(JsonObject payload) {
    if (bridge == null) {
      return;
    }

    Long totalSubscribers = jsonLong(payload, "totalSubscribers");
    Long interval = jsonLong(payload, "milestoneInterval");
    if (totalSubscribers == null || interval == null) {
      return;
    }

    String channelId = jsonString(payload, "channelId");
    Instant timestamp = parseTimestamp(jsonString(payload, "timestamp"));

    bridge.emitMilestone(new SubscriberMilestone(totalSubscribers, interval, channelId, timestamp));
  }

  private void handleStructuredLog(JsonObject payload, Level fallbackLevel) {
    String message = jsonString(payload, "message");
    if (message == null || message.isBlank()) {
      return;
    }

    Level level = parseLogLevel(jsonString(payload, "level"), fallbackLevel);
    getLogger().log(level, message);
  }

  private void publishChatMessage(
      String author, String content, Instant timestamp, String channelId, String targetIgn) {
    String resolvedAuthor = Objects.requireNonNullElse(author, "YouTube");
    if (bridge != null) {
      bridge.emitChatMessage(
          new ChatMessage(
              resolvedAuthor,
              content,
              messageSequence.incrementAndGet(),
              timestamp,
              channelId));
    }

    deliverChatToPlayers(resolvedAuthor, content, targetIgn);
  }

  private void deliverChatToPlayers(String author, String content, String targetIgn) {
    String resolvedAuthor = author == null || author.isBlank() ? "YouTube" : author;
    String formatted;
    if (resolvedAuthor.equals("YouTube")) {
      formatted = ChatColor.RED + "[YouTube] " + ChatColor.WHITE + content;
    } else {
      formatted =
          ChatColor.RED
              + "[YouTube] "
              + ChatColor.YELLOW
              + resolvedAuthor
              + ChatColor.WHITE
              + ": "
              + content;
    }

    if (targetIgn != null && !targetIgn.isBlank()) {
      Player player = Bukkit.getPlayerExact(targetIgn);
      if (player != null
          && player.isOnline()
          && player.hasPermission("example.ytstream.monitor")) {
        player.sendMessage(formatted);
        return;
      }
    }

    Bukkit.getOnlinePlayers().stream()
        .filter(player -> player.hasPermission("example.ytstream.monitor"))
        .forEach(player -> player.sendMessage(formatted));
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

  private void handleChatMessage(ChatMessage message) {
    if (!settings.enabled || !settings.chatEnabled || !settings.chatTntEnabled) {
      return;
    }

    String lowerMessage = message.message().toLowerCase(Locale.ROOT);
    if (!lowerMessage.contains(settings.chatTntCommand)) {
      return;
    }

    Optional<Player> target = resolveConfiguredPlayer();
    if (target.isEmpty()) {
      return;
    }

    Player player = target.get();
    spawnSingleTnt(
        player.getLocation(), settings.chatTntFuseTicks, settings.chatTntVerticalOffset);
  }

  private void handleSubscriberNotification(SubscriberNotification notification) {
    boolean subscriberCountChanged =
        updateKnownSubscriberCount(notification.totalSubscribers());
    if (subscriberCountChanged) {
      persistSubscriberState();
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
              if (!isWorldAllowed(target.getWorld())) {
                return;
              }
              target.setHealth(0.0);
            });
  }

  private void handleMilestone(SubscriberMilestone milestone) {
    updateKnownSubscriberCount(milestone.totalSubscribers());

    if (!settings.enabled || !settings.milestoneEnabled) {
      return;
    }

    if (milestone.totalSubscribers() <= lastCelebratedMilestone) {
      return;
    }

    Optional<Player> target = resolveConfiguredPlayer();
    if (target.isEmpty()) {
      return;
    }

    Player player = target.get();
    spawnMilestoneCelebration(player, milestone);
    lastCelebratedMilestone = milestone.totalSubscribers();
    persistSubscriberState();
  }

  private Optional<Player> resolveConfiguredPlayer() {
    if (settings.targetPlayer == null || settings.targetPlayer.isBlank()) {
      return Optional.empty();
    }
    Player player = Bukkit.getPlayerExact(settings.targetPlayer);
    if (player == null || !player.isOnline()) {
      return Optional.empty();
    }
    if (!isWorldAllowed(player.getWorld())) {
      return Optional.empty();
    }
    if (!player.getLocation().getChunk().isLoaded()) {
      return Optional.empty();
    }
    return Optional.of(player);
  }

  private boolean isWorldAllowed(World world) {
    return settings.allowedWorlds.isEmpty()
        || settings.allowedWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
  }

  private void spawnSingleTnt(Location baseLocation, int fuseTicks, double verticalOffset) {
    Location spawnLocation = baseLocation.clone().add(new Vector(0, verticalOffset, 0));
    if (!canSpawnTnt(spawnLocation)) {
      return;
    }
    spawnPrimedTnt(spawnLocation, fuseTicks);
  }

  private boolean canSpawnTnt(Location location) {
    World world = location.getWorld();
    if (world == null) {
      return false;
    }
    if (!isWorldAllowed(world)) {
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
          tnt.setFuseTicks(Math.max(0, fuseTicks));
        });
  }

  private void spawnMilestoneCelebration(Player player, SubscriberMilestone milestone) {
    MilestoneSettings milestoneSettings = settings.milestoneSettings;
    if (milestoneSettings.tntCount <= 0) {
      return;
    }

    Location baseLocation = player.getLocation();
    if (!canSpawnTnt(baseLocation)) {
      return;
    }

    List<Location> spawnLocations = new ArrayList<>();
    for (int i = 0; i < milestoneSettings.tntCount; i++) {
      double angle = secureRandom.nextDouble() * Math.PI * 2.0;
      double distance = secureRandom.nextDouble() * milestoneSettings.radius;
      double offsetX = Math.cos(angle) * distance;
      double offsetZ = Math.sin(angle) * distance;
      Location location =
          new Location(
              baseLocation.getWorld(),
              baseLocation.getX() + offsetX,
              baseLocation.getY(),
              baseLocation.getZ() + offsetZ);
      if (canSpawnTnt(location)) {
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

    new BukkitRunnable() {
      private int index = 0;

      @Override
      public void run() {
        if (!player.isOnline() || !player.getWorld().equals(baseLocation.getWorld())) {
          cancel();
          return;
        }

        int spawnedThisTick = 0;
        while (index < spawnLocations.size() && spawnedThisTick < milestoneSettings.perTick) {
          Location location = spawnLocations.get(index++);
          spawnPrimedTnt(location, milestoneSettings.fuseTicks);
          spawnedThisTick++;
        }

        if (index >= spawnLocations.size()) {
          cancel();
        }
      }
    }.runTaskTimer(this, 0L, milestoneSettings.tickInterval);
  }

  /** Reloads cached configuration values from {@code config.yml}. */
  public void loadSettingsFromConfig() {
    FileConfiguration config = getConfig();
    settings = BridgeSettings.from(config);
    listenerSettings = ListenerSettings.from(config);
    listenerScriptPath = listenerSettings.listenerScript();
    if (bridge != null) {
      bridge.setSubscriberMilestoneInterval(settings.subscriberMilestoneInterval());
    }
  }

  private void loadSubscriberState() {
    FileConfiguration config = getConfig();
    ConfigurationSection state = config.getConfigurationSection("youtube-bridge-state");
    if (state == null) {
      knownSubscriberCount = 0L;
      lastCelebratedMilestone = 0L;
      return;
    }
    knownSubscriberCount = state.getLong("known-subscriber-count", 0L);
    lastCelebratedMilestone = state.getLong("last-celebrated-milestone", 0L);
  }

  private void persistSubscriberState() {
    FileConfiguration config = getConfig();
    ConfigurationSection state = config.getConfigurationSection("youtube-bridge-state");
    if (state == null) {
      state = config.createSection("youtube-bridge-state");
    }
    state.set("known-subscriber-count", knownSubscriberCount);
    state.set("last-celebrated-milestone", lastCelebratedMilestone);
    saveConfig();
  }

  private boolean updateKnownSubscriberCount(long totalSubscribers) {
    long updated = Math.max(knownSubscriberCount, totalSubscribers);
    if (updated != knownSubscriberCount) {
      knownSubscriberCount = updated;
      return true;
    }
    return false;
  }

  private record ListenerSettings(
      String streamIdentifier,
      String targetIgn,
      int pollingIntervalSeconds,
      String pythonExecutable,
      String listenerScript,
      String listenerUrl,
      String streamlabsSocketToken) {

    static ListenerSettings from(FileConfiguration config) {
      ConfigurationSection root = config.getConfigurationSection("youtube");
      if (root == null) {
        root = config.createSection("youtube");
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
      MilestoneSettings milestoneSettings) {

    static BridgeSettings from(FileConfiguration config) {
      ConfigurationSection root = config.getConfigurationSection("youtube-bridge");
      if (root == null) {
        root = config.createSection("youtube-bridge");
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
          new MilestoneSettings(tntCount, radius, fuseTicks, perTick, tickInterval));
    }
  }

  private record MilestoneSettings(
      int tntCount, double radius, int fuseTicks, int perTick, long tickInterval) {}

  /**
   * Helper used by tests or debug scripts to emulate an incoming chat message without the
   * external Python process.
   */
  public void simulateChatMessage(String author, String message) {
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
    if (bridge == null) {
      return;
    }
    bridge.emitSubscriberNotification(
        new SubscriberNotification(author, ign, totalSubscribers, null, Instant.now()));
  }
}