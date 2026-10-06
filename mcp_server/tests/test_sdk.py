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
    MineBotDiedError,
    MineBotEntityNotFoundError,
    MineBotErrorCode,
    MineBotInteractionError,
    MineBotInteractionUnavailableError,
    MineBotInvalidItemError,
    MineBotInvalidRequestError,
    MineBotMissingItemError,
    MineBotNotLookingAtEntityError,
    MineBotOutOfEnergyError,
    MineBotPlayerNotFoundError,
    MineBotTargetFullError,
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
                 "refuel", "eat", "mine", "move_to", "is_connected", "move_absolute"):
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


def test_attack_entity_until_dead(fake, robot):
    fake.entities.append({"entity_id": 56, "type": "minecraft:blaze", "name": "Blaze", "category": "hostile", "x": 0.5, "y": 64.0, "z": 2.5, "health": 20.0})
    robot.look_at(entity_id=56)
    fight = robot.attack_entity(until_dead=True, follow=True, max_seconds=10, poll_interval=0.02)
    assert fight["killed"] is True and fight["ended"] == "killed" and fight["hits"] == 3
    sent = fake.requests_for("attack_entity")[-1]
    assert sent["until_dead"] is True and sent["follow"] is True and sent["guard"] is True and sent["max_seconds"] == 10.0 and "min_health" not in sent
    assert fight["guard"] is False  # no shield in the hotbar
    assert robot.status()["last_fight"] == fight

    fake.entities.append({"entity_id": 57, "type": "minecraft:blaze", "name": "Blaze", "category": "hostile", "x": 0.5, "y": 64.0, "z": 2.5, "health": 20.0})
    robot.look_at(entity_id=57)
    started = robot.attack_entity(until_dead=True, wait=False)
    assert started["fighting"] is True and robot.status()["fighting"] is True
    robot.stop()
    status = robot.status()
    assert status["fighting"] is False and status["last_fight"]["ended"] == "interrupted"

    fake.fight_end = ("out_of_reach", "Blaze went out of sight for 3 s")
    gone = robot.attack_entity(until_dead=True, guard=False, poll_interval=0.02)
    assert gone["killed"] is False and gone["message"] == "Blaze went out of sight for 3 s"
    assert fake.requests_for("attack_entity")[-1]["guard"] is False

    # a named target out of reach is fought with follow
    fake.entities.append({"entity_id": 58, "type": "minecraft:blaze", "name": "Blaze", "category": "hostile", "x": 0.5, "y": 64.0, "z": 9.5, "health": 20.0})
    far = robot.attack_entity(until_dead=True, follow=True, entity_id=58, poll_interval=0.02)
    assert far["killed"] is True and fake.requests_for("attack_entity")[-1]["entity_id"] == 58

    fake.robots["ROBOT001"].health = 6.0
    with pytest.raises(MineBotInteractionUnavailableError):
        robot.attack_entity(until_dead=True)


def test_status_reports_recent_hurts(fake, robot):
    assert robot.status()["hurt_count"] == 0 and robot.status()["recent_hurt"] == []
    fake.hurt_robot("ROBOT001", 5.0, cause="minecraft:fireball", attacker="Blaze", attacker_type="minecraft:blaze", attacker_id=812, projectile="minecraft:small_fireball")
    fake.hurt_robot("ROBOT001", 1.0, cause="minecraft:arrow", attacker="Skeleton", seen=False, projectile="minecraft:arrow")
    status = robot.status()
    assert status["hurt_count"] == 2 and status["health"] == 14.0
    first, second = status["recent_hurt"]
    assert first["cause"] == "minecraft:fireball" and first["attacker"] == "Blaze" and first["attacker_seen"] is True
    assert second["attacker_seen"] is False and "attacker" not in second and second["id"] == 2


