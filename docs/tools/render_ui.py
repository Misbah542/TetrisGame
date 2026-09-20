#!/usr/bin/env python3
"""
Render the Tetris UI to PNG, straight from the Compose sources.

Every measurement, colour and shape below is transcribed from
app/src/main/java/com/misbahminiproject/tetris/ui/theme/*.kt so the images
match what the app actually draws. The piece/rotation logic is a direct port
of logic/TetrisSpirits.kt.

Usage: python3 docs/tools/render_ui.py docs/screenshots
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

# ----------------------------------------------------------------------------
# Render setup: work in dp, rasterise at SS * S and downsample for antialiasing.
# ----------------------------------------------------------------------------
S = 3           # final px per dp (xxhdpi phone)
SS = 2          # supersampling factor
R = S * SS      # render px per dp
DENSITY = 3.0   # px-per-dp the app would run at; raw px sizes in code use this

def u(v):       # dp -> render px
    return v * R


class Layer:
    """PIL draws translucent ink by overwriting, so every translucent group is
    drawn on its own transparent layer and alpha-composited in paint order."""

    def __init__(self, img):
        self.img = img
        self.layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
        self.draw = ImageDraw.Draw(self.layer, "RGBA")

    def __enter__(self):
        return self.draw

    def __exit__(self, *exc):
        self.img.alpha_composite(self.layer)
        return False

# ---- Colors (ui/theme/Color.kt) --------------------------------------------
BRICK_SPIRIT = (0, 0, 0, 0xDD)
BRICK_MATRIX = (0, 0, 0, 0x1F)
SCREEN_BACKGROUND = (0x9E, 0xAD, 0x86, 0xFF)
BODY_COLOR = (0xFF, 0xFF, 0xFF, 0xFF)
PURPLE200 = (0xBB, 0x86, 0xFC, 0xFF)
PURPLE500 = (0x62, 0x00, 0xEE, 0xFF)
BLACK = (0, 0, 0, 0xFF)
ON_BACKGROUND = (0x1C, 0x1B, 0x1F, 0xFF)   # material3 light onBackground

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
LED_TTF = os.path.join(REPO, "app/src/main/res/font/unidream_led.ttf")
SANS = "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"
SANS_BOLD = "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"
CURSIVE = "/usr/share/fonts/truetype/freefont/FreeSerifBoldItalic.ttf"
SYMBOLS = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

def font(path, sp):
    return ImageFont.truetype(path, int(round(u(sp))))

# ----------------------------------------------------------------------------
# logic/TetrisSpirits.kt port
# ----------------------------------------------------------------------------
SPIRIT_TYPE = [
    [(1, -1), (1, 0), (0, 0), (0, 1)],   # Z
    [(0, -1), (0, 0), (1, 0), (1, 1)],   # S
    [(0, -1), (0, 0), (0, 1), (0, 2)],   # I
    [(0, 1), (0, 0), (0, -1), (1, 0)],   # T
    [(1, 0), (0, 0), (1, -1), (0, -1)],  # O
    [(0, -1), (1, -1), (1, 0), (1, 1)],  # L
    [(1, -1), (0, -1), (0, 0), (0, 1)],  # J
]
SPIRIT_NAMES = ["Z", "S", "I", "T", "O", "L", "J"]


class Spirit:
    def __init__(self, shape, offset=(0, 0)):
        self.shape = list(shape)
        self.offset = offset

    @property
    def location(self):
        ox, oy = self.offset
        return [(x + ox, y + oy) for x, y in self.shape]

    def move_by(self, step):
        return Spirit(self.shape, (self.offset[0] + step[0], self.offset[1] + step[1]))

    def rotate(self):
        return Spirit([(y, -x) for x, y in self.shape], self.offset)

    def adjust_offset(self, matrix, adjust_y=True):
        loc = self.location
        if adjust_y:
            min_y = min(y for _, y in loc)
            max_y = max(y for _, y in loc)
            y_off = (abs(min_y) if min_y < 0 else 0) + \
                    ((matrix[1] - max_y - 1) if max_y > matrix[1] - 1 else 0)
        else:
            y_off = 0
        min_x = min(x for x, _ in loc)
        max_x = max(x for x, _ in loc)
        x_off = (abs(min_x) if min_x < 0 else 0) + \
                ((matrix[0] - max_x - 1) if max_x > matrix[0] - 1 else 0)
        return self.move_by((x_off, y_off))


EMPTY = Spirit([])
MATRIX = (12, 24)
NEXT_MATRIX = (4, 2)

# ----------------------------------------------------------------------------
# Drawing primitives
# ----------------------------------------------------------------------------

def rect(d, x, y, w, h, color):
    d.rectangle([u(x), u(y), u(x + w) - 1, u(y + h) - 1], fill=color)


def draw_brick(d, ox, oy, cell, brick_size, color):
    """ui/theme/TetrisScreen.kt :: DrawScope.drawBrick"""
    bx = ox + cell[0] * brick_size
    by = oy + cell[1] * brick_size
    outer = brick_size * 0.8
    outer_off = (brick_size - outer) / 2
    stroke = max(1, int(round(u(outer / 10))))
    half = outer / 20.0
    d.rectangle(
        [u(bx + outer_off - half), u(by + outer_off - half),
         u(bx + outer_off + outer + half), u(by + outer_off + outer + half)],
        outline=color, width=stroke,
    )
    inner = brick_size * 0.5
    inner_off = (brick_size - inner) / 2
    d.rectangle(
        [u(bx + inner_off), u(by + inner_off),
         u(bx + inner_off + inner), u(by + inner_off + inner)],
        fill=color,
    )


def draw_matrix(d, ox, oy, brick_size, matrix):
    for x in range(matrix[0]):
        for y in range(matrix[1]):
            draw_brick(d, ox, oy, (x, y), brick_size, BRICK_MATRIX)


def draw_matrix_border(d, ox, oy, brick_size, matrix):
    gap = matrix[0] * brick_size * 0.05
    w = matrix[0] * brick_size + gap
    h = matrix[1] * brick_size + gap
    d.rectangle(
        [u(ox - gap / 2), u(oy - gap / 2), u(ox - gap / 2 + w), u(oy - gap / 2 + h)],
        outline=BLACK, width=max(1, int(round(u(1)))),
    )


def draw_cells(d, ox, oy, cells, brick_size, matrix):
    for c in cells:
        if 0 <= c[0] < matrix[0] and 0 <= c[1] < matrix[1]:
            draw_brick(d, ox, oy, c, brick_size, BRICK_SPIRIT)


def led_number(d, right, top, num, digits, fill_zero=False):
    """ui/theme/LedNo.kt :: LedNumber - 16sp LED glyphs in 8dp cells."""
    f = font(LED_TTF, 16)
    cell = 8.0
    for i in range(digits):
        cx = right - (digits - i) * cell
        d.text((u(cx + cell), u(top)), "8", font=f, fill=BRICK_MATRIX, anchor="ra")
    s = ("%0*d" % (digits, num)) if fill_zero else str(num)
    s = s[-digits:]
    for i, ch in enumerate(s):
        cx = right - (len(s) - i) * cell
        d.text((u(cx + cell), u(top)), ch, font=f, fill=BRICK_SPIRIT, anchor="ra")
    return cell * digits


def led_clock(d, left, top, hh, mm):
    f = font(LED_TTF, 16)
    led_number(d, left + 16, top, hh, 2, fill_zero=True)
    d.text((u(left + 16 + 5), u(top)), ":", font=f, fill=BRICK_SPIRIT, anchor="ra")
    led_number(d, left + 16 + 6 + 16, top, mm, 2, fill_zero=True)


def icon_pause(d, x, y, size, color):
    """res/drawable/ic_baseline_pause_24.xml, scaled from its 24dp viewport."""
    k = size / 24.0
    rect(d, x + 6 * k, y + 5 * k, 4 * k, 14 * k, color)
    rect(d, x + 14 * k, y + 5 * k, 4 * k, 14 * k, color)


def icon_music_off(d, x, y, size, color):
    """res/drawable/ic_baseline_music_off_24.xml, simplified to its silhouette."""
    k = size / 24.0
    d.ellipse([u(x + 6 * k), u(y + 13 * k), u(x + 14 * k), u(y + 21 * k)], fill=color)
    rect(d, x + 12 * k, y + 7 * k, 2 * k, 10 * k, color)
    rect(d, x + 12 * k, y + 3 * k, 6 * k, 4 * k, color)
    d.line([u(x + 3 * k), u(y + 3 * k), u(x + 21 * k), u(y + 21 * k)],
           fill=color, width=max(1, int(round(u(2 * k)))))


def gradient_shape(img, x, y, w, h, radius, start_y_px=0.0, end_y_px=80.0):
    """GameButton: vertical Purple200 -> Purple500 gradient over [0,80] raw px."""
    px_w, px_h = int(round(u(w))), int(round(u(h)))
    grad = Image.new("RGBA", (px_w, px_h))
    gd = ImageDraw.Draw(grad)
    span = (end_y_px - start_y_px) * (R / DENSITY)  # raw px -> render px
    for row in range(px_h):
        t = min(1.0, max(0.0, (row - start_y_px * (R / DENSITY)) / span)) if span > 0 else 1.0
        col = tuple(int(round(PURPLE200[i] + (PURPLE500[i] - PURPLE200[i]) * t)) for i in range(3))
        gd.line([(0, row), (px_w, row)], fill=col + (255,))
    mask = Image.new("L", (px_w, px_h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, px_w - 1, px_h - 1],
                                           radius=int(round(u(radius))), fill=255)
    grad.putalpha(mask)
    img.alpha_composite(grad, (int(round(u(x))), int(round(u(y)))))


def shadow_shape(layer, x, y, w, h, radius):
    d = ImageDraw.Draw(layer, "RGBA")
    d.rounded_rectangle([u(x), u(y + 1.5), u(x + w), u(y + h + 1.5)],
                        radius=int(round(u(radius))), fill=(0, 0, 0, 90))


# ----------------------------------------------------------------------------
# GameScreen (ui/theme/TetrisScreen.kt)
# ----------------------------------------------------------------------------

def game_screen(img, x, y, w, h, st):
    # Box(...).background(Black).padding(1.dp).background(ScreenBackground).padding(10.dp)
    with Layer(img) as d:
        rect(d, x, y, w, h, BLACK)
        rect(d, x + 1, y + 1, w - 2, h - 2, SCREEN_BACKGROUND)

    cx, cy = x + 11, y + 11
    cw, ch = w - 22, h - 22
    brick = min(cw / MATRIX[0], ch / MATRIX[1])

    with Layer(img) as d:
        draw_matrix(d, cx, cy, brick, MATRIX)
        draw_matrix_border(d, cx, cy, brick, MATRIX)
    with Layer(img) as d:
        draw_cells(d, cx, cy, st["bricks"], brick, MATRIX)
        draw_cells(d, cx, cy, st["spirit"].location, brick, MATRIX)

    # drawText: centred on the matrix, Paint.Align.CENTER (y is the baseline)
    if st["status"] in ("Onboard", "GameOver"):
        text, size_px = ("TETRIS", 80.0) if st["status"] == "Onboard" else ("GAME OVER", 60.0)
        size_dp = size_px / DENSITY
        alpha = int(255 * st.get("text_alpha", 0.7))
        with Layer(img) as d:
            d.text((u(cx + brick * MATRIX[0] / 2), u(cy + brick * MATRIX[1] / 2)), text,
                   font=font(SANS, size_dp), fill=(0, 0, 0, alpha), anchor="ms",
                   stroke_width=max(1, int(round(u(size_dp / 12) / 2))),
                   stroke_fill=(0, 0, 0, alpha))

    # ---- GameScoreboard: Row { Spacer(0.65f); Column(0.35f) } ----
    col_x = cx + cw * 0.65
    col_right = col_x + cw * 0.35
    label = font(SANS, 12)
    lab_h, led_h, margin = 15.0, 19.0, 12.0

    with Layer(img) as d:
        ly = cy
        for name, value, digits in (("Score", st["score"], 6),
                                    ("Lines", st["line"], 6),
                                    ("Level", st["level"], 1)):
            d.text((u(col_x), u(ly)), name, font=label, fill=ON_BACKGROUND, anchor="la")
            ly += lab_h
            led_number(d, col_right, ly, value, digits)
            ly += led_h + margin

        d.text((u(col_x), u(ly)), "Next", font=label, fill=ON_BACKGROUND, anchor="la")
        ly += lab_h + 10
        nxt = st["next"]
        if nxt.shape:
            nb = 35.0 / DENSITY  # brickSize = 35f raw px
            draw_matrix(d, col_x + 10, ly, nb, NEXT_MATRIX)
            draw_cells(d, col_x + 10, ly, nxt.adjust_offset(NEXT_MATRIX).location,
                       nb, NEXT_MATRIX)

        # bottom row: mute / pause indicators + LED clock
        by = cy + ch - 16
        icon_music_off(d, col_x, by, 15, BRICK_SPIRIT if st["mute"] else BRICK_MATRIX)
        icon_pause(d, col_x + 16, by, 16, BRICK_SPIRIT if st["paused"] else BRICK_MATRIX)
        led_clock(d, col_right - 38, by, st["clock"][0], st["clock"][1])


# ----------------------------------------------------------------------------
# GameBody (ui/theme/TetrisGameBody.kt)
# ----------------------------------------------------------------------------
BODY_W, BODY_H = 400, 740
DIRECTION_BTN, ROTATE_BTN, SETTING_BTN = 60.0, 90.0, 15.0


def render(st):
    img = Image.new("RGBA", (int(u(BODY_W)), int(u(BODY_H))), BODY_COLOR)
    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))

    # Column(padding(top = 20.dp)); the screen Box measures 360 x 400
    bx, by = (BODY_W - 360) / 2.0, 20.0

    with Layer(img) as d:
        # size(330,400).padding(top=20).background(Black 80%).padding(5).background(BodyColor)
        rect(d, bx + 15, by + 20, 330, 380, (0, 0, 0, int(255 * 0.8)))
        rect(d, bx + 20, by + 25, 320, 370, BODY_COLOR)
        # brand label box 120x45, TopCenter
        rect(d, bx + 120, by, 120, 45, BODY_COLOR)
        d.text((u(bx + 180), u(by + 22)), "TETRIS", font=font(CURSIVE, 20),
               fill=ON_BACKGROUND, anchor="mm")

    # screen Box: size(360,380) centred, padding(start/end=50, top=50, bottom=30)
    sx, sy, sw, sh = bx + 50, by + 10 + 50, 260.0, 300.0
    with Layer(img) as d:
        # drawScreenBorder: dark top/left bevel, light bottom/right bevel (6dp frame)
        d.polygon([(u(sx), u(sy)), (u(sx + sw), u(sy)),
                   (u(sx + sw / 2), u(sy + sw / 2)), (u(sx + sw / 2), u(sy + sh - sw / 2)),
                   (u(sx), u(sy + sh))], fill=(0, 0, 0, 128))
        d.polygon([(u(sx + sw), u(sy + sh)), (u(sx), u(sy + sh)),
                   (u(sx + sw / 2), u(sy + sh - sw / 2)), (u(sx + sw / 2), u(sy + sw / 2)),
                   (u(sx + sw), u(sy))], fill=(255, 255, 255, 128))
    game_screen(img, sx + 6, sy + 6, sw - 12, sh - 12, st)

    # ---- setting row: labels + three capsule buttons ----
    top = by + 400 + 20
    lab = font(SANS, 12)
    avail = BODY_W - 30 - 40
    cell_w = avail / 3.0
    with Layer(img) as d:
        for i, text in enumerate(("SOUNDS", "PAUSE/RESUME", "START/RESET")):
            d.text((u(30 + cell_w * (i + 0.5)), u(top)), text, font=lab,
                   fill=(0, 0, 0, int(255 * 0.9)), anchor="ma")
    btn_top = top + 15 + 5
    for i in range(3):
        shadow_shape(shadow, 30 + cell_w * i + 20, btn_top, cell_w - 40,
                     SETTING_BTN, SETTING_BTN / 2)

    # ---- direction pad + rotate ----
    row_top = btn_top + SETTING_BTN + 30
    row_h = 160.0
    pad_w = (BODY_W - 80) / 2.0
    pad_x = 40.0
    up = (pad_x + (pad_w - DIRECTION_BTN) / 2, row_top)
    down = (pad_x + (pad_w - DIRECTION_BTN) / 2, row_top + row_h - DIRECTION_BTN)
    left = (pad_x, row_top + (row_h - DIRECTION_BTN) / 2)
    right = (pad_x + pad_w - DIRECTION_BTN, row_top + (row_h - DIRECTION_BTN) / 2)
    rot = (pad_x + 2 * pad_w - ROTATE_BTN, row_top + (row_h - ROTATE_BTN) / 2)
    for p in (up, down, left, right):
        shadow_shape(shadow, p[0], p[1], DIRECTION_BTN, DIRECTION_BTN, DIRECTION_BTN / 2)
    shadow_shape(shadow, rot[0], rot[1], ROTATE_BTN, ROTATE_BTN, ROTATE_BTN / 2)

    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(u(2.5))))

    for i in range(3):
        gradient_shape(img, 30 + cell_w * i + 20, btn_top, cell_w - 40,
                       SETTING_BTN, SETTING_BTN / 2)
    for p in (up, down, left, right):
        gradient_shape(img, p[0], p[1], DIRECTION_BTN, DIRECTION_BTN, DIRECTION_BTN / 2)
    gradient_shape(img, rot[0], rot[1], ROTATE_BTN, ROTATE_BTN, ROTATE_BTN / 2)

    with Layer(img) as d:
        arrows = font(SYMBOLS, 18)
        white = (255, 255, 255, int(255 * 0.9))
        for p, glyph in ((up, "\u25b2"), (down, "\u25bc"),
                         (left, "\u25c0"), (right, "\u25b6")):
            d.text((u(p[0] + DIRECTION_BTN / 2), u(p[1] + DIRECTION_BTN / 2)), glyph,
                   font=arrows, fill=white, anchor="mm")
        d.text((u(rot[0] + ROTATE_BTN / 2), u(rot[1] + ROTATE_BTN / 2)), "ROTATE",
               font=font(SANS, 18), fill=white, anchor="mm")

    return img.convert("RGB").resize((BODY_W * S, BODY_H * S), Image.LANCZOS)


# ----------------------------------------------------------------------------
# States
# ----------------------------------------------------------------------------
STACK = [
    "....##......",
    "...####.....",
    "..#####....#",
    ".########.##",
    "##########.#",
    "###.########",
    "##########.#",
    "#########.##",
]


def stack_cells(rows, bottom=23):
    cells = []
    for i, row in enumerate(rows):
        y = bottom - len(rows) + 1 + i
        for x, ch in enumerate(row):
            if ch == "#":
                cells.append((x, y))
    return cells


def state(status, bricks=(), spirit=EMPTY, nxt=EMPTY, score=0, line=0,
          mute=False, paused=False, clock=(21, 7), text_alpha=0.7):
    return dict(status=status, bricks=list(bricks), spirit=spirit, next=nxt,
                score=score, line=line, level=min(10, 1 + line // 20),
                mute=mute, paused=paused, clock=clock, text_alpha=text_alpha)


def pieces_strip():
    """ui/theme/TetrisScreen.kt :: PreviewSpiritType, plus name labels."""
    w, h, labels_h = 320, 56, 18
    img = Image.new("RGBA", (int(u(w)), int(u(h + labels_h))), BODY_COLOR)
    matrix = (2, 4)
    cell_w = w / 7.0
    f = font(SANS, 11)
    with Layer(img) as d:
        rect(d, 0, 0, w, h, SCREEN_BACKGROUND)
    with Layer(img) as d:
        for i, shape in enumerate(SPIRIT_TYPE):
            x, y = cell_w * i + 5, 5.0
            b = min((cell_w - 10) / matrix[0], (h - 10) / matrix[1])
            draw_cells(d, x, y, Spirit(shape).adjust_offset(matrix).location, b, matrix)
            d.text((u(cell_w * (i + 0.5)), u(h + 3)), SPIRIT_NAMES[i], font=f,
                   fill=ON_BACKGROUND, anchor="ma")
    return img.convert("RGB").resize((w * S, (h + labels_h) * S), Image.LANCZOS)


def save(img, path):
    # flat-colour UI: an adaptive palette halves the file with no visible banding
    img.convert("P", palette=Image.ADAPTIVE, colors=255).save(path, optimize=True)
    print("wrote", path)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)

    bricks = stack_cells(STACK)
    falling = Spirit(SPIRIT_TYPE[1], (5, 8))       # S piece, mid-fall
    nxt = Spirit(SPIRIT_TYPE[5]).rotate()          # scoreboard shows next.rotate()

    shots = {
        "01-onboard.png": state("Onboard"),
        "02-gameplay.png": state("Running", bricks, falling, nxt, score=3860, line=22),
        "03-paused.png": state("Paused", bricks, falling, nxt, score=3860, line=22,
                               paused=True, mute=True),
        "04-game-over.png": state("GameOver", score=12860, line=46),
    }
    for name, st in shots.items():
        save(render(st), os.path.join(out, name))

    save(pieces_strip(), os.path.join(out, "05-pieces.png"))


if __name__ == "__main__":
    main()
