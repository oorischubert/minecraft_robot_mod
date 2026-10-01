"""MCP server (stdio) that lets Claude drive one MineBot robot.

stdout belongs to the MCP transport: this module never prints; logs go to stderr.
"""

from __future__ import annotations

import atexit
import json
import logging
import os
import signal
import sys
import threading
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator, Callable, Literal, Optional

import anyio
from mcp.server.fastmcp import FastMCP, Image
from mcp.server.fastmcp.exceptions import ToolError
from mcp.types import ToolAnnotations

from minebot import MineBotCommandError, MineBotConnectionError

from .actions import Actions, norm_id
from .session import ActionError, Cancelled, RobotSession, Settings, unreachable_message

log = logging.getLogger("minebot_mcp")

INSTRUCTIONS = """\
You drive ONE MineBot robot in a running Minecraft world; players give you orders in game chat.

Operating loop:
1. Any robot tool auto-connects on first use (list_robots / connect to choose). Start with status.
2. Call wait_for_chat in a loop. It blocks until a player writes '@<robot code> ...', '@bot ...' (nearest
   robot) or '@all ...' in chat, or until the timeout; an empty result is normal - just call it again.
   You only act while this loop is running.
3. Acknowledge each order and report results with say(message, to=<sender>) (short lines), do the work,
   then go back to wait_for_chat.

World conventions: Minecraft F3 coordinates. X east(+)/west(-), Y height, Z south(+)/north(-). Block
(x, y, z) spans x..x+1 etc.; its centre is (x+0.5, y+0.5, z+0.5); the robot's y is its feet.
Yaw 0 = south(+Z), 90 = west(-X), 180 = north(-Z), -90 = east(+X). Pitch -90 = straight up, 90 = straight down.

Aiming: mining, placing and using act on the crosshair target within 4 blocks. Prefer mine_block(x, y, z)
and place_block(x, y, z), which aim and verify for you. Otherwise look_at(...) and check the returned
crosshair (or inspect) for the right block, face and in_reach before mine/place/use.

Body: 10 hotbar slots (0-9), items are full ids like minecraft:oak_log. Movement and most actions burn
blaze powder (1 powder = 200 blocks of range). Watch status fuel/range and health (no regeneration);
on out_of_energy put blaze powder in the hotbar and call refuel.
The robot floats in water. move_to swims across it, straight up waterfalls and flooded shafts, and out
onto a bank up to one block above the water; the robot only goes under when crouched.

Senses: the robot only knows what it can see. scan_blocks and scan_entities report what is in its line
of sight (glass and water are see-through); they never show what is behind walls, underground or inside
closed containers. snapshot draws the same view as a picture. To find something that is not in view,
explore the way a player would: walk, look around, dig, open chests. Never claim to know where something
is unless a tool result showed it.

Fair play: act only through these robot tools. Do not use any other tool, file, program or server command
to learn about the world or to change it.

turn_evil turns the robot hostile for good. Use it only when a player explicitly orders it.

Death: health never regenerates, and a robot that dies is gone for good. Every player sees its death message,
and the next tool call fails with 'died: ...' giving the cause, the place and any chat it never read. This
chat then has no robot until you connect() to another one.

Errors come back as '<code>: <message>' - read the message and adapt instead of retrying blindly.
"""

