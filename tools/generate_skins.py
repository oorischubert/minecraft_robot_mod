"""Generate the MineBot skin textures (retro computer heads, refined from the original skin) and the spawn egg.

Usage: python3 tools/generate_skins.py  (writes src/main/resources/assets/minebot/textures/entity/minebot/
and textures/item/minebot_spawn_egg.png)

BipedEntityModel 64x64 layout: head (0,0,8,8,8), hat overlay (32,0,8,8,8), body (16,16,8,12,4),
arm (40,16,4,12,4; left mirrored), leg (0,16,4,12,4; left mirrored).
The hat overlay carries the monitor bezel, so the screen sits recessed half a pixel.
"""
import os, random
from PIL import Image

TEXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources', 'assets', 'minebot', 'textures')
OUT = os.path.join(TEXTURES, 'entity', 'minebot')
PARTS = {'head': (0, 0, 8, 8, 8), 'hat': (32, 0, 8, 8, 8), 'body': (16, 16, 8, 12, 4),
         'arm': (40, 16, 4, 12, 4), 'leg': (0, 16, 4, 12, 4)}


def face_rects(u, v, w, h, d):
    return {'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d),
            'right': (u, v + d, d, h), 'front': (u + d, v + d, w, h),
            'left': (u + d + w, v + d, d, h), 'back': (u + 2 * d + w, v + d, w, h)}


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


