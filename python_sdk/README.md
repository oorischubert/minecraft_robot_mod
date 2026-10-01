# MineBot Python SDK

The MineBot Python SDK requires Python `3.11+`.

Install locally from the repository root:

```bash
python3 -m pip install -e ./python_sdk
```

## Quick example

```python
from minebot import MineBot

robots = MineBot.list_robots()
if not robots:
    raise RuntimeError("This world has no MineBots")

robot = MineBot(code=robots[0]["code"], url=robots[0]["endpoint"])
robot.connect()

print(robot.locate())
target = robot.camera.inspect()
print(target["block"], target["distance"])

robot.hotbar(0)
robot.turn_to(90.0, 0.0)
robot.move(1.0, 0.0, duration=0.5)
print(robot.move_by(3.0, 0.0))
print(robot.attack())
print(robot.craft("minecraft:oak_planks"))

robot.close()
```

## Bundled examples

When you run examples directly from this checkout, start in the `python_sdk` directory.

The examples support:

- explicit CLI arguments
- `MINEBOT_URL` and `MINEBOT_CODE`
- auto-discovery of the first local robot when supported

```bash
python3 examples/list_robots.py
python3 examples/basic_control.py
python3 examples/basic_control.py ws://127.0.0.1:8765/minebot AB12CD34
python3 examples/camera_snapshot.py ws://127.0.0.1:8765/minebot AB12CD34 minebot_snapshot.png
python3 examples/camera_stream.py ws://127.0.0.1:8765/minebot AB12CD34 10 minebot_stream_frames
python3 examples/perception_dump.py
python3 examples/chat_listener.py ws://127.0.0.1:8765/minebot AB12CD34
```

## Notes

