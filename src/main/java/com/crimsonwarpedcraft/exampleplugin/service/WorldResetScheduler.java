package com.crimsonwarpedcraft.exampleplugin.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/**
 * Handles scheduling and execution of periodic world reset routines.
 */
public class WorldResetScheduler {

  private static final long TICKS_PER_SECOND = 20L;
  private static final long SECONDS_PER_MINUTE = 60L;
  private static final long MINUTES_PER_HOUR = 60L;

  private final JavaPlugin plugin;
  private BukkitTask scheduledTask;

  /**
   * Creates a new scheduler bound to the provided plugin instance.
   *
   * @param plugin the owning plugin
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Plugin lifecycle requires keeping a reference to the managing instance.")
  public WorldResetScheduler(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  /**
   * Starts the repeating reset task using the interval configured in {@code config.yml}.
   */
  public void start() {
    cancel();

    long intervalHours = plugin.getConfig().getLong("world-reset.interval-hours", 5L);
    if (intervalHours < 1L) {
      plugin.getLogger().warning("Configured interval-hours is less than 1. Defaulting to 1 hour.");
      intervalHours = 1L;
    }

    long ticks = intervalHours * MINUTES_PER_HOUR * SECONDS_PER_MINUTE * TICKS_PER_SECOND;
    BukkitScheduler scheduler = Bukkit.getScheduler();
    scheduledTask = scheduler.runTaskTimer(plugin, this::runResetRoutine, ticks, ticks);
    plugin.getLogger().info("World reset scheduled every " + intervalHours + " hour(s).");
  }

  /**
   * Cancels the active repeating task if it is running.
   */
  public void cancel() {
    if (scheduledTask != null) {
      scheduledTask.cancel();
      scheduledTask = null;
    }
  }

  private void runResetRoutine() {
    List<TargetWorld> targets = loadTargetWorlds();
    if (targets.isEmpty()) {
      plugin.getLogger().warning("No target worlds configured for reset; skipping routine.");
      return;
    }

    HoldingLocation holdingLocation = loadHoldingLocation();
    if (holdingLocation == null) {
      plugin.getLogger().warning("Holding location is not configured correctly; skipping reset.");
      return;
    }

    Map<Player, SavedLocation> toRestore = evacuatePlayers(targets, holdingLocation);
    executeResetCommands();
    for (TargetWorld target : targets) {
      resetWorld(target);
    }

    // Delay the return slightly to ensure the worlds are fully initialised.
    Bukkit.getScheduler()
        .runTaskLater(plugin, () -> returnPlayers(toRestore), TICKS_PER_SECOND * 5L);
  }

  private void executeResetCommands() {
    List<String> commands = plugin.getConfig().getStringList("world-reset.reset-commands");
    if (commands.isEmpty()) {
      return;
    }

    for (String command : commands) {
      if (command == null || command.trim().isEmpty()) {
        continue;
      }
      Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }
  }

  private Map<Player, SavedLocation> evacuatePlayers(
      List<TargetWorld> targets, HoldingLocation holdingLocation) {
    Map<Player, SavedLocation> originals = new HashMap<>();
    for (Player player : Bukkit.getOnlinePlayers()) {
      World world = player.getWorld();
      if (world == null) {
        continue;
      }

      boolean inTarget = targets.stream().anyMatch(target -> target.name.equals(world.getName()));
      if (!inTarget) {
        continue;
      }

      originals.put(player, SavedLocation.from(player));
      player.saveData();
      holdingLocation.teleport(player);
    }
    return originals;
  }

  private void resetWorld(TargetWorld target) {
    World world = Bukkit.getWorld(target.name);
    if (world != null) {
      world.save();
      if (!Bukkit.unloadWorld(world, true)) {
        plugin
            .getLogger()
            .log(Level.WARNING, "Failed to unload world {0}; skipping regeneration.", target.name);
        return;
      }
    }

    Path worldFolder =
        Objects.requireNonNull(Bukkit.getWorldContainer(), "World container is unavailable")
            .toPath()
            .resolve(target.name);
    try {
      deleteDirectory(worldFolder);
      if (target.templateDirectory != null) {
        Path templatePath = resolveTemplateDirectory(target.templateDirectory);
        if (Files.notExists(templatePath)) {
          plugin
              .getLogger()
              .warning("Template directory missing for world " + target.name + ": " + templatePath);
        } else {
          copyDirectory(templatePath, worldFolder);
        }
      }
    } catch (IOException ex) {
      plugin.getLogger().log(Level.SEVERE, "Failed to prepare world folder for " + target.name, ex);
      return;
    }

    WorldCreator.name(target.name).createWorld();
    plugin.getLogger().info("World reset completed for " + target.name);
  }

