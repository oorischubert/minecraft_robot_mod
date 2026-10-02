# MineBot MCP server

This folder contains an MCP server that lets Claude control one MineBot robot. MCP (Model Context Protocol) is the way Claude Code loads extra tools. With this server, Claude gets tools to move the robot, mine, place blocks, use items and containers, look around, and read and answer in-game chat. Players then give the robot orders in the normal Minecraft chat, and Claude carries them out.

The server is a thin layer over the Python SDK in [`../python_sdk`](../python_sdk). One Claude Code chat drives one robot.

## Requirements

- [Claude Code](https://claude.com/claude-code)
- [`uv`](https://docs.astral.sh/uv/) on your `PATH`
- Python `3.11+` (uv can provide it)
- Minecraft with the MineBot mod, with a world open that has at least one robot

The first start creates `mcp_server/.venv` and downloads the dependencies, so it needs network access once.

## Starting it

You do not start the server yourself. The file [`../.mcp.json`](../.mcp.json) in the repository root registers it for Claude Code under the name `minebot`, and Claude Code starts it with:

```bash
uv run --quiet --project mcp_server minebot-mcp
```

1. Start the game (see [Running the game](../README.md#running-the-game)) and open the Minecraft world. In singleplayer you must be inside the world, not on the title screen, because the websocket bridge runs with the world. Make sure the world has a robot with blaze powder in its fuel slot. It does not have to be near a player: connecting loads it.
2. Open a terminal in the repository root and run `./play.sh` (robot mode, see below), or plain `claude`. With plain `claude`, Claude Code asks the first time to approve the project's `minebot` server. The `/mcp` command in Claude Code shows whether it is connected.
3. Tell Claude what you want, for example: `Connect to the robot and listen for orders in chat.` To pick a specific robot, name its code: `Connect to robot AB12CD34 and listen for orders.`

### Robot mode

[`../play.sh`](../play.sh) starts Claude Code with:

```bash
claude --strict-mcp-config --mcp-config .mcp.json --tools "" --allowedTools "mcp__minebot"
```

In this mode Claude has the `minebot` tools and nothing else: no shell, no file access, no web, and none of your other MCP servers. It cannot look at world files, run server commands, or use the Python SDK behind the robot's back, so everything it learns and does goes through the robot. The `minebot` tools are pre-approved, so orders from chat do not wait for you in the terminal. Arguments are passed on, for example `./play.sh --model sonnet`.

With plain `claude`, Claude keeps its developer tools, and Claude Code asks for permission the first time Claude uses each tool. Use that when you are working on the code rather than playing.

When Claude Code exits, the server closes its session and the robot becomes free for other programs.

## Settings

The server reads these environment variables:

| Variable | Default | Meaning |
| --- | --- | --- |
| `MINEBOT_URL` | `ws://127.0.0.1:8765/minebot` | Websocket address of the MineBot bridge (the `Connection Socket` in the robot GUI). |
| `MINEBOT_CODE` | not set | Robot code to use. When not set, the server takes the only robot that is free and not hostile, and asks for a code when there are several. |
| `MINEBOT_KEEPALIVE_SECONDS` | `20` | After this many idle seconds the server sends a cheap status request to keep the session open. `0` turns this off. |
| `MINEBOT_POLL_SECONDS` | `0.25` | How often to check the robot while it moves or mines. |
| `MINEBOT_CHAT_POLL_SECONDS` | `0.5` | How often `wait_for_chat` checks the inbox. |
| `MINEBOT_SOCKET_TIMEOUT` | `20` | Seconds to wait for each reply from the bridge. Keep it above the bridge's own `15` second limit. |
| `MINEBOT_LOG_LEVEL` | `INFO` | Log level. Logs go to standard error; standard output carries only MCP messages. |

`.mcp.json` passes `MINEBOT_URL` and `MINEBOT_CODE` through from the shell you start Claude Code in, so you can run for example:

```bash
MINEBOT_URL=ws://192.168.1.10:8766/minebot MINEBOT_CODE=AB12CD34 claude
```

The other variables are not listed in `.mcp.json`; the server reads them from the environment it inherits.

## Tools

Every robot tool connects automatically on first use: to `MINEBOT_CODE` if it is set, otherwise to the only robot that is free and not hostile, loaded or not. When several are free it does not guess: it fails with `choose_robot`, lists them, and Claude picks one with `connect(code=...)`. Tools that take item or block ids accept the short form (`oak_log`) as well as the full id (`minecraft:oak_log`).

Session:

- `list_robots` lists every living robot of the world with its code, name, health, owner, position, whether it is loaded, whether another program controls it, and whether it has turned evil; `this_session` marks the one this chat holds. For a robot that is not loaded (`loaded: false`), health and position are from when it was last seen. Under `dead` it lists the 10 robots that died last and how.
- `connect(code=None, url=None)` connects to a robot, releasing any previous one. Without a code it takes back the robot this chat had (also after `disconnect`), else connects as described above. A robot whose chunks are not loaded is loaded where it was last seen first, however far away it is.
- `disconnect` releases the robot; the next robot tool takes that same robot back if it is still free, and never another one. Use `connect(code=...)` to switch.
- `status` reports position, facing, health, fuel and range, selected item, crosshair block, owner, ongoing work, what hurt the robot in the last 5 minutes (`recent_hurt`), and warnings such as low energy.
- `turn_evil` turns the connected robot hostile for good, like `evil()` in the Python SDK: it hunts the nearest player until it is killed and never takes orders or chat again. Claude is told to use it only when a player orders it, and in robot mode it runs without asking you, like every `minebot` tool. It only acts on a robot this chat is already connected to. Afterwards the robot tools report `not_connected` until Claude calls `connect` for another robot; they do not pick one on their own.

Movement:

- `move_to(x, z, y=None, speed=1.0, timeout=60)` pathfinds to world coordinates and waits; `y` picks the floor height. On timeout (at most 600 seconds) the robot is stopped.
- `move_by(forward, right=0, speed=1.0, timeout=30)` walks a number of blocks relative to the robot's nearest cardinal facing.
- `move(forward, right=0, duration=1.0)` holds raw movement input for up to 10 seconds, without pathfinding.
- `turn_to(yaw=None, pitch=None)` and `turn_by(yaw=0, pitch=0)` turn the robot and report what the crosshair hits.
- `look_at(x, y, z)` aims at a world point; `look_at_entity(entity_id)` aims at an entity.
- `jump`, `crouch(enabled)`, `center`, and `stop`.
- `enter_vehicle` and `exit_vehicle` (experimental).
- `go_to_player(name, distance=2.0, timeout=60)` walks to an online player within 64 blocks and faces them.

Mining, placing, and using:

- `mine` mines the crosshair block and waits.
- `mine_block(x, y, z)` aims at a block, checks that it is the right one and within reach, then mines it.
- `collect_items(radius=6, item=None)` walks over dropped items nearby so they are picked up. Drops scatter up to about 2 blocks and the robot only picks up what is within about a block of it.
- `place` right-clicks the crosshair block with the selected item.
- `place_block(x, y, z, item=None)` finds a supporting face next to the target, aims at it, selects `item` if given, and places.
- `use_item(hold_seconds=None)` right-clicks with the selected item (buckets, throwables, boats, spawn eggs). Hold-to-use items are held, then released, and the tool waits: a bow draws fully and fires, a crossbow loads (call again to fire), a trident is thrown, a shield is raised.
- `use_on_entity(entity_id=None)` and `attack_entity(entity_id=None)` right-click or attack an entity, aiming at it first when `entity_id` is given.
- `attack_entity(entity_id, until_dead=True, follow=False, min_health=8, max_seconds=30)` fights a mob to the end in one call: the robot keeps aiming and swings each time its weapon has recharged until the mob dies, gets away, the robot's health falls to `min_health`, or time runs out. `follow=True` also walks after it, which a mob that is knocked back or drifts, like a blaze, needs. The result says how it ended and what hurt the robot meanwhile.

Inventory, crafting, and containers:

- `inventory`, `select_slot(slot)`, `equip(item)`, `drop(slot=None, count=None)`, and `move_item(from_slot, to_slot, count=None)`.
- `refuel(count=None)` moves blaze powder from the hotbar into the fuel slot.
- `eat(item=None, count=None)` eats iron or copper ingots from the hotbar, one heart each, up to full health. It is the only way a robot gets health back.
- `craft(item, count=None)`: 2x2 recipes work anywhere, 3x3 recipes need the robot to look at a crafting table.
- `chest_inspect`, `chest_put(item, count=1)`, and `chest_take(item, count=1)` for chests, barrels, shulker boxes, hoppers, droppers, and dispensers.
- `furnace_inspect`, `furnace_put(input_item=None, input_count=1, fuel_item=None, fuel_count=1)`, and `furnace_take(item=None, count=None, fuel_item=None, fuel_count=1)`.

Camera:

- `inspect` reports what the crosshair is on, with block position, face, distance, and whether it is in reach.
- `snapshot(source="render")` returns a 640x360 picture from the robot's eyes. The server draws it from what the robot can see, with Minecraft's textures and lighting, so it works with no player online; players, robots, common mobs, and dropped items look as in the game but stand still; other entities are coloured boxes. Signs show their text, readable within a few blocks. `source="client"` captures the robot owner's real game screen instead (see [Troubleshooting](#troubleshooting)).

Perception:

- `scan_blocks(radius=8, blocks=None, limit=32, center_x=None, center_y=None, center_z=None)` lists the blocks the robot can see around itself or around a given block. `radius` goes up to 16, or 32 with a `blocks` filter. Lava and water sources are `minecraft:lava` / `minecraft:water`, flowing fluid `minecraft:flowing_lava` / `minecraft:flowing_water`, so `blocks=["lava"]` finds lava a bucket can pick up.
- `scan_entities(radius=16, types=None, players_only=False, limit=16)` lists the entities the robot can see, with ids for the entity tools.
- `nearby_players(radius=64)` lists players near the robot.

The scans only report what is in the robot's line of sight; see [What a robot can see](../README.md#what-a-robot-can-see). Claude is told to explore for anything that is not in view.
- `environment` reports dimension, biome, time, weather, light, and the blocks around the robot.

Chat:

- `wait_for_chat(timeout=60)` waits up to `timeout` seconds (at most 300) for messages addressed to the robot and returns them.
- `read_chat(peek=False)` returns unread messages without waiting.
- `say(message, to=None)` writes a chat line as the robot, to everyone or to one player.

When a tool fails, Claude gets a line of the form `<code>: <message>`, often followed by a hint in parentheses. Besides the MineBot error codes listed in the [main README](../README.md#standard-interaction-errors), the tools use these codes:

- `connection_failed`, `connection_lost`, `no_robots`, `no_free_robot`, `not_found`, `program_running`, `broke_free`: the robot session could not be started or kept (see [Troubleshooting](#troubleshooting))
- `out_of_reach`, `obstructed`, `target_empty`, `target_occupied`, `no_support`: `mine_block` or `place_block` could not aim at the requested block
- `mining_failed`, `place_failed`: the action started but did not work
- `timeout`: a move or mining job took too long; the robot was stopped
- `cancelled`: the tool call was interrupted; the robot was told to stop

## How the chat loop works

From the player's side:

- Write a chat line that starts with the robot's address: `@AB12CD34 ...` (its code), `@<name> ...` (its name tag, without spaces), `@bot ...` (the nearest robot in your dimension), or `@all ...` (every robot). The full addressing rules are in the [main README](../README.md#talking-to-robots-in-chat).
- The robot answers through Claude with chat lines labelled `<MineBot:AB12CD34>`, usually sent privately to you.
- If no program is connected to the robot, you get `[MineBot:AB12CD34] No program is connected. Your message was queued.` The message waits in the robot's inbox for up to 10 minutes.

From Claude's side:

1. The server tells Claude to start with `status` and then call `wait_for_chat` in a loop. `wait_for_chat` returns as soon as a message arrives, or after its timeout with a "no new messages" note, after which Claude calls it again.
2. Each message shows who wrote it (`from`), the text without the address, how the robot was addressed (`to`), how old it is, and where the sender stood (`sender_pos`, `distance`).
3. Claude acknowledges the order with `say(..., to=<sender>)`, does the work with the other tools, reports the result, and goes back to `wait_for_chat`.

Claude only acts on chat while this loop is running. If Claude stops (for example because it ended its turn or you interrupted it), messages collect in the inbox until you tell Claude to listen again. The player gets no notice of this, because a program is still connected.

Whenever the robot has lost health since Claude last looked, the next tool result starts with a `NOTE:` line saying how much, the damage type, and who did it when the robot saw them, for example `NOTE: The robot was hurt: lost 5.0 health (15.0 left) 2.1 s ago: fireball (small_fireball) from Blaze (blaze, entity 812).` `wait_for_chat` checks too, so a robot that is hit while it waits for orders is reported.

While Claude is idle, the server keeps the websocket session alive on its own. If the session drops anyway, the server reconnects to the same robot (at the latest on the next tool call) and adds a `NOTE:` line to the next tool result. A dropped session stops the robot, so Claude has to re-issue any movement or mining that was in progress.

## Running the tests

From the repository root:

```bash
uv run --project mcp_server pytest mcp_server/tests -q
```

The tests run against a fake MineBot bridge inside the test process, so Minecraft does not need to be running.

## Troubleshooting

**`connection_failed: ... Could not reach the MineBot websocket bridge`**

Nothing is listening at `MINEBOT_URL`. Open the world with the MineBot mod loaded; in singleplayer, be inside the world rather than on the title screen. Then ask Claude to try again.

**The bridge is on a different port**

If port `8765` was taken when the world started, the bridge picks another free port. Right-click the robot: the GUI shows the real `Connection Socket`. Tell Claude to connect with that URL (`connect` takes a `url`), or restart Claude Code with `MINEBOT_URL` set to it.

**`no_robots`**

The bridge is running but lists no living robot. Summon one. A robot from before the mod version that saves the robot list is only listed once it has been loaded: go near it once.

**`timeout` when connecting**

The robot's chunks were not loaded, and its area did not load within 4 seconds. Ask Claude to connect again.

**`program_running` or `no_free_robot`**

Another program already controls the robot: a Python script, or another Claude Code chat. Stop that program, or ask Claude to connect to a different robot. When `MINEBOT_CODE` is set, every chat tries that same robot.

**`broke_free`**

The robot turned hostile (through `turn_evil`, or `evil()` from a Python program) and can no longer be controlled. Use another robot.

**`died`**

The robot died. Every player saw its death message in chat, and Claude gets the same message, where the robot died, and any chat it received but never read. The robot tools then report `not_connected` until Claude calls `connect` for another robot; they do not pick one on their own.

**`out_of_energy`**

The robot has no blaze powder energy. Put blaze powder in its fuel slot through the robot GUI, or drop some next to the robot (it picks items up) and let Claude call `refuel`.

**`camera_assets_unavailable` from `snapshot`**

The server draws snapshots with Minecraft's block textures, which a dedicated server reads from the official Minecraft client jar. It could not load them: check `config/minebot.properties` on the server and its log (see [Camera textures on a server](../README.md#camera-textures-on-a-server)). Claude can still use `inspect`, `scan_blocks`, and `scan_entities`.

**`camera_owner_offline`, `camera_owner_unavailable`, or `camera_owner_required` from `snapshot`**

These only come from `snapshot(source="client")`, which is rendered by the Minecraft client of the player who summoned the robot. That player must be online, with the game not paused or minimized, and near enough for the robot to be loaded on their client. Robots without a recorded owner cannot use it. Plain `snapshot()` works without an owner.

**The server does not appear in Claude Code**

Start Claude Code from the repository root, where `.mcp.json` is, and check `/mcp`. Make sure `uv` is on the `PATH` of that shell. The first start needs network access to install the dependencies.
