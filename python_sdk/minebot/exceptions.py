from __future__ import annotations

from enum import StrEnum
from typing import Any, Optional


class MineBotErrorCode(StrEnum):
    ERROR = "error"
    BUSY = "busy"
    BROKE_FREE = "broke_free"
    CAMERA_ASSETS_UNAVAILABLE = "camera_assets_unavailable"
    CAMERA_ERROR = "camera_error"
    CAMERA_OWNER_OFFLINE = "camera_owner_offline"
    CAMERA_OWNER_REQUIRED = "camera_owner_required"
    DIED = "died"
    CAMERA_OWNER_UNAVAILABLE = "camera_owner_unavailable"
    INTERACTION_UNAVAILABLE = "interaction_unavailable"
    INVALID_ITEM = "invalid_item"
    INVALID_REQUEST = "invalid_request"
    MISSING_INGREDIENTS = "missing_ingredients"
    MISSING_ITEM = "missing_item"
    MOVEMENT_FAILED = "movement_failed"
    NO_INVENTORY_SPACE = "no_inventory_space"
    NOT_LOOKING_AT_BLOCK = "not_looking_at_block"
    NOT_LOOKING_AT_ENTITY = "not_looking_at_entity"
    ENTITY_NOT_FOUND = "entity_not_found"
    OUT_OF_ENERGY = "out_of_energy"
    PLAYER_NOT_FOUND = "player_not_found"
    PROGRAM_RUNNING = "program_running"
    TARGET_EMPTY = "target_empty"
    TARGET_FULL = "target_full"
    TIMEOUT = "timeout"
    WRONG_BLOCK = "wrong_block"


def parse_error_code(code: Optional[str | MineBotErrorCode]) -> str | MineBotErrorCode:
    if code is None:
        return MineBotErrorCode.ERROR
    if isinstance(code, MineBotErrorCode):
        return code
    try:
        return MineBotErrorCode(str(code))
    except ValueError:
        return str(code)


class MineBotError(RuntimeError):
    """Base class for all MineBot SDK exceptions."""


class MineBotConnectionError(MineBotError):
    """Raised when the websocket connection cannot be established or used."""


class MineBotCommandError(MineBotError):
    """Base class for command failures returned by MineBot or raised by the SDK."""

    default_code: str | MineBotErrorCode = MineBotErrorCode.ERROR

    def __init__(self, message: str, *, code: Optional[str | MineBotErrorCode] = None) -> None:
        normalized = parse_error_code(code if code is not None else self.default_code)
        self.code = normalized
        self.raw_code = str(normalized)
        self.detail = message
        super().__init__(f"{self.raw_code}: {message}")


class MineBotBusyError(MineBotCommandError):
    """Raised when a robot is already controlled elsewhere."""

    default_code = MineBotErrorCode.BUSY


class MineBotProgramRunningError(MineBotBusyError):
    """Raised when another Python program already owns the robot session."""

    default_code = MineBotErrorCode.PROGRAM_RUNNING


class MineBotBrokeFreeError(MineBotCommandError):
    """Raised when robot.evil() severs control and turns the robot hostile."""

    default_code = MineBotErrorCode.BROKE_FREE


class MineBotDiedError(MineBotCommandError):
    """Raised when the robot died. Its session is over and the socket is closed.

    `death` holds what the bridge reported: code, display_name, message (the death message
    players saw, e.g. "MineBot was slain by Zombie"), cause (damage type id), killer (only when
    something killed it), dimension, x, y, z, timestamp_ms and unread_chat (chat messages that
    reached the robot but were never read, in the read_chat format).
    """

    default_code = MineBotErrorCode.DIED

    def __init__(
        self,
        message: str,
        *,
        code: Optional[str | MineBotErrorCode] = None,
        death: Optional[dict[str, Any]] = None,
    ) -> None:
        super().__init__(message, code=code)
        self.death: dict[str, Any] = dict(death or {})


class MineBotMovementFailedError(MineBotCommandError):
    """Raised when pathing movement stops before reaching the requested destination."""

    default_code = MineBotErrorCode.MOVEMENT_FAILED


