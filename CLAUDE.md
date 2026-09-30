# MineBot

Fabric mod (Minecraft 1.21.11) that adds robots, plus two ways to control them from outside the game.

## How the pieces fit

| Piece | Where | Role |
| --- | --- | --- |
| Mod | `src/` | Robot entity, chat inbox, and a websocket bridge (`ws://<host>:8765/minebot`, JSON request/response). One program per robot. |
| Python SDK | `python_sdk/` | Synchronous client for the bridge. One method per command. |
| MCP server | `mcp_server/`, started by `.mcp.json` | Wraps the SDK as the `minebot` tools. One Claude Code chat drives one robot. |

Players talk to a robot in game chat: `@<code> ...`, `@<robot name> ...`, `@bot ...` (nearest robot) or `@all ...`.
The mod queues those lines per robot; you read them with `wait_for_chat`.

## Driving a robot

Play sessions are started with `./play.sh`, which gives Claude the `minebot` tools and nothing else.

You only act while you are looping on `wait_for_chat`. When asked to take control or start listening:

1. Call `status`. It connects to the first free robot (use `connect(code=...)` for a specific one).
2. Loop on `wait_for_chat`. An empty result is normal; call it again.
3. For each order: acknowledge with `say(message, to=<sender>)`, do the work, report the result, return to step 2.

Fair play, which is not optional:

- The robot only knows what it can see. `scan_blocks` and `scan_entities` report what is in its line of sight; nothing behind walls or underground. An empty scan means "not in view from here".
- To find something that is not in view, explore as a player would: walk, look around, dig, open chests. Say so when you have not found it yet. Never state a location that no tool result showed you.
- While driving a robot, act only through the `minebot` tools. Do not read world or server files, run server commands or RCON, or call the SDK or websocket directly to learn about the world or to change it.

Things that are easy to get wrong:

- Coordinates are Minecraft F3 values. A robot's `y` is its feet. Yaw 0 is south (+Z), 90 west, 180 north, -90 east.
- Mining, placing and using act on the crosshair within 4 blocks. Prefer `mine_block` and `place_block`, which aim and verify. Otherwise `look_at`, then check the returned crosshair before acting.
- Nearly every action needs blaze powder energy. On `out_of_energy`, get blaze powder into the hotbar and call `refuel`. Health never regenerates.
- The hotbar has 10 slots. Drops are collected only when the robot stands within about a block of them, and mined drops scatter: call `collect_items` after mining.
- The robot floats in water. `move_to` swims across it, straight up waterfalls and flooded shafts, and out onto a bank up to one block above the water. It only goes under when crouched, where it has 15 seconds of air.
- `snapshot` is drawn by the server from what the robot sees and works with nobody online; mobs, players and items are coloured boxes in it. Only `snapshot(source="client")` needs the robot owner's game client.
- Errors come back as `<code>: <message>`. Read the message and adapt; do not retry unchanged.
- `accepted: true` from `use_item` / `use_on_entity` is the game's own answer and does not prove anything changed. Check the inventory or the world.

The tool descriptions are the reference for operating the robot. Do not read the source to operate it.
Read the source only when something behaves oddly:

- a tool's own logic (aiming, waiting, reconnecting): `mcp_server/minebot_mcp/actions.py`, `session.py`
- what a command really does in the game: `executeCommand` in `src/main/java/com/oori/minebot/MineBotEntity.java`
- chat routing: `MineBotChat.java`; area scans: `MineBotScanner.java`
- what a snapshot draws: `MineBotCameraRenderer.java` (scene and tracing), `MineBotCameraModels.java` (block models), `MineBotCameraAssets.java` (textures)

## Working on the code

This directory is a git repository (GitHub: `oorischubert/minecraft_robot_mod`, private).

```bash
# build the mod (bundled JDK)
GRADLE_USER_HOME=.gradle-home JAVA_HOME=./jdk-21.0.10+7/Contents/Home ./gradlew build

# game client with the mod loaded (opens a window; ask the user before launching it)
GRADLE_USER_HOME=.gradle-home JAVA_HOME=./jdk-21.0.10+7/Contents/Home ./gradlew runClient

# local dedicated test server in run/ (localhost only; RCON on 25598, password in run/server.properties)
GRADLE_USER_HOME=.gradle-home JAVA_HOME=./jdk-21.0.10+7/Contents/Home ./gradlew runServer --args="nogui"

# SDK + MCP tests (use an in-process fake of the bridge, no game needed)
uv run --project mcp_server pytest mcp_server/tests -q
```

Rules:

- A change to the SDK or the websocket protocol updates `README.md`, `PYTHON_SDK.md`, `PYTHON_EXCEPTIONS.md`, the SDK docstrings and the examples in the same change, and lists every breaking change.
- The MCP server must never write to stdout (it is the MCP transport); log to stderr.
- A new mod command needs its SDK method, its MCP tool, and an entry in the fake bridge (`mcp_server/tests/fake_minebot.py`).
- Test against a running world before saying something works, and say what was not tested.
- Robot senses are limited to line of sight inside the mod (`MineBotScanner`). Do not add a command, field or option that reports what the robot cannot see.
- On a server with no players, entities only tick in force-loaded chunks. A connected robot force-loads the 3x3 chunks around itself and releases them on disconnect.
