"""Launch the server with the exact command from the repo-root .mcp.json (cwd = repo root, as Claude Code
does for project-scoped servers) and speak MCP over real stdio. Also checks stdout carries only JSON-RPC
and that the robot session is closed when the client goes away."""

from __future__ import annotations

import json
import os
import queue
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def expand(value: str, env: dict[str, str]) -> str:
    """Claude Code style ${VAR} / ${VAR:-default} expansion."""

    def replace(match: re.Match) -> str:
        name, default = match.group(1), match.group(3)
        return env.get(name) or (default if default is not None else "")

    return re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(:-([^}]*))?\}", replace, value)


class LineReader:
    def __init__(self, stream) -> None:
        self.lines: "queue.Queue[str]" = queue.Queue()
        self.all: list[str] = []
        threading.Thread(target=self._pump, args=(stream,), daemon=True).start()

    def _pump(self, stream) -> None:
        for line in iter(stream.readline, ""):
            self.all.append(line)
            self.lines.put(line)

    def read_json(self, timeout: float) -> dict:
        return json.loads(self.lines.get(timeout=timeout))


def test_mcp_json_command_starts_server_over_stdio(fake):
    config = json.loads((REPO_ROOT / ".mcp.json").read_text())
    entry = config["mcpServers"]["minebot"]
    assert entry.get("type", "stdio") == "stdio"

    user_env = {"MINEBOT_URL": fake.url}  # MINEBOT_CODE unset -> default "" -> auto-pick
    command = shutil.which(expand(entry["command"], user_env))
    assert command, "uv must be on PATH"
    args = [expand(a, user_env) for a in entry["args"]]
    env = {k: v for k, v in os.environ.items() if k not in ("VIRTUAL_ENV", "PYTHONPATH")}
    env.update({k: expand(v, user_env) for k, v in entry.get("env", {}).items()})
    assert env["MINEBOT_URL"] == fake.url and env["MINEBOT_CODE"] == ""

    proc = subprocess.Popen(
        [command, *args],
        cwd=REPO_ROOT,
        env=env,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
    )
    out = LineReader(proc.stdout)
    err = LineReader(proc.stderr)

    def send(message: dict) -> None:
        proc.stdin.write(json.dumps(message) + "\n")
        proc.stdin.flush()

    try:
        send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "pytest", "version": "0"}}})
        init = out.read_json(timeout=120)
        assert init["id"] == 1, init
        assert init["result"]["serverInfo"]["name"] == "minebot"
        assert "wait_for_chat" in init["result"]["instructions"]

        send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        send({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        tools = out.read_json(timeout=30)
        names = {t["name"] for t in tools["result"]["tools"]}
        assert len(names) == 54 and {"wait_for_chat", "say", "mine_block", "place_block", "collect_items", "snapshot"} <= names

        send({"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "status", "arguments": {}}})
        status = out.read_json(timeout=30)
        text = status["result"]["content"][0]["text"]
        assert not status["result"].get("isError"), text
        assert "Connected to robot ROBOT001" in text
        assert fake.robots["ROBOT001"].connected

        proc.stdin.close()  # client goes away -> server must exit and release the robot
        assert proc.wait(timeout=20) == 0
        deadline = time.monotonic() + 5
        while fake.robots["ROBOT001"].connected and time.monotonic() < deadline:
            time.sleep(0.05)
        assert not fake.robots["ROBOT001"].connected
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait()

    for line in out.all:  # stdout carries nothing but JSON-RPC
        assert json.loads(line)["jsonrpc"] == "2.0"
    assert any("MineBot MCP server starting" in line for line in err.all)
