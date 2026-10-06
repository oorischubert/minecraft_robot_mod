# MineBot

Fabric mod (Minecraft 1.21.11) that adds robots, plus two ways to control them from outside the game.

## How the pieces fit

| Piece | Where | Role |
| --- | --- | --- |
| Mod | `src/` | Robot entity, chat inbox, and a websocket bridge (`ws://<host>:8765/minebot`, JSON request/response). One program per robot. |
| Python SDK | `python_sdk/` | Synchronous client for the bridge. One method per command. |
| MCP server | `mcp_server/`, started by `.mcp.json` | Wraps the SDK as the `minebot` tools. One Claude Code chat drives one robot. `run_program` runs a short Python-subset program of robot actions inside the server (`minebot_mcp/program.py`). |

Players talk to a robot in game chat: `@<code> ...`, `@<robot name> ...`, `@bot ...` (nearest robot) or `@all ...`.
The mod queues those lines per robot; you read them with `wait_for_chat`.

## Driving a robot

Play sessions are started with `./play.sh`, which gives Claude the `minebot` tools and nothing else.

You only act while you are looping on `wait_for_chat`. When asked to take control or start listening:

1. Call `status`. It connects to the only free robot; when several are free it fails with `choose_robot` and lists them, so pick one with `connect(code=...)`. After `disconnect` it takes the same robot back.
2. Loop on `wait_for_chat`. An empty result is normal; call it again.
3. For each order: acknowledge with `say(message, to=<sender>)`, do the work, report the result, return to step 2.

Fair play, which is not optional:

- The robot only knows what it can see. `scan_blocks` and `scan_entities` report what is in its line of sight; nothing behind walls or underground. An empty scan means "not in view from here".
- To find something that is not in view, explore as a player would: walk, look around, dig, open chests. Say so when you have not found it yet. Never state a location that no tool result showed you.
- While driving a robot, act only through the `minebot` tools. Do not read world or server files, run server commands or RCON, or call the SDK or websocket directly to learn about the world or to change it.

Things that are easy to get wrong:

