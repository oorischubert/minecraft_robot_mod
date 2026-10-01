import sys

from _common import resolve_connection
from minebot import MineBot, MineBotMovementFailedError


def main(argv: list[str]) -> int:
    socket_url, robot_code = resolve_connection(
        argv,
        "Usage: pillar_up.py [connection-socket robot-code] [count]\n"
        "Example: pillar_up.py ws://127.0.0.1:8765/minebot AB12CD34 5",
    )
    count = int(argv[3]) if len(argv) > 3 else 3

    robot = MineBot(code=robot_code, url=socket_url)
    robot.connect()

    try:
        slot = next((s for s in robot.inventory()["slots"] if s["item"] == "minecraft:cobblestone"), None)
        if slot is None:
            print("Put cobblestone in the robot's hotbar first.")
            return 1
        robot.select_slot(slot["slot"])
        try:
            print("Pillar:", robot.pillar_up(count=count))
        except MineBotMovementFailedError as exc:
            # A ceiling, running out of blocks or energy: the message says how many blocks went in.
            print("Stopped early:", exc.detail)
    finally:
        robot.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
