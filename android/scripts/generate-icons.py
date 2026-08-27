#!/usr/bin/env python3
"""Generate the AnyDown launcher icons.

Run only when the mark changes:

    pip install Pillow
    python android/scripts/generate-icons.py

The geometry deliberately matches the `Mark` composable in
app/src/main/java/com/anydown/downloader/ui/Design.kt, so the icon on the
launcher and the mark inside the app are the same drawing.

Three sets are produced:
  * ic_launcher_foreground — white mark on transparency for the adaptive icon
    (API 26+). Art is confined to the centre 66/108 of the canvas, the only
    region a launcher mask is guaranteed not to crop.
  * ic_launcher            — legacy square icon, API 24-25.
  * ic_launcher_round      — legacy round icon, same.

The wordmark from the source artwork is intentionally left out. At 48dp it
would be an illegible smudge, and Android already prints the app name directly
beneath the icon, so it would only be repeating itself.
"""

from pathlib import Path

from PIL import Image, ImageDraw

BACKDROP = (18, 18, 20, 255)  # @color/icon_background
MARK = (255, 255, 255, 255)
SUPERSAMPLE = 4

LEGACY_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
# Adaptive foregrounds are authored on a 108dp canvas.
FOREGROUND_SIZES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

RES_DIR = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"


def rounded(draw, x0, y0, x1, y1, colour):
    """Rounded bar, radius = half the short side."""
    radius = min(abs(x1 - x0), abs(y1 - y0)) / 2
    draw.rounded_rectangle([x0, y0, x1, y1], radius=radius, fill=colour)


def draw_mark(draw, cx, cy, art, colour=MARK, motion_lines=True):
    """Arrow descending into an open tray, with motion lines to its left.

    `art` is the height of the mark; `cx`/`cy` its centre.
    """
    # The motion lines extend well to the left of the arrow, so the arrow has to
    # sit right of centre for the *composition* to be centred. The whole mark
    # spans from ax - 0.905*art to ax + 0.40*art, which is balanced when the
    # arrow is offset by a quarter of the art height.
    ax = cx + (art * 0.25 if motion_lines else 0)
    top = cy - art / 2

    stem_w = art * 0.17
    stem_top = top + art * 0.10
    head_top = top + art * 0.44
    head_half = art * 0.325
    tip_y = top + art * 0.67

    rounded(draw, ax - stem_w / 2, stem_top, ax + stem_w / 2, head_top + art * 0.02, colour)
    draw.polygon(
        [(ax - head_half, head_top), (ax + head_half, head_top), (ax, tip_y)],
        fill=colour,
    )

    # Tray: two arms and a base, assembled rather than stroked, because PIL has
    # no round-capped path stroking.
    tray_half = art * 0.40
    tray_top = top + art * 0.58
    tray_bottom = top + art * 0.92
    stroke = art * 0.125
    rounded(draw, ax - tray_half, tray_top, ax - tray_half + stroke, tray_bottom, colour)
    rounded(draw, ax + tray_half - stroke, tray_top, ax + tray_half, tray_bottom, colour)
    rounded(draw, ax - tray_half, tray_bottom - stroke, ax + tray_half, tray_bottom, colour)

    if motion_lines:
        line_h = art * 0.095
        right = ax - head_half - art * 0.08
        for y_frac, len_frac, alpha in (
            (0.24, 0.50, 1.0),
            (0.39, 0.35, 0.70),
            (0.53, 0.14, 0.42),
        ):
            faded = (colour[0], colour[1], colour[2], int(255 * alpha))
            length = art * len_frac
            y = top + art * y_frac
            rounded(draw, right - length, y, right, y + line_h, faded)


def build(size: int, shape: str) -> Image.Image:
    canvas = size * SUPERSAMPLE
    image = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    if shape == "foreground":
        # Transparent; the adaptive icon supplies the backdrop colour.
        art = canvas * (66 / 108) * 0.88
    elif shape == "round":
        draw.ellipse([0, 0, canvas - 1, canvas - 1], fill=BACKDROP)
        art = canvas * 0.50
    else:
        draw.rounded_rectangle(
            [0, 0, canvas - 1, canvas - 1], radius=canvas * 0.235, fill=BACKDROP
        )
        art = canvas * 0.56

    draw_mark(draw, canvas / 2, canvas / 2, art)
    return image.resize((size, size), Image.LANCZOS)


def main() -> None:
    written = 0
    for folder, size in FOREGROUND_SIZES.items():
        target = RES_DIR / folder
        target.mkdir(parents=True, exist_ok=True)
        build(size, "foreground").save(
            target / "ic_launcher_foreground.png", "PNG", optimize=True
        )
        written += 1

    for folder, size in LEGACY_SIZES.items():
        target = RES_DIR / folder
        target.mkdir(parents=True, exist_ok=True)
        build(size, "square").save(target / "ic_launcher.png", "PNG", optimize=True)
        build(size, "round").save(target / "ic_launcher_round.png", "PNG", optimize=True)
        written += 2

    print(f"wrote {written} icon files under {RES_DIR}")


if __name__ == "__main__":
    main()
