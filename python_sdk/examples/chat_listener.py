import sys

from _common import resolve_connection
from minebot import MineBot, MineBotCommandError


def main(argv: list[str]) -> int:
    socket_url, robot_code = resolve_connection(
        argv,
        "Usage: chat_listener.py [connection-socket robot-code]\n"
        "Example: chat_listener.py ws://127.0.0.1:8765/minebot AB12CD34\n"
        "In game, talk to the robot with '@AB12CD34 hello', '@bot come' or '@all status'.",
    )

    robot = MineBot(code=robot_code, url=socket_url)
    robot.connect()
    robot.say("Listening. Try '@bot come', '@bot where' or '@bot stop'.")

    try:
        while True:
            # wait_for_chat polls the inbox; each poll also keeps the websocket alive.
            for message in robot.wait_for_chat(timeout=30.0):
                sender = message["sender"]
                # Only players can be whispered to; console and command block lines get a public reply.
                reply_to = sender if message.get("sender_type") == "player" else None
                text = message["text"].strip().lower()
                print(f"[{message['address']}] <{sender}> {message['text']}")

                try:
                    if text == "come" and "sender_x" in message:
                        robot.say("On my way,", sender, to=reply_to)
                        robot.move_to(message["sender_x"], message["sender_z"], y=message.get("sender_y"))
                        robot.say("Here!", to=reply_to)
                    elif text == "where":
                        pose = robot.locate()
                        robot.say(f"I am at {pose['x']:.1f} {pose['y']:.1f} {pose['z']:.1f}", to=reply_to)
                    elif text == "stop":
                        robot.stop()
                        robot.say("Stopped. Bye!")
                        return 0
                    else:
                        robot.say(f"You said: {message['text']}", to=reply_to)
                except MineBotCommandError as error:
                    robot.say(f"Sorry, that failed ({error})")
    except KeyboardInterrupt:
        pass
    finally:
        robot.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
