#!/usr/bin/env python3
"""Simple YouTube chat listener used by the ExamplePlugin.

The script prefers the third-party ``pytchat`` library when available, but will
fall back to emitting placeholder messages so the Java plugin can demonstrate
its integration without additional dependencies.
"""

from __future__ import annotations

import argparse
import sys
import time
from datetime import datetime


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

    try:
        _run_with_pytchat(args.stream)
    except ModuleNotFoundError:
        _emit("YouTube: pytchat not installed; using placeholder output")
        _run_placeholder(args.stream, args.interval)
    except KeyboardInterrupt:
        return 0
    except Exception as exc:  # pragma: no cover - defensive logging
        _emit(f"Error: {exc}")
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
