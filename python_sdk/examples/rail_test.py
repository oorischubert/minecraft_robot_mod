import sys
from pathlib import Path
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
    print(list)
    robot = MineBot(code=list[0]['code'])
    robot.connect()
    time.sleep(5)
    robot.center()
    robot.turn_by(0,65)
    try:
        for i in range(30):
            if robot.camera.inspect()["distance"] < 2.5:
                slot = hotbar_finder(robot,"minecraft:diamond_pickaxe")
                robot.hotbar(slot)
                robot.attack()
                time.sleep(0.25)
            slot = hotbar_finder(robot,"minecraft:cobblestone")
            robot.hotbar(slot)
            robot.place()
            time.sleep(0.25)
            slot = hotbar_finder(robot,"minecraft:powered_rail")
            robot.hotbar(slot)
            robot.place()
            time.sleep(0.25)
            robot.move_by(1,0)
            if i%6==0:
                robot.print("placing redstone torch")
                slot = hotbar_finder(robot,"minecraft:redstone_torch")
                robot.hotbar(slot)
                robot.turn_by(90,0)
                time.sleep(2)
                robot.place()
                robot.turn_by(-90,0)
                time.sleep(0.25)
        robot.center()
        robot.print("Railroad done!")
        
    except Exception as e:
        print(e)

    finally:
        robot.close()
    return 0

if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
