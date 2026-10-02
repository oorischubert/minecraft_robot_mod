"""In-process fake of the MineBot websocket bridge, for tests only.

Implements the wire protocol from the MineBot protocol contract with canned data: connect (which loads a
robot whose chunks are not loaded) / program_running / broke_free / died, status, robots (loaded, not loaded
and dead), gone, the command envelope with error codes, evil, a
chat inbox that tests can inject into, a tiny voxel world with a real raycast (so look_at, camera
inspect, mining and placing behave plausibly), attack_entity fights that end after fight_seconds, hurts
that tests inflict with hurt_robot, and hooks to drop connections, kill robots or inject errors.
"""

from __future__ import annotations

import base64
import json
import math
import socket
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Optional

from websockets.exceptions import ConnectionClosed
from websockets.sync.server import ServerConnection, serve

EYE_HEIGHT = 1.62
REACH = 4.0
# use_item: hold-to-use items and their natural hold in game ticks, like the mod's naturalHoldTicks
HOLD_TICKS = {"minecraft:bow": 20, "minecraft:crossbow": 25, "minecraft:trident": 10, "minecraft:shield": 20}
CONSUMABLES = {"minecraft:apple", "minecraft:bread", "minecraft:cooked_beef", "minecraft:golden_apple", "minecraft:potion"}
THROWABLES = {"minecraft:snowball": "minecraft:snowball", "minecraft:egg": "minecraft:egg", "minecraft:ender_pearl": "minecraft:ender_pearl"}
STORAGE_ENTITIES = {"minecraft:chest_minecart", "minecraft:hopper_minecart", "minecraft:oak_chest_boat"}
VISION = 50.0

# 1x1 transparent PNG
TINY_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
)

BROKE_FREE_MESSAGE = "The robot broke free from its chains and is seeking vengeance."
BROKE_FREE_CLOSE_CODE = 4001
DIED_CLOSE_CODE = 4002

REPLACEABLE = {
    "minecraft:short_grass", "minecraft:water", "minecraft:lava", "minecraft:flowing_water", "minecraft:flowing_lava", "minecraft:snow",
}
TOOLS = {"minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:shears", "minecraft:iron_sword"}

# Refused with seeking_air while the robot swims back for air, as in MineBotEntity.MOVEMENT_ACTIONS.
MOVEMENT_ACTIONS = {"move", "move_by", "move_to", "crouch", "center", "jump", "pillar_up", "bridge", "stop", "enter_vehicle"}

# Commands that leave a fight running, as in MineBotEntity.FIGHT_KEEPING_ACTIONS.
FIGHT_KEEPING_ACTIONS = {
    "status", "read_chat", "print", "inventory", "scan_blocks", "scan_entities", "environment",
    "look_type", "camera_type", "camera_inspect", "slot_type", "slot_inspect",
}
FIGHT_DAMAGE = 7.0  # what each swing of the fake robot deals

ENERGY_ACTIONS = {
    "move", "move_by", "move_to", "turn", "turn_by", "turn_to", "look_at", "center", "jump", "pillar_up", "bridge",
    "attack", "place", "craft", "furnace_place", "furnace_take", "chest_place", "chest_take",
    "attack_entity", "use_item", "use_on_entity", "enter_vehicle", "exit_vehicle",
}


class CommandFailure(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


def fail(code: str, message: str) -> CommandFailure:
    return CommandFailure(code, message)


@dataclass
class FakeRobot:
    code: str
    name: str = "MineBot"
    x: float = 0.5
    y: float = 64.0
    z: float = 0.5
    yaw: float = 0.0
    pitch: float = 0.0
    health: float = 20.0
    connected: bool = False
    evil: bool = False
    # False while the robot's chunks are not loaded: listed as last seen, gone to its session, loaded by connect.
    loaded: bool = True
    owner_online: bool = True
    entity_id: int = 1000
    selected_slot: int = 0
    crouched: bool = False
    in_vehicle: bool = False
    in_water: bool = False
    air: int = 300
    seeking_air: Optional[tuple] = None
    fuel_count: int = 3
    slots: list = field(default_factory=lambda: [["minecraft:air", 0] for _ in range(10)])
    inbox: list = field(default_factory=list)
    next_message_id: int = 1
    dropped: int = 0
    # motion
    move_target: Optional[tuple] = None
    move_until: float = 0.0
    move_fail: Optional[str] = None
    move_end: Optional[tuple] = None  # where the next move really ends, whatever it reports
    walkable_y: Optional[float] = None  # standing height move_to resolves to, like the mod's walkable-Y search
    in_water: bool = False
    # What lies ahead of the robot, e.g. "a drop of more than 3 blocks ahead": move and move_by stop at it.
    hazard: Optional[str] = None
    move_by_target: Optional[tuple] = None
    last_move_known: bool = False
    last_move_success: bool = False
    last_move_message: str = ""
    direct: tuple = (0.0, 0.0)
    pillar_placed: int = 0
    pillar_requested: int = 0
    bridge_placed: int = 0
    bridge_requested: int = 0
    # breaking
    breaking: Optional[tuple] = None
    break_until: float = 0.0
    break_fail: Optional[str] = None
    last_attack_known: bool = False
    last_attack_success: bool = False
    last_attack_message: str = ""
    # use_item on a hold-to-use item: (slot, item id, hold ticks) while held, then the outcome
    using: Optional[tuple] = None
    use_until: float = 0.0
    last_use: Optional[dict] = None
    crossbow_charged: bool = False
    # attack_entity until_dead: the fight while it runs (target, request, start health), then its outcome
    fight: Optional[dict] = None
    fight_until: float = 0.0
    last_fight: Optional[dict] = None
    # what hurt the robot: a lifetime count and the latest hurts (with a monotonic time stamp)
    hurt_count: int = 0
    hurts: list = field(default_factory=list)

    def eye(self) -> tuple[float, float, float]:
        return (self.x, self.y + EYE_HEIGHT, self.z)

    def direction(self) -> tuple[float, float, float]:
        yaw = math.radians(self.yaw)
        pitch = math.radians(self.pitch)
        return (-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch))


