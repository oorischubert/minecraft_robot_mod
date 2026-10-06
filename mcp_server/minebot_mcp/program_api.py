"""The robot functions a program may call: the MCP tools under the same names, with the same arguments
and defaults, returning the same results as dicts. Failures become RobotError (catchable in the program),
except the ones that end the program whatever it does: the robot died, the connection is gone, another
program took the robot."""

from __future__ import annotations

import threading
from typing import Any, Callable, Optional

from minebot import MineBotCommandError, MineBotConnectionError

from .actions import Actions, norm_id
from .program import ProgramHalt, RobotError
from .session import ActionError, Cancelled

# Errors no program can catch: the robot is not there to be driven any more.
ABORT_CODES = {"died", "not_connected", "connection_lost", "connection_failed", "broke_free", "program_running", "no_robots", "no_free_robot"}


def make_api(actions: Actions, cancel: threading.Event, remaining: Callable[[], float]) -> dict[str, Callable[..., Any]]:
    """The functions, bound to this run's cancel event and to the program's remaining time (which caps
    every per-call timeout, so one stuck move cannot eat the whole budget)."""

    def cap(timeout: float) -> float:
        return max(0.5, min(float(timeout), remaining()))

    def guarded(fn: Callable[..., Any]) -> Callable[..., Any]:
        def call(*args: Any, **kwargs: Any) -> Any:
            try:
                return fn(*args, **kwargs)
            except ActionError as exc:
                raise _robot_error(exc.code, exc.message) from None
            except MineBotCommandError as exc:
                raise _robot_error(exc.raw_code, exc.detail) from None
            except MineBotConnectionError as exc:
                raise ProgramHalt("aborted", f"connection_failed: {exc}") from None
            except Cancelled:
                raise
        return call

    def _robot_error(code: str, message: str) -> Exception:
        if code in ABORT_CODES:
            return ProgramHalt("aborted", f"{code}: {message}")
        return RobotError(code, message)

    def opt_int(value: Any) -> Optional[int]:
        return None if value is None else int(value)

    api: dict[str, Callable[..., Any]] = {
        "status": lambda: actions.status(),
        "move_to": lambda x, z, y=None, speed=1.0, timeout=60.0: actions.move_to(cancel, x, z, y, speed, cap(timeout)),
        "move_by": lambda forward, right=0.0, speed=1.0, timeout=30.0: actions.move_by(cancel, forward, right, speed, cap(timeout)),
        "move": lambda forward, right=0.0, duration=1.0: actions.move(cancel, forward, right, duration),
        "turn_to": lambda yaw=None, pitch=None: actions.turn_to(yaw, pitch),
        "turn_by": lambda yaw=0.0, pitch=0.0: actions.turn_by(yaw, pitch),
        "look_at": lambda x, y, z: actions.look_at(x, y, z),
        "look_at_entity": lambda entity_id: actions.look_at_entity(entity_id),
        "jump": lambda: actions.simple("jump"),
        "pillar_up": lambda count=1, item=None: actions.pillar_up(cancel, count, item),
        "bridge": lambda direction, count=1, item=None: actions.bridge(cancel, direction, count, item),
        "crouch": lambda enabled: actions.crouch(enabled),
        "center": lambda: actions.simple("center"),
        "stop": lambda: actions.simple("stop"),
        "enter_vehicle": lambda: actions.simple("enter_vehicle"),
        "exit_vehicle": lambda: actions.simple("exit_vehicle"),
        "go_to_player": lambda name, distance=2.0, timeout=60.0: actions.go_to_player(cancel, name, distance, cap(timeout)),
        "mine": lambda: actions.mine(cancel),
        "mine_block": lambda x, y, z: actions.mine_block(cancel, x, y, z),
        "collect_items": lambda radius=6.0, item=None: actions.collect_items(cancel, radius, item),
        "place": lambda: actions.simple("place"),
        "place_block": lambda x, y, z, item=None: actions.place_block(x, y, z, item),
        "use_item": lambda hold_seconds=None: actions.use_item(cancel, hold_seconds),
        "use_on_entity": lambda entity_id=None: actions.entity_action("use_on_entity", entity_id),
        "attack_entity": lambda entity_id=None, until_dead=False, follow=True, guard=True, min_health=8.0, max_seconds=30.0:
            actions.attack_entity(cancel, entity_id, until_dead, follow, guard, min_health, min(float(max_seconds), max(1.0, remaining()))),
        "wait": lambda seconds=10.0, until_hurt=True, until_entity=None, within=4.0, until_health_below=None:
            actions.wait(cancel, cap(seconds), until_hurt, until_entity, within, until_health_below),
        "inventory": lambda: actions.inventory(),
        "select_slot": lambda slot: actions.select_slot(slot),
        "equip": lambda item: actions.equip(item),
        "drop": lambda slot=None, count=None: actions.drop(slot, count),
        "move_item": lambda from_slot, to_slot, count=None: actions.simple(
            "move_item", **{"from": int(from_slot), "to": int(to_slot), **({"count": int(count)} if count is not None else {})}
        ),
        "refuel": lambda count=None: actions.simple("refuel", **({"count": int(count)} if count is not None else {})),
        "eat": lambda item=None, count=None: actions.simple(
            "eat", **({"item": norm_id(item)} if item is not None else {}), **({"count": int(count)} if count is not None else {})
        ),
        "craft": lambda item, count=None: actions.simple("craft", item=norm_id(item), **({"count": int(count)} if count is not None else {})),
        "chest_inspect": lambda: actions.simple("chest_inspect"),
        "chest_put": lambda item, count=1: actions.simple("chest_place", item=norm_id(item), count=int(count)),
        "chest_take": lambda item, count=1: actions.simple("chest_take", item=norm_id(item), count=int(count)),
        "furnace_inspect": lambda: actions.simple("furnace_inspect"),
        "furnace_put": lambda input_item=None, input_count=1, fuel_item=None, fuel_count=1: actions.furnace_put(input_item, input_count, fuel_item, fuel_count),
        "furnace_take": lambda item=None, count=None, fuel_item=None, fuel_count=1: actions.furnace_take(item, opt_int(count), fuel_item, fuel_count),
        "inspect": lambda: actions.inspect(),
        "scan_blocks": lambda radius=8, blocks=None, limit=32, center_x=None, center_y=None, center_z=None: actions.scan_blocks(
            int(radius), blocks, int(limit), _centre(center_x, center_y, center_z)
        ),
        "scan_entities": lambda radius=16.0, types=None, players_only=False, limit=16: actions.scan_entities(radius, types, players_only, limit),
        "nearby_players": lambda radius=64.0: actions.nearby_players(radius),
        "environment": lambda: actions.simple("environment"),
        "read_chat": lambda peek=False: actions.read_chat(peek),
        "say": lambda message, to=None: actions.say(message, to),
    }
    return {name: guarded(fn) for name, fn in api.items()}


def _centre(x: Any, y: Any, z: Any) -> Optional[tuple[int, int, int]]:
    values = (x, y, z)
    if all(v is None for v in values):
        return None
    if any(v is None for v in values):
        raise RobotError("invalid_request", "give all of center_x, center_y and center_z, or none")
    return (int(x), int(y), int(z))
