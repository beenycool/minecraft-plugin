package com.crimsonwarpedcraft.exampleplugin.bridge.event;

import com.crimsonwarpedcraft.exampleplugin.bridge.PlatformChatBridge;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Bukkit event fired when the TikTok bridge delivers a chat message. */
public class TikTokChatMessageEvent extends Event {

  private static final HandlerList HANDLERS = new HandlerList();

  private final PlatformChatBridge.ChatMessage message;

  /** Creates a new TikTok chat message event. */
  public TikTokChatMessageEvent(@NotNull PlatformChatBridge.ChatMessage message) {
    super(!org.bukkit.Bukkit.isPrimaryThread());
    this.message = message;
  }

  @NotNull
  public PlatformChatBridge.ChatMessage getMessage() {
    return message;
  }

  @Override
  public @NotNull HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
