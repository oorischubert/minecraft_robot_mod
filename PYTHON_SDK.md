# Python SDK

The MineBot Python SDK wraps the websocket protocol with a synchronous client API. It is intended for simple scripts, automation, and prototypes that control one robot at a time.

## Install

The MineBot Python SDK requires Python `3.11+`.

From the repository root:

```bash
python3 -m pip install -e ./python_sdk
```

The SDK depends on `websocket-client`.

All public SDK methods include short Python docstrings. In a REPL, you can use `help(MineBot.move_to)` or `help(robot.hotbar(0).drop)` for inline guidance, then use this document for the full behavior reference.

Structured SDK exceptions live in `minebot.exceptions`. See [`PYTHON_EXCEPTIONS.md`](./PYTHON_EXCEPTIONS.md) for the full code and class reference.

Upgrading from an earlier SDK version? Read [Breaking changes](#breaking-changes) first.

## Quick start

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

## Constructor

```python
MineBot(code: str | None = None, url: str = "ws://127.0.0.1:8765/minebot", timeout: float = 5.0)
```

Arguments:

- `code`
  - the 8-character MineBot code shown in the Minecraft GUI
- `url`
  - websocket endpoint shown in the GUI
- `timeout`
  - socket timeout in seconds: how long each call waits for the server's reply
  - if no reply arrives in time, the SDK closes the session and raises `MineBotConnectionError`
  - camera snapshots can take longer than the default `5` seconds (the first one after the server starts, or a `source="client"` frame from a slow client); use `timeout=20` for camera work

If you pass `http://` or `https://` instead of `ws://` or `wss://`, the SDK normalizes it automatically.

If you are connecting from another computer on the same local network, use the full `Connection Socket` shown in the MineBot GUI, not `127.0.0.1`.

Every connected client also exposes helper namespaces:

- `robot.camera`
- `robot.furnace`
- `robot.chest`

## Connection lifecycle

### `connect(code: str | None = None, url: str | None = None) -> dict`

Connects to a robot and returns the initial status payload.

A robot whose chunks are not loaded, however far from every player, is loaded where it was last seen first. That usually takes well under a second; if its area has not loaded after `4` seconds, `connect()` raises `MineBotTimeoutError` and you can try again. See [Robot chunk loading](./README.md#robot-chunk-loading) for when a robot's chunks stay loaded.

It raises `MineBotCommandError` with code `not_found` when no robot of this world has that code, or when the robot was not where it was last seen, and `MineBotDiedError` when the robot died (see [Robot death](#robot-death)).

### `close() -> None`

Closes the websocket cleanly.

`disconnect()` is an alias.

### `is_connected() -> bool`

Returns `True` while the client holds an open websocket session.

It becomes `False` after `close()`, and also when the SDK drops the session by itself because the connection was lost (see below). Call `connect()` again to start a new session.

### Lost connections

When the websocket fails, is closed by the server, or does not answer within `timeout`, the SDK:

- closes its side of the socket, so `is_connected()` returns `False`
- raises `MineBotConnectionError` from the call that was running

Every later call raises `MineBotConnectionError("The MineBot is not connected")` until you call `connect()` again. When a session drops, the mod releases the robot and stops it, so re-issue any movement or mining after reconnecting.

### Keeping a session alive

The MineBot bridge sends a websocket ping every `60` seconds and drops clients that do not answer. The SDK only answers pings during a call, so a script that sends nothing for more than about 1.5 to 2 minutes loses its session: the robot stops and becomes free for other programs.

If your script waits for something, poll a cheap call while it waits, for example `status()` every few seconds or `wait_for_chat(...)` in a loop, which polls the chat inbox on its own.

### Context manager

```python
with MineBot(code="AB12CD34", url="ws://127.0.0.1:8765/minebot") as robot:
    print(robot.status())
```

## Discovery and status

### `MineBot.list_robots(url: str = "ws://127.0.0.1:8765/minebot", timeout: float = 5.0, include_dead: bool = False) -> list[dict]`

Returns the MineBots of the world: first the loaded robots, then the robots whose chunks are not loaded. `connect()` works for both. With `include_dead=True`, the robots that died follow, the last to die first.

Each item includes:

- `display_name`: the name players see, `MineBot <name>` for a renamed robot, else `MineBot <code>`
- `code`
- `endpoint`
- `dimension`
- `connected`
- `evil`
- `owner_uuid`
- `owner_name`
- `owner_online`
- `health`, `max_health`
- `x`, `y`, `z`
- `loaded`: `False` for a robot whose chunks are not loaded; its `health` and position are then the ones saved when it was last seen
- `dead`: `True` only with `include_dead=True`, for a robot that died
- `entity_id`: only for loaded robots
- `death`: only for dead robots, the same dict as `MineBotDiedError.death` (see [Robot death](#robot-death))

For compatibility, listings also still include `access_code` as an alias of `code`.

Coordinates use Minecraft F3-style world values rounded to 3 decimal places.

### `locate() -> dict`

Returns the connected robot's absolute location and orientation:

- `dimension`
- `x`, `y`, `z`
- `yaw`, `pitch`

Use this after connecting when you want the current pose of a specific robot. For broad discovery, use `MineBot.list_robots()`.

### `status() -> dict`

Returns the full status payload for the connected robot.

Common fields include:

- `entity_id`, `display_name`, `code`, `dimension`, `owner_name`, `owner_online`
- `selected_slot`, `selected_item`, `crouched`
- `health`, `max_health`, `fuel_count`, `stored_range_blocks`, `in_vehicle`
- `in_water`, `air`, `max_air`
  - `air` is the robot's remaining breath in game ticks (`max_air` is `300`, which is 15 seconds); it only drops while the robot's head is under water, and the robot takes damage when it reaches `0`
- `seeking_air`
  - `True` while the robot is swimming back to where it last breathed (see [Running short of air](#running-short-of-air)); `air_target_x`, `air_target_y`, `air_target_z` are then that position
- `x`, `y`, `z`
- `yaw`, `pitch`
- `look_block`
- `moving_to_target`, `moving_by_target`
- `pillaring`, `pillar_placed`, `pillar_requested`
  - `pillaring` is `True` while `pillar_up()` runs; `pillar_placed` and `pillar_requested` count the blocks of the current or last pillar
- `bridging`, `bridge_placed`, `bridge_requested`
  - `bridging` is `True` while `bridge()` runs; `bridge_placed` and `bridge_requested` count the blocks of the current or last bridge
- `direct_move_active`, `direct_move_x`, `direct_move_z`
- `last_move_known`, `last_move_success`, `last_move_message`
- `breaking_block`
- `last_attack_known`, `last_attack_success`, `last_attack_message`
- `using_item`
  - `True` while `use_item()` holds a bow, crossbow, trident, shield or similar item before releasing it
- `fighting`
  - `True` while `attack_entity(until_dead=True)` fights
- `hurt_count`, `recent_hurt`
  - `hurt_count` counts every time the robot lost health and is kept with the robot; `recent_hurt` lists its last 8 hurts, oldest first, each with:
    - `id` (its number in `hurt_count`), `seconds_ago`, `amount` (health lost), `health` (health left)
    - `cause`: the damage type, for example `minecraft:mob_attack`, `minecraft:fireball`, `minecraft:on_fire`, `minecraft:player_attack`, `minecraft:fall`
    - `attacker_seen`, when something attacked it: whether the robot could see it at that moment; only then `attacker` (its name), `attacker_type` and `attacker_id`
    - `projectile`: the type of the entity that hit it, when that is not the attacker itself, for example `minecraft:small_fireball` or `minecraft:arrow`
  - `recent_hurt` starts empty each time the robot is loaded

Conditional fields include:

- `move_target_x`, `move_target_y`, `move_target_z`, `move_target_speed` while `move_to()` is active
- `move_by_target_x`, `move_by_target_y`, `move_by_target_z`, `move_by_target_speed` while `move_by()` is active
- `break_target_x`, `break_target_y`, `break_target_z`, `break_ticks_remaining`, `break_progress` while a block is being broken
- `use_item_id`, `use_hold_ticks`, `use_ticks_remaining` while `using_item` is `True`
- `last_use` once a held use has ended: the outcome `use_item()` returns for it (see [`use_item()`](#use_itemhold_secondsnone-waittrue-timeoutnone-poll_interval025---dict))
- `fight_target_id`, `fight_swings`, `fight_hits` while `fighting` is `True`
- `last_fight` once a fight has ended: the outcome `attack_entity(until_dead=True)` returns for it

Additional metadata and compatibility fields may also be present.

MineBots do not regenerate health on their own. If a robot takes damage, `health` stays reduced until it eats iron or copper ingots with `eat()`, one heart each. `recent_hurt` says what did it.

World coordinates in `status()` use 3 decimal places. `yaw` and `pitch` use 1 decimal place.

Fuel is movement-based:

- `movement_blocks_per_blaze_powder` is currently `200`
- while the robot is in water or lava only horizontal travel counts, so floating in place uses no fuel
- `stored_range_blocks` is the total remaining movement range from buffered fuel plus loaded blaze powder

Actions that need energy, such as movement, turning, mining, placing, crafting, and container transfers, raise `MineBotOutOfEnergyError` when the robot has no energy left. Perception, chat, inventory listing, `stop()`, `move_item()`, `refuel()`, and `eat()` work without energy. Put blaze powder in the robot hotbar and call `refuel()` to recover.

### `last_status() -> dict`

Returns the last cached payload returned by `connect()` or `status()`.

## Motion and orientation

### `move(x: float, z: float, duration: float | None = None) -> dict`

Sets low-level local movement input.

- `x = 1.0`
  - full forward
- `x = -1.0`
  - full backward
- `z = 1.0`
  - full right strafe
- `z = -1.0`
  - full left strafe

Values are clamped to `[-1.0, 1.0]`.

This `z` is local strafe input, not the Minecraft world `Z` coordinate.

- `duration`
  - if provided, the SDK holds that move for the given seconds and then automatically sends `move(0, 0)`
- `move(0, 0)` releases the input and stops the robot dead, with no slide
- crouched, the robot does not step off any ledge; like a sneaking player it leans out up to `0.25` blocks past the edge, still standing on the block, far enough to see and place against the side of that block
- in water, pushing into a bank at most one block above the water climbs out onto it, as a player does by holding jump; a higher wall stops the robot, and so does a ceiling lower than 3 blocks above the water
- the robot stops rather than step into lava or fire, off a drop of more than `3` blocks, or from dry land into deep water; the input is released and `status()` reports `last_move_success: False` with the reason in `last_move_message` (starting `Stopped:`)
- if the robot turns back for air during the move, the closing `move(0, 0)` raises `MineBotSeekingAirError` (see [Running short of air](#running-short-of-air))

The magnitude of the `(x, z)` vector already defines the effective move intensity, so there is no separate `speed` argument on `move(...)`.

### `move_by(x: float, z: float, speed: float = 1.0, timeout: float = 30.0, poll_interval: float = 0.25, tolerance: float = 0.75) -> bool`

Moves by a local block offset using the robot's current block center and nearest cardinal facing.

- `x`
  - forward/backward block distance relative to the robot
- `z`
  - right/left block distance relative to the robot
- implemented as a dedicated server-side relative move, with the SDK waiting for completion
- in water, like `move(...)`, it climbs out onto a bank at most one block above the water
- success is judged the same way as `move_to(...)`: by where the robot ends up, horizontally and in height
- like `move(...)`, it stops rather than step into lava or fire, off a drop of more than `3` blocks, or from dry land into deep water, and raises `MineBotCommandError` with a reason starting `Stopped:`; use `move_to(...)` to swim

### `move_absolute(...) -> bool`

Compatibility alias for `move_by(...)`.

### `move_to(x: float | None = None, z: float | None = None, speed: float = 1.0, timeout: float = 30.0, poll_interval: float = 0.25, tolerance: float = 0.75, *, y: float | None = None) -> bool`

Starts server-side pathfinding and waits until the robot arrives or gives up.

- coordinates follow the Minecraft F3 readout
- at least one of `x` or `z` must be provided
- if only `x` is given, MineBot keeps the current `z`
- if only `z` is given, MineBot keeps the current `x`
- `X` and `Z` are the horizontal plane
- Minecraft `Y` is height
  - without `y`, MineBot picks a walkable level near the robot's current height
  - a target in open water resolves to the water surface and the robot swims there
  - paths may cross water, swim straight up waterfalls and flooded shafts, and climb out onto a bank up to one block above the water when there are 3 clear blocks above the water; they never dive, and a bank two or more blocks above the water cannot be climbed from it
  - paths keep a block away from lava and fire and never cross magma; the robot stops rather than step into lava or fire or off a drop of more than `3` blocks, and the move fails with a reason starting `Stopped:`
  - paths keep out of water that reaches the ceiling, where the robot's head would be under water, unless there is no other way
  - paths need room for the robot's whole body, at each block and on the way between two: they go round cocoa pods, trapdoors, amethyst, pointed dripstone, open doors seen side-on and other part blocks, and stand the robot on top of carpets, snow layers and candles
  - paths go round other robots, mobs and players where there is room, never pass through a shulker, and keep clear of boats and minecarts, which would pick the robot up
  - a robot that reaches no new spot of its path for `1.5` seconds (`3` in water, longer at a lower `speed`) plans again; jumping at a step or being shoved back and forth by a mob does not count. After `3` such stalls in a row, `move_to` raises `MineBotMovementFailedError` with a reason such as `Stuck at (x, y, z), blocked by MineBot 1A2B3C4D at (x, y, z), and found no way round`
  - if the robot runs short of air on the way, it turns back and `move_to` raises `MineBotMovementFailedError` (see [Running short of air](#running-short-of-air))
  - `y` is keyword-only: the target height for the robot's feet, the F3 `Y` you would read standing at the target; MineBot looks for a walkable spot within `12` blocks of that height and raises `MineBotInvalidRequestError` if there is none
- `x`, `y`, and `z` are rounded to 3 decimal places before sending
- the exact absolute `x` / `z` values are preserved, so `move_to(12.5, -13.5)` targets the center of that block
- `speed` scales pathfinding movement up to the robot's normal maximum speed
- `timeout` controls how long the SDK waits
- `tolerance` is the maximum remaining horizontal distance allowed for success

Success is judged by where the robot ends up, not by what the server reports. The robot has arrived when it is within `tolerance` blocks of the target horizontally and its feet are within `0.75` blocks of the target height (`1.25` while it floats in water, where it bobs above the water block). The target height is the walkable height the server chose, the `target_y` of the `move_to` reply.

Returns `True` when the robot arrived, even if the server reported a problem on the way (for example the exact point was blocked and it stopped 0.4 blocks short).

Otherwise `move_to(...)` raises `MineBotCommandError` (code `movement_failed`) instead of returning `False`. The message gives the server's reason, where the robot is, and how far it is from the target horizontally and above or below it, for example `MineBot ended away from the target. Robot is at (4.5, 56.0, 4.5), 0.0 blocks from the target horizontally and 8.0 blocks below it`.

```python
robot.move_to(12.5, -13.5)          # walkable level near the current height
robot.move_to(12.5, -13.5, y=40)    # standing at height 40, for example inside a cave
```

### `stop() -> dict`

Immediately stops direct movement from `move(...)`, a running `move_by(...)` or `move_to(...)`, any block breaking, an item held by `use_item(...)` (its `last_use` then has `completed: False`), and a fight (its `last_fight` then has `ended: "interrupted"`).

- needs no energy
- a `move_to(...)` or `move_by(...)` that was interrupted is recorded as failed in `status()`, with `last_move_message` set to `The MineBot was stopped before reaching the destination`

Returns `{"stopped": True}`.

### `look_at(x: float | None = None, y: float | None = None, z: float | None = None, entity_id: int | None = None) -> dict`

Turns the robot so its crosshair passes through a world point or an entity.

- pass `x`, `y`, and `z` (world coordinates) to look at a point; to aim at the middle of block `(bx, by, bz)`, use `(bx + 0.5, by + 0.5, bz + 0.5)`
- or pass `entity_id` (from `scan_entities()` or `camera.inspect()`) to look at that entity; living entities are aimed at their eyes, other entities at the center of their hitbox
- raises `MineBotInvalidRequestError` if neither a full point nor `entity_id` is given
- raises `MineBotEntityNotFoundError` if no loaded entity has that id
- needs energy, like `turn_to(...)`, and cancels block breaking in progress

Returns the new orientation: `{"yaw": ..., "pitch": ...}`.

Use `robot.camera.inspect()` afterwards to check what the crosshair actually hits.

### `turn_by(yaw: float = 0.0, pitch: float = 0.0) -> dict`

Applies a delta turn.

- `yaw`
  - horizontal delta, clamped to `[-180, 180]`
- `pitch`
  - vertical delta, clamped to `[-90, 90]`
  - applied relative to the robot's current commanded pitch

### `turn(yaw: float = 0.0, pitch: float = 0.0) -> dict`

Legacy compatibility alias for `turn_by(...)`.

### `turn_to(yaw: float | None = None, pitch: float | None = None) -> dict`

Sets the robot to an absolute Minecraft F3-style look direction.

- at least one of `yaw` or `pitch` must be provided
- if only one axis is given, the other stays unchanged
- `yaw` is wrapped to `[-180.0, 180.0]`
- `pitch` is clamped to `[-90.0, 90.0]`
- `pitch = -90.0` means straight up
- `pitch = 90.0` means straight down
- both values are rounded to 1 decimal place

### `crouch() -> dict`

Puts the robot into crouch mode.

- the robot stays crouched until `uncrouch()` or `jump()` is used
- direct movement while crouched will not step over unsupported ledges (standing, it only refuses drops of more than `3` blocks); it leans out up to `0.25` blocks past the edge, as a sneaking player does
- in water, crouching makes the robot dive: it stops floating and sinks about 4 blocks a second, like a sneaking player
- a diving robot stands up by itself when it runs short of air (see [Running short of air](#running-short-of-air))

### Running short of air

With its head under water, a robot has 15 seconds of air (`status()["air"]`, in ticks), then it takes drowning damage. Its head goes under when it dives (`crouch()`), or where water fills the space up to a ceiling, such as a flooded passage. There, swimming straight up does not reach air.

When the air left is just enough to swim back to where the robot last had its head above water, with about 2 seconds to spare, it turns back by itself. It:

- drops what it was doing: a `move_to` or `move_by` raises `MineBotMovementFailedError` with the message `Ran short of air and turned back to (x, y, z), where it last breathed`, a raw `move` stops, and mining stops
- stands up if it was crouched, and swims back to that position
- refuses `move`, `move_by`, `move_to`, `crouch`, `center`, `jump`, `pillar_up`, `bridge`, `stop`, and `enter_vehicle` with `MineBotSeekingAirError` (code `seeking_air`) until its head is above water; other commands still work
- reports `seeking_air: True` and the position in `status()` while it does so

It allows about 0.6 seconds of air per block back, measured in a straight line. A long, winding way back can still use up its air. A robot out of blaze powder cannot swim back.

### `uncrouch() -> dict`

Leaves crouch mode explicitly.

### `jump() -> dict`

Makes the robot jump once.

- jumping automatically clears crouch mode first
- in water or lava the robot swims upward instead, and the result has `swimming: True`; because crouch mode is cleared, a diving robot returns to the surface, rising about 3 blocks a second
- `jumped` is `True` when the robot was on the ground and jumped, `False` when it was already in the air

### `pillar_up(count: int = 1, timeout: float = 30.0, poll_interval: float = 0.25) -> dict`

Builds straight up under the robot with the block in the selected slot, and waits until it is done. Like a player pillaring, the robot looks straight down, jumps, places a block in the cell its feet just left, and lands on it. It does this `count` times (`1..64`), so it ends `count` blocks higher, standing on a one-block column.

```python
robot.select_slot(1)          # a stack of cobblestone
robot.pillar_up(count=4)      # {'placed': 4, 'x': 10.5, 'y': -56.0, 'z': 3.5}
```

- the server times each placement, on the first tick the robot is clear of the cell, so latency does not matter
- the robot first moves to the middle of its block when there is room, and is left looking straight down
- it must stand on the ground on top of a full block (not a slab), with room above its head to stand on the new block; the selected item must be a block with a top to stand on
- a ceiling stops it when there is no room for the next block
- raises `MineBotMovementFailedError` when it stops early, for example with no blocks left, a ceiling, out of energy, or after `stop()`; the message says how many blocks were placed and where the robot is
- raises `MineBotMissingItemError` or `MineBotInvalidItemError` at once when the selected slot is empty or not a block
- nothing can walk up the column afterwards; to come down, mine the blocks under the robot

### `bridge(direction: str, count: int = 1, timeout: float | None = None, poll_interval: float = 0.25) -> dict`

Builds a walkway out over open air with the block in the selected slot, and waits until it is done. Like a player bridging, the robot crouches, faces back the way it came, and backs up until it leans over the edge of the block it stands on. It looks down at that block's side, places a block against it, and backs onto the new block. It does this `count` times (`1..64`) toward `direction`: `"north"`, `"south"`, `"east"` or `"west"`.

```python
robot.select_slot(1)                  # a stack of cobblestone
robot.bridge("east", count=5)         # {'placed': 5, 'x': 15.5, 'y': 61.0, 'z': 4.5}
```

- the walkway is level with the block the robot stands on: stand on the last block before the gap; the cell beyond it must be open (air, water, lava, grass, ...)
- the server runs each step, so latency does not matter; each block takes about a second
- the crosshair has to really hit the side of the block underfoot from where the robot leans, so something in the way stops it
- the selected item must be a block with a top to stand on, level with the robot's feet; use full blocks
- there must be room for the crouched robot above each new block
- like other moves it stops before lava or fire; the detail then starts with `Stopped:`
- it ends crouched in the middle of the last block, facing `direction`; `uncrouch()` to stand
- `timeout` defaults to 10 seconds plus 2 per block
- raises `MineBotMovementFailedError` when it stops early, for example with the next cell already filled, no room above it, no blocks left, out of energy, or after `stop()`; the message says how many blocks were placed and where the robot is
- raises `MineBotMissingItemError` or `MineBotInvalidItemError` at once when the selected slot is empty or not a block
- `move()`, `move_by()` and `move_to()` end a running bridge

### `enter_vehicle() -> dict`

Enters the rideable vehicle directly in front of the robot. (Experimental)

### `exit_vehicle() -> dict`

Exits the vehicle the robot is currently riding. (Experimental)

### `print(*parts: Any) -> dict`

Sends a MineBot-labelled chat line to players on the server.

```python
robot.print("Track segment", 12, "ready")
```

Return shape:

- `sender`
  - the chat label, for example `MineBot:AB12CD34`
- `message`
  - the final joined message string

To send a line to one player only, use `say(..., to=...)` from the [Chat](#chat) section.

### `center() -> bool`

Snaps the robot to the exact center of the block it is standing on.

- sets `pitch` to `0.0`
- snaps `yaw` to the nearest cardinal direction
- raises `MineBotMovementFailedError` if exact centering is not possible

## Chat

Players can send a robot orders through the normal Minecraft chat. The mod keeps an inbox of messages for each robot, and your program reads it.

### How players address a robot

A chat line is delivered to robots when its text starts with `@` followed by an address. The address is matched without regard to upper or lower case, and the first rule that matches wins:

- `@all <text>`
  - every loaded robot
- `@bot <text>`
  - the nearest loaded robot in the sender's dimension
- `@AB12CD34 <text>`
  - the robot with that exact code
- `@<name> <text>`
  - every robot whose name tag matches, compared with spaces removed (`Mine Helper` is addressed as `@MineHelper`)
- `@AB1 <text>`
  - the one robot whose code starts with that prefix, if the prefix is at least 3 characters long and matches exactly one robot

Other `@` words are ignored, as are messages with no text after the address. Robots that have turned hostile never receive chat. Normal chat, and `/say`, `/me` and similar commands from players, the server console, and command blocks all count. The line itself still appears in chat for everyone.

If the addressed robot has no program connected (and the address was not `@all`), the sending player gets a private reply: `[MineBot:<CODE>] No program is connected. Your message was queued.`

The inbox keeps collecting while no program is connected. It is kept in memory only (lost when the robot's chunk unloads or the server restarts), holds at most `64` messages (the oldest is dropped when full), and discards messages older than `600` seconds when it is read.

### `read_chat(peek: bool = False, limit: int | None = None) -> dict`

Returns the messages addressed to this robot, oldest first.

- unless `peek=True`, the returned messages are removed from the inbox
- `limit` returns at most that many messages; it must be at least `1` (otherwise `MineBotInvalidRequestError`); without it, all waiting messages are returned
- needs no energy and does not interrupt movement or mining

Return shape:

- `messages`
  - list of message dictionaries, see below
- `remaining`
  - how many messages are still in the inbox after this read
- `dropped`
  - how many messages were lost because the inbox was full or they expired, since the last read that was not a peek

Each message has:

- `id`
  - per-robot message number, counting up from `1`
- `sender`
  - the player name, or the command source name such as `Server`
- `sender_type`
  - `"player"`, `"console"`, or `"command"` (command blocks and other command sources)
- `sender_uuid`
  - player UUID; only for players
- `text`
  - the message without the `@address` word
- `raw`
  - the full chat line as written
- `address`
  - how the robot was addressed: `"code"` (full code or prefix), `"name"`, `"bot"`, or `"all"`
- `timestamp_ms`
  - when the mod received the message, in milliseconds since the Unix epoch
- `age_seconds`
  - how long ago that was, with 1 decimal place
- `sender_dimension`, `sender_x`, `sender_y`, `sender_z`
  - where the sender stood when they wrote the message; absent for the server console
- `distance`
  - distance in blocks from the robot's current position to where the sender stood; absent when the sender is in another dimension or has no position

```python
result = robot.read_chat()
for message in result["messages"]:
    print(message["sender"], "->", message["text"])
```

### `wait_for_chat(timeout: float = 30.0, poll_interval: float = 0.5) -> list[dict]`

Polls `read_chat()` every `poll_interval` seconds until messages arrive.

- returns the list of messages from the first read that is not empty (the same message dictionaries as `read_chat()`), and removes them from the inbox
- returns `[]` after `timeout` seconds without messages; this is not an error
- because it keeps talking to the server, waiting in a `wait_for_chat(...)` loop also keeps the session alive

```python
while True:
    for message in robot.wait_for_chat(timeout=30.0):
        # Only players can receive a private line; answer the console and command blocks publicly.
        reply_to = message["sender"] if message["sender_type"] == "player" else None
        robot.say("Got it:", message["text"], to=reply_to)
```

### `say(*parts: Any, to: str | None = None) -> dict`

Sends a MineBot-labelled chat line, like `print(...)`.

- the message text is built by joining the arguments with spaces
- without `to`, every player on the server sees it
- with `to=<player name>` (case-insensitive), only that player sees it
- raises `MineBotPlayerNotFoundError` if no online player has that name

Return shape: `sender` and `message` as for `print(...)`, plus `to` (the player's exact name) when a recipient was given.

```python
robot.say("On my way", to="Steve")
```

## Perception

These calls only read the world. They need no energy and do not interrupt movement or mining.

### `inventory() -> dict`

Returns the robot's whole hotbar and fuel slot.

Return shape:

- `selected_slot`
  - the selected hotbar slot, `0` to `9`
- `slots`
  - all 10 slots in order; each has `slot`, `item` (`minecraft:air` when empty), and `count`, plus `damage` and `max_damage` for items that wear out, such as tools
- `slots_total`, `slots_used`
- `fuel_item`, `fuel_count`
  - contents of the fuel slot (`minecraft:air` when empty)
- `stored_range_blocks`
  - remaining movement range in blocks

### `scan_blocks(radius: int = 8, blocks: list[str] | None = None, limit: int = 64, center: tuple[int, int, int] | None = None) -> dict`

Lists the blocks the robot can see in a cube around its feet block, or around the block `center`.

A robot only knows what it can see. A block is reported when a straight line from the robot's eyes reaches one of its faces without hitting another block first. Glass and water are see-through; leaves and every solid block hide what is behind them. Blocks behind walls, buried underground, or on the far side of a hill are never reported, and there is no option to turn this off. To see more, move, turn a corner, or dig, then scan again.

- `radius`
  - half the width of the cube, clamped to `1..16`, or to `1..32` when `blocks` is given (only matching blocks are ray traced, so a filtered scan can reach further)
- `blocks`
  - optional filter: block ids such as `minecraft:oak_log`, or block tags such as `#minecraft:logs`; an unknown id or tag raises `MineBotInvalidRequestError`; without a filter every visible block matches, so `counts` becomes a census of what is in view
- `limit`
  - how many of the nearest matches to return, clamped to `1..256`
- `center`
  - `(x, y, z)` block position to scan around instead of the robot; it must be within `32` blocks of the robot, otherwise `MineBotInvalidRequestError`; visibility is still judged from the robot's eyes

Water and lava are reported by their fluid, the same ids `camera.inspect()` returns: a source block is `minecraft:water` or `minecraft:lava`, flowing fluid is `minecraft:flowing_water` or `minecraft:flowing_lava`. The `blocks` filter matches these ids, so `blocks=["minecraft:lava"]` finds lava sources only (the ones a bucket can pick up and water turns into obsidian); pass both ids to find all lava. Chunks that are not loaded are skipped.

Return shape:

- `origin_x`, `origin_y`, `origin_z`, `radius`
  - the cube that was scanned
- `matches`
  - nearest first; each has `block`, `x`, `y`, `z`, and `distance` (from the robot's eyes to the block center, even when `center` is used)
- `total_matches`
  - number of visible matching blocks before `limit` was applied
- `truncated`
  - `True` when more blocks matched than were returned
- `counts`
  - visible matching blocks per block id, over all matches; holds at most the 32 most common ids

An empty result means nothing matching is in view from where the robot stands. It does not mean there is none nearby.

```python
logs = robot.scan_blocks(radius=12, blocks=["#minecraft:logs"], limit=5)
for match in logs["matches"]:
    print(match["block"], match["x"], match["y"], match["z"], match["distance"])
```

### `scan_entities(radius: float = 16.0, types: list[str] | None = None, players_only: bool = False, limit: int = 32) -> dict`

Lists the entities the robot can see, nearest first. The robot itself, dead entities, and players in spectator mode are left out.

Entities hidden behind blocks are left out too: mobs, animals, dropped items and vehicles are only listed when a straight line from the robot's eyes reaches them (glass and water are see-through). Players are the exception. Like a name tag that shows through blocks, a player is noticed through walls unless they are sneaking or invisible.

- `radius`
  - search distance in blocks, clamped to `1..64`
- `types`
  - optional filter of entity type ids such as `minecraft:sheep`; an unknown id raises `MineBotInvalidRequestError`
- `players_only`
  - only players
- `limit`
  - clamped to `1..128`

Return shape:

- `radius`, `total`, `truncated`
- `entities`
  - each has:
    - `entity_id`: number used by `look_at(entity_id=...)`; valid while the entity stays loaded
    - `uuid`, `type`, `name`
    - `category`: `"player"`, `"minebot"`, `"item"` (dropped items), `"vehicle"` (boats and minecarts), `"hostile"`, `"animal"`, or `"other"` (for example villagers and golems)
    - `x`, `y`, `z`, `distance` (from the robot's position)
    - `line_of_sight`: `True` when the robot sees the entity directly; `False` only for a player noticed through a wall
    - `health`, `max_health`: living entities only
    - `item`, `count`: dropped items only

### `environment() -> dict`

Returns a snapshot of the robot's surroundings:

- `dimension`, `biome` (`"unknown"` if the game reports none)
- `time_of_day` (`0` to `23999`), `day`, `is_day`
- `raining`, `thundering`
- `light`
  - combined light level at the robot's eye block
- `block_below`, `block_at_feet`, `block_at_head`
- `on_ground`, `in_water`, `in_lava`

## Inventory and world interaction

### `hotbar(slot: int) -> MineBotSlot`

Selects one of the robot's 10 hotbar slots and returns a helper object.

```python
robot.hotbar(3).inspect()
```

### `hotbox(slot: int) -> MineBotSlot`

Alias for `hotbar(slot)`.

### `hotbar(slot).drop(count: int | None = None) -> bool`

Drops items from the selected robot hotbar slot.

- if `count` is omitted, the whole slot stack is dropped
- if `count` is larger than the stack, the whole stack is dropped
- the items are thrown like a player's drop key: about 3 blocks along the robot's look direction when it looks straight ahead, at its feet when it looks straight down (`turn_to(pitch=90)`)
- thrown items get the player's 2-second pickup delay; after that, anyone who walks over them picks them up, including this robot

### `select_slot(slot: int) -> dict`

Legacy compatibility alias for selecting a slot directly.

### `move_item(from_slot: int, to_slot: int, count: int | None = None) -> dict`

Moves items between two hotbar slots (`0` to `9`).

- if the target slot is empty, up to `count` items move there (the whole stack if `count` is omitted)
- if the target slot holds the same item, as many as fit are merged in; it raises `MineBotTargetFullError` only when nothing fits
- if the target slot holds a different item and `count` is omitted or equals the whole stack, the two slots are swapped; with a smaller `count` it raises `MineBotTargetFullError`
- a `count` larger than the stack is reduced to the stack size
- raises `MineBotMissingItemError` if the source slot is empty, and `MineBotInvalidRequestError` for a slot outside `0..9`, the same slot twice, or a `count` below `1`
- needs no energy

Returns `{"from": 2, "to": 5, "moved": 16, "swapped": False}`.

### `refuel(count: int | None = None) -> dict`

Moves blaze powder from the robot hotbar into its fuel slot.

- without `count`, moves as much as fits (the fuel slot holds up to `64`); a larger `count` is reduced to what is available and fits
- needs no energy, so it is how an empty robot recovers
- raises `MineBotMissingItemError` if the hotbar has no blaze powder, and `MineBotTargetFullError` if the fuel slot is full

Returns `{"moved": 5, "fuel_count": 8, "stored_range_blocks": 1600.0}`.

### `eat(item: str | None = None, count: int | None = None) -> dict`

Eats iron or copper ingots from the robot hotbar to get health back. Each ingot gives back one heart (`2` health); nothing else heals a robot.

- eats as many ingots as it takes to reach full health, never more; `count` sets a lower limit
- copper ingots go first, then iron; `item` (`"minecraft:iron_ingot"` or `"minecraft:copper_ingot"`) eats only that kind
- the last ingot can heal less than a full heart when the robot was less than a heart short
- needs no energy, and is instant: the robot makes the eating sound and crumbs fly from its face
- raises `MineBotTargetFullError` if the robot is already at full health, `MineBotMissingItemError` if the hotbar has no ingots (of `item`, when given), `MineBotInvalidItemError` for any other item, and `MineBotInvalidRequestError` for a `count` below `1`

Returns `{"eaten": 3, "spent": {"minecraft:copper_ingot": 1, "minecraft:iron_ingot": 2}, "healed": 6.0, "health": 18.0, "max_health": 20.0}`.

### `hotbar(slot).inspect() -> dict[str, str | int]`

Returns structured slot contents for the selected hotbar slot.

```python
slot_info = robot.hotbar(3).inspect()
print(slot_info["block"])
print(slot_info["count"])
```

Return shape:

- `block`
  - the Minecraft item id in the slot
- `count`
  - stack size in that slot

### `hotbar(slot).type() -> str`

Legacy compatibility alias for `hotbar(slot).inspect()`. Returns only the Minecraft item id.

### `inspect_slot(slot: int | None = None) -> dict[str, str | int]`

Returns structured slot contents for a specific slot or the currently selected slot.

```python
slot_info = robot.inspect_slot()
print(slot_info["block"])
print(slot_info["count"])
```

### `slot_type(slot: int | None = None) -> str`

Legacy compatibility alias for `inspect_slot(...)`. Returns only the Minecraft item id.

### `place() -> dict`

MineBot uses the selected hotbar item on the looked-at block, following normal vanilla item behavior as closely as possible.

- common interactive blocks such as doors, trapdoors, levers, and buttons get first chance to react, even if the robot is holding an item
- TNT is primed correctly when used with flint and steel or a fire charge
- block items place normally, against the face the crosshair hits, including replacing simple replaceable blocks such as snow layers when appropriate
- tool and utility items such as flint and steel or shears are used on the target block
- if the selected slot is empty, or the selected item does not consume the interaction, MineBot falls back to a simple block interaction when possible:

- open / close
- powered / unpowered

If the selected slot is empty and there is nothing to activate, `place()` returns a no-op result instead of raising an exception.

### `craft(item: str, count: int | None = None) -> bool`

Crafts the requested output item.

Example:

```python
robot.craft("minecraft:acacia_door")
robot.craft("minecraft:oak_planks", count=8)
```

Rules:

- recipes that fit a 2x2 grid (planks, sticks, torches, a crafting table, ...) work anywhere, like the player's own inventory grid
- recipes that need a 3x3 grid require the robot to be looking at a crafting table; otherwise `craft` raises `MineBotWrongTargetError` with the message `Not looking at crafting table`
- the item id must be a valid Minecraft item id
- the robot must have the required ingredients in its own hotbar
- the robot must have room for the crafted output and any recipe remainders
- if `count` is omitted, MineBot crafts one recipe result
- if `count` is provided, it must be a multiple of the recipe's output count

Returns `True` on success. On failure it raises a typed interaction exception such as `MineBotWrongTargetError` or `MineBotMissingIngredientsError`.

The underlying `craft` command result reports `crafted`, `count`, `operations`, `recipe_id`, and `grid` (`"2x2"` or `"3x3"`). Use `robot.command("craft", item=...)` if you need it.

### `attack(wait: bool = True, timeout: float = 10.0, poll_interval: float = 0.25) -> bool`

Starts mining the block in front of the robot.

By default, the SDK waits for the mining animation to finish before returning.

Returns `True` only if the block was actually broken.

Returns `False` if MineBot cannot start or finish the break, including cases where:

- no block is in range
- the selected tool is not suitable
- the selected tool is too damaged to finish the break
- the robot stops or runs out of energy before finishing

### `mine(timeout: float = 10.0, poll_interval: float = 0.25) -> dict`

Mines the block in the robot crosshair and waits until it is broken or mining stops.

Unlike `attack()`, it tells you why something went wrong:

- raises a `MineBotCommandError` subclass if mining cannot start, for example `MineBotInvalidRequestError` when no block is in front of the robot, or `MineBotOutOfEnergyError`
- raises `MineBotTimeoutError` if the block is still being broken after `timeout` seconds

Return shape:

- `broken`
  - `True` if the block was actually broken
- `block`
  - the block id that was targeted
- `pos`
  - the block position as a string such as `"12, 64, -3"`
- `message`
  - the reason when `broken` is `False`, for example an unsuitable tool; empty otherwise

```python
result = robot.mine()
if not result["broken"]:
    print("Could not mine", result["block"], "at", result["pos"], ":", result["message"])
```

### `attack_entity(until_dead=False, follow=False, min_health=None, max_seconds=None, wait=True, timeout=None, poll_interval=0.25) -> dict`

Melee-attacks the entity in the robot crosshair with the selected item, like a player's left click.

- the entity must be in the crosshair within `4` blocks and in front of any block; otherwise it raises `MineBotNotLookingAtEntityError`
- a mob that has died and is still showing its death animation is not in the crosshair
- aim first with `look_at(entity_id=...)`
- damage is `1` plus the attack damage of the selected item, including enchantments; the item loses durability like a weapon, and the target is knocked back
- mobs that are hit fight back against the robot; a blaze that is hit, and the blazes near it, shoot fireballs at it (see `recent_hurt` in [`status()`](#status---dict))
- any attackable entity can be hit, including boats and item frames
- players can only be attacked when the server has PvP enabled, otherwise it raises `MineBotInteractionUnavailableError`; players in creative or spectator mode are never hit (`hit` is `False`)
- a single attack has no attack cooldown: repeated calls hit at full strength
- needs energy

Return shape of a single attack:

- `entity`, `entity_id`
- `damage`
  - the damage that was dealt
- `hit`
  - `False` when the damage was blocked or ignored, for example because the target was hurt by something else a moment before and a weaker hit does not count during its hurt immunity
- `killed`
- `health`
  - the target's remaining health; only for living entities

#### Fighting to the end

With `until_dead=True` the robot fights the living entity in its crosshair until the fight is over, in one call. The server aims at the target every tick and swings each time the weapon has recharged, the way a player's fully charged attack does: a sword every `0.65` s, an axe every `1` to `1.25` s, never more often than every `0.5` s (a target ignores a second hit sooner than that). It only swings when the target itself is the first thing in its crosshair within reach.

The fight ends, and `ended` says why, when:

- `killed`: the target died
- `gone`: the target was removed or left for another dimension
- `out_of_reach`: the target was out of reach or out of sight for `2` s, or `5` s while the robot follows a target it can see
- `low_health`: the robot's health fell to `min_health` (default `8` of `20`) or below. It is checked after each hurt, so one big hit can take the robot below it
- `timeout`: `max_seconds` (`1` to `120`, default `30`) passed
- `out_of_energy`: the robot ran out of blaze powder energy
- `interrupted`: another command arrived. `status()`, `read_chat()`, `say()` / `print()`, `inventory()`, `scan_blocks()`, `scan_entities()`, `environment()`, `camera.inspect()` and `inspect_slot()` leave the fight running; any other command, `stop()` included, ends it

With `follow=True` the robot also walks after the target along a path, as `move_to()` does, to keep within `2.5` blocks of it, but only while the target is within `16` blocks of where the fight started. Without `follow` it fights from where it stands.

During a fight the robot does not step into lava or fire or off a drop of more than `3` blocks, as during `move_to()`; it fights on from where it stands instead.

It raises `MineBotInteractionUnavailableError` when the target is not alive (a boat, an item frame) or the robot's health is already at or below `min_health`, and `MineBotInvalidRequestError` for a `min_health` or `max_seconds` out of range.

With `wait=True` (default) the call waits for the end of the fight (at most `timeout`, default `max_seconds` plus `5` s) and returns the outcome, which `status()` also keeps as `last_fight`:

- `entity`, `entity_id`
- `killed`, `ended`, `message`
  - `message` says what happened, for example `Killed Blaze` or `Blaze stayed out of reach (8.7 blocks away) for 5 s; following it stopped: the robot keeps within 16 blocks of where the fight started`
- `swings`, `hits`, `damage`
  - the swings made, those that landed, and the damage they dealt
- `target_health`
  - the target's health at the end; only while the robot can still see it
- `seconds`
- `health`, `health_lost`
  - the robot's health at the end and what the fight cost it; `recent_hurt` in `status()` says what hit it

With `wait=False` it returns at once with `fighting: True`, `entity`, `entity_id`, the target's `health`, `swing_ticks` (game ticks between swings with the selected item), `follow`, `min_health` and `max_seconds`. Poll `status()` until `fighting` is `False` and read `last_fight`.

```python
blaze = robot.scan_entities(radius=8, types=["minecraft:blaze"])["entities"][0]
robot.look_at(entity_id=blaze["entity_id"])
fight = robot.attack_entity(until_dead=True, follow=True)
print(fight["message"], "- lost", fight["health_lost"], "health")
for hurt in robot.status()["recent_hurt"]:
    print(hurt["cause"], hurt.get("attacker", "?"), hurt["amount"])
```

### `use_item(hold_seconds=None, wait=True, timeout=None, poll_interval=0.25) -> dict`

Right-clicks with the selected item: buckets on water or lava, throwable items, boats, spawn eggs, and similar items. If that does nothing and a block is in the crosshair within reach, the item is used on that block exactly as `place()` would (flint and steel, bone meal, hoes, placing a block item), so for block targets `place()` and `use_item()` end up doing the same thing.

Hold-to-use items are held the way a player holds the button, for real game time, and then released:

| Item | Held for | What happens |
| --- | --- | --- |
| bow | 1 s (full draw) | fires an arrow where the robot looks at the release |
| crossbow | its charge time (1.25 s, less with Quick Charge) | loads; the next `use_item()` fires it at once |
| trident | 0.5 s | is thrown |
| shield, spyglass, goat horn | 1 s | raised, looked through or blown; a raised shield blocks attacks like a player's |

`hold_seconds` (`0.05` to `60`) sets a different hold time, for example `0.3` for a weak bow shot or `10` to keep a shield up. Items that are not held ignore it. While the item is held the robot can still turn, look and move, so it aims at the release; another hand action (`stop()`, `mine()`, `place()`, `use_item()`, `use_on_entity()`, `attack_entity()`, chest, furnace or crafting commands) interrupts it, as does changing the selected item.

Projectiles leave the robot's eyes in the look direction and fall with distance, so aim slightly above a far target. Bows and crossbows use ammunition from anywhere in the hotbar, the first stack in slot order.

- raises `MineBotMissingItemError` if the selected slot is empty, or if a bow or crossbow has no ammunition in the hotbar (arrows; a crossbow also takes firework rockets)
- raises `MineBotInteractionUnavailableError` for food and potions: a robot cannot eat or drink them (it eats iron and copper ingots with `eat()`)
- raises `MineBotInvalidRequestError` if `hold_seconds` is out of range
- with `wait=True`, raises `MineBotTimeoutError` if the held use has not ended `timeout` seconds after it started (default: the hold time plus 5 s)
- needs energy

The use is performed by a fake player: a stand-in player (named `[MineBot]`) that the mod creates at the robot's eyes, looking where the robot looks. It is lent the robot's whole hotbar, the selected item in its hand, so it finds ammunition the way a player does. Afterwards every stack goes back to its slot, new items (a filled bucket when you used one of several) go into free hotbar slots, and what does not fit is dropped at the robot.

Return shape for items that are clicked:

- `used`
  - the item that was used
- `accepted`
  - the game's own answer; `False` means the item did nothing, which is a normal result, not an error
- `projectiles`
  - entity ids of what the use launched, for example `["minecraft:snowball"]`; empty when nothing was thrown
- `spent`, `gained`
  - hotbar item counts that went down or up, for example `{"minecraft:bucket": 1}` and `{"minecraft:water_bucket": 1}`
- `selected_item`
  - what the selected slot holds now, for example `minecraft:water_bucket` after filling a bucket
- `on_block`
  - only present when the item was used on the crosshair block; holds the same fields `place()` returns, for example `{"used": "minecraft:flint_and_steel", "pos": "10, 64, -3"}`

Return shape for held items with `wait=True`:

- `used`
- `completed`
  - `False` when the use was interrupted before the release; `message` then says why
- `held_ticks`
  - how long it was held, in game ticks (20 per second)
- `projectiles`, `spent`, `gained`, `selected_item`
  - as above; a bow shot gives `projectiles: ["minecraft:arrow"]` and `spent: {"minecraft:arrow": 1}`
- `charged`
  - crossbows only: whether it is now loaded

With `wait=False` a held item returns at once with `used`, `accepted: True`, `holding: True`, `hold_ticks`, `eta_ticks` and `selected_item`; poll `status()` until `using_item` is `False` and read `last_use`.

```python
robot.select_slot(3)                     # a bow; arrows elsewhere in the hotbar
robot.look_at(entity_id=chicken_id)
shot = robot.use_item()
print(shot["projectiles"], shot["spent"])   # ['minecraft:arrow'] {'minecraft:arrow': 1}
```

`accepted` can be `True` even when nothing visibly changed, so check the world or `inventory()` when it matters. Known limits of the fake player:

- a thrown ender pearl does not teleport the robot, and a thrown trident with Loyalty does not come back to it
- fired arrows and tridents stuck in the ground cannot be picked up by the robot
- kills by projectiles, taming, and breeding are credited to `[MineBot]` rather than to the robot
- near a dedicated server's spawn protection area the fake player is refused

### `use_on_entity() -> dict`

Right-clicks the entity in the robot crosshair with the selected item, or with an empty hand: shears on a sheep, feeding and breeding animals, milking with a bucket, leads, saddles.

- the entity must be in the crosshair within `4` blocks; otherwise it raises `MineBotNotLookingAtEntityError`
- uses the same fake player as `use_item()`, with the same limits
- needs energy

Return shape: `entity`, `entity_id`, `used`, `accepted`, and `selected_item`, with the same meaning as for `use_item()`. For example, shears on a sheep that is already sheared can still report `accepted: True`.

### Automatic item pickup

MineBot automatically picks up nearby dropped item entities into its hotbar when:

- the item is close enough to the robot
- the item's pickup delay has run out (half a second for mined blocks and mob loot, 2 seconds for items a player or robot throws)
- the robot still has room in its 10-slot hotbar

This works whichever slot is selected, including an empty one. It is intended to let mining and block breaking collect drops without extra code.

### `break_block(...) -> bool`

Compatibility alias for `attack(...)`.

### `destroy(...) -> bool`

Compatibility alias for `attack(...)`.

### `robot.furnace.inspect() -> dict`

Reads the currently looked-at furnace-like block.

Supported blocks:

- furnace
- blast furnace
- smoker

Returns a dictionary like:

```python
{
    "kind": "minecraft:furnace",
    "input": {"item": "minecraft:iron_ore", "count": 3, "empty": False},
    "fuel": {"item": "minecraft:coal", "count": 4, "empty": False},
    "output": {"item": "minecraft:iron_ingot", "count": 1, "empty": False},
}
```

### `robot.furnace.place(...) -> bool`

Moves items from the robot hotbar into the furnace input and/or fuel slots.

Examples:

```python
robot.furnace.place(food="minecraft:iron_ore", fuel="minecraft:coal")
robot.furnace.place(food="minecraft:sand", food_count=3)
robot.furnace.place(fuel="minecraft:charcoal", fuel_count=2)
```

Notes:

- the requested fuel must be valid furnace fuel
- returns `True` on success

### `robot.furnace.take(...) -> bool`

Moves items from the furnace back into the robot hotbar.

Examples:

```python
robot.furnace.take()
robot.furnace.take(food="minecraft:glass", food_count=4)
robot.furnace.take(food="minecraft:iron_ore", fuel="minecraft:coal")
```

Notes:

- if no arguments are given, the SDK requests one item from the output slot
- `food` means the smeltable or smelted item
- when taking `food`, MineBot prefers the furnace output slot and falls back to the input slot
- returns `True` on success

### `robot.chest.inspect() -> dict`

Reads the currently looked-at chest-like block or storage entity.

Supported targets:

- chest
- trapped chest
- barrel
- shulker box (any color)
- hopper
- dropper
- dispenser
- chest minecart and hopper minecart
- chest boat and chest raft (any wood)

A storage minecart or chest boat in the crosshair within reach is used ahead of any block behind it; aim at it with `robot.look_at_entity(...)`. Its `kind` is the entity id, for example `minecraft:chest_minecart`, and a mineshaft cart fills with its loot the first time the robot reads it.

Looking at anything else raises `MineBotWrongTargetError` with the message `Not looking at a chest, barrel, shulker box, hopper, dropper, dispenser, storage minecart, or chest boat`.

Returns a dictionary like:

```python
{
    "kind": "minecraft:barrel",
    "slots_total": 27,
    "slots_used": 2,
    "items": {
        "minecraft:cobblestone": 32,
        "minecraft:torch": 12,
    },
}
```

### `robot.chest.place(item: str, count: int = 1) -> bool`

Moves the requested item from the robot hotbar into the looked-at chest-like block or storage entity.

If the container itself refuses the item, for example a shulker box put into a shulker box, it raises `MineBotInvalidItemError`.

### `robot.chest.take(item: str, count: int = 1) -> bool`

Moves the requested item from the looked-at chest-like block or storage entity into the robot hotbar.

## Camera

Every connected MineBot exposes `robot.camera`.

### `robot.camera.inspect() -> dict[str, str | float]`

Returns structured crosshair target data:

```python
target = robot.camera.inspect()
print(target["block"])
print(target["distance"])
```

Return shape:

- `block`
  - the Minecraft block id directly in the robot camera crosshair
  - water and lava are returned by their fluid: `minecraft:water` and `minecraft:lava` for source blocks, `minecraft:flowing_water` and `minecraft:flowing_lava` for flowing fluid
- `distance`
  - ray distance in blocks from the robot command-eye point to the hit point
  - if the crosshair does not hit a block within the configured vision range, the result is `51.0`

When a block is hit, the result also has:

- `x`, `y`, `z`
  - the position of the hit block
- `face`
  - the side that was hit: `"up"`, `"down"`, `"north"`, `"south"`, `"east"`, or `"west"`
- `in_reach`
  - `True` when mining and placing would act on this same block (their reach is `4` blocks); for water or lava, `True` when the surface is within `4` blocks

When an entity is in the crosshair in front of the block, it also has:

- `entity`, `entity_id`, `entity_uuid`, `entity_name`
- `entity_distance`
- `entity_in_reach`
  - whether `attack_entity()` and `use_on_entity()` can reach it

Keys that do not apply are missing from the dictionary.

```python
target = robot.camera.inspect()
if target.get("in_reach"):
    robot.mine()
```

### `robot.camera.snapshot(source: str = "render") -> bytes`

Captures a 640x360 PNG from the robot's eyes, looking where the robot looks, and returns the raw PNG bytes. The `+` in the centre is the crosshair: where `mine()`, `place()`, and `use_item()` act.

- `source="render"` (default)
  - The server draws the picture from what the robot can see, with Minecraft's block textures, per-side shading, block and sky light, biome colours, water and fog. It works with no player online and does not touch anyone's screen.
  - Each pixel shows the first thing along its line of sight, so nothing behind a wall can appear.
  - Players (in their skins), robots, common mobs, dropped items, and falling blocks are drawn with their real models and textures, standing still and facing where they look. Other entities are plain boxes coloured by kind: red hostile, green animal, blue player, grey robot, yellow item, brown vehicle, white other. Invisible entities are not drawn. The README's [current limitations](./README.md#current-limitations) lists the mobs.
  - Signs (standing, wall, and hanging) show the text on the side facing the robot, in the game's font, sized as in the game, so it is readable within a few blocks. Glowing text is bright with an outline. Characters the game's bitmap fonts lack, such as Chinese or Japanese, show as the game's missing-glyph box.
  - Other blocks the game draws with code instead of a model file (chests, beds, banners, heads) appear as plain boxes wearing their particle texture.
  - The view reaches 96 blocks into loaded chunks; past that, or past the loaded area, is fog. See the README's [current limitations](./README.md#current-limitations) for the full list of differences from the game.
  - A dedicated server needs Minecraft's textures for this. If it cannot load them, the call raises `MineBotCameraAssetsUnavailableError` (see [Camera textures on a server](./README.md#camera-textures-on-a-server)).
- `source="client"`
  - The Minecraft client of the player who summoned the robot renders a real frame. It raises `MineBotCameraOwnerRequiredError` when the robot has no owner, `MineBotCameraOwnerOfflineError` when the owner is offline, and `MineBotCameraOwnerUnavailableError` when their game is paused, minimized, or does not have the robot loaded. It never borrows another player's view.

A drawn snapshot usually takes 0.1 to 0.3 seconds. The first one after the server starts can take longer while the server loads the textures, and a `source="client"` frame can take longer on a slow client. A reply later than the client `timeout` closes the session (see [Lost connections](#lost-connections)), so construct the client with `timeout=20` for camera work.

```python
from pathlib import Path

Path("view.png").write_bytes(robot.camera.snapshot())
Path("owner_view.png").write_bytes(robot.camera.snapshot(source="client"))
```

### `robot.camera.stream(interval: float = 0.25, frame_limit: int | None = None, source: str = "render") -> Iterator[bytes]`

Returns a generator that repeatedly captures snapshots.

- `interval`
  - delay between frames in seconds
- `frame_limit`
  - optional maximum number of frames
- `source`
  - passed to every `snapshot()` call

### `robot.camera.type() -> dict[str, str | float]`

Legacy compatibility alias for `robot.camera.inspect()`.

### `look_type() -> dict[str, str | float]`

Legacy compatibility alias for `robot.camera.inspect()`.

## Raw commands

### `command(action: str, **payload: Any) -> dict`

Sends any command `action` from the [raw websocket protocol](./README.md#command-actions) with the given extra fields, and returns the command's `result` dictionary unchanged.

- failures raise the same typed exceptions as the named methods
- use it for fields a named method does not return, or for actions that have no named method

```python
result = robot.command("craft", item="minecraft:stick")
print(result["grid"], result["count"])
```

## Special behavior

### `evil() -> None`

Triggers hostile mode.

- the websocket session is immediately broken
- the call raises `MineBotBrokeFreeError`
- the server error code is `broke_free`
- the current message is `The robot broke free from its chains and is seeking vengeance.`

After this, the robot becomes a hostile attacker and can no longer be controlled through the SDK.

### Robot death

A robot whose health reaches zero dies for good, and every player sees its death message in chat.

- the next call on its client raises `MineBotDiedError` (code `died`) and the client is disconnected
- `exc.detail` is the death message players saw, for example `MineBot John was slain by Zombie`
- `exc.death` is a dict with `code`, `display_name`, `message`, `cause` (damage type id), `killer` (only when something killed it), `dimension`, `x`, `y`, `z`, `timestamp_ms` and `unread_chat` (messages that reached the robot but were never read, in the `read_chat()` format)
- `connect()` with that robot's code raises the same error, also after a server restart: deaths are saved with the world (the last 64)
- `MineBot.list_robots(include_dead=True)` lists the robot with `dead: True` and the same dict under `death`

```python
from minebot import MineBotDiedError

try:
    robot.wait_for_chat(timeout=30.0)
except MineBotDiedError as exc:
    print("Robot died:", exc.detail, "at", exc.death.get("x"), exc.death.get("y"), exc.death.get("z"))
```

## Errors

The SDK raises:

See [`PYTHON_EXCEPTIONS.md`](./PYTHON_EXCEPTIONS.md) for the dedicated exception guide, including `match/case` examples and the full `MineBotErrorCode` list.

- `MineBotError`
  - base class
- `MineBotConnectionError`
  - transport or session setup failure, or a connection that was lost or timed out (the client is then disconnected)
- `MineBotCommandError`
  - websocket or MineBot command error
- `MineBotBusyError`
  - another Python client is already controlling that robot
- `MineBotProgramRunningError`
  - another Python program already owns the robot session
- `MineBotMovementFailedError`
  - pathing movement stopped before the destination was reached
- `MineBotSeekingAirError`
  - a movement command was refused because the robot is swimming back to air; see [Running short of air](#running-short-of-air)
- `MineBotTimeoutError`
  - the SDK timed out while waiting for a state change, or `connect()` waited `4` seconds for the area of a robot that was not loaded
- `MineBotBrokeFreeError`
  - `robot.evil()` severed the session and turned the robot hostile
- `MineBotDiedError`
  - the robot died; `exc.death` says how and where (see [Robot death](#robot-death))
- `MineBotCameraUnavailableError`
  - base class for camera failures; raised itself (code `camera_error`) when no frame came back
- `MineBotCameraAssetsUnavailableError`
  - the server could not load Minecraft's textures to draw a snapshot
- `MineBotCameraOwnerRequiredError`
  - `source="client"` only: the robot has no recorded owner
- `MineBotCameraOwnerOfflineError`
  - `source="client"` only: the recorded owner is offline
- `MineBotCameraOwnerUnavailableError`
  - `source="client"` only: the owner is online, but their client cannot provide camera frames
- `MineBotInteractionError`
  - base class for structured crafting and container interaction failures
- `MineBotWrongTargetError`
  - wrong block type or not looking at any block
- `MineBotNotLookingAtBlockError`
  - the robot crosshair is not on a block
- `MineBotInteractionUnavailableError`
  - target block exists but cannot currently be accessed
- `MineBotMissingIngredientsError`
  - requested craft cannot be satisfied from the robot hotbar
- `MineBotMissingItemError`
  - robot hotbar is missing requested transfer items
- `MineBotInvalidItemError`
  - unknown item id or invalid slot item
- `MineBotInvalidRequestError`
  - the SDK or caller sent an invalid request payload
- `MineBotTargetFullError`
  - target slot or storage block is full
- `MineBotTargetEmptyError`
  - target slot or storage block does not contain the requested item
- `MineBotInventoryFullError`
  - the robot hotbar has no room for the requested transfer or craft output
- `MineBotOutOfEnergyError`
  - the robot has no blaze powder energy left for the action; use `refuel()`
- `MineBotNotLookingAtEntityError`
  - no entity is in the robot crosshair within reach; a subclass of `MineBotInteractionError`
- `MineBotEntityNotFoundError`
  - `look_at(entity_id=...)` was given an id that is not loaded
- `MineBotPlayerNotFoundError`
  - `say(..., to=...)` named a player who is not online

Current structured interaction error codes:

- `program_running`
- `movement_failed`
- `seeking_air`
- `camera_assets_unavailable`
- `camera_owner_required`
- `camera_owner_offline`
- `camera_owner_unavailable`
- `not_looking_at_block`
- `wrong_block`
- `interaction_unavailable`
- `invalid_item`
- `missing_item`
- `missing_ingredients`
- `target_full`
- `target_empty`
- `no_inventory_space`
- `out_of_energy`
- `not_looking_at_entity`
- `entity_not_found`
- `player_not_found`

## Breaking changes

SDK `0.2.0` and the matching mod build change the following existing behavior:

1. **Out-of-energy failures raise `MineBotOutOfEnergyError`.** The error code is now `out_of_energy`. Before, the same failures raised `MineBotInvalidRequestError` with code `invalid_request`. The message text is unchanged. If you caught `MineBotInvalidRequestError` to detect an empty robot, catch `MineBotOutOfEnergyError` instead.
2. **A lost connection raises `MineBotConnectionError` and disconnects the client.** When the websocket fails or the server closes it, calls now raise `MineBotConnectionError` and `is_connected()` becomes `False`. Before, raw `websocket` library exceptions escaped, or a closed socket surfaced as `Received invalid JSON`. A reply that does not arrive within the client `timeout` also closes the session, so use `MineBot(..., timeout=20)` for camera snapshots. See [Lost connections](#lost-connections).
3. **`craft()` without a crafting table succeeds for 2x2 recipes.** Before, it always raised `MineBotWrongTargetError` or `MineBotNotLookingAtBlockError` when the robot was not looking at a crafting table. Now only items that need a 3x3 grid still raise `MineBotWrongTargetError` there.
4. **Raw protocol only: `move_to` reads `y` as the height.** Raw clients used to be able to send `y` instead of `z` to `move_to`. The SDK never did this, so `robot.move_to(...)` callers are not affected; the new keyword-only `y=` argument is optional.
5. **`robot.camera.snapshot()` and `stream()` are drawn by the server by default.** They now return a picture the server draws from what the robot sees, and work with no player online. They used to capture the robot owner's game client and raise `MineBotCameraOwnerRequiredError`, `MineBotCameraOwnerOfflineError`, or `MineBotCameraOwnerUnavailableError` when that was not possible. Those errors now only come from `source="client"`, which keeps the old behaviour; pass it if you need the owner's real frame. The image is always 640x360.
6. **New `MineBotCameraAssetsUnavailableError`** (code `camera_assets_unavailable`, a subclass of `MineBotCameraUnavailableError`): a drawn snapshot failed because the server could not load Minecraft's textures.
7. **A dead robot raises `MineBotDiedError`, not `gone` or `not_found`.** Before, calls on a robot that had died raised `MineBotCommandError` with code `gone`, the same as a robot whose chunk had unloaded, and `connect()` to its code raised code `not_found`. Both now raise `MineBotDiedError` (code `died`), and the client is disconnected. It is a `MineBotCommandError` subclass, so `except MineBotCommandError` still catches it, but code that compared `exc.raw_code` with `"gone"` or `"not_found"` must also check `"died"`. See [Robot death](#robot-death).
8. **`MineBot.list_robots()` leaves out dying robots.** A robot used to stay listed for the second of its death animation. Listings also gain `health` and `max_health`.
9. **`display_name` is `MineBot <name>` or `MineBot <code>`.** It used to be the robot's name tag alone, or `MineBot` for a robot without one. It is the name players now see, in status, listings, scans and death reports. Use `code` to identify a robot.
10. **`move_to()` and `move_by()` judge success by where the robot ends up, in height too.** They used to raise `MineBotCommandError` whenever the server reported a failure, even with the robot 0.2 blocks from the target, and to return `True` whenever the robot was within `tolerance` horizontally, even 8 blocks above or below the target. Now they return `True` when the robot is within `tolerance` horizontally and `0.75` blocks of the target height (`1.25` afloat), and raise `movement_failed` otherwise, whatever the server reported. Code that relied on a move to a point on another floor "succeeding" now gets an error; code that retried moves that had in fact arrived can stop. The error message now ends with the robot's position and its horizontal and vertical distance from the target.
11. **`scan_blocks()` tells source fluid from flowing fluid.** Flowing water and lava are now reported as `minecraft:flowing_water` and `minecraft:flowing_lava`, in `matches` and in `counts`; before, every water or lava block was `minecraft:water` or `minecraft:lava`. A `blocks` filter of `minecraft:water` or `minecraft:lava` now matches source blocks only; add `minecraft:flowing_water` or `minecraft:flowing_lava` to get the flowing ones too. `radius` may now go up to `32` when `blocks` is given.
12. **A robot short of air turns back by itself.** With its head under water and only enough air left to swim back to where it last breathed, it drops its current order, stands up from a crouch, and swims back. An interrupted `move_to` or `move_by` raises `MineBotMovementFailedError` (`Ran short of air and turned back to ...`), and interrupted mining fails. Before, the robot kept going until it drowned. See [Running short of air](#running-short-of-air).
13. **New `MineBotSeekingAirError`** (code `seeking_air`): while the robot swims back for air, `move`, `move_by`, `move_to`, `crouch`, `center`, `jump`, `stop`, and `enter_vehicle` raise it instead of running. `move(..., duration=...)` raises it from its closing `move(0, 0)` when the robot turned back during the move.
14. **`move_to` paths go round water that reaches the ceiling.** Paths through such water, where the robot cannot breathe, are now a last resort, so some paths are longer than before.
15. **`jump()` reports `jumped: False` when the robot was already in the air.** It used to read the ground state before the robot had left the ground: a real jump reported `jumped: False`, and a jump in mid-air `True`. Now `jumped` is `True` exactly when the robot was on the ground and jumped.
16. **New `pillar_up()`** (raw command `pillar_up`). `status()` gains `pillaring`, `pillar_placed` and `pillar_requested`, and `pillar_up` is refused with `MineBotSeekingAirError` like the other movement commands.
17. **`MineBot.list_robots()` lists robots whose chunks are not loaded.** It used to list loaded robots only. Robots that are not loaded now follow the loaded ones, with `loaded: False`, no `entity_id`, and the health and position saved when they were last seen. Every entry has new fields `loaded` and `dead`. Dead robots are only listed with the new `include_dead=True`. Code that connected to every listed robot, or picked one by position, may now load robots far from any player.
18. **`connect()` loads a robot that is not loaded.** It used to raise `MineBotCommandError` with code `not_found` for a robot whose chunks were not loaded. It now loads the area where the robot was last seen and connects, which can take up to `4` seconds, and raises `MineBotTimeoutError` if the area has not loaded by then. `not_found` now means that no robot of this world has that code, or that the robot was not where it was last seen.
19. **`connect()` to a dead robot raises `MineBotDiedError` after a server restart too.** Deaths used to be forgotten when the server stopped, and `connect()` then raised `not_found`.
20. **Robots keep their area loaded for 5 minutes after their last order, and while in danger.** Before, a robot's chunks could unload as soon as the program disconnected and the robot stood still, freezing the robot and its drops until a player came near. See [Robot chunk loading](./README.md#robot-chunk-loading) and the `chunks.hold_seconds` setting. Robots also no longer force-load chunks, so `/forceload` is not affected by them.
21. **New `bridge()`** (raw command `bridge`). `status()` gains `bridging`, `bridge_placed` and `bridge_requested`, and `bridge` is refused with `MineBotSeekingAirError` like the other movement commands.
22. **`place()` no longer puts a block behind a crouched robot.** Crouched on the ground and looking down at `70` degrees or more, with air behind the block it stood on, the robot used to place against the back of that block whenever the crosshair was on its top or on anything lower, though the crosshair never touched that face. A block aimed into a pit in front of the robot could end up behind it. `place()` now places against the face the crosshair hits. To build out over open air use `bridge()`, or crouch, lean over the edge with `move()`, and place against the side face.
23. **A crouched robot leans out over ledges.** `move()` while crouched used to stop as soon as the robot's centre reached the edge of the block it stood on. It now goes on until the robot leans out up to `0.25` blocks past the edge, still standing on the block.
24. **`move(0, 0)` stops the robot dead.** The robot used to slide on for about 0.2 blocks after the input was released.
25. **`attack_entity()` can fight to the end.** New optional arguments `until_dead`, `follow`, `min_health`, `max_seconds`, `wait`, `timeout` and `poll_interval`. Called without them it hits once, as before. `status()` gains `fighting`, `last_fight` and, during a fight, `fight_target_id`, `fight_swings` and `fight_hits`.
26. **`status()` says what hurt the robot.** New fields `hurt_count` and `recent_hurt`.
27. **The crosshair passes through a mob that has died.** `attack_entity()`, `use_on_entity()`, the chest commands and `camera.inspect()` no longer find a mob in its one-second death animation; they act on, or report, what is behind it.
28. **New `eat()`** (raw command `eat`): the robot eats iron and copper ingots from its hotbar, one heart each. Health used to never come back. The `MineBotInteractionUnavailableError` that `use_item()` raises for food and potions now ends with `; they eat iron and copper ingots with eat`.
29. **`move_to()` gives up when the robot is stuck.** A robot pressed against something it could not get past, such as another robot in a one-block-wide bend, used to keep pushing until `timeout`. It now plans again when it has reached no new spot of its path for 1.5 seconds (3 in water, longer at a lower `speed`), and after 3 such stalls in a row, about 4.5 seconds, raises `MineBotMovementFailedError` with a reason starting `Stuck at` that names what it is pressed against.
30. **`move_to()` paths go round small blocks, robots, mobs, players and vehicles.** They used to run straight into cocoa pods, trapdoors, amethyst, pointed dripstone, open doors seen side-on and other part blocks, and through other robots, mobs and players. Some paths are now longer, and a target that only such blocks lead to may now raise `movement_failed`. A robot arriving next to another one that stands on the target point no longer snaps into it.
31. **`move_to()` onto a carpet, snow layer or candle stands on it.** It used to aim for the block above and raise `movement_failed` there. The raw `target_y` is now the top of the carpet or layer, for example `-59.938`.

## Example workflow

```python
from pathlib import Path
from minebot import MineBot

robots = MineBot.list_robots()
if not robots:
    raise RuntimeError("This world has no MineBots")

robot = MineBot(code=robots[0]["code"], url=robots[0]["endpoint"])
robot.connect()

print("Locate:", robot.locate())
print("Status:", robot.status())
print("Looking at:", robot.camera.inspect())

robot.hotbar(0)
print("Slot 0:", robot.inspect_slot())
robot.craft("minecraft:oak_planks")
Path("minebot_snapshot.png").write_bytes(robot.camera.snapshot())

robot.turn_to(45.0, -10.0)
robot.jump()
robot.attack()

robot.close()
```

## Running the bundled examples

The examples support three input modes:

1. Explicit CLI arguments.
2. `MINEBOT_URL` and `MINEBOT_CODE` environment variables.
3. Automatic selection of the first locally discoverable robot when the script supports it.

Examples:

```bash
python python_sdk/examples/list_robots.py
python python_sdk/examples/basic_control.py
python python_sdk/examples/basic_control.py ws://127.0.0.1:8765/minebot AB12CD34
python python_sdk/examples/camera_snapshot.py ws://127.0.0.1:8765/minebot AB12CD34 minebot_snapshot.png
python python_sdk/examples/camera_stream.py ws://127.0.0.1:8765/minebot AB12CD34 10 minebot_stream_frames
python python_sdk/examples/perception_dump.py
python python_sdk/examples/chat_listener.py ws://127.0.0.1:8765/minebot AB12CD34
python python_sdk/examples/pillar_up.py ws://127.0.0.1:8765/minebot AB12CD34 5
python python_sdk/examples/bridge.py ws://127.0.0.1:8765/minebot AB12CD34 east 5
python python_sdk/examples/bow_shot.py ws://127.0.0.1:8765/minebot AB12CD34 minecraft:chicken
python python_sdk/examples/fight.py ws://127.0.0.1:8765/minebot AB12CD34 minecraft:blaze
```

- `perception_dump.py` prints the robot's status, hotbar, fuel, environment, crosshair target, a census of the blocks around it, the nearest coal and iron ores, nearby entities, and the number of unread chat messages.
- `fight.py` fights the nearest mob of a type within reach with `attack_entity(until_dead=True, follow=True)`, then prints how the fight ended and what hurt the robot.
- `chat_listener.py` waits for chat addressed to the robot and answers. In game, try `@bot come` (the robot walks to you), `@bot where` (it tells you its position), or `@bot stop` (it stops and the script ends).
