#!/usr/bin/env python3
"""Simple YouTube chat listener used by the ExamplePlugin.

The script prefers the third-party ``pytchat`` library when available, but will
fall back to emitting placeholder messages so the Java plugin can demonstrate
its integration without additional dependencies.
"""

from __future__ import annotations

import argparse
import re
import sys
import time
from datetime import datetime
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
    return parser.parse_args()


def _emit(message: str) -> None:
    sys.stdout.write(message + "\n")
    sys.stdout.flush()


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


def _run_with_pytchat(stream_identifier: str) -> None:
    import pytchat

    chat = pytchat.create(video_id=stream_identifier)
    while chat.is_alive():
        for item in chat.get().items:
            _emit(f"{item.author.name}: {item.message}")
        time.sleep(1)


def _run_placeholder(stream_identifier: str, interval: int) -> None:
    _emit(f"YouTube: Simulated relay for {stream_identifier}")
    while True:
        time.sleep(interval)
        _emit(f"YouTube: Heartbeat at {datetime.utcnow().isoformat()}Z")


def main() -> int:
    args = _parse_args()
    stream_identifier = _normalize_stream_identifier(args.stream)

    try:
        _run_with_pytchat(stream_identifier)
    except ModuleNotFoundError:
        _emit("YouTube: pytchat not installed; using placeholder output")
        _run_placeholder(stream_identifier, args.interval)
    except KeyboardInterrupt:
        return 0
    except Exception as exc:  # pragma: no cover - defensive logging
        _emit(f"Error: {exc}")
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