class Face:
    def __init__(self, skin, rect, flip=False):
        self.skin, self.flip = skin, flip
        self.x0, self.y0, self.w, self.h = rect

    def px(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            if self.flip:
                x = self.w - 1 - x
            self.skin.im.putpixel((self.x0 + x, self.y0 + y), tuple(c)[:3] + (255,))

    def rect(self, x, y, w, h, c, grain=0):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                n = self.skin.rng.randint(-grain, grain) if grain else 0
                self.px(xx, yy, tuple(max(0, min(255, ch + n)) for ch in c))

    def fill(self, c, grain=0):
        self.rect(0, 0, self.w, self.h, c, grain)

    def row(self, y, c, x=0, w=None):
        self.rect(x, y, self.w - x if w is None else w, 1, c)

    def col(self, x, c, y=0, h=None):
        self.rect(x, y, 1, self.h - y if h is None else h, c)


class Skin:
    def __init__(self, seed):
        self.im = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
        self.rng = random.Random(seed)

    def f(self, part, name):
        return Face(self, face_rects(*PARTS[part])[name], flip=(name == 'left'))

    def sides(self, part):
        return [self.f(part, n) for n in ('front', 'right', 'left', 'back')]


DARK_LED = (44, 48, 52)
LEDS = {'idle': dict(power=(236, 162, 40)),
        'connected': dict(power=(70, 224, 104)),
        'evil': dict(power=(255, 52, 40))}

KINDS = {
    # refined original: the same cool greys, cyan face
    'classic': dict(
        bezel=(200, 207, 215), bezel_hi=(228, 233, 238), bezel_lo=(150, 158, 168),
        case=(126, 135, 145), case_hi=(160, 168, 178), case_lo=(94, 102, 112),
        panel=(178, 186, 196), panel_hi=(206, 213, 221), panel_lo=(146, 154, 164),
        rib_hi=(112, 120, 130), rib_lo=(70, 77, 86), dark=(20, 25, 30),
        screen=(12, 24, 30), screen_alt=(17, 32, 39), phosphor=(122, 238, 255)),
    'beige': dict(
        bezel=(224, 215, 192), bezel_hi=(240, 234, 216), bezel_lo=(186, 176, 152),
        case=(204, 194, 168), case_hi=(224, 216, 194), case_lo=(168, 158, 132),
        panel=(214, 205, 181), panel_hi=(234, 227, 207), panel_lo=(180, 170, 146),
        rib_hi=(150, 141, 120), rib_lo=(108, 100, 84), dark=(30, 28, 26),
        screen=(10, 22, 12), screen_alt=(14, 30, 17), phosphor=(112, 255, 132)),
    'terminal': dict(
        bezel=(66, 64, 62), bezel_hi=(94, 90, 86), bezel_lo=(44, 42, 41),
        case=(80, 76, 72), case_hi=(106, 101, 96), case_lo=(58, 55, 52),
        panel=(152, 144, 130), panel_hi=(178, 170, 154), panel_lo=(122, 115, 102),
        rib_hi=(74, 70, 66), rib_lo=(38, 36, 34), dark=(20, 18, 16),
        screen=(24, 15, 6), screen_alt=(33, 21, 9), phosphor=(255, 178, 50)),
    'pocket': dict(
        bezel=(198, 198, 190), bezel_hi=(222, 222, 214), bezel_lo=(160, 160, 152),
        case=(190, 190, 182), case_hi=(214, 214, 206), case_lo=(154, 154, 146),
        panel=(204, 204, 196), panel_hi=(226, 226, 218), panel_lo=(168, 168, 160),
        rib_hi=(146, 146, 140), rib_lo=(102, 102, 98), dark=(40, 40, 50),
        screen=None, phosphor=None, button=(172, 40, 96), button_hi=(214, 86, 140)),
}


def screen_colors(K, state):
    """(screen, screen_alt, face) for this kind and state."""
    if K['screen'] is None:  # reflective LCD: dark pixels on a light screen
        return {'idle': ((116, 126, 88), (116, 126, 88), (88, 98, 66)),
                'connected': ((158, 182, 58), (158, 182, 58), (38, 62, 30)),
                'evil': ((204, 104, 56), (204, 104, 56), (70, 22, 14))}[state]
    if state == 'evil':
        return (28, 9, 9), (38, 12, 12), (255, 64, 52)
    face = K['phosphor'] if state == 'connected' else mix(K['screen'], K['phosphor'], 0.5)
    return K['screen'], K['screen_alt'], face


def draw_screen(f, K, state):
    scr, alt, face = screen_colors(K, state)
    for y in range(1, 6):
        f.row(y, alt if y % 2 else scr, 1, 6)
    lcd = K['screen'] is None
    glare = mix(scr, (255, 255, 255), 0.18 if lcd else 0.22)
    f.px(6, 1, glare)
    if state == 'connected':
        pts = [(2, 2), (2, 3), (5, 2), (5, 3), (1, 4), (6, 4), (2, 5), (3, 5), (4, 5), (5, 5)]
    elif state == 'idle':
        pts = [(1, 3), (2, 3), (5, 3), (6, 3), (3, 5), (4, 5)]
    else:
        pts = [(1, 2), (1, 3), (2, 3), (6, 2), (6, 3), (5, 3), (2, 5), (3, 5), (4, 5), (5, 5)]
    for x, y in pts:
        # phosphor is a touch brighter on the lit scanlines
        c = face if (lcd or y % 2) else mix(face, scr, 0.12)
        f.px(x, y, c)


def chest_lights(K, state):
    L = LEDS[state]
    act = K['phosphor'] if K['phosphor'] else (120, 220, 90)
    if state == 'idle':
        return [L['power'], DARK_LED, DARK_LED, DARK_LED]
    if state == 'connected':
        return [L['power'], act, act, DARK_LED]
    return [L['power']] * 4


def retro(kind, state, seed):
    K = KINDS[kind]
    L = LEDS[state]
    s = Skin(seed)
    g = 3

    # ---- head: a CRT monitor
    f = s.f('head', 'front')
    f.fill(K['bezel']); f.row(0, K['bezel_hi']); f.row(7, K['bezel_lo'])
    draw_screen(f, K, state)
    for n in ('right', 'left'):
        f = s.f('head', n)
        f.fill(K['case'], g); f.row(0, K['case_hi']); f.row(7, K['case_lo'])
        f.col(7, K['bezel']); f.col(6, K['case_lo'])
        for y in (3, 5):
            f.row(y, K['rib_lo'], 1, 4)
    f = s.f('head', 'top')
    f.fill(K['case'], g); f.row(7, K['bezel_hi']); f.row(0, K['case_lo'])
    for y in (2, 4):
        f.row(y, K['rib_lo'], 2, 4)
    f = s.f('head', 'back')
    f.fill(K['case'], g); f.row(0, K['case_hi']); f.row(7, K['case_lo'])
    f.rect(1, 1, 6, 6, K['case_lo'])
    for y in (2, 4):
        f.row(y, K['rib_lo'], 2, 4)
    f.rect(3, 6, 2, 1, K['dark'])
    s.f('head', 'bottom').fill(K['case_lo'])

    # ---- hat overlay: the bezel, half a pixel proud of the screen
    f = s.f('hat', 'front')
    f.row(0, K['bezel_hi']); f.col(0, K['bezel'], 1, 5); f.col(7, K['bezel'], 1, 5)
    f.row(6, K['bezel']); f.row(7, K['bezel_lo'])
    f.px(1, 6, K['bezel_lo']); f.px(2, 6, K['bezel_lo']); f.px(6, 6, L['power'])
    for n in ('right', 'left'):
        f = s.f('hat', n)
        f.col(7, K['bezel']); f.px(7, 0, K['bezel_hi']); f.px(7, 7, K['bezel_lo'])
    s.f('hat', 'top').row(7, K['bezel_hi'])
    s.f('hat', 'bottom').row(0, K['bezel_lo'])

    # ---- body
    f = s.f('body', 'front')
    f.fill(K['case'], g); f.row(0, K['case_hi']); f.row(11, K['case_lo'])
    f.rect(1, 1, 6, 10, K['panel'], g); f.row(1, K['panel_hi'], 1, 6); f.row(10, K['panel_lo'], 1, 6)
    lights = chest_lights(K, state)
    if kind == 'pocket':
        f.px(2, 2, lights[0])
    else:
        f.rect(1, 2, 6, 1, K['panel_lo'])
        for i, c in enumerate(lights):
            f.px(2 + i, 2, c)
    if kind == 'classic':
        f.rect(1, 4, 6, 2, K['panel_lo']); f.row(4, K['dark'], 2, 4); f.px(5, 5, lights[1])
        for x in range(2, 6):
            f.rect(x, 7, 1, 3, K['rib_lo'] if x % 2 == 0 else K['panel_lo'])
    elif kind == 'beige':
        f.rect(1, 4, 6, 2, K['panel_lo']); f.row(4, K['dark'], 2, 4)
        f.px(2, 5, lights[1]); f.px(5, 5, K['panel_hi'])
        f.row(7, K['rib_lo'], 2, 4); f.row(9, K['rib_lo'], 2, 4)
    elif kind == 'terminal':
        card, hole = (232, 222, 186), (150, 132, 96)
        f.rect(1, 4, 6, 1, K['dark'])
        f.rect(2, 5, 4, 3, card)
        for x, y in [(3, 5), (5, 5), (2, 6), (4, 6), (3, 7), (5, 7)]:
            f.px(x, y, hole)
        f.row(9, K['rib_lo'], 2, 4)
    elif kind == 'pocket':
        for x, y in [(2, 5), (1, 6), (2, 6), (3, 6), (2, 7)]:
            f.px(x, y, K['dark'])
        f.px(5, 6, K['button']); f.px(6, 5, K['button_hi'] if state != 'idle' else K['button'])
        f.rect(3, 9, 1, 1, K['rib_lo']); f.rect(4, 9, 1, 1, K['rib_lo'])
        for x, y in [(5, 9), (6, 8), (6, 9)]:
            f.px(x, y, K['rib_lo'])
    f = s.f('body', 'back')
    f.fill(K['case'], g); f.row(0, K['case_hi']); f.row(11, K['case_lo'])
    if kind == 'pocket':
        f.rect(1, 3, 6, 6, K['case_lo']); f.rect(2, 4, 4, 4, K['case']); f.rect(3, 3, 2, 1, K['case_hi'])
    else:
        for x in range(1, 7):
            f.rect(x, 2, 1, 7, K['rib_lo'] if x % 2 else K['case_hi'])
    if kind == 'beige':
        f.px(2, 10, (122, 84, 176)); f.px(3, 10, (70, 164, 92)); f.rect(5, 10, 2, 1, K['dark'])
    else:
        f.rect(3, 10, 2, 1, K['dark'])
    for n in ('right', 'left'):
        f = s.f('body', n)
        f.fill(K['case'], g); f.row(0, K['case_hi']); f.row(11, K['case_lo'])
        for y in (3, 5, 7):
            f.row(y, K['rib_lo'], 1, 2)
    f = s.f('body', 'top'); f.fill(K['case_hi'], g); f.rect(2, 1, 4, 2, K['dark'])
    s.f('body', 'bottom').fill(K['case_lo'])

    # ---- arms: shoulder block, accordion tube, clamp hand
    for f in s.sides('arm'):
        f.fill(K['case'], g); f.row(0, K['case_hi']); f.row(2, K['case_lo'])
        for y in range(3, 9):
            f.row(y, K['rib_hi'] if y % 2 else K['rib_lo'])
        f.row(9, K['case_hi']); f.row(11, K['case_lo'])
    s.f('arm', 'front').rect(1, 11, 2, 1, K['dark'])
    s.f('arm', 'top').fill(K['case_hi'], g)
    s.f('arm', 'bottom').fill(K['dark'])

    # ---- legs: hip block, accordion tube, boot
    for f in s.sides('leg'):
        f.fill(K['case'], g); f.row(0, K['case_lo'])
        for y in range(2, 8):
            f.row(y, K['rib_hi'] if y % 2 == 0 else K['rib_lo'])
        f.row(8, K['case_hi']); f.row(11, K['dark'])
    s.f('leg', 'front').row(10, K['panel'])
    s.f('leg', 'top').fill(K['case'])
    s.f('leg', 'bottom').fill(K['dark'])
    return s


STATES = ['idle', 'connected', 'evil']


def spawn_egg():
    """The classic connected head, bezel baked in: the skin's 32x16 head unfold, which models/item/minebot_spawn_egg.json maps onto a cube."""
    skin = retro('classic', 'connected', 100).im
    egg = skin.crop((0, 0, 32, 16))
    egg.alpha_composite(skin.crop((32, 0, 64, 16)))
    return egg


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    for i, kind in enumerate(KINDS):
        for st in STATES:
            retro(kind, st, 100 + i).im.save(os.path.join(OUT, f'{kind}_{st}.png'))
    spawn_egg().save(os.path.join(TEXTURES, 'item', 'minebot_spawn_egg.png'))
