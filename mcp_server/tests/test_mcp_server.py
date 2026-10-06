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
    "move_to", "move_by", "move", "turn_to", "turn_by", "look_at", "look_at_entity", "jump", "pillar_up", "bridge", "crouch",
    "center", "stop", "enter_vehicle", "exit_vehicle", "go_to_player",
    "mine", "mine_block", "collect_items", "place", "place_block", "use_item", "use_on_entity", "attack_entity",
    "inventory", "select_slot", "equip", "drop", "move_item", "refuel", "eat", "craft",
    "chest_inspect", "chest_put", "chest_take", "furnace_inspect", "furnace_put", "furnace_take",
    "inspect", "snapshot", "scan_blocks", "scan_entities", "nearby_players", "environment",
    "wait_for_chat", "read_chat", "say", "wait",
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


async def test_robots_that_are_not_loaded_are_listed_and_connectable():
    with FakeMineBotServer() as fake:
        fake.add_robot("BUSY0001", connected=True)
        fake.add_robot("FAR00001", name="Faraway", loaded=False, x=2000.5, z=-3000.5)
        async with mcp_client(fake) as client:
            listing = payload_of(await call(client, "list_robots"))
            assert [(r["code"], r["loaded"]) for r in listing["robots"]] == [("BUSY0001", True), ("FAR00001", False)]
            assert listing["robots"][1]["x"] == 2000.5 and "dead" not in listing
            # the only free robot is far away and not loaded: auto-connect loads it
            result = await call(client, "status")
            assert not result.isError, text_of(result)
            assert payload_of(result)["code"] == "FAR00001" and fake.loads == ["FAR00001"]
            assert fake.robots["FAR00001"].loaded and fake.robots["FAR00001"].connected

            result = await call(client, "connect", code="nope0000")
            assert result.isError and "not_found: No MineBot was found for code NOPE0000" in text_of(result)
            assert "also those whose chunks are not loaded" in text_of(result)
            await call(client, "disconnect")
            await anyio.sleep(0.1)

        # several free robots: the choice says which are not loaded
        fake.unload_robot("FAR00001")
        fake.add_robot("NEAR0001", name="Nearby")
        async with mcp_client(fake) as client:
            result = await call(client, "status")
            text = text_of(result)
            assert result.isError and "choose_robot" in text, text
            assert "FAR00001 (Faraway, owner Steve, x=2000.5 y=64.0 z=-3000.5 in minecraft:overworld, not loaded)" in text
            assert "NEAR0001 (Nearby, owner Steve, x=0.5 y=64.0 z=0.5 in minecraft:overworld)" in text


async def test_robot_that_unloaded_under_its_session_is_loaded_again(fake):
    async with mcp_client(fake) as client:
        await call(client, "status")
        fake.unload_robot("ROBOT001")
        result = await call(client, "inventory")
        assert not result.isError, text_of(result)
        assert "reconnected to robot ROBOT001" in text_of(result)
        assert fake.loads == ["ROBOT001"] and fake.robots["ROBOT001"].connected
        # an explicit connect() to the same robot also recovers from 'gone'
        fake.unload_robot("ROBOT001")
        assert payload_of(await call(client, "connect"))["code"] == "ROBOT001"
        assert fake.loads == ["ROBOT001", "ROBOT001"]


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


async def test_auto_connect_never_guesses_between_free_robots(fake):
    fake.add_robot("ROBOT002", name="Second")
    async with mcp_client(fake) as client:
        for args in ({}, {"timeout": 0.1}):
            result = await call(client, "wait_for_chat" if args else "status", **args)
            text = text_of(result)
            assert result.isError and "choose_robot" in text, text
            assert "ROBOT001 (Rusty" in text and "ROBOT002 (Second" in text and "connect(code=...)" in text
        assert not any(r.connected for r in fake.robots.values())
        # an explicit connect without a code does not guess either
        result = await call(client, "connect")
        assert result.isError and "choose_robot" in text_of(result)
        # once the other robot is taken, the only free one is unambiguous
        fake.robots["ROBOT001"].connected = True
        assert payload_of(await call(client, "status"))["code"] == "ROBOT002"