class MineBotTimeoutError(MineBotCommandError):
    """Raised when the SDK timed out waiting for a MineBot state change."""

    default_code = MineBotErrorCode.TIMEOUT


class MineBotCameraUnavailableError(MineBotCommandError):
    """Base class for camera-related failures."""

    default_code = MineBotErrorCode.CAMERA_ERROR


class MineBotCameraAssetsUnavailableError(MineBotCameraUnavailableError):
    """Raised when the server cannot load Minecraft's block textures for a rendered snapshot."""

    default_code = MineBotErrorCode.CAMERA_ASSETS_UNAVAILABLE


class MineBotCameraOwnerRequiredError(MineBotCameraUnavailableError):
    """Raised when a robot has no recorded owner for camera capture."""

    default_code = MineBotErrorCode.CAMERA_OWNER_REQUIRED


class MineBotCameraOwnerOfflineError(MineBotCameraUnavailableError):
    """Raised when the robot owner is offline and camera capture cannot run."""

    default_code = MineBotErrorCode.CAMERA_OWNER_OFFLINE


class MineBotCameraOwnerUnavailableError(MineBotCameraUnavailableError):
    """Raised when the owner is online but their client cannot provide camera frames."""

    default_code = MineBotErrorCode.CAMERA_OWNER_UNAVAILABLE


class MineBotInteractionError(MineBotCommandError):
    """Base class for crafting, storage, and block-interaction failures."""


class MineBotWrongTargetError(MineBotInteractionError):
    """Raised when the robot is looking at the wrong block type."""

    default_code = MineBotErrorCode.WRONG_BLOCK


class MineBotNotLookingAtBlockError(MineBotWrongTargetError):
    """Raised when the robot crosshair is not on any block."""

    default_code = MineBotErrorCode.NOT_LOOKING_AT_BLOCK


class MineBotInteractionUnavailableError(MineBotInteractionError):
    """Raised when a valid target exists but cannot be used right now."""

    default_code = MineBotErrorCode.INTERACTION_UNAVAILABLE


class MineBotMissingIngredientsError(MineBotInteractionError):
    """Raised when a craft request cannot be satisfied from the robot hotbar."""

    default_code = MineBotErrorCode.MISSING_INGREDIENTS


class MineBotMissingItemError(MineBotInteractionError):
    """Raised when the robot does not contain enough of an item."""

    default_code = MineBotErrorCode.MISSING_ITEM


class MineBotInvalidItemError(MineBotInteractionError):
    """Raised when a requested Minecraft item id is unknown or invalid."""

    default_code = MineBotErrorCode.INVALID_ITEM


class MineBotTargetFullError(MineBotInteractionError):
    """Raised when a destination slot or storage block has no room left."""

    default_code = MineBotErrorCode.TARGET_FULL


class MineBotTargetEmptyError(MineBotInteractionError):
    """Raised when a requested source slot or storage block is empty."""

    default_code = MineBotErrorCode.TARGET_EMPTY


class MineBotInventoryFullError(MineBotInteractionError):
    """Raised when the robot hotbar has no room for the requested transfer."""

    default_code = MineBotErrorCode.NO_INVENTORY_SPACE


class MineBotInvalidRequestError(MineBotCommandError):
    """Raised when the SDK or caller sends an invalid command payload."""

    default_code = MineBotErrorCode.INVALID_REQUEST


class MineBotOutOfEnergyError(MineBotCommandError):
    """Raised when the robot has no blaze powder energy left for an action. Use robot.refuel()."""

    default_code = MineBotErrorCode.OUT_OF_ENERGY


class MineBotNotLookingAtEntityError(MineBotInteractionError):
    """Raised when no entity is in the robot crosshair within interaction reach."""

    default_code = MineBotErrorCode.NOT_LOOKING_AT_ENTITY


class MineBotEntityNotFoundError(MineBotCommandError):
    """Raised when look_at() is given an entity id that is not loaded."""

    default_code = MineBotErrorCode.ENTITY_NOT_FOUND


