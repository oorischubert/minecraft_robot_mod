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

Codes that the SDK does not know yet, such as the bridge's `gone` or `not_connected`, still raise `MineBotCommandError`; `exc.code` is then the plain string code.

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
- `MineBotMovementFailedError`
  - pathing movement stopped before completion
- `MineBotTimeoutError`
  - the SDK timed out waiting for a state change
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
  - destination slot/container is full
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

See [Breaking changes](./PYTHON_SDK.md#breaking-changes) in the SDK reference for the other changes in this release.
