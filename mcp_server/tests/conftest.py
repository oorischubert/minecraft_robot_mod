from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from fake_minebot import FakeMineBotServer  # noqa: E402


@pytest.fixture
def fake():
    with FakeMineBotServer() as server:
        robot = server.add_robot("ROBOT001", name="Rusty")
        robot.slots[0] = ["minecraft:iron_pickaxe", 1]
        robot.slots[1] = ["minecraft:cobblestone", 32]
        robot.slots[2] = ["minecraft:blaze_powder", 5]
        yield server