HINTS = {
    "out_of_energy": "The robot has no blaze powder energy. Get blaze powder into its hotbar (chest_take, or ask a player to drop some near it) and call refuel.",
    "not_looking_at_block": "Aim first: look_at the block centre (x+0.5, y+0.5, z+0.5) and check the crosshair, or use mine_block/place_block.",
    "wrong_block": "Check what the crosshair is on with inspect; aim at the right block with look_at.",
    "not_looking_at_entity": "Aim at it with look_at_entity(entity_id) (ids from scan_entities) and make sure it is within 4 blocks.",
    "entity_not_found": "That entity is gone or not in view; call scan_entities for what the robot can see now.",
    "player_not_found": "Use the exact player name (from chat messages or nearby_players).",
    "camera_owner_offline": "source='client' needs the robot owner's Minecraft client, which is offline. Call snapshot() without source for the server-drawn picture.",
    "camera_owner_unavailable": "The owner's Minecraft client cannot render right now (paused, minimized or robot not loaded there). Call snapshot() without source for the server-drawn picture.",
    "camera_owner_required": "This robot has no recorded owner, so source='client' cannot work. Call snapshot() without source for the server-drawn picture.",
    "camera_assets_unavailable": "The server could not load Minecraft's block textures, so it cannot draw pictures (the server owner can fix this in config/minebot.properties). Use inspect / scan_blocks / scan_entities instead.",
    "camera_error": "The camera could not produce a frame. Use inspect / scan_blocks / scan_entities instead.",
    "missing_item": "Check inventory for what the robot actually holds.",
    "missing_ingredients": "Check inventory; all ingredients must be in the robot hotbar.",
    "no_inventory_space": "The 10-slot hotbar is full: drop or chest_put something first.",
    "movement_failed": "Try an intermediate point, a different y, or clear the way (mine_block).",
    "gone": "The robot is not loaded (its chunk unloaded). A player must be near it; then call connect.",
    "died": "The robot died and cannot be controlled any more. Pick another robot with list_robots / connect.",
    "broke_free": "This robot turned evil and cannot be controlled any more. Pick another robot with list_robots / connect.",
    "program_running": "Another program controls this robot. Stop it or pick another robot with list_robots / connect(code=...).",
}


def format_error(exc: BaseException, url: str = "the configured URL") -> str:
    if isinstance(exc, ActionError):
        return str(exc)
    if isinstance(exc, MineBotCommandError):
        text = f"{exc.raw_code}: {exc.detail}"
        hint = HINTS.get(exc.raw_code)
        return f"{text} ({hint})" if hint else text
    if isinstance(exc, MineBotConnectionError):
        return f"connection_failed: {exc}. {unreachable_message(url)}"
    if isinstance(exc, Cancelled):
        return "cancelled: the call was cancelled or the server is shutting down; the robot was told to stop."
    log.error("Unexpected tool failure", exc_info=exc)
    return f"internal_error: {type(exc).__name__}: {exc}"


def render(value: Any) -> str:
    if isinstance(value, str):
        return value
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False, default=str)


def with_notes(value: Any, notes: list[str]) -> Any:
    if not notes:
        return value if isinstance(value, list) else render(value)
    prefix = "\n".join(f"NOTE: {note}" for note in notes)
    if isinstance(value, list):
        return [prefix, *value]
    return f"{prefix}\n{render(value)}"