async def test_disconnect_remembers_the_robot(fake):
    fake.add_robot("ROBOT002", name="Second")
    async with mcp_client(fake) as client:
        assert payload_of(await call(client, "connect", code="ROBOT002"))["code"] == "ROBOT002"
        assert "Disconnected from robot ROBOT002" in text_of(await call(client, "disconnect"))
        await anyio.sleep(0.1)
        assert not fake.robots["ROBOT002"].connected
        # the next tool takes the same robot back, not whichever robot is listed first
        result = await call(client, "status")
        assert payload_of(result)["code"] == "ROBOT002" and "NOTE: Connected to robot ROBOT002" in text_of(result)
        assert not fake.robots["ROBOT001"].connected

        await call(client, "disconnect")
        await anyio.sleep(0.1)
        assert payload_of(await call(client, "connect"))["code"] == "ROBOT002"

        # while another program holds it, the chat does not fall back to a free robot
        await call(client, "disconnect")
        await anyio.sleep(0.1)
        fake.robots["ROBOT002"].connected = True
        result = await call(client, "inventory")
        assert result.isError and "program_running" in text_of(result) and "ROBOT002" in text_of(result)
        assert not fake.robots["ROBOT001"].connected
        result = await call(client, "connect")
        assert result.isError and "program_running" in text_of(result)
        assert not fake.robots["ROBOT001"].connected
        # picking another robot by code works and replaces the remembered one
        assert payload_of(await call(client, "connect", code="ROBOT001"))["code"] == "ROBOT001"
        await call(client, "disconnect")
        await anyio.sleep(0.1)
        fake.robots["ROBOT002"].connected = False
        assert payload_of(await call(client, "status"))["code"] == "ROBOT001"


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

        assert not (await call(client, "connect", code="ROBOT001")).isError
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


async def test_death_during_wait_for_chat(fake):
    fake.add_robot("ROBOT002", name="Second")
    async with mcp_client(fake) as client:
        assert not (await call(client, "connect", code="ROBOT001")).isError
        listing = payload_of(await call(client, "list_robots"))
        assert listing["robots"][0]["health"] == 20.0

        def kill_soon():
            time.sleep(0.2)
            fake.kill_robot("ROBOT001", killer="Skeleton")

        threading.Thread(target=kill_soon, daemon=True).start()
        result = await call(client, "wait_for_chat", timeout=5)
        text = text_of(result)
        assert result.isError and "died: Robot ROBOT001 died: Rusty was slain by Skeleton." in text
        assert "x=0.5 y=64.0 z=0.5 in minecraft:overworld" in text and "connect()" in text

        # the chat does not silently take over another robot
        result = await call(client, "wait_for_chat", timeout=0.1)
        assert result.isError and "not_connected: Robot ROBOT001 died (Rusty was slain by Skeleton)" in text_of(result)
        assert not fake.robots["ROBOT002"].connected
        listing = payload_of(await call(client, "list_robots"))
        assert [r["code"] for r in listing["robots"]] == ["ROBOT002"]
        assert listing["dead"] == [{
            "code": "ROBOT001", "name": "Rusty", "death": "Rusty was slain by Skeleton",
            "dimension": "minecraft:overworld", "x": 0.5, "y": 64.0, "z": 0.5,
        }]

        result = await call(client, "connect", code="ROBOT001")
        assert result.isError and "died: Robot ROBOT001 died" in text_of(result)
        assert payload_of(await call(client, "connect"))["code"] == "ROBOT002"
        assert not (await call(client, "inventory")).isError


async def test_death_reports_unread_chat_even_when_the_push_is_lost(fake):
    async with mcp_client(fake) as client:
        await call(client, "status")
        fake.inject_chat("ROBOT001", "come back", sender="Alex")
        fake.kill_robot("ROBOT001", push=False)
        await anyio.sleep(0.1)
        result = await call(client, "status")
        text = text_of(result)
        assert result.isError and "died: Robot ROBOT001 died: Rusty was slain by Zombie." in text
        assert text.endswith("Chat it received but never read: Alex: come back")
        assert fake.actions()[-1] == "connect"  # the reconnect is what found out


async def test_keepalive_notes_a_death(fake):
    async with mcp_client(fake, keepalive_interval=0.1) as client:
        await call(client, "status")
        fake.kill_robot("ROBOT001")
        await anyio.sleep(0.6)
        result = await call(client, "status")
        text = text_of(result)
        assert result.isError and "Robot ROBOT001 died: Rusty was slain by Zombie" in text


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


