from __future__ import annotations

import base64
import json
import math
import select
import time
import uuid
from dataclasses import dataclass
from typing import Any, Iterator, NoReturn, Optional
from urllib.parse import urlparse, urlunparse

import websocket
from .exceptions import (
    MINEBOT_CODE_TO_EXCEPTION,
    MineBotBrokeFreeError,
    MineBotCameraUnavailableError,
    MineBotCommandError,
    MineBotConnectionError,
    MineBotDiedError,
    MineBotError,
    MineBotErrorCode,
    MineBotInvalidRequestError,
    MineBotTimeoutError,
)

# How far the robot's feet may end above or below the target height for a move to count as arrived.
MOVE_VERTICAL_TOLERANCE = 0.75
# A floating robot bobs up to a block above the water block it was sent to.
MOVE_AFLOAT_VERTICAL_TOLERANCE = 1.25


@dataclass(frozen=True)
class MineBotSlot:
    """Helper for one robot hotbar slot. See PYTHON_SDK.md hotbar section."""

    robot: "MineBot"
    slot: int

    def inspect(self) -> dict[str, Any]:
        """Return {'block': <minecraft:id>, 'count': <int>} for this hotbar slot."""
        return self.robot.inspect_slot(self.slot)

    def type(self) -> str:
        """Compatibility alias for inspect(). Returns only the Minecraft item id."""
        return str(self.inspect()["block"])

    def drop(self, count: Optional[int] = None) -> bool:
        """Drop items from this slot. See README.md inventory notes for drop behavior."""
        payload: dict[str, Any] = {"slot": int(self.slot)}
        if count is not None:
            payload["count"] = int(count)
        self.robot._command("drop", **payload)
        return True


@dataclass(frozen=True)
class MineBotCamera:
    """Camera helper namespace. See PYTHON_SDK.md camera section."""

    robot: "MineBot"

    def snapshot(self, source: str = "render") -> bytes:
        """Capture one 640x360 PNG frame from the robot's eyes and return the PNG bytes.

        source="render" (default): the server draws the view from what the robot can see, with
        Minecraft's block textures, lighting and fog. Works with nobody online. Players, robots, common mobs
        and dropped items look as in the game but stand still; other entities are plain boxes coloured by
        kind. source="client": the robot owner's game client renders a real
        frame; raises a MineBotCameraOwner*Error when the owner is missing, offline or cannot render.
        """
        payload = self.robot._command("camera_snapshot", source=source)
        encoded = str(payload.get("data_base64", ""))
        if not encoded:
            raise MineBotCameraUnavailableError(
                "MineBot camera snapshot returned no image data",
                code=MineBotErrorCode.CAMERA_ERROR,
            )
        return base64.b64decode(encoded)

    def stream(self, interval: float = 0.25, frame_limit: Optional[int] = None, source: str = "render") -> Iterator[bytes]:
        """Yield repeated PNG snapshots (see snapshot() for source) at a fixed polling interval."""
        frame = 0
        while frame_limit is None or frame < frame_limit:
            yield self.snapshot(source=source)
            frame += 1
            if frame_limit is None or frame < frame_limit:
                time.sleep(interval)

    def inspect(self) -> dict[str, Any]:
        """Return the camera crosshair target: always 'block' (id) and 'distance' (blocks).

        When a block is hit the mod also reports 'x', 'y', 'z' (block position), 'face'
        ('up', 'down', 'north', 'south', 'east', 'west') and 'in_reach' (bool). When an entity
        is in the crosshair closer than the block it adds 'entity', 'entity_id', 'entity_uuid',
        'entity_name', 'entity_distance' and 'entity_in_reach'. Absent keys are simply missing.
        """
        payload = self.robot._command("camera_inspect")
        result: dict[str, Any] = dict(payload) if isinstance(payload, dict) else {}
        result["block"] = str(result.get("block", "minecraft:air"))
        result["distance"] = float(result.get("distance", 51.0))
        return result

    def type(self) -> dict[str, Any]:
        """Compatibility alias for inspect()."""
        return self.inspect()


@dataclass(frozen=True)
class MineBotFurnace:
    """Furnace helper namespace for furnace, blast furnace, and smoker blocks."""

    robot: "MineBot"

    def inspect(self) -> dict[str, Any]:
        """Inspect the looked-at furnace-like block."""
        return self.robot._command("furnace_inspect")

    def place(
        self,
        food: Optional[str] = None,
        fuel: Optional[str] = None,
        food_count: int = 1,
        fuel_count: int = 1,
    ) -> bool:
        """Move robot hotbar items into furnace input and/or fuel slots."""
        payload: dict[str, Any] = {}
        if food is not None:
            payload["food"] = str(food)
            payload["food_count"] = int(food_count)
        if fuel is not None:
            payload["fuel"] = str(fuel)
            payload["fuel_count"] = int(fuel_count)
        self.robot._command("furnace_place", **payload)
        return True

    def take(
        self,
        food: Optional[str] = None,
        fuel: Optional[str] = None,
        food_count: int = 1,
        fuel_count: int = 1,
    ) -> bool:
        """Take items from furnace output or input plus fuel into the robot hotbar."""
        payload: dict[str, Any] = {}
        if food is not None:
            payload["food"] = str(food)
            payload["food_count"] = int(food_count)
        if fuel is not None:
            payload["fuel"] = str(fuel)
            payload["fuel_count"] = int(fuel_count)
        self.robot._command("furnace_take", **payload)
        return True


@dataclass(frozen=True)
class MineBotChest:
    """Chest helper namespace for chests, trapped chests, barrels, shulker boxes, hoppers, droppers, dispensers,
    chest and hopper minecarts, and chest boats."""

    robot: "MineBot"

    def inspect(self) -> dict[str, Any]:
        """Inspect the looked-at chest-like block, storage minecart, or chest boat."""
        return self.robot._command("chest_inspect")

    def place(self, item: str, count: int = 1) -> bool:
        """Move an item from the robot hotbar into the looked-at chest-like block, storage minecart, or chest boat."""
        self.robot._command("chest_place", item=str(item), count=int(count))
        return True

    def take(self, item: str, count: int = 1) -> bool:
        """Move an item from the looked-at chest-like block, storage minecart, or chest boat into the robot hotbar."""
        self.robot._command("chest_take", item=str(item), count=int(count))
        return True