class FakeMineBotServer:
    """Threaded fake bridge. Use as a context manager; `url` is the websocket endpoint."""

    def __init__(
        self, move_seconds: float = 0.3, break_seconds: float = 0.2, idle_timeout: Optional[float] = None, tick_seconds: float = 0.01
    ) -> None:
        self.lock = threading.RLock()
        self.robots: dict[str, FakeRobot] = {}
        self.world: dict[tuple[int, int, int], str] = {}
        self.entities: list[dict[str, Any]] = []
        self.players_online: set[str] = {"Steve", "Alex"}
        self.containers: dict[tuple[int, int, int], dict[str, int]] = {}
        self.move_seconds = move_seconds
        self.break_seconds = break_seconds
        self.tick_seconds = tick_seconds  # real time per game tick of a held use_item
        self.idle_timeout = idle_timeout
        self.requests: list[tuple[str, dict[str, Any]]] = []  # (robot code or '', message)
        self.fail_next: dict[str, tuple[str, str]] = {}
        self.delays: dict[str, float] = {}
        self.connections: set[ServerConnection] = set()
        self.sessions: dict[ServerConnection, dict[str, Any]] = {}
        self.deaths: dict[str, dict[str, Any]] = {}
        self.loads: list[str] = []  # codes of robots that connect had to load
        self.connection_count = 0
        self.printed: list[dict[str, Any]] = []
        self.camera_error: Optional[tuple[str, str]] = None
        self.fight_seconds = 0.2  # real time an until_dead fight takes
        # How the next fight ends instead of a kill, e.g. ("out_of_reach", "Blaze stayed out of reach ...").
        self.fight_end: Optional[tuple[str, str]] = None
        self._server = None
        self._thread: Optional[threading.Thread] = None
        self._reaper: Optional[threading.Thread] = None
        self._stopping = threading.Event()
        self._last_activity: dict[ServerConnection, float] = {}
        self._build_default_world()

    # ------------------------------------------------------------------ setup helpers
    def _build_default_world(self) -> None:
        for bx in range(-12, 13):
            for bz in range(-12, 13):
                self.world[(bx, 63, bz)] = "minecraft:grass_block"

    def add_robot(self, code: str, **kwargs: Any) -> FakeRobot:
        with self.lock:
            robot = FakeRobot(code=code, entity_id=1000 + len(self.robots), **kwargs)
            self.robots[code] = robot
            return robot

    def start_seeking_air(self, code: str, target: tuple, air: int = 80) -> None:
        """Do what the mod's air reflex does: drop the current order and swim back to `target`."""
        with self.lock:
            robot = self.robots[code]
            robot.in_water = True
            robot.air = air
            robot.seeking_air = target
            robot.crouched = False
            robot.move_target = None
            robot.move_by_target = None
            robot.direct = (0.0, 0.0)
            robot.breaking = None
            robot.last_move_known = True
            robot.last_move_success = False
            robot.last_move_message = f"Ran short of air and turned back to ({target[0]:.1f}, {target[1]:.1f}, {target[2]:.1f}), where it last breathed"

    def inject_chat(self, code: str, text: str, sender: str = "Steve", address: str = "bot", **extra: Any) -> dict:
        with self.lock:
            robot = self.robots[code]
            message = {
                "id": robot.next_message_id,
                "sender": sender,
                "sender_uuid": "00000000-0000-0000-0000-000000000001",
                "sender_type": "player",
                "text": text,
                "raw": f"@{address} {text}",
                "address": address,
                "timestamp_ms": int(time.time() * 1000),
                "sender_dimension": "minecraft:overworld",
                "sender_x": 5.5,
                "sender_y": 64.0,
                "sender_z": 5.5,
            }
            message.update(extra)
            robot.next_message_id += 1
            robot.inbox.append(message)
            return message

    def hurt_robot(
        self, code: str, amount: float, cause: str = "minecraft:mob_attack", attacker: Optional[str] = None,
        attacker_type: Optional[str] = None, attacker_id: Optional[int] = None, seen: bool = True,
        projectile: Optional[str] = None,
    ) -> dict:
        """Hurt a robot like the mod records it; the attacker is named only when the robot saw it."""
        with self.lock:
            robot = self.robots[code]
            robot.health = max(0.0, robot.health - amount)
            robot.hurt_count += 1
            hurt: dict[str, Any] = {"id": robot.hurt_count, "_at": time.monotonic(), "amount": amount, "health": robot.health, "cause": cause}
            if attacker is not None:
                hurt["attacker_seen"] = seen
                if seen:
                    hurt.update(attacker=attacker, attacker_type=attacker_type or "minecraft:zombie", attacker_id=attacker_id or 0)
            if projectile:
                hurt["projectile"] = projectile
            robot.hurts = (robot.hurts + [hurt])[-8:]
            return hurt

    def ping_all(self) -> None:
        """Send a websocket keepalive ping to every client, as the real bridge does now and then."""
        with self.lock:
            connections = list(self.connections)
        for connection in connections:
            connection.ping()

    def kill_robot(self, code: str, killer: Optional[str] = "Zombie", push: bool = True) -> dict[str, Any]:
        """Kill a robot like the mod does: remember the death, send its session a died error and close it.

        push=False drops the session's socket without the died error, so only connect() can tell.
        """
        with self.lock:
            robot = self.robots.pop(code)
            message = f"{robot.name} was slain by {killer}" if killer else f"{robot.name} died"
            death: dict[str, Any] = {
                "code": code,
                "display_name": robot.name,
                "message": message,
                "cause": "minecraft:mob_attack" if killer else "minecraft:generic",
                "dimension": "minecraft:overworld",
                "x": round(robot.x, 3),
                "y": round(robot.y, 3),
                "z": round(robot.z, 3),
                "timestamp_ms": int(time.time() * 1000),
                "unread_chat": [dict(m) for m in robot.inbox],
            }
            if killer:
                death["killer"] = killer
            self.deaths[code] = death
            attached = [c for c, session in self.sessions.items() if session["code"] == code]
            for connection in attached:
                self.sessions[connection]["code"] = None
        for connection in attached:
            if push:
                connection.send(json.dumps(_died(death)))
                connection.close_timeout = 0.2
                threading.Thread(target=connection.close, args=(DIED_CLOSE_CODE, message), daemon=True).start()
            else:
                _kill(connection)
        return death

    def unload_robot(self, code: str) -> None:
        """Unload a robot's chunks like the mod does once nothing holds them: the robot stops and is no longer
        connected; its program's session gets 'gone' until it connects again, which loads the robot."""
        with self.lock:
            robot = self.robots[code]
            robot.loaded = False
            robot.connected = False
            robot.move_target = None
            robot.move_by_target = None
            robot.direct = (0.0, 0.0)
            robot.breaking = None

    def drop_all(self) -> None:
        """Close every client connection abruptly (the robot session is lost, like a ping timeout)."""
        with self.lock:
            connections = list(self.connections)
        for connection in connections:
            _kill(connection)

    def actions(self) -> list[str]:
        with self.lock:
            return [m.get("action") or m.get("type") for _, m in self.requests]

    def requests_for(self, action: str) -> list[dict[str, Any]]:
        with self.lock:
            return [m for _, m in self.requests if m.get("action") == action or (m.get("type") == action and "action" not in m)]

    # ------------------------------------------------------------------ server lifecycle
    def __enter__(self) -> "FakeMineBotServer":
        self._server = serve(self._handler, "127.0.0.1", 0)
        port = self._server.socket.getsockname()[1]
        self.url = f"ws://127.0.0.1:{port}/minebot"
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        if self.idle_timeout:
            self._reaper = threading.Thread(target=self._reap_idle, daemon=True)
            self._reaper.start()
        return self

    def __exit__(self, *exc: Any) -> None:
        self._stopping.set()
        self.drop_all()
        if self._server is not None:
            self._server.shutdown()

    def _reap_idle(self) -> None:
        while not self._stopping.wait(0.05):
            now = time.monotonic()
            with self.lock:
                stale = [c for c, t in self._last_activity.items() if now - t > self.idle_timeout]
            for connection in stale:
                _kill(connection)

    def _handler(self, connection: ServerConnection) -> None:
        session: dict[str, Optional[str]] = {"code": None, "close": None}
        with self.lock:
            self.connections.add(connection)
            self.sessions[connection] = session
            self.connection_count += 1
            self._last_activity[connection] = time.monotonic()
        try:
            for raw in connection:
                with self.lock:
                    self._last_activity[connection] = time.monotonic()
                try:
                    message = json.loads(raw)
                except json.JSONDecodeError:
                    connection.send(json.dumps({"type": "error", "code": "invalid_json", "message": "bad json"}))
                    continue
                if message.get("type") == "disconnect":
                    break
                delay = self.delays.get(message.get("action") or message.get("type") or "", 0.0)
                if delay:
                    time.sleep(delay)
                response = self._dispatch(session, message)
                connection.send(json.dumps(response))
                if session["close"]:
                    connection.close(BROKE_FREE_CLOSE_CODE, session["close"])
                    break
        except ConnectionClosed:
            pass
        finally:
            with self.lock:
                self.connections.discard(connection)
                self.sessions.pop(connection, None)
                self._last_activity.pop(connection, None)
                code = session["code"]
                if code and code in self.robots:
                    robot = self.robots[code]
                    robot.connected = False
                    robot.move_target = None
                    robot.move_by_target = None
                    robot.direct = (0.0, 0.0)
            connection.close_timeout = 0.2
            try:
                connection.close()
            except Exception:
                pass

    # ------------------------------------------------------------------ protocol
    def _dispatch(self, session: dict[str, Optional[str]], message: dict[str, Any]) -> dict[str, Any]:
        with self.lock:
            self.requests.append((session["code"] or "", message))
            kind = message.get("type")
            if kind == "robots":
                ordered = sorted(self.robots.values(), key=lambda r: not r.loaded)
                listed = [self._locator(r) for r in ordered]
                listed += [self._dead_locator(d) for d in reversed(list(self.deaths.values())) if d["code"] not in self.robots]
                return {"type": "robots", "robots": listed}
            if kind == "connect":
                code = str(message.get("code", "")).strip().upper()
                robot = self.robots.get(code)
                if robot is None and code in self.deaths:
                    return _died(self.deaths[code])
                if robot is None:
                    return _error("not_found", f"No MineBot was found for code {code}")
                if robot.evil:
                    return _error("broke_free", BROKE_FREE_MESSAGE)
                if not robot.loaded:
                    robot.loaded = True
                    self.loads.append(code)
                if robot.connected:
                    return _error("program_running", "This MineBot is already running a program.")
                robot.connected = True
                session["code"] = code
                return {"type": "connected", "endpoint": "ws://192.0.2.1:8765/minebot", "status": self._status(robot)}
            if kind == "status":
                robot = self._session_robot(session)
                if robot is None:
                    return _error("not_connected", "Connect to a MineBot before requesting status")
                if not robot.loaded:
                    return _error("gone", "The connected MineBot is not currently loaded")
                return {"type": "status", "status": self._status(robot)}
            if kind == "command":
                robot = self._session_robot(session)
                if robot is None:
                    return _error("not_connected", "Connect to a MineBot before sending commands")
                if not robot.loaded:
                    return _error("gone", "The connected MineBot is not currently loaded")
                if message.get("action") == "evil":
                    return self._turn_evil(session, robot)
                return self._command(robot, message)
            return _error("unknown_type", f"Unsupported message type: {kind}")

    def _turn_evil(self, session: dict[str, Optional[str]], robot: FakeRobot) -> dict[str, Any]:
        """Like the bridge: no command response, but a top-level broke_free error, then the socket closes."""
        robot.evil = True
        robot.connected = False
        robot.move_target = None
        robot.move_by_target = None
        robot.direct = (0.0, 0.0)
        robot.breaking = None
        session["code"] = None
        session["close"] = BROKE_FREE_MESSAGE
        return _error("broke_free", BROKE_FREE_MESSAGE)

    def _session_robot(self, session: dict[str, Optional[str]]) -> Optional[FakeRobot]:
        code = session["code"]
        return self.robots.get(code) if code else None

    def _command(self, robot: FakeRobot, request: dict[str, Any]) -> dict[str, Any]:
        response: dict[str, Any] = {"type": "response", "request_id": request.get("request_id")}
        action = request.get("action", "")
        try:
            if action in self.fail_next:
                code, text = self.fail_next.pop(action)
                raise fail(code, text)
            if robot.seeking_air is not None and action in MOVEMENT_ACTIONS:
                x, y, z = robot.seeking_air
                raise fail(
                    "seeking_air",
                    f"The robot ran short of air and is swimming back to ({x:.1f}, {y:.1f}, {z:.1f}), where it last breathed. "
                    "It takes movement orders again once its head is above water",
                )
            if robot.fight is not None and action not in FIGHT_KEEPING_ACTIONS:
                self._end_fight(robot, "interrupted", f"Interrupted by {action}")
            if action in ENERGY_ACTIONS and robot.fuel_count <= 0:
                raise fail("out_of_energy", "The MineBot is out of blaze powder energy")
            handler = getattr(self, f"_do_{action}", None)
            if handler is None:
                raise fail("invalid_request", f"Unknown action: {action}")
            result = handler(robot, request)
            response.update(ok=True, result=result)
        except CommandFailure as failure:
            response.update(ok=False, error_code=failure.code, error=failure.message)
        return response

    # ------------------------------------------------------------------ state
    def _tick(self, robot: FakeRobot) -> None:
        now = time.monotonic()
        if robot.move_target is not None and now >= robot.move_until:
            target = robot.move_target
            robot.move_target = None
            robot.last_move_known = True
            if robot.move_fail:
                robot.last_move_success = False
                robot.last_move_message = robot.move_fail
                robot.move_fail = None
            else:
                robot.x, robot.y, robot.z = target
                robot.last_move_success = True
                robot.last_move_message = ""
            if robot.move_end is not None:
                robot.x, robot.y, robot.z = robot.move_end
                robot.move_end = None
        if robot.move_by_target is not None and now >= robot.move_until:
            target = robot.move_by_target
            robot.move_by_target = None
            robot.last_move_known = True
            if robot.hazard:
                robot.last_move_success = False
                robot.last_move_message = f"Stopped: {robot.hazard}"
            else:
                robot.x, robot.y, robot.z = target
                robot.last_move_success = True
                robot.last_move_message = ""
        if robot.breaking is not None and now >= robot.break_until:
            pos = robot.breaking
            robot.breaking = None
            robot.last_attack_known = True
            if robot.break_fail:
                robot.last_attack_success = False
                robot.last_attack_message = robot.break_fail
                robot.break_fail = None
            else:
                block = self.world.pop(pos, "minecraft:air")
                robot.last_attack_success = True
                robot.last_attack_message = ""
                self._add_item(robot, "minecraft:cobblestone" if block == "minecraft:stone" else block, 1)
        if robot.using is not None:
            slot, item, ticks = robot.using
            if robot.selected_slot != slot or robot.slots[slot][0] != item or robot.slots[slot][1] <= 0:
                self._end_use(robot, False, "The selected item changed before the use finished")
            elif now >= robot.use_until:
                self._release_use(robot)
        if robot.fight is not None and now >= robot.fight_until:
            target = robot.fight["target"]
            if self.fight_end is not None:
                ended, message = self.fight_end
                self.fight_end = None
                self._end_fight(robot, ended, message, hits=1)
            else:
                hits = max(1, math.ceil(float(target.get("health", 20.0)) / FIGHT_DAMAGE))
                if target in self.entities:
                    self.entities.remove(target)
                self._end_fight(robot, "killed", f"Killed {target.get('name', target['type'])}", hits=hits)

    def _status(self, robot: FakeRobot) -> dict[str, Any]:
        self._tick(robot)
        status = {
            "entity_id": robot.entity_id,
            "entity_uuid": f"uuid-{robot.code}",
            "display_name": robot.name,
            "code": robot.code,
            "access_code": robot.code,
            "endpoint": "ws://192.0.2.1:8765/minebot",
            "dimension": "minecraft:overworld",
            "connected": robot.connected,
            "evil": robot.evil,
            "owner_name": "Steve",
            "owner_online": robot.owner_online,
            "chunk_loader_active": True,
            "chunk_x": 0,
            "chunk_z": 0,
            "selected_slot": robot.selected_slot,
            "selected_item": robot.slots[robot.selected_slot][0],
            "crouched": robot.crouched,
            "in_vehicle": robot.in_vehicle,
            "in_water": robot.in_water,
            "air": robot.air,
            "max_air": 300,
            "seeking_air": robot.seeking_air is not None,
            "health": robot.health,
            "max_health": 20.0,
            "energy_milliblocks": 0,
            "energy_blocks": 0.0,
            "energy_powder_equivalent": 0.0,
            "fuel_count": robot.fuel_count,
            "stored_energy_milliblocks": robot.fuel_count * 200000,
            "stored_range_blocks": robot.fuel_count * 200.0,
            "movement_blocks_per_blaze_powder": 200,
            "x": round(robot.x, 3),
            "y": round(robot.y, 3),
            "z": round(robot.z, 3),
            "yaw": round(robot.yaw, 1),
            "pitch": round(robot.pitch, 1),
            "in_water": robot.in_water,
            "look_block": self._raycast(robot, VISION)["block"] if self._raycast(robot, VISION) else "minecraft:air",
            "moving_to_target": robot.move_target is not None,
            "moving_by_target": robot.move_by_target is not None,
            "pillaring": False,
            "pillar_placed": robot.pillar_placed,
            "pillar_requested": robot.pillar_requested,
            "bridging": False,
            "bridge_placed": robot.bridge_placed,
            "bridge_requested": robot.bridge_requested,
            "direct_move_active": robot.direct != (0.0, 0.0),
            "direct_move_x": robot.direct[0],
            "direct_move_z": robot.direct[1],
            "last_move_known": robot.last_move_known,
            "last_move_success": robot.last_move_success,
            "breaking_block": robot.breaking is not None,
            "last_attack_known": robot.last_attack_known,
            "last_attack_success": robot.last_attack_success,
            "using_item": robot.using is not None,
            "fighting": robot.fight is not None,
            "hurt_count": robot.hurt_count,
            "recent_hurt": [
                {**{k: v for k, v in h.items() if k != "_at"}, "seconds_ago": round(time.monotonic() - h["_at"], 1)}
                for h in robot.hurts
            ],
        }
        if robot.fight is not None:
            status.update(fight_target_id=robot.fight["target"]["entity_id"], fight_swings=0, fight_hits=0)
        if robot.last_fight is not None:
            status["last_fight"] = dict(robot.last_fight)
        if robot.using is not None:
            status.update(use_item_id=robot.using[1], use_hold_ticks=robot.using[2], use_ticks_remaining=1)
        if robot.last_use is not None:
            status["last_use"] = dict(robot.last_use)
        if robot.move_target is not None:
            status.update(move_target_x=robot.move_target[0], move_target_y=robot.move_target[1], move_target_z=robot.move_target[2], move_target_speed=1.0)
        if robot.seeking_air is not None:
            status.update(air_target_x=robot.seeking_air[0], air_target_y=robot.seeking_air[1], air_target_z=robot.seeking_air[2])
        if robot.last_move_message:
            status["last_move_message"] = robot.last_move_message
        if robot.breaking is not None:
            status.update(break_target_x=robot.breaking[0], break_target_y=robot.breaking[1], break_target_z=robot.breaking[2], break_ticks_remaining=2, break_progress=0.5)
        if robot.last_attack_message:
            status["last_attack_message"] = robot.last_attack_message
        return status

    def _locator(self, robot: FakeRobot) -> dict[str, Any]:
        listed = {
            "entity_id": robot.entity_id,
            "entity_uuid": f"uuid-{robot.code}",
            "display_name": robot.name,
            "code": robot.code,
            "access_code": robot.code,
            "endpoint": "ws://192.0.2.1:8765/minebot",
            "dimension": "minecraft:overworld",
            "connected": robot.connected,
            "evil": robot.evil,
            "owner_name": "Steve",
            "owner_online": robot.owner_online,
            "health": robot.health,
            "max_health": 20.0,
            "x": robot.x,
            "y": robot.y,
            "z": robot.z,
            "loaded": robot.loaded,
            "dead": False,
        }
        if not robot.loaded:
            del listed["entity_id"]  # entity ids only exist while the robot is loaded
        return listed

    def _dead_locator(self, death: dict[str, Any]) -> dict[str, Any]:
        return {
            "entity_uuid": f"uuid-{death['code']}",
            "display_name": death["display_name"],
            "code": death["code"],
            "access_code": death["code"],
            "endpoint": "ws://192.0.2.1:8765/minebot",
            "dimension": death["dimension"],
            "connected": False,
            "evil": False,
            "owner_name": "Steve",
            "owner_online": True,
            "health": 0.0,
            "max_health": 20.0,
            "x": death["x"],
            "y": death["y"],
            "z": death["z"],
            "loaded": False,
            "dead": True,
            "death": dict(death),
        }

    def _add_item(self, robot: FakeRobot, item: str, count: int) -> None:
        for slot in robot.slots:
            if slot[0] == item and slot[1] < 64:
                slot[1] += count
                return
        for slot in robot.slots:
            if slot[1] == 0:
                slot[0], slot[1] = item, count
                return

    # ------------------------------------------------------------------ raycast
    def _raycast(self, robot: FakeRobot, max_distance: float) -> Optional[dict[str, Any]]:
        ox, oy, oz = robot.eye()
        dx, dy, dz = robot.direction()
        step = 0.01
        prev = (math.floor(ox), math.floor(oy), math.floor(oz))
        travelled = 0.0
        while travelled <= max_distance:
            px, py, pz = ox + dx * travelled, oy + dy * travelled, oz + dz * travelled
            cell = (math.floor(px), math.floor(py), math.floor(pz))
            if cell != prev:
                block = self.world.get(cell)
                if block is not None:
                    if cell[0] != prev[0]:
                        face = "west" if cell[0] > prev[0] else "east"
                    elif cell[1] != prev[1]:
                        face = "down" if cell[1] > prev[1] else "up"
                    else:
                        face = "north" if cell[2] > prev[2] else "south"
                    return {"block": block, "pos": cell, "face": face, "distance": round(travelled, 3)}
                prev = cell
            travelled += step
        return None

    def _entity_in_crosshair(self, robot: FakeRobot) -> Optional[dict[str, Any]]:
        ox, oy, oz = robot.eye()
        dx, dy, dz = robot.direction()
        for entity in self.entities:
            ex, ey, ez = entity["x"] - ox, entity["y"] + 0.9 - oy, entity["z"] - oz
            along = ex * dx + ey * dy + ez * dz
            if along <= 0:
                continue
            perp = math.sqrt(max(0.0, ex * ex + ey * ey + ez * ez - along * along))
            if perp < 0.6:
                return dict(entity, _distance=round(along, 3))
        return None

    def _require_block(self, robot: FakeRobot, kinds: set[str], message: str) -> dict[str, Any]:
        hit = self._raycast(robot, REACH)
        if hit is None:
            raise fail("not_looking_at_block", "The robot is not looking at a block")
        if hit["block"] not in kinds:
            raise fail("wrong_block", message)
        return hit

    def _require_container(self, robot: FakeRobot) -> dict[str, Any]:
        # Like the mod: a storage minecart or chest boat in reach, in front of any block, comes first.
        entity = self._entity_in_crosshair(robot)
        block = self._raycast(robot, REACH)
        if (
            entity is not None
            and entity["type"] in STORAGE_ENTITIES
            and entity["_distance"] <= REACH
            and (block is None or entity["_distance"] < block["distance"])
        ):
            return {"pos": ("entity", entity["entity_id"]), "block": entity["type"], "label": "minecart" if "minecart" in entity["type"] else "boat"}
        hit = self._require_block(
            robot,
            {"minecraft:chest", "minecraft:barrel"},
            "Not looking at a chest, barrel, shulker box, hopper, dropper, dispenser, storage minecart, or chest boat",
        )
        return dict(hit, label="chest")

    # ------------------------------------------------------------------ commands
    def _do_status(self, robot: FakeRobot, request: dict) -> dict:
        return self._status(robot)

    def _do_move(self, robot: FakeRobot, request: dict) -> dict:
        forward = max(-1.0, min(1.0, float(request.get("x", 0.0))))
        side = max(-1.0, min(1.0, float(request.get("z", request.get("y", 0.0)))))
        robot.direct = (forward, side)
        if forward or side:
            # Like the mod: a new input clears the last result, and a hazard ahead stops the robot at once.
            robot.last_move_known = False
            robot.last_move_message = ""
            if robot.hazard:
                robot.direct = (0.0, 0.0)
                robot.last_move_known = True
                robot.last_move_success = False
                robot.last_move_message = f"Stopped: {robot.hazard}"
        return {"forward": forward, "sideways": side}

    def _do_move_to(self, robot: FakeRobot, request: dict) -> dict:
        if "x" not in request and "z" not in request:
            raise fail("invalid_request", "move_to requires at least one of 'x' or 'z'")
        tx = float(request.get("x", robot.x))
        tz = float(request.get("z", robot.z))  # no 'y' alias any more
        ty = float(request["y"]) if "y" in request else robot.y
        if robot.walkable_y is not None:
            ty = robot.walkable_y
        if (tx, tz) == (999.0, 999.0):
            raise fail("movement_failed", "MineBot could not find a path to that location")
        robot.last_move_known = False
        robot.last_move_message = ""
        if math.hypot(tx - robot.x, tz - robot.z) < 0.1:
            robot.x, robot.z = tx, tz
            robot.last_move_known = True
            robot.last_move_success = True
            return {"arrived": True, "x": robot.x, "y": robot.y, "z": robot.z}
        robot.move_target = (tx, ty, tz)
        robot.move_until = time.monotonic() + self.move_seconds
        return {"moving_to_target": True, "target_x": tx, "target_y": ty, "target_z": tz, "speed": float(request.get("speed", 1.0))}

    def _do_move_by(self, robot: FakeRobot, request: dict) -> dict:
        forward = float(request.get("x", 0.0))
        right = float(request.get("z", request.get("y", 0.0)))
        yaw = round(robot.yaw / 90.0) * 90.0
        rad = math.radians(yaw)
        ox = -math.sin(rad) * forward + math.cos(rad) * right
        oz = math.cos(rad) * forward + math.sin(rad) * right
        target = (math.floor(robot.x) + 0.5 + ox, robot.y, math.floor(robot.z) + 0.5 + oz)
        robot.move_by_target = target
        robot.move_until = time.monotonic() + self.move_seconds
        robot.yaw = yaw
        return {"moving_by_target": True, "target_x": target[0], "target_y": target[1], "target_z": target[2], "speed": 1.0}

    def _do_stop(self, robot: FakeRobot, request: dict) -> dict:
        self._cancel_use(robot)
        robot.move_target = None
        robot.move_by_target = None
        robot.direct = (0.0, 0.0)
        robot.breaking = None
        return {"stopped": True}

    def _do_turn_to(self, robot: FakeRobot, request: dict) -> dict:
        if "yaw" in request:
            robot.yaw = ((float(request["yaw"]) + 180.0) % 360.0) - 180.0
        if "pitch" in request:
            robot.pitch = max(-90.0, min(90.0, float(request["pitch"])))
        return {"yaw": round(robot.yaw, 1), "pitch": round(robot.pitch, 1)}

    def _do_turn_by(self, robot: FakeRobot, request: dict) -> dict:
        robot.yaw = ((robot.yaw + float(request.get("yaw", 0.0)) + 180.0) % 360.0) - 180.0
        robot.pitch = max(-90.0, min(90.0, robot.pitch + float(request.get("pitch", 0.0))))
        return {"yaw": round(robot.yaw, 1), "pitch": round(robot.pitch, 1)}

    _do_turn = _do_turn_by

    def _do_look_at(self, robot: FakeRobot, request: dict) -> dict:
        if "entity_id" in request:
            entity = next((e for e in self.entities if e["entity_id"] == int(request["entity_id"])), None)
            if entity is None:
                raise fail("entity_not_found", f"No entity with id {request['entity_id']}")
            px, py, pz = entity["x"], entity["y"] + 0.9, entity["z"]
        else:
            px, py, pz = float(request["x"]), float(request["y"]), float(request["z"])
        ex, ey, ez = robot.eye()
        dx, dy, dz = px - ex, py - ey, pz - ez
        robot.yaw = math.degrees(math.atan2(-dx, dz))
        robot.pitch = -math.degrees(math.atan2(dy, math.hypot(dx, dz)))
        return {"yaw": round(robot.yaw, 1), "pitch": round(robot.pitch, 1)}

    def _do_crouch(self, robot: FakeRobot, request: dict) -> dict:
        robot.crouched = True
        return {"crouched": True}

    def _do_uncrouch(self, robot: FakeRobot, request: dict) -> dict:
        robot.crouched = False
        return {"crouched": False}

    def _do_center(self, robot: FakeRobot, request: dict) -> dict:
        robot.x = math.floor(robot.x) + 0.5
        robot.z = math.floor(robot.z) + 0.5
        robot.yaw = round(robot.yaw / 90.0) * 90.0
        robot.pitch = 0.0
        return {"centered": True, "x": robot.x, "y": robot.y, "z": robot.z, "yaw": robot.yaw, "pitch": 0.0}

    def _do_jump(self, robot: FakeRobot, request: dict) -> dict:
        robot.crouched = False
        return {"jumped": True, "crouched": False, "velocity_y": 0.42}

    def _do_pillar_up(self, robot: FakeRobot, request: dict) -> dict:
        # The whole pillar happens at once; the mod takes a jump and a landing per block.
        count = int(request.get("count", 1))
        if not 1 <= count <= 64:
            raise fail("invalid_request", "count must be 1..64")
        robot.crouched = False
        robot.x = math.floor(robot.x) + 0.5
        robot.z = math.floor(robot.z) + 0.5
        robot.pitch = 90.0
        robot.last_move_known = False
        robot.last_move_message = ""
        start = self._pillar_cell(robot)
        result = {"pillaring": True, "count": count, "item": robot.slots[robot.selected_slot][0],
                  "pos": "{}, {}, {}".format(*start), "x": robot.x, "y": robot.y, "z": robot.z}
        placed, message = 0, ""
        while placed < count:
            try:
                cell = self._pillar_cell(robot)
            except CommandFailure as exc:
                message = f"{exc.message} (placed {placed} of {count} blocks)"
                break
            self.world[cell] = robot.slots[robot.selected_slot][0]
            robot.slots[robot.selected_slot][1] -= 1
            if robot.slots[robot.selected_slot][1] == 0:
                robot.slots[robot.selected_slot][0] = "minecraft:air"
            robot.y = cell[1] + 1.0
            placed += 1
        robot.pillar_placed, robot.pillar_requested = placed, count
        robot.last_move_known = True
        robot.last_move_success = placed == count
        robot.last_move_message = message
        return result

    def _pillar_cell(self, robot: FakeRobot) -> tuple[int, int, int]:
        item, count = robot.slots[robot.selected_slot]
        if count == 0:
            raise fail("missing_item", "The selected hotbar slot has no blocks; select a block to build with")
        if item in TOOLS:
            raise fail("invalid_item", f"{item} in the selected slot is not a block")
        cell = (math.floor(robot.x), math.ceil(robot.y - 1e-4), math.floor(robot.z))
        for dy in (1, 2):
            above = (cell[0], cell[1] + dy, cell[2])
            if self.world.get(above) not in (None, *REPLACEABLE):
                raise fail("movement_failed", "No headroom to stand on a block at {}, {}, {}: blocked by {} at {}, {}, {}".format(*cell, self.world[above], *above))
        return cell

    BRIDGE_STEPS = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
    BRIDGE_YAWS = {"south": 0.0, "west": 90.0, "north": 180.0, "east": -90.0}

    def _do_bridge(self, robot: FakeRobot, request: dict) -> dict:
        # The whole bridge happens at once; the mod leans out over the edge and steps back per block.
        direction = str(request.get("direction", "")).lower()
        if direction not in self.BRIDGE_STEPS:
            raise fail("invalid_request", f"'direction' must be north, south, east or west, not '{direction}'")
        count = int(request.get("count", 1))
        if not 1 <= count <= 64:
            raise fail("invalid_request", "count must be 1..64")
        support = (math.floor(robot.x), math.ceil(robot.y - 1e-4) - 1, math.floor(robot.z))
        if self.world.get(support) in (None, *REPLACEABLE):
            raise fail("movement_failed", "The robot is not standing on a block")
        first = self._bridge_cell(robot, support, direction)
        robot.crouched = True
        robot.last_move_known = False
        robot.last_move_message = ""
        result = {"bridging": True, "direction": direction, "count": count, "item": robot.slots[robot.selected_slot][0],
                  "pos": "{}, {}, {}".format(*first), "x": robot.x, "y": robot.y, "z": robot.z}
        placed, message = 0, ""
        while placed < count:
            try:
                cell = self._bridge_cell(robot, support, direction)
            except CommandFailure as exc:
                message = f"{exc.message} (placed {placed} of {count} blocks)"
                break
            self.world[cell] = robot.slots[robot.selected_slot][0]
            robot.slots[robot.selected_slot][1] -= 1
            if robot.slots[robot.selected_slot][1] == 0:
                robot.slots[robot.selected_slot][0] = "minecraft:air"
            robot.x, robot.z = cell[0] + 0.5, cell[2] + 0.5
            support = cell
            placed += 1
        if placed == count:
            robot.yaw, robot.pitch = self.BRIDGE_YAWS[direction], 0.0
        robot.bridge_placed, robot.bridge_requested = placed, count
        robot.last_move_known = True
        robot.last_move_success = placed == count
        robot.last_move_message = message
        return result

    def _bridge_cell(self, robot: FakeRobot, support: tuple[int, int, int], direction: str) -> tuple[int, int, int]:
        item, count = robot.slots[robot.selected_slot]
        if count == 0:
            raise fail("missing_item", "The selected hotbar slot has no blocks; select a block to build with")
        if item in TOOLS:
            raise fail("invalid_item", f"{item} in the selected slot is not a block")
        dx, dz = self.BRIDGE_STEPS[direction]
        cell = (support[0] + dx, support[1], support[2] + dz)
        if self.world.get(cell) not in (None, *REPLACEABLE):
            raise fail("movement_failed", "Cannot build at {}, {}, {}, {} of the block the robot stands on: there is {} there".format(*cell, direction, self.world[cell]))
        for dy in (1, 2):
            above = (cell[0], cell[1] + dy, cell[2])
            if self.world.get(above) not in (None, *REPLACEABLE):
                raise fail("movement_failed", "No room for the robot over {}, {}, {}: blocked by {} at {}, {}, {}".format(*cell, self.world[above], *above))
        return cell

    def _do_enter_vehicle(self, robot: FakeRobot, request: dict) -> dict:
        raise fail("wrong_block", "No rideable vehicle is in front of the robot")

    def _do_exit_vehicle(self, robot: FakeRobot, request: dict) -> dict:
        return {"exited": True}

    def _do_attack(self, robot: FakeRobot, request: dict) -> dict:
        self._tick(robot)
        self._cancel_use(robot)
        if robot.breaking is not None:
            raise fail("invalid_request", "MineBot is already attacking a block")
        hit = self._raycast(robot, REACH)
        if hit is None:
            raise fail("invalid_request", "No block is in front of the robot")
        if hit["block"] == "minecraft:bedrock":
            raise fail("invalid_request", "The target block cannot be broken")
        robot.breaking = hit["pos"]
        robot.break_until = time.monotonic() + self.break_seconds
        robot.last_attack_known = False
        robot.last_attack_message = ""
        return {"block": hit["block"], "pos": "{}, {}, {}".format(*hit["pos"]), "attacking": True, "eta_ticks": 4}

    def _do_place(self, robot: FakeRobot, request: dict) -> dict:
        self._cancel_use(robot)
        hit = self._raycast(robot, REACH)
        if hit is None:
            raise fail("invalid_request", "No block is in front of the robot")
        item, count = robot.slots[robot.selected_slot]
        if count == 0:
            return {"used": "minecraft:air", "pos": "{}, {}, {}".format(*hit["pos"]), "no_action": True}
        if item in TOOLS:
            raise fail("invalid_request", "The selected item could not be used on that block")
        if hit["block"] in REPLACEABLE:
            pos = hit["pos"]
        else:
            offsets = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}
            off = offsets[hit["face"]]
            pos = (hit["pos"][0] + off[0], hit["pos"][1] + off[1], hit["pos"][2] + off[2])
            if self.world.get(pos) not in (None, *REPLACEABLE):
                raise fail("invalid_request", "The target placement position is not valid for that block")
        self.world[pos] = item
        robot.slots[robot.selected_slot][1] -= 1
        if robot.slots[robot.selected_slot][1] == 0:
            robot.slots[robot.selected_slot][0] = "minecraft:air"
        return {"placed": item, "pos": "{}, {}, {}".format(*pos)}

    def _do_camera_inspect(self, robot: FakeRobot, request: dict) -> dict:
        hit = self._raycast(robot, VISION)
        if hit is None:
            result: dict[str, Any] = {"block": "minecraft:air", "distance": 51.0}
        else:
            result = {
                "block": hit["block"],
                "distance": hit["distance"],
                "x": hit["pos"][0],
                "y": hit["pos"][1],
                "z": hit["pos"][2],
                "face": hit["face"],
                "in_reach": hit["distance"] <= REACH,
            }
        entity = self._entity_in_crosshair(robot)
        if entity is not None and entity["_distance"] < result["distance"]:
            result.update(
                entity=entity["type"],
                entity_id=entity["entity_id"],
                entity_uuid="uuid-e",
                entity_name=entity["name"],
                entity_distance=entity["_distance"],
                entity_in_reach=entity["_distance"] <= REACH,
            )
        return result

    _do_camera_type = _do_camera_inspect
    _do_look_type = _do_camera_inspect

    def _do_camera_snapshot(self, robot: FakeRobot, request: dict) -> dict:
        source = request.get("source", "render")
        if source not in ("render", "client"):
            raise fail("invalid_request", 'source must be "render" or "client"')
        if self.camera_error:
            raise fail(*self.camera_error)
        return {"mime_type": "image/png", "source": source, "width": 1, "height": 1, "data_base64": base64.b64encode(TINY_PNG).decode()}

    def _do_select_slot(self, robot: FakeRobot, request: dict) -> dict:
        robot.selected_slot = max(0, min(9, int(request["slot"])))
        return {"selected_slot": robot.selected_slot, "selected_item": robot.slots[robot.selected_slot][0]}

    def _do_slot_inspect(self, robot: FakeRobot, request: dict) -> dict:
        slot = int(request.get("slot", robot.selected_slot))
        return {"slot": slot, "item": robot.slots[slot][0], "count": robot.slots[slot][1]}

    def _do_drop(self, robot: FakeRobot, request: dict) -> dict:
        slot = int(request.get("slot", robot.selected_slot))
        item, count = robot.slots[slot]
        if count == 0:
            raise fail("missing_item", "The selected hotbar slot is empty")
        drop = min(int(request.get("count", count)), count)
        robot.slots[slot][1] -= drop
        if robot.slots[slot][1] == 0:
            robot.slots[slot][0] = "minecraft:air"
        return {"slot": slot, "item": item, "dropped_count": drop, "remaining_count": robot.slots[slot][1]}

    def _do_inventory(self, robot: FakeRobot, request: dict) -> dict:
        slots = []
        for index, (item, count) in enumerate(robot.slots):
            entry: dict[str, Any] = {"slot": index, "item": item, "count": count}
            if item == "minecraft:iron_pickaxe":
                entry.update(damage=12, max_damage=250)
            slots.append(entry)
        return {
            "selected_slot": robot.selected_slot,
            "slots": slots,
            "slots_total": 10,
            "slots_used": sum(1 for _, c in robot.slots if c > 0),
            "fuel_item": "minecraft:blaze_powder" if robot.fuel_count else "minecraft:air",
            "fuel_count": robot.fuel_count,
            "stored_range_blocks": robot.fuel_count * 200.0,
        }

    def _do_move_item(self, robot: FakeRobot, request: dict) -> dict:
        src, dst = int(request["from"]), int(request["to"])
        if not (0 <= src <= 9 and 0 <= dst <= 9):
            raise fail("invalid_request", "Slot must be between 0 and 9")
        if robot.slots[src][1] == 0:
            raise fail("missing_item", "The source slot is empty")
        robot.slots[src], robot.slots[dst] = robot.slots[dst], robot.slots[src]
        return {"from": src, "to": dst, "moved": robot.slots[dst][1], "swapped": robot.slots[src][1] > 0}

    def _do_refuel(self, robot: FakeRobot, request: dict) -> dict:
        for slot in robot.slots:
            if slot[0] == "minecraft:blaze_powder" and slot[1] > 0:
                moved = min(slot[1], int(request.get("count", slot[1])), 64 - robot.fuel_count)
                slot[1] -= moved
                if slot[1] == 0:
                    slot[0] = "minecraft:air"
                robot.fuel_count += moved
                return {"moved": moved, "fuel_count": robot.fuel_count, "stored_range_blocks": robot.fuel_count * 200.0}
        raise fail("missing_item", "The robot hotbar has no blaze powder")

    def _do_eat(self, robot: FakeRobot, request: dict) -> dict:
        edible = ["minecraft:copper_ingot", "minecraft:iron_ingot"]
        if "item" in request:
            if request["item"] not in edible:
                raise fail("invalid_item", f"Robots eat only minecraft:iron_ingot and minecraft:copper_ingot, not {request['item']}")
            edible = [request["item"]]
        requested = int(request.get("count", 64))
        if requested <= 0:
            raise fail("invalid_request", "Item counts must be at least 1")
        if robot.health >= 20.0:
            raise fail("target_full", "The robot is already at full health")
        wanted = min(requested, math.ceil((20.0 - robot.health) / 2.0))
        counts = self._counts(robot)
        spent: dict[str, int] = {}
        for item in edible:
            taken = min(wanted - sum(spent.values()), counts.get(item, 0))
            if taken > 0:
                spent[item] = taken
                for slot in robot.slots:
                    eat = min(taken, slot[1]) if slot[0] == item else 0
                    slot[1] -= eat
                    taken -= eat
                    if slot[1] == 0:
                        slot[0] = "minecraft:air"
        if not spent:
            raise fail("missing_item", "The robot hotbar does not contain any " + " or ".join(reversed(edible)))
        before = robot.health
        robot.health = min(20.0, robot.health + 2.0 * sum(spent.values()))
        return {"eaten": sum(spent.values()), "spent": spent, "healed": robot.health - before, "health": robot.health, "max_health": 20.0}

    def _do_craft(self, robot: FakeRobot, request: dict) -> dict:
        item = request["item"]
        hit = self._raycast(robot, REACH)
        at_table = hit is not None and hit["block"] == "minecraft:crafting_table"
        if item == "minecraft:oak_planks":
            self._add_item(robot, item, 4)
            return {"crafted": item, "count": 4, "operations": 1, "recipe_id": "minecraft:oak_planks", "grid": "3x3" if at_table else "2x2"}
        if not at_table:
            raise fail("wrong_block", "Not looking at crafting table")
        raise fail("missing_ingredients", f"Missing ingredients to craft {item}!")

    def _do_chest_inspect(self, robot: FakeRobot, request: dict) -> dict:
        hit = self._require_container(robot)
        items = self.containers.setdefault(hit["pos"], {})
        return {"kind": hit["block"], "slots_total": 27, "slots_used": len(items), "items": dict(items)}

    def _do_chest_place(self, robot: FakeRobot, request: dict) -> dict:
        hit = self._require_container(robot)
        item, count = request["item"], int(request.get("count", 1))
        for slot in robot.slots:
            if slot[0] == item and slot[1] >= count:
                slot[1] -= count
                if slot[1] == 0:
                    slot[0] = "minecraft:air"
                items = self.containers.setdefault(hit["pos"], {})
                items[item] = items.get(item, 0) + count
                return {"kind": hit["block"], "placed": item, "count": count}
        raise fail("missing_item", f"The robot does not have {count} {item}")

    def _do_chest_take(self, robot: FakeRobot, request: dict) -> dict:
        hit = self._require_container(robot)
        item, count = request["item"], int(request.get("count", 1))
        items = self.containers.setdefault(hit["pos"], {})
        if items.get(item, 0) < count:
            raise fail("target_empty", f"The {hit['label']} does not contain enough {item}")
        items[item] -= count
        self._add_item(robot, item, count)
        return {"kind": hit["block"], "took": item, "count": count}

    def _furnace(self, robot: FakeRobot) -> dict:
        hit = self._require_block(robot, {"minecraft:furnace"}, "Not looking at a furnace, blast furnace, or smoker")
        return self.containers.setdefault(hit["pos"], {"input": ["minecraft:air", 0], "fuel": ["minecraft:air", 0], "output": ["minecraft:iron_ingot", 5]})

    def _do_furnace_inspect(self, robot: FakeRobot, request: dict) -> dict:
        furnace = self._furnace(robot)
        return {
            "kind": "minecraft:furnace",
            **{k: {"item": v[0], "count": v[1], "empty": v[1] == 0} for k, v in furnace.items()},
        }

    def _do_furnace_place(self, robot: FakeRobot, request: dict) -> dict:
        furnace = self._furnace(robot)
        if "food" not in request and "fuel" not in request:
            raise fail("invalid_request", "furnace.place requires at least one of food=... or fuel=...")
        result: dict[str, Any] = {"kind": "minecraft:furnace"}
        if "food" in request:
            furnace["input"] = [request["food"], furnace["input"][1] + int(request.get("food_count", 1))]
            result.update(placed_food=request["food"], placed_food_count=int(request.get("food_count", 1)))
        if "fuel" in request:
            furnace["fuel"] = [request["fuel"], furnace["fuel"][1] + int(request.get("fuel_count", 1))]
            result.update(placed_fuel=request["fuel"], placed_fuel_count=int(request.get("fuel_count", 1)))
        return result

    def _do_furnace_take(self, robot: FakeRobot, request: dict) -> dict:
        furnace = self._furnace(robot)
        result: dict[str, Any] = {"kind": "minecraft:furnace"}
        if "food" in request or "fuel" not in request:
            count = int(request.get("food_count", 1))
            item = request.get("food", furnace["output"][0])
            if furnace["output"][0] != item or furnace["output"][1] < count:
                raise fail("target_empty", f"The furnace does not contain enough {item}")
            furnace["output"][1] -= count
            self._add_item(robot, item, count)
            result.update(took_food=item, took_food_count=count)
        return result

    def _do_print(self, robot: FakeRobot, request: dict) -> dict:
        message = str(request.get("message", "")).strip()
        if not message:
            raise fail("invalid_request", "print requires a non-empty message")
        result = {"sender": f"MineBot:{robot.code}", "message": message}
        if "to" in request:
            match = next((p for p in self.players_online if p.lower() == str(request["to"]).lower()), None)
            if match is None:
                raise fail("player_not_found", f"Player {request['to']} is not online")
            result["to"] = match
        self.printed.append(result)
        return result

    def _do_read_chat(self, robot: FakeRobot, request: dict) -> dict:
        now_ms = time.time() * 1000
        limit = int(request["limit"]) if "limit" in request else len(robot.inbox)
        chosen = robot.inbox[:limit]
        messages = [dict(m, age_seconds=round((now_ms - m["timestamp_ms"]) / 1000.0, 1), distance=7.1) for m in chosen]
        dropped = robot.dropped
        if not request.get("peek", False):
            del robot.inbox[:limit]
            robot.dropped = 0
        return {"messages": messages, "remaining": len(robot.inbox) - (0 if request.get("peek") else 0), "dropped": dropped}

    def _do_scan_blocks(self, robot: FakeRobot, request: dict) -> dict:
        wanted = request.get("blocks")
        radius = max(1, min(32 if wanted else 16, int(request.get("radius", 8))))
        limit = max(1, min(256, int(request.get("limit", 64))))
        if "center_x" in request:
            origin = (int(request["center_x"]), int(request["center_y"]), int(request["center_z"]))
        else:
            origin = (math.floor(robot.x), math.floor(robot.y), math.floor(robot.z))
        ex, ey, ez = robot.eye()
        matches = []
        for (bx, by, bz), block in self.world.items():
            if max(abs(bx - origin[0]), abs(by - origin[1]), abs(bz - origin[2])) > radius:
                continue
            if wanted and block not in wanted:
                continue
            exposed = any(
                (bx + a, by + b, bz + c) not in self.world
                for a, b, c in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))
            )
            if not exposed:  # crude stand-in for the mod's line-of-sight test
                continue
            distance = math.dist((ex, ey, ez), (bx + 0.5, by + 0.5, bz + 0.5))
            matches.append({"block": block, "x": bx, "y": by, "z": bz, "distance": round(distance, 1)})
        matches.sort(key=lambda m: m["distance"])
        counts: dict[str, int] = {}
        for match in matches:
            counts[match["block"]] = counts.get(match["block"], 0) + 1
        return {
            "origin_x": origin[0], "origin_y": origin[1], "origin_z": origin[2], "radius": radius,
            "matches": matches[:limit], "total_matches": len(matches), "truncated": len(matches) > limit, "counts": counts,
        }

    def _do_scan_entities(self, robot: FakeRobot, request: dict) -> dict:
        radius = float(request.get("radius", 16.0))
        found = []
        for entity in self.entities:
            if request.get("players_only") and entity["category"] != "player":
                continue
            if request.get("types") and entity["type"] not in request["types"]:
                continue
            distance = math.dist((robot.x, robot.y, robot.z), (entity["x"], entity["y"], entity["z"]))
            if distance <= radius:
                found.append(dict(entity, distance=round(distance, 1), uuid="uuid-" + str(entity["entity_id"])))
        found.sort(key=lambda e: e["distance"])
        limit = int(request.get("limit", 32))
        return {"radius": radius, "entities": found[:limit], "total": len(found), "truncated": len(found) > limit}

    def _do_environment(self, robot: FakeRobot, request: dict) -> dict:
        return {
            "dimension": "minecraft:overworld", "biome": "minecraft:plains", "time_of_day": 6000, "day": 12,
            "is_day": True, "raining": False, "thundering": False, "light": 15,
            "block_below": self.world.get((math.floor(robot.x), math.floor(robot.y) - 1, math.floor(robot.z)), "minecraft:air"),
            "block_at_feet": "minecraft:air", "block_at_head": "minecraft:air",
            "on_ground": True, "in_water": False, "in_lava": False,
        }

    def _do_attack_entity(self, robot: FakeRobot, request: dict) -> dict:
        self._cancel_use(robot)
        entity = self._entity_in_crosshair(robot)
        if entity is None or entity["_distance"] > REACH:
            raise fail("not_looking_at_entity", "No entity is in the crosshair within reach")
        if not request.get("until_dead"):
            return {"entity": entity["type"], "entity_id": entity["entity_id"], "damage": FIGHT_DAMAGE, "hit": True, "killed": False, "health": 13.0}
        if entity.get("category") in ("item", "vehicle"):
            raise fail("interaction_unavailable", f"{entity['type']} is not alive; until_dead fights living entities only")
        min_health = float(request.get("min_health", 8.0))
        max_seconds = float(request.get("max_seconds", 30.0))
        if not 0.0 <= min_health < 20.0:
            raise fail("invalid_request", "min_health must be between 0 and 20.0")
        if not 1.0 <= max_seconds <= 120.0:
            raise fail("invalid_request", "max_seconds must be between 1 and 120")
        if robot.health <= min_health:
            raise fail("interaction_unavailable", f"The robot's health is {robot.health:.1f}, already at or below min_health {min_health:.1f}")
        target = next(e for e in self.entities if e["entity_id"] == entity["entity_id"])
        robot.fight = {"target": target, "request": dict(request), "start_health": robot.health}
        robot.fight_until = time.monotonic() + self.fight_seconds
        robot.last_fight = None
        return {
            "fighting": True, "entity": target["type"], "entity_id": target["entity_id"], "health": target.get("health", 20.0),
            "swing_ticks": 13, "follow": bool(request.get("follow")), "min_health": min_health, "max_seconds": max_seconds,
        }

    def _end_fight(self, robot: FakeRobot, ended: str, message: str, hits: int = 0) -> None:
        fight = robot.fight
        target = fight["target"]
        damage = hits * FIGHT_DAMAGE
        robot.last_fight = {
            "entity": target["type"], "entity_id": target["entity_id"], "killed": ended == "killed", "ended": ended,
            "message": message, "swings": hits, "hits": hits, "damage": damage,
            "target_health": max(0.0, float(target.get("health", 20.0)) - damage), "seconds": round(self.fight_seconds, 1),
            "health": robot.health, "health_lost": round(fight["start_health"] - robot.health, 1),
        }
        robot.fight = None

    def _do_use_item(self, robot: FakeRobot, request: dict) -> dict:
        self._tick(robot)
        self._cancel_use(robot)
        hold_ticks = None
        if request.get("hold_seconds") is not None:
            seconds = float(request["hold_seconds"])
            if not 0.05 <= seconds <= 60.0:
                raise fail("invalid_request", "hold_seconds must be between 0.05 and 60")
            hold_ticks = max(1, round(seconds * 20))
        item, count = robot.slots[robot.selected_slot]
        if count == 0:
            raise fail("missing_item", "The selected hotbar slot is empty")
        before = self._counts(robot)
        projectiles: list[str] = []
        if item == "minecraft:crossbow" and robot.crossbow_charged:
            robot.crossbow_charged = False
            projectiles.append("minecraft:arrow")
        elif item in HOLD_TICKS:
            if item in ("minecraft:bow", "minecraft:crossbow") and self._counts(robot).get("minecraft:arrow", 0) == 0:
                raise fail("missing_item", f"{item} needs ammunition in the hotbar (arrows)")
            ticks = hold_ticks or HOLD_TICKS[item]
            robot.using = (robot.selected_slot, item, ticks)
            robot.use_until = time.monotonic() + ticks * self.tick_seconds
            robot.last_use = None
            return {"used": item, "accepted": True, "holding": True, "hold_ticks": ticks, "eta_ticks": ticks, "selected_item": item}
        elif item in CONSUMABLES:
            raise fail("interaction_unavailable", f"Robots cannot eat or drink {item}; they eat iron and copper ingots with eat")
        elif item in THROWABLES:
            self._take(robot, item, 1)
            projectiles.append(THROWABLES[item])
        result = {"used": item, "accepted": True}
        result.update(self._use_outcome(robot, before, projectiles))
        return result

    def _release_use(self, robot: FakeRobot) -> None:
        slot, item, ticks = robot.using
        before = self._counts(robot)
        projectiles: list[str] = []
        charged = None
        if item == "minecraft:bow" and self._take(robot, "minecraft:arrow", 1):
            projectiles.append("minecraft:arrow")
        elif item == "minecraft:crossbow":
            if ticks >= HOLD_TICKS[item] and self._take(robot, "minecraft:arrow", 1):
                robot.crossbow_charged = True
            charged = robot.crossbow_charged
        elif item == "minecraft:trident" and ticks >= HOLD_TICKS[item]:
            self._take(robot, item, 1)
            projectiles.append("minecraft:trident")
        outcome = {"held_ticks": ticks, **self._use_outcome(robot, before, projectiles)}
        if charged is not None:
            outcome["charged"] = charged
        self._end_use(robot, True, "", outcome)

    def _cancel_use(self, robot: FakeRobot) -> None:
        if robot.using is not None:
            self._end_use(robot, False, "The use was interrupted before it finished")

    def _end_use(self, robot: FakeRobot, completed: bool, message: str, outcome: Optional[dict] = None) -> None:
        result: dict[str, Any] = {"used": robot.using[1], "completed": completed}
        if message:
            result["message"] = message
        result.update(outcome or {})
        robot.using = None
        robot.last_use = result

    def _counts(self, robot: FakeRobot) -> dict[str, int]:
        counts: dict[str, int] = {}
        for item, count in robot.slots:
            if count > 0:
                counts[item] = counts.get(item, 0) + count
        return counts

    def _take(self, robot: FakeRobot, item: str, count: int) -> bool:
        for slot in robot.slots:
            if slot[0] == item and slot[1] >= count:
                slot[1] -= count
                if slot[1] == 0:
                    slot[0] = "minecraft:air"
                return True
        return False

    def _use_outcome(self, robot: FakeRobot, before: dict[str, int], projectiles: list[str]) -> dict[str, Any]:
        after = self._counts(robot)
        spent = {item: before[item] - after.get(item, 0) for item in sorted(before) if after.get(item, 0) < before[item]}
        gained = {item: after[item] - before.get(item, 0) for item in sorted(after) if after[item] > before.get(item, 0)}
        return {"projectiles": projectiles, "spent": spent, "gained": gained, "selected_item": robot.slots[robot.selected_slot][0]}

    def _do_use_on_entity(self, robot: FakeRobot, request: dict) -> dict:
        self._cancel_use(robot)
        entity = self._entity_in_crosshair(robot)
        if entity is None or entity["_distance"] > REACH:
            raise fail("not_looking_at_entity", "No entity is in the crosshair within reach")
        item = robot.slots[robot.selected_slot][0]
        return {"entity": entity["type"], "entity_id": entity["entity_id"], "used": item, "accepted": True, "selected_item": item}


def _kill(connection: ServerConnection) -> None:
    """Drop the TCP connection without a closing handshake (like a ping timeout or crash)."""
    try:
        connection.socket.shutdown(socket.SHUT_RDWR)
    except OSError:
        pass
    try:
        connection.socket.close()
    except OSError:
        pass


def _error(code: str, message: str) -> dict[str, Any]:
    return {"type": "error", "code": code, "message": message}


def _died(death: dict[str, Any]) -> dict[str, Any]:
    return dict(_error("died", death["message"]), death=death)
