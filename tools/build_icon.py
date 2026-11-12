"""ChefBoard icon (design appIcon) as a 512 PNG for the showcase / Vibe Lab: same drawing as
res/drawable/ic_launcher_fg.xml (white timer ring, 12 ticks, a wooden spoon as the hand) on olive #5E6B3A.
Usage: python3 tools/build_icon.py OUT.png"""
import math, re, sys
from PIL import Image, ImageDraw
S = 512; SS = 4; K = S * SS / 108.0
src = open(__file__.replace("tools/build_icon.py", "app/src/main/res/drawable/ic_launcher_fg.xml")).read()
im = Image.new("RGBA", (S * SS, S * SS), (0x5E, 0x6B, 0x3A, 255))
d = ImageDraw.Draw(im)
def col(h): return (int(h[2:4], 16), int(h[4:6], 16), int(h[6:8], 16), 255)
for fill, data in re.findall(r'fillColor="#([0-9A-F]{8})"(?: android:fillType="evenOdd")? android:pathData="([^"]+)"', src):
    c = col(fill)
    if " a" in data:  # circles: M cx-r,cy a r,r ... (one or two → ring)
        circles = [(float(m.group(1)), float(m.group(2)), float(m.group(3))) for m in re.finditer(r"M([\d.]+),([\d.]+) a([\d.]+)", data)]
        (x0, y0, r0) = circles[0]; cx, cy = x0 + r0, y0
        d.ellipse([(cx - r0) * K, (cy - r0) * K, (cx + r0) * K, (cy + r0) * K], fill=c)
        if len(circles) > 1:
            r1 = circles[1][2]; d.ellipse([(cx - r1) * K, (cy - r1) * K, (cx + r1) * K, (cy + r1) * K], fill=(0x5E, 0x6B, 0x3A, 255))
    else:
        for poly in data.split("Z"):
            pts = [tuple(float(v) * K for v in p.split(",")) for p in re.findall(r"[\d.]+,[\d.]+", poly)]
            if len(pts) >= 3: d.polygon(pts, fill=c)
im.resize((S, S), Image.LANCZOS).save(sys.argv[1])
