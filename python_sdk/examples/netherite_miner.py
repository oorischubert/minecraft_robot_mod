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

def cliff_detect(robot: MineBot, delay: float = 0.25) -> bool:
    robot.turn_to(pitch=65)
    time.sleep(delay)
    distance = robot.camera.inspect()["distance"]
    time.sleep(delay)
    robot.turn_to(pitch=0)
    time.sleep(delay)
    if distance > 2.5: return True
    else: return False
        
def crouch_build(robot: MineBot, blocks: int):
    robot.print("Begin crouch_build procedure.")
    robot.turn_by(180,0)
    robot.crouch()
    for i in range(blocks):
        robot.move(-0.75,0,duration=0.5)
        slot = hotbar_finder(robot,"minecraft:cobblestone")
        robot.hotbar(slot)
        robot.turn_to(pitch=82.5)
        time.sleep(0.25)
        robot.place()
    robot.move(0.25,0,duration=0.1)
    robot.uncrouch()
    time.sleep(0.5)
    robot.turn_by(180,0)
    time.sleep(0.5)
    robot.center()
    
def mine_forward(robot: MineBot, delay: float = 0.25):
    robot.print("Begin mine_forward procedure.")
    slot = hotbar_finder(robot,"minecraft:diamond_pickaxe")
    robot.hotbar(slot)
    robot.attack()
    robot.turn_to(pitch=60)
    time.sleep(delay)
    robot.attack()
    robot.turn_to(pitch=0)
    time.sleep(delay)
        
def main(argv: list[str]) -> int:
    socket_url, robot_code = resolve_connection(
        argv,
        "Usage: basic_control.py [connection-socket robot-code]\n"
        "Example: basic_control.py ws://127.0.0.1:8765/minebot AB12CD34",
    )
    list = MineBot().list_robots()
    #print(list)
    robot = MineBot(code=list[0]['code'])
    robot.connect()
    time.sleep(5)
    robot.center()
    try:
        while True:
            if not cliff_detect(robot):
                distance = robot.camera.inspect()["distance"]
                if distance < 1.5: mine_forward(robot)
                else: robot.move_by(1,0)
            else:
                distance = robot.camera.inspect()["distance"]
                crouch_build(robot,round(distance)-1)
        
    except Exception as e:
        print(e)

    finally:
        robot.close()

    return 0

if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
