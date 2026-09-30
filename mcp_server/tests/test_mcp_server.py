"""End-to-end tests of the MCP tools through a real MCP client session (in memory) against the fake bridge."""

from __future__ import annotations

import base64
import functools
import json
import threading
import time
from contextlib import asynccontextmanager

import anyio
import pytest
from mcp.shared.memory import create_connected_server_and_client_session

from fake_minebot import TINY_PNG, FakeMineBotServer
from minebot import MineBot
from minebot_mcp.server import create_server
from minebot_mcp.session import Settings

pytestmark = pytest.mark.anyio

EXPECTED_TOOLS = {
    "list_robots", "connect", "disconnect", "status", "turn_evil",
    "move_to", "move_by", "move", "turn_to", "turn_by", "look_at", "look_at_entity", "jump", "crouch",
    "center", "stop", "enter_vehicle", "exit_vehicle", "go_to_player",
    "mine", "mine_block", "collect_items", "place", "place_block", "use_item", "use_on_entity", "attack_entity",
    "inventory", "select_slot", "equip", "drop", "move_item", "refuel", "craft",
    "chest_inspect", "chest_put", "chest_take", "furnace_inspect", "furnace_put", "furnace_take",
    "inspect", "snapshot", "scan_blocks", "scan_entities", "nearby_players", "environment",
    "wait_for_chat", "read_chat", "say",
}


@pytest.fixture
def anyio_backend():
    return "asyncio"


def settings_for(fake: FakeMineBotServer, **overrides) -> Settings:
    values = dict(url=fake.url, keepalive_interval=0.0, poll_interval=0.05, chat_poll_interval=0.05, socket_timeout=5.0)
    values.update(overrides)
    return Settings(**values)


@asynccontextmanager
async def mcp_client(fake: FakeMineBotServer, **overrides):
    server = create_server(settings_for(fake, **overrides))
    async with create_connected_server_and_client_session(server) as client:
        yield client


def text_of(result) -> str:
    return "\n".join(c.text for c in result.content if c.type == "text")


def payload_of(result) -> dict:
    lines = [line for line in text_of(result).splitlines() if not line.startswith("NOTE:")]
    return json.loads("\n".join(lines))


async def call(client, tool, /, **arguments):
    return await client.call_tool(tool, arguments)


# ---------------------------------------------------------------------------------- basics
async def test_all_tools_listed_with_docs(fake):
    async with mcp_client(fake) as client:
        tools = (await client.list_tools()).tools
        assert {t.name for t in tools} == EXPECTED_TOOLS
        assert all(t.description and len(t.description) > 20 for t in tools)
        assert all(t.outputSchema is None for t in tools)  # plain text results, no duplicated structured copy


async def test_initialize_has_instructions(fake):
    server = create_server(settings_for(fake))
    async with create_connected_server_and_client_session(server) as client:
        result = await client.initialize()
        assert "wait_for_chat" in (result.instructions or "")


async def test_auto_connect_skips_busy_and_evil_robots():
    with FakeMineBotServer() as fake:
        fake.add_robot("BUSY0001", connected=True)
        fake.add_robot("EVIL0001", evil=True)
        fake.add_robot("FREE0001", name="Freddy")
        async with mcp_client(fake) as client:
            result = await call(client, "status")
            assert not result.isError, text_of(result)
            assert "NOTE: Connected to robot FREE0001 (Freddy)" in text_of(result)
            status = payload_of(result)
            assert status["code"] == "FREE0001"
            assert status["facing"] == "south(+Z)"
            assert "chunk_x" not in status and "energy_milliblocks" not in status
            assert fake.robots["FREE0001"].connected
            # the next call does not announce again
            assert "NOTE" not in text_of(await call(client, "status"))


async def test_env_code_is_preferred(fake):
    fake.add_robot("ROBOT002")
    async with mcp_client(fake, code="ROBOT002") as client:
        assert payload_of(await call(client, "status"))["code"] == "ROBOT002"


