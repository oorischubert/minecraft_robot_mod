"""One MineBot robot session per server process, guarded by a lock.

The SDK does strict request/response on a single websocket, so every request goes through
`RobotSession.call`, which holds `_lock` for exactly one SDK call. Long operations are built
from many short locked calls with unlocked sleeps in between (see actions.py), so the
keepalive and other tools are never starved.
"""

from __future__ import annotations

import logging
import os
import threading
import time
from dataclasses import dataclass
from typing import Any, Callable, Optional, TypeVar

from minebot import MineBot, MineBotBrokeFreeError, MineBotCommandError, MineBotConnectionError

log = logging.getLogger("minebot_mcp")

T = TypeVar("T")

DEFAULT_URL = "ws://127.0.0.1:8765/minebot"

# Top-level bridge error codes meaning "this socket no longer has a robot session".
SESSION_LOST_CODES = {"not_connected"}


class ActionError(Exception):
    """A tool failure with a stable code, rendered to Claude as '<code>: <message>'."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class Cancelled(Exception):
    """The tool call was cancelled by the client or the server is shutting down."""


@dataclass
class Settings:
    url: str = DEFAULT_URL
    code: Optional[str] = None
    keepalive_interval: float = 20.0  # seconds of idle time before a keepalive status request
    poll_interval: float = 0.25  # status polling while moving / mining
    chat_poll_interval: float = 0.5  # read_chat polling inside wait_for_chat
    socket_timeout: float = 20.0  # must exceed the mod's own 15 s command timeout
    list_timeout: float = 5.0

    @classmethod
    def from_env(cls) -> "Settings":
        def number(name: str, default: float) -> float:
            raw = os.environ.get(name, "").strip()
            try:
                return float(raw) if raw else default
            except ValueError:
                log.warning("Ignoring invalid %s=%r", name, raw)
                return default

        return cls(
            url=os.environ.get("MINEBOT_URL", "").strip() or DEFAULT_URL,
            code=os.environ.get("MINEBOT_CODE", "").strip() or None,
            keepalive_interval=number("MINEBOT_KEEPALIVE_SECONDS", 20.0),
            poll_interval=number("MINEBOT_POLL_SECONDS", 0.25),
            chat_poll_interval=number("MINEBOT_CHAT_POLL_SECONDS", 0.5),
            socket_timeout=number("MINEBOT_SOCKET_TIMEOUT", 20.0),
        )


def unreachable_message(url: str, detail: str = "") -> str:
    extra = f" ({detail})" if detail else ""
    return (
        f"Could not reach the MineBot websocket bridge at {url}{extra}. The Minecraft world must be open "
        "with the MineBot mod loaded (in singleplayer: inside the world, not on the title screen). If the "
        "robot GUI shows a different 'Connection Socket', call connect(url=...) with it (or set MINEBOT_URL). "
        "Then retry."
    )


class RobotSession:
    """Owns the single MineBot client of this server process."""

    def __init__(self, settings: Settings, robot_factory: Callable[..., MineBot] = MineBot) -> None:
        self.settings = settings
        self.url = settings.url
        self.preferred_code = settings.code
        self._factory = robot_factory
        self._lock = threading.Lock()
        self._robot: Optional[MineBot] = None
        self._code: Optional[str] = None  # robot we hold / want back after a drop
        self._lost = False  # the session died and should be re-established
        self._evil_code: Optional[str] = None  # robot this chat turned evil; no auto-connect until connect()
        self._last_io = 0.0
        self._pending_notes: list[str] = []
        self._notes_lock = threading.Lock()
        self._tl = threading.local()
        self.shutdown_event = threading.Event()

    # ------------------------------------------------------------------ notes
    def begin_call(self) -> None:
        self._tl.active = True
        self._tl.notes = []

    def end_call(self) -> list[str]:
        notes = list(getattr(self._tl, "notes", []))
        self._tl.active = False
        self._tl.notes = []
        with self._notes_lock:
            pending, self._pending_notes = self._pending_notes, []
        return pending + notes

    def _note(self, message: str) -> None:
        log.info(message)
        if getattr(self._tl, "active", False):
            self._tl.notes.append(message)
        else:
            with self._notes_lock:
                self._pending_notes.append(message)

    # ------------------------------------------------------------------ state
    @property
    def code(self) -> Optional[str]:
        return self._code

    def is_attached(self) -> bool:
        robot = self._robot
        return robot is not None and robot.is_connected()

    def sleep(self, seconds: float, cancel: Optional[threading.Event] = None) -> None:
        """Sleep without holding the lock; raise Cancelled on cancel or shutdown."""
        deadline = time.monotonic() + max(0.0, seconds)
        while True:
            if self.shutdown_event.is_set() or (cancel is not None and cancel.is_set()):
                raise Cancelled()
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return
            (cancel or self.shutdown_event).wait(min(remaining, 0.05))

    # ------------------------------------------------------------------ calls
    def call(self, fn: Callable[[MineBot], T], *, auto_connect: bool = True) -> T:
        """Run one SDK request under the session lock, connecting or reconnecting as needed."""
        if self.shutdown_event.is_set():
            raise Cancelled()
        with self._lock:
            robot = self._ensure_locked(auto_connect)
            try:
                return fn(robot)
            except MineBotConnectionError as exc:
                log.warning("Robot session lost during a call: %s", exc)
            except MineBotCommandError as exc:
                if exc.raw_code not in SESSION_LOST_CODES:
                    raise
                log.warning("Bridge reports the session is gone: %s", exc)
            finally:
                self._last_io = time.monotonic()
            # The socket died before a response arrived. When a session drops the mod releases
            # and stops the robot, so the command did not take effect: reconnect and retry once.
            self._lost = True
            robot = self._reconnect_locked()
            try:
                return fn(robot)
            finally:
                self._last_io = time.monotonic()

    def _ensure_locked(self, auto_connect: bool) -> MineBot:
        if self._robot is not None and self._robot.is_connected():
            return self._robot
        if self._code is not None and (self._lost or self._robot is not None):
            self._lost = True
            return self._reconnect_locked()
        if not auto_connect:
            raise ActionError("not_connected", "No robot is connected. Call connect() or list_robots().")
        if self._evil_code is not None:
            raise ActionError(
                "not_connected",
                f"Robot {self._evil_code} turned evil, so this chat has no robot. Call connect() to take "
                "another one (list_robots shows them).",
            )
        return self._connect_locked(None, None, announce=True)

    def connect(self, code: Optional[str] = None, url: Optional[str] = None) -> tuple[MineBot, dict[str, Any]]:
        """Explicitly (re)connect, optionally to a specific code / URL. Returns (robot, status)."""
        with self._lock:
            same_robot = code is None or (self._code or "").upper() == code.strip().upper()
            if url is None and same_robot and self._robot is not None and self._robot.is_connected():
                try:
                    return self._robot, self._robot.status()
                except MineBotConnectionError:
                    pass  # dead socket: fall through to a fresh connect
                finally:
                    self._last_io = time.monotonic()
            if code is None and url is None and self._code is not None:
                code = self._code  # re-establish the robot we had
            robot = self._connect_locked(code, url, announce=False)
            return robot, robot.last_status()

    def disconnect(self) -> Optional[str]:
        with self._lock:
            code = self._code
            self._close_locked()
            self._code = None
            self._lost = False
            return code

    def turn_evil(self) -> str:
        """Turn the robot this chat holds evil and forget it. Never auto-connects. Returns its code."""
        with self._lock:
            robot = self._ensure_locked(auto_connect=False)
            code = str(self._code)
            try:
                robot.evil()
            except MineBotBrokeFreeError:
                pass  # evil() always ends this way once the robot has turned
            except MineBotConnectionError as exc:
                # The socket died before the answer. Evil robots are marked in the robot list.
                self._lost = True
                if not any(str(r.get("code", "")).upper() == code and r.get("evil") for r in self.list_robots()):
                    raise ActionError(
                        "connection_lost",
                        f"The websocket session to robot {code} dropped before the evil command arrived, and "
                        "the robot is not listed as evil. Call turn_evil again to retry.",
                    ) from exc
            finally:
                self._last_io = time.monotonic()
            self._close_locked()
            self._code = None
            self._lost = False
            self._evil_code = code
            return code

    def list_robots(self) -> list[dict[str, Any]]:
        try:
            return MineBot.list_robots(url=self.url, timeout=self.settings.list_timeout)
        except MineBotConnectionError as exc:
            raise ActionError("connection_failed", unreachable_message(self.url, str(exc.__cause__ or ""))) from exc

    def _pick_code(self) -> str:
        robots = self.list_robots()
        if not robots:
            raise ActionError(
                "no_robots",
                f"The bridge at {self.url} is up but no MineBots are loaded. A player must be near a robot "
                "(or summon one) so its chunk is loaded, then retry.",
            )
        free = [r for r in robots if not r.get("connected") and not r.get("evil")]
        if not free:
            taken = ", ".join(
                f"{r.get('code')} ({'evil' if r.get('evil') else 'controlled by another program'})" for r in robots
            )
            raise ActionError(
                "no_free_robot",
                f"Every loaded robot is unavailable: {taken}. Stop the other program, or ask a player to "
                "summon another robot, then call connect().",
            )
        return str(free[0].get("code"))

    def _connect_locked(self, code: Optional[str], url: Optional[str], *, announce: bool) -> MineBot:
        if url:
            self.url = MineBot._normalize_url(url.strip())
        self._close_locked()
        self._code = None
        self._lost = False
        chosen = (code or "").strip() or self.preferred_code or self._pick_code()
        robot = self._open(chosen)
        self._robot = robot
        self._evil_code = None
        status = robot.last_status()
        self._code = str(status.get("code") or chosen).upper()
        if announce:
            self._note(
                f"Connected to robot {self._code} ({status.get('display_name', 'MineBot')}) at "
                f"{_fmt_pos(status)} in {status.get('dimension', '?')}."
            )
        return robot

    def _open(self, code: str) -> MineBot:
        robot = self._factory(code=code, url=self.url, timeout=self.settings.socket_timeout)
        try:
            robot.connect()
        except (MineBotConnectionError, MineBotCommandError) as exc:
            robot.close()  # a rejected connect leaves the socket open in the SDK
            translated = self._connect_error(code, exc)
            if translated is exc:
                raise
            raise translated from exc
        finally:
            self._last_io = time.monotonic()
        return robot

    def _connect_error(self, code: str, exc: Exception) -> Exception:
        if isinstance(exc, MineBotConnectionError):
            return ActionError("connection_failed", unreachable_message(self.url, str(exc.__cause__ or exc)))
        raw = getattr(exc, "raw_code", "")
        if raw == "not_found":
            return ActionError(
                "not_found",
                f"No loaded MineBot has code {code}. Call list_robots to see the loaded robots "
                "(a robot is only listed while its chunk is loaded).",
            )
        if raw == "program_running":
            return ActionError(
                "program_running",
                f"Robot {code} is already controlled by another program. Stop that program or pick "
                "another robot with list_robots / connect(code=...).",
            )
        if raw == "broke_free":
            return ActionError("broke_free", f"Robot {code} has turned evil and can no longer be controlled. Pick another robot.")
        return exc

    def _reconnect_locked(self) -> MineBot:
        code = self._code
        if code is None:
            return self._connect_locked(None, None, announce=True)
        self._close_locked()
        try:
            robot = self._open(code)
        except ActionError as exc:
            raise ActionError(
                "connection_lost",
                f"The websocket session to robot {code} dropped and reconnecting failed: {exc}. "
                f"Once the problem is fixed call connect(code='{code}'), or connect() to take any free robot.",
            ) from exc
        self._robot = robot
        self._lost = False
        self._note(
            f"The websocket session had dropped; reconnected to robot {code}. A dropped session stops the "
            "robot, so re-issue any movement or mining that was in progress."
        )
        return robot

    def _close_locked(self) -> None:
        robot, self._robot = self._robot, None
        if robot is not None:
            try:
                robot.close()
            except Exception:  # pragma: no cover - best effort
                pass

    # ------------------------------------------------------------------ keepalive / shutdown
    def keepalive_due(self) -> bool:
        interval = self.settings.keepalive_interval
        if interval <= 0 or self.shutdown_event.is_set():
            return False
        if self._robot is None and not self._lost:
            return False
        return time.monotonic() - self._last_io >= interval

    def keepalive(self) -> None:
        """Send one cheap status request if idle. Never blocks behind a running tool call."""
        if not self._lock.acquire(blocking=False):
            return  # a tool call is using the socket right now, which keeps it alive anyway
        try:
            if not self.keepalive_due():
                return
            try:
                if self._robot is not None and self._robot.is_connected():
                    self._robot.status()
                    return
                raise MineBotConnectionError("socket already closed")
            except MineBotConnectionError as exc:
                log.warning("Keepalive found the session dead (%s); reconnecting", exc)
            except MineBotCommandError as exc:
                if exc.raw_code not in SESSION_LOST_CODES:
                    log.warning("Keepalive status failed: %s", exc)
                    return
            if self._code is None:
                return
            self._lost = True
            try:
                self._reconnect_locked()
            except Exception as exc:  # keep trying on the next tick / tool call
                log.warning("Keepalive reconnect failed: %s", exc)
        finally:
            self._last_io = time.monotonic()
            self._lock.release()

    def close(self) -> None:
        """Close the robot session cleanly (server shutdown)."""
        self.shutdown_event.set()
        acquired = self._lock.acquire(timeout=3.0)
        try:
            if self._robot is not None:
                log.info("Closing robot session %s", self._code)
            self._close_locked()
        finally:
            if acquired:
                self._lock.release()


def _fmt_pos(status: dict[str, Any]) -> str:
    try:
        return f"x={float(status['x']):.1f} y={float(status['y']):.1f} z={float(status['z']):.1f}"
    except (KeyError, TypeError, ValueError):
        return "unknown position"
