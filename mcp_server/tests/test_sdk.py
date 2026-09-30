"""SDK tests for the protocol additions (contract section 6) against the fake bridge."""

from __future__ import annotations

import threading
import time

import pytest

import minebot
from fake_minebot import TINY_PNG
from minebot import (
    MINEBOT_CODE_TO_EXCEPTION,
    MineBot,
    MineBotBrokeFreeError,
    MineBotCameraAssetsUnavailableError,
    MineBotCameraUnavailableError,
    MineBotCommandError,
    MineBotConnectionError,
    MineBotEntityNotFoundError,
    MineBotErrorCode,
    MineBotInteractionError,
    MineBotInvalidRequestError,
    MineBotNotLookingAtEntityError,
    MineBotOutOfEnergyError,
    MineBotPlayerNotFoundError,
    MineBotWrongTargetError,
)


@pytest.fixture
def robot(fake):
    client = MineBot(code="ROBOT001", url=fake.url)
    client.connect()
    yield client
    client.close()


def test_camera_snapshot_source(fake, robot):
    assert robot.camera.snapshot() == TINY_PNG
    assert fake.requests_for("camera_snapshot")[-1]["source"] == "render"
    frames = list(robot.camera.stream(interval=0, frame_limit=2, source="client"))
    assert frames == [TINY_PNG, TINY_PNG]
    assert [r["source"] for r in fake.requests_for("camera_snapshot")[-2:]] == ["client", "client"]
    assert MINEBOT_CODE_TO_EXCEPTION["camera_assets_unavailable"] is MineBotCameraAssetsUnavailableError
    assert issubclass(MineBotCameraAssetsUnavailableError, MineBotCameraUnavailableError)
    fake.camera_error = ("camera_assets_unavailable", "no textures")
    with pytest.raises(MineBotCameraAssetsUnavailableError):
        robot.camera.snapshot()


def test_new_codes_are_mapped():
    assert MINEBOT_CODE_TO_EXCEPTION["out_of_energy"] is MineBotOutOfEnergyError
    assert MINEBOT_CODE_TO_EXCEPTION["not_looking_at_entity"] is MineBotNotLookingAtEntityError
    assert MINEBOT_CODE_TO_EXCEPTION["entity_not_found"] is MineBotEntityNotFoundError
    assert MINEBOT_CODE_TO_EXCEPTION["player_not_found"] is MineBotPlayerNotFoundError
    assert issubclass(MineBotNotLookingAtEntityError, MineBotInteractionError)
    assert MineBotErrorCode("out_of_energy") is MineBotErrorCode.OUT_OF_ENERGY
    for name in ("MineBotOutOfEnergyError", "MineBotNotLookingAtEntityError", "MineBotEntityNotFoundError", "MineBotPlayerNotFoundError"):
        assert name in minebot.__all__


def test_public_methods_have_docstrings():
    for name in ("command", "read_chat", "wait_for_chat", "say", "inventory", "scan_blocks", "scan_entities",
                 "environment", "stop", "look_at", "attack_entity", "use_item", "use_on_entity", "move_item",
                 "refuel", "mine", "move_to", "is_connected", "move_absolute"):
        assert getattr(MineBot, name).__doc__, name


def test_command_and_read_chat(fake, robot):
    assert robot.command("environment")["biome"] == "minecraft:plains"
    fake.inject_chat("ROBOT001", "hello")
    fake.inject_chat("ROBOT001", "second")
    peeked = robot.read_chat(peek=True, limit=1)
    assert [m["text"] for m in peeked["messages"]] == ["hello"]
    assert fake.requests_for("read_chat")[-1] == {**fake.requests_for("read_chat")[-1], "peek": True, "limit": 1}
    result = robot.read_chat()
    assert [m["text"] for m in result["messages"]] == ["hello", "second"]
    assert robot.read_chat()["messages"] == []


def test_wait_for_chat_returns_early_and_times_out(fake, robot):
    assert robot.wait_for_chat(timeout=0.3, poll_interval=0.05) == []
    threading.Timer(0.2, lambda: fake.inject_chat("ROBOT001", "come here")).start()
    started = time.monotonic()
    messages = robot.wait_for_chat(timeout=5.0, poll_interval=0.05)
    assert time.monotonic() - started < 2.0
    assert messages[0]["text"] == "come here"


def test_say_to_player(fake, robot):
    assert robot.say("hi", 3, to="steve")["to"] == "Steve"
    assert fake.requests_for("print")[-1]["message"] == "hi 3"
    with pytest.raises(MineBotPlayerNotFoundError) as info:
        robot.say("hi", to="Nobody")
    assert info.value.raw_code == "player_not_found"
    robot.print("legacy", "print")  # still works
    assert "to" not in fake.requests_for("print")[-1]