async def test_wait_times_out_and_reports_hurts(fake):
    async with mcp_client(fake) as client:
        start = time.monotonic()
        result = await call(client, "wait", seconds=0.3, until_hurt=False)
        assert not result.isError, text_of(result)
        out = payload_of(result)
        assert out["woke"] == "timeout" and 0.25 <= out["waited_s"] < 2.0 and out["health"] == 20.0
        assert 0.25 < time.monotonic() - start < 3.0
        assert "hurt" not in out and "entity" not in out

        # a hurt taken while waiting is listed in the result, not noted
        def hurt_soon():
            time.sleep(0.05)
            fake.hurt_robot("ROBOT001", 1.0, cause="minecraft:on_fire")

        threading.Thread(target=hurt_soon, daemon=True).start()
        result = await call(client, "wait", seconds=0.5, until_hurt=False)
        out = payload_of(result)
        assert out["woke"] == "timeout" and len(out["hurt"]) == 1 and out["hurt"][0].endswith(": on_fire")
        assert "NOTE: The robot was hurt" not in text_of(result)
        assert fake.actions()[-1] == "status" and "scan_entities" not in fake.actions()[-10:]


async def test_wait_wakes_on_hurt_entity_and_health(fake):
    async with mcp_client(fake) as client:
        def hurt_soon():
            time.sleep(0.1)
            fake.hurt_robot("ROBOT001", 5.0, cause="minecraft:fireball", attacker="Blaze", attacker_type="minecraft:blaze", attacker_id=11, projectile="minecraft:small_fireball")

        threading.Thread(target=hurt_soon, daemon=True).start()
        out = payload_of(await call(client, "wait", seconds=5))
        assert out["woke"] == "hurt" and out["waited_s"] < 2.0 and out["health"] == 15.0
        assert out["hurt"] == [out["hurt"][0]] and "fireball (small_fireball) from Blaze" in out["hurt"][0]

        # an entity of the watched type coming within reach wakes it, with its id for attack_entity
        def blaze_soon():
            time.sleep(0.1)
            fake.entities.append({"entity_id": 31, "type": "minecraft:blaze", "name": "Blaze", "category": "hostile", "x": 0.5, "y": 64.0, "z": 2.5, "health": 20.0})

        fake.entities.append({"entity_id": 30, "type": "minecraft:zombie", "name": "Zombie", "category": "hostile", "x": 0.5, "y": 64.0, "z": 1.5})
        threading.Thread(target=blaze_soon, daemon=True).start()
        out = payload_of(await call(client, "wait", seconds=5, until_entity="blaze", within=4))
        assert out["woke"] == "entity" and out["entity"]["entity_id"] == 31 and out["entity"]["type"] == "minecraft:blaze"
        sent = fake.requests_for("scan_entities")[-1]
        assert sent["types"] == ["minecraft:blaze"] and sent["radius"] == 4.0

        # an entity further away than `within` does not count
        out = payload_of(await call(client, "wait", seconds=0.2, until_entity="minecraft:blaze", within=1))
        assert out["woke"] == "timeout" and "entity" not in out

        fake.robots["ROBOT001"].health = 6.0
        out = payload_of(await call(client, "wait", seconds=5, until_hurt=False, until_health_below=8))
        assert out["woke"] == "health" and out["health"] == 6.0


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