class MineBot:
    """Synchronous Python client for one MineBot. See README.md and PYTHON_SDK.md."""

    def __init__(self, code: Optional[str] = None, url: str = "ws://127.0.0.1:8765/minebot", timeout: float = 5.0) -> None:
        """Create a client from the GUI's Robot Code and Connection Socket values."""
        normalized_code = code.strip() if code else None
        normalized_url = self._normalize_url(url)

        if normalized_code and self._looks_like_url(normalized_code):
            normalized_url = self._normalize_url(normalized_code)
            normalized_code = None

        self.code = normalized_code
        self.url = normalized_url
        self.timeout = timeout
        self._socket: Optional[websocket.WebSocket] = None
        self._last_status: dict[str, Any] = {}
        self.camera = MineBotCamera(self)
        self.furnace = MineBotFurnace(self)
        self.chest = MineBotChest(self)

    def connect(self, code: Optional[str] = None, url: Optional[str] = None) -> dict[str, Any]:
        """Open the websocket session and attach to a robot.

        A robot whose chunks are not loaded is loaded where it was last seen first, which takes up to a few
        seconds; raises MineBotTimeoutError if its area did not load within 4 seconds.
        """
        if code is not None:
            normalized_code = code.strip()
            if self._looks_like_url(normalized_code):
                self.url = self._normalize_url(normalized_code)
                self.code = None
            else:
                self.code = normalized_code

        if url is not None:
            self.url = self._normalize_url(url)

        if not self.code:
            raise MineBotConnectionError(
                "A MineBot code is required. Use the UI's 'Robot Code' as code=... and 'Connection Socket' as url=..."
            )

        self.close()

        try:
            self._socket = websocket.create_connection(self.url, timeout=self.timeout)
        except Exception as exc:  # pragma: no cover - depends on runtime environment
            raise MineBotConnectionError(f"Could not connect to {self.url}") from exc

        response = self._send_raw({"type": "connect", "code": self.code})
        endpoint = str(response.get("endpoint", "")).strip()
        if endpoint:
            self.url = self._normalize_url(endpoint)
        self._last_status = self._normalize_robot_payload(response.get("status", {}))
        return self._last_status

    def close(self) -> None:
        """Close the websocket session cleanly."""
        if self._socket is None:
            return

        try:
            self._socket.send(json.dumps({"type": "disconnect"}))
        except Exception:
            pass

        try:
            self._socket.close()
        finally:
            self._socket = None

    disconnect = close

    def status(self) -> dict[str, Any]:
        """Return the current robot status payload.

        Common keys:
        - entity_id, display_name, code, dimension, owner_name, owner_online
        - selected_slot, selected_item, crouched
        - health, max_health, fuel_count, stored_range_blocks, in_water, air, max_air, seeking_air
        - x, y, z, yaw, pitch, look_block
        - moving_to_target, moving_by_target, direct_move_active, direct_move_x, direct_move_z
        - pillaring, pillar_placed, pillar_requested, bridging, bridge_placed, bridge_requested
        - last_move_known, last_move_success, last_move_message
        - breaking_block, last_attack_known, last_attack_success, last_attack_message
        - using_item, fighting
        - hurt_count (times the robot lost health, kept with the robot) and recent_hurt (its last 8
          hurts, oldest first: id, seconds_ago, amount, health left, cause (damage type id such as
          minecraft:fireball); attacker, attacker_type and attacker_id when it saw who did it, with
          attacker_seen telling whether it did; projectile when one hit it)

        Conditional keys:
        - move_to active: move_target_x, move_target_y, move_target_z, move_target_speed
        - move_by active: move_by_target_x, move_by_target_y, move_by_target_z, move_by_target_speed
        - seeking_air (swimming back to where it last breathed): air_target_x, air_target_y, air_target_z
        - block breaking active: break_target_x, break_target_y, break_target_z,
          break_ticks_remaining, break_progress
        - holding an item in use (use_item on a bow, crossbow, ...): use_item_id, use_hold_ticks,
          use_ticks_remaining
        - after a held use ended: last_use (the outcome use_item() returns)
        - while fighting (attack_entity(until_dead=True)): fight_target_id, fight_swings, fight_hits
        - after a fight ended: last_fight (the outcome attack_entity(until_dead=True) returns)

        Additional metadata and compatibility keys may also be present.
        """
        response = self._send_raw({"type": "status"})
        self._last_status = self._normalize_robot_payload(response.get("status", {}))
        return self._last_status

    def move(self, x: float, z: float, duration: Optional[float] = None) -> dict[str, Any]:
        """Apply local timed movement input where x=forward/backward and z=right/left strafe.

        With a duration the input is released after that many seconds; without one it stays on
        until the next move. Releasing it (x=0, z=0) stops the robot dead.

        The robot stops rather than step into lava or fire, off a drop of more than 3 blocks, or
        from dry land into deep water; status then has last_move_message starting "Stopped:". A
        robot already standing in fire or lava may step out of it (and does so by itself when no
        order drives it).
        Crouched, it does not step off any ledge: like a sneaking player it leans out at most
        0.25 past the edge, far enough to see and place against the side of the block it stands
        on. In water, pushing into a bank at most one block above the water climbs out onto it.
        """
        result = self._command("move", x=float(x), z=float(z))
        if duration is None or duration <= 0.0 or (abs(float(x)) < 1e-9 and abs(float(z)) < 1e-9):
            return result

        try:
            time.sleep(float(duration))
        finally:
            result = self._command("move", x=0.0, z=0.0)
        return result

    def move_by(
        self,
        x: float,
        z: float,
        speed: float = 1.0,
        timeout: float = 30.0,
        poll_interval: float = 0.25,
        tolerance: float = 0.75,
    ) -> bool:
        """Move by a local forward/right block offset using dedicated server-side relative movement.

        The robot stops rather than step into lava or fire, off a drop of more than 3 blocks, or from
        dry land into deep water, and the move fails with a reason starting "Stopped:". A robot
        already standing in fire or lava may step out of it.

        The target height is the walkable spot at the target X/Z nearest the robot's own height,
        at most 12 blocks away; with none there it raises MineBotInvalidRequestError. A robot
        that reaches the target X/Z more than a block off that height is not lifted there, and
        the move fails.

        Returns True when the robot ends within tolerance blocks of the target horizontally and
        0.75 blocks of its height (1.25 afloat); otherwise raises movement_failed with the distance.
        """
        pose = self.locate()
        base_x = math.floor(float(pose.get("x", 0.0))) + 0.5
        base_z = math.floor(float(pose.get("z", 0.0))) + 0.5
        snapped_yaw = self._snap_cardinal_yaw(float(pose.get("yaw", 0.0)))
        offset_x, offset_z = self._local_offset_from_yaw(snapped_yaw, float(x), float(z))

        target_x = round(base_x + offset_x, 3)
        target_z = round(base_z + offset_z, 3)
        result = self._command("move_by", x=round(float(x), 3), z=round(float(z), 3), speed=float(speed))
        if bool(result.get("arrived", False)):
            return True

        status = self._wait_for_status(
            lambda current: not bool(current.get("moving_by_target", False)),
            timeout=timeout,
            poll_interval=poll_interval,
            description="MineBot did not finish the relative move in time",
        )

        return self._finish_move(
            status,
            (float(result.get("target_x", target_x)), result.get("target_y"), float(result.get("target_z", target_z))),
            tolerance,
            "MineBot could not complete the relative move",
        )

    move_absolute = move_by

    def move_to(
        self,
        x: Optional[float] = None,
        z: Optional[float] = None,
        speed: float = 1.0,
        timeout: float = 30.0,
        poll_interval: float = 0.25,
        tolerance: float = 0.75,
        *,
        y: Optional[float] = None,
    ) -> bool:
        """Pathfind to an absolute Minecraft X/Z position and wait for completion.

        The optional keyword-only y is the world height (F3 Y) to search for a walkable
        spot near; without it MineBot searches within 12 blocks of its current height, then
        the open surface, except in the Nether (a dimension with a ceiling), where it raises
        MineBotInvalidRequestError instead.

        Paths may cross water, swim straight up waterfalls and flooded shafts, and climb
        out onto a bank up to one block above the water. They never dive, and they go round
        water that reaches the ceiling unless there is no other way. Paths keep a block
        away from lava and fire, except that a robot already standing in or beside fire or lava
        may path past it to get away; the robot stops rather than step into lava or fire it is
        not already in, or off a drop of more than 3 blocks, and the move then fails with a
        reason starting "Stopped:".
        A robot that runs short of air turns back, and this raises MineBotMovementFailedError.
        Paths need room for the robot's whole body, so they go round cocoa pods, trapdoors and
        other part blocks, and they go round other robots, mobs, players, boats and minecarts. A
        robot that still gets stuck plans again, and after about 4.5 seconds without reaching a
        new spot of its path (jumping at a step or being shoved by a mob gets it nowhere) the
        move fails with a reason starting "Stuck at" that names what it is pressed against.

        Success is judged by where the robot ends up: within tolerance blocks of the target
        horizontally and 0.75 blocks of the target height (1.25 afloat) returns True, even if
        the server reported a problem on the way. Otherwise raises movement_failed with the
        reason and how far the robot is from the target horizontally and vertically.
        """
        if x is None and z is None:
            raise MineBotCommandError("move_to requires at least one of x or z")

        payload: dict[str, Any] = {"speed": float(speed)}
        if x is not None:
            payload["x"] = round(float(x), 3)
        if z is not None:
            payload["z"] = round(float(z), 3)
        if y is not None:
            payload["y"] = round(float(y), 3)

        result = self._command("move_to", **payload)
        target_x = float(result.get("target_x", result.get("x", self.status().get("x", 0.0))))
        target_z = float(result.get("target_z", result.get("z", self.status().get("z", 0.0))))

        if bool(result.get("arrived", False)):
            return True

        status = self._wait_for_status(
            lambda current: not bool(current.get("moving_to_target", False)),
            timeout=timeout,
            poll_interval=poll_interval,
            description="MineBot was still navigating to its target",
        )

        return self._finish_move(
            status,
            (target_x, result.get("target_y"), target_z),
            tolerance,
            "MineBot could not reach that location",
        )

    def turn_by(self, yaw: float = 0.0, pitch: float = 0.0) -> dict[str, Any]:
        """Apply a relative yaw/pitch turn where both axes are deltas."""
        payload: dict[str, Any] = {}
        if yaw is not None:
            payload["yaw"] = float(yaw)
        if pitch is not None:
            payload["pitch"] = float(pitch)
        return self._command("turn_by", **payload)

    def turn(self, yaw: float = 0.0, pitch: float = 0.0) -> dict[str, Any]:
        """Compatibility alias for turn_by()."""
        return self.turn_by(yaw=yaw, pitch=pitch)

    def turn_to(self, yaw: Optional[float] = None, pitch: Optional[float] = None) -> dict[str, Any]:
        """Set absolute Minecraft F3-style yaw and/or pitch orientation."""
        if yaw is None and pitch is None:
            raise MineBotCommandError("turn_to requires at least one of yaw or pitch")

        payload: dict[str, Any] = {}
        if yaw is not None:
            payload["yaw"] = round(float(yaw), 1)
        if pitch is not None:
            payload["pitch"] = round(float(pitch), 1)
        return self._command("turn_to", **payload)

    def crouch(self) -> dict[str, Any]:
        """Enter crouch mode and stay crouched until uncrouched or a jump cancels it.

        Crouched, move() will not walk off a ledge but leans out at most 0.25 past it, like a
        sneaking player. In water this dives, sinking about 4 blocks a second. Short of air, the robot stands
        up and swims back to where it last breathed by itself.
        """
        return self._command("crouch")

    def uncrouch(self) -> dict[str, Any]:
        """Leave crouch mode explicitly."""
        return self._command("uncrouch")

    def center(self) -> bool:
        """Snap to the exact center of the current block and face the nearest cardinal direction."""
        self._command("center")
        return True

    def enter_vehicle(self) -> dict[str, Any]:
        """Enter the rideable vehicle directly in front of the robot. (Experimental)"""
        return self._command("enter_vehicle")

    def exit_vehicle(self) -> dict[str, Any]:
        """Exit the vehicle the robot is currently riding. (Experimental)"""
        return self._command("exit_vehicle")

    def place(self) -> dict[str, Any]:
        """Use the selected hotbar item on the looked-at block, or fall back to a simple block interaction."""
        return self._command("place")

    def craft(self, item: str, count: Optional[int] = None) -> bool:
        """Craft the requested item. 2x2 recipes work anywhere; 3x3 recipes need the robot looking at a crafting table."""
        payload: dict[str, Any] = {"item": str(item)}
        if count is not None:
            payload["count"] = int(count)
        self._command("craft", **payload)
        return True

    def attack(self, wait: bool = True, timeout: float = 10.0, poll_interval: float = 0.25) -> bool:
        """Mine the block in front of the robot and optionally wait for completion."""
        try:
            result = self._command("attack")
        except MineBotCommandError:
            return False

        if not wait:
            return bool(result.get("attacking", False))

        try:
            status = self._wait_for_status(
                lambda current: not bool(current.get("breaking_block", False)),
                timeout=timeout,
                poll_interval=poll_interval,
                description="MineBot did not finish attacking the block in time",
            )
        except MineBotCommandError:
            return False

        return bool(status.get("last_attack_known", False) and status.get("last_attack_success", False))

    def break_block(self, wait: bool = True, timeout: float = 10.0, poll_interval: float = 0.25) -> bool:
        """Compatibility alias for attack()."""
        return self.attack(wait=wait, timeout=timeout, poll_interval=poll_interval)

    destroy = attack

    def jump(self) -> dict[str, Any]:
        """Make the robot jump once. In water or lava it swims upward and surfaces."""
        return self._command("jump")

    def pillar_up(self, count: int = 1, timeout: float = 30.0, poll_interval: float = 0.25) -> dict[str, Any]:
        """Build straight up under the robot with the block in the selected slot, and wait.

        Like a player pillaring: the robot looks down, jumps, places a block in the cell its
        feet just left and lands on it, `count` times (1..64). The server times each placement,
        so latency does not matter. It must stand on the ground on top of a full block, with
        room above its head. Each block rises the robot one block.

        Returns {'placed': n, 'x', 'y', 'z'}. Raises MineBotMovementFailedError, with how many
        blocks were placed, when it stops early (no blocks left, a ceiling, out of energy, ...).
        """
        result = self._command("pillar_up", count=int(count))
        status = self._wait_for_status(
            lambda current: not bool(current.get("pillaring", False)),
            timeout=timeout,
            poll_interval=poll_interval,
            description="MineBot was still pillaring up",
        )
        placed = int(status.get("pillar_placed", 0))
        if placed < int(result.get("count", count)) or not bool(status.get("last_move_success", False)):
            reason = str(status.get("last_move_message") or "MineBot stopped pillaring")
            self._raise_command_error(
                f"{reason}. Robot is at ({float(status.get('x', 0.0)):.1f}, {float(status.get('y', 0.0)):.1f}, "
                f"{float(status.get('z', 0.0)):.1f})",
                code=MineBotErrorCode.MOVEMENT_FAILED.value,
            )
        return {"placed": placed, "x": status.get("x"), "y": status.get("y"), "z": status.get("z")}

    def bridge(
        self,
        direction: str,
        count: int = 1,
        timeout: Optional[float] = None,
        poll_interval: float = 0.25,
    ) -> dict[str, Any]:
        """Build a walkway out over open air with the block in the selected slot, and wait.

        Like a player bridging: the robot crouches, faces back the way it came, backs up until
        it leans over the edge of the block it stands on, places a block against that block's
        outer face and backs onto it, `count` times (1..64) toward `direction` ('north',
        'south', 'east' or 'west'). The walkway is level with the block it stands on. The
        server runs each step, so latency does not matter. It ends crouched in the middle of
        the last block, facing `direction`. `timeout` defaults to 10 s plus 2 s per block.

        Returns {'placed': n, 'x', 'y', 'z'}. Raises MineBotMovementFailedError, with how many
        blocks were placed, when it stops early (the next cell is not open, no room above it,
        no blocks left, out of energy, lava ahead, ...).
        """
        count = int(count)
        result = self._command("bridge", direction=str(direction), count=count)
        status = self._wait_for_status(
            lambda current: not bool(current.get("bridging", False)),
            timeout=10.0 + 2.0 * count if timeout is None else timeout,
            poll_interval=poll_interval,
            description="MineBot was still bridging",
        )
        placed = int(status.get("bridge_placed", 0))
        if placed < int(result.get("count", count)) or not bool(status.get("last_move_success", False)):
            reason = str(status.get("last_move_message") or "MineBot stopped bridging")
            self._raise_command_error(
                f"{reason}. Robot is at ({float(status.get('x', 0.0)):.1f}, {float(status.get('y', 0.0)):.1f}, "
                f"{float(status.get('z', 0.0)):.1f})",
                code=MineBotErrorCode.MOVEMENT_FAILED.value,
            )
        return {"placed": placed, "x": status.get("x"), "y": status.get("y"), "z": status.get("z")}

    def print(self, *parts: Any) -> dict[str, Any]:
        """Print a MineBot-labelled chat line. Example: robot.print('Track ready', 12)."""
        message = " ".join(str(part) for part in parts).strip()
        return self._command("print", message=message)

    def say(self, *parts: Any, to: Optional[str] = None) -> dict[str, Any]:
        """Send a MineBot chat line to everyone, or only to player `to` (raises MineBotPlayerNotFoundError if offline)."""
        message = " ".join(str(part) for part in parts).strip()
        payload: dict[str, Any] = {"message": message}
        if to is not None and str(to).strip():
            payload["to"] = str(to).strip()
        return self._command("print", **payload)

    def read_chat(self, peek: bool = False, limit: Optional[int] = None) -> dict[str, Any]:
        """Read chat addressed to this robot (@code, @name, @bot, @all).

        Returns {'messages': [...], 'remaining': int, 'dropped': int}, oldest first. Unless
        peek=True the returned messages are removed from the robot inbox.
        """
        payload: dict[str, Any] = {"peek": bool(peek)}
        if limit is not None:
            payload["limit"] = int(limit)
        return self._command("read_chat", **payload)

    def wait_for_chat(self, timeout: float = 30.0, poll_interval: float = 0.5) -> list[dict[str, Any]]:
        """Poll read_chat() until messages arrive; return them, or [] after `timeout` seconds."""
        deadline = time.monotonic() + max(0.0, float(timeout))
        while True:
            messages = self.read_chat().get("messages") or []
            if messages:
                return list(messages)
            remaining = deadline - time.monotonic()
            if remaining <= 0.0:
                return []
            time.sleep(min(float(poll_interval), remaining))

    def inventory(self) -> dict[str, Any]:
        """Return all 10 hotbar slots plus selected_slot, fuel_item, fuel_count and stored_range_blocks."""
        return self._command("inventory")

    def scan_blocks(
        self,
        radius: int = 8,
        blocks: Optional[list[str]] = None,
        limit: int = 64,
        center: Optional[tuple[int, int, int]] = None,
    ) -> dict[str, Any]:
        """List the blocks the robot can see in the cube of `radius` around it (or around block `center`).

        Only blocks in the robot's line of sight are reported: nothing behind walls or underground.
        `radius` is clamped to 1..16, or 1..32 when `blocks` is given. `blocks` filters by block ids or
        '#namespace:tag' tags. Source fluid is reported as minecraft:water / minecraft:lava and flowing
        fluid as minecraft:flowing_water / minecraft:flowing_lava; the filter matches those ids, so
        blocks=["minecraft:lava"] finds only lava sources. blocks=["minecraft:fire"] finds soul fire too,
        reported as minecraft:soul_fire; blocks=["minecraft:soul_fire"] finds soul fire only. Returns
        'matches' (nearest first, each with block, x, y, z, distance), 'total_matches', 'truncated' and
        'counts'.
        """
        payload: dict[str, Any] = {
            "radius": int(radius),
            "limit": int(limit),
        }
        if blocks:
            payload["blocks"] = [str(block) for block in blocks]
        if center is not None:
            center_x, center_y, center_z = center
            payload["center_x"] = int(center_x)
            payload["center_y"] = int(center_y)
            payload["center_z"] = int(center_z)
        return self._command("scan_blocks", **payload)

    def scan_entities(
        self,
        radius: float = 16.0,
        types: Optional[list[str]] = None,
        players_only: bool = False,
        limit: int = 32,
    ) -> dict[str, Any]:
        """List entities the robot can see within `radius` blocks, nearest first.

        Entities hidden behind blocks are left out, except players, who are noticed through walls
        unless they sneak. Returns {'radius', 'entities', 'total', 'truncated'}.
        """
        payload: dict[str, Any] = {
            "radius": float(radius),
            "players_only": bool(players_only),
            "limit": int(limit),
        }
        if types:
            payload["types"] = [str(entity_type) for entity_type in types]
        return self._command("scan_entities", **payload)

    def environment(self) -> dict[str, Any]:
        """Return dimension, biome, time_of_day, weather, light level and the blocks around the robot."""
        return self._command("environment")

    def stop(self) -> dict[str, Any]:
        """Stop direct motion, move_by, move_to, any block breaking and an item held by use_item()."""
        return self._command("stop")

    def look_at(
        self,
        x: Optional[float] = None,
        y: Optional[float] = None,
        z: Optional[float] = None,
        entity_id: Optional[int] = None,
    ) -> dict[str, Any]:
        """Turn so the crosshair passes through world point (x, y, z) or through visible entity `entity_id`."""
        if entity_id is not None:
            return self._command("look_at", entity_id=int(entity_id))
        if x is None or y is None or z is None:
            raise MineBotInvalidRequestError("look_at requires x, y and z, or entity_id")
        return self._command("look_at", x=float(x), y=float(y), z=float(z))

    def attack_entity(
        self,
        until_dead: bool = False,
        follow: bool = False,
        guard: bool = True,
        min_health: Optional[float] = None,
        max_seconds: Optional[float] = None,
        entity_id: Optional[int] = None,
        wait: bool = True,
        timeout: Optional[float] = None,
        poll_interval: float = 0.25,
    ) -> dict[str, Any]:
        """Melee-attack the entity in the crosshair (within reach) with the selected item.

        By default one hit, at full strength however soon it follows the last: returns ``entity``,
        ``entity_id``, ``damage``, ``hit``, ``killed`` and the target's ``health``. A hit pushes the
        target back as any hit does (about a block); only a knockback enchantment adds to that, as for
        a standing player.

        With ``until_dead`` the robot fights a living target to the end. The server aims at it every
        tick and swings each time the weapon has recharged, as a player's attack does (a sword every
        0.65 s, an axe every 1 to 1.25 s, never more often than every 0.5 s). It keeps fighting for
        the whole of ``max_seconds`` (1..120, default 30) as long as it can see the target, however
        far off the target keeps. The fight ends when the target dies or is gone; when the robot has
        not seen it for 3 s; when the robot's health falls to ``min_health`` (default 8 of 20) or
        below; after ``max_seconds``; when the robot runs out of energy; or when another command
        interrupts it (any command but status, read_chat, print, inventory, the scans, camera inspect,
        eat and refuel). A robot already at or below ``min_health`` raises
        ``MineBotInteractionUnavailableError``.

        With ``follow`` the robot also walks after the target to keep within 2.5 blocks of it: along
        a path, as ``move_to`` does, after a target on the ground, and straight at the ground under a
        target in the air, such as a hovering blaze. It never steps into lava or fire or off a drop of
        more than 3 blocks, and never follows more than 16 blocks from where the fight started; it
        fights on from where it stands.

        With an ``entity_id`` (from ``scan_entities``) the fight starts on that entity even when it is
        out of reach, as long as the robot can see it within 16 blocks: the robot aims at it, and walks
        up to it with ``follow`` or waits for it to come within reach without. Without an id the target
        is the entity in the crosshair within reach (``MineBotEntityNotFoundError`` for an id that is
        not in view, ``MineBotNotLookingAtEntityError`` for an empty crosshair).

        With ``guard`` (default) and a shield in another hotbar slot, the robot holds the shield up
        between swings, facing the target, as a player holds one in the off hand, and takes the weapon
        back for each swing; the weapon is selected again when the fight ends. The started fight and
        the outcome say ``guard: True`` when a shield was found.

        With ``wait`` (default) the call waits for the end and returns the outcome, also kept as
        ``status()["last_fight"]``: ``entity``, ``entity_id``, ``killed``, ``ended`` (``killed``,
        ``gone``, ``out_of_reach`` for a target out of sight, ``low_health``, ``timeout``,
        ``out_of_energy`` or ``interrupted``), ``message``, ``swings``, ``hits``, ``damage`` dealt,
        ``target_health`` (only while the robot can still see the target), ``seconds``, the robot's
        ``health`` and ``health_lost``, and ``guard``. With ``wait=False`` it returns at once with
        ``fighting``; poll ``status()`` until ``fighting`` is False and read ``last_fight``.
        ``timeout`` defaults to ``max_seconds`` plus 5 s.

        Mobs fight back, and a blaze that is hit calls the blazes near it: ``status()["recent_hurt"]``
        says what hurt the robot. A blaze hovers 4 to 8 blocks off and shoots; it never comes to a
        robot it can see, so the robot has to go to it (``follow=True``).
        """
        if not until_dead:
            return self._command("attack_entity")

        payload: dict[str, Any] = {"until_dead": True, "follow": bool(follow), "guard": bool(guard)}
        if entity_id is not None:
            payload["entity_id"] = int(entity_id)
        if min_health is not None:
            payload["min_health"] = float(min_health)
        if max_seconds is not None:
            payload["max_seconds"] = float(max_seconds)
        result = self._command("attack_entity", **payload)
        if not wait or not result.get("fighting"):
            return result

        seconds = float(result.get("max_seconds", max_seconds if max_seconds is not None else 30.0))
        status = self._wait_for_status(
            lambda current: not bool(current.get("fighting", False)),
            timeout=timeout if timeout is not None else seconds + 5.0,
            poll_interval=poll_interval,
            description=f"MineBot was still fighting {result.get('entity')}",
        )
        outcome = status.get("last_fight")
        if not isinstance(outcome, dict):
            return {"entity": result.get("entity"), "killed": False, "message": "The mod reported no fight result"}
        return outcome

    def use_item(
        self,
        hold_seconds: Optional[float] = None,
        wait: bool = True,
        timeout: Optional[float] = None,
        poll_interval: float = 0.25,
    ) -> dict[str, Any]:
        """Right-click with the selected item (buckets, throwables, boats, spawn eggs, ...).

        If the item does nothing in the air and a block is in the crosshair, it is used on that block
        like ``place()`` (flint and steel, bone meal, hoes, ...); the result then holds ``on_block``.

        Hold-to-use items are held like a player holds the button, then released: a bow draws fully
        (1 s) and fires, a crossbow loads (the next ``use_item()`` fires it), a trident is thrown, a
        shield is raised for 1 s. ``hold_seconds`` (0.05..60) sets another hold time, for example a
        partial bow draw; click items ignore it. Bows and crossbows need ammunition in the hotbar
        (``missing_item`` otherwise). Food and potions raise ``interaction_unavailable``: robots
        cannot eat or drink them (they eat iron and copper ingots with ``eat()``).

        With ``wait`` (default) a held use waits for the release and returns the final outcome:
        ``used``, ``completed`` (False with a ``message`` when another command or a slot change
        interrupted it), ``held_ticks``, ``projectiles`` (entity ids launched, e.g.
        ``["minecraft:arrow"]``), ``spent`` / ``gained`` (hotbar item counts), ``charged`` (crossbows)
        and ``selected_item``. With ``wait=False`` it returns at once with ``holding``, ``hold_ticks``
        and ``eta_ticks``; poll ``status()`` until ``using_item`` is False and read ``last_use``.

        Click items return at once with ``used``, ``accepted``, ``projectiles``, ``spent``,
        ``gained`` and ``selected_item``.
        """
        payload: dict[str, Any] = {}
        if hold_seconds is not None:
            payload["hold_seconds"] = float(hold_seconds)
        result = self._command("use_item", **payload)
        if not wait or not result.get("holding"):
            return result

        hold = float(result.get("eta_ticks", 0) or 0) / 20.0
        status = self._wait_for_status(
            lambda current: not bool(current.get("using_item", False)),
            timeout=timeout if timeout is not None else hold + 5.0,
            poll_interval=poll_interval,
            description=f"MineBot was still using {result.get('used')}",
        )
        outcome = status.get("last_use")
        if not isinstance(outcome, dict):
            return {"used": result.get("used"), "completed": False, "message": "The mod reported no use result"}
        return outcome

    def use_on_entity(self) -> dict[str, Any]:
        """Right-click the entity in the crosshair with the selected item (shears, food, leads, ...)."""
        return self._command("use_on_entity")

    def move_item(self, from_slot: int, to_slot: int, count: Optional[int] = None) -> dict[str, Any]:
        """Move or merge a stack between hotbar slots; swaps when the target holds a different item."""
        payload: dict[str, Any] = {"from": int(from_slot), "to": int(to_slot)}
        if count is not None:
            payload["count"] = int(count)
        return self._command("move_item", **payload)

    def refuel(self, count: Optional[int] = None) -> dict[str, Any]:
        """Move blaze powder from the hotbar into the fuel slot (as much as fits unless `count` is given)."""
        payload: dict[str, Any] = {}
        if count is not None:
            payload["count"] = int(count)
        return self._command("refuel", **payload)

    def eat(self, item: Optional[str] = None, count: Optional[int] = None) -> dict[str, Any]:
        """Eat iron or copper ingots from the hotbar; each gives back one heart (2 health).

        Eats as many as it takes to reach full health, never more, and at most ``count``. Copper
        ingots go first; ``item`` (``minecraft:iron_ingot`` or ``minecraft:copper_ingot``) eats only
        that kind. Needs no energy. Returns ``eaten``, ``spent`` (item id to count), ``healed``,
        ``health`` and ``max_health``. Raises ``target_full`` at full health, ``missing_item`` without
        ingots and ``invalid_item`` for any other item.
        """
        payload: dict[str, Any] = {}
        if item is not None:
            payload["item"] = item
        if count is not None:
            payload["count"] = int(count)
        return self._command("eat", **payload)

    def mine(self, timeout: float = 10.0, poll_interval: float = 0.25) -> dict[str, Any]:
        """Mine the crosshair block and wait. Raises if mining cannot start.

        Returns {'broken': bool, 'block': id, 'pos': 'x, y, z', 'message': reason or ''}.
        """
        result = self._command("attack")
        status = self._wait_for_status(
            lambda current: not bool(current.get("breaking_block", False)),
            timeout=timeout,
            poll_interval=poll_interval,
            description="MineBot did not finish mining the block in time",
        )
        return {
            "broken": bool(status.get("last_attack_known", False) and status.get("last_attack_success", False)),
            "block": result.get("block"),
            "pos": result.get("pos"),
            "message": str(status.get("last_attack_message", "")),
        }

    def command(self, action: str, **payload: Any) -> dict[str, Any]:
        """Send a raw command action with extra fields and return its result dict (raises typed errors)."""
        return self._command(action, **payload)

    def is_connected(self) -> bool:
        """Return True while this client holds an open websocket session."""
        return self._socket is not None

    def evil(self) -> None:
        """Break control and turn the robot permanently hostile."""
        try:
            self._command("evil")
        finally:
            self.close()

        raise MineBotBrokeFreeError(
            "The robot broke free from its chains and is seeking vengeance.",
            code=MineBotErrorCode.BROKE_FREE,
        )

    def select_slot(self, slot: int) -> dict[str, Any]:
        """Select a robot hotbar slot directly."""
        return self._command("select_slot", slot=int(slot))

    def hotbar(self, slot: int) -> MineBotSlot:
        """Select a robot hotbar slot and return a slot helper. See PYTHON_SDK.md hotbar section."""
        self.select_slot(slot)
        return MineBotSlot(self, slot)

    hotbox = hotbar

    def inspect_slot(self, slot: Optional[int] = None) -> dict[str, Any]:
        """Return {'block': <minecraft:id>, 'count': <int>} for a slot or the currently selected slot."""
        payload = self._command("slot_inspect", **({"slot": int(slot)} if slot is not None else {}))
        return {
            "block": str(payload.get("item", "minecraft:air")),
            "count": int(payload.get("count", 0)),
        }

    def slot_type(self, slot: Optional[int] = None) -> str:
        """Compatibility alias for inspect_slot(). Returns only the Minecraft item id."""
        return str(self.inspect_slot(slot)["block"])

    def look_type(self) -> dict[str, Any]:
        """Compatibility alias for robot.camera.inspect(). Returns {'block': ..., 'distance': ...}."""
        return self.camera.inspect()

    def locate(self) -> dict[str, Any]:
        """Return the connected robot's absolute position and orientation."""
        status = self.status()
        return {
            "dimension": status.get("dimension"),
            "x": status.get("x"),
            "y": status.get("y"),
            "z": status.get("z"),
            "yaw": status.get("yaw"),
            "pitch": status.get("pitch"),
        }

    def locate_robot(self) -> dict[str, Any]:
        """Compatibility alias for locate()."""
        return self.locate()

    def last_status(self) -> dict[str, Any]:
        """Return the last cached status payload received by this client."""
        return dict(self._last_status)

    @classmethod
    def list_robots(
        cls,
        url: str = "ws://127.0.0.1:8765/minebot",
        timeout: float = 5.0,
        include_dead: bool = False,
    ) -> list[dict[str, Any]]:
        """List the robots of the world without connecting to one first.

        Loaded robots come first. Robots whose chunks are not loaded follow with `loaded: False` and the
        position, health and name saved when they were last seen; connect() loads them. With
        include_dead=True the robots that died are listed last, with `dead: True` and their `death` report.
        """
        response = cls._request_once(url, timeout, {"type": "robots"})
        robots = response.get("robots", [])
        if not isinstance(robots, list):
            raise MineBotConnectionError("Received an invalid robot listing response")
        return [
            cls._normalize_robot_payload(robot)
            for robot in robots
            if isinstance(robot, dict) and (include_dead or not robot.get("dead", False))
        ]

    def __enter__(self) -> "MineBot":
        """Connect on context-manager entry."""
        self.connect()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        """Close on context-manager exit."""
        self.close()

    def _command(self, action: str, **payload: Any) -> dict[str, Any]:
        request: dict[str, Any] = {
            "type": "command",
            "request_id": uuid.uuid4().hex,
            "action": action,
        }
        request.update(payload)
        response = self._send_raw(request)

        if not response.get("ok", False):
            self._raise_command_error(
                str(response.get("error", "Command failed")),
                code=response.get("error_code"),
            )

        result = response.get("result", {})
        if action == "status" and isinstance(result, dict):
            normalized = self._normalize_robot_payload(result)
            self._last_status = normalized
            return normalized
        return result

    def _wait_for_status(
        self,
        predicate,
        timeout: float,
        poll_interval: float,
        description: str,
        *,
        code: str | MineBotErrorCode = MineBotErrorCode.TIMEOUT,
    ) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        status = self.status()

        while not predicate(status):
            if time.monotonic() >= deadline:
                raise MineBotTimeoutError(description, code=code)

            time.sleep(poll_interval)
            status = self.status()

        return status

    def _send_raw(self, payload: dict[str, Any]) -> dict[str, Any]:
        if self._socket is None:
            raise MineBotConnectionError("The MineBot is not connected")

        self._raise_pushed_error()

        try:
            self._socket.send(json.dumps(payload))
            raw = self._socket.recv()
        except (websocket.WebSocketException, OSError) as exc:
            # The request/response pairing on this socket can no longer be trusted.
            self._abandon_socket()
            raise MineBotConnectionError(f"Lost the websocket connection to {self.url}: {exc}") from exc

        return self._parse_response(raw)

    def _raise_pushed_error(self) -> None:
        """Raise the error the bridge sent between requests, if any.

        The bridge only speaks unasked when it ends the session (the robot died), and then closes
        the socket. Reading that message before sending keeps it from being lost to the close.
        Keepalive pings also make the socket readable. They are answered and skipped here: a plain
        recv() would answer one and then wait for a message that is not coming.
        """
        socket = self._socket
        sock = getattr(socket, "sock", None)
        if socket is None or sock is None:
            return
        try:
            while True:
                if not select.select([sock], [], [], 0)[0]:
                    return
                opcode, frame = socket.recv_data_frame(control_frame=True)
                if opcode not in (websocket.ABNF.OPCODE_PING, websocket.ABNF.OPCODE_PONG):
                    break
        except (websocket.WebSocketException, OSError, ValueError) as exc:
            self._abandon_socket()
            raise MineBotConnectionError(f"Lost the websocket connection to {self.url}: {exc}") from exc
        # The same values recv() returns: text decoded, binary as bytes, "" for a close.
        if opcode == websocket.ABNF.OPCODE_TEXT:
            self._parse_response(frame.data.decode("utf-8"))
        elif opcode == websocket.ABNF.OPCODE_BINARY:
            self._parse_response(frame.data)
        else:
            self._parse_response("")

    def _parse_response(self, raw: Any) -> dict[str, Any]:
        if not raw:
            self._abandon_socket()
            raise MineBotConnectionError(f"The websocket connection to {self.url} was closed by the server")

        try:
            response = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise MineBotConnectionError(f"Received invalid JSON: {raw!r}") from exc

        if response.get("type") == "error":
            if response.get("code") == MineBotErrorCode.DIED.value:
                self._abandon_socket()  # the bridge closes a dead robot's session
            self._raise_error_response(response)

        return response

    def _abandon_socket(self) -> None:
        socket, self._socket = self._socket, None
        if socket is not None:
            try:
                socket.close()
            except Exception:
                pass

    @classmethod
    def _request_once(cls, url: str, timeout: float, payload: dict[str, Any]) -> dict[str, Any]:
        normalized_url = cls._normalize_url(url)

        try:
            socket = websocket.create_connection(normalized_url, timeout=timeout)
        except Exception as exc:  # pragma: no cover - depends on runtime environment
            raise MineBotConnectionError(f"Could not connect to {normalized_url}") from exc

        try:
            socket.send(json.dumps(payload))
            raw = socket.recv()
        finally:
            socket.close()

        if raw is None:
            raise MineBotConnectionError("No response was received from the server")

        try:
            response = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise MineBotConnectionError(f"Received invalid JSON: {raw!r}") from exc

        if response.get("type") == "error":
            cls._raise_error_response(response)

        return response

    @classmethod
    def _raise_error_response(cls, response: dict[str, Any]) -> NoReturn:
        message = str(response.get("message", "Unknown error"))
        code = response.get("code")
        if code == MineBotErrorCode.DIED.value:
            death = response.get("death")
            raise MineBotDiedError(message, code=code, death=death if isinstance(death, dict) else None)
        cls._raise_command_error(message, code=code)

    @classmethod
    def _raise_command_error(cls, message: str, code: Optional[str] = None) -> NoReturn:
        error_cls = MINEBOT_CODE_TO_EXCEPTION.get(str(code), MineBotCommandError)
        raise error_cls(message, code=str(code) if code else None)

    @staticmethod
    def _distance_to_target(status: dict[str, Any], target_x: float, target_z: float) -> float:
        dx = float(status.get("x", 0.0)) - target_x
        dz = float(status.get("z", 0.0)) - target_z
        return math.hypot(dx, dz)

    def _finish_move(
        self,
        status: dict[str, Any],
        target: tuple[float, Any, float],
        tolerance: float,
        failure_message: str,
    ) -> bool:
        """Judge a finished move by where the robot ended up; the server's verdict only supplies the reason."""
        target_x, target_y, target_z = target
        horizontal = self._distance_to_target(status, target_x, target_z)
        vertical = 0.0 if target_y is None else float(status.get("y", 0.0)) - float(target_y)
        vertical_tolerance = MOVE_AFLOAT_VERTICAL_TOLERANCE if bool(status.get("in_water", False)) else MOVE_VERTICAL_TOLERANCE
        if horizontal <= tolerance and abs(vertical) <= vertical_tolerance:
            return True

        if bool(status.get("last_move_known", False)) and not bool(status.get("last_move_success", False)):
            reason = str(status.get("last_move_message") or failure_message)
        else:
            reason = "MineBot ended away from the target"
        where = f"{horizontal:.1f} blocks from the target"
        if abs(vertical) >= 0.5:
            where += f" horizontally and {abs(vertical):.1f} blocks {'above' if vertical > 0 else 'below'} it"
        self._raise_command_error(
            f"{reason}. Robot is at ({float(status.get('x', 0.0)):.1f}, {float(status.get('y', 0.0)):.1f}, "
            f"{float(status.get('z', 0.0)):.1f}), {where}",
            code=MineBotErrorCode.MOVEMENT_FAILED.value,
        )

    @staticmethod
    def _local_offset_from_yaw(yaw: float, x: float, z: float) -> tuple[float, float]:
        yaw_radians = math.radians(yaw)
        forward_x = -math.sin(yaw_radians)
        forward_z = math.cos(yaw_radians)
        right_x = math.cos(yaw_radians)
        right_z = math.sin(yaw_radians)
        return (
            round(forward_x * float(x) + right_x * float(z), 3),
            round(forward_z * float(x) + right_z * float(z), 3),
        )

    @staticmethod
    def _snap_cardinal_yaw(yaw: float) -> float:
        wrapped = ((float(yaw) + 180.0) % 360.0) - 180.0
        return round(round(wrapped / 90.0) * 90.0, 1)

    @staticmethod
    def _looks_like_url(value: str) -> bool:
        lowered = value.lower()
        return lowered.startswith(("ws://", "wss://", "http://", "https://"))

    @staticmethod
    def _normalize_url(value: str) -> str:
        parsed = urlparse(value.strip())
        scheme = parsed.scheme.lower()

        if scheme == "http":
            parsed = parsed._replace(scheme="ws")
        elif scheme == "https":
            parsed = parsed._replace(scheme="wss")

        return urlunparse(parsed)

    @staticmethod
    def _normalize_robot_payload(payload: Any) -> dict[str, Any]:
        if not isinstance(payload, dict):
            return {}

        normalized = dict(payload)
        code = normalized.get("code")
        access_code = normalized.get("access_code")

        if code in (None, "") and access_code not in (None, ""):
            normalized["code"] = access_code
        elif access_code in (None, "") and code not in (None, ""):
            normalized["access_code"] = code

        return normalized
