#!/usr/bin/env python3
"""Simple YouTube chat listener used by the ExamplePlugin.

The script prefers the third-party ``pytchat`` library when available, but will
fall back to emitting placeholder messages so the Java plugin can demonstrate
its integration without additional dependencies.
"""

from __future__ import annotations

import argparse
import os
import json
import re
import sys
import threading
import time
from datetime import datetime
from typing import Any, Dict, Iterable, Optional
from urllib.parse import parse_qs, urlparse


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
    return parser.parse_args()


def _emit(message: str) -> None:
    sys.stdout.write(message + "\n")
    sys.stdout.flush()


def _emit_json(payload: Dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


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


def _run_with_pytchat(stream_identifier: str, stop_event: threading.Event) -> None:
    import pytchat

    chat = pytchat.create(video_id=stream_identifier)
    while not stop_event.is_set() and chat.is_alive():
        for item in chat.get().items:
            author = getattr(item.author, "name", "YouTube")
            channel_id = getattr(item.author, "channelId", None)
            message_id = _safe_int(getattr(item, "timestamp", None))
            _emit_chat(author, item.message, channel_id=channel_id, message_id=message_id)
        time.sleep(1)


def _run_placeholder(stream_identifier: str, interval: int, stop_event: threading.Event) -> None:
    _emit_chat("YouTube", f"Simulated relay for {stream_identifier}")
    while not stop_event.is_set():
        time.sleep(interval)
        _emit_log("Heartbeat", stream="placeholder", streamIdentifier=stream_identifier)


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

        if event_type not in {"subscription", "resubscription", "mass_subscription"}:
            continue

        author = (
            entry.get("name")
            or entry.get("display_name")
            or entry.get("from")
            or "YouTube Subscriber"
        )
        total_subscribers = _safe_int(entry.get("count") or entry.get("amount"))
        channel_id = entry.get("channel_id")
        _emit_subscriber(str(author), total_subscribers, channel_id, raw=entry)


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

    streamlabs_thread: Optional[threading.Thread] = None
    token = args.streamlabs_token or os.environ.get("STREAMLABS_SOCKET_TOKEN")
    if token:
        streamlabs_thread = threading.Thread(
            target=_run_streamlabs_listener, args=(token, stop_event), daemon=True
        )
        streamlabs_thread.start()

    try:
        try:
            _run_with_pytchat(stream_identifier, stop_event)
        except ModuleNotFoundError:
            _emit_log(
                "pytchat not installed; using placeholder output",
                level="warning",
                streamIdentifier=stream_identifier,
            )
            _run_placeholder(stream_identifier, args.interval, stop_event)
    except KeyboardInterrupt:
        return 0
    except Exception as exc:  # pragma: no cover - defensive logging
        _emit_error("Unhandled listener error", error=str(exc))
        return 1
    finally:
        stop_event.set()
        if streamlabs_thread is not None:
            streamlabs_thread.join(timeout=5.0)

    return 0


if __name__ == "__main__":
    sys.exit(main())
