# Python Exceptions

MineBot's Python SDK exposes structured exceptions in [`minebot.exceptions`](./python_sdk/minebot/exceptions.py).

The exception layer requires Python `3.11+` because it uses `enum.StrEnum`.

Every command failure raises a `MineBotCommandError` subclass with:

- `exc.code`
  - a `MineBotErrorCode` value when the code is known
- `exc.raw_code`
  - the raw string code exactly as reported by the SDK or server
- `exc.detail`
  - the human-readable failure message

This makes it easy to branch on failures using `match/case`.

Codes that the SDK does not know yet, such as the bridge's `gone`, `not_connected` or `not_found`, still raise `MineBotCommandError`; `exc.code` is then the plain string code. `connect()` raises `not_found` when no robot of this world has that code, or when the robot was not where it was last seen.

Connection problems are not command failures. They raise `MineBotConnectionError`, which has no `code`. This includes a websocket that fails, is closed by the server, or does not answer within the client `timeout`; the client is then disconnected and `robot.is_connected()` returns `False`.

## Basic pattern

```python
from minebot import MineBot, MineBotCommandError, MineBotErrorCode

robot = MineBot(code="AB12CD34", url="ws://127.0.0.1:8765/minebot")
robot.connect()

try:
    robot.move_to(128.5, -42.0)
except MineBotCommandError as exc:
    match exc.code:
        case MineBotErrorCode.MOVEMENT_FAILED:
            print("Pathing failed:", exc.detail)
        case MineBotErrorCode.OUT_OF_ENERGY:
            print("Put blaze powder in the robot hotbar, then call robot.refuel().")
        case MineBotErrorCode.PROGRAM_RUNNING:
            print("That robot is already controlled elsewhere.")
        case MineBotErrorCode.WRONG_BLOCK:
            print("The robot is looking at the wrong block.")
        case MineBotErrorCode.TIMEOUT:
            print("The SDK timed out while waiting for a result.")
        case _:
            raise
finally:
    robot.close()
```

`MOVEMENT_FAILED` also covers a robot that stopped short of lava, fire, a drop of more than 3 blocks, or (for `move_by`) deep water. Its detail then starts with `Stopped:`, for example `Stopped: a drop of more than 3 blocks ahead`. A robot that already stands in fire or lava is not stopped for stepping within or out of it, only for stepping into burning blocks it does not already touch.

`move_to()` also raises `MOVEMENT_FAILED` when the robot is stuck: it reached no new spot of its path, planned again and still got nowhere. Jumping at a step or being shoved back and forth by a mob does not count as getting anywhere. The detail starts with `Stuck at` and names what the robot is pressed against, for example `Stuck at (x, y, z), blocked by MineBot 1A2B3C4D at (x, y, z), and found no way round` or `Stuck at (x, y, z), against minecraft:cobweb at (x, y, z), and found no way round`.

`move_by()` raises `MOVEMENT_FAILED` when the robot reaches the target X/Z more than a block above or below the target height, for example under an overhang: `MineBot reached that X/Z at y=67.0, but the walkable height there is y=73.0`. It is not lifted there.

`move_by()`, and `move_to()` without `y` in the Nether, raise `INVALID_REQUEST` (`MineBotInvalidRequestError`) before moving when nothing walkable lies within 12 blocks of the robot's height at the target X/Z: `MineBot could not find a walkable Y height within 12 blocks of its own at that X/Z location`. Pass `y` to `move_to()` to aim for another floor.

`pillar_up()` raises `MOVEMENT_FAILED` when it stops before placing every block, for example at a ceiling or with no blocks left. The detail says how many were placed, for example `No headroom to stand on a block at 200, -53, 200: blocked by minecraft:stone at 200, -51, 200 (placed 2 of 3 blocks)`.

