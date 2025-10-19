package com.crimsonwarpedcraft.exampleplugin.bridge.event;

import com.crimsonwarpedcraft.exampleplugin.bridge.YouTubeChatBridge;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Bukkit event fired when the YouTube bridge delivers a chat message.
 */
public class YouTubeChatMessageEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final YouTubeChatBridge.ChatMessage message;

  /** Creates a new chat message event with the supplied payload. */
  public YouTubeChatMessageEvent(@NotNull YouTubeChatBridge.ChatMessage message) {
    super(!org.bukkit.Bukkit.isPrimaryThread());
    this.message = message;
  }

  @NotNull
  public YouTubeChatBridge.ChatMessage getMessage() {
    return message;
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
