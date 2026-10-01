import sys

from _common import resolve_connection
from minebot import MineBot, MineBotMovementFailedError


def main(argv: list[str]) -> int:
    socket_url, robot_code = resolve_connection(
        argv,
        "Usage: bridge.py [connection-socket robot-code] [direction] [count]\n"
        "Example: bridge.py ws://127.0.0.1:8765/minebot AB12CD34 east 5",
    )
    direction = argv[3] if len(argv) > 3 else "east"
    count = int(argv[4]) if len(argv) > 4 else 3

    robot = MineBot(code=robot_code, url=socket_url)
    robot.connect()

    try:
        slot = next((s for s in robot.inventory()["slots"] if s["item"] == "minecraft:cobblestone"), None)
        if slot is None:
            print("Put cobblestone in the robot's hotbar first.")
            return 1
        robot.select_slot(slot["slot"])
        try:
            # Stand at the edge to build out from; the walkway is level with the block underfoot.
            print("Bridge:", robot.bridge(direction, count=count))
        except MineBotMovementFailedError as exc:
            # Something in the way, no room overhead, lava ahead, out of blocks: the message says how many went in.
            print("Stopped early:", exc.detail)
    finally:
        robot.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