async def test_all_robots_busy_error():
    with FakeMineBotServer() as fake:
        fake.add_robot("BUSY0001", connected=True)
        async with mcp_client(fake) as client:
            result = await call(client, "inventory")
            assert result.isError
            assert "no_free_robot" in text_of(result) and "BUSY0001" in text_of(result)


async def test_unreachable_bridge_error_mentions_world_and_url():
    settings = Settings(url="ws://127.0.0.1:9/minebot", keepalive_interval=0.0)
    async with create_connected_server_and_client_session(create_server(settings)) as client:
        result = await call(client, "status")
        assert result.isError
        text = text_of(result)
        assert "connection_failed" in text
        assert "ws://127.0.0.1:9/minebot" in text
        assert "Minecraft world must be open" in text
        assert "Traceback" not in text


async def test_list_connect_disconnect(fake):
    fake.add_robot("ROBOT002", name="Second")
    async with mcp_client(fake) as client:
        listing = payload_of(await call(client, "list_robots"))
        assert [r["code"] for r in listing["robots"]] == ["ROBOT001", "ROBOT002"]
        connected = payload_of(await call(client, "connect", code="robot002"))
        assert connected["code"] == "ROBOT002" and fake.robots["ROBOT002"].connected
        listing = payload_of(await call(client, "list_robots"))
        assert listing["robots"][1].get("this_session") is True
        assert "Disconnected from robot ROBOT002" in text_of(await call(client, "disconnect"))
        time.sleep(0.1)
        assert not fake.robots["ROBOT002"].connected
        # explicit connect errors are clear
        fake.robots["ROBOT001"].connected = True
        result = await call(client, "connect", code="ROBOT001")
        assert result.isError and "program_running" in text_of(result)
        result = await call(client, "connect", code="NOPE0000")
        assert result.isError and "not_found" in text_of(result)


async def test_turn_evil(fake):
    fake.add_robot("ROBOT002", name="Second")
    async with mcp_client(fake) as client:
        tools = {t.name: t for t in (await client.list_tools()).tools}
        assert tools["turn_evil"].annotations.destructiveHint is True
        # never auto-connects: it must not turn a robot this chat did not pick
        result = await call(client, "turn_evil")
        assert result.isError and "not_connected" in text_of(result)
        assert not any(r.connected or r.evil for r in fake.robots.values())

        await call(client, "status")
        result = await call(client, "turn_evil")
        assert not result.isError, text_of(result)
        assert "Robot ROBOT001 broke free and turned evil" in text_of(result)
        assert fake.robots["ROBOT001"].evil and not fake.robots["ROBOT001"].connected
        assert fake.actions()[-1] == "evil"

        # the chat does not silently take over another robot
        result = await call(client, "wait_for_chat", timeout=0.1)
        assert result.isError and "not_connected: Robot ROBOT001 turned evil" in text_of(result)
        assert not fake.robots["ROBOT002"].connected
        listing = payload_of(await call(client, "list_robots"))
        assert listing["robots"][0]["evil"] is True and "this_session" not in listing["robots"][0]

        result = await call(client, "connect", code="ROBOT001")
        assert result.isError and "broke_free" in text_of(result)
        result = await call(client, "status")
        assert result.isError and "not_connected" in text_of(result)  # a failed connect keeps the block
        assert payload_of(await call(client, "connect"))["code"] == "ROBOT002"
        assert not (await call(client, "inventory")).isError


async def test_turn_evil_on_dropped_socket_does_not_claim_success(fake):
    async with mcp_client(fake) as client:
        await call(client, "status")
        fake.drop_all()
        await anyio.sleep(0.1)
        result = await call(client, "turn_evil")
        assert result.isError and "connection_lost" in text_of(result) and "not listed as evil" in text_of(result)
        assert not fake.robots["ROBOT001"].evil
        # the retry reconnects to the same robot first
        result = await call(client, "turn_evil")
        assert not result.isError, text_of(result)
        assert "reconnected to robot ROBOT001" in text_of(result)
        assert fake.robots["ROBOT001"].evil


