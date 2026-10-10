#!/usr/bin/env python3
"""Regenerate committed launcher assets: python3 -m pip install Pillow first."""

import json
from math import hypot
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
MARK = Image.open(ROOT / "assets/branding/hasht-mark.png").convert("RGBA")
MARK = MARK.crop(MARK.getbbox())
LIGHT = "#F5F5F7"
DARK = "#151517"


def foreground(size, width):
    logo = MARK.resize((round(width), round(width * MARK.height / MARK.width)),
                       Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size))
    canvas.alpha_composite(logo, ((size - logo.width) // 2, (size - logo.height) // 2))
    return canvas


def composite(fg, color):
    bg = Image.new("RGBA", fg.size, color)
    bg.alpha_composite(fg)
    return bg.convert("RGB")


def silhouette(fg):
    result = Image.new("RGBA", fg.size, "white")
    result.putalpha(fg.getchannel("A"))
    return result


# Keep the whole mark inside Android's 66 dp safe circle, including sharp masks
# and launcher motion. The visible viewport is 72 dp; the layer is 108 dp.
alpha = MARK.getchannel("A")
radius = max(hypot(x - (MARK.width - 1) / 2, y - (MARK.height - 1) / 2)
             for y in range(MARK.height) for x in range(MARK.width)
             if alpha.getpixel((x, y)))
android_width = MARK.width * 32.5 / radius
res = ROOT / "android/app/src/main/res"
for density, scale in {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2,
                       "xxhdpi": 3, "xxxhdpi": 4}.items():
    target = res / f"mipmap-{density}"
    fg = foreground(round(108 * scale), android_width * scale)
    fg.save(target / "ic_launcher_foreground.png")
    silhouette(fg).save(target / "ic_launcher_monochrome.png")
    for name, color in [("", LIGHT), ("-night", DARK)]:
        legacy_target = res / f"mipmap{ name }-{density}"
        legacy_target.mkdir(exist_ok=True)
        size = round(48 * scale)
        # Match the adaptive icon's visible 72 dp viewport on pre-Android 8.
        legacy = composite(foreground(size, android_width / 72 * size), color)
        for filename, rounded in [("ic_launcher.png", False),
                                  ("ic_launcher_round.png", True)]:
            mask = Image.new("L", (size * 4, size * 4))
            draw = ImageDraw.Draw(mask)
            if rounded:
                draw.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
            else:
                draw.rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1),
                                       radius=size * 0.22 * 4, fill=255)
            legacy_rgba = legacy.convert("RGBA")
            legacy_rgba.putalpha(mask.resize((size, size), Image.Resampling.LANCZOS))
            legacy_rgba.save(legacy_target / filename)

catalog = ROOT / "ios/App/App/Assets.xcassets/AppIcon.appiconset"
catalog.mkdir(exist_ok=True)
fg = foreground(1024, 800)
composite(fg, LIGHT).save(catalog / "AppIcon.png")
# iOS supplies the dark background behind the transparent mark.
fg.save(catalog / "AppIcon-dark.png")
composite(silhouette(fg), "black").save(catalog / "AppIcon-tinted.png")
images = []
for appearance in [None, "dark", "tinted"]:
    entry = {"filename": f"AppIcon{'-' + appearance if appearance else ''}.png",
             "idiom": "universal", "platform": "ios", "size": "1024x1024"}
    if appearance:
        entry["appearances"] = [{"appearance": "luminosity", "value": appearance}]
    images.append(entry)
(catalog / "Contents.json").write_text(json.dumps(
    {"images": images, "info": {"author": "xcode", "version": 1}}, indent=2) + "\n")

print("Generated iOS appearances and Android adaptive, themed, and legacy icons.")
