package com.crimsonwarpedcraft.exampleplugin.bridge;

import com.crimsonwarpedcraft.exampleplugin.bridge.event.YouTubeChatMessageEvent;
import com.crimsonwarpedcraft.exampleplugin.bridge.event.YouTubeSubscriberEvent;
import com.crimsonwarpedcraft.exampleplugin.bridge.event.YouTubeSubscriberMilestoneEvent;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Provides a simple bridge between the external Python listener and the Bukkit API.
 *
 * <p>The bridge accepts simple POJO payloads and guarantees they will be dispatched on the
 * Bukkit primary thread before invoking any consumer callbacks or raising Bukkit events.
 */
public final class YouTubeChatBridge {

  private final Plugin plugin;
  private final List<Consumer<ChatMessage>> chatConsumers = new CopyOnWriteArrayList<>();
  private final List<Consumer<SubscriberNotification>> subscriberConsumers =
      new CopyOnWriteArrayList<>();
  private final List<Consumer<SubscriberMilestone>> milestoneConsumers =
      new CopyOnWriteArrayList<>();

  private final AtomicLong milestoneInterval = new AtomicLong(100L);

  /** Creates a new YouTube chat bridge bound to the supplied plugin. */
  public YouTubeChatBridge(@NotNull Plugin plugin) {
    this.plugin = Objects.requireNonNull(plugin, "plugin");
  }

  /** Registers a consumer that is invoked whenever the bridge receives a chat message. */
  public Registration registerChatListener(@NotNull Consumer<ChatMessage> consumer) {
    chatConsumers.add(Objects.requireNonNull(consumer, "consumer"));
    return () -> chatConsumers.remove(consumer);
  }

  /** Registers a consumer that receives subscriber notifications. */
  public Registration registerSubscriberListener(
      @NotNull Consumer<SubscriberNotification> consumer) {
    subscriberConsumers.add(Objects.requireNonNull(consumer, "consumer"));
    return () -> subscriberConsumers.remove(consumer);
  }

  /** Registers a consumer for milestone notifications. */
  public Registration registerMilestoneListener(@NotNull Consumer<SubscriberMilestone> consumer) {
    milestoneConsumers.add(Objects.requireNonNull(consumer, "consumer"));
    return () -> milestoneConsumers.remove(consumer);
  }

  /** Updates the milestone interval used when generating milestone callbacks. */
  public void setSubscriberMilestoneInterval(long interval) {
    if (interval <= 0) {
      milestoneInterval.set(0L);
    } else {
      milestoneInterval.set(interval);
    }
  }

  /** Returns the active milestone interval. */
  public long getSubscriberMilestoneInterval() {
    return milestoneInterval.get();
  }

  /**
   * Accepts a message payload from the Python listener and dispatches it to consumers.
   *
   * @param message YouTube chat payload
   */
  public void emitChatMessage(@NotNull ChatMessage message) {
    Objects.requireNonNull(message, "message");
    runOnMainThread(
        () -> {
          chatConsumers.forEach(listener -> listener.accept(message));
          Bukkit.getPluginManager().callEvent(new YouTubeChatMessageEvent(message));
        });
  }

  /**
   * Accepts a subscriber notification payload.
   *
   * <p>This method will also automatically trigger a milestone callback when the supplied total
   * subscriber count satisfies the configured milestone interval.
   */
  public void emitSubscriberNotification(@NotNull SubscriberNotification notification) {
    Objects.requireNonNull(notification, "notification");
    runOnMainThread(
        () -> {
          subscriberConsumers.forEach(listener -> listener.accept(notification));
          Bukkit.getPluginManager().callEvent(new YouTubeSubscriberEvent(notification));
        });
    maybeEmitMilestone(notification.totalSubscribers(), notification.channelId());
  }

  /** Explicitly emits a milestone notification. */
  public void emitMilestone(@NotNull SubscriberMilestone milestone) {
    Objects.requireNonNull(milestone, "milestone");
    runOnMainThread(
        () -> {
          milestoneConsumers.forEach(listener -> listener.accept(milestone));
          Bukkit.getPluginManager().callEvent(new YouTubeSubscriberMilestoneEvent(milestone));
        });
  }

  private void maybeEmitMilestone(long totalSubscribers, @Nullable String channelId) {
    long interval = milestoneInterval.get();
    if (interval <= 0) {
      return;
    }
    if (totalSubscribers > 0 && totalSubscribers % interval == 0) {
      emitMilestone(new SubscriberMilestone(totalSubscribers, interval, channelId, Instant.now()));
    }
  }

  private void runOnMainThread(Runnable runnable) {
    if (Bukkit.isPrimaryThread()) {
      runnable.run();
    } else {
      Bukkit.getScheduler().runTask(plugin, runnable);
    }
  }

  /** Simple token returned when registering listeners. */
  @FunctionalInterface
  public interface Registration extends AutoCloseable {
    @Override
    void close();
  }

  /** Represents a chat message payload from YouTube. */
  public record ChatMessage(
      @NotNull String authorDisplayName,
      @NotNull String message,
      long messageId,
      @NotNull Instant timestamp,
      @Nullable String channelId) {

    /** Canonical constructor ensuring required fields are present. */
    public ChatMessage {
      Objects.requireNonNull(authorDisplayName, "authorDisplayName");
      Objects.requireNonNull(message, "message");
      Objects.requireNonNull(timestamp, "timestamp");
    }
  }

  /** Represents a subscriber notification from YouTube. */
  public record SubscriberNotification(
      @NotNull String authorDisplayName,
      @Nullable String inGameName,
      long totalSubscribers,
      @Nullable String channelId,
      @NotNull Instant timestamp) {

    /** Canonical constructor ensuring required fields are present. */
    public SubscriberNotification {
      Objects.requireNonNull(authorDisplayName, "authorDisplayName");
      Objects.requireNonNull(timestamp, "timestamp");
    }
  }

  /** Represents a subscriber milestone notification. */
  public record SubscriberMilestone(
      long totalSubscribers,
      long milestoneInterval,
      @Nullable String channelId,
      @NotNull Instant timestamp) {

    /** Canonical constructor ensuring required fields are present. */
    public SubscriberMilestone {
      Objects.requireNonNull(timestamp, "timestamp");
    }
  }
}