class MineBotPlayerNotFoundError(MineBotCommandError):
    """Raised when say(..., to=...) names a player who is not online."""

    default_code = MineBotErrorCode.PLAYER_NOT_FOUND


MINEBOT_CODE_TO_EXCEPTION: dict[str, type[MineBotCommandError]] = {
    MineBotErrorCode.BUSY.value: MineBotBusyError,
    MineBotErrorCode.BROKE_FREE.value: MineBotBrokeFreeError,
    MineBotErrorCode.CAMERA_ASSETS_UNAVAILABLE.value: MineBotCameraAssetsUnavailableError,
    MineBotErrorCode.CAMERA_ERROR.value: MineBotCameraUnavailableError,
    MineBotErrorCode.CAMERA_OWNER_OFFLINE.value: MineBotCameraOwnerOfflineError,
    MineBotErrorCode.CAMERA_OWNER_REQUIRED.value: MineBotCameraOwnerRequiredError,
    MineBotErrorCode.CAMERA_OWNER_UNAVAILABLE.value: MineBotCameraOwnerUnavailableError,
    MineBotErrorCode.DIED.value: MineBotDiedError,
    MineBotErrorCode.INTERACTION_UNAVAILABLE.value: MineBotInteractionUnavailableError,
    MineBotErrorCode.INVALID_ITEM.value: MineBotInvalidItemError,
    MineBotErrorCode.INVALID_REQUEST.value: MineBotInvalidRequestError,
    MineBotErrorCode.MISSING_INGREDIENTS.value: MineBotMissingIngredientsError,
    MineBotErrorCode.MISSING_ITEM.value: MineBotMissingItemError,
    MineBotErrorCode.MOVEMENT_FAILED.value: MineBotMovementFailedError,
    MineBotErrorCode.NO_INVENTORY_SPACE.value: MineBotInventoryFullError,
    MineBotErrorCode.NOT_LOOKING_AT_BLOCK.value: MineBotNotLookingAtBlockError,
    MineBotErrorCode.NOT_LOOKING_AT_ENTITY.value: MineBotNotLookingAtEntityError,
    MineBotErrorCode.ENTITY_NOT_FOUND.value: MineBotEntityNotFoundError,
    MineBotErrorCode.OUT_OF_ENERGY.value: MineBotOutOfEnergyError,
    MineBotErrorCode.PLAYER_NOT_FOUND.value: MineBotPlayerNotFoundError,
    MineBotErrorCode.PROGRAM_RUNNING.value: MineBotProgramRunningError,
    MineBotErrorCode.TARGET_EMPTY.value: MineBotTargetEmptyError,
    MineBotErrorCode.TARGET_FULL.value: MineBotTargetFullError,
    MineBotErrorCode.TIMEOUT.value: MineBotTimeoutError,
    MineBotErrorCode.WRONG_BLOCK.value: MineBotWrongTargetError,
}


__all__ = [
    "MineBotErrorCode",
    "parse_error_code",
    "MineBotError",
    "MineBotConnectionError",
    "MineBotCommandError",
    "MineBotBusyError",
    "MineBotProgramRunningError",
    "MineBotBrokeFreeError",
    "MineBotDiedError",
    "MineBotMovementFailedError",
    "MineBotTimeoutError",
    "MineBotCameraUnavailableError",
    "MineBotCameraAssetsUnavailableError",
    "MineBotCameraOwnerRequiredError",
    "MineBotCameraOwnerOfflineError",
    "MineBotCameraOwnerUnavailableError",
    "MineBotInteractionError",
    "MineBotWrongTargetError",
    "MineBotNotLookingAtBlockError",
    "MineBotInteractionUnavailableError",
    "MineBotMissingIngredientsError",
    "MineBotMissingItemError",
    "MineBotInvalidItemError",
    "MineBotTargetFullError",
    "MineBotTargetEmptyError",
    "MineBotInventoryFullError",
    "MineBotInvalidRequestError",
    "MineBotOutOfEnergyError",
    "MineBotNotLookingAtEntityError",
    "MineBotEntityNotFoundError",
    "MineBotPlayerNotFoundError",
    "MINEBOT_CODE_TO_EXCEPTION",
]
