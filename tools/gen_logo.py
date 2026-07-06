#!/usr/bin/env python3
"""
Generate the NEROLINK mod logo (square), in the shared Neroland family style
(cf. neroland-core/tools/gen_logo.py and nerotech/tools/gen_logo.py): a deep-space
starfield, a glowing faceted central prism, and a beveled glowing wordmark.

NeroLink is the companion-bridge mod — the ecosystem's connectivity layer — so the
family faceted hexagonal prism sits inside an orbital **link ring** with connected
satellite nodes and radiates **broadcast/signal arcs**, lit by NeroLink's plasma
**cyan**. The palette stays in the family (teal / steel-blue / cyan) but leads with
cyan so it reads as the "connected" member of the set. Renders supersampled, then
downsamples.

Outputs:
  art/logo/nerolink_logo.png       (1024x1024 master)
  art/logo/nerolink_logo_400.png   (CurseForge/Modrinth-ready)
  common/src/main/resources/nerolink_logo.png  (256x256 in-game mods-list icon)
"""
import math
import os
import random
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "art/logo")
ICON = os.path.join(ROOT, "common/src/main/resources")
os.makedirs(OUT, exist_ok=True)
os.makedirs(ICON, exist_ok=True)

FINAL = 1024
SS = 2
R = FINAL * SS
rng = random.Random(17)

# Neroland family palette, cyan-led for NeroLink
NERO_ALLOY = (38, 166, 154)     # teal
STARSTEEL = (140, 178, 208)     # steel-blue
PLASMA = (96, 212, 232)         # cyan — NeroLink's lead colour
PLASMA_BRIGHT = (170, 240, 255)
VOID = (146, 116, 212)          # void-crystal purple, secondary accent
BRIGHT = (236, 252, 255)


def _font(size):
    for path in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    ):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def background():
    top = np.array([6, 11, 17], float)
    bot = np.array([13, 18, 28], float)
    yy = np.linspace(0, 1, R)[:, None, None]
    img = top[None, None, :] * (1 - yy) + bot[None, None, :] * yy
    img = np.repeat(img, R, axis=1)
    Y, X = np.mgrid[0:R, 0:R].astype(float)

    def glow(cx, cy, rad, color, strength):
        d = np.sqrt((X - cx) ** 2 + (Y - cy) ** 2)
        f = np.clip(1 - d / rad, 0, 1) ** 2 * strength
        for c in range(3):
            img[:, :, c] += color[c] * f

    glow(R * 0.26, R * 0.32, R * 0.55, (18, 84, 96), 0.46)    # cyan-teal nebula (lead)
    glow(R * 0.78, R * 0.70, R * 0.55, (52, 40, 96), 0.42)    # void-purple nebula
    glow(R * 0.5, R * 0.5, R * 0.42, (24, 44, 60), 0.28)

    d = np.sqrt((X - R / 2) ** 2 + (Y - R / 2) ** 2) / (R * 0.72)
    vig = np.clip(1 - (d ** 2) * 0.85, 0.25, 1)
    img *= vig[:, :, None]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def add_stars(base):
    layer = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for _ in range(460):
        x, y = rng.randint(0, R), rng.randint(0, R)
        s = rng.choice([1, 1, 1, 2, 2, 3]) * SS
        b = rng.randint(120, 255)
        tint = rng.choice([(b, b, b), (b, 255, 255), (190, 235, 255), (200, 200, 255)])
        d.ellipse([x, y, x + s, y + s], fill=tint + (rng.randint(120, 255),))
    base.alpha_composite(layer.filter(ImageFilter.GaussianBlur(2 * SS)))
    base.alpha_composite(layer)
    return base


def soft_glow(draw_fn, blur):
    layer = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(layer))
    return layer.filter(ImageFilter.GaussianBlur(blur))


def small_hex(d, cx, cy, r, fill, outline):
    pts = [(cx + math.cos(math.radians(60 * i - 90)) * r,
            cy + math.sin(math.radians(60 * i - 90)) * r) for i in range(6)]
    d.polygon(pts, fill=fill, outline=outline, width=max(1, SS))