# ---------------------------------------------------------------------------------- errors
async def test_sdk_errors_become_coded_tool_errors(fake):
    async with mcp_client(fake) as client:
        result = await call(client, "craft", item="minecraft:furnace")
        assert result.isError
        text = text_of(result)
        assert "wrong_block: Not looking at crafting table" in text
        assert "Traceback" not in text
        result = await call(client, "say", message="hi", to="Herobrine")
        assert result.isError and "player_not_found: Player Herobrine is not online" in text_of(result)
        fake.robots["ROBOT001"].fuel_count = 0
        result = await call(client, "move_to", x=5.5, z=5.5)
        assert result.isError and "out_of_energy" in text_of(result) and "refuel" in text_of(result)
        fake.robots["ROBOT001"].fuel_count = 3
        result = await call(client, "attack_entity")
        assert result.isError and "not_looking_at_entity" in text_of(result)


# ---------------------------------------------------------------------------------- lock
async def test_lock_serialises_concurrent_tool_calls(fake, monkeypatch):
    active = 0
    peak = 0
    guard = threading.Lock()
    original = MineBot._send_raw

    def tracking_send_raw(self, payload):
        nonlocal active, peak
        with guard:
            active += 1
            peak = max(peak, active)
        try:
            time.sleep(0.02)
            return original(self, payload)
        finally:
            with guard:
                active -= 1

    monkeypatch.setattr(MineBot, "_send_raw", tracking_send_raw)
    fake.inject_chat("ROBOT001", "hello")
    async with mcp_client(fake) as client:
        await call(client, "status")  # connect first
        results: dict[str, object] = {}

        async def run(name, **args):
            results[name] = await call(client, name, **args)

        async with anyio.create_task_group() as tg:
            for name in ("inventory", "environment", "status", "scan_entities", "inspect", "nearby_players"):
                tg.start_soon(run, name)
            tg.start_soon(functools.partial(run, "read_chat", peek=True))
        assert peak == 1
        assert "slots" in payload_of(results["inventory"])
        assert payload_of(results["environment"])["biome"] == "minecraft:plains"
        assert payload_of(results["status"])["code"] == "ROBOT001"
        assert "entities" in payload_of(results["scan_entities"])
        assert "block" in payload_of(results["inspect"])
        assert payload_of(results["read_chat"])["messages"][0]["text"] == "hello"


async def test_long_move_does_not_starve_other_tools(fake):
    fake.move_seconds = 1.0
    async with mcp_client(fake) as client:
        await call(client, "status")
        finished: dict[str, float] = {}
        start = time.monotonic()

        async def mover():
            result = await call(client, "move_to", x=6.5, z=0.5)
            assert not result.isError, text_of(result)
            finished["move"] = time.monotonic() - start

        async def talker():
            await anyio.sleep(0.2)
            result = await call(client, "say", message="on my way")
            assert not result.isError
            finished["say"] = time.monotonic() - start

        async with anyio.create_task_group() as tg:
            tg.start_soon(mover)
            tg.start_soon(talker)
        assert finished["say"] < 0.6 < finished["move"]


# ---------------------------------------------------------------------------------- keepalive / reconnect
async def test_keepalive_keeps_idle_session_alive():
    with FakeMineBotServer(idle_timeout=0.5) as fake:
        fake.add_robot("ROBOT001")
        async with mcp_client(fake, keepalive_interval=0.15) as client:
            await call(client, "status")
            connections = fake.connection_count
            before = len(fake.requests_for("status"))
            await anyio.sleep(1.6)
            result = await call(client, "status")
            assert not result.isError
            assert "dropped" not in text_of(result)
            assert fake.connection_count == connections
            assert len(fake.requests_for("status")) - before >= 4


