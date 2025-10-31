package com.crimsonwarpedcraft.exampleplugin.bridge;

import com.crimsonwarpedcraft.exampleplugin.bridge.event.YouTubeChatMessageEvent;
import com.crimsonwarpedcraft.exampleplugin.bridge.event.YouTubeSubscriberEvent;
import com.crimsonwarpedcraft.exampleplugin.bridge.event.YouTubeSubscriberMilestoneEvent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/** Chat bridge that emits YouTube specific Bukkit events. */
public final class YouTubeChatBridge extends PlatformChatBridge {

  /** Creates a new YouTube chat bridge bound to the supplied plugin. */
  public YouTubeChatBridge(@NotNull Plugin plugin) {
    super(plugin);
  }

  @Override
  protected void dispatchChatEvent(@NotNull ChatMessage message) {
    Bukkit.getPluginManager().callEvent(new YouTubeChatMessageEvent(message));
  }

  @Override
  protected void dispatchSubscriberEvent(@NotNull SubscriberNotification notification) {
    Bukkit.getPluginManager().callEvent(new YouTubeSubscriberEvent(notification));
  }

  @Override
  protected void dispatchMilestoneEvent(@NotNull SubscriberMilestone milestone) {
    Bukkit.getPluginManager().callEvent(new YouTubeSubscriberMilestoneEvent(milestone));
  }
}