`attack_entity(until_dead=True)` raises `INTERACTION_UNAVAILABLE` (`MineBotInteractionUnavailableError`) before the fight starts when the target is not alive, for example `minecraft:oak_boat is not alive; until_dead fights living entities only`, or when the robot's health is already at or below `min_health`, for example `The robot's health is 6.0, already at or below min_health 8.0`. A fight that ends without a kill is not an error: the returned outcome says why in `ended` and `message`.

`eat()` raises `TARGET_FULL` (`MineBotTargetFullError`) when the robot is already at full health (`The robot is already at full health`), `MISSING_ITEM` when the hotbar has no iron or copper ingot, and `INVALID_ITEM` for any other item, for example `Robots eat only minecraft:iron_ingot and minecraft:copper_ingot, not minecraft:bread`.

`bridge()` raises `MOVEMENT_FAILED` the same way when it stops before placing every block, for example when the next cell is already filled, there is no room above it, or lava is ahead. The detail says how many were placed, for example `Cannot build at 205, 60, 4, east of the block the robot stands on: there is minecraft:netherrack there (placed 3 of 14 blocks)`.

You can also import the exception module directly:

```python
from minebot.exceptions import MineBotCommandError, MineBotErrorCode
```

## Exception hierarchy

- `MineBotError`
  - base class for all SDK exceptions
- `MineBotConnectionError`
  - websocket connection or transport failure, including a connection that was lost or timed out
- `MineBotCommandError`
  - base class for MineBot command failures
- `MineBotBusyError`
  - robot is busy
- `MineBotProgramRunningError`
  - another Python client already owns the robot
- `MineBotBrokeFreeError`
  - `robot.evil()` intentionally severed control
- `MineBotDiedError`
  - the robot died; the client is disconnected and `exc.death` holds the death message, cause, killer, place and unread chat
- `MineBotMovementFailedError`
  - pathing movement stopped before completion
- `MineBotSeekingAirError`
  - a movement command was refused because the robot is swimming back to where it last breathed
- `MineBotTimeoutError`
  - the SDK timed out waiting for a state change, or `connect()` waited `4` seconds for the area of a robot that was not loaded
- `MineBotCameraUnavailableError`
  - base class for camera failures
- `MineBotCameraAssetsUnavailableError`
  - the server could not load Minecraft's textures to draw a snapshot
- `MineBotCameraOwnerRequiredError`
  - `source="client"` only: no recorded camera owner
- `MineBotCameraOwnerOfflineError`
  - `source="client"` only: camera owner is offline
- `MineBotCameraOwnerUnavailableError`
  - `source="client"` only: owner is online but their client cannot provide frames
- `MineBotInteractionError`
  - base class for crafting and storage interaction failures
- `MineBotWrongTargetError`
  - wrong block type
- `MineBotNotLookingAtBlockError`
  - crosshair is not on a block
- `MineBotInteractionUnavailableError`
  - valid target exists but cannot be used right now
- `MineBotMissingIngredientsError`
  - not enough crafting ingredients
- `MineBotMissingItemError`
  - robot hotbar is missing required items
- `MineBotInvalidItemError`
  - invalid Minecraft item id or invalid slot item
- `MineBotInvalidRequestError`
  - invalid command payload
- `MineBotTargetFullError`
  - destination slot/container is full, or `eat()` found the robot at full health
- `MineBotTargetEmptyError`
  - source slot/container is empty
- `MineBotInventoryFullError`
  - robot hotbar has no room
- `MineBotNotLookingAtEntityError`
  - no entity is in the crosshair within reach; a subclass of `MineBotInteractionError`
- `MineBotOutOfEnergyError`
  - the robot has no blaze powder energy left for the action
- `MineBotEntityNotFoundError`
  - `look_at(entity_id=...)` was given an id that is not loaded
- `MineBotPlayerNotFoundError`
  - `say(..., to=...)` named a player who is not online

`MineBotOutOfEnergyError`, `MineBotEntityNotFoundError`, and `MineBotPlayerNotFoundError` derive directly from `MineBotCommandError`.

## Stable error codes

The SDK exposes these codes through `MineBotErrorCode`:

- `MineBotErrorCode.ERROR`
  - generic fallback when a failure has no code
- `MineBotErrorCode.BUSY`
- `MineBotErrorCode.BROKE_FREE`
- `MineBotErrorCode.CAMERA_ASSETS_UNAVAILABLE`
- `MineBotErrorCode.CAMERA_ERROR`
- `MineBotErrorCode.CAMERA_OWNER_REQUIRED`
- `MineBotErrorCode.CAMERA_OWNER_OFFLINE`
- `MineBotErrorCode.CAMERA_OWNER_UNAVAILABLE`
- `MineBotErrorCode.DIED`
- `MineBotErrorCode.ENTITY_NOT_FOUND`
- `MineBotErrorCode.INTERACTION_UNAVAILABLE`
- `MineBotErrorCode.INVALID_ITEM`
- `MineBotErrorCode.INVALID_REQUEST`
- `MineBotErrorCode.MISSING_INGREDIENTS`
- `MineBotErrorCode.MISSING_ITEM`
- `MineBotErrorCode.MOVEMENT_FAILED`
- `MineBotErrorCode.NO_INVENTORY_SPACE`
- `MineBotErrorCode.NOT_LOOKING_AT_BLOCK`
- `MineBotErrorCode.NOT_LOOKING_AT_ENTITY`
- `MineBotErrorCode.OUT_OF_ENERGY`
- `MineBotErrorCode.PLAYER_NOT_FOUND`
- `MineBotErrorCode.PROGRAM_RUNNING`
- `MineBotErrorCode.SEEKING_AIR`
- `MineBotErrorCode.TARGET_EMPTY`
- `MineBotErrorCode.TARGET_FULL`
- `MineBotErrorCode.TIMEOUT`
- `MineBotErrorCode.WRONG_BLOCK`

## Code-to-class mapping

The SDK also exports `MINEBOT_CODE_TO_EXCEPTION`, which maps raw code strings to the exception class raised for that code.

Example:

```python
from minebot import MINEBOT_CODE_TO_EXCEPTION

