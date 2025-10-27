package com.crimsonwarpedcraft.exampleplugin.service;

import com.crimsonwarpedcraft.exampleplugin.ExamplePlugin;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.scheduler.BukkitTask;

/**
 * Coordinates the lifecycle of the external Python chat listener process.
 */
public class YouTubeChatBridge {

  private final ExamplePlugin plugin;
  private Process process;
  private ExecutorService outputReader;
  private volatile HttpClient httpClient;
  private BukkitTask pollingTask;
  private final AtomicBoolean pollInFlight = new AtomicBoolean(false);
  private int consecutivePollFailures;

  /**
   * Creates a new chat bridge instance.
   *
   * @param plugin the owning plugin
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public YouTubeChatBridge(ExamplePlugin plugin) {
    this.plugin = plugin;
  }

  /**
   * Starts the external process with the provided configuration.
   *
   * @param pythonExecutable path to the Python interpreter
   * @param listenerScript script to execute
   * @param streamIdentifier YouTube stream identifier
   * @param pollingIntervalSeconds polling interval for placeholder mode
   * @param targetIgn Minecraft IGN that should receive messages
   * @param streamlabsToken Streamlabs Socket API token used to receive subscriber events
   */
  @SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification = "Arguments passed directly without shell")
  public synchronized void start(
      String pythonExecutable,
      File listenerScript,
      String streamIdentifier,
      int pollingIntervalSeconds,
      String targetIgn,
      String streamlabsToken,
      String listenerUrl) {
    stop();

    boolean useExternalListener = listenerUrl != null && !listenerUrl.isBlank();

    if (useExternalListener) {
      startHttpPolling(listenerUrl, pollingIntervalSeconds, targetIgn);
      return;
    }

    if (streamIdentifier == null || streamIdentifier.isEmpty()) {
      plugin.getLogger().info("YouTube chat bridge not started: stream identifier not configured.");
      return;
    }

    if (listenerScript == null || !listenerScript.exists()) {
      plugin
          .getLogger()
          .log(
              Level.WARNING,
              "YouTube listener script not found at {0}. Unable to start chat bridge.",
              listenerScript == null ? "<unspecified>" : listenerScript.getAbsolutePath());
      return;
    }

    List<String> command = new ArrayList<>();
    command.add(pythonExecutable);
    command.add(listenerScript.getAbsolutePath());
    command.add("--stream");
    command.add(streamIdentifier);
    command.add("--interval");
    command.add(Integer.toString(pollingIntervalSeconds));
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);
    processBuilder.directory(listenerScript.getParentFile());
    if (streamlabsToken != null && !streamlabsToken.isBlank()) {
      processBuilder.environment().put("STREAMLABS_SOCKET_TOKEN", streamlabsToken);
    }

    try {
      process = processBuilder.start();
      plugin.getLogger().info("Started YouTube chat listener process.");
      startOutputReader(process, targetIgn);
    } catch (IOException e) {
      plugin
          .getLogger()
          .log(Level.SEVERE, "Failed to start YouTube chat listener process", e);
      stop();
    }
  }

  private void startOutputReader(Process process, String targetIgn) {
    outputReader =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "YouTubeChatBridge-Output"));
    outputReader.submit(
        () -> {
          try (BufferedReader reader =
              new BufferedReader(
                  new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
              final String message = line;
              plugin.getServer()
                  .getScheduler()
                  .runTask(
                      plugin,
                      () -> plugin.handleIncomingYouTubeMessage(message, targetIgn));
            }
          } catch (IOException e) {
            plugin
                .getLogger()
                .log(Level.WARNING, "Error while reading YouTube chat bridge output", e);
          }
        });
  }

  /**
   * Stops the external listener process if it is currently running.
   */
  public synchronized void stop() {
    if (outputReader != null) {
      outputReader.shutdownNow();
      outputReader = null;
    }

    if (pollingTask != null) {
      pollingTask.cancel();
      pollingTask = null;
    }

    httpClient = null;
    pollInFlight.set(false);
    consecutivePollFailures = 0;

    if (process != null) {
      process.destroy();
      try {
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          plugin.getLogger().warning("YouTube chat listener did not exit; forcing termination.");
          process.destroyForcibly();
          process.waitFor(2, TimeUnit.SECONDS);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      process = null;
      plugin.getLogger().info("Stopped YouTube chat listener process.");
    }
  }

  /** Returns {@code true} if the listener process is alive. */
  public synchronized boolean isRunning() {
    if (process != null) {
      return process.isAlive();
    }
    return pollingTask != null;
  }

  private void startHttpPolling(String listenerUrl, int pollingIntervalSeconds, String targetIgn) {
    URI endpoint;
    try {
      endpoint = URI.create(listenerUrl);
    } catch (IllegalArgumentException ex) {
      plugin
          .getLogger()
          .log(Level.WARNING, "Invalid listener URL provided; unable to start polling.", ex);
      return;
    }

    String scheme = endpoint.getScheme();
    if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
      plugin.getLogger().warning("Listener URL must use http or https scheme.");
      return;
    }

    httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    consecutivePollFailures = 0;
    pollInFlight.set(false);

    Runnable poller = () -> pollEndpoint(endpoint, targetIgn);

    long intervalTicks = Math.max(20L, pollingIntervalSeconds * 20L);
    double intervalSeconds = intervalTicks / 20.0d;
    String intervalLabel =
        intervalTicks % 20L == 0
            ? (intervalTicks / 20L) + "s"
            : String.format(Locale.ROOT, "%.1fs", intervalSeconds);
    try {
      pollingTask =
          plugin
              .getServer()
              .getScheduler()
              .runTaskTimerAsynchronously(plugin, poller, 0L, intervalTicks);
      plugin
          .getLogger()
          .info(
              "Polling external YouTube listener at "
                  + listenerUrl
                  + " every "
                  + intervalLabel
                  + ".");
    } catch (IllegalStateException schedulerShutdown) {
      plugin
          .getLogger()
          .log(Level.WARNING, "Failed to schedule listener polling task", schedulerShutdown);
      poller.run();
    }
  }

  private void pollEndpoint(URI endpoint, String targetIgn) {
    HttpClient client = httpClient;
    if (client == null) {
      return;
    }

    if (!pollInFlight.compareAndSet(false, true)) {
      return;
    }

    try {
      HttpRequest request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(10))
              .GET()
              .header("Cache-Control", "no-cache")
              .build();
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

      int status = response.statusCode();
      if (status == 204) {
        consecutivePollFailures = 0;
        return;
      }

      if (status >= 200 && status < 300) {
        consecutivePollFailures = 0;
        String body = response.body();
        if (body == null || body.isBlank()) {
          return;
        }

        for (String line : body.split("\\R")) {
          if (line == null || line.isBlank()) {
            continue;
          }
          final String message = line;
          try {
            plugin
                .getServer()
                .getScheduler()
                .runTask(
                    plugin,
                    () -> plugin.handleIncomingYouTubeMessage(message, targetIgn));
          } catch (IllegalStateException schedulerShutdown) {
            plugin
                .getLogger()
                .log(
                    Level.FINE,
                    "Server scheduler unavailable while delivering message",
                    schedulerShutdown);
          }
        }
        return;
      }

      consecutivePollFailures++;
      if (consecutivePollFailures <= 3 || consecutivePollFailures % 10 == 0) {
        plugin
            .getLogger()
            .warning(
                "Listener polling returned status "
                    + status
                    + " from "
                    + endpoint);
      }
    } catch (IOException ex) {
      consecutivePollFailures++;
      if (consecutivePollFailures <= 3 || consecutivePollFailures % 10 == 0) {
        plugin
            .getLogger()
            .log(Level.WARNING, "I/O error polling listener endpoint", ex);
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } finally {
      pollInFlight.set(false);
    }
  }
}