def create_server(settings: Optional[Settings] = None, session: Optional[RobotSession] = None) -> FastMCP:
    settings = settings or Settings.from_env()
    session = session or RobotSession(settings)
    actions = Actions(session)

    @asynccontextmanager
    async def lifespan(_app: FastMCP) -> AsyncIterator[dict[str, Any]]:
        async def keepalive_loop() -> None:
            interval = settings.keepalive_interval
            if interval <= 0:
                return
            tick = min(max(interval / 4.0, 0.05), 5.0)
            while True:
                await anyio.sleep(tick)
                if session.keepalive_due():
                    await anyio.to_thread.run_sync(session.keepalive)

        try:
            async with anyio.create_task_group() as tg:
                tg.start_soon(keepalive_loop)
                yield {"session": session}
                tg.cancel_scope.cancel()
        finally:
            session.close()

    mcp = FastMCP("minebot", instructions=INSTRUCTIONS, lifespan=lifespan)
    mcp.session = session  # type: ignore[attr-defined]  # handy for tests

    def tool(fn: Callable[..., Any]) -> Callable[..., Any]:
        return mcp.tool(structured_output=False)(fn)

    def destructive_tool(fn: Callable[..., Any]) -> Callable[..., Any]:
        return mcp.tool(structured_output=False, annotations=ToolAnnotations(destructiveHint=True))(fn)

    async def run(body: Callable[[threading.Event], Any]) -> Any:
        cancel = threading.Event()

        def work() -> tuple[Any, Optional[BaseException], list[str]]:
            session.begin_call()
            try:
                return body(cancel), None, session.end_call()
            except Exception as exc:  # converted below; never a bare traceback
                return None, exc, session.end_call()

        try:
            result, error, notes = await anyio.to_thread.run_sync(work, abandon_on_cancel=True)
        except anyio.get_cancelled_exc_class():
            cancel.set()
            raise
        if error is not None:
            message = format_error(error, session.url)
            if notes:
                message = "\n".join(f"NOTE: {n}" for n in notes) + "\n" + message
            raise ToolError(message)
        return with_notes(result, notes)

    # ------------------------------------------------------------------ session
    @tool
    async def list_robots() -> str:
        """List every loaded MineBot: code, name, connected (another program holds it), evil, health, owner,
        position. Dead robots are not listed; connect(code=...) to a dead robot's code says how it died.

        Does not change which robot this chat controls. 'this_session' marks ours.
        """
        return await run(lambda c: actions.list_robots())

    @tool
    async def connect(code: Optional[str] = None, url: Optional[str] = None) -> str:
        """Connect this chat to a robot (closes any previous robot session).

        code: 8-character robot code from the robot GUI or list_robots. Omitted: the robot this chat
        already had, else MINEBOT_CODE if set, else the first robot that is free and not evil.
        url: websocket 'Connection Socket' from the robot GUI (default from MINEBOT_URL).
        Usually unnecessary: every robot tool auto-connects. Returns the robot status.
        """
        return await run(lambda c: actions.connect(code, url))

    @tool
    async def disconnect() -> str:
        """Release the robot (it stops and becomes free for other programs). The next robot tool auto-connects again."""
        return await run(lambda c: actions.disconnect())

    @tool
    async def status() -> str:
        """Robot state: code, position (x, y=feet, z), yaw/pitch/facing, health/max_health (20 = 10 hearts,
        no regeneration), fuel_blaze_powder and range_blocks (remaining movement), selected slot/item,
        look_block (crosshair block), owner, plus any ongoing move/mining, last failure and warnings."""
        return await run(lambda c: actions.status())

    @destructive_tool
    async def turn_evil() -> str:
        """Turn the robot this chat controls evil. It cannot be undone: the robot breaks free of this chat,
        grabs an axe, hunts the nearest player and keeps attacking until it is killed. It never takes
        commands or chat again, from you or any other program. Use it only when a player explicitly
        orders it; say() anything you want players to read first. Acts only on the robot already
        connected (never auto-connects). Afterwards this chat has no robot and the robot tools report
        not_connected until you connect() to another one."""
        return await run(lambda c: actions.turn_evil())

    # ------------------------------------------------------------------ movement
    @tool
    async def move_to(x: float, z: float, y: Optional[float] = None, speed: float = 1.0, timeout: float = 60.0) -> str:
        """Pathfind to world coordinates and wait until arrival.

        x, z: target (use block centres like 12.5, -3.5). y: optional target height (world Y of the
        floor you want to stand on, i.e. feet level); without it a walkable spot near the current
        height is used. speed: 0..1 fraction of normal speed. timeout: seconds to wait (max 600); on
        timeout the robot is stopped and its position reported. The result is judged by where the robot
        ends up: arrived means within 0.75 blocks of the target horizontally and of its height (1.25 when
        floating). Returns arrived + final x, y, z (plus a note when it stopped short of the exact point
        or had to stand at another height than the y you gave), or a movement_failed error with the
        reason, where the robot is and how far from the target, horizontally and above/below. Uses fuel.
        In water it swims (about 2 blocks/s), swims straight up waterfalls and flooded shafts, and climbs
        out onto a bank up to one block above the water. It never dives; a bank two or more blocks above
        the water cannot be climbed from it.
        """
        return await run(lambda c: actions.move_to(c, x, z, y, speed, timeout))

    @tool
    async def move_by(forward: float, right: float = 0.0, speed: float = 1.0, timeout: float = 30.0) -> str:
        """Walk a relative number of blocks and wait. First snaps to the current block centre and the
        nearest cardinal direction (N/E/S/W); forward/right are blocks in that frame (negative = back/left).
        Returns arrived + final position, judged like move_to by where the robot ends up."""
        return await run(lambda c: actions.move_by(c, forward, right, speed, timeout))

    @tool
    async def move(forward: float, right: float = 0.0, duration: float = 1.0) -> str:
        """Raw timed movement input (like holding W/A/S/D): forward and right are -1..1, duration seconds
        (required, max 10), then input is released. No pathfinding; may walk off ledges unless crouched.
        In water, pushing into a bank at most one block above the water climbs out onto it.
        Returns the final position."""
        return await run(lambda c: actions.move(c, forward, right, duration))

    @tool
    async def turn_to(yaw: Optional[float] = None, pitch: Optional[float] = None) -> str:
        """Set absolute look direction. yaw: 0 = south(+Z), 90 = west(-X), 180 = north(-Z), -90 = east(+X).
        pitch: -90 = straight up, 0 = level, 90 = straight down. Omitted axis stays. Returns the new
        orientation and what the crosshair now hits."""
        return await run(lambda c: actions.turn_to(yaw, pitch))

    @tool
    async def turn_by(yaw: float = 0.0, pitch: float = 0.0) -> str:
        """Turn relative to the current direction: yaw degrees (positive = turn right, e.g. 90 turns from
        south to west), pitch degrees (positive = look further down). Returns orientation and crosshair."""
        return await run(lambda c: actions.turn_by(yaw, pitch))

    @tool
    async def look_at(x: float, y: float, z: float) -> str:
        """Aim the crosshair at a world point. For block (bx, by, bz) aim at its centre
        (bx+0.5, by+0.5, bz+0.5); for a face aim at the face centre. Returns orientation and the
        crosshair hit (block, x/y/z, face, distance, in_reach; reach is 4 blocks) so you can verify."""
        return await run(lambda c: actions.look_at(x, y, z))

    @tool
    async def look_at_entity(entity_id: int) -> str:
        """Aim at an entity the robot can see (entity_id from scan_entities / nearby_players); hidden
        entities give entity_not_found. Returns orientation and crosshair."""
        return await run(lambda c: actions.look_at_entity(entity_id))

    @tool
    async def jump() -> str:
        """Jump once (clears crouch). In water: swim up and return to the surface."""
        return await run(lambda c: actions.simple("jump"))

    @tool
    async def crouch(enabled: bool) -> str:
        """Crouch (enabled=true) or stand up (false). While crouched raw move will not walk off ledges.
        In water the robot floats by itself; crouch(true) makes it dive, sinking about 4 blocks/s, and
        crouch(false) or jump brings it back up at about 3 blocks/s. Under water it has 15 s of air
        (status shows air_seconds), then it takes damage."""
        return await run(lambda c: actions.crouch(enabled))

    @tool
    async def center() -> str:
        """Snap to the exact centre of the current block, face the nearest cardinal direction, pitch 0."""
        return await run(lambda c: actions.simple("center"))

    @tool
    async def stop() -> str:
        """Immediately stop all movement and mining."""
        return await run(lambda c: actions.simple("stop"))

    @tool
    async def enter_vehicle() -> str:
        """Enter the rideable vehicle (boat, minecart, ...) directly in front of the robot. Experimental."""
        return await run(lambda c: actions.simple("enter_vehicle"))

    @tool
    async def exit_vehicle() -> str:
        """Leave the vehicle the robot is riding. Experimental."""
        return await run(lambda c: actions.simple("exit_vehicle"))

    @tool
    async def go_to_player(name: str, distance: float = 2.0, timeout: float = 60.0) -> str:
        """Walk to an online player within 64 blocks and face them. Stops `distance` blocks short of the
        player (exact name, case-insensitive). If they are farther away, move_to the sender_pos from their
        chat message instead."""
        return await run(lambda c: actions.go_to_player(c, name, distance, timeout))

    # ------------------------------------------------------------------ mining / placing / using
    @tool
    async def mine() -> str:
        """Mine the block in the crosshair (within 4 blocks) with the selected item and wait until it
        breaks. Select a suitable tool first (equip). Returns broken + block + position, or
        mining_failed with the reason (wrong tool, out of reach, interrupted, ...)."""
        return await run(lambda c: actions.mine(c))

    @tool
    async def mine_block(x: int, y: int, z: int) -> str:
        """Mine the block at integer block coordinates (x, y, z): aims at its centre, verifies the crosshair
        is on exactly that block and within reach (4 blocks from the eyes), then mines and waits.
        Errors: out_of_reach (move closer), obstructed (another block is in the way; mine it first),
        target_empty (it is air). Equip the right tool first."""
        return await run(lambda c: actions.mine_block(c, x, y, z))

    @tool
    async def collect_items(radius: float = 6.0, item: Optional[str] = None) -> str:
        """Walk over dropped items within `radius` blocks (max 16) so they are picked up into the hotbar.
        Mined blocks and killed mobs scatter their drops up to ~2 blocks; the robot only picks up what is
        within about a block of it. item: only collect this item id. Returns what was collected, how many
        drops are left, and drops it could not walk to. Uses fuel."""
        return await run(lambda c: actions.collect_items(c, radius, item))

    @tool
    async def place() -> str:
        """Right-click the crosshair block with the selected item: places a block against the looked-at
        face, uses tools/items on it (flint and steel, bone meal, hoe, bucket on a block, ...), or
        activates doors, trapdoors, levers and buttons. Aim first with look_at."""
        return await run(lambda c: actions.simple("place"))

    @tool
    async def place_block(x: int, y: int, z: int, item: Optional[str] = None) -> str:
        """Place a block at integer block position (x, y, z), which must be air or replaceable (grass,
        water, ...). Finds an adjacent solid block, aims at the face touching the target, verifies it is
        in reach, selects `item` (e.g. 'minecraft:cobblestone') if given, else uses the selected slot,
        then places. Errors: target_occupied, no_support (nothing adjacent to build against),
        out_of_reach, obstructed, missing_item."""
        return await run(lambda c: actions.place_block(x, y, z, item))

    @tool
    async def use_item() -> str:
        """Right-click with the selected item like a player: buckets on fluids, throwables, boats, spawn
        eggs, ... If that does nothing and a block is in the crosshair, the item is used on that block as
        `place` would (flint and steel, bone meal, hoe; the result then has `on_block`). Hold-to-use items
        (bow, food, shield) are only clicked. Returns used item, accepted (false = nothing happened) and
        the new selected item."""
        return await run(lambda c: actions.simple("use_item"))

    @tool
    async def use_on_entity(entity_id: Optional[int] = None) -> str:
        """Right-click an entity with the selected item (or empty hand): shears on sheep, feeding/breeding,
        milking with a bucket, leads, saddles. If entity_id is given the robot aims at it first; it must
        be within 4 blocks. Otherwise acts on the entity in the crosshair."""
        return await run(lambda c: actions.entity_action("use_on_entity", entity_id))

    @tool
    async def attack_entity(entity_id: Optional[int] = None) -> str:
        """Melee-attack a living entity with the selected item (swords/axes do more damage). If entity_id is
        given the robot aims at it first; it must be within 4 blocks. Attacking players needs PvP enabled.
        Returns damage, hit, killed and remaining health. Mobs may fight back."""
        return await run(lambda c: actions.entity_action("attack_entity", entity_id))

    # ------------------------------------------------------------------ inventory / crafting / containers
    @tool
    async def inventory() -> str:
        """Hotbar contents: non-empty slots (slot 0-9, item id, count, durability for tools), empty slot
        numbers, selected slot, fuel item/count and remaining movement range in blocks."""
        return await run(lambda c: actions.inventory())

    @tool
    async def select_slot(slot: int) -> str:
        """Select hotbar slot 0-9 (the held item used by mine/place/use/attack)."""
        return await run(lambda c: actions.select_slot(slot))

    @tool
    async def equip(item: str) -> str:
        """Select the hotbar slot holding `item` (e.g. 'minecraft:iron_pickaxe'; 'iron_pickaxe' also works)."""
        return await run(lambda c: actions.equip(item))

    @tool
    async def drop(slot: Optional[int] = None, count: Optional[int] = None) -> str:
        """Drop items on the ground from `slot` (default: selected slot); count default = whole stack.
        Players can pick them up; the robot will not re-collect them for a moment."""
        return await run(lambda c: actions.drop(slot, count))

    @tool
    async def move_item(from_slot: int, to_slot: int, count: Optional[int] = None) -> str:
        """Move/merge items between hotbar slots 0-9; swaps if the target holds a different item and the
        whole stack is moved."""
        return await run(lambda c: actions.simple("move_item", **{"from": int(from_slot), "to": int(to_slot), **({"count": int(count)} if count is not None else {})}))

    @tool
    async def refuel(count: Optional[int] = None) -> str:
        """Move blaze powder from the hotbar into the fuel slot (as much as fits unless count is given).
        Works even when out of energy. Returns new fuel_count and stored_range_blocks."""
        return await run(lambda c: actions.simple("refuel", **({"count": int(count)} if count is not None else {})))

    @tool
    async def craft(item: str, count: Optional[int] = None) -> str:
        """Craft `item` from hotbar ingredients. 2x2 recipes (planks, sticks, crafting table, torches, ...)
        work anywhere; 3x3 recipes need the robot looking at a crafting table (else wrong_block).
        count must be a multiple of the recipe output (e.g. planks come in 4s); default one batch."""
        payload: dict[str, Any] = {"item": norm_id(item)}
        if count is not None:
            payload["count"] = int(count)
        return await run(lambda c: actions.simple("craft", **payload))

    @tool
    async def chest_inspect() -> str:
        """List the contents of the looked-at chest, barrel, shulker box, hopper, dropper or dispenser
        (aim with look_at first; within 4 blocks)."""
        return await run(lambda c: actions.simple("chest_inspect"))

    @tool
    async def chest_put(item: str, count: int = 1) -> str:
        """Move `count` of `item` from the robot hotbar into the looked-at chest-like block."""
        return await run(lambda c: actions.simple("chest_place", item=norm_id(item), count=int(count)))

    @tool
    async def chest_take(item: str, count: int = 1) -> str:
        """Move `count` of `item` from the looked-at chest-like block into the robot hotbar."""
        return await run(lambda c: actions.simple("chest_take", item=norm_id(item), count=int(count)))

    @tool
    async def furnace_inspect() -> str:
        """Show input, fuel and output slots of the looked-at furnace, blast furnace or smoker."""
        return await run(lambda c: actions.simple("furnace_inspect"))

    @tool
    async def furnace_put(input_item: Optional[str] = None, input_count: int = 1, fuel_item: Optional[str] = None, fuel_count: int = 1) -> str:
        """Put items from the hotbar into the looked-at furnace: input_item (to smelt/cook) and/or
        fuel_item (coal, charcoal, planks, ...)."""
        return await run(lambda c: actions.furnace_put(input_item, input_count, fuel_item, fuel_count))

    @tool
    async def furnace_take(item: Optional[str] = None, count: Optional[int] = None, fuel_item: Optional[str] = None, fuel_count: int = 1) -> str:
        """Take items out of the looked-at furnace into the hotbar. With no arguments takes the whole
        output stack. item: take this item (output slot preferred, else input slot), count default = all
        of it. fuel_item/fuel_count: take fuel back."""
        return await run(lambda c: actions.furnace_take(item, count, fuel_item, fuel_count))

    # ------------------------------------------------------------------ camera
    @tool
    async def inspect() -> str:
        """What the crosshair is on (up to 50 blocks): block id, block x/y/z, face hit, distance and in_reach
        (within the 4-block interaction reach), plus an entity (type, entity_id, name, distance,
        in_reach) if one is in front of the block. Cheap; use it to verify aim."""
        return await run(lambda c: actions.inspect())

    @tool
    async def snapshot(source: Literal["render", "client"] = "render") -> list:
        """Take a 640x360 picture from the robot's eyes. The default, source="render", is drawn by the server
        from what the robot can see, with Minecraft's block textures, light and fog, and works with nobody
        online. Players, robots, common mobs, dropped items and falling blocks look as in the game but stand
        still (no walking, armour or held items); other entities are plain boxes: red hostile, green animal,
        brown vehicle, white other. Signs show their text, readable within a few blocks. The + in the centre
        is the crosshair.
        source="client" instead captures the robot owner's real game screen (needs the owner online)."""

        def body(_c: threading.Event) -> list:
            data, caption = actions.snapshot(source)
            return [Image(data=data, format="png"), caption]

        return await run(body)

    # ------------------------------------------------------------------ perception
    @tool
    async def scan_blocks(
        radius: int = 8,
        blocks: Optional[list[str]] = None,
        limit: int = 32,
        center_x: Optional[int] = None,
        center_y: Optional[int] = None,
        center_z: Optional[int] = None,
    ) -> str:
        """Look around: list the blocks the robot can SEE in the cube of `radius` (1-16) around its feet, or
        around block center_x/y/z (all three, within 32 blocks). Only blocks in the robot's line of sight
        are reported (glass and water are see-through); blocks behind walls or buried underground never
        appear, so an empty result means "not in view from here", not "does not exist". Move or dig and
        scan again to see more. blocks: optional ids or '#minecraft:logs'-style tags. Returns counts per
        block id and the nearest `limit` matches as [block, x, y, z, distance] rows (distance from the eyes)."""
        centre_values = (center_x, center_y, center_z)
        if any(v is not None for v in centre_values) and not all(v is not None for v in centre_values):
            raise ToolError("invalid_request: give all of center_x, center_y and center_z, or none")
        centre = (int(center_x), int(center_y), int(center_z)) if center_x is not None else None  # type: ignore[arg-type]
        return await run(lambda c: actions.scan_blocks(radius, blocks, limit, centre))

    @tool
    async def scan_entities(radius: float = 16.0, types: Optional[list[str]] = None, players_only: bool = False, limit: int = 16) -> str:
        """List the entities the robot can SEE within `radius` blocks (1-64), nearest first: entity_id (for
        look_at_entity / attack_entity / use_on_entity), type, name, category (player, minebot, hostile,
        animal, item, vehicle, other), position, distance, health, and item/count for dropped items.
        Mobs and items hidden behind blocks are not listed. Players are the exception: they are noticed
        through walls unless they sneak, and are then marked behind_cover."""
        return await run(lambda c: actions.scan_entities(radius, types, players_only, limit))

    @tool
    async def nearby_players(radius: float = 64.0) -> str:
        """Players within `radius` blocks: name, entity_id, position, distance, health. Players are noticed
        through walls unless they sneak; behind_cover marks those not in direct view."""
        return await run(lambda c: actions.nearby_players(radius))

    @tool
    async def environment() -> str:
        """Dimension, biome, time_of_day (0-23999; 0 = sunrise, 6000 noon, 13000 night), day, weather,
        light level, the blocks below/at feet/at head, on_ground, in_water, in_lava."""
        return await run(lambda c: actions.simple("environment"))

    # ------------------------------------------------------------------ chat
    @tool
    async def wait_for_chat(timeout: float = 60.0) -> str:
        """Block until players send chat addressed to this robot ('@<code> ...', '@<robot name> ...',
        '@bot ...' for the nearest robot, '@all ...'), then return those messages (oldest first, removed
        from the inbox): id, from (player name), text (without the @ prefix), to (how it was addressed),
        age_s, sender_pos [x, y, z] and distance. timeout: seconds, max 300. Returns early as soon as a
        message arrives; on timeout returns a 'no new messages' note - just call it again. If the robot
        dies meanwhile it fails with 'died: ...' (cause, place and any chat it never read)."""
        return await run(lambda c: actions.wait_for_chat(c, timeout))

    @tool
    async def read_chat(peek: bool = False) -> str:
        """Return unread messages addressed to this robot without waiting. peek=true leaves them in the inbox."""
        return await run(lambda c: actions.read_chat(peek))

    @tool
    async def say(message: str, to: Optional[str] = None) -> str:
        """Send a chat line as the robot. to: a player name to whisper only to them (player_not_found if
        they are offline); omitted = everyone."""
        return await run(lambda c: actions.say(message, to))

    return mcp


def main() -> None:
    logging.basicConfig(
        stream=sys.stderr,
        level=os.environ.get("MINEBOT_LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    settings = Settings.from_env()
    session = RobotSession(settings)
    server = create_server(settings, session)
    atexit.register(session.close)

    def on_signal(signum: int, _frame: Any) -> None:
        log.info("Signal %s received; closing the robot session", signum)
        session.close()
        os._exit(0)

    for sig in (signal.SIGTERM, getattr(signal, "SIGHUP", None)):
        if sig is not None:
            signal.signal(sig, on_signal)

    log.info("MineBot MCP server starting (url=%s, code=%s)", settings.url, settings.code or "auto")
    try:
        server.run("stdio")
    except KeyboardInterrupt:
        pass
    finally:
        session.close()


if __name__ == "__main__":
    main()
