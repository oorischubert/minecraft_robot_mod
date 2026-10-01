import math
import sys

from _common import resolve_connection
from minebot import MineBot, MineBotMissingItemError


def main(argv: list[str]) -> int:
    socket_url, robot_code = resolve_connection(
        argv,
        "Usage: bow_shot.py [connection-socket robot-code] [entity-type]\n"
        "Example: bow_shot.py ws://127.0.0.1:8765/minebot AB12CD34 minecraft:chicken",
    )
    wanted = argv[3] if len(argv) > 3 else None

    robot = MineBot(code=robot_code, url=socket_url)
    robot.connect()

    try:
        bow = next((s for s in robot.inventory()["slots"] if s["item"] == "minecraft:bow"), None)
        if bow is None:
            print("Put a bow and some arrows in the robot's hotbar first.")
            return 1
        robot.select_slot(bow["slot"])

        # Nearest mob in view; players are left alone.
        seen = robot.scan_entities(radius=24.0, types=[wanted] if wanted else None)["entities"]
        target = next((e for e in seen if e.get("category") in ("hostile", "animal")), None)
        if target is None:
            print("No mob in view within 24 blocks.")
            return 1

        # Arrows fall with distance: aim at the body, a little higher the further away it is.
        status = robot.status()
        distance = math.dist((status["x"], status["z"]), (target["x"], target["z"]))
        robot.look_at(x=target["x"], y=target["y"] + 0.5 + distance * distance / 400.0, z=target["z"])

        try:
            shot = robot.use_item()  # draws for 1 s, then fires
        except MineBotMissingItemError as exc:
            print("Cannot shoot:", exc.detail)
            return 1
        print(f"Shot at {target['type']} {distance:.1f} blocks away:", shot)
    finally:
        robot.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
