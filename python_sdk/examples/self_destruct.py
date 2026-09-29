import sys
from _common import resolve_connection
from minebot import MineBot
from minebot.exceptions import MineBotMissingItemError
import time

def hotbar_finder(robot: MineBot, item: str):
    for i in range(10):
        if robot.inspect_slot(i)["block"] == item:
            return i
    raise MineBotMissingItemError(f"Item {item!r} not in hotbar")
    
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
    slot = hotbar_finder(robot,"minecraft:tnt")
    robot.hotbar(slot)
    robot.turn_by(0,60)
    time.sleep(0.5)
    robot.place()
    if robot.inspect_slot(slot)["count"]>1:
        robot.turn_to(pitch=35)
        time.sleep(0.5)
        robot.place()
    slot = hotbar_finder(robot,"minecraft:flint_and_steel")
    robot.hotbar(slot)
    robot.place()
    robot.status()
    time.sleep(0.5)
    robot.jump()
    robot.print("Goodbye cruel world...")
    return 0
    

if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
