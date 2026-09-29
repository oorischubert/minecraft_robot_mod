import sys
from pathlib import Path
from _common import resolve_connection
from minebot import MineBot
import time

def place_torch(robot: MineBot):
    robot.turn(60,90)
    robot.camera.inspect()
    
    
def main(argv: list[str]) -> int:
    socket_url, robot_code = resolve_connection(
        argv,
        "Usage: basic_control.py [connection-socket robot-code]\n"
        "Example: basic_control.py ws://127.0.0.1:8765/minebot AB12CD34",
    )
    list = MineBot.list_robots()
    robots=[MineBot(code="B1C687B7")]
    # for robot in list:
    #     robots.append(MineBot(code=robot['code']))

    print(list)
    #robot = MineBot(code=list[0]['code'])
    for robot in robots:
        robot.connect()
    time.sleep(5)
    for robot in robots:
        robot.center()
        robot.move_to(-533.5,-252.5)
        robot.center()
    try:
        for i in range(60):
            for robot in robots:
                robot.hotbar(0)
                robot.attack()
            time.sleep(0.5)
            for robot in robots:
                robot.turn(0,45)
            time.sleep(0.5)
            for robot in robots:
                robot.attack()
                robot.hotbar(1)
                robot.turn(0,60)
            time.sleep(0.5)
            for robot in robots:
                robot.place()
                robot.turn(90,0)
                robot.hotbar(2)
            time.sleep(0.5)
            for robot in robots:
                robot.place()
                robot.center()
                robot.move_by(1,0)
            time.sleep(0.5)  
            for robot in robots: 
                robot.center()
            time.sleep(0.5) 
        
    except Exception as e:
        print(e)

    finally:
        for robot in robots:
            robot.close()

    return 0

if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