print(MINEBOT_CODE_TO_EXCEPTION["wrong_block"].__name__)
# MineBotWrongTargetError
```

## Breaking change: `out_of_energy`

Before SDK `0.2.0`, an action that failed because the robot had no blaze powder energy reported the code `invalid_request` and raised `MineBotInvalidRequestError`. It now reports `out_of_energy` and raises `MineBotOutOfEnergyError`. The message text, `The MineBot is out of blaze powder energy`, is unchanged.

Code that detected an empty robot like this:

```python
except MineBotInvalidRequestError:
    ...
```

must now catch `MineBotOutOfEnergyError`, or match `MineBotErrorCode.OUT_OF_ENERGY`. `MineBotOutOfEnergyError` is not a subclass of `MineBotInvalidRequestError`, so the old `except` clause no longer catches it.

## Breaking change: camera owner errors

`robot.camera.snapshot()` and `stream()` are now drawn by the server by default and no longer need the robot owner's game client. `MineBotCameraOwnerRequiredError`, `MineBotCameraOwnerOfflineError`, and `MineBotCameraOwnerUnavailableError` are therefore only raised for `snapshot(source="client")`. A drawn snapshot instead raises `MineBotCameraAssetsUnavailableError` (code `camera_assets_unavailable`) when the server cannot load Minecraft's textures. All four subclass `MineBotCameraUnavailableError`, so `except MineBotCameraUnavailableError` still catches every camera failure.

## Breaking change: robot death

Calls on a robot that has died used to raise `MineBotCommandError` with code `gone`, the same as a robot whose chunk had unloaded, and `connect()` to its code raised code `not_found`. Both now raise `MineBotDiedError` with code `died`, and the client is disconnected. `exc.detail` is the death message players saw, and `exc.death` has the details listed in [Robot death](./PYTHON_SDK.md#robot-death).

`MineBotDiedError` subclasses `MineBotCommandError`, so `except MineBotCommandError` still catches it. Code that checked `exc.raw_code == "gone"` or `"not_found"` to notice a lost robot should also check `MineBotErrorCode.DIED`.

## Breaking change: connecting to a robot that is not loaded

`connect()` to a robot whose chunks were not loaded used to raise `MineBotCommandError` with code `not_found`. It now loads the robot where it was last seen and connects. If the area has not loaded after `4` seconds it raises `MineBotTimeoutError` (code `timeout`); try again. `not_found` is now only raised when no robot of this world has the code, or when the robot was not where it was last seen.

`connect()` to a robot that died raises `MineBotDiedError` after a server restart too; it used to raise `not_found` once the server had restarted.

Code that caught `not_found` to wait until a player walked near the robot can connect directly now.

## Breaking change: when moves raise `movement_failed`

`move_to()` and `move_by()` now decide by where the robot ends up. They raise `MineBotCommandError` with code `movement_failed` when the robot is more than `tolerance` blocks from the target horizontally, or more than `0.75` blocks above or below the target height (`1.25` while floating), even if the server reported success. They no longer raise when the server reported a problem but the robot ended within those limits. The message ends with the robot's position and its distance from the target, for example `... Robot is at (4.5, 56.0, 4.5), 0.0 blocks from the target horizontally and 8.0 blocks below it`.

## Breaking change: no lift to the Nether roof

`move_by()`, and `move_to()` without `y` in the Nether, used to aim for the top of the bedrock roof when nothing walkable lay within 12 blocks of the robot's height at the target, and `move_by()` lifted the robot there through the bedrock. They now raise `MineBotInvalidRequestError` instead. A `move_by()` that reaches the target X/Z more than a block off its target height used to be lifted there through whatever was in between; it now raises `MineBotMovementFailedError`. The `could not find a walkable Y height` message now reads `... within 12 blocks of its own at that X/Z location`.

## Breaking change: turning back for air

A robot whose head is under water now turns back by itself when it has only enough air left to swim back to where it last breathed. A `move_to` or `move_by` it was running raises `MineBotMovementFailedError` with the message `Ran short of air and turned back to (x, y, z), where it last breathed`. Before, the robot kept going and drowned.

Until its head is above water again, `move`, `move_by`, `move_to`, `crouch`, `center`, `jump`, `stop`, and `enter_vehicle` raise the new `MineBotSeekingAirError` (code `seeking_air`). It subclasses `MineBotCommandError` only. `move(..., duration=...)` raises it from its closing `move(0, 0)` when the robot turned back during the move. Wait a few seconds, check `status()["seeking_air"]`, and continue:

```python
import time

from minebot import MineBotSeekingAirError

try:
    robot.move_to(40.5, 12.5)
except MineBotSeekingAirError:
    while robot.status().get("seeking_air"):
        time.sleep(0.5)
```

See [Running short of air](./PYTHON_SDK.md#running-short-of-air) for the details.

See [Breaking changes](./PYTHON_SDK.md#breaking-changes) in the SDK reference for the other changes in this release.