- Coordinates are Minecraft F3 values. A robot's `y` is its feet. Yaw 0 is south (+Z), 90 west, 180 north, -90 east.
- Mining, placing and using act on the crosshair within 4 blocks. Prefer `mine_block` and `place_block`, which aim and verify. Otherwise `look_at`, then check the returned crosshair before acting.
- Nearly every action needs blaze powder energy. On `out_of_energy`, get blaze powder into the hotbar and call `refuel`. Health never regenerates on its own: `eat` turns iron or copper ingots from the hotbar into health, 1 heart each.
- A robot that dies is gone. Tools then fail with `died: ...` (cause, place, chat it never read) and the chat has no robot until `connect`.
- Mobs fight back: a blaze that is hit, and the blazes near it, shoot fireballs and set the robot on fire. `attack_entity(until_dead=True)` fights one target for up to `max_seconds` while it can see it, walks after it (`follow`, default on; straight at the ground under a hovering mob) and holds a hotbar shield up between swings (`guard`, default on); it stops at `min_health`. `eat` does not end the fight. A `NOTE:` reports every hurt (damage type, attacker if seen); `status` lists `recent_hurt`.
- Blazes (vanilla AI): one that sees its target hovers 4-8 blocks off and fires bursts of 3 fireballs about every 8.6 s; it only flies at a target already within 2 blocks, or for the first 5 ticks after losing sight of it. Shelters, lures and ambushes do not draw a blaze in; the robot must walk up to it. A fireball does 1.25 to a robot plus burning, a blaze's melee hit 6. A blaze needs 3 diamond-sword hits: give the fight 60-120 s.
- A robot standing in fire or lava steps out by itself when no order moves it (`status` shows `escaping_fire`), and movement orders may lead it out; it never steps into fire or lava it is not already in. `wait(seconds, until_entity=..., within=...)` holds a position until a mob comes into reach, a hurt, or a health threshold, and is the way to wait (not `wait_for_chat`).
- Many similar steps, or a check after every step (a staircase with a lava scan per block, mining a vein, holding a spot until a mob is in reach): write a `run_program` program instead of one tool call per step. It runs at about 0.3 s per action with no model turn between steps; keep each program to one goal, scan for hazards every step, use `stop_health_below`, and `return` a summary. A failing robot call ends it with the line unless caught with `except RobotError as e`.
- Robots are metal: fire, burning, lava, magma and fireball hits do a quarter of the usual damage. Lava still kills a robot that stays in it.
- The hotbar has 10 slots. Drops are collected only when the robot stands within about a block of them, and mined drops scatter: call `collect_items` after mining.
- Movement stops at lava, fire and drops of more than 3 blocks with `Stopped: ...`; `move` and `move_by` also stop before deep water, so use `move_to` to swim. To go lower, dig down or build steps.
- `move_to` goes round small blocks, other robots, mobs, players, boats and minecarts. When it still cannot get past it fails with `Stuck at ..., blocked by <what> at ...`: go another way, clear the block, or ask whoever is in the way to move.
- To go up, `pillar_up` jumps and places blocks under the robot; `place_block` refuses the robot's own cell. The 1x1 column it leaves cannot be walked up later.
- To cross a gap, `bridge(direction, count)` builds a walkway out from the edge of the block the robot stands on, level with it. The sides of that block cannot be seen from on top of it; `place_block` can use one only after `crouch` and a `move` that leans the robot out over that edge (up to 0.25).
- The robot floats in water. `move_to` swims across it, straight up waterfalls and flooded shafts, and out onto a bank up to one block above the water when there are 3 clear blocks above the water. Its head goes under when crouched or where the water reaches the ceiling; it then has 15 seconds of air. When it has just enough left to get back, it drops its order and swims back to where it last breathed, and movement tools fail with `seeking_air` until its head is out.
- `snapshot` is drawn by the server from what the robot sees and works with nobody online. Players, robots, common mobs and dropped items look as in the game but stand still; other entities are coloured boxes. Sign text is readable within a few blocks. Only `snapshot(source="client")` needs the robot owner's game client.
- Errors come back as `<code>: <message>`. Read the message and adapt; do not retry unchanged.
- `accepted: true` from `use_item` / `use_on_entity` is the game's own answer and does not prove anything changed. Check the inventory or the world.

The tool descriptions are the reference for operating the robot. Do not read the source to operate it.
Read the source only when something behaves oddly:

- a tool's own logic (aiming, waiting, reconnecting): `mcp_server/minebot_mcp/actions.py`, `session.py`; the program language and its robot functions: `program.py`, `program_api.py`
- what a command really does in the game: `executeCommand` in `src/main/java/com/oori/minebot/MineBotEntity.java`
- chat routing: `MineBotChat.java`; area scans: `MineBotScanner.java`
- what a snapshot draws: `MineBotCameraRenderer.java` (scene and tracing), `MineBotCameraModels.java` (block and item models), `MineBotCameraEntities.java` (mobs, players, items, signs), `MineBotCameraFont.java` (sign text), `MineBotCameraAssets.java` (textures)

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
- A new mod command needs its SDK method, its MCP tool, an entry in the fake bridge (`mcp_server/tests/fake_minebot.py`), and a function in the program API (`program_api.py`) with the tool's name and defaults.
- Test against a running world before saying something works, and say what was not tested.
- Robot senses are limited to line of sight inside the mod (`MineBotScanner`). Do not add a command, field or option that reports what the robot cannot see.
- Snapshot mob shapes come from `src/main/resources/assets/minebot/camera/entity_models.json`, exported from the game's own model code by `tools/dump_entity_models.sh`. Rerun it after a Minecraft update.
- On a server with no players, entities only tick in chunks something keeps loaded. A robot keeps its 3x3 chunks loaded with a `minebot:robot` chunk ticket (never `/forceload`) while busy, for `chunks.hold_seconds` after (default 300), and while in danger; it renews the ticket every tick and the ticket lapses 2 s after the last renewal (`MineBotChunkLoader`).
- The robot list saved with the world (`MineBotRegistry`, `data/minebot_robots.dat`) is what `robots`, `locate` and `connect` use for robots that are not loaded, and where deaths are kept.
