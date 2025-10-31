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

/** Command handler that controls the TikTok chat integration. */
public class TikTokIntegrationCommand implements CommandExecutor, TabCompleter {

  private static final List<String> SUBCOMMANDS = Arrays.asList("setchat", "settarget", "reload");
  private final ExamplePlugin plugin;

  /** Creates a new TikTok integration command handler. */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public TikTokIntegrationCommand(ExamplePlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission("example.ttstream.use")) {
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
        sender.sendMessage(ChatColor.GREEN + "Reloaded TikTok stream configuration.");
        return true;
      default:
        sendUsage(sender, label);
        return true;
    }
  }

  private boolean handleSetChat(CommandSender sender, String[] args) {
    if (args.length < 2) {
      sender.sendMessage(ChatColor.RED + "Usage: /ttstream setchat <identifier>");
      return true;
    }

    String identifier = args[1];
    updateConfigValueAndReload("tiktok.stream-identifier", identifier);
    sender.sendMessage(
        ChatColor.GREEN
            + "TikTok listener identifier set to "
            + ChatColor.YELLOW
            + identifier
            + ChatColor.GREEN
            + ".");
    return true;
  }

  private boolean handleSetTarget(CommandSender sender, String[] args) {
    if (args.length < 2) {
      sender.sendMessage(ChatColor.RED + "Usage: /ttstream settarget <player>");
      return true;
    }

    String ign = args[1];
    updateConfigValueAndReload("tiktok.target-player-ign", ign);
    sender.sendMessage(
        ChatColor.GREEN
            + "TikTok target player set to "
            + ChatColor.YELLOW
            + ign
            + ChatColor.GREEN
            + ".");
    return true;
  }

  private void updateConfigValueAndReload(String key, String value) {
    plugin.getConfig().set(key, value);
    if ("tiktok.target-player-ign".equals(key)) {
      plugin.getConfig().set("tiktok-bridge.target-player", value);
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
            + " <setchat|settarget|reload>");
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    if (!sender.hasPermission("example.ttstream.use")) {
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

    return Collections.emptyList();
  }
}
