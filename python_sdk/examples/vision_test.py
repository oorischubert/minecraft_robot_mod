import sys
from pathlib import Path
from _common import resolve_connection
from minebot import MineBot
import time

def main(argv: list[str]) -> int:
    socket_url, robot_code = resolve_connection(
        argv,
        "Usage: basic_control.py [connection-socket robot-code]\n"
        "Example: basic_control.py ws://127.0.0.1:8765/minebot AB12CD34",
    )
    list = MineBot.list_robots()
    robot = MineBot(code=list[0]['code'])
    robot.connect()
    time.sleep(5)
    robot.center()
    vision = robot.camera.inspect()
    robot.print("Type:",vision['block'],"Distance:",vision['distance'])
    return 0
    

if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