- Python `3.11+` is required. Python `3.10` will fail because the SDK exception layer uses `enum.StrEnum`.
- All public SDK methods include short `help()` docstrings, for example `help(MineBot.move_to)` and `help(robot.hotbar(0).drop)`.
- `move(x, z, duration=...)` is the low-level timed motion helper. The magnitude of the `(x, z)` vector already defines the move intensity.
- `move_by(x, z)` uses a dedicated server-side relative movement controller based on the robot's current block center and nearest cardinal facing.
- `move_to(x=None, z=None, speed=...)` uses Minecraft F3-style horizontal coordinates. If only one axis is provided, the other stays at the robot's current coordinate. The keyword-only `y=...` sets the target height (feet level) when several floors are possible.
- `robot.stop()` stops all movement and block breaking immediately.
- `robot.look_at(x, y, z)` or `robot.look_at(entity_id=...)` aims the crosshair at a world point or an entity.
- `turn_by(yaw=..., pitch=...)` is the relative look helper. `turn(...)` remains as a compatibility alias.
- `robot.crouch()` enters crouch mode until `robot.uncrouch()` or `robot.jump()` clears it.
- In water the robot floats on its own and swims. `move_to` also swims up waterfalls and flooded shafts, and any movement climbs out onto a bank up to one block above the water when there are 3 clear blocks above the water. `robot.crouch()` dives, `robot.jump()` surfaces; `status()` reports `in_water` and `air`.
- With its head under water the robot has 15 seconds of air. When it has just enough left to swim back to where it last breathed, it drops its order and does so; meanwhile movement calls raise `MineBotSeekingAirError` and `status()` reports `seeking_air`.
- While crouched, direct movement will not step off unsupported ledges.
- Driven movement (`move`, `move_by`, `move_to`) stops rather than step into lava or fire or off a drop of more than 3 blocks; `move` and `move_by` also stop before deep water. The reason starts with `Stopped:`.
- `robot.enter_vehicle()` and `robot.exit_vehicle()` handle boats, minecarts, and similar rideable vehicles.
- `robot.camera.inspect()` returns `{"block": ..., "distance": ...}` and can report fluids like `minecraft:water` and `minecraft:lava` (source blocks) or `minecraft:flowing_water` and `minecraft:flowing_lava`. If nothing is hit in range, the result is `{"block": "minecraft:air", "distance": 51.0}`. When something is hit it also reports the block position, `face`, `in_reach`, and any entity in the crosshair.
- `robot.hotbar(slot).inspect()` and `robot.inspect_slot()` return `{"block": ..., "count": ...}`.
- `robot.print(...)` sends a MineBot-labelled chat message to players on the server. `robot.say(..., to="Steve")` sends it to one player only.
- Players address robots in chat with `@<robot code>`, `@<robot name>`, `@bot` (nearest robot) or `@all`. `robot.read_chat()` and `robot.wait_for_chat()` return those messages.
- `robot.inventory()`, `robot.scan_blocks(...)`, `robot.scan_entities(...)` and `robot.environment()` report the hotbar, the blocks and entities the robot can see, and the surroundings. They need no energy.
- Scans only report what is in the robot's line of sight. Nothing behind walls or underground is reported; players are noticed through walls unless they sneak.
- `robot.center()` snaps the robot to the exact center of its current block, sets pitch to straight ahead, and snaps yaw to the nearest cardinal direction.
- When connecting from another computer on the same LAN, use the full `Connection Socket` shown in the MineBot GUI rather than `127.0.0.1`.
- Fuel is movement-based: one blaze powder currently gives `200` blocks of robot travel.
- The robot automatically picks up nearby dropped items when it has hotbar space.
- `robot.mine()` mines the crosshair block and tells you why it failed. `robot.attack_entity()`, `robot.use_item()` and `robot.use_on_entity()` attack or right-click entities and items.
- `robot.move_item(from_slot, to_slot)` moves or swaps hotbar stacks. `robot.refuel()` moves blaze powder from the hotbar into the fuel slot, even when the robot is out of energy.
- `robot.command(action, **fields)` sends any raw command action and returns its result.
- `robot.hotbar(slot).drop(count)` drops items from a robot hotbar slot.
- `robot.craft(item, count=...)` crafts 2x2 recipes anywhere; 3x3 recipes require the robot to be looking at a crafting table. If `count` is provided, it must be a whole-number multiple of the recipe output.
- `robot.furnace.*` works on furnaces, blast furnaces, and smokers.
- `robot.chest.*` works on chests, trapped chests, barrels, shulker boxes, hoppers, droppers, dispensers, chest and hopper minecarts, and chest boats.
- Crafting and container calls raise typed interaction exceptions such as `MineBotWrongTargetError`, `MineBotMissingIngredientsError`, and `MineBotTargetFullError`.
- Structured exception classes and stable codes live in `minebot.exceptions`. See [`../PYTHON_EXCEPTIONS.md`](../PYTHON_EXCEPTIONS.md).
- `MineBotProgramRunningError` means another client is already running a program on that robot.
- `MineBotOutOfEnergyError` means the robot has no blaze powder energy left. Before SDK `0.2.0` this was `MineBotInvalidRequestError`.
- A lost or timed-out connection raises `MineBotConnectionError` and disconnects the client; `robot.is_connected()` then returns `False`. Use `MineBot(..., timeout=20)` for camera snapshots.
- The server drops clients that stay silent for more than about 1.5 to 2 minutes. While a script waits, poll `robot.status()` or `robot.wait_for_chat()`.
- `robot.camera.snapshot()` returns a picture the server draws from what the robot sees; it works with no player online. `snapshot(source="client")` asks the robot owner's game client for a real frame instead and raises a `MineBotCameraOwner*Error` when that owner is missing, offline, or cannot render.
- `robot.evil()` intentionally raises `MineBotBrokeFreeError`, closes the session, and turns the MineBot hostile.
- When the robot dies, the next call raises `MineBotDiedError` and disconnects the client; `exc.death` holds the death message, cause, place and any chat it never read.
- `MineBot.list_robots()` also lists robots whose chunks are not loaded (`loaded: False`, with where they were last seen), and `connect()` loads such a robot first. `list_robots(include_dead=True)` adds the dead.

See [`../PYTHON_SDK.md`](../PYTHON_SDK.md) for the full API reference, including the list of breaking changes.

To let Claude control a robot instead of writing a script, use the MCP server in [`../mcp_server`](../mcp_server), which is built on this SDK. See [`../mcp_server/README.md`](../mcp_server/README.md).
