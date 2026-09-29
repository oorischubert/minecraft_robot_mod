from pathlib import Path
import sys

from _common import resolve_connection
from minebot import MineBot

def main(argv: list[str]) -> int:
    socket_url, robot_code = resolve_connection(
        argv,
        "Usage: camera_snapshot.py [connection-socket robot-code] [output.png]\n"
        "Example: camera_snapshot.py ws://127.0.0.1:8765/minebot AB12CD34 minebot_snapshot.png",
    )
    output_path = Path(argv[3]).expanduser() if len(argv) >= 4 else Path("minebot_snapshot.png")

    robot = MineBot(code=robot_code, url=socket_url)
    robot.connect()

    try:
        snapshot = robot.camera.snapshot()
        output_path.write_bytes(snapshot)
        print(f"Saved snapshot to {output_path}")
        print("Camera target:", robot.camera.inspect())
    finally:
        robot.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
