package com.crimsonwarpedcraft.exampleplugin.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
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
    executeCommandsAtPath("world-reset.pre-reset-commands");

    CompletableFuture<Void> resetFuture = CompletableFuture.completedFuture(null);
    for (TargetWorld target : targets) {
      resetFuture = resetFuture.thenCompose(ignored -> resetWorldAsync(target));
    }

    resetFuture.whenComplete(
        (ignored, throwable) -> {
          if (throwable != null) {
            plugin
                .getLogger()
                .log(
                    Level.SEVERE,
                    "One or more worlds failed to reset during the routine.",
                    throwable);
          }
          Bukkit.getScheduler()
              .runTask(
                  plugin,
                  () ->
                      Bukkit.getScheduler()
                          .runTaskLater(
                              plugin,
                              () -> {
                                returnPlayers(toRestore);
                                executeCommandsAtPath("world-reset.post-reset-commands");
                              },
                              TICKS_PER_SECOND * 5L));
        });
  }

  private void executeCommandsAtPath(String path) {
    List<String> commands = plugin.getConfig().getStringList(path);
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
    Set<String> targetNames = targets.stream().map(TargetWorld::name).collect(Collectors.toSet());
    for (Player player : Bukkit.getOnlinePlayers()) {
      World world = player.getWorld();
      if (world == null) {
        continue;
      }

      if (!targetNames.contains(world.getName())) {
        continue;
      }

      originals.put(player, SavedLocation.from(player));
      player.saveData();
      holdingLocation.teleport(player);
    }
    return originals;
  }

  private CompletableFuture<Void> resetWorldAsync(TargetWorld target) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    Bukkit.getScheduler().runTask(plugin, () -> runResetOnMainThread(target, future));
    return future;
  }

  @SuppressFBWarnings(
      value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
      justification = "Guard against edge cases where Bukkit returns a null world container.")
  private void runResetOnMainThread(TargetWorld target, CompletableFuture<Void> future) {
    World world = Bukkit.getWorld(target.name);
    WorldCreationSettings creationSettings = WorldCreationSettings.from(world);
    if (world != null) {
      world.save();
      if (!Bukkit.unloadWorld(world, true)) {
        plugin
            .getLogger()
            .log(
                Level.WARNING,
                "Failed to unload world {0}; skipping regeneration.",
                target.name);
        future.complete(null);
        return;
      }
    }

    java.io.File worldContainer = Bukkit.getWorldContainer();
    if (worldContainer == null) {
      plugin
          .getLogger()
          .log(
              Level.SEVERE,
              "World container is unavailable; skipping regeneration for {0}",
              target.name);
      future.complete(null);
      return;
    }

    Path worldFolder = worldContainer.toPath().resolve(target.name);
    Bukkit.getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              try {
                deleteDirectory(worldFolder);
                if (target.templateDirectory != null) {
                  Path templatePath = resolveTemplateDirectory(target.templateDirectory);
                  if (Files.notExists(templatePath)) {
                    plugin
                        .getLogger()
                        .warning(
                            "Template directory missing for world "
                                + target.name
                                + ": "
                                + templatePath);
                  } else {
                    copyDirectory(templatePath, worldFolder);
                  }
                }

                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          try {
                            WorldCreator creator = WorldCreator.name(target.name);
                            creationSettings.apply(creator);
                            creator.createWorld();
                            plugin
                                .getLogger()
                                .info("World reset completed for " + target.name);
                          } catch (Exception creationException) {
                            plugin
                                .getLogger()
                                .log(
                                    Level.SEVERE,
                                    "Failed to recreate world " + target.name,
                                    creationException);
                          } finally {
                            future.complete(null);
                          }
                        });
              } catch (Exception ex) {
                plugin
                    .getLogger()
                    .log(
                        Level.SEVERE,
                        "Failed to prepare world folder for " + target.name,
                        ex);
                future.complete(null);
              }
            });
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
    Path base = plugin.getDataFolder().toPath();
    Path templatePath = Path.of(configuredPath);
    Path resolved =
        templatePath.isAbsolute()
            ? templatePath.normalize()
            : base.resolve(templatePath).normalize();
    if (!resolved.startsWith(base)) {
      throw new IllegalArgumentException(
          "Template directory must be within the plugin data folder.");
    }
    return resolved;
  }

  private void deleteDirectory(Path path) throws IOException {
    if (Files.notExists(path)) {
      return;
    }

    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(
              Path file, BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (exc != null) {
              throw exc;
            }
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private void copyDirectory(Path source, Path destination) throws IOException {
    if (Files.notExists(source)) {
      return;
    }

    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(destination.resolve(source.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(
              Path file, BasicFileAttributes attrs) throws IOException {
            Files.copy(
                file,
                destination.resolve(source.relativize(file)),
                StandardCopyOption.REPLACE_EXISTING);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private record WorldCreationSettings(
      World.Environment environment, Long seed, WorldType worldType, ChunkGenerator generator) {
    private static WorldCreationSettings from(World world) {
      if (world == null) {
        return new WorldCreationSettings(null, null, null, null);
      }
      return new WorldCreationSettings(
          world.getEnvironment(),
          world.getSeed(),
          world.getWorldType(),
          world.getGenerator());
    }

    private void apply(WorldCreator creator) {
      if (environment != null) {
        creator.environment(environment);
      }
      if (worldType != null) {
        creator.type(worldType);
      }
      if (seed != null) {
        creator.seed(seed);
      }
      if (generator != null) {
        creator.generator(generator);
      }
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
