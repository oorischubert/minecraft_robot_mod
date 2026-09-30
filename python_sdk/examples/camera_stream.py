from pathlib import Path
import sys

from _common import resolve_connection
from minebot import MineBot

def main(argv: list[str]) -> int:
    socket_url, robot_code = resolve_connection(
        argv,
        "Usage: camera_stream.py [connection-socket robot-code] [frame-count] [output-dir]\n"
        "Example: camera_stream.py ws://127.0.0.1:8765/minebot AB12CD34 10 minebot_stream_frames",
    )
    frame_count = int(argv[3]) if len(argv) >= 4 else 10
    output_dir = Path(argv[4]).expanduser() if len(argv) >= 5 else Path("minebot_stream_frames")
    output_dir.mkdir(parents=True, exist_ok=True)

    robot = MineBot(code=robot_code, url=socket_url, timeout=20)
    robot.connect()

    try:
        for index, frame in enumerate(robot.camera.stream(interval=0.35, frame_limit=frame_count), start=1):
            frame_path = output_dir / f"frame_{index:03d}.png"
            frame_path.write_bytes(frame)
            print(f"Saved {frame_path}")
    finally:
        robot.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