def test_use_item_holds_bows_and_crossbows(fake, robot):
    bot = fake.robots["ROBOT001"]
    bot.slots[3] = ["minecraft:bow", 1]
    robot.select_slot(3)
    with pytest.raises(MineBotMissingItemError) as info:
        robot.use_item()
    assert "ammunition" in info.value.detail
    bot.slots[4] = ["minecraft:arrow", 16]
    shot = robot.use_item(poll_interval=0.02)
    assert shot["completed"] is True and shot["held_ticks"] == 20
    assert shot["projectiles"] == ["minecraft:arrow"] and shot["spent"] == {"minecraft:arrow": 1}
    assert fake.requests_for("use_item")[-1].get("hold_seconds") is None
    started = robot.use_item(hold_seconds=0.5, wait=False)
    assert started["holding"] is True and started["eta_ticks"] == 10
    assert fake.requests_for("use_item")[-1]["hold_seconds"] == 0.5
    robot.stop()
    assert robot.status()["last_use"]["completed"] is False
    bot.slots[3] = ["minecraft:crossbow", 1]
    assert robot.use_item(poll_interval=0.02)["charged"] is True
    fired = robot.use_item()
    assert fired["projectiles"] == ["minecraft:arrow"] and "holding" not in fired
    bot.slots[3] = ["minecraft:bread", 1]
    with pytest.raises(MineBotInteractionUnavailableError):
        robot.use_item()


def test_move_item_and_refuel(fake, robot):
    assert robot.move_item(1, 5)["to"] == 5
    assert fake.requests_for("move_item")[-1]["from"] == 1
    assert robot.refuel(count=2)["fuel_count"] == 5
    assert fake.requests_for("refuel")[-1]["count"] == 2


def test_eat_heals_a_heart_per_ingot(fake, robot):
    bot = fake.robots["ROBOT001"]
    with pytest.raises(MineBotTargetFullError):
        robot.eat()
    bot.health = 13.0
    with pytest.raises(MineBotMissingItemError):
        robot.eat()
    bot.slots[3] = ["minecraft:iron_ingot", 5]
    bot.slots[4] = ["minecraft:copper_ingot", 1]
    with pytest.raises(MineBotInvalidItemError):
        robot.eat(item="minecraft:gold_ingot")
    first = robot.eat(count=2)
    assert first == {"eaten": 2, "spent": {"minecraft:copper_ingot": 1, "minecraft:iron_ingot": 1}, "healed": 4.0, "health": 17.0, "max_health": 20.0}
    assert fake.requests_for("eat")[-1]["count"] == 2
    rest = robot.eat(item="minecraft:iron_ingot")
    assert rest["eaten"] == 2 and rest["health"] == 20.0 and rest["healed"] == 3.0
    assert bot.slots[3] == ["minecraft:iron_ingot", 2]


def test_move_to_judges_by_final_position(fake, robot):
    state = fake.robots["ROBOT001"]
    state.move_fail = "MineBot could not continue moving to that location"
    state.move_end = (3.3, 64.0, 4.5)
    assert robot.move_to(3.5, 4.5) is True
    state.move_end = (8.5, 56.0, 8.5)
    with pytest.raises(MineBotCommandError) as error:
        robot.move_to(8.5, 8.5)
    assert error.value.code == MineBotErrorCode.MOVEMENT_FAILED.value
    assert "8.0 blocks below it" in str(error.value)


def test_move_to_sends_real_y(fake, robot):
    assert robot.move_to(3.5, 4.5, y=70) is True
    sent = fake.requests_for("move_to")[-1]
    assert sent["y"] == 70.0 and sent["z"] == 4.5
    robot.move_to(3.5, 1.5)
    assert "y" not in fake.requests_for("move_to")[-1]
    assert robot.move_absolute(1.0, 0.0) is True


def test_pillar_up(fake, robot):
    robot.select_slot(1)
    assert robot.pillar_up(count=2, poll_interval=0.01) == {"placed": 2, "x": 0.5, "y": 66.0, "z": 0.5}
    fake.world[(0, 69, 0)] = "minecraft:stone"
    with pytest.raises(minebot.MineBotMovementFailedError, match="placed 1 of 2 blocks"):
        robot.pillar_up(count=2, poll_interval=0.01)


def test_bridge(fake, robot):
    for x in (1, 2, 3):
        del fake.world[(x, 63, 0)]
    robot.select_slot(1)
    assert robot.bridge("east", count=2, poll_interval=0.01) == {"placed": 2, "x": 2.5, "y": 64.0, "z": 0.5}
    assert fake.requests_for("bridge")[-1]["direction"] == "east"
    with pytest.raises(minebot.MineBotMovementFailedError, match="placed 1 of 2 blocks"):
        robot.bridge("east", count=2, poll_interval=0.01)


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


