import sys

from _common import resolve_connection
from minebot import MineBot


def main(argv: list[str]) -> int:
    socket_url, robot_code = resolve_connection(
        argv,
        "Usage: perception_dump.py [connection-socket robot-code]\n"
        "Example: perception_dump.py ws://127.0.0.1:8765/minebot AB12CD34",
    )

    robot = MineBot(code=robot_code, url=socket_url)
    robot.connect()

    try:
        status = robot.status()
        print(f"Robot {status['code']} at {status['x']} {status['y']} {status['z']} "
              f"yaw={status['yaw']} pitch={status['pitch']} health={status['health']}/{status['max_health']}")

        inventory = robot.inventory()
        print(f"\nHotbar (selected slot {inventory['selected_slot']}):")
        for slot in inventory["slots"]:
            if slot["count"] > 0:
                wear = f"  damage {slot['damage']}/{slot['max_damage']}" if "max_damage" in slot else ""
                print(f"  [{slot['slot']}] {slot['item']} x{slot['count']}{wear}")
        print(f"Fuel: {inventory['fuel_count']} {inventory['fuel_item']} "
              f"(range {inventory['stored_range_blocks']} blocks)")

        env = robot.environment()
        print(f"\nEnvironment: {env['dimension']} / {env['biome']}, time {env['time_of_day']}, "
              f"{'day' if env['is_day'] else 'night'}, light {env['light']}, raining={env['raining']}")
        print(f"Standing on {env['block_below']}")

        target = robot.camera.inspect()
        print(f"\nCrosshair: {target['block']} at distance {target['distance']}", end="")
        if "x" in target:
            print(f" -> block {target['x']} {target['y']} {target['z']} face={target['face']} in_reach={target['in_reach']}")
        else:
            print()
        if "entity" in target:
            print(f"  entity in view: {target['entity_name']} ({target['entity']}, id {target['entity_id']})")

        blocks = robot.scan_blocks(radius=6, limit=10)
        print(f"\nBlocks in view within 6 blocks ({blocks['total_matches']}):")
        for block_id, count in blocks["counts"].items():
            print(f"  {count:5d}  {block_id}")
        print("Nearest ores in view:")
        ores = robot.scan_blocks(radius=12, blocks=["#minecraft:coal_ores", "#minecraft:iron_ores"], limit=5)
        for match in ores["matches"]:
            print(f"  {match['block']} at {match['x']} {match['y']} {match['z']} ({match['distance']} blocks)")

        entities = robot.scan_entities(radius=24)
        print(f"\nEntities within 24 blocks ({entities['total']}):")
        for entity in entities["entities"]:
            print(f"  #{entity['entity_id']} {entity['name']} [{entity['category']}] "
                  f"at {entity['x']} {entity['y']} {entity['z']} ({entity['distance']} blocks)")

        chat = robot.read_chat(peek=True)
        print(f"\nUnread chat messages: {len(chat['messages'])}")
    finally:
        robot.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
