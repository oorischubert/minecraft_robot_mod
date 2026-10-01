from __future__ import annotations

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from minebot import MineBot

DEFAULT_URL = "ws://127.0.0.1:8765/minebot"


def resolve_listing_url(argv: list[str]) -> str:
    if len(argv) >= 2:
        return argv[1]
    return os.environ.get("MINEBOT_URL", DEFAULT_URL)


def resolve_connection(argv: list[str], usage: str, allow_auto_discovery: bool = True) -> tuple[str, str]:
    socket_url = argv[1] if len(argv) >= 2 else os.environ.get("MINEBOT_URL", DEFAULT_URL)
    robot_code = argv[2] if len(argv) >= 3 else os.environ.get("MINEBOT_CODE")

    if socket_url and robot_code:
        return socket_url, robot_code

    if allow_auto_discovery:
        robots = MineBot.list_robots(url=socket_url)
        if robots:
            first_robot = robots[0]
            endpoint = str(first_robot.get("endpoint") or socket_url)
            code = str(first_robot.get("code") or "")
            if code:
                return endpoint, code

    raise SystemExit(f"{usage}\nTip: set MINEBOT_URL and MINEBOT_CODE, or summon a robot in the world.")
