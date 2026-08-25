#!/usr/bin/env python3
"""Generate the launcher icons and Leanback banners for both APKs.

One geometry description drives two renderers: Android vector drawables for the
adaptive icon, and PIL for the legacy mipmap bitmaps and the banner. Re-run after
changing a colour or a proportion; nothing here is edited by hand.
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

# The mark is drawn in a 48x48 box and placed inside the 108x108 adaptive icon
# foreground, whose guaranteed-visible area is only the middle 72x72.
MARK = 48
FOREGROUND = 108
SAFE = 66  # the mark occupies this much of the 108 box
LEGACY_DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

APPS = {
    "mirakc": {
        "module": "mirakc",
        "title": "mirakc",
        "background": "#263238",
        "body": "#455A64",
        "screen": "#4DD0E1",
        "detail": "#263238",
        "screen_style": "waves",
    },
    "epgstation-server": {
        "module": "epgstation-server",
        "title": "EPGStation Server",
        "background": "#263238",
        "body": "#455A64",
        "screen": "#FFB74D",
        "detail": "#263238",
        "screen_style": "grid",
    },
}


def rounded_rect_path(x: float, y: float, w: float, h: float, r: float) -> str:
    return (
        f"M{x + r},{y} H{x + w - r} A{r},{r} 0 0 1 {x + w},{y + r} "
        f"V{y + h - r} A{r},{r} 0 0 1 {x + w - r},{y + h} "
        f"H{x + r} A{r},{r} 0 0 1 {x},{y + h - r} "
        f"V{y + r} A{r},{r} 0 0 1 {x + r},{y} Z"
    )


def circle_path(cx: float, cy: float, r: float) -> str:
    return (
        f"M{cx - r},{cy} A{r},{r} 0 1 0 {cx + r},{cy} "
        f"A{r},{r} 0 1 0 {cx - r},{cy} Z"
    )


def screen_shapes(style: str) -> list[tuple[float, float, float, float, float]]:
    """Blocks drawn on the screen, as (x, y, w, h, radius) inside the 48 box."""
    if style == "grid":
        blocks = []
        for row in range(3):
            y = 20.0 + row * 5.0
            for col, (x, w) in enumerate(((10.0, 8.0), (19.5, 5.0), (25.5, 4.5))):
                if (row + col) % 3 == 2:
                    continue
                blocks.append((x, y, w, 3.0, 0.8))
        return blocks
    # "waves": broadcast arcs rendered as stacked bars of decreasing width
    return [
        (13.5, 20.0, 17.0, 3.0, 1.2),
        (15.5, 25.0, 13.0, 3.0, 1.2),
        (17.5, 30.0, 9.0, 3.0, 1.2),
    ]


def mark_paths(app: dict) -> list[tuple[str, str]]:
    """(fillColor, pathData) pairs for the 48x48 mark."""
    body, screen, detail = app["body"], app["screen"], app["detail"]
    paths: list[tuple[str, str]] = []

    # Antenna: two tapered arms meeting just above the cabinet.
    paths.append((body, "M23.1,13 L13.2,4.4 L15.4,2.0 L24.6,11.0 Z"))
    paths.append((body, "M24.9,13 L34.8,4.4 L32.6,2.0 L23.4,11.0 Z"))

    # Cabinet.
    paths.append((body, rounded_rect_path(3.5, 12.5, 41.0, 33.0, 4.5)))
    # Screen.
    paths.append((screen, rounded_rect_path(7.5, 16.5, 23.0, 25.0, 2.5)))
    # Content on the screen.
    for x, y, w, h, r in screen_shapes(app["screen_style"]):
        paths.append((detail, rounded_rect_path(x, y, w, h, r)))
    # Control panel: two knobs and a speaker grille.
    paths.append((screen, circle_path(37.5, 21.5, 2.6)))
    paths.append((screen, circle_path(37.5, 28.5, 2.6)))
    for i in range(3):
        paths.append((screen, rounded_rect_path(34.0, 33.5 + i * 2.6, 7.0, 1.4, 0.7)))
    return paths


def vector_drawable(app: dict) -> str:
    scale = SAFE / MARK
    offset = (FOREGROUND - SAFE) / 2
    lines = [
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{FOREGROUND}dp"',
        f'    android:height="{FOREGROUND}dp"',
        f'    android:viewportWidth="{FOREGROUND}"',
        f'    android:viewportHeight="{FOREGROUND}">',
        f'    <group android:scaleX="{scale:.6f}" android:scaleY="{scale:.6f}"',
        f'        android:translateX="{offset:.4f}" android:translateY="{offset:.4f}">',
    ]
    for color, data in mark_paths(app):
        lines.append(f'        <path android:fillColor="{color}" android:pathData="{data}" />')
    lines.append("    </group>")
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def draw_mark(draw: ImageDraw.ImageDraw, app: dict, scale: float, ox: float, oy: float) -> None:
    body, screen, detail = app["body"], app["screen"], app["detail"]

    def box(x, y, w, h, r, fill):
        draw.rounded_rectangle(
            [ox + x * scale, oy + y * scale, ox + (x + w) * scale, oy + (y + h) * scale],
            radius=max(1.0, r * scale),
            fill=fill,
        )

    for pts in (
        [(23.1, 13), (13.2, 4.4), (15.4, 2.0), (24.6, 11.0)],
        [(24.9, 13), (34.8, 4.4), (32.6, 2.0), (23.4, 11.0)],
    ):
        draw.polygon([(ox + x * scale, oy + y * scale) for x, y in pts], fill=body)

    box(3.5, 12.5, 41.0, 33.0, 4.5, body)
    box(7.5, 16.5, 23.0, 25.0, 2.5, screen)
    for x, y, w, h, r in screen_shapes(app["screen_style"]):
        box(x, y, w, h, r, detail)
    for cy in (21.5, 28.5):
        draw.ellipse(
            [
                ox + (37.5 - 2.6) * scale,
                oy + (cy - 2.6) * scale,
                ox + (37.5 + 2.6) * scale,
                oy + (cy + 2.6) * scale,
            ],
            fill=screen,
        )
    for i in range(3):
        box(34.0, 33.5 + i * 2.6, 7.0, 1.4, 0.7, screen)


def rounded_mask(size: int, radius: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return mask


def circle_mask(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    return mask


def legacy_icon(app: dict, size: int, round_icon: bool) -> Image.Image:
    image = Image.new("RGBA", (size, size), app["background"])
    scale = (size * (SAFE / FOREGROUND)) / MARK
    inset = (size - MARK * scale) / 2
    draw_mark(ImageDraw.Draw(image), app, scale, inset, inset)
    mask = circle_mask(size) if round_icon else rounded_mask(size, max(2, size // 6))
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(image, (0, 0), mask)
    return out


def banner(app: dict) -> Image.Image:
    width, height = 320, 180
    image = Image.new("RGBA", (width, height), app["background"])
    draw = ImageDraw.Draw(image)
    mark_px = 104
    scale = mark_px / MARK
    draw_mark(draw, app, scale, 26, (height - mark_px) / 2)

    title = app["title"]
    size = 34 if len(title) <= 8 else 22
    font = ImageFont.truetype(FONT_BOLD, size)
    left = 26 + mark_px + 20
    while font.getbbox(title)[2] > width - left - 18 and size > 12:
        size -= 1
        font = ImageFont.truetype(FONT_BOLD, size)
    bbox = font.getbbox(title)
    draw.text((left, (height - (bbox[3] - bbox[1])) / 2 - bbox[1]), title, font=font, fill="#ECEFF1")
    accent_y = (height - (bbox[3] - bbox[1])) / 2 - bbox[1] + bbox[3] + 8
    draw.rounded_rectangle(
        [left, accent_y, left + min(120, bbox[2]), accent_y + 5], radius=2.5, fill=app["screen"]
    )
    return image


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    print(f"wrote {path.relative_to(ROOT)}")


def save(path: Path, image: Image.Image) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)
    print(f"wrote {path.relative_to(ROOT)}")


def adaptive_icon_xml(round_icon: bool) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        "</adaptive-icon>\n"
    )


def main() -> None:
    for name, app in APPS.items():
        res = ROOT / app["module"] / "src" / "main" / "res"
        write(res / "drawable" / "ic_launcher_foreground.xml", vector_drawable(app))
        write(
            res / "values" / "ic_launcher_background.xml",
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<resources>\n"
            f'    <color name="ic_launcher_background">{app["background"]}</color>\n'
            "</resources>\n",
        )
        for file_name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            write(res / "mipmap-anydpi-v26" / file_name, adaptive_icon_xml("round" in file_name))
        for density, size in LEGACY_DENSITIES.items():
            save(res / f"mipmap-{density}" / "ic_launcher.png", legacy_icon(app, size, False))
            save(res / f"mipmap-{density}" / "ic_launcher_round.png", legacy_icon(app, size, True))
        save(res / "drawable-xhdpi" / "banner.png", banner(app))
        preview = ROOT / ".work" / "icon-preview"
        preview.mkdir(parents=True, exist_ok=True)
        save(preview / f"{name}-icon.png", legacy_icon(app, 192, False))
        save(preview / f"{name}-round.png", legacy_icon(app, 192, True))
        save(preview / f"{name}-banner.png", banner(app))


if __name__ == "__main__":
    main()
