package com.crimsonwarpedcraft.exampleplugin.bridge.event;

import com.crimsonwarpedcraft.exampleplugin.bridge.PlatformChatBridge;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Bukkit event fired when the TikTok bridge emits a subscriber milestone notification. */
public class TikTokSubscriberMilestoneEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final PlatformChatBridge.SubscriberMilestone milestone;

  /** Creates a new TikTok subscriber milestone event. */
  public TikTokSubscriberMilestoneEvent(@NotNull PlatformChatBridge.SubscriberMilestone milestone) {
    super(!org.bukkit.Bukkit.isPrimaryThread());
    this.milestone = milestone;
  }

  @NotNull
  public PlatformChatBridge.SubscriberMilestone getMilestone() {
    return milestone;
  }

  @Override
  public @NotNull HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
