package com.crimsonwarpedcraft.exampleplugin.command;

import com.crimsonwarpedcraft.exampleplugin.ExamplePlugin;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/**
 * Command that forces a world to stay at night while periodically eliminating a target player.
 */
public class NightPunishCommand implements CommandExecutor, TabCompleter {

  private static final int TICKS_PER_MINUTE = 60 * 20;

  private final ExamplePlugin plugin;
  private final Map<UUID, BukkitTask> activePunishments = new HashMap<>();
  private final Map<UUID, World> trackedWorlds = new HashMap<>();
  private final Map<World, Integer> worldNightLocks = new HashMap<>();
  private final Map<World, Boolean> originalDaylightCycle = new HashMap<>();
  private final Map<UUID, String> lastKnownNames = new HashMap<>();

  /**
   * Creates a new command executor bound to the owning plugin instance.
   *
   * @param plugin the plugin registering the command
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public NightPunishCommand(ExamplePlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(
      @NotNull CommandSender sender,
      @NotNull Command command,
      @NotNull String label,
      String[] args) {
    if (!sender.hasPermission("example.nightpunish.use")) {
      sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
      return true;
    }

    if (args.length == 0) {
      sendUsage(sender, label);
      return true;
    }

    String subcommand = args[0].toLowerCase(Locale.ROOT);
    if (subcommand.equals("start")) {
      return handleStart(sender, args);
    }
    if (subcommand.equals("stop")) {
      return handleStop(sender, args);
    }

    // Allow shorthand without explicit start keyword: /nightpunish <player> <minutes>
    if (args.length >= 2) {
      return handleStart(sender, new String[] {"start", args[0], args[1]});
    }

    sendUsage(sender, label);
    return true;
  }

  private boolean handleStart(CommandSender sender, String[] args) {
    if (args.length < 3) {
      sender.sendMessage(ChatColor.RED + "Usage: /nightpunish start <player> <minutes>");
      return true;
    }

    Player target = Bukkit.getPlayerExact(args[1]);
    if (target == null) {
      sender.sendMessage(
          ChatColor.RED
              + "Player "
              + ChatColor.YELLOW
              + args[1]
              + ChatColor.RED
              + " is not online.");
      return true;
    }

    double minutes;
    try {
      minutes = Double.parseDouble(args[2]);
    } catch (NumberFormatException ex) {
      sender.sendMessage(ChatColor.RED + "Minutes must be a positive number.");
      return true;
    }

    if (minutes <= 0) {
      sender.sendMessage(ChatColor.RED + "Minutes must be greater than zero.");
      return true;
    }

    UUID targetId = target.getUniqueId();
    if (activePunishments.containsKey(targetId)) {
      sender.sendMessage(
          ChatColor.RED
              + "A punishment task already exists for "
              + ChatColor.YELLOW
              + target.getName()
              + ChatColor.RED
              + ". Use /nightpunish stop to cancel it.");
      return true;
    }

    final long intervalTicks = Math.max(1L, Math.round(minutes * TICKS_PER_MINUTE));
    World world = target.getWorld();
    applyNightLock(world);
    trackedWorlds.put(targetId, world);
    lastKnownNames.put(targetId, target.getName());

    BukkitTask task =
        new BukkitRunnable() {
          @Override
          public void run() {
            Player onlineTarget = Bukkit.getPlayer(targetId);
            if (onlineTarget == null || !onlineTarget.isOnline()) {
              cancelPunishment(targetId, false);
              return;
            }

            World currentWorld = onlineTarget.getWorld();
            World lockedWorld = trackedWorlds.get(targetId);
            if (lockedWorld == null) {
              trackedWorlds.put(targetId, currentWorld);
              applyNightLock(currentWorld);
              lockedWorld = currentWorld;
            }

            if (!lockedWorld.equals(currentWorld)) {
              // Update world tracking if the player changes dimensions.
              releaseNightLock(lockedWorld);
              applyNightLock(currentWorld);
              trackedWorlds.put(targetId, currentWorld);
            }

            onlineTarget.setHealth(0.0);
            onlineTarget.sendMessage(
                ChatColor.DARK_PURPLE
                    + "The night claims you again. Survive until daylight to break the curse!"
                    + ChatColor.GRAY
                    + " (Cast by "
                    + (sender instanceof Player ? ((Player) sender).getDisplayName() : "the void")
                    + ChatColor.GRAY
                    + ")");
          }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks);

    activePunishments.put(targetId, task);

    sender.sendMessage(
        ChatColor.GREEN
            + "Night punishment started for "
            + ChatColor.YELLOW
            + target.getName()
            + ChatColor.GREEN
            + " every "
            + ChatColor.YELLOW
            + minutes
            + ChatColor.GREEN
            + " minutes.");
    target.sendMessage(
        ChatColor.DARK_RED
            + "An eerie chill locks the world in eternal night. You will fall every "
            + minutes
            + " minutes until the curse is lifted!");
    return true;
  }

  private boolean handleStop(CommandSender sender, String[] args) {
    if (args.length < 2) {
      sender.sendMessage(ChatColor.RED + "Usage: /nightpunish stop <player>");
      return true;
    }

    Player target = Bukkit.getPlayerExact(args[1]);
    UUID targetId = target != null ? target.getUniqueId() : findTargetId(args[1]);

    if (targetId == null || !activePunishments.containsKey(targetId)) {
      sender.sendMessage(
          ChatColor.RED
              + "No active punishment for "
              + ChatColor.YELLOW
              + args[1]
              + ChatColor.RED
              + ".");
      return true;
    }

    String displayName =
        target != null ? target.getName() : lastKnownNames.getOrDefault(targetId, args[1]);

    cancelPunishment(targetId, true);

    sender.sendMessage(
        ChatColor.GREEN
            + "Night punishment removed for "
            + ChatColor.YELLOW
            + displayName
            + ChatColor.GREEN
            + ".");
    if (target != null && target.isOnline()) {
      target.sendMessage(ChatColor.GREEN + "The night curse fades and daylight may return.");
    }
    return true;
  }

  private void sendUsage(CommandSender sender, String label) {
    sender.sendMessage(
        ChatColor.RED
            + "Usage: /"
            + label
            + " start <player> <minutes>"
            + ChatColor.GRAY
            + " or /"
            + label
            + " stop <player>");
  }

  private void cancelPunishment(UUID targetId, boolean explicit) {
    BukkitTask task = activePunishments.remove(targetId);
    if (task != null) {
      task.cancel();
    }

    World world = trackedWorlds.remove(targetId);
    if (world != null) {
      releaseNightLock(world);
    }

    lastKnownNames.remove(targetId);

    if (!explicit) {
      Player target = Bukkit.getPlayer(targetId);
      if (target != null) {
        target.sendMessage(ChatColor.GREEN + "The night curse lifts as you leave the realm.");
      }
    }
  }

  private void applyNightLock(World world) {
    world.setTime(18000L);
    int locks = worldNightLocks.getOrDefault(world, 0);
    if (locks == 0) {
      originalDaylightCycle.putIfAbsent(
          world, world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE));
      world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
    }
    worldNightLocks.put(world, locks + 1);
  }

  private void releaseNightLock(World world) {
    Integer locks = worldNightLocks.get(world);
    if (locks == null) {
      return;
    }

    if (locks <= 1) {
      worldNightLocks.remove(world);
      Boolean original = originalDaylightCycle.remove(world);
      world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, original == null || original);
    } else {
      worldNightLocks.put(world, locks - 1);
    }
  }

  /**
   * Cancels any outstanding punishment tasks and restores affected worlds to their default state.
   */
  public void shutdown() {
    for (BukkitTask task : new ArrayList<>(activePunishments.values())) {
      task.cancel();
    }
    activePunishments.clear();

    for (Entry<World, Boolean> entry : new ArrayList<>(originalDaylightCycle.entrySet())) {
      World world = entry.getKey();
      Boolean original = entry.getValue();
      world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, original == null || original);
    }
    worldNightLocks.clear();
    originalDaylightCycle.clear();
    trackedWorlds.clear();
    lastKnownNames.clear();
  }

  @Override
  public List<String> onTabComplete(
      @NotNull CommandSender sender, Command command, @NotNull String alias, String[] args) {
    if (!sender.hasPermission("example.nightpunish.use")) {
      return Collections.emptyList();
    }

    if (args.length == 1) {
      return filterSuggestions(args[0], List.of("start", "stop"));
    }

    if (args.length == 2 && "stop".equalsIgnoreCase(args[0])) {
      return filterPlayerNames(args[1], activePunishments.keySet());
    }

    if (args.length == 2 && "start".equalsIgnoreCase(args[0])) {
      return filterPlayerNames(args[1], null);
    }

    return Collections.emptyList();
  }

  private List<String> filterSuggestions(String input, List<String> options) {
    String lower = input.toLowerCase(Locale.ROOT);
    List<String> matches = new ArrayList<>();
    for (String option : options) {
      if (option.startsWith(lower)) {
        matches.add(option);
      }
    }
    return matches;
  }

  private List<String> filterPlayerNames(String prefix, Iterable<UUID> includeOnly) {
    String lower = prefix.toLowerCase(Locale.ROOT);
    Set<String> matches = new LinkedHashSet<>();
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (includeOnly != null && !containsUuid(includeOnly, player.getUniqueId())) {
        continue;
      }
      String name = player.getName();
      if (name.toLowerCase(Locale.ROOT).startsWith(lower)) {
        matches.add(name);
      }
    }

    if (includeOnly != null) {
      for (Entry<UUID, String> entry : lastKnownNames.entrySet()) {
        if (!containsUuid(includeOnly, entry.getKey())) {
          continue;
        }
        String name = entry.getValue();
        if (name != null
            && name.toLowerCase(Locale.ROOT).startsWith(lower)) {
          matches.add(name);
        }
      }
    }
    return new ArrayList<>(matches);
  }

  private boolean containsUuid(Iterable<UUID> uuids, UUID candidate) {
    if (uuids instanceof java.util.Collection<?>) {
      return ((java.util.Collection<?>) uuids).contains(candidate);
    }
    for (UUID uuid : uuids) {
      if (uuid.equals(candidate)) {
        return true;
      }
    }
    return false;
  }

  private UUID findTargetId(String name) {
    if (name == null || name.isEmpty()) {
      return null;
    }

    Player online = Bukkit.getPlayerExact(name);
    if (online != null) {
      return online.getUniqueId();
    }

    for (Entry<UUID, String> entry : lastKnownNames.entrySet()) {
      String storedName = entry.getValue();
      if (storedName != null && storedName.equalsIgnoreCase(name)) {
        return entry.getKey();
      }
    }
    return null;
  }
}
