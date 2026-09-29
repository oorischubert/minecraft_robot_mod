import time
import sys

from _common import resolve_connection
from minebot import MineBot


def main(argv: list[str]) -> int:
    socket_url, robot_code = resolve_connection(
        argv,
        "Usage: basic_control.py [connection-socket robot-code]\n"
        "Example: basic_control.py ws://127.0.0.1:8765/minebot AB12CD34",
    )

    robot = MineBot(code=robot_code, url=socket_url)
    robot.connect()

    try:
        print(robot.status())
        time.sleep(1.0)
        robot.hotbar(1)
        robot.move(0.5, 0.0)
        time.sleep(1.0)
        robot.move(0.0, 0.0)
        print("Attack 1:", robot.attack())
        robot.turn_to(90.0, 0.0)
        robot.jump()
        time.sleep(0.5)
        print("Attack 2:", robot.attack())
        print("Selected slot:", robot.hotbar(0).inspect())
        print("Looking at:", robot.camera.inspect())
        print("Locate:", robot.locate())
    finally:
        robot.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