async def test_idle_drop_without_keepalive_reconnects_on_next_call():
    with FakeMineBotServer(idle_timeout=0.3) as fake:
        fake.add_robot("ROBOT001")
        async with mcp_client(fake, keepalive_interval=0.0) as client:
            await call(client, "status")
            await anyio.sleep(0.8)
            assert not fake.robots["ROBOT001"].connected  # dropped by the idle reaper
            result = await call(client, "inventory")
            assert not result.isError, text_of(result)
            assert "NOTE: The websocket session had dropped; reconnected to robot ROBOT001" in text_of(result)
            assert "slots" in payload_of(result)
            assert fake.robots["ROBOT001"].connected


async def test_reconnect_after_dropped_socket(fake):
    async with mcp_client(fake) as client:
        await call(client, "status")
        fake.drop_all()
        await anyio.sleep(0.1)
        result = await call(client, "environment")
        assert not result.isError, text_of(result)
        assert "reconnected to robot ROBOT001" in text_of(result)
        assert payload_of(result)["biome"] == "minecraft:plains"
        # only reported once
        assert "NOTE" not in text_of(await call(client, "environment"))


async def test_keepalive_reconnects_in_background(fake):
    async with mcp_client(fake, keepalive_interval=0.1) as client:
        await call(client, "status")
        count = fake.connection_count
        fake.drop_all()
        await anyio.sleep(0.8)
        assert fake.connection_count > count
        assert fake.robots["ROBOT001"].connected
        result = await call(client, "status")
        assert "reconnected to robot ROBOT001" in text_of(result)


async def test_reconnect_failure_tells_claude_what_to_do(fake):
    async with mcp_client(fake) as client:
        await call(client, "status")
        fake.drop_all()
        await anyio.sleep(0.1)
        fake.robots["ROBOT001"].connected = True  # another program grabbed it meanwhile
        result = await call(client, "status")
        assert result.isError
        text = text_of(result)
        assert text.count("connection_lost") == 1
        assert "program_running" in text and "connect(code='ROBOT001')" in text


async def test_session_closed_on_server_exit(fake):
    async with mcp_client(fake) as client:
        await call(client, "status")
        assert fake.robots["ROBOT001"].connected
    time.sleep(0.2)
    assert not fake.robots["ROBOT001"].connected
    assert any(m.get("type") == "status" for _, m in fake.requests)


# ---------------------------------------------------------------------------------- chat
async def test_wait_for_chat_returns_early(fake):
    async with mcp_client(fake) as client:
        await call(client, "status")
        threading.Timer(0.3, lambda: fake.inject_chat("ROBOT001", "come here", sender="Alex", address="code")).start()
        start = time.monotonic()
        result = await call(client, "wait_for_chat", timeout=20)
        assert time.monotonic() - start < 3.0
        message = payload_of(result)["messages"][0]
        assert message["from"] == "Alex" and message["text"] == "come here" and message["to"] == "code"
        assert message["sender_pos"] == [5.5, 64.0, 5.5]
        assert "timestamp_ms" not in message and "sender_uuid" not in message


async def test_wait_for_chat_times_out_cleanly(fake):
    async with mcp_client(fake) as client:
        start = time.monotonic()
        result = await call(client, "wait_for_chat", timeout=0.4)
        assert not result.isError
        assert 0.3 < time.monotonic() - start < 3.0
        assert "call wait_for_chat again" in text_of(result)


async def test_read_chat_and_say(fake):
    async with mcp_client(fake) as client:
        assert "No unread" in text_of(await call(client, "read_chat"))
        fake.inject_chat("ROBOT001", "hi")
        assert payload_of(await call(client, "read_chat", peek=True))["messages"][0]["text"] == "hi"
        assert payload_of(await call(client, "read_chat"))["messages"][0]["text"] == "hi"
        said = payload_of(await call(client, "say", message="hello Steve", to="steve"))
        assert said["to"] == "Steve"
        assert fake.printed[-1]["message"] == "hello Steve"


