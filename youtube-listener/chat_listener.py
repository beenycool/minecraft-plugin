#!/usr/bin/env python3
"""Simple YouTube chat listener used by the ExamplePlugin.

The script prefers the third-party ``pytchat`` library when available, but will
fall back to emitting placeholder messages so the Java plugin can demonstrate
its integration without additional dependencies.
"""

from __future__ import annotations

import argparse
import http.server
import json
import os
import re
import socketserver
import sys
import threading
import time
from datetime import datetime
from queue import Empty, Full, Queue
from typing import Any, Dict, Iterable, List, Optional, Tuple
from urllib.parse import parse_qs, urlparse
from http.cookiejar import MozillaCookieJar

import httpx


MAX_HTTP_QUEUE = 10000  # prevent unbounded growth if clients stop polling
EVENT_QUEUE: "Queue[str]" = Queue(maxsize=MAX_HTTP_QUEUE)
HTTP_PUBLISH_ENABLED = False

OVERLAY_HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>YouTube Bridge Overlay</title>
  <style>
    :root {{
      color-scheme: light dark;
    }}
    body {{
      margin: 0;
      padding: 16px;
      background: rgba(0, 0, 0, 0);
      font-family: "Segoe UI", "Helvetica Neue", Arial, sans-serif;
    }}
    #events {{
      display: flex;
      flex-direction: column;
      gap: 8px;
    }}
    .line {{
      padding: 8px 12px;
      border-radius: 8px;
      font-weight: 600;
      font-size: 20px;
      color: #ffffff;
      background: rgba(20, 20, 20, 0.65);
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.35);
      text-shadow: 0 2px 4px rgba(0, 0, 0, 0.35);
      letter-spacing: 0.3px;
    }}
    .line.subscriber {{
      background: linear-gradient(135deg, rgba(255, 215, 0, 0.85), rgba(255, 140, 0, 0.85));
      color: #201000;
      text-shadow: none;
    }}
    .line.chat {{
      background: rgba(52, 152, 219, 0.75);
    }}
    .line.donation {{
      background: linear-gradient(135deg, rgba(231, 76, 60, 0.85), rgba(192, 57, 43, 0.85));
    }}
    .line.log {{
      background: rgba(149, 165, 166, 0.6);
      font-weight: 500;
    }}
  </style>
</head>
<body>
  <div id="events"></div>
  <script>
    const eventsPath = {events_path_json};
    const container = document.getElementById("events");
    const MAX_ITEMS = 8;

    function addLine(text, cssClass) {{
      const entry = document.createElement("div");
      entry.className = "line " + (cssClass || "");
      entry.textContent = text;
      container.prepend(entry);
      while (container.children.length > MAX_ITEMS) {{
        container.removeChild(container.lastChild);
      }}
    }}

    function handlePayload(payload) {{
      if (!payload || typeof payload !== "object") {{
        return;
      }}
      if (payload.type === "subscriber") {{
        const name = payload.author || "Someone";
        addLine(`${{name}} subscribed! Spawned 1 TNT.`, "subscriber");
        return;
      }}
      if (payload.type === "donation") {{
        const donor = payload.author || "Supporter";
        const rawAmount = typeof payload.amount === "number" ? payload.amount.toFixed(2) : null;
        const amountText = payload.formattedAmount || (rawAmount ? `${{rawAmount}}${{payload.currency ? ` ${payload.currency}` : ""}}` : "");
        const suffix = amountText ? ` donated ${{amountText}}` : " donated";
        addLine(`${{donor}}${{suffix}} - Orbital Strike Cannon online!`, "donation");
        return;
      }}
      if (payload.type === "chat") {{
        const author = payload.author || "YouTube";
        const message = payload.message || "";
        addLine(`${{author}}: ${{message}}`, "chat");
        return;
      }}
      if (payload.type === "log") {{
        addLine(payload.message || "Log event", "log");
      }}
    }}

    async function poll() {{
      while (true) {{
        try {{
          const response = await fetch(eventsPath, {{
            cache: "no-store",
            headers: {{
              "Pragma": "no-cache",
              "Cache-Control": "no-cache"
            }}
          }});
          if (response.status === 204) {{
            await new Promise((resolve) => setTimeout(resolve, 1200));
            continue;
          }}
          if (!response.ok) {{
            console.error("Overlay fetch failed", response.status);
            await new Promise((resolve) => setTimeout(resolve, 2000));
            continue;
          }}
          const raw = await response.text();
          raw.split("\\n").forEach((line) => {{
            if (!line.trim()) {{
              return;
            }}
            try {{
              const payload = JSON.parse(line);
              handlePayload(payload);
            }} catch (error) {{
              console.error("Failed to parse event line", line, error);
            }}
          }});
        }} catch (error) {{
          console.error("Overlay polling error", error);
          await new Promise((resolve) => setTimeout(resolve, 2500));
        }}
      }}
    }}

    poll();
  </script>