def test_perception_methods(fake, robot):
    inventory = robot.inventory()
    assert len(inventory["slots"]) == 10 and inventory["slots"][0]["max_damage"] == 250
    scan = robot.scan_blocks(radius=2, blocks=["minecraft:grass_block"], limit=5, center=(0, 63, 0))
    sent = fake.requests_for("scan_blocks")[-1]
    assert (sent["center_x"], sent["center_y"], sent["center_z"]) == (0, 63, 0)
    assert sent["blocks"] == ["minecraft:grass_block"] and "exposed_only" not in sent
    assert scan["truncated"] and len(scan["matches"]) == 5
    fake.entities.append({"entity_id": 7, "type": "minecraft:player", "name": "Steve", "category": "player", "x": 3.5, "y": 64.0, "z": 0.5})
    entities = robot.scan_entities(players_only=True)
    assert entities["entities"][0]["name"] == "Steve"
    assert robot.environment()["block_below"] == "minecraft:grass_block"


def test_look_at_and_inspect_extra_keys(fake, robot):
    with pytest.raises(MineBotInvalidRequestError):
        robot.look_at(x=1.0)
    robot.look_at(0.5, 63.5, 2.5)
    target = robot.camera.inspect()
    assert (target["x"], target["y"], target["z"]) == (0, 63, 2)
    assert target["face"] == "up" and target["in_reach"] is True
    assert robot.look_type()["block"] == "minecraft:grass_block"
    with pytest.raises(MineBotEntityNotFoundError):
        robot.look_at(entity_id=99)


def test_entity_actions(fake, robot):
    with pytest.raises(MineBotNotLookingAtEntityError) as info:
        robot.attack_entity()
    assert isinstance(info.value, MineBotInteractionError)
    fake.entities.append({"entity_id": 55, "type": "minecraft:zombie", "name": "Zombie", "category": "hostile", "x": 0.5, "y": 64.0, "z": 2.5})
    robot.look_at(entity_id=55)
    assert robot.attack_entity()["hit"] is True
    assert robot.use_on_entity()["entity"] == "minecraft:zombie"
    assert robot.use_item()["accepted"] is True


def test_move_item_and_refuel(fake, robot):
    assert robot.move_item(1, 5)["to"] == 5
    assert fake.requests_for("move_item")[-1]["from"] == 1
    assert robot.refuel(count=2)["fuel_count"] == 5
    assert fake.requests_for("refuel")[-1]["count"] == 2


def test_move_to_sends_real_y(fake, robot):
    assert robot.move_to(3.5, 4.5, y=70) is True
    sent = fake.requests_for("move_to")[-1]
    assert sent["y"] == 70.0 and sent["z"] == 4.5
    robot.move_to(3.5, 1.5)
    assert "y" not in fake.requests_for("move_to")[-1]
    assert robot.move_absolute(1.0, 0.0) is True


def test_mine(fake, robot):
    fake.world[(0, 64, 2)] = "minecraft:stone"
    robot.look_at(0.5, 64.5, 2.5)
    result = robot.mine(timeout=5.0, poll_interval=0.05)
    assert result == {"broken": True, "block": "minecraft:stone", "pos": "0, 64, 2", "message": ""}
    fake.world[(0, 64, 2)] = "minecraft:bedrock"
    with pytest.raises(MineBotCommandError) as info:
        robot.mine()
    assert "cannot be broken" in str(info.value)


def test_stop_and_out_of_energy(fake, robot):
    assert robot.stop() == {"stopped": True}
    fake.robots["ROBOT001"].fuel_count = 0
    with pytest.raises(MineBotOutOfEnergyError) as info:
        robot.turn_to(yaw=90)
    assert str(info.value).startswith("out_of_energy:")


def test_wrong_block_still_typed(fake, robot):
    robot.turn_to(pitch=0)
    with pytest.raises(MineBotWrongTargetError) as info:
        robot.craft("minecraft:furnace")
    assert str(info.value) == "wrong_block: Not looking at crafting table"


def test_dropped_connection_raises_connection_error(fake, robot):
    fake.drop_all()
    time.sleep(0.1)
    with pytest.raises(MineBotConnectionError):
        robot.status()
    assert not robot.is_connected()


def test_evil_breaks_free(fake, robot):
    with pytest.raises(MineBotBrokeFreeError):
        robot.evil()
    assert not robot.is_connected()
    assert fake.robots["ROBOT001"].evil and not fake.robots["ROBOT001"].connected
    assert next(r for r in MineBot.list_robots(url=fake.url) if r["code"] == "ROBOT001")["evil"] is True
    with pytest.raises(MineBotBrokeFreeError):
        MineBot(code="ROBOT001", url=fake.url).connect()