# ---------------------------------------------------------------------------------- camera
async def test_snapshot_returns_image_content(fake):
    async with mcp_client(fake) as client:
        result = await call(client, "snapshot")
        assert not result.isError, text_of(result)
        images = [c for c in result.content if c.type == "image"]
        assert len(images) == 1 and images[0].mimeType == "image/png"
        assert base64.b64decode(images[0].data) == TINY_PNG
        assert "1x1 PNG" in text_of(result) and "other entities are boxes" in text_of(result)
        assert fake.requests_for("camera_snapshot")[-1]["source"] == "render"

        result = await call(client, "snapshot", source="client")
        assert not result.isError, text_of(result)
        assert fake.requests_for("camera_snapshot")[-1]["source"] == "client"
        assert "other entities are boxes" not in text_of(result)

        fake.camera_error = ("camera_owner_offline", "The MineBot owner is offline")
        result = await call(client, "snapshot", source="client")
        assert result.isError
        assert "camera_owner_offline: The MineBot owner is offline" in text_of(result)
        assert "without source" in text_of(result)


async def test_inspect_and_look_at(fake):
    async with mcp_client(fake) as client:
        result = payload_of(await call(client, "look_at", x=0.5, y=63.5, z=2.5))
        assert result["crosshair"] == {"block": "minecraft:grass_block", "x": 0, "y": 63, "z": 2, "face": "up", "distance": result["crosshair"]["distance"], "in_reach": True}
        fake.entities.append({"entity_id": 9, "type": "minecraft:sheep", "name": "Sheep", "category": "animal", "x": 0.5, "y": 64.0, "z": 2.5, "health": 8.0, "max_health": 8.0})
        result = payload_of(await call(client, "look_at_entity", entity_id=9))
        assert result["crosshair"]["entity"]["entity_id"] == 9
        assert payload_of(await call(client, "use_on_entity"))["entity"] == "minecraft:sheep"
        assert payload_of(await call(client, "attack_entity", entity_id=9))["hit"] is True
        result = await call(client, "look_at_entity", entity_id=12345)
        assert result.isError and "entity_not_found" in text_of(result)


# ---------------------------------------------------------------------------------- movement
async def test_move_to_and_failures(fake):
    async with mcp_client(fake) as client:
        result = payload_of(await call(client, "move_to", x=4.5, z=-2.5, y=64))
        assert result["arrived"] is True and (result["x"], result["z"]) == (4.5, -2.5)
        assert fake.requests_for("move_to")[-1]["y"] == 64
        fake.robots["ROBOT001"].move_fail = "Path is blocked"
        result = await call(client, "move_to", x=8.5, z=8.5)
        assert result.isError and "movement_failed: Path is blocked" in text_of(result)
        result = await call(client, "move_to", x=999, z=999)
        assert result.isError and "movement_failed" in text_of(result)


async def test_move_to_timeout_stops_robot(fake):
    fake.move_seconds = 5.0
    async with mcp_client(fake) as client:
        result = await call(client, "move_to", x=8.5, z=8.5, timeout=1)
        assert result.isError and "timeout" in text_of(result)
        assert fake.requests_for("stop")


async def test_move_by_move_and_turns(fake):
    async with mcp_client(fake) as client:
        result = payload_of(await call(client, "move_by", forward=2))
        assert result["arrived"] is True and result["z"] == 2.5
        result = payload_of(await call(client, "move", forward=1, right=0, duration=0.2))
        assert result["moved_for_s"] == 0.2
        moves = fake.requests_for("move")
        assert moves[-1]["x"] == 0.0 and moves[-2]["x"] == 1.0
        assert payload_of(await call(client, "turn_to", yaw=90))["facing"] == "west(-X)"
        assert payload_of(await call(client, "turn_by", yaw=90))["facing"] == "north(-Z)"
        assert payload_of(await call(client, "crouch", enabled=True))["crouched"] is True
        assert payload_of(await call(client, "crouch", enabled=False))["crouched"] is False
        assert payload_of(await call(client, "stop")) == {"stopped": True}
        result = await call(client, "turn_to")
        assert result.isError and "invalid_request" in text_of(result)