</body>
</html>
"""


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Relay YouTube chat messages")
    parser.add_argument("--stream", required=True, help="YouTube chat ID or URL")
    parser.add_argument(
        "--interval",
        type=int,
        default=5,
        help="Polling interval in seconds when pytchat is unavailable",
    )
    parser.add_argument(
        "--streamlabs-token",
        dest="streamlabs_token",
        help="(Discouraged) Streamlabs token; prefer STREAMLABS_SOCKET_TOKEN env var",
    )
    parser.add_argument(
        "--http-endpoint",
        dest="http_endpoint",
        help=(
            "Optional host:port or URL for an HTTP polling endpoint "
            "(e.g. 127.0.0.1:8081 or localhost:8081)"
        ),
    )
    parser.add_argument(
        "--http-path-prefix",
        dest="http_path_prefix",
        default="/",
        help="Optional path prefix to serve polling requests from (defaults to /)",
    )
    parser.add_argument(
        "--cookies",
        dest="cookies_path",
        help="Optional path to a Netscape-format cookies.txt file for authenticated access",
    )
    return parser.parse_args()


def _emit(message: str) -> None:
    sys.stdout.write(message + "\n")
    sys.stdout.flush()
    _queue_event(message)


def _emit_json(payload: Dict[str, Any]) -> None:
    message = json.dumps(payload, ensure_ascii=False)
    sys.stdout.write(message + "\n")
    sys.stdout.flush()
    _queue_event(message)


def _queue_event(message: str) -> None:
    if not HTTP_PUBLISH_ENABLED:
        return
    try:
        EVENT_QUEUE.put_nowait(message)
    except Full:
        # Drop the oldest entry to preserve recent events without blocking producers.
        try:
            EVENT_QUEUE.get_nowait()
        except Empty:
            pass
        try:
            EVENT_QUEUE.put_nowait(message)
        except Full:
            # If we still cannot enqueue, drop the event silently.
            pass


def _parse_http_endpoint(endpoint: str) -> Tuple[str, int]:
    if not endpoint:
        raise ValueError("HTTP endpoint cannot be empty")

    parsed = urlparse(endpoint if "://" in endpoint else f"http://{endpoint}")
    host = parsed.hostname or "127.0.0.1"
    port = parsed.port
    if port is None:
        raise ValueError("HTTP endpoint must include a port")
    return host, port


def _normalize_http_path(prefix: str) -> str:
    if not prefix:
        return "/"

    sanitized = prefix.strip()
    if not sanitized.startswith("/"):
        sanitized = f"/{sanitized}"

    if len(sanitized) > 1 and sanitized.endswith("/"):
        sanitized = sanitized.rstrip("/")

    return sanitized or "/"


def _start_http_server(
    endpoint: str, stop_event: threading.Event, path_prefix: str
) -> threading.Thread:
    global HTTP_PUBLISH_ENABLED

    host, port = _parse_http_endpoint(endpoint)
    normalized_prefix = _normalize_http_path(path_prefix)
    base_path = normalized_prefix.rstrip("/") if normalized_prefix != "/" else ""
    events_path = f"{base_path}/events" if base_path else "/events"
    overlay_path = f"{base_path}/overlay" if base_path else "/overlay"
    allowed_paths = {normalized_prefix, events_path, overlay_path}

    def _send_overlay_headers(handler: http.server.BaseHTTPRequestHandler, include_body: bool) -> None:
        html = OVERLAY_HTML_TEMPLATE.format(events_path_json=json.dumps(events_path))
        data = html.encode("utf-8")
        handler.send_response(200)
        handler.send_header("Content-Type", "text/html; charset=utf-8")
        handler.send_header("Cache-Control", "no-store")
        handler.send_header("X-Frame-Options", "SAMEORIGIN")
        handler.send_header("X-Content-Type-Options", "nosniff")
        handler.send_header("Content-Length", str(len(data)))
        handler.end_headers()
        if include_body:
            try:
                handler.wfile.write(data)
            except BrokenPipeError:  # pragma: no cover - depends on client
                pass

    class _PollingHandler(http.server.BaseHTTPRequestHandler):
        server_version = "YouTubeChatListener/1.0"

        def do_GET(self) -> None:  # pragma: no cover - network integration
            path = urlparse(self.path).path
            if path not in allowed_paths:
                self.send_error(404, "Not Found")
                return

            if path == overlay_path:
                _send_overlay_headers(self, True)
                return

            messages: List[str] = []
            while True:
                try:
                    messages.append(EVENT_QUEUE.get_nowait())
                except Empty:
                    break
                except Exception as exc:  # pragma: no cover - defensive guard
                    self.log_error("Error getting event from queue: %s", str(exc))
                    self.send_error(500, "Internal Server Error")
                    return

            if not messages:
                self.send_response(204)
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return

            body = "\n".join(messages) + "\n"
            data = body.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Accel-Buffering", "no")
            self.end_headers()
            try:
                self.wfile.write(data)
            except BrokenPipeError:  # pragma: no cover - depends on client
                pass

        def do_HEAD(self) -> None:  # pragma: no cover - network integration
            path = urlparse(self.path).path
            if path not in allowed_paths:
                self.send_error(404, "Not Found")
                return

            if path == overlay_path:
                _send_overlay_headers(self, False)
                return

            self.send_response(204)
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Accel-Buffering", "no")
            self.send_header("Content-Length", "0")
            self.end_headers()

        def log_message(self, format: str, *args: Any) -> None:  # pragma: no cover - silence logs
            return

    class _ThreadedServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
        daemon_threads = True
        allow_reuse_address = True

    server = _ThreadedServer((host, port), _PollingHandler)

    def _serve() -> None:  # pragma: no cover - integration with Minecraft
        with server:
            server.serve_forever(poll_interval=0.5)

    thread = threading.Thread(target=_serve, name="YouTubeChatListenerHTTP", daemon=True)
    thread.start()

    def _shutdown_on_stop() -> None:  # pragma: no cover - integration cleanup
        global HTTP_PUBLISH_ENABLED
        stop_event.wait()
        try:
            server.shutdown()
        except Exception as exc:  # pragma: no cover - depends on runtime state
            _emit_log("HTTP server shutdown failed", level="warning", error=str(exc))
        finally:
            server.server_close()
            HTTP_PUBLISH_ENABLED = False

    threading.Thread(target=_shutdown_on_stop, name="YouTubeChatListenerHTTP-Shutdown", daemon=True).start()

    HTTP_PUBLISH_ENABLED = True
    base_url = f"http://{host}:{port}{normalized_prefix}"
    _emit_log(
        "HTTP polling endpoint started",
        level="info",
        url=f"{base_url.rstrip('/') or base_url}",
        eventsUrl=f"http://{host}:{port}{events_path}",
        overlayUrl=f"http://{host}:{port}{overlay_path}",
    )
    return thread


def _timestamp() -> str:
    return datetime.utcnow().isoformat(timespec="seconds") + "Z"


def _normalize_stream_identifier(stream_identifier: str) -> str:
    """Return a YouTube video identifier when given a URL or identifier string."""

    if not stream_identifier:
        return stream_identifier

    parsed = urlparse(stream_identifier)
    if parsed.scheme and parsed.netloc:
        query_params = parse_qs(parsed.query)
        video_id = next((value for value in query_params.get("v", []) if value), None)
        if not video_id and parsed.path:
            segments = [segment for segment in parsed.path.split("/") if segment]
            if segments:
                candidate = segments[-1]
                match = re.fullmatch(r"[A-Za-z0-9_-]{11}", candidate)
                if match:
                    video_id = match.group(0)
        if video_id:
            return video_id

    return stream_identifier


def _emit_chat(
    author: str,
    message: str,
    channel_id: Optional[str] = None,
    message_id: Optional[int] = None,
) -> None:
    payload: Dict[str, Any] = {
        "type": "chat",
        "author": author,
        "message": message,
        "timestamp": _timestamp(),
    }
    if channel_id:
        payload["channelId"] = channel_id
    if message_id is not None:
        payload["messageId"] = message_id
    _emit_json(payload)


def _emit_log(message: str, level: str = "info", **extra: Any) -> None:
    payload: Dict[str, Any] = {
        "type": "log",
        "level": level,
        "message": message,
        "timestamp": _timestamp(),
    }
    if extra:
        payload.update(extra)
    _emit_json(payload)


def _emit_error(message: str, **extra: Any) -> None:
    payload: Dict[str, Any] = {
        "type": "error",
        "level": "error",
        "message": message,
        "timestamp": _timestamp(),
    }
    if extra:
        payload.update(extra)
    _emit_json(payload)


def _emit_subscriber(
    author: str,
    total_subscribers: Optional[int],
    channel_id: Optional[str],
    raw: Optional[Dict[str, Any]] = None,
) -> None:
    payload: Dict[str, Any] = {
        "type": "subscriber",
        "author": author,
        "timestamp": _timestamp(),
    }
    if total_subscribers is not None:
        payload["totalSubscribers"] = total_subscribers
    if channel_id:
        payload["channelId"] = channel_id
    if raw:
        payload["raw"] = raw
    _emit_json(payload)


def _emit_donation(
    author: str,
    amount: Optional[float],
    currency: Optional[str],
    message: Optional[str],
    formatted_amount: Optional[str],
    raw: Optional[Dict[str, Any]] = None,
) -> None:
    payload: Dict[str, Any] = {
        "type": "donation",
        "author": author,
        "timestamp": _timestamp(),
    }
    if amount is not None:
        payload["amount"] = amount
    if currency:
        payload["currency"] = currency
    if formatted_amount:
        payload["formattedAmount"] = formatted_amount
    if message:
        payload["message"] = message
    if raw:
        payload["raw"] = raw
    _emit_json(payload)

def _load_cookies(cookies_path: str) -> httpx.Cookies:
    jar = MozillaCookieJar()
    if not os.path.exists(cookies_path):
        raise FileNotFoundError(cookies_path)
    jar.load(cookies_path, ignore_discard=True, ignore_expires=True)
    cookies = httpx.Cookies()
    for cookie in jar:
        name = cookie.name
        value = cookie.value
        domain = cookie.domain or ""
        path = cookie.path or "/"
        cookies.set(name, value, domain=domain, path=path)
    return cookies


def _build_http_client(cookies_path: str) -> httpx.Client:
    cookies = _load_cookies(cookies_path)
    headers: Optional[Dict[str, str]] = None
    try:
        import pytchat  # type: ignore

        headers = getattr(pytchat.config, "headers", None)
    except Exception:
        headers = None
    return httpx.Client(http2=True, cookies=cookies, headers=headers)


def _run_with_pytchat(
    stream_identifier: str,
    stop_event: threading.Event,
    http_client: Optional[httpx.Client],
) -> None:
    import pytchat

    create_kwargs: Dict[str, Any] = {}
    if http_client is not None:
        create_kwargs["client"] = http_client

    chat = pytchat.create(video_id=stream_identifier, **create_kwargs)
    try:
        while not stop_event.is_set() and chat.is_alive():
            for item in chat.get().items:
                author = getattr(item.author, "name", "YouTube")
                channel_id = getattr(item.author, "channelId", None)
                message_id = _safe_int(getattr(item, "timestamp", None))
                _emit_chat(
                    author,
                    item.message,
                    channel_id=channel_id,
                    message_id=message_id,
                )
            time.sleep(1)
    finally:
        try:
            chat.terminate()
        except Exception:
            pass


def _safe_int(value: Any) -> Optional[int]:
    if value is None:
        return None
    try:
        if isinstance(value, str) and value.strip() == "":
            return None
        if isinstance(value, str) and value.strip().isdigit():
            return int(value.strip())
        return int(float(value))
    except (TypeError, ValueError):
        return None


def _safe_float(value: Any) -> Optional[float]:
    if value is None:
        return None
    try:
        if isinstance(value, str):
            stripped = value.strip()
            if stripped == "":
                return None
            return float(stripped)
        return float(value)
    except (TypeError, ValueError):
        return None


def _maybe_iterable(value: Any) -> Iterable[Any]:
    if isinstance(value, list):
        return value
    if value is None:
        return []
    return [value]


def _handle_streamlabs_event(payload: Dict[str, Any]) -> None:
    event_type = str(payload.get("type") or payload.get("event_type") or "").lower()
    platform_hint = str(payload.get("for") or "").lower()
    messages = _maybe_iterable(payload.get("message"))

    for entry in messages:
        if not isinstance(entry, dict):
            continue
        platform = str(entry.get("platform") or platform_hint).lower()
        if platform and "youtube" not in platform:
            continue

        if event_type in {"subscription", "resubscription", "mass_subscription"}:
            author = (
                entry.get("name")
                or entry.get("display_name")
                or entry.get("from")
                or "YouTube Subscriber"
            )
            total_subscribers = _safe_int(entry.get("count") or entry.get("amount"))
            channel_id = entry.get("channel_id")
            _emit_subscriber(str(author), total_subscribers, channel_id, raw=entry)
            continue

        if event_type in {"donation", "tip"}:
            author = (
                entry.get("name")
                or entry.get("display_name")
                or entry.get("from")
                or "YouTube Supporter"
            )
            amount = _safe_float(entry.get("amount"))
            currency = str(entry.get("currency") or "").upper() or None
            formatted_amount = entry.get("formatted_amount") or entry.get("amount_display")
            message_text = (
                entry.get("message")
                or entry.get("comment")
                or entry.get("body")
                or entry.get("note")
            )
            _emit_donation(str(author), amount, currency, message_text, formatted_amount, raw=entry)
            continue


def _run_streamlabs_listener(token: str, stop_event: threading.Event) -> None:
    try:
        import socketio  # type: ignore
    except ModuleNotFoundError:
        _emit_log(
            "python-socketio not installed; Streamlabs subscriber integration disabled",
            level="warning",
        )
        return

    sio = socketio.Client(logger=False, engineio_logger=False)

    @sio.event
    def connect() -> None:  # pragma: no cover - requires remote service
        _emit_log("Connected to Streamlabs Socket API", level="info")

    @sio.event
    def disconnect() -> None:  # pragma: no cover - requires remote service
        _emit_log("Disconnected from Streamlabs Socket API", level="info")

    @sio.on("event")
    def on_event(data: Any) -> None:  # pragma: no cover - requires remote service
        if isinstance(data, dict):
            try:
                _handle_streamlabs_event(data)
            except Exception as exc:  # pragma: no cover - defensive logging
                _emit_error("Streamlabs event handling failed", error=str(exc))

    url = f"https://sockets.streamlabs.com?token={token}"
    try:
        sio.connect(url, transports=["websocket"], wait=True)
    except Exception as exc:  # pragma: no cover - depends on environment
        _emit_error("Failed to connect to Streamlabs Socket API", error=str(exc))
        return

    try:
        while not stop_event.is_set():
            sio.sleep(0.5)
    except KeyboardInterrupt:  # pragma: no cover - handled by caller
        pass
    finally:
        try:
            sio.disconnect()
        except Exception as exc:  # pragma: no cover - cleanup best effort
            _emit_log("Streamlabs disconnect failed", level="warning", error=str(exc))


def main() -> int:
    args = _parse_args()
    stream_identifier = _normalize_stream_identifier(args.stream)
    stop_event = threading.Event()
    cookies_path: Optional[str] = args.cookies_path or os.environ.get("YOUTUBE_COOKIES_PATH")
    if cookies_path:
        cookies_path = os.path.expanduser(cookies_path)
        if not os.path.isabs(cookies_path):
            cookies_path = os.path.abspath(cookies_path)

    http_client: Optional[httpx.Client] = None
    if cookies_path:
        try:
            http_client = _build_http_client(cookies_path)
            if http_client is not None:
                _emit_log("Loaded YouTube cookies", path=cookies_path)
        except FileNotFoundError:
            _emit_error("Cookie file not found", path=cookies_path)
        except Exception as exc:  # pragma: no cover - defensive logging
            _emit_error("Failed to load cookies", path=cookies_path, error=str(exc))

    http_thread: Optional[threading.Thread] = None
    if args.http_endpoint:
        try:
            http_thread = _start_http_server(
                args.http_endpoint, stop_event, args.http_path_prefix
            )
        except ValueError as exc:
            _emit_error("Invalid HTTP endpoint", error=str(exc))
        except OSError as exc:
            _emit_error("Failed to start HTTP endpoint", error=str(exc))

    streamlabs_thread: Optional[threading.Thread] = None
    token = args.streamlabs_token or os.environ.get("STREAMLABS_SOCKET_TOKEN")
    if token:
        streamlabs_thread = threading.Thread(
            target=_run_streamlabs_listener, args=(token, stop_event), daemon=True
        )
        streamlabs_thread.start()

    exit_code = 0
    try:
        while not stop_event.is_set():
            try:
                _run_with_pytchat(stream_identifier, stop_event, http_client)
                break
            except ModuleNotFoundError as exc:
                _emit_error(
                    "pytchat is not installed; install requirements and restart the listener",
                    error=str(exc),
                )
                exit_code = 1
                break
            except Exception as exc:  # pragma: no cover - defensive logging
                _emit_error("Unhandled listener error", error=str(exc))
                if stop_event.wait(10):
                    break
    except KeyboardInterrupt:
        exit_code = 0
    finally:
        stop_event.set()
        if streamlabs_thread is not None:
            streamlabs_thread.join(timeout=5.0)
        if http_thread is not None:
            http_thread.join(timeout=5.0)
        if http_client is not None:
            try:
                http_client.close()
            except Exception:
                pass

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
