"""Unit tests of the robot program evaluator with a fake, in-memory robot API (no bridge needed)."""

from __future__ import annotations

import threading
import time

import pytest

from minebot_mcp.program import MAX_OPS, Program, ProgramHalt, RobotError


def run(code: str, api=None, helpers=None, *, max_steps=100, seconds=5.0, cancel=None, on_step=None):
    program = Program(code, api or {}, helpers or {}, max_steps=max_steps, deadline=time.monotonic() + seconds, cancel=cancel, on_step=on_step)
    return program.run(), program


def halt(code: str, **kwargs) -> ProgramHalt:
    with pytest.raises(ProgramHalt) as info:
        run(code, **kwargs)
    return info.value


def test_values_loops_and_return():
    code = """
total = 0
names = []
for i in range(5):
    if i % 2 == 0:
        continue
    total += i
    names.append(f"n{i:02d}")
while total < 20:
    total = total + 3
    if total > 15:
        break
d = {"a": 1, "b": [1, 2, 3]}
d["c"] = total
squares = [v * v for v in range(4) if v > 0]
first, second = (10, 20)
return {"total": total, "names": names, "c": d.get("c"), "sq": squares, "sum": first + second, "sl": names[1:], "ok": 3 in d["b"] and not False}
"""
    value, program = run(code)
    assert value == {"total": 16, "names": ["n01", "n03"], "c": 16, "sq": [1, 4, 9], "sum": 30, "sl": ["n03"], "ok": True}
    assert program.steps == 0 and program.ops > 10


def test_robot_calls_count_steps_and_pass_arguments():
    calls = []

    def move_to(x, z, y=None, speed=1.0, timeout=60.0):
        calls.append(("move_to", x, z, y, timeout))
        return {"arrived": True, "x": x, "y": y, "z": z}

    def status():
        return {"health": 20.0, "x": 1.5}

    seen = []
    value, program = run(
        "r = move_to(10.5, -3.5, y=64, timeout=5)\nh = status()['health']\nreturn [r['x'], h]",
        {"move_to": move_to, "status": status}, on_step=lambda name, result: seen.append(name),
    )
    assert value == [10.5, 20.0] and program.steps == 2 and seen == ["move_to", "status"]
    assert calls == [("move_to", 10.5, -3.5, 64, 5)]


def test_robot_error_is_catchable_by_name_or_code():
    def mine_block(x, y, z):
        raise RobotError("obstructed", "The view is blocked")

    code = """
log = []
try:
    mine_block(1, 2, 3)
except RobotError as e:
    note = e["code"] + "/" + e["message"]
try:
    mine_block(1, 2, 3)
except movement_failed:
    note = "wrong"
except obstructed:
    note = note + "+by code"
return note
"""
    value, _ = run(code, {"mine_block": mine_block})
    assert value == "obstructed/The view is blocked+by code"

    h = halt("mine_block(1, 2, 3)\n", api={"mine_block": mine_block})
    assert h.kind == "error" and h.message == "obstructed: The view is blocked" and h.line == 1

    h = halt("try:\n    mine_block(1, 2, 3)\nexcept wrong_block:\n    pass\n", api={"mine_block": mine_block})
    assert h.kind == "error" and h.line == 2


def test_helpers_and_builtins():
    lines = []
    value, _ = run(
        "log('a', 1)\nlog(min(3, 2), max([1, 9]), abs(-2), round(2.567, 1), len('abc'), sorted([3, 1]), sum([1, 2]))\nreturn str(int(2.9)) + '!'",
        helpers={"log": lambda *parts: lines.append(" ".join(str(p) for p in parts))},
    )
    assert value == "2!" and lines == ["a 1", "2 9 2 2.6 3 [1, 3] 3"]


def test_disallowed_constructs_are_refused_with_a_line():
    cases = {
        "import os\n": (1, "import"),
        "x = 1\ndef f():\n    pass\n": (2, "def"),
        "f = lambda: 1\n": (1, "lambda"),
        "x = {}\nx.__class__\n": (2, "__class__"),
        "s = 'a'\ns.__len__()\n": (2, "__len__"),
        "d = {}\nd.update\n": (2, "attribute access"),
        "raise Exception()\n": (1, "raise"),
        "with x:\n    pass\n": (1, "with"),
        "x = 1\nx = y\n": (2, "unknown name 'y'"),
        "foo()\n": (1, "unknown function 'foo'"),
        "status = 3\n": (1, "robot function"),
        "x = 1 / 0\n": (1, "ZeroDivisionError"),
        "x = [1][5]\n": (1, "IndexError"),
        "x = 'a' + 1\n": (1, "TypeError"),
        "x = (1\n": (1, "syntax error"),
        "break\n": (1, "outside a loop"),
        "'abc'.format_map({})\n": (1, "no method"),
        "x = {**{}}\n": (1, "unpacking"),
    }
    for code, (line, text) in cases.items():
        h = halt(code, api={"status": lambda: {}})
        assert h.kind == "error", code
        assert h.line == line, (code, h.line, h.message)
        assert text in h.message, (code, h.message)


def test_budgets_deadline_and_cancel():
    h = halt("while True:\n    pass\n")
    assert h.kind == "budget" and str(MAX_OPS) in h.message and h.line == 2

    h = halt("for i in range(10):\n    status()\n", api={"status": lambda: {}}, max_steps=3)
    assert h.kind == "budget" and "3 robot calls" in h.message

    h = halt("while True:\n    x = 1\n", seconds=0.05)
    assert h.kind == "timeout"

    cancel = threading.Event()
    cancel.set()
    h = halt("x = 1\n", cancel=cancel)
    assert h.kind == "cancelled"


def test_on_step_can_stop_the_program():
    def on_step(name, result):
        raise ProgramHalt("stopped", "health too low")

    h = halt("status()\nx = 2\n", api={"status": lambda: {}}, on_step=on_step)
    assert h.kind == "stopped" and h.message == "health too low"


def test_program_without_return_returns_none_and_keeps_variables():
    value, program = run("x = 1\nx += 2\n")
    assert value is None and program.vars["x"] == 3
