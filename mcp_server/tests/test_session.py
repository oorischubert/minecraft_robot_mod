"""Session-level behaviour that is awkward to drive through an MCP client: cancellation and shutdown."""

from __future__ import annotations

import threading
import time

import pytest

from minebot_mcp.actions import Actions
from minebot_mcp.session import Cancelled, RobotSession, Settings


def make(fake, **overrides) -> tuple[RobotSession, Actions]:
    values = dict(url=fake.url, keepalive_interval=0.0, poll_interval=0.05, chat_poll_interval=0.05, socket_timeout=5.0)
    values.update(overrides)
    session = RobotSession(Settings(**values))
    return session, Actions(session)


def test_cancelled_move_stops_the_robot(fake):
    fake.move_seconds = 5.0
    session, actions = make(fake)
    cancel = threading.Event()
    threading.Timer(0.3, cancel.set).start()
    started = time.monotonic()
    with pytest.raises(Cancelled):
        actions.move_to(cancel, 8.5, 8.5, None, 1.0, 60.0)
    assert time.monotonic() - started < 2.0
    assert fake.requests_for("stop")
    assert fake.robots["ROBOT001"].move_target is None
    session.close()


def test_shutdown_interrupts_wait_for_chat_and_releases_robot(fake):
    session, actions = make(fake)
    threading.Timer(0.3, session.close).start()
    started = time.monotonic()
    with pytest.raises(Cancelled):
        actions.wait_for_chat(threading.Event(), 60.0)
    assert time.monotonic() - started < 2.0
    time.sleep(0.1)
    assert not fake.robots["ROBOT001"].connected


def test_rejected_connect_does_not_leak_socket(fake):
    fake.robots["ROBOT001"].connected = True
    session, _ = make(fake, code="ROBOT001")
    before = fake.connection_count
    with pytest.raises(Exception) as info:
        session.call(lambda r: r.status())
    assert "program_running" in str(info.value)
    time.sleep(0.2)
    assert len(fake.connections) == 0 and fake.connection_count == before + 1
