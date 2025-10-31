#!/usr/bin/env python3
"""Simple multi-platform chat listener used by the ExamplePlugin.

The script prefers the third-party ``pytchat`` library for YouTube streams and
``TikTokLive`` for TikTok broadcasts when available, but will fall back to
emitting placeholder messages so the Java plugin can demonstrate its
integration without additional dependencies.
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


MAX_HTTP_QUEUE = 10000  # prevent unbounded growth if clients stop polling
EVENT_QUEUE: "Queue[str]" = Queue(maxsize=MAX_HTTP_QUEUE)
HTTP_PUBLISH_ENABLED = False


class YouTubeStreamUnavailableError(RuntimeError):
    """Raised when a YouTube stream cannot be reached or initialized."""


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Relay livestream chat messages")
    parser.add_argument(
        "--platform",
        choices={"youtube", "tiktok"},
        default="youtube",
        help="Streaming platform to listen to (default: youtube)",
    )
    parser.add_argument(
        "--stream",
        help="YouTube chat ID or URL (required when --platform=youtube)",
    )
    parser.add_argument(
        "--interval",
        type=int,
        default=5,
        help="Polling interval in seconds when pytchat is unavailable",
    )
    parser.add_argument(
        "--streamlabs-token",
        dest="streamlabs_token",
        help=(
            "(Discouraged) Streamlabs token; prefer STREAMLABS_SOCKET_TOKEN env var. "
            "Only used when --platform=youtube"
        ),
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
        "--tiktok-username",
        dest="tiktok_username",
        help="TikTok username/unique ID to connect to when --platform=tiktok",
    )
    parser.add_argument(
        "--tiktok-room-id",
        dest="tiktok_room_id",
        help="TikTok room ID to join when --platform=tiktok",
    )
    parser.add_argument(
        "--tiktok-session-id",
        dest="tiktok_session_id",
        help="Optional TikTok sessionid cookie for authenticated access",
    )
    parser.add_argument(
        "--tiktok-ms-token",
        dest="tiktok_ms_token",
        help="Optional TikTok msToken cookie for authenticated access",
    )

    args = parser.parse_args()

    if args.platform == "youtube":
        if not args.stream:
            parser.error("--stream is required when --platform=youtube")
    elif not (args.tiktok_username or args.tiktok_room_id):
        parser.error(
            "--tiktok-username or --tiktok-room-id is required when --platform=tiktok"
        )

    return args


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
    allowed_paths = {normalized_prefix, f"{normalized_prefix}/events"}
    if normalized_prefix == "/":
        allowed_paths.add("/events")

    class _PollingHandler(http.server.BaseHTTPRequestHandler):
        server_version = "ChatRelay/1.0"

        def do_GET(self) -> None:  # pragma: no cover - network integration
            path = urlparse(self.path).path
            if path not in allowed_paths:
                self.send_error(404, "Not Found")
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

    thread = threading.Thread(target=_serve, name="ChatRelayHTTP", daemon=True)
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

    threading.Thread(
        target=_shutdown_on_stop, name="ChatRelayHTTP-Shutdown", daemon=True
    ).start()

    HTTP_PUBLISH_ENABLED = True
    base_url = f"http://{host}:{port}{normalized_prefix}"
    events_path = f"{normalized_prefix.rstrip('/')}/events" if normalized_prefix != "/" else "/events"
    _emit_log(
        "HTTP polling endpoint started",
        level="info",
        url=f"{base_url.rstrip('/') or base_url}",
        eventsUrl=f"http://{host}:{port}{events_path}",
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
    *,
    platform: Optional[str] = None,
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
    if platform:
        payload["platform"] = platform
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
    *,
    platform: Optional[str] = None,
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
    if platform:
        payload["platform"] = platform
    _emit_json(payload)


def _emit_donation(
    author: str,
    amount: Optional[float],
    currency: Optional[str],
    message: Optional[str],
    formatted_amount: Optional[str] = None,
    raw: Optional[Dict[str, Any]] = None,
    *,
    platform: Optional[str] = None,
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
    if platform:
        payload["platform"] = platform
    _emit_json(payload)


def _run_with_pytchat(stream_identifier: str, stop_event: threading.Event) -> None:
    import pytchat

    try:
        chat = pytchat.create(video_id=stream_identifier)
    except Exception as exc:
        raise YouTubeStreamUnavailableError(str(exc)) from exc

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
                    platform="youtube",
                )
            time.sleep(1)
    except KeyboardInterrupt:
        raise
    except Exception as exc:
        raise YouTubeStreamUnavailableError(str(exc)) from exc


def _run_placeholder(
    stream_identifier: str,
    interval: int,
    stop_event: threading.Event,
    *,
    platform: str,
    author: str,
) -> None:
    _emit_chat(author, f"Simulated relay for {stream_identifier}", platform=platform)
    while not stop_event.is_set():
        time.sleep(interval)
        _emit_log(
            "Heartbeat",
            stream="placeholder",
            streamIdentifier=stream_identifier,
            platform=platform,
        )


def _run_tiktok_listener(
    username: Optional[str],
    room_id: Optional[str],
    session_id: Optional[str],
    ms_token: Optional[str],
    interval: int,
    stop_event: threading.Event,
) -> int:
    try:
        from TikTokLive import TikTokLiveClient  # type: ignore
        from TikTokLive.types.events import (  # type: ignore
            CommentEvent,
            FollowEvent,
            GiftEvent,
            LiveEndEvent,
            SubscribeEvent,
        )
    except ModuleNotFoundError as exc:
        _emit_log(
            "TikTokLive is not installed; using placeholder output",
            level="warning",
            platform="tiktok",
            error=str(exc),
        )
        identifier = username or room_id or "tiktok"
        _run_placeholder(
            identifier,
            interval,
            stop_event,
            platform="tiktok",
            author="TikTok",
        )
        return 0
    except Exception as exc:  # pragma: no cover - defensive guard
        _emit_error("Failed to initialise TikTok listener", error=str(exc), platform="tiktok")
        return 1

    client_kwargs: Dict[str, Any] = {}
    if room_id:
        client_kwargs["room_id"] = room_id
    if session_id:
        client_kwargs["session_id"] = session_id
    if ms_token:
        client_kwargs["ms_token"] = ms_token
    if username:
        client_kwargs["unique_id"] = username

    try:
        client = TikTokLiveClient(**client_kwargs)
    except Exception as exc:  # pragma: no cover - defensive guard
        _emit_error("Failed to create TikTok client", error=str(exc), platform="tiktok")
        return 1

    def _user_display(user: Any) -> Tuple[str, Optional[str]]:
        if user is None:
            return "TikTok", None
        nickname = getattr(user, "nickname", None)
        unique_id = getattr(user, "uniqueId", None)
        display = str(nickname or unique_id or "TikTok")
        identifier = getattr(user, "userId", None) or getattr(user, "id", None)
        if identifier is not None:
            identifier = str(identifier)
        return display, identifier

    @client.on("comment")  # type: ignore[misc]
    async def _on_comment(event: CommentEvent) -> None:
        user = getattr(event, "user", None) or getattr(event, "author", None)
        author, channel_id = _user_display(user)
        message = getattr(event, "comment", None) or getattr(event, "message", None) or ""
        _emit_chat(author, str(message), channel_id=channel_id, platform="tiktok")

    @client.on("follow")  # type: ignore[misc]
    async def _on_follow(event: FollowEvent) -> None:
        user = getattr(event, "user", None) or getattr(event, "follower", None)
        author, channel_id = _user_display(user)
        follow_info = getattr(event, "followInfo", None)
        total_followers = _safe_int(getattr(follow_info, "followerCount", None))
        _emit_subscriber(
            author,
            total_followers,
            channel_id,
            raw={"event": "follow", "data": _serialize_event(event)},
            platform="tiktok",
        )

    @client.on("subscribe")  # type: ignore[misc]
    async def _on_subscribe(event: SubscribeEvent) -> None:
        user = getattr(event, "user", None) or getattr(event, "subscriber", None)
        author, channel_id = _user_display(user)
        total_subscribers = _safe_int(
            getattr(event, "subLevel", None)
            or getattr(event, "subscriberCount", None)
            or getattr(getattr(event, "subscribeInfo", None), "subscriberCount", None)
        )
        _emit_subscriber(
            author,
            total_subscribers,
            channel_id,
            raw={"event": "subscribe", "data": _serialize_event(event)},
            platform="tiktok",
        )

    @client.on("gift")  # type: ignore[misc]
    async def _on_gift(event: GiftEvent) -> None:
        user = getattr(event, "user", None)
        author, channel_id = _user_display(user)
        gift = getattr(event, "gift", None)
        gift_name = getattr(gift, "name", None) or getattr(gift, "id", None)
        repeat_count = _safe_int(
            getattr(event, "repeat_count", None) or getattr(event, "repeatCount", None)
        )
        diamond_value = _safe_int(
            getattr(gift, "diamond_count", None) or getattr(gift, "diamondCount", None)
        )
        total_diamonds: Optional[float] = None
        if diamond_value is not None:
            multiplier = repeat_count if repeat_count and repeat_count > 0 else 1
            total_diamonds = float(diamond_value * multiplier)
        formatted_amount = None
        if total_diamonds is not None:
            formatted_amount = f"{int(total_diamonds)} diamonds"
        _emit_donation(
            author,
            total_diamonds,
            "diamonds" if total_diamonds is not None else None,
            str(gift_name) if gift_name else None,
            formatted_amount,
            raw={"event": "gift", "data": _serialize_event(event)},
            platform="tiktok",
        )

    @client.on("live_end")  # type: ignore[misc]
    async def _on_live_end(event: LiveEndEvent) -> None:
        _emit_log("TikTok live ended", level="info", platform="tiktok")
        stop_event.set()
        try:
            client.stop()
        except Exception:
            pass

    @client.on("connect")  # type: ignore[misc]
    async def _on_connect(_: Any) -> None:
        _emit_log("Connected to TikTok live chat", level="info", platform="tiktok")

    def _stop_on_event() -> None:
        stop_event.wait()
        try:
            client.stop()
        except Exception:
            pass

    threading.Thread(target=_stop_on_event, name="TikTokLive-Stopper", daemon=True).start()

    try:
        client.run()
    except KeyboardInterrupt:
        raise
    except Exception as exc:  # pragma: no cover - defensive guard
        _emit_error("TikTok listener encountered an error", error=str(exc), platform="tiktok")
        return 1

    return 0


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
        if isinstance(value, str) and value.strip() == "":
            return None
        return float(value)
    except (TypeError, ValueError):
        return None


def _maybe_iterable(value: Any) -> Iterable[Any]:
    if isinstance(value, list):
        return value
    if value is None:
        return []
    return [value]


def _serialize_event(value: Any) -> Any:
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    if isinstance(value, dict):
        return {k: _serialize_event(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [_serialize_event(v) for v in value]
    if hasattr(value, "to_dict") and callable(getattr(value, "to_dict")):
        try:
            return _serialize_event(value.to_dict())
        except Exception:
            pass
    if hasattr(value, "dict") and callable(getattr(value, "dict")):
        try:
            return _serialize_event(value.dict())
        except Exception:
            pass
    if hasattr(value, "__dict__"):
        try:
            return {
                key: _serialize_event(val)
                for key, val in value.__dict__.items()
                if not key.startswith("_")
            }
        except Exception:
            pass
    return str(value)


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
            _emit_subscriber(
                str(author),
                total_subscribers,
                channel_id,
                raw=entry,
                platform="youtube",
            )
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
            _emit_donation(
                str(author),
                amount,
                currency,
                message_text,
                formatted_amount,
                raw=entry,
                platform="youtube",
            )
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
    stop_event = threading.Event()

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
    if args.platform == "youtube" and token:
        streamlabs_thread = threading.Thread(
            target=_run_streamlabs_listener, args=(token, stop_event), daemon=True
        )
        streamlabs_thread.start()
    elif args.platform != "youtube" and token:
        _emit_log(
            "Streamlabs integration is only available for YouTube streams",
            level="info",
            platform=args.platform,
        )

    exit_code = 0
    try:
        if args.platform == "youtube":
            stream_identifier = _normalize_stream_identifier(args.stream)
            try:
                _run_with_pytchat(stream_identifier, stop_event)
            except ModuleNotFoundError:
                _emit_log(
                    "pytchat not installed; using placeholder output",
                    level="warning",
                    streamIdentifier=stream_identifier,
                    platform="youtube",
                )
                _run_placeholder(
                    stream_identifier,
                    args.interval,
                    stop_event,
                    platform="youtube",
                    author="YouTube",
                )
            except YouTubeStreamUnavailableError as exc:
                _emit_log(
                    "Unable to reach YouTube chat; using placeholder output",
                    level="warning",
                    streamIdentifier=stream_identifier,
                    error=str(exc),
                    platform="youtube",
                )
                _run_placeholder(
                    stream_identifier,
                    args.interval,
                    stop_event,
                    platform="youtube",
                    author="YouTube",
                )
        else:
            exit_code = _run_tiktok_listener(
                args.tiktok_username,
                args.tiktok_room_id,
                args.tiktok_session_id,
                args.tiktok_ms_token,
                args.interval,
                stop_event,
            )
    except KeyboardInterrupt:
        exit_code = 0
    except Exception as exc:  # pragma: no cover - defensive logging
        _emit_error("Unhandled listener error", error=str(exc))
        exit_code = 1
    finally:
        stop_event.set()
        if streamlabs_thread is not None:
            streamlabs_thread.join(timeout=5.0)
        if http_thread is not None:
            http_thread.join(timeout=5.0)

    return exit_code


if __name__ == "__main__":
    sys.exit(main())
