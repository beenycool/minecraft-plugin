package com.crimsonwarpedcraft.exampleplugin.command;

import com.crimsonwarpedcraft.exampleplugin.ExamplePlugin;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Command handler that controls the YouTube chat integration.
 */
public class YouTubeIntegrationCommand implements CommandExecutor, TabCompleter {

  private static final List<String> SUBCOMMANDS =
      Arrays.asList("setchat", "settarget", "reload", "test");
  private static final String ORBITAL_STRIKE_SCENARIO = "orbitalstrike";
  private final ExamplePlugin plugin;

  /**
   * Creates a new command handler bound to the supplied plugin instance.
   *
   * @param plugin the owning plugin
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public YouTubeIntegrationCommand(ExamplePlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission("example.ytstream.use")) {
      sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
      return true;
    }

    if (args.length == 0) {
      sendUsage(sender, label);
      return true;
    }

    String subcommand = args[0].toLowerCase();
    switch (subcommand) {
      case "setchat":
        return handleSetChat(sender, args);
      case "settarget":
        return handleSetTarget(sender, args);
      case "reload":
        plugin.reloadConfig();
        plugin.loadSettingsFromConfig();
        plugin.restartMonitoring();
        sender.sendMessage(
            ChatColor.GREEN
                + "Reloaded YouTube stream configuration and "
                + "restarted monitoring.");
        return true;
      case "test":
        return handleSelfTest(sender, args);
      default:
        sendUsage(sender, label);
        return true;
    }
  }

  private boolean handleSetChat(CommandSender sender, String[] args) {
    if (args.length < 2) {
      sender.sendMessage(ChatColor.RED + "Usage: /ytstream setchat <chatId|url>");
      return true;
    }

    String identifier = args[1];
    updateConfigValueAndRestart("youtube.stream-identifier", identifier);
    sender.sendMessage(
        ChatColor.GREEN
            + "YouTube stream identifier set to "
            + ChatColor.YELLOW
            + identifier
            + ChatColor.GREEN
            + ".");
    return true;
  }

  private boolean handleSetTarget(CommandSender sender, String[] args) {
    if (args.length < 2) {
      sender.sendMessage(ChatColor.RED + "Usage: /ytstream settarget <player>");
      return true;
    }

    String ign = args[1];
    updateConfigValueAndRestart("youtube.target-player-ign", ign);
    sender.sendMessage(
        ChatColor.GREEN
            + "Target player set to "
            + ChatColor.YELLOW
            + ign
            + ChatColor.GREEN
            + ".");
    return true;
  }

  private void updateConfigValueAndRestart(String key, String value) {
    plugin.getConfig().set(key, value);
    // Also update the legacy bridge setting to keep them in sync.
    if ("youtube.target-player-ign".equals(key)) {
      plugin.getConfig().set("youtube-bridge.target-player", value);
    }
    plugin.saveConfig();
    plugin.loadSettingsFromConfig();
    plugin.restartMonitoring();
  }

  private void sendUsage(CommandSender sender, String label) {
    sender.sendMessage(
        ChatColor.RED
            + "Usage: /"
            + label
            + " <setchat|settarget|reload|test> [scenario]"
            + ChatColor.YELLOW
            + " (try /"
            + label
            + " test "
            + ORBITAL_STRIKE_SCENARIO
            + ")");
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    if (!sender.hasPermission("example.ytstream.use")) {
      return Collections.emptyList();
    }

    if (args.length == 1) {
      String input = args[0].toLowerCase();
      List<String> matches = new ArrayList<>();
      for (String sub : SUBCOMMANDS) {
        if (sub.startsWith(input)) {
          matches.add(sub);
        }
      }
      return matches;
    }

    if (args.length == 2 && "settarget".equalsIgnoreCase(args[0])) {
      String prefix = args[1].toLowerCase();
      List<String> suggestions = new ArrayList<>();
      for (Player player : plugin.getServer().getOnlinePlayers()) {
        String name = player.getName();
        if (name.toLowerCase().startsWith(prefix)) {
          suggestions.add(name);
        }
      }
      if (sender instanceof Player && suggestions.isEmpty()) {
        suggestions.add(((Player) sender).getName());
      }
      return suggestions;
    }

    if (args.length == 2 && "test".equalsIgnoreCase(args[0])) {
      String prefix = args[1].toLowerCase();
      List<String> suggestions = new ArrayList<>();
      for (String option : List.of(ORBITAL_STRIKE_SCENARIO)) {
        if (option.startsWith(prefix)) {
          suggestions.add(option);
        }
      }
      return suggestions;
    }

    return Collections.emptyList();
  }

  private boolean handleSelfTest(CommandSender sender, String[] args) {
    if (args.length == 1) {
      ExamplePlugin.SelfTestResult result = plugin.runIntegrationSelfTest();
      sender.sendMessage(ChatColor.YELLOW + "YouTube integration self-test results:");
      for (String line : result.messages()) {
        sender.sendMessage(line);
      }
      if (result.passed()) {
        sender.sendMessage(ChatColor.GREEN + "All checks passed successfully.");
      } else {
        sender.sendMessage(
            ChatColor.RED
                +
                "One or more checks failed. Review the messages above before going live.");
      }
      return true;
    }

    String scenario = args[1].toLowerCase();
    switch (scenario) {
      case ORBITAL_STRIKE_SCENARIO:
        return handleOrbitalStrikeTest(sender);
      default:
        sender.sendMessage(
            ChatColor.RED
                + "Unknown test scenario. Available options:"
                + ChatColor.YELLOW
                + " "
                + ORBITAL_STRIKE_SCENARIO);
        return true;
    }
  }

  private boolean handleOrbitalStrikeTest(CommandSender sender) {
    ExamplePlugin.OrbitalStrikeDemoResult result = plugin.runOrbitalStrikeDemo();
    if (!result.triggered()) {
      result.messages().forEach(sender::sendMessage);
      sender.sendMessage(ChatColor.RED + "Orbital strike test could not be started.");
      return true;
    }
    result.messages().forEach(sender::sendMessage);
    sender.sendMessage(ChatColor.GREEN + "Orbital strike test launched successfully.");
    return true;
  }
}