async def test_go_to_player(fake):
    fake.entities.append({"entity_id": 7, "type": "minecraft:player", "name": "Steve", "category": "player", "x": 6.5, "y": 64.0, "z": 0.5, "health": 20.0, "max_health": 20.0})
    async with mcp_client(fake) as client:
        result = payload_of(await call(client, "go_to_player", name="steve", distance=2))
        assert result["player"] == "Steve" and result["moved"] is True
        assert result["distance_to_player"] == 2.0
        assert fake.requests_for("look_at")[-1]["entity_id"] == 7
        result = await call(client, "go_to_player", name="Herobrine")
        assert result.isError and "player_not_found" in text_of(result) and "Steve" in text_of(result)


# ---------------------------------------------------------------------------------- mining / placing
async def test_mine_block_paths(fake):
    fake.world[(0, 64, 2)] = "minecraft:stone"
    async with mcp_client(fake) as client:
        result = payload_of(await call(client, "mine_block", x=0, y=64, z=2))
        assert result["broken"] is True and (result["x"], result["y"], result["z"]) == (0, 64, 2)
        assert (0, 64, 2) not in fake.world
        # target is air
        result = await call(client, "mine_block", x=0, y=64, z=3)
        assert result.isError and "target_empty" in text_of(result)
        # out of reach (ray reaches it but > 4 blocks)
        fake.world[(0, 64, 5)] = "minecraft:stone"
        result = await call(client, "mine_block", x=0, y=64, z=5)
        assert result.isError and "out_of_reach" in text_of(result)
        # far away: rejected before turning
        result = await call(client, "mine_block", x=0, y=64, z=12)
        assert result.isError and "out_of_reach" in text_of(result)
        # obstructed by a wall
        for pos in ((0, 64, 1), (0, 65, 1), (0, 64, 2), (0, 65, 2)):
            fake.world[pos] = "minecraft:dirt"
        fake.world[(0, 64, 3)] = "minecraft:stone"
        result = await call(client, "mine_block", x=0, y=64, z=3)
        assert result.isError and "obstructed" in text_of(result) and "minecraft:dirt" in text_of(result)
        # mining failure reason is passed through
        fake.robots["ROBOT001"].break_fail = "The selected tool cannot harvest that block"
        result = await call(client, "mine_block", x=0, y=65, z=1)
        assert result.isError and "mining_failed" in text_of(result) and "cannot harvest" in text_of(result)


async def test_place_block_paths(fake):
    async with mcp_client(fake) as client:
        result = await call(client, "place_block", x=0, y=64, z=2, item="cobblestone")
        assert not result.isError, text_of(result)
        placed = payload_of(result)
        assert placed["placed"] == "minecraft:cobblestone" and (placed["x"], placed["y"], placed["z"]) == (0, 64, 2)
        assert fake.world[(0, 64, 2)] == "minecraft:cobblestone"
        assert fake.robots["ROBOT001"].selected_slot == 1
        result = await call(client, "place_block", x=0, y=63, z=3)
        assert result.isError and "target_occupied" in text_of(result)
        result = await call(client, "place_block", x=1, y=66, z=2)
        assert result.isError and "no_support" in text_of(result)
        result = await call(client, "place_block", x=0, y=64, z=0)
        assert result.isError and "occupies" in text_of(result)
        result = await call(client, "place_block", x=1, y=64, z=1, item="minecraft:diamond_block")
        assert result.isError and "missing_item" in text_of(result)
        # stacking on top of the placed block works too (support below is the new block)
        result = await call(client, "place_block", x=0, y=65, z=2)
        assert not result.isError, text_of(result)
        assert fake.world[(0, 65, 2)] == "minecraft:cobblestone"


