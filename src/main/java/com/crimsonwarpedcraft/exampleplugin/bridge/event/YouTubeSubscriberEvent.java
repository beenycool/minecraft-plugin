package com.crimsonwarpedcraft.exampleplugin.bridge.event;

import com.crimsonwarpedcraft.exampleplugin.bridge.YouTubeChatBridge;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired when the bridge reports a new subscriber.
 */
public class YouTubeSubscriberEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final YouTubeChatBridge.SubscriberNotification notification;

  /** Creates a new subscriber event with the supplied notification. */
  public YouTubeSubscriberEvent(@NotNull YouTubeChatBridge.SubscriberNotification notification) {
    super(!org.bukkit.Bukkit.isPrimaryThread());
    this.notification = notification;
  }

  @NotNull
  public YouTubeChatBridge.SubscriberNotification getNotification() {
    return notification;
  }

  @Nullable
  public String getInGameName() {
    return notification.inGameName();
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
