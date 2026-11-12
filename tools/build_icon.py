"""BeatPad icon (design appIcon) as a 512 PNG for the showcase / Vibe Lab: same drawing as
res/drawable/ic_launcher_fg.xml on walnut #3B281C. Usage: python3 tools/build_icon.py OUT.png"""
import sys
from PIL import Image, ImageDraw
S = 512; K = S / 108.0; SS = 4
im = Image.new("RGBA", (S * SS, S * SS), (0x3B, 0x28, 0x1C, 255))
d = ImageDraw.Draw(im, "RGBA")
k = K * SS
def c(x, y, r, fill=None, outline=None, w=0): d.ellipse(((x - r) * k, (y - r) * k, (x + r) * k, (y + r) * k), fill=fill, outline=outline, width=int(w * k))
c(54, 46, 18, outline=(0xF3, 0xE6, 0xC8, 255), w=4)
for (x, y, col) in [(54, 28, (0xD9, 0x78, 0x5B)), (72, 46, (0xC9, 0xA1, 0x5B)), (54, 64, (0x8F, 0xA7, 0xA0)), (36, 46, (0xC4, 0x8C, 0x9A))]:
    c(x, y, 4.4, fill=col + (255,))
d.line([(54 * k, 46 * k), (54 * k, 33 * k)], fill=(0xF3, 0xE6, 0xC8, 255), width=int(2 * k))
pts = []
for i in range(41):
    t = i / 40
    x = (1 - t) ** 2 * 30 + 2 * (1 - t) * t * 54 + t * t * 78
    y = (1 - t) ** 2 * 76 + 2 * (1 - t) * t * 90 + t * t * 76
    pts.append((x * k, y * k))
d.line(pts, fill=(0xC9, 0xA1, 0x5B, 255), width=int(6 * k), joint="curve")
for (x, y) in [pts[0], pts[-1]]:
    r = 3 * k; d.ellipse((x - r, y - r, x + r, y + r), fill=(0xC9, 0xA1, 0x5B, 255))
im.resize((S, S), Image.LANCZOS).save(sys.argv[1])
