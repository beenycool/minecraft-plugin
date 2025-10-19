package com.crimsonwarpedcraft.exampleplugin.bridge.event;

import com.crimsonwarpedcraft.exampleplugin.bridge.YouTubeChatBridge;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired when the bridge reports a subscriber milestone.
 */
public class YouTubeSubscriberMilestoneEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final YouTubeChatBridge.SubscriberMilestone milestone;

  /** Creates a new milestone event with the supplied payload. */
  public YouTubeSubscriberMilestoneEvent(
      @NotNull YouTubeChatBridge.SubscriberMilestone milestone) {
    super(!org.bukkit.Bukkit.isPrimaryThread());
    this.milestone = milestone;
  }

  @NotNull
  public YouTubeChatBridge.SubscriberMilestone getMilestone() {
    return milestone;
  }

  @Override
  public @NotNull HandlerList getHandlers() {
    return HANDLERS;
  }

  /** Bukkit boilerplate for event handlers. */
  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
