import sys

from _common import resolve_connection
from minebot import MineBot, MineBotInteractionUnavailableError


def main(argv: list[str]) -> int:
    socket_url, robot_code = resolve_connection(
        argv,
        "Usage: fight.py [connection-socket robot-code] [entity-type]\n"
        "Example: fight.py ws://127.0.0.1:8765/minebot AB12CD34 minecraft:blaze",
    )
    entity_type = argv[3] if len(argv) > 3 else "minecraft:zombie"

    robot = MineBot(code=robot_code, url=socket_url)
    robot.connect()

    try:
        sword = next((s for s in robot.inventory()["slots"] if s["item"].endswith("_sword")), None)
        if sword is not None:
            robot.select_slot(sword["slot"])

        # Only what the robot can see is listed; walk closer if nothing is.
        seen = robot.scan_entities(radius=4, types=[entity_type], limit=1)["entities"]
        if not seen:
            print(f"No {entity_type} within reach in view.")
            return 1
        robot.look_at(entity_id=seen[0]["entity_id"])

        hurt_before = robot.status()["hurt_count"]
        try:
            # The server swings each time the weapon has recharged and follows a target that is knocked back.
            fight = robot.attack_entity(until_dead=True, follow=True, min_health=10, max_seconds=30)
        except MineBotInteractionUnavailableError as exc:
            print("Cannot fight:", exc.detail)
            return 1
        print(f"{fight['message']}: {fight['hits']} hits, {fight['damage']} damage, robot lost {fight['health_lost']} health")

        # Mobs fight back; status says what hurt the robot, and names the attacker when the robot saw it.
        for hurt in robot.status()["recent_hurt"]:
            if hurt["id"] > hurt_before:
                source = hurt.get("attacker") or ("an attacker out of view" if hurt.get("attacker_seen") is False else "")
                print(f"  lost {hurt['amount']} to {hurt['cause']}" + (f" from {source}" if source else ""))
    finally:
        robot.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