  private void returnPlayers(Map<Player, SavedLocation> toRestore) {
    for (Map.Entry<Player, SavedLocation> entry : toRestore.entrySet()) {
      Player player = entry.getKey();
      if (!player.isOnline()) {
        continue;
      }

      SavedLocation savedLocation = entry.getValue();
      savedLocation.teleport(player);
    }
  }

  private HoldingLocation loadHoldingLocation() {
    ConfigurationSection section =
        plugin.getConfig().getConfigurationSection("world-reset.holding");
    if (section == null) {
      return null;
    }

    String worldName = section.getString("world");
    World world = worldName == null ? null : Bukkit.getWorld(worldName);
    if (world == null) {
      plugin.getLogger().warning("Holding world not found: " + worldName);
      return null;
    }

    double x = section.getDouble("x");
    double y = section.getDouble("y");
    double z = section.getDouble("z");
    float yaw = (float) section.getDouble("yaw", 0.0);
    float pitch = (float) section.getDouble("pitch", 0.0);
    return new HoldingLocation(world, x, y, z, yaw, pitch);
  }

  private List<TargetWorld> loadTargetWorlds() {
    List<TargetWorld> targets = new ArrayList<>();
    List<Map<?, ?>> configTargets = plugin.getConfig().getMapList("world-reset.target-worlds");
    if (configTargets.isEmpty()) {
      return targets;
    }

    for (Map<?, ?> entry : configTargets) {
      Object nameValue = entry.get("name");
      if (!(nameValue instanceof String)) {
        continue;
      }
      String name = (String) nameValue;
      String template = null;
      Object templateValue = entry.get("template-directory");
      if (templateValue instanceof String && !((String) templateValue).isEmpty()) {
        template = (String) templateValue;
      }
      targets.add(new TargetWorld(name, template));
    }

    return targets;
  }

  private Path resolveTemplateDirectory(String configuredPath) {
    Path templatePath = Path.of(configuredPath);
    if (!templatePath.isAbsolute()) {
      templatePath = plugin.getDataFolder().toPath().resolve(templatePath);
    }
    return templatePath;
  }

  private void deleteDirectory(Path path) throws IOException {
    if (Files.notExists(path)) {
      return;
    }

    List<Path> paths = new ArrayList<>();
    try (var stream = Files.walk(path)) {
      stream.forEach(paths::add);
    }
    paths.sort(Comparator.reverseOrder());
    for (Path p : paths) {
      Files.deleteIfExists(p);
    }
  }

  private void copyDirectory(Path source, Path destination) throws IOException {
    if (Files.notExists(source)) {
      return;
    }

    try (var stream = Files.walk(source)) {
      stream.forEach(
          path -> {
            Path target = destination.resolve(source.relativize(path));
            try {
              if (Files.isDirectory(path)) {
                Files.createDirectories(target);
              } else {
                Files.createDirectories(target.getParent());
                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
              }
            } catch (IOException ex) {
              throw new RuntimeException(ex);
            }
          });
    } catch (RuntimeException ex) {
      if (ex.getCause() instanceof IOException ioEx) {
        throw ioEx;
      }
      throw ex;
    }
  }

  private record TargetWorld(String name, String templateDirectory) {
    private TargetWorld {
      Objects.requireNonNull(name, "name");
    }
  }

  private record HoldingLocation(
      World world, double x, double y, double z, float yaw, float pitch) {
    private void teleport(Player player) {
      Location location = new Location(world, x, y, z, yaw, pitch);
      player.teleport(location);
    }
  }

  private record SavedLocation(
      String worldName, double x, double y, double z, float yaw, float pitch) {
    private static SavedLocation from(Player player) {
      Location location = player.getLocation();
      World world = Objects.requireNonNull(location.getWorld(), "Player world");
      return new SavedLocation(
          world.getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(),
          location.getPitch());
    }

    private void teleport(Player player) {
      World world = Bukkit.getWorld(worldName);
      if (world == null) {
        player.sendMessage(
            String.format("World \"%s\" is unavailable after reset.", worldName));
        return;
      }
      Location location = new Location(world, x, y, z, yaw, pitch);
      player.teleport(location);
    }
  }
}