async def test_attack_entity_until_dead(fake):
    async with mcp_client(fake) as client:
        fake.entities.append({"entity_id": 11, "type": "minecraft:blaze", "name": "Blaze", "category": "hostile", "x": 0.5, "y": 64.0, "z": 2.5, "health": 20.0})

        def hurt_soon():
            time.sleep(0.05)
            fake.hurt_robot("ROBOT001", 5.0, cause="minecraft:fireball", attacker="Blaze", attacker_type="minecraft:blaze", attacker_id=11, projectile="minecraft:small_fireball")

        threading.Thread(target=hurt_soon, daemon=True).start()
        result = await call(client, "attack_entity", entity_id=11, until_dead=True, follow=True)
        assert not result.isError, text_of(result)
        assert "NOTE: The robot was hurt" not in text_of(result)  # the result lists it under hurt
        fight = payload_of(result)
        assert fight["killed"] is True and fight["ended"] == "killed" and fight["hits"] == 3 and "collect_items" in fight["note"]
        assert len(fight["hurt"]) == 1 and fight["hurt"][0].startswith("lost 5.0 health (15.0 left) ")
        assert fight["hurt"][0].endswith(" s ago: fireball (small_fireball) from Blaze (blaze, entity 11)")
        sent = fake.requests_for("attack_entity")[-1]
        assert sent == {**sent, "until_dead": True, "follow": True, "guard": True, "min_health": 8.0, "max_seconds": 30.0, "entity_id": 11}
        assert fight["guard"] is False  # no shield in the hotbar

        fake.entities.append({"entity_id": 12, "type": "minecraft:blaze", "name": "Blaze", "category": "hostile", "x": 0.5, "y": 64.0, "z": 2.5, "health": 20.0})
        fake.fight_end = ("out_of_reach", "Blaze stayed out of reach (5.2 blocks away) for 2 s")
        fight = payload_of(await call(client, "attack_entity", entity_id=12, until_dead=True))
        assert fight["killed"] is False and fight["ended"] == "out_of_reach" and "note" not in fight

        # follow is on by default, guard can be turned off, and a shield in the hotbar makes the guard
        fake.robots["ROBOT001"].slots[5] = ["minecraft:shield", 1]
        fight = payload_of(await call(client, "attack_entity", entity_id=12, until_dead=True, guard=False))
        sent = fake.requests_for("attack_entity")[-1]
        assert sent["follow"] is True and sent["guard"] is False and fight["guard"] is False
        fake.entities.append({"entity_id": 13, "type": "minecraft:blaze", "name": "Blaze", "category": "hostile", "x": 0.5, "y": 64.0, "z": 2.5, "health": 20.0})
        fight = payload_of(await call(client, "attack_entity", entity_id=13, until_dead=True, follow=False))
        sent = fake.requests_for("attack_entity")[-1]
        assert sent["follow"] is False and sent["guard"] is True and fight["guard"] is True and sent["entity_id"] == 13

        # a target in view but out of reach is fought: the robot walks up to it, or waits for it
        fake.entities.append({"entity_id": 14, "type": "minecraft:blaze", "name": "Blaze", "category": "hostile", "x": 0.5, "y": 64.0, "z": 9.5, "health": 20.0})
        fight = payload_of(await call(client, "attack_entity", entity_id=14, until_dead=True))
        assert fight["killed"] is True and fake.requests_for("attack_entity")[-1]["entity_id"] == 14
        result = await call(client, "attack_entity", entity_id=99, until_dead=True)
        assert result.isError and "entity_not_found" in text_of(result)
        fake.entities.append({"entity_id": 12, "type": "minecraft:blaze", "name": "Blaze", "category": "hostile", "x": 0.5, "y": 64.0, "z": 2.5, "health": 20.0})

        fake.robots["ROBOT001"].health = 7.0
        result = await call(client, "attack_entity", entity_id=12, until_dead=True)
        assert result.isError and "interaction_unavailable: The robot's health is 7.0" in text_of(result)
        # one hit still works as before
        assert payload_of(await call(client, "attack_entity", entity_id=12))["hit"] is True


async def test_hurts_are_noted_and_listed(fake):
    async with mcp_client(fake) as client:
        # hurts from before this chat took the robot are listed by status, not noted
        fake.hurt_robot("ROBOT001", 2.0, cause="minecraft:fall")
        status = payload_of(await call(client, "status"))
        assert status["recent_hurt"] == [status["recent_hurt"][0]] and status["recent_hurt"][0].endswith(": fall")

        fake.hurt_robot("ROBOT001", 1.0, cause="minecraft:player_attack", attacker="Steve", attacker_type="minecraft:player", attacker_id=7)
        result = await call(client, "wait_for_chat", timeout=0.1)
        text = text_of(result)
        assert "NOTE: The robot was hurt: lost 1.0 health (17.0 left)" in text
        assert "player_attack from Steve (player, entity 7)." in text
        assert "No new chat messages" in text
        # noted once only
        assert "NOTE" not in text_of(await call(client, "wait_for_chat", timeout=0.1))

        fake.hurt_robot("ROBOT001", 3.0, cause="minecraft:arrow", attacker="Skeleton", seen=False, projectile="minecraft:arrow")
        result = await call(client, "move_to", x=2.5, z=0.5)
        assert "NOTE: The robot was hurt: lost 3.0 health (14.0 left)" in text_of(result)
        assert "arrow (arrow) from an attacker out of view." in text_of(result)
        status = payload_of(await call(client, "status"))
        assert len(status["recent_hurt"]) == 3 and "NOTE" not in text_of(await call(client, "status"))


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


