"""Synchronous tool bodies. Each runs in a worker thread and talks to the robot only through
`RobotSession.call` (one locked SDK request at a time); waits happen between calls, unlocked."""

from __future__ import annotations

import base64
import math
import re
import threading
import time
from typing import Any, Optional

from minebot import MineBotEntityNotFoundError

from .session import ActionError, Cancelled, RobotSession

REACH = 4.0
EYE_HEIGHT = 1.62  # approximate; only used to order candidate faces and for early range checks
MAX_CHAT_WAIT = 300.0
MAX_RAW_MOVE = 10.0
MAX_MOVE_TIMEOUT = 600.0

# Blocks that a placed block simply replaces, and that therefore cannot support a placement.
REPLACEABLE = {
    "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:water", "minecraft:lava",
    "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
    "minecraft:dead_bush", "minecraft:bush", "minecraft:short_dry_grass", "minecraft:tall_dry_grass",
    "minecraft:seagrass", "minecraft:tall_seagrass", "minecraft:vine", "minecraft:glow_lichen",
    "minecraft:snow", "minecraft:fire", "minecraft:soul_fire", "minecraft:light", "minecraft:structure_void",
    "minecraft:bubble_column", "minecraft:leaf_litter", "minecraft:crimson_roots", "minecraft:warped_roots",
    "minecraft:nether_sprouts", "minecraft:hanging_roots",
}
FLUIDS = {"minecraft:water", "minecraft:lava", "minecraft:bubble_column"}

# neighbour offset -> the face of that neighbour that touches the target block
NEIGHBOUR_FACES: list[tuple[tuple[int, int, int], str]] = [
    ((0, -1, 0), "up"),
    ((0, 0, -1), "south"),
    ((0, 0, 1), "north"),
    ((-1, 0, 0), "east"),
    ((1, 0, 0), "west"),
    ((0, 1, 0), "down"),
]


# ---------------------------------------------------------------------- formatting helpers
def norm_id(value: str) -> str:
    """Accept 'oak_log' as shorthand for 'minecraft:oak_log'; tags keep their '#'."""
    value = str(value).strip()
    if not value:
        return value
    tag = value.startswith("#")
    body = value[1:] if tag else value
    if ":" not in body:
        body = f"minecraft:{body}"
    return ("#" if tag else "") + body.lower()


def r1(value: Any) -> Any:
    try:
        return round(float(value), 1)
    except (TypeError, ValueError):
        return value


def facing(yaw: Any) -> str:
    try:
        wrapped = ((float(yaw) + 180.0) % 360.0) - 180.0
    except (TypeError, ValueError):
        return "?"
    if -45.0 <= wrapped < 45.0:
        return "south(+Z)"
    if 45.0 <= wrapped < 135.0:
        return "west(-X)"
    if -135.0 <= wrapped < -45.0:
        return "east(+X)"
    return "north(-Z)"


def pos_of(status: dict[str, Any]) -> dict[str, Any]:
    return {"x": status.get("x"), "y": status.get("y"), "z": status.get("z")}


def pos_text(status: dict[str, Any]) -> str:
    return f"({r1(status.get('x'))}, {r1(status.get('y'))}, {r1(status.get('z'))})"


def parse_pos(value: Any) -> Optional[tuple[int, int, int]]:
    if isinstance(value, str):
        numbers = re.findall(r"-?\d+", value)
        if len(numbers) == 3:
            return (int(numbers[0]), int(numbers[1]), int(numbers[2]))
    return None


