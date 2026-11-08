#!/usr/bin/env python3
"""Genera el icono de la app (ventana de terminal con un prompt) en todas las densidades Android."""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
S = 1024  # se dibuja grande y se reduce: bordes suaves

BG = (15, 18, 22, 255)        # cuerpo
BAR = (28, 33, 41, 255)       # barra de título
GREEN = (61, 220, 132, 255)   # prompt
DOTS = [(255, 95, 87), (254, 188, 46), (40, 200, 64)]


def draw_icon():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=220, fill=255)

    body = Image.new("RGBA", (S, S), BG)
    d = ImageDraw.Draw(body)
    d.rectangle([0, 0, S, 230], fill=BAR)
    for i, col in enumerate(DOTS):
        cx = 150 + i * 105
        cy = 115
        r = 34
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)

    # prompt ">" con puntas redondeadas y guion bajo
    w = 58
    pts = [(250, 370), (430, 520), (250, 670)]
    d.line(pts, fill=GREEN, width=w, joint="curve")
    for x, y in (pts[0], pts[2]):
        d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=GREEN)
    d.rounded_rectangle([510, 700, 780, 700 + w], radius=w // 2, fill=GREEN)

    img.paste(body, (0, 0), mask)
    return img


def main():
    master = draw_icon()
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for density, px in sizes.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        master.resize((px, px), Image.LANCZOS).save(os.path.join(folder, "ic_launcher.png"))
        print("ok", density, px)
    master.resize((512, 512), Image.LANCZOS).save(os.path.join(HERE, "icon-preview.png"))


if __name__ == "__main__":
    main()