# ---------------------------------------------------------------------------------- inventory & containers
async def test_inventory_and_items(fake):
    async with mcp_client(fake) as client:
        inv = payload_of(await call(client, "inventory"))
        assert [s["item"] for s in inv["slots"]] == ["minecraft:iron_pickaxe", "minecraft:cobblestone", "minecraft:blaze_powder"]
        assert inv["slots"][0]["durability"] == "238/250"
        assert inv["empty_slots"] == [3, 4, 5, 6, 7, 8, 9]
        assert payload_of(await call(client, "equip", item="cobblestone"))["selected_slot"] == 1
        assert payload_of(await call(client, "select_slot", slot=0))["selected_slot"] == 0
        assert payload_of(await call(client, "refuel", count=2))["fuel_count"] == 5
        assert payload_of(await call(client, "move_item", from_slot=1, to_slot=6))["to"] == 6
        assert payload_of(await call(client, "drop", slot=6, count=4))["dropped_count"] == 4
        assert payload_of(await call(client, "craft", item="oak_planks", count=4))["grid"] == "2x2"


async def test_chest_and_furnace(fake):
    fake.world[(0, 64, 2)] = "minecraft:chest"
    fake.world[(1, 64, 2)] = "minecraft:furnace"
    async with mcp_client(fake) as client:
        await call(client, "look_at", x=0.5, y=64.5, z=2.5)
        assert payload_of(await call(client, "chest_put", item="minecraft:cobblestone", count=5))["count"] == 5
        assert payload_of(await call(client, "chest_inspect"))["items"] == {"minecraft:cobblestone": 5}
        assert payload_of(await call(client, "chest_take", item="cobblestone", count=2))["count"] == 2
        await call(client, "look_at", x=1.5, y=64.5, z=2.5)
        assert payload_of(await call(client, "furnace_inspect"))["output"]["count"] == 5
        taken = payload_of(await call(client, "furnace_take"))
        assert taken["took_food"] == "minecraft:iron_ingot" and taken["took_food_count"] == 5
        sent = fake.requests_for("furnace_take")[-1]
        assert sent["food"] == "minecraft:iron_ingot" and sent["food_count"] == 5
        result = await call(client, "furnace_take")
        assert result.isError and "target_empty" in text_of(result)
        put = payload_of(await call(client, "furnace_put", input_item="cobblestone", input_count=3, fuel_item="minecraft:blaze_powder"))
        assert put["placed_food_count"] == 3
        assert fake.requests_for("furnace_place")[-1]["fuel"] == "minecraft:blaze_powder"


async def test_perception_tools(fake):
    fake.entities.append({"entity_id": 7, "type": "minecraft:player", "name": "Steve", "category": "player", "x": 3.5, "y": 64.0, "z": 0.5, "health": 20.0, "max_health": 20.0})
    fake.entities.append({"entity_id": 8, "type": "minecraft:item", "name": "Oak Log", "category": "item", "x": 1.5, "y": 64.0, "z": 1.5, "item": "minecraft:oak_log", "count": 3})
    async with mcp_client(fake) as client:
        scan = payload_of(await call(client, "scan_blocks", radius=2, limit=3, center_x=0, center_y=63, center_z=0))
        assert scan["truncated"] is True and len(scan["matches[block,x,y,z,distance]"]) == 3
        assert scan["counts"] == {"minecraft:grass_block": 25}
        sent = fake.requests_for("scan_blocks")[-1]
        assert (sent["center_x"], sent["center_y"], sent["center_z"]) == (0, 63, 0)
        result = await call(client, "scan_blocks", center_x=1)
        assert result.isError and "invalid_request" in text_of(result)
        entities = payload_of(await call(client, "scan_entities"))["entities"]
        assert entities[0]["item"] == "minecraft:oak_log" and "uuid" not in entities[0]
        players = payload_of(await call(client, "nearby_players"))["players"]
        assert players == [{"entity_id": 7, "name": "Steve", "x": 3.5, "y": 64.0, "z": 0.5, "distance": 3.0, "health": 20.0, "max_health": 20.0}]
        assert payload_of(await call(client, "environment"))["block_below"] == "minecraft:grass_block"