async def test_move_to_judges_by_final_position(fake):
    robot = fake.robots["ROBOT001"]
    async with mcp_client(fake) as client:
        # The mod reports success, but the robot ends up 8 blocks below the target.
        robot.move_end = (4.5, 56.0, 4.5)
        result = await call(client, "move_to", x=4.5, z=4.5)
        assert result.isError and "movement_failed" in text_of(result)
        assert "8.0 blocks below it" in text_of(result)
        # The mod reports failure, but the robot stopped 0.2 blocks from the target.
        robot.move_fail = "MineBot could not continue moving to that location"
        robot.move_end = (8.3, 64.0, 8.5)
        result = payload_of(await call(client, "move_to", x=8.5, z=8.5, y=64))
        assert result["arrived"] is True and result["x"] == 8.3
        assert "0.2 blocks from the exact point" in result["note"]
        # Afloat, the robot may bob up to a block above the water block it was sent to.
        robot.in_water = True
        robot.move_end = (0.5, 65.0, 0.5)
        assert payload_of(await call(client, "move_to", x=0.5, z=0.5, y=64))["arrived"] is True
        robot.in_water = False
        robot.move_end = (4.5, 65.0, 4.5)
        result = await call(client, "move_to", x=4.5, z=4.5, y=64)
        assert result.isError and "1.0 blocks above it" in text_of(result)


async def test_move_to_reports_a_different_standing_height(fake):
    robot = fake.robots["ROBOT001"]
    robot.walkable_y = 62.0
    async with mcp_client(fake) as client:
        result = payload_of(await call(client, "move_to", x=4.5, z=4.5, y=70))
        assert result["arrived"] is True and result["y"] == 62.0
        assert "nowhere to stand at y=70" in result["note"] and "y=62.0" in result["note"]
        # One block off (the floor block instead of the feet) is what the mod corrects silently.
        robot.walkable_y = 64.0
        result = payload_of(await call(client, "move_to", x=8.5, z=8.5, y=63))
        assert result["arrived"] is True and "note" not in result


async def test_move_to_timeout_at_the_target_is_an_arrival(fake):
    fake.move_seconds = 5.0
    fake.robots["ROBOT001"].x = 8.3
    async with mcp_client(fake) as client:
        result = payload_of(await call(client, "move_to", x=8.5, z=0.5, timeout=1))
        assert result["arrived"] is True and "timeout" in result["note"]
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


async def test_move_and_move_by_report_a_hazard_stop(fake):
    async with mcp_client(fake) as client:
        result = payload_of(await call(client, "move", forward=1, duration=0.2))
        assert "stopped" not in result
        fake.robots["ROBOT001"].hazard = "a drop of more than 3 blocks ahead"
        result = payload_of(await call(client, "move", forward=1, duration=0.2))
        assert result["stopped"] == "Stopped: a drop of more than 3 blocks ahead"
        result = await call(client, "move_by", forward=2)
        assert result.isError and "movement_failed: Stopped: a drop of more than 3 blocks ahead" in text_of(result)
        fake.robots["ROBOT001"].hazard = None
        result = payload_of(await call(client, "move", forward=1, duration=0.2))
        assert "stopped" not in result


async def test_turning_back_for_air(fake):
    fake.move_seconds = 5.0
    async with mcp_client(fake) as client:
        threading.Timer(0.3, fake.start_seeking_air, args=("ROBOT001", (2.5, 63.4, 0.5))).start()
        result = await call(client, "move_to", x=8.5, z=8.5, timeout=10)
        assert result.isError
        assert "movement_failed: Ran short of air and turned back to (2.5, 63.4, 0.5)" in text_of(result)

        status = payload_of(await call(client, "status"))
        assert status["seeking_air"] == [2.5, 63.4, 0.5]
        assert status["air_seconds"] == 4.0
        assert any("short of air" in warning for warning in status["warnings"])

        for tool, args in (("move", {"forward": 1, "duration": 0.1}), ("jump", {}), ("crouch", {"enabled": True}), ("stop", {})):
            result = await call(client, tool, **args)
            assert result.isError and "seeking_air" in text_of(result), tool
            assert "Wait a few seconds" in text_of(result)
        assert payload_of(await call(client, "turn_to", yaw=90))["facing"] == "west(-X)"

        robot = fake.robots["ROBOT001"]
        robot.seeking_air, robot.air = None, 300
        assert payload_of(await call(client, "move_by", forward=1))["arrived"] is True
        assert "warnings" not in payload_of(await call(client, "status"))


