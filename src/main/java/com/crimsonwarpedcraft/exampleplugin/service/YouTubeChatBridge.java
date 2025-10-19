package com.crimsonwarpedcraft.exampleplugin.service;

import com.crimsonwarpedcraft.exampleplugin.ExamplePlugin;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Coordinates the lifecycle of the external Python chat listener process.
 */
public class YouTubeChatBridge {

  private final ExamplePlugin plugin;
  private Process process;
  private ExecutorService outputReader;

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
   */
  @SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification = "Arguments passed directly without shell")
  public synchronized void start(
      String pythonExecutable,
      File listenerScript,
      String streamIdentifier,
      int pollingIntervalSeconds,
      String targetIgn) {
    stop();

    if (streamIdentifier == null || streamIdentifier.isEmpty()) {
      plugin.getLogger().info("YouTube chat bridge not started: stream identifier not configured.");
      return;
    }

    if (!listenerScript.exists()) {
      plugin
          .getLogger()
          .log(
              Level.WARNING,
              "YouTube listener script not found at {0}. Unable to start chat bridge.",
              listenerScript.getAbsolutePath());
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
    return process != null && process.isAlive();
  }
}
