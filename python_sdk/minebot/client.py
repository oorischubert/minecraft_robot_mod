from __future__ import annotations

import base64
import json
import math
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
    MineBotError,
    MineBotErrorCode,
    MineBotInvalidRequestError,
    MineBotTimeoutError,
)


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

    def snapshot(self) -> bytes:
        """Capture one PNG frame from the robot camera as PNG bytes."""
        payload = self.robot._command("camera_snapshot")
        encoded = str(payload.get("data_base64", ""))
        if not encoded:
            raise MineBotCameraUnavailableError(
                "MineBot camera snapshot returned no image data",
                code=MineBotErrorCode.CAMERA_ERROR,
            )
        return base64.b64decode(encoded)

    def stream(self, interval: float = 0.25, frame_limit: Optional[int] = None) -> Iterator[bytes]:
        """Yield repeated PNG snapshots at a fixed polling interval."""
        frame = 0
        while frame_limit is None or frame < frame_limit:
            yield self.snapshot()
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
    """Chest helper namespace for chests, trapped chests, barrels, shulker boxes, hoppers, droppers, and dispensers."""

    robot: "MineBot"

    def inspect(self) -> dict[str, Any]:
        """Inspect the looked-at chest-like inventory."""
        return self.robot._command("chest_inspect")

    def place(self, item: str, count: int = 1) -> bool:
        """Move an item from the robot hotbar into the looked-at chest-like block."""
        self.robot._command("chest_place", item=str(item), count=int(count))
        return True

    def take(self, item: str, count: int = 1) -> bool:
        """Move an item from the looked-at chest-like block into the robot hotbar."""
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
        """Open the websocket session and attach to a robot."""
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
        - health, max_health, fuel_count, stored_range_blocks, in_water, air, max_air
        - x, y, z, yaw, pitch, look_block
        - moving_to_target, moving_by_target, direct_move_active, direct_move_x, direct_move_z
        - last_move_known, last_move_success, last_move_message
        - breaking_block, last_attack_known, last_attack_success, last_attack_message

        Conditional keys:
        - move_to active: move_target_x, move_target_y, move_target_z, move_target_speed
        - move_by active: move_by_target_x, move_by_target_y, move_by_target_z, move_by_target_speed
        - block breaking active: break_target_x, break_target_y, break_target_z,
          break_ticks_remaining, break_progress

        Additional metadata and compatibility keys may also be present.
        """
        response = self._send_raw({"type": "status"})
        self._last_status = self._normalize_robot_payload(response.get("status", {}))
        return self._last_status

    def move(self, x: float, z: float, duration: Optional[float] = None) -> dict[str, Any]:
        """Apply local timed movement input where x=forward/backward and z=right/left strafe.

        In water, pushing into a bank at most one block above the water climbs out onto it.
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
        """Move by a local forward/right block offset using dedicated server-side relative movement."""
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

        if bool(status.get("last_move_known", False)) and not bool(status.get("last_move_success", False)):
            self._raise_command_error(
                str(status.get("last_move_message", "MineBot could not complete the relative move")),
                code=MineBotErrorCode.MOVEMENT_FAILED.value,
            )

        distance = self._distance_to_target(status, target_x, target_z)
        if distance <= tolerance:
            return True

        self._raise_command_error(
            str(status.get("last_move_message", "MineBot stopped before reaching the relative target")),
            code=MineBotErrorCode.MOVEMENT_FAILED.value,
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
        spot near; without it MineBot searches near its current height.

        Paths may cross water, swim straight up waterfalls and flooded shafts, and climb
        out onto a bank up to one block above the water. They never dive.
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

        if bool(status.get("last_move_known", False)) and not bool(status.get("last_move_success", False)):
            self._raise_command_error(
                str(status.get("last_move_message", "MineBot could not reach that location")),
                code=MineBotErrorCode.MOVEMENT_FAILED.value,
            )

        distance = self._distance_to_target(status, target_x, target_z)
        if distance <= tolerance:
            return True

        self._raise_command_error(
            str(status.get("last_move_message", "MineBot stopped before reaching the target location")),
            code=MineBotErrorCode.MOVEMENT_FAILED.value,
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

        In water this dives, sinking about 4 blocks a second.
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
        `blocks` filters by block ids or '#namespace:tag' tags. Returns 'matches' (nearest first,
        each with block, x, y, z, distance), 'total_matches', 'truncated' and 'counts'.
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
        """Stop direct motion, move_by, move_to and any block breaking."""
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

    def attack_entity(self) -> dict[str, Any]:
        """Melee-attack the living entity in the crosshair (within reach) with the selected item."""
        return self._command("attack_entity")

    def use_item(self) -> dict[str, Any]:
        """Right-click with the selected item (buckets, throwables, boats, spawn eggs, ...).

        If the item does nothing in the air and a block is in the crosshair, it is used on that block
        like ``place()`` (flint and steel, bone meal, hoes, ...); the result then holds ``on_block``.
        """
        return self._command("use_item")

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
    def list_robots(cls, url: str = "ws://127.0.0.1:8765/minebot", timeout: float = 5.0) -> list[dict[str, Any]]:
        """List all currently loaded robots without connecting to one first."""
        response = cls._request_once(url, timeout, {"type": "robots"})
        robots = response.get("robots", [])
        if not isinstance(robots, list):
            raise MineBotConnectionError("Received an invalid robot listing response")
        return [cls._normalize_robot_payload(robot) for robot in robots if isinstance(robot, dict)]

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

        try:
            self._socket.send(json.dumps(payload))
            raw = self._socket.recv()
        except (websocket.WebSocketException, OSError) as exc:
            # The request/response pairing on this socket can no longer be trusted.
            self._abandon_socket()
            raise MineBotConnectionError(f"Lost the websocket connection to {self.url}: {exc}") from exc

        if not raw:
            self._abandon_socket()
            raise MineBotConnectionError(f"The websocket connection to {self.url} was closed by the server")

        try:
            response = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise MineBotConnectionError(f"Received invalid JSON: {raw!r}") from exc

        if response.get("type") == "error":
            self._raise_command_error(
                str(response.get("message", "Unknown error")),
                code=response.get("code"),
            )

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
            cls._raise_command_error(
                str(response.get("message", "Unknown error")),
                code=response.get("code"),
            )

        return response

    @classmethod
    def _raise_command_error(cls, message: str, code: Optional[str] = None) -> NoReturn:
        error_cls = MINEBOT_CODE_TO_EXCEPTION.get(str(code), MineBotCommandError)
        raise error_cls(message, code=str(code) if code else None)

    @staticmethod
    def _distance_to_target(status: dict[str, Any], target_x: float, target_z: float) -> float:
        dx = float(status.get("x", 0.0)) - target_x
        dz = float(status.get("z", 0.0)) - target_z
        return math.hypot(dx, dz)

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