async def test_raw_move_cut_short_for_air(fake):
    async with mcp_client(fake) as client:
        threading.Timer(0.1, fake.start_seeking_air, args=("ROBOT001", (0.5, 63.5, 0.5))).start()
        result = payload_of(await call(client, "move", forward=1, duration=0.4))
        assert result["seeking_air"] == [0.5, 63.5, 0.5]
        assert result["stopped"].startswith("Ran short of air and turned back to (0.5, 63.5, 0.5)")


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


async def test_use_item_fires_a_bow_and_reports_interruptions(fake):
    bot = fake.robots["ROBOT001"]
    bot.slots[3] = ["minecraft:bow", 1]
    bot.slots[4] = ["minecraft:arrow", 2]
    bot.selected_slot = 3
    async with mcp_client(fake) as client:
        shot = payload_of(await call(client, "use_item"))
        assert shot["projectiles"] == ["minecraft:arrow"] and shot["spent"] == {"minecraft:arrow": 1}
        weak = payload_of(await call(client, "use_item", hold_seconds=0.25))
        assert weak["held_ticks"] == 5
        assert bot.slots[4] == ["minecraft:air", 0]
        result = await call(client, "use_item")
        assert result.isError and "missing_item" in text_of(result)
        bot.slots[4] = ["minecraft:arrow", 1]
        fake.tick_seconds = 0.2  # 4 s draw: long enough to switch slots mid-draw
        threading.Timer(0.5, lambda: setattr(bot, "selected_slot", 0)).start()
        result = await call(client, "use_item")
        assert result.isError and "use_interrupted" in text_of(result) and "changed" in text_of(result)
        assert bot.slots[4] == ["minecraft:arrow", 1]


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


async def test_pillar_up(fake):
    async with mcp_client(fake) as client:
        result = await call(client, "pillar_up", count=3, item="cobblestone")
        assert not result.isError, text_of(result)
        out = payload_of(result)
        assert out["placed"] == 3 and out["item"] == "minecraft:cobblestone" and out["y"] == 67.0
        assert [fake.world[(0, y, 0)] for y in (64, 65, 66)] == ["minecraft:cobblestone"] * 3
        assert fake.robots["ROBOT001"].slots[1] == ["minecraft:cobblestone", 29]
        # a ceiling stops it, saying how far it got
        fake.world[(0, 70, 0)] = "minecraft:stone"
        result = await call(client, "pillar_up", count=3)
        assert result.isError and "movement_failed" in text_of(result)
        assert "placed 1 of 3 blocks" in text_of(result) and "minecraft:stone at 0, 70, 0" in text_of(result)
        result = await call(client, "pillar_up", item="minecraft:iron_pickaxe")
        assert result.isError and "invalid_item" in text_of(result)
        result = await call(client, "pillar_up", count=0)
        assert result.isError and "invalid_request" in text_of(result)


async def test_place_block_in_own_cell_points_to_pillar_up(fake):
    async with mcp_client(fake) as client:
        result = await call(client, "place_block", x=0, y=64, z=0, item="cobblestone")
        assert result.isError and "pillar_up" in text_of(result)


async def test_bridge(fake):
    for x in (1, 2, 3, 4):
        del fake.world[(x, 63, 0)]
    async with mcp_client(fake) as client:
        result = await call(client, "bridge", direction="east", count=2, item="cobblestone")
        assert not result.isError, text_of(result)
        out = payload_of(result)
        assert out["placed"] == 2 and out["item"] == "minecraft:cobblestone" and out["direction"] == "east"
        assert (out["x"], out["y"], out["z"], out["crouched"]) == (2.5, 64.0, 0.5, True)
        assert fake.requests_for("bridge")[-1]["direction"] == "east"
        assert [fake.world[(x, 63, 0)] for x in (1, 2)] == ["minecraft:cobblestone"] * 2
        # a block above the next cell stops it, saying how far it got
        fake.world[(4, 65, 0)] = "minecraft:stone"
        result = await call(client, "bridge", direction="east", count=3)
        assert result.isError and "movement_failed" in text_of(result)
        assert "placed 1 of 3 blocks" in text_of(result) and "minecraft:stone at 4, 65, 0" in text_of(result)
        result = await call(client, "bridge", direction="up")
        assert result.isError and "invalid_request" in text_of(result)
        result = await call(client, "bridge", direction="east", count=65)
        assert result.isError and "invalid_request" in text_of(result)
        # building into solid ground is refused before anything is placed
        result = await call(client, "bridge", direction="north")
        assert result.isError and "grass_block" in text_of(result)


