package com.crimsonwarpedcraft.exampleplugin.bridge.event;

import com.crimsonwarpedcraft.exampleplugin.bridge.PlatformChatBridge;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Bukkit event fired when the TikTok bridge emits a subscriber notification. */
public class TikTokSubscriberEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final PlatformChatBridge.SubscriberNotification notification;

  /** Creates a new TikTok subscriber event. */
  public TikTokSubscriberEvent(@NotNull PlatformChatBridge.SubscriberNotification notification) {
    super(!org.bukkit.Bukkit.isPrimaryThread());
    this.notification = notification;
  }

  @NotNull
  public PlatformChatBridge.SubscriberNotification getNotification() {
    return notification;
  }

  @Override
  public @NotNull HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
