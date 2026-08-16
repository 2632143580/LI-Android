#!/usr/bin/env python3
# Generate LI launcher icons (legacy PNG densities) from a hand-drawn "li" mark.
# Brand background + white mark. No external deps beyond Pillow.
import os
from PIL import Image, ImageDraw

BRAND = (79, 124, 255, 255)   # #4F7CFF
WHITE = (255, 255, 255, 255)
BASE = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")


def draw_mark(draw, S, color):
    lw = S * 0.11
    lx0, lx1 = S * 0.31, S * 0.31 + lw
    ly0, ly1 = S * 0.28, S * 0.80
    draw.rounded_rectangle([lx0, ly0, lx1, ly1], radius=lw / 2, fill=color)
    r = S * 0.075
    cx, cy = S * 0.66, S * 0.28 + r
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)
    iw = S * 0.11
    ix0, ix1 = S * 0.66 - iw / 2, S * 0.66 + iw / 2
    iy0, iy1 = S * 0.44, S * 0.80
    draw.rounded_rectangle([ix0, iy0, ix1, iy1], radius=iw / 2, fill=color)


def make(size, rel):
    img = Image.new("RGBA", (size, size), BRAND)
    draw_mark(ImageDraw.Draw(img), size, WHITE)
    out = os.path.abspath(os.path.join(BASE, rel))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out)
    print("wrote", out)


for d, s in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
             ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
    make(s, f"{d}/ic_launcher.png")
    make(s, f"{d}/ic_launcher_round.png")
print("done")
