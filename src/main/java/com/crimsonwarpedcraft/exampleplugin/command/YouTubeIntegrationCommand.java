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

  private static final List<String> SUBCOMMANDS = Arrays.asList("setchat", "settarget", "reload");
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
    plugin.getConfig().set("youtube.stream-identifier", identifier);
    plugin.saveConfig();
    plugin.loadSettingsFromConfig();
    plugin.restartMonitoring();
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
    plugin.getConfig().set("youtube.target-player-ign", ign);
    plugin.saveConfig();
    plugin.loadSettingsFromConfig();
    plugin.restartMonitoring();
    sender.sendMessage(
        ChatColor.GREEN
            + "Target player set to "
            + ChatColor.YELLOW
            + ign
            + ChatColor.GREEN
            + ".");
    return true;
  }

  private void sendUsage(CommandSender sender, String label) {
    sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <setchat|settarget|reload>");
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

    if (args.length == 2 && "settarget".equalsIgnoreCase(args[0]) && sender instanceof Player) {
      Player player = (Player) sender;
      return Collections.singletonList(player.getName());
    }

    return Collections.emptyList();
  }
}