def test_death_pushed_between_requests_raises_died(fake, robot):
    fake.inject_chat("ROBOT001", "come here")
    fake.kill_robot("ROBOT001")
    time.sleep(0.3)  # the error and the close are both waiting on the socket before the next request
    with pytest.raises(MineBotDiedError) as caught:
        robot.status()
    death = caught.value.death
    assert caught.value.raw_code == "died" and caught.value.detail == "Rusty was slain by Zombie"
    assert death["code"] == "ROBOT001" and death["killer"] == "Zombie" and death["cause"] == "minecraft:mob_attack"
    assert [m["text"] for m in death["unread_chat"]] == ["come here"]
    assert not robot.is_connected()
    assert MINEBOT_CODE_TO_EXCEPTION["died"] is MineBotDiedError and "MineBotDiedError" in minebot.__all__


def test_keepalive_ping_between_requests_does_not_stall(fake):
    client = MineBot(code="ROBOT001", url=fake.url, timeout=2)
    client.connect()
    try:
        fake.ping_all()
        time.sleep(0.2)  # the ping is waiting on the socket before the next request
        started = time.monotonic()
        assert client.status()["code"] == "ROBOT001"
        assert time.monotonic() - started < 1.0 and client.is_connected()

        # A ping just before a pushed death still lets the death through.
        fake.ping_all()
        fake.kill_robot("ROBOT001")
        time.sleep(0.3)
        with pytest.raises(MineBotDiedError):
            client.status()
        assert not client.is_connected()
    finally:
        client.close()


def test_connect_to_dead_robot_raises_died(fake, robot):
    fake.kill_robot("ROBOT001", killer=None, push=False)
    time.sleep(0.1)
    with pytest.raises(MineBotConnectionError):
        robot.status()  # the push was lost with the socket
    assert all(r["code"] != "ROBOT001" for r in MineBot.list_robots(url=fake.url))
    fresh = MineBot(code="ROBOT001", url=fake.url)
    with pytest.raises(MineBotDiedError) as caught:
        fresh.connect()
    assert caught.value.death["message"] == "Rusty died" and "killer" not in caught.value.death
    assert not fresh.is_connected()


def test_list_robots_marks_robots_not_loaded_and_hides_the_dead(fake):
    fake.add_robot("FAR00001", name="Faraway", loaded=False, x=900.5)
    fake.add_robot("DOOMED01", name="Doomed")
    fake.kill_robot("DOOMED01")
    listed = MineBot.list_robots(url=fake.url)
    assert [(r["code"], r["loaded"], r["dead"]) for r in listed] == [("ROBOT001", True, False), ("FAR00001", False, False)]
    assert listed[1]["x"] == 900.5 and "entity_id" not in listed[1]
    everything = MineBot.list_robots(url=fake.url, include_dead=True)
    assert [r["code"] for r in everything] == ["ROBOT001", "FAR00001", "DOOMED01"]
    assert everything[2]["dead"] is True and everything[2]["death"]["message"] == "Doomed was slain by Zombie"


def test_connect_loads_a_robot_that_is_not_loaded(fake):
    fake.add_robot("FAR00001", loaded=False, x=900.5)
    client = MineBot(code="FAR00001", url=fake.url)
    try:
        assert client.connect()["x"] == 900.5
        assert fake.loads == ["FAR00001"] and fake.robots["FAR00001"].connected
        assert MineBot.connect.__doc__ and "loaded" in MineBot.connect.__doc__
    finally:
        client.close()


def test_evil_breaks_free(fake, robot):
    with pytest.raises(MineBotBrokeFreeError):
        robot.evil()
    assert not robot.is_connected()
    assert fake.robots["ROBOT001"].evil and not fake.robots["ROBOT001"].connected
    assert next(r for r in MineBot.list_robots(url=fake.url) if r["code"] == "ROBOT001")["evil"] is True
    with pytest.raises(MineBotBrokeFreeError):
        MineBot(code="ROBOT001", url=fake.url).connect()
