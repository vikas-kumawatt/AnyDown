#!/usr/bin/env python3
"""Generate the Android launcher icons.

Run only when the icon design changes:

    pip install Pillow
    python android/scripts/generate-icons.py

Produces three sets:
  * ic_launcher_foreground  — the arrow on transparency, for the adaptive icon
    (API 26+). Art stays inside the centre 66/108 of the canvas, which is the
    only region guaranteed to survive the launcher's mask.
  * ic_launcher             — legacy square icon for API 24-25.
  * ic_launcher_round       — legacy round icon for the same.
"""

from pathlib import Path

from PIL import Image, ImageDraw

INK = (7, 11, 24, 255)
ACCENT = (91, 140, 255, 255)
SUPERSAMPLE = 4

# mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi
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


def draw_arrow(draw: ImageDraw.ImageDraw, cx: float, cy: float, art: float) -> None:
    """Download arrow (stem + head) above a tray line. Matches the web favicon."""
    stem_half = art * 0.085
    draw.rounded_rectangle(
        [cx - stem_half, cy - art * 0.44, cx + stem_half, cy + art * 0.04],
        radius=stem_half,
        fill=ACCENT,
    )
    draw.polygon(
        [
            (cx - art * 0.28, cy - art * 0.04),
            (cx + art * 0.28, cy - art * 0.04),
            (cx, cy + art * 0.30),
        ],
        fill=ACCENT,
    )
    tray_half_w = art * 0.32
    tray_half_h = art * 0.05
    draw.rounded_rectangle(
        [cx - tray_half_w, cy + art * 0.40 - tray_half_h,
         cx + tray_half_w, cy + art * 0.40 + tray_half_h],
        radius=tray_half_h,
        fill=ACCENT,
    )


def build(size: int, shape: str) -> Image.Image:
    canvas = size * SUPERSAMPLE
    image = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    if shape == "foreground":
        # Transparent background; the adaptive icon supplies the colour. Art is
        # scaled to the 66/108 safe zone so masking can't clip it.
        art = canvas * (66 / 108) * 0.82
    elif shape == "round":
        draw.ellipse([0, 0, canvas - 1, canvas - 1], fill=INK)
        art = canvas * 0.52
    else:
        draw.rounded_rectangle(
            [0, 0, canvas - 1, canvas - 1], radius=canvas * 0.16, fill=INK
        )
        art = canvas * 0.58

    draw_arrow(draw, canvas / 2, canvas / 2, art)
    return image.resize((size, size), Image.LANCZOS)


def main() -> None:
    written = 0
    for folder, size in FOREGROUND_SIZES.items():
        target = RES_DIR / folder
        target.mkdir(parents=True, exist_ok=True)
        build(size, "foreground").save(target / "ic_launcher_foreground.png", "PNG", optimize=True)
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