def emblem(base, cx, cy, rad):
    # cyan + teal aura
    base.alpha_composite(soft_glow(
        lambda dr: dr.ellipse([cx - rad * 1.9, cy - rad * 1.9, cx + rad * 1.9, cy + rad * 1.9],
                              fill=(26, 110, 130, 150)), 34 * SS))
    base.alpha_composite(soft_glow(
        lambda dr: dr.ellipse([cx - rad * 1.3, cy - rad * 1.3, cx + rad * 1.3, cy + rad * 1.3],
                              fill=(40, 150, 150, 120)), 18 * SS))

    # orbital link ring (tilted ellipse) with satellite nodes wired to the core
    ring = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    rd = ImageDraw.Draw(ring)
    a, b = rad * 1.72, rad * 0.74           # ring semi-axes
    tilt = math.radians(-18)

    def ring_pt(t):
        x, y = math.cos(t) * a, math.sin(t) * b
        return (cx + x * math.cos(tilt) - y * math.sin(tilt),
                cy + x * math.sin(tilt) + y * math.cos(tilt))

    ring_pts = [ring_pt(2 * math.pi * i / 240) for i in range(241)]
    rd.line(ring_pts, fill=PLASMA + (215,), width=SS * 3, joint="curve")
    base.alpha_composite(ring.filter(ImageFilter.GaussianBlur(3 * SS)))
    base.alpha_composite(ring)

    # satellite nodes on the ring, linked to the core
    nodes = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    nd = ImageDraw.Draw(nodes)
    for t, col in ((math.radians(200), NERO_ALLOY), (math.radians(340), VOID),
                   (math.radians(75), STARSTEEL)):
        nx, ny = ring_pt(t)
        nd.line([(cx, cy), (nx, ny)], fill=PLASMA + (140,), width=SS * 2)
        small_hex(nd, nx, ny, rad * 0.17, col + (255,), (225, 245, 252, 235))
    base.alpha_composite(soft_glow(
        lambda dr: [dr.ellipse([ring_pt(t)[0] - 8 * SS, ring_pt(t)[1] - 8 * SS,
                                ring_pt(t)[0] + 8 * SS, ring_pt(t)[1] + 8 * SS],
                               fill=PLASMA + (200,)) for t in
                    (math.radians(200), math.radians(340), math.radians(75))] and None, 6 * SS))
    base.alpha_composite(nodes)

    # faceted hexagonal prism (family motif), lit cyan
    hexpts = [(cx + math.cos(math.radians(60 * i - 90)) * rad,
               cy + math.sin(math.radians(60 * i - 90)) * rad) for i in range(6)]
    layer = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    facet_cols = [STARSTEEL, NERO_ALLOY, PLASMA, STARSTEEL, NERO_ALLOY, PLASMA]
    for i in range(6):
        shade = 0.58 + 0.42 * (i / 5.0)
        col = tuple(int(c * shade) for c in facet_cols[i])
        d.polygon([(cx, cy), hexpts[i], hexpts[(i + 1) % 6]], fill=col + (255,))
    # bright cyan signal core
    ir = rad * 0.36
    d.ellipse([cx - ir, cy - ir, cx + ir, cy + ir], fill=PLASMA_BRIGHT + (255,))
    d.ellipse([cx - ir * 0.5, cy - ir * 0.5, cx + ir * 0.5, cy + ir * 0.5], fill=BRIGHT + (255,))
    for i in range(6):
        d.line([hexpts[i], hexpts[(i + 1) % 6]], fill=(230, 240, 250, 235), width=max(1, SS * 2))
        d.line([(cx, cy), hexpts[i]], fill=(220, 230, 240, 150), width=max(1, SS))
    base.alpha_composite(layer)

    # broadcast / signal arcs radiating up-right from the core
    arcs = Image.new("RGBA", (R, R), (0, 0, 0, 0))
    ad = ImageDraw.Draw(arcs)
    for k, alpha in ((1.28, 235), (1.52, 190), (1.76, 145)):
        rr = rad * k
        ad.arc([cx - rr, cy - rr, cx + rr, cy + rr], start=-72, end=-18,
               fill=PLASMA_BRIGHT + (alpha,), width=SS * 4)
    base.alpha_composite(arcs.filter(ImageFilter.GaussianBlur(3 * SS)))
    base.alpha_composite(arcs)

    # specular sparkle
    sx, sy = cx - rad * 0.16, cy - rad * 0.46
    base.alpha_composite(soft_glow(
        lambda dr: dr.ellipse([sx - 9 * SS, sy - 9 * SS, sx + 9 * SS, sy + 9 * SS],
                              fill=(255, 255, 255, 255)), 5 * SS))
    dd = ImageDraw.Draw(base)
    L = 18 * SS
    dd.line([sx - L, sy, sx + L, sy], fill=(255, 255, 255, 230), width=SS * 2)
    dd.line([sx, sy - L, sx, sy + L], fill=(255, 255, 255, 230), width=SS * 2)
    return base


def wordmark(base):
    big = _font(int(R * 0.140))
    tagf = _font(int(R * 0.030))

    def centered(text, font, y, fill, glow=None):
        w = ImageDraw.Draw(base).textlength(text, font=font)
        x = (R - w) / 2
        if glow:
            gl = Image.new("RGBA", (R, R), (0, 0, 0, 0))
            ImageDraw.Draw(gl).text((x, y), text, font=font, fill=glow)
            base.alpha_composite(gl.filter(ImageFilter.GaussianBlur(9 * SS)))
            base.alpha_composite(gl.filter(ImageFilter.GaussianBlur(3 * SS)))
        out = Image.new("RGBA", (R, R), (0, 0, 0, 0))
        ImageDraw.Draw(out).text((x, y), text, font=font, fill=(10, 12, 16, 255))
        base.alpha_composite(out.filter(ImageFilter.MaxFilter(2 * SS + 1)))
        ImageDraw.Draw(base).text((x, y), text, font=font, fill=fill)

    centered("NEROLINK", big, int(R * 0.70), (244, 250, 252, 255), glow=(64, 196, 224, 255))

    tag = "L I N K   ·   M O N I T O R   ·   A C T"
    tw = ImageDraw.Draw(base).textlength(tag, font=tagf)
    ImageDraw.Draw(base).text(((R - tw) / 2, int(R * 0.862)), tag, font=tagf, fill=(158, 222, 238, 255))
    return base


def main():
    img = background()
    img = add_stars(img)
    cx, cy, rad = int(R * 0.5), int(R * 0.355), int(R * 0.125)
    img = emblem(img, cx, cy, rad)
    img = wordmark(img)

    final = img.convert("RGB").resize((FINAL, FINAL), Image.LANCZOS)
    p1 = os.path.join(OUT, "nerolink_logo.png")
    p2 = os.path.join(OUT, "nerolink_logo_400.png")
    p3 = os.path.join(ICON, "nerolink_logo.png")
    final.save(p1)
    final.resize((400, 400), Image.LANCZOS).save(p2)
    final.resize((256, 256), Image.LANCZOS).save(p3)
    for p in (p1, p2, p3):
        print("wrote", os.path.relpath(p, ROOT))


if __name__ == "__main__":
    main()