async def test_place_block_beside_own_block_points_to_bridge(fake):
    # the only support is the west side of the block the robot stands on, out of sight from on top of it
    for cell in ((-1, 63, 0), (-2, 63, 0), (-1, 63, -1), (-1, 63, 1)):
        del fake.world[cell]
    async with mcp_client(fake) as client:
        result = await call(client, "place_block", x=-1, y=63, z=0, item="cobblestone")
        assert result.isError and "obstructed" in text_of(result)
        assert "bridge(direction='west')" in text_of(result)


async def test_flowing_fluids(fake):
    fake.world[(0, 64, 2)] = "minecraft:flowing_lava"
    fake.world[(1, 64, 2)] = "minecraft:lava"
    async with mcp_client(fake) as client:
        scan = payload_of(await call(client, "scan_blocks", radius=24, blocks=["lava"]))
        assert scan["radius"] == 24
        assert [row[:4] for row in scan["matches[block,x,y,z,distance]"]] == [["minecraft:lava", 1, 64, 2]]
        assert fake.requests_for("scan_blocks")[-1]["blocks"] == ["minecraft:lava"]
        assert payload_of(await call(client, "scan_blocks", radius=24))["radius"] == 16
        result = await call(client, "mine_block", x=0, y=64, z=2)
        assert result.isError and "wrong_block" in text_of(result) and "flowing_lava" in text_of(result)
        result = await call(client, "place_block", x=0, y=64, z=2, item="cobblestone")
        assert not result.isError, text_of(result)
        assert fake.world[(0, 64, 2)] == "minecraft:cobblestone"


async def test_fire_filter_finds_soul_fire(fake):
    fake.world[(0, 64, 2)] = "minecraft:soul_fire"
    fake.world[(1, 64, 2)] = "minecraft:fire"
    async with mcp_client(fake) as client:
        scan = payload_of(await call(client, "scan_blocks", blocks=["fire"]))
        assert [row[0] for row in scan["matches[block,x,y,z,distance]"]] == ["minecraft:soul_fire", "minecraft:fire"]
        assert scan["counts"] == {"minecraft:soul_fire": 1, "minecraft:fire": 1}
        scan = payload_of(await call(client, "scan_blocks", blocks=["soul_fire"]))
        assert [row[0] for row in scan["matches[block,x,y,z,distance]"]] == ["minecraft:soul_fire"]


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


async def test_eat_ingots_to_heal(fake):
    bot = fake.robots["ROBOT001"]
    bot.health = 15.0
    bot.slots[5] = ["minecraft:iron_ingot", 4]
    async with mcp_client(fake) as client:
        result = await call(client, "eat", item="gold_ingot")
        assert result.isError and "invalid_item" in text_of(result)
        eaten = payload_of(await call(client, "eat", item="iron_ingot"))
        assert eaten["eaten"] == 3 and eaten["health"] == 20.0
        assert fake.requests_for("eat")[-1]["item"] == "minecraft:iron_ingot"
        assert bot.slots[5] == ["minecraft:iron_ingot", 1]
        result = await call(client, "eat")
        assert result.isError and "target_full: The robot is already at full health" in text_of(result)


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


async def test_chest_tools_open_a_chest_minecart(fake):
    fake.entities.append({"entity_id": 11, "type": "minecraft:chest_minecart", "name": "Minecart with Chest", "category": "other", "x": 0.5, "y": 63.2, "z": 2.5, "health": 0.0, "max_health": 0.0})
    async with mcp_client(fake) as client:
        await call(client, "look_at_entity", entity_id=11)
        assert payload_of(await call(client, "chest_put", item="minecraft:cobblestone", count=3))["kind"] == "minecraft:chest_minecart"
        inspected = payload_of(await call(client, "chest_inspect"))
        assert inspected["kind"] == "minecraft:chest_minecart" and inspected["items"] == {"minecraft:cobblestone": 3}
        assert payload_of(await call(client, "chest_take", item="cobblestone", count=3))["count"] == 3


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