def trim_status(status: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {
        "code": status.get("code"),
        "name": status.get("display_name"),
        "dimension": status.get("dimension"),
        "x": status.get("x"),
        "y": status.get("y"),
        "z": status.get("z"),
        "yaw": status.get("yaw"),
        "pitch": status.get("pitch"),
        "facing": facing(status.get("yaw")),
        "health": status.get("health"),
        "max_health": status.get("max_health"),
        "fuel_blaze_powder": status.get("fuel_count"),
        "range_blocks": status.get("stored_range_blocks"),
        "selected_slot": status.get("selected_slot"),
        "selected_item": status.get("selected_item"),
        "look_block": status.get("look_block"),
    }
    if status.get("crouched"):
        out["crouched"] = True
    if status.get("in_vehicle"):
        out["in_vehicle"] = True
    if status.get("in_water"):
        out["in_water"] = True
    submerged = "air" in status and "max_air" in status and int(status["air"]) < int(status["max_air"])
    if submerged:
        out["air_seconds"] = r1(int(status["air"]) / 20.0)
    out["owner"] = status.get("owner_name")
    out["owner_online"] = status.get("owner_online")
    if status.get("moving_to_target"):
        out["moving_to"] = [status.get("move_target_x"), status.get("move_target_y"), status.get("move_target_z")]
    if status.get("moving_by_target"):
        out["moving_by_to"] = [status.get("move_by_target_x"), status.get("move_by_target_y"), status.get("move_by_target_z")]
    if status.get("direct_move_active"):
        out["raw_move_input"] = [status.get("direct_move_x"), status.get("direct_move_z")]
    if status.get("breaking_block"):
        out["mining"] = [status.get("break_target_x"), status.get("break_target_y"), status.get("break_target_z")]
        out["mining_progress"] = r1(status.get("break_progress"))
    if status.get("last_move_known") and not status.get("last_move_success"):
        out["last_move_failed"] = status.get("last_move_message", "")
    if status.get("last_attack_known") and not status.get("last_attack_success"):
        out["last_mine_failed"] = status.get("last_attack_message", "")
    warnings = []
    try:
        if float(status.get("stored_range_blocks", 1.0)) <= 0.0:
            warnings.append("OUT OF ENERGY: most actions fail until blaze powder is added (refuel)")
        elif float(status.get("stored_range_blocks", 1000.0)) < 100.0:
            warnings.append("low energy: refuel soon")
        if submerged:
            warnings.append("head under water: the robot drowns when air runs out; jump to surface")
        if float(status.get("health", 20.0)) <= 6.0:
            warnings.append("low health: robots do not regenerate; avoid damage")
    except (TypeError, ValueError):
        pass
    if warnings:
        out["warnings"] = warnings
    return out


def crosshair(target: dict[str, Any]) -> dict[str, Any]:
    """Compact camera inspect result."""
    out: dict[str, Any] = {"block": target.get("block", "minecraft:air")}
    if "x" in target:
        out.update(x=target.get("x"), y=target.get("y"), z=target.get("z"), face=target.get("face"))
    out["distance"] = r1(target.get("distance"))
    if "in_reach" in target:
        out["in_reach"] = target.get("in_reach")
    if out["block"] == "minecraft:air" and "x" not in target:
        out = {"block": "nothing within 50 blocks"}
    if "entity" in target:
        out["entity"] = {
            "type": target.get("entity"),
            "entity_id": target.get("entity_id"),
            "name": target.get("entity_name"),
            "distance": r1(target.get("entity_distance")),
            "in_reach": target.get("entity_in_reach"),
        }
    return out


def trim_entity(entity: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {
        "entity_id": entity.get("entity_id"),
        "type": entity.get("type"),
        "name": entity.get("name"),
        "category": entity.get("category"),
        "x": r1(entity.get("x")),
        "y": r1(entity.get("y")),
        "z": r1(entity.get("z")),
        "distance": r1(entity.get("distance")),
    }
    if "health" in entity:
        out["health"] = r1(entity.get("health"))
        out["max_health"] = r1(entity.get("max_health"))
    if "item" in entity:
        out["item"] = entity.get("item")
        out["count"] = entity.get("count")
    if entity.get("line_of_sight") is False:
        out["behind_cover"] = True
    return out


def trim_message(message: dict[str, Any], robot_dimension: Optional[str]) -> dict[str, Any]:
    out: dict[str, Any] = {
        "id": message.get("id"),
        "from": message.get("sender"),
        "text": message.get("text"),
        "to": message.get("address"),
        "age_s": r1(message.get("age_seconds")),
    }
    if message.get("sender_type") not in (None, "player"):
        out["sender_type"] = message.get("sender_type")
    if "sender_x" in message:
        out["sender_pos"] = [r1(message.get("sender_x")), r1(message.get("sender_y")), r1(message.get("sender_z"))]
    if "distance" in message:
        out["distance"] = r1(message.get("distance"))
    dimension = message.get("sender_dimension")
    if dimension and dimension != robot_dimension:
        out["sender_dimension"] = dimension
    return out


# ---------------------------------------------------------------------- actions
class Actions:
    def __init__(self, session: RobotSession) -> None:
        self.s = session

    # -- small helpers -------------------------------------------------------------------
    def _status(self) -> dict[str, Any]:
        return self.s.call(lambda r: r.status())

    def _inspect(self) -> dict[str, Any]:
        return self.s.call(lambda r: r.camera.inspect())

    def _cmd(self, action: str, **payload: Any) -> dict[str, Any]:
        return self.s.call(lambda r: r.command(action, **payload))

    def _stop_quietly(self) -> None:
        try:
            self.s.call(lambda r: r.stop(), auto_connect=False)
        except Exception:
            pass

    @staticmethod
    def _eye(status: dict[str, Any]) -> tuple[float, float, float]:
        return (float(status["x"]), float(status["y"]) + EYE_HEIGHT, float(status["z"]))

    # -- session ----------------------------------------------------------------------------
    def list_robots(self) -> dict[str, Any]:
        robots = self.s.list_robots()
        rows = []
        for robot in robots:
            row = {
                "code": robot.get("code"),
                "name": robot.get("display_name"),
                "connected": robot.get("connected"),
                "evil": robot.get("evil"),
                "owner": robot.get("owner_name"),
                "owner_online": robot.get("owner_online"),
                "dimension": robot.get("dimension"),
                "x": r1(robot.get("x")),
                "y": r1(robot.get("y")),
                "z": r1(robot.get("z")),
            }
            if self.s.code and str(robot.get("code", "")).upper() == self.s.code and self.s.is_attached():
                row["this_session"] = True
            rows.append(row)
        return {"url": self.s.url, "robots": rows}

    def connect(self, code: Optional[str], url: Optional[str]) -> dict[str, Any]:
        _, status = self.s.connect(code, url)
        return {"connected": True, **trim_status(status)}

    def disconnect(self) -> str:
        code = self.s.disconnect()
        if code is None:
            return "No robot was connected."
        return f"Disconnected from robot {code}. The next robot tool call will auto-connect again."

    def status(self) -> dict[str, Any]:
        return trim_status(self._status())

    def turn_evil(self) -> str:
        code = self.s.turn_evil()
        return (
            f"Robot {code} broke free and turned evil. It now hunts the nearest player until it is killed, and "
            "it will never take commands or chat again. This chat has no robot now: the robot tools report "
            "not_connected until you connect() to another one."
        )

    # -- movement ---------------------------------------------------------------------------
    def _wait_motion(
        self,
        cancel: threading.Event,
        flag: str,
        target: tuple[float, float],
        timeout: float,
        tolerance: float = 0.75,
    ) -> dict[str, Any]:
        started = time.monotonic()
        deadline = started + timeout
        try:
            while True:
                self.s.sleep(self.s.settings.poll_interval, cancel)
                status = self._status()
                if not status.get(flag):
                    break
                if time.monotonic() >= deadline:
                    self._stop_quietly()
                    status = self._status()
                    distance = math.hypot(float(status["x"]) - target[0], float(status["z"]) - target[1])
                    raise ActionError(
                        "timeout",
                        f"Still moving after {timeout:g} s; stopped the robot at {pos_text(status)}, "
                        f"{distance:.1f} blocks from the target. Call again (with a larger timeout) to continue.",
                    )
        except Cancelled:
            self._stop_quietly()
            raise
        distance = math.hypot(float(status["x"]) - target[0], float(status["z"]) - target[1])
        if status.get("last_move_known") and not status.get("last_move_success"):
            raise ActionError(
                "movement_failed",
                f"{status.get('last_move_message') or 'Could not reach the target'}. Robot is at {pos_text(status)}, "
                f"{distance:.1f} blocks from the target.",
            )
        if distance > tolerance:
            raise ActionError(
                "movement_failed",
                f"{status.get('last_move_message') or 'Stopped before reaching the target'}. Robot is at "
                f"{pos_text(status)}, {distance:.1f} blocks from the target.",
            )
        return {"arrived": True, **pos_of(status), "elapsed_s": r1(time.monotonic() - started)}

    def move_to(self, cancel: threading.Event, x: float, z: float, y: Optional[float], speed: float, timeout: float) -> dict[str, Any]:
        payload: dict[str, Any] = {"x": round(float(x), 3), "z": round(float(z), 3), "speed": float(speed)}
        if y is not None:
            payload["y"] = round(float(y), 3)
        result = self._cmd("move_to", **payload)
        if result.get("arrived"):
            return {"arrived": True, "x": result.get("x"), "y": result.get("y"), "z": result.get("z"), "elapsed_s": 0.0}
        target = (float(result.get("target_x", x)), float(result.get("target_z", z)))
        timeout = min(max(1.0, float(timeout)), MAX_MOVE_TIMEOUT)
        return self._wait_motion(cancel, "moving_to_target", target, timeout)

    def move_by(self, cancel: threading.Event, forward: float, right: float, speed: float, timeout: float) -> dict[str, Any]:
        result = self._cmd("move_by", x=round(float(forward), 3), z=round(float(right), 3), speed=float(speed))
        if result.get("arrived"):
            return {"arrived": True, "x": result.get("x"), "y": result.get("y"), "z": result.get("z"), "elapsed_s": 0.0}
        target = (float(result["target_x"]), float(result["target_z"]))
        timeout = min(max(1.0, float(timeout)), MAX_MOVE_TIMEOUT)
        return self._wait_motion(cancel, "moving_by_target", target, timeout)

    def move(self, cancel: threading.Event, forward: float, right: float, duration: float) -> dict[str, Any]:
        duration = min(max(0.0, float(duration)), MAX_RAW_MOVE)
        forward = max(-1.0, min(1.0, float(forward)))
        right = max(-1.0, min(1.0, float(right)))
        self._cmd("move", x=forward, z=right)
        try:
            self.s.sleep(duration, cancel)
        finally:
            try:
                self.s.call(lambda r: r.command("move", x=0.0, z=0.0), auto_connect=False)
            except Exception:
                pass
        status = self._status()
        return {"moved_for_s": duration, **pos_of(status), "yaw": status.get("yaw")}

    def turn_to(self, yaw: Optional[float], pitch: Optional[float]) -> dict[str, Any]:
        if yaw is None and pitch is None:
            raise ActionError("invalid_request", "Give yaw and/or pitch.")
        result = self.s.call(lambda r: r.turn_to(yaw=yaw, pitch=pitch))
        return self._after_turn(result)

    def turn_by(self, yaw: float, pitch: float) -> dict[str, Any]:
        result = self.s.call(lambda r: r.turn_by(yaw=yaw, pitch=pitch))
        return self._after_turn(result)

    def _after_turn(self, result: dict[str, Any]) -> dict[str, Any]:
        return {
            "yaw": result.get("yaw"),
            "pitch": result.get("pitch"),
            "facing": facing(result.get("yaw")),
            "crosshair": crosshair(self._inspect()),
        }

    def look_at(self, x: float, y: float, z: float) -> dict[str, Any]:
        result = self.s.call(lambda r: r.look_at(x=x, y=y, z=z))
        return self._after_turn(result)

    def look_at_entity(self, entity_id: int) -> dict[str, Any]:
        result = self.s.call(lambda r: r.look_at(entity_id=entity_id))
        return self._after_turn(result)

    def simple(self, action: str, **payload: Any) -> dict[str, Any]:
        return self._cmd(action, **payload)

    def crouch(self, enabled: bool) -> dict[str, Any]:
        return self._cmd("crouch" if enabled else "uncrouch")

    def go_to_player(self, cancel: threading.Event, name: str, distance: float, timeout: float) -> dict[str, Any]:
        scan = self.s.call(lambda r: r.scan_entities(radius=64.0, players_only=True, limit=128))
        players = scan.get("entities") or []
        wanted = name.strip().lower()
        match = next((p for p in players if str(p.get("name", "")).lower() == wanted), None)
        if match is None:
            partial = [p for p in players if wanted in str(p.get("name", "")).lower()]
            match = partial[0] if len(partial) == 1 else None
        if match is None:
            seen = ", ".join(f"{p.get('name')} ({r1(p.get('distance'))} blocks)" for p in players) or "none"
            raise ActionError(
                "player_not_found",
                f"No player named {name!r} within 64 blocks of the robot (players in range: {seen}). If they wrote in "
                "chat, move_to their sender_pos instead.",
            )
        status = self._status()
        px, py, pz = float(match["x"]), float(match["y"]), float(match["z"])
        rx, rz = float(status["x"]), float(status["z"])
        horizontal = math.hypot(rx - px, rz - pz)
        distance = max(0.0, float(distance))
        moved: Optional[dict[str, Any]] = None
        if horizontal > distance + 0.75:
            tx = px + (rx - px) / horizontal * distance
            tz = pz + (rz - pz) / horizontal * distance
            moved = self.move_to(cancel, tx, tz, py, 1.0, timeout)
        try:
            self.s.call(lambda r: r.look_at(entity_id=int(match["entity_id"])))
        except MineBotEntityNotFoundError:
            pass
        status = self._status()
        return {
            "player": match.get("name"),
            "player_pos": [r1(px), r1(py), r1(pz)],
            "moved": moved is not None,
            **pos_of(status),
            "distance_to_player": r1(math.dist((float(status["x"]), float(status["y"]), float(status["z"])), (px, py, pz))),
        }

    # -- mining -----------------------------------------------------------------------------
    def mine(self, cancel: threading.Event) -> dict[str, Any]:
        started = self._cmd("attack")
        eta = float(started.get("eta_ticks", 0) or 0) / 20.0
        timeout = min(max(10.0, eta * 1.5 + 5.0), MAX_MOVE_TIMEOUT)
        deadline = time.monotonic() + timeout
        try:
            while True:
                self.s.sleep(self.s.settings.poll_interval, cancel)
                status = self._status()
                if not status.get("breaking_block"):
                    break
                if time.monotonic() >= deadline:
                    self._stop_quietly()
                    raise ActionError("timeout", f"Mining {started.get('block')} did not finish within {timeout:g} s; stopped.")
        except Cancelled:
            self._stop_quietly()
            raise
        pos = parse_pos(started.get("pos"))
        where = f"({pos[0]}, {pos[1]}, {pos[2]})" if pos else str(started.get("pos"))
        if status.get("last_attack_known") and status.get("last_attack_success"):
            out: dict[str, Any] = {"broken": True, "block": started.get("block")}
            if pos:
                out.update(x=pos[0], y=pos[1], z=pos[2])
            out["note"] = "Drops scatter up to ~2 blocks and are only picked up within about a block: call collect_items."
            return out
        reason = status.get("last_attack_message") or "mining was interrupted"
        raise ActionError("mining_failed", f"Could not break {started.get('block')} at {where}: {reason}")

    def mine_block(self, cancel: threading.Event, x: int, y: int, z: int) -> dict[str, Any]:
        target = (int(x), int(y), int(z))
        centre = (target[0] + 0.5, target[1] + 0.5, target[2] + 0.5)
        status = self._status()
        eye_distance = math.dist(self._eye(status), centre)
        if eye_distance > REACH + 2.0:
            raise ActionError(
                "out_of_reach",
                f"Block ({x}, {y}, {z}) is {eye_distance:.1f} blocks from the robot's eyes; reach is {REACH:g}. "
                f"Move closer first (robot is at {pos_text(status)}).",
            )
        self.s.call(lambda r: r.look_at(x=centre[0], y=centre[1], z=centre[2]))
        hit = self._inspect()
        if "x" not in hit:
            if hit.get("block", "minecraft:air") == "minecraft:air":
                raise ActionError("target_empty", f"The crosshair hits nothing when aimed at ({x}, {y}, {z}); it is air.")
            raise ActionError("unsupported", "The mod did not report the crosshair block position; update the MineBot mod.")
        hit_pos = (int(hit["x"]), int(hit["y"]), int(hit["z"]))
        if hit_pos != target:
            if float(hit.get("distance", 0.0)) > eye_distance + 0.5:
                raise ActionError(
                    "target_empty",
                    f"({x}, {y}, {z}) is air or has no hitbox: the crosshair passed through it and hit "
                    f"{hit.get('block')} at {hit_pos}.",
                )
            raise ActionError(
                "obstructed",
                f"The view of ({x}, {y}, {z}) is blocked by {hit.get('block')} at {hit_pos}, "
                f"{r1(hit.get('distance'))} blocks away. Mine that block first or move for a clear line of sight.",
            )
        if hit.get("block") in FLUIDS:
            raise ActionError("wrong_block", f"({x}, {y}, {z}) is {hit.get('block')}; fluids cannot be mined.")
        if hit.get("in_reach") is False:
            raise ActionError(
                "out_of_reach",
                f"{hit.get('block')} at ({x}, {y}, {z}) is {r1(hit.get('distance'))} blocks away; reach is {REACH:g}. "
                "Move closer first.",
            )
        return self.mine(cancel)

    def collect_items(self, cancel: threading.Event, radius: float, item: Optional[str]) -> dict[str, Any]:
        radius = min(max(1.0, float(radius)), 16.0)
        wanted = norm_id(item) if item else None
        before = self._item_counts()
        skipped: set[int] = set()
        unreachable: list[str] = []
        for _ in range(12):
            scan = self.s.call(lambda r: r.scan_entities(radius=radius, types=["minecraft:item"], limit=32))
            drops = [
                e for e in scan.get("entities") or []
                if int(e.get("entity_id", -1)) not in skipped and (wanted is None or e.get("item") == wanted)
            ]
            if not drops:
                break
            drop = drops[0]
            skipped.add(int(drop["entity_id"]))
            try:
                self.move_to(cancel, float(drop["x"]), float(drop["z"]), float(drop["y"]), 1.0, 20.0)
            except ActionError as exc:
                unreachable.append(f"{drop.get('item')} at ({r1(drop.get('x'))}, {r1(drop.get('y'))}, {r1(drop.get('z'))}): {exc.message}")
                continue
            self.s.sleep(0.6, cancel)
        after = self._item_counts()
        collected = {name: count - before.get(name, 0) for name, count in after.items() if count > before.get(name, 0)}
        scan = self.s.call(lambda r: r.scan_entities(radius=radius, types=["minecraft:item"], limit=32))
        left = [e for e in scan.get("entities") or [] if wanted is None or e.get("item") == wanted]
        out: dict[str, Any] = {"collected": collected, "drops_left": len(left)}
        if left:
            inventory = self.s.call(lambda r: r.inventory())
            if int(inventory.get("slots_used", 0)) >= int(inventory.get("slots_total", 10)):
                out["note"] = "The hotbar has no free slot; drops that do not stack onto held items stay on the ground."
        if unreachable:
            out["unreachable"] = unreachable
        return out

    def _item_counts(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for slot in self.s.call(lambda r: r.inventory()).get("slots") or []:
            if int(slot.get("count", 0)) > 0:
                counts[str(slot.get("item"))] = counts.get(str(slot.get("item")), 0) + int(slot["count"])
        return counts

    # -- placing ----------------------------------------------------------------------------
    def place_block(self, x: int, y: int, z: int, item: Optional[str]) -> dict[str, Any]:
        target = (int(x), int(y), int(z))
        centre = (target[0] + 0.5, target[1] + 0.5, target[2] + 0.5)
        status = self._status()
        eye = self._eye(status)
        feet = (math.floor(float(status["x"])), math.floor(float(status["y"])), math.floor(float(status["z"])))
        if target in (feet, (feet[0], feet[1] + 1, feet[2])):
            raise ActionError("invalid_request", f"The robot itself occupies ({x}, {y}, {z}); move away first.")
        if math.dist(eye, centre) > REACH + 2.0:
            raise ActionError(
                "out_of_reach",
                f"({x}, {y}, {z}) is {math.dist(eye, centre):.1f} blocks from the robot's eyes; reach is {REACH:g}. "
                f"Move closer first (robot is at {pos_text(status)}).",
            )

        inventory = self.s.call(lambda r: r.inventory())
        slots = inventory.get("slots") or []
        if item:
            wanted = norm_id(item)
            slot = next((s for s in slots if s.get("item") == wanted and int(s.get("count", 0)) > 0), None)
            if slot is None:
                raise ActionError("missing_item", f"No {wanted} in the hotbar. Hotbar: {_hotbar_summary(slots)}.")
            if int(slot["slot"]) != int(inventory.get("selected_slot", -1)):
                self.s.call(lambda r: r.select_slot(int(slot["slot"])))
            placing = wanted
        else:
            selected = int(inventory.get("selected_slot", 0))
            slot = next((s for s in slots if int(s.get("slot", -1)) == selected), None)
            if slot is None or int(slot.get("count", 0)) <= 0:
                raise ActionError("missing_item", "The selected hotbar slot is empty; pass item=... or equip a block first.")
            placing = str(slot.get("item"))

        scan = self.s.call(lambda r: r.scan_blocks(radius=1, limit=27, center=target))
        blocks = {(int(m["x"]), int(m["y"]), int(m["z"])): str(m["block"]) for m in scan.get("matches") or []}
        target_block = blocks.get(target)
        if target_block is not None and target_block not in REPLACEABLE:
            raise ActionError(
                "target_occupied",
                f"({x}, {y}, {z}) already contains {target_block}. Mine it first (mine_block) or choose another spot.",
            )

        candidates = []
        for offset, face in NEIGHBOUR_FACES:
            neighbour = (target[0] + offset[0], target[1] + offset[1], target[2] + offset[2])
            neighbour_block = blocks.get(neighbour)
            if neighbour_block is None or neighbour_block in REPLACEABLE:
                continue
            face_point = (centre[0] + offset[0] * 0.5, centre[1] + offset[1] * 0.5, centre[2] + offset[2] * 0.5)
            # The face is visible when the eye is on the target's side of the face plane.
            facing_eye = sum((eye[i] - face_point[i]) * -offset[i] for i in range(3)) > 0.05
            candidates.append((not facing_eye, math.dist(eye, face_point), offset, face, neighbour, neighbour_block, face_point))
        if not candidates:
            raise ActionError(
                "no_support",
                f"The robot sees no solid block next to ({x}, {y}, {z}) to place against. Place a supporting block "
                "next to it first (for example directly below), or move to where the support is in view.",
            )
        candidates.sort(key=lambda c: (c[0], c[1]))

        attempts = []
        out_of_reach = 0
        for _, _, offset, face, neighbour, neighbour_block, face_point in candidates:
            aim = tuple(face_point[i] + offset[i] * 0.02 for i in range(3))
            self.s.call(lambda r: r.look_at(x=aim[0], y=aim[1], z=aim[2]))
            hit = self._inspect()
            hit_pos = (hit.get("x"), hit.get("y"), hit.get("z"))
            on_face = hit_pos == neighbour and hit.get("face") == face
            on_replaceable_target = hit_pos == target and target_block in REPLACEABLE
            if not (on_face or on_replaceable_target):
                attempts.append(f"{face} face of {neighbour_block} at {neighbour}: crosshair hit {hit.get('block')} at {hit_pos} ({hit.get('face')})")
                continue
            if hit.get("in_reach") is False or (on_replaceable_target and float(hit.get("distance", 0)) > REACH):
                out_of_reach += 1
                attempts.append(f"{face} face of {neighbour_block} at {neighbour}: {r1(hit.get('distance'))} blocks, out of reach")
                continue
            result = self.s.call(lambda r: r.place())
            placed_pos = parse_pos(result.get("pos"))
            out: dict[str, Any] = {
                "placed": result.get("placed") or result.get("used") or placing,
                "x": target[0],
                "y": target[1],
                "z": target[2],
                "against": f"{face} face of {neighbour_block}",
            }
            if result.get("no_action"):
                raise ActionError("place_failed", f"Nothing happened: {result}")
            if placed_pos is not None and placed_pos != target:
                out["warning"] = f"The block ended up at {placed_pos}, not at the requested position."
                out["x"], out["y"], out["z"] = placed_pos
            return out
        code = "out_of_reach" if out_of_reach == len(candidates) else "obstructed"
        raise ActionError(
            code,
            f"Could not aim at a supporting face for ({x}, {y}, {z}) from {pos_text(status)}. Tried: "
            + "; ".join(attempts)
            + ". Move so the spot is in clear view within 4 blocks.",
        )

    # -- entity interaction ------------------------------------------------------------------
    def entity_action(self, action: str, entity_id: Optional[int]) -> dict[str, Any]:
        if entity_id is not None:
            self.s.call(lambda r: r.look_at(entity_id=int(entity_id)))
        return self._cmd(action)

    # -- inventory ---------------------------------------------------------------------------
    def inventory(self) -> dict[str, Any]:
        inv = self.s.call(lambda r: r.inventory())
        slots = []
        empty = []
        for slot in inv.get("slots") or []:
            if int(slot.get("count", 0)) <= 0 or slot.get("item") == "minecraft:air":
                empty.append(slot.get("slot"))
                continue
            row: dict[str, Any] = {"slot": slot.get("slot"), "item": slot.get("item"), "count": slot.get("count")}
            if "max_damage" in slot:
                row["durability"] = f"{int(slot['max_damage']) - int(slot.get('damage', 0))}/{slot['max_damage']}"
            slots.append(row)
        return {
            "selected_slot": inv.get("selected_slot"),
            "slots": slots,
            "empty_slots": empty,
            "fuel_item": inv.get("fuel_item"),
            "fuel_count": inv.get("fuel_count"),
            "range_blocks": inv.get("stored_range_blocks"),
        }

    def select_slot(self, slot: int) -> dict[str, Any]:
        if not 0 <= int(slot) <= 9:
            raise ActionError("invalid_request", "slot must be 0..9")
        return self.s.call(lambda r: r.select_slot(int(slot)))

    def equip(self, item: str) -> dict[str, Any]:
        wanted = norm_id(item)
        inv = self.s.call(lambda r: r.inventory())
        slots = inv.get("slots") or []
        matches = [s for s in slots if s.get("item") == wanted and int(s.get("count", 0)) > 0]
        if not matches:
            raise ActionError("missing_item", f"No {wanted} in the hotbar. Hotbar: {_hotbar_summary(slots)}.")
        slot = max(matches, key=lambda s: int(s.get("count", 0)))
        return self.s.call(lambda r: r.select_slot(int(slot["slot"])))

    def drop(self, slot: Optional[int], count: Optional[int]) -> dict[str, Any]:
        payload: dict[str, Any] = {}
        if slot is not None:
            payload["slot"] = int(slot)
        if count is not None:
            payload["count"] = int(count)
        return self._cmd("drop", **payload)

    def furnace_take(self, item: Optional[str], count: Optional[int], fuel_item: Optional[str], fuel_count: int) -> dict[str, Any]:
        payload: dict[str, Any] = {}
        if item is None and fuel_item is None or (item is not None and count is None):
            furnace = self._cmd("furnace_inspect")
            output = furnace.get("output") or {}
            input_slot = furnace.get("input") or {}
            if item is None:
                if int(output.get("count", 0)) <= 0:
                    raise ActionError("target_empty", f"The furnace output is empty. Furnace: {_furnace_summary(furnace)}.")
                item, count = str(output.get("item")), int(output.get("count", 1))
            else:
                wanted = norm_id(item)
                source = output if output.get("item") == wanted else input_slot
                count = int(source.get("count", 0)) if source.get("item") == wanted else 1
                count = max(count, 1)
        if item is not None:
            payload["food"] = norm_id(item)
            payload["food_count"] = int(count or 1)
        if fuel_item is not None:
            payload["fuel"] = norm_id(fuel_item)
            payload["fuel_count"] = int(fuel_count)
        return self._cmd("furnace_take", **payload)

    def furnace_put(self, input_item: Optional[str], input_count: int, fuel_item: Optional[str], fuel_count: int) -> dict[str, Any]:
        if input_item is None and fuel_item is None:
            raise ActionError("invalid_request", "Give input_item and/or fuel_item.")
        payload: dict[str, Any] = {}
        if input_item is not None:
            payload["food"] = norm_id(input_item)
            payload["food_count"] = int(input_count)
        if fuel_item is not None:
            payload["fuel"] = norm_id(fuel_item)
            payload["fuel_count"] = int(fuel_count)
        return self._cmd("furnace_place", **payload)

    # -- camera / perception -----------------------------------------------------------------
    def inspect(self) -> dict[str, Any]:
        return crosshair(self._inspect())

    def snapshot(self, source: str = "render") -> tuple[bytes, str]:
        result = self._cmd("camera_snapshot", source=source)
        data = base64.b64decode(str(result.get("data_base64", "")))
        if not data:
            raise ActionError("camera_error", "The camera returned no image data.")
        status = self.s.call(lambda r: r.last_status())
        caption = (
            f"Robot camera view, {result.get('width')}x{result.get('height')} PNG, facing {facing(status.get('yaw'))} "
            f"(yaw {status.get('yaw')}, pitch {status.get('pitch')}) from {pos_text(status)}."
        )
        if result.get("source", source) == "render":
            caption += (
                " Drawn by the server from what the robot sees. Players, robots, common mobs and items stand"
                " still; other entities are boxes: red hostile, green animal, brown vehicle, white other."
                " + marks the crosshair."
            )
        return data, caption

    def scan_blocks(
        self,
        radius: int,
        blocks: Optional[list[str]],
        limit: int,
        center: Optional[tuple[int, int, int]],
    ) -> dict[str, Any]:
        filters = [norm_id(b) for b in blocks] if blocks else None
        scan = self.s.call(
            lambda r: r.scan_blocks(radius=radius, blocks=filters, limit=limit, center=center)
        )
        rows = [
            [m.get("block"), m.get("x"), m.get("y"), m.get("z"), r1(m.get("distance"))]
            for m in scan.get("matches") or []
        ]
        return {
            "origin": [scan.get("origin_x"), scan.get("origin_y"), scan.get("origin_z")],
            "radius": scan.get("radius"),
            "total_matches": scan.get("total_matches"),
            "truncated": scan.get("truncated"),
            "counts": scan.get("counts"),
            "matches[block,x,y,z,distance]": rows,
        }

    def scan_entities(self, radius: float, types: Optional[list[str]], players_only: bool, limit: int) -> dict[str, Any]:
        filters = [norm_id(t) for t in types] if types else None
        scan = self.s.call(lambda r: r.scan_entities(radius=radius, types=filters, players_only=players_only, limit=limit))
        return {
            "radius": scan.get("radius"),
            "total": scan.get("total"),
            "truncated": scan.get("truncated"),
            "entities": [trim_entity(e) for e in scan.get("entities") or []],
        }

    def nearby_players(self, radius: float) -> dict[str, Any]:
        scan = self.s.call(lambda r: r.scan_entities(radius=radius, players_only=True, limit=64))
        players = []
        for entity in scan.get("entities") or []:
            row = trim_entity(entity)
            row.pop("category", None)
            row.pop("type", None)
            players.append(row)
        return {"radius": scan.get("radius"), "players": players}

    # -- chat ---------------------------------------------------------------------------------
    def _chat_result(self, result: dict[str, Any]) -> dict[str, Any]:
        dimension = self.s.call(lambda r: r.last_status()).get("dimension")
        out: dict[str, Any] = {"messages": [trim_message(m, dimension) for m in result.get("messages") or []]}
        if result.get("remaining"):
            out["remaining"] = result.get("remaining")
        if result.get("dropped"):
            out["dropped"] = result.get("dropped")
        return out

    def wait_for_chat(self, cancel: threading.Event, timeout: float) -> Any:
        timeout = min(max(0.0, float(timeout)), MAX_CHAT_WAIT)
        deadline = time.monotonic() + timeout
        while True:
            result = self.s.call(lambda r: r.read_chat())
            if result.get("messages"):
                return self._chat_result(result)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return (
                    f"No new chat messages in {timeout:g} s. This is normal: call wait_for_chat again to keep "
                    "listening."
                )
            self.s.sleep(min(self.s.settings.chat_poll_interval, remaining), cancel)

    def read_chat(self, peek: bool) -> Any:
        result = self.s.call(lambda r: r.read_chat(peek=peek))
        if not result.get("messages"):
            return "No unread chat messages."
        return self._chat_result(result)

    def say(self, message: str, to: Optional[str]) -> dict[str, Any]:
        if not str(message).strip():
            raise ActionError("invalid_request", "message must not be empty")
        return self.s.call(lambda r: r.say(message, to=to))


def _hotbar_summary(slots: list[dict[str, Any]]) -> str:
    used = [f"{s.get('slot')}:{s.get('item')} x{s.get('count')}" for s in slots if int(s.get("count", 0)) > 0]
    return ", ".join(used) or "empty"


def _furnace_summary(furnace: dict[str, Any]) -> str:
    parts = []
    for key in ("input", "fuel", "output"):
        slot = furnace.get(key) or {}
        parts.append(f"{key}={slot.get('item')} x{slot.get('count', 0)}")
    return ", ".join(parts)


__all__ = ["Actions", "ActionError", "Cancelled", "norm_id"]
