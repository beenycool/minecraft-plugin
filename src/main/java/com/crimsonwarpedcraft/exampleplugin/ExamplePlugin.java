package com.crimsonwarpedcraft.exampleplugin;

import com.crimsonwarpedcraft.exampleplugin.bridge.YouTubeChatBridge;
import com.crimsonwarpedcraft.exampleplugin.bridge.YouTubeChatBridge.ChatMessage;
import com.crimsonwarpedcraft.exampleplugin.bridge.YouTubeChatBridge.Registration;
import com.crimsonwarpedcraft.exampleplugin.bridge.YouTubeChatBridge.SubscriberMilestone;
import com.crimsonwarpedcraft.exampleplugin.bridge.YouTubeChatBridge.SubscriberNotification;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.papermc.lib.PaperLib;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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

  private YouTubeChatBridge bridge;
  private final List<Registration> registrations = new ArrayList<>();
  private final SecureRandom secureRandom = new SecureRandom();
  private BridgeSettings settings;
  private long knownSubscriberCount;
  private long lastCelebratedMilestone;

  @Override
  public void onEnable() {
    PaperLib.suggestPaper(this);

    saveDefaultConfig();
    reloadBridgeSettings();
    loadSubscriberState();

    bridge = new YouTubeChatBridge(this);
    bridge.setSubscriberMilestoneInterval(settings.subscriberMilestoneInterval());
    registrations.add(bridge.registerChatListener(this::handleChatMessage));
    registrations.add(bridge.registerSubscriberListener(this::handleSubscriberNotification));
    registrations.add(bridge.registerMilestoneListener(this::handleMilestone));

    getLogger().info("YouTube bridge initialised. Awaiting events from Python listener.");
  }

  @Override
  public void onDisable() {
    registrations.forEach(Registration::close);
    registrations.clear();
    bridge = null;
    knownSubscriberCount = 0L;
    lastCelebratedMilestone = 0L;
  }

  /** Returns the active YouTube chat bridge instance. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Bridge is intentionally shared so other components can subscribe to events.")
  public YouTubeChatBridge getYouTubeChatBridge() {
    return bridge;
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

  private void reloadBridgeSettings() {
    reloadConfig();
    FileConfiguration config = getConfig();
    settings = BridgeSettings.from(config);
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
