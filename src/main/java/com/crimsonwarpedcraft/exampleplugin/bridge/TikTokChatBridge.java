package com.crimsonwarpedcraft.exampleplugin.bridge;

import com.crimsonwarpedcraft.exampleplugin.bridge.event.TikTokChatMessageEvent;
import com.crimsonwarpedcraft.exampleplugin.bridge.event.TikTokSubscriberEvent;
import com.crimsonwarpedcraft.exampleplugin.bridge.event.TikTokSubscriberMilestoneEvent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/** Chat bridge that emits TikTok specific Bukkit events. */
public final class TikTokChatBridge extends PlatformChatBridge {

  /** Creates a new TikTok chat bridge bound to the supplied plugin. */
  public TikTokChatBridge(@NotNull Plugin plugin) {
    super(plugin);
  }

  @Override
  protected void dispatchChatEvent(@NotNull ChatMessage message) {
    Bukkit.getPluginManager().callEvent(new TikTokChatMessageEvent(message));
  }

  @Override
  protected void dispatchSubscriberEvent(@NotNull SubscriberNotification notification) {
    Bukkit.getPluginManager().callEvent(new TikTokSubscriberEvent(notification));
  }

  @Override
  protected void dispatchMilestoneEvent(@NotNull SubscriberMilestone milestone) {
    Bukkit.getPluginManager().callEvent(new TikTokSubscriberMilestoneEvent(milestone));
  }
}
