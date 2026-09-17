"""Generate matching SVG and Android vector brand assets (standard library only)."""
from pathlib import Path
import math

ROOT = Path(__file__).resolve().parents[1]
POINTS = [(84, 10), (25, 71), (53, 71), (42, 117), (103, 54), (76, 54)]
CUTS = [11, 5, 3, 11, 5, 3]


def transform(p, dx=0, dy=0):
    angle = math.radians(9)
    x, y = p[0] - 64, p[1] - 64
    return (64 + x * math.cos(angle) - y * math.sin(angle) + dx,
            61 + x * math.sin(angle) + y * math.cos(angle) + dy)


def rounded_path(dx=0, dy=0):
    def near(p, q, distance):
        length = math.dist(p, q)
        return tuple(a + (b - a) * distance / length for a, b in zip(p, q))

    def fmt(p):
        return ' '.join(f'{v:.3f}' for v in transform(p, dx, dy))

    result = []
    for i, (point, cut) in enumerate(zip(POINTS, CUTS)):
        before = near(point, POINTS[i - 1], cut)
        after = near(point, POINTS[(i + 1) % len(POINTS)], cut)
        result.append(f'{"M" if i == 0 else "L"}{fmt(before)} Q{fmt(point)} {fmt(after)}')
    return ' '.join(result) + ' Z'


def write(name, text):
    target = ROOT / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf-8')


FACE = rounded_path()
# Vector-only soft shadow: broad faint edge, then progressively tighter layers.
SHADOWS = [(width, round(.005 + (10 - width) * .0015, 4)) for width in range(10, -1, -1)]
SIDES = [(i * .5, i * .65) for i in range(7, 0, -1)]
svg_layers = []
android_layers = []
for width, opacity in SHADOWS:
    path = rounded_path(4, 7)
    svg_layers.append(f'<path d="{path}" fill="#101216" opacity="{opacity}" stroke="#101216" stroke-width="{width}" stroke-linejoin="round"/>')
    android_layers.append(f'<path android:pathData="{path}" android:fillColor="#101216" android:fillAlpha="{opacity}" android:strokeColor="#101216" android:strokeAlpha="{opacity}" android:strokeWidth="{width}" android:strokeLineJoin="round"/>')
for dx, dy in SIDES:
    path = rounded_path(dx, dy)
    svg_layers.append(f'<path d="{path}" fill="#171A1F"/>')
    android_layers.append(f'<path android:pathData="{path}" android:fillColor="#171A1F"/>')
svg_layers.append(f'<path d="{FACE}" fill="url(#graphite)"/>')
android_layers.append(f'''<path android:pathData="{FACE}">
    <aapt:attr name="android:fillColor">
      <gradient android:type="linear" android:startX="32" android:startY="22" android:endX="100" android:endY="108">
        <item android:offset="0" android:color="#50555D"/>
        <item android:offset="0.5" android:color="#30343B"/>
        <item android:offset="1" android:color="#202329"/>
      </gradient>
    </aapt:attr>
  </path>''')
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128" fill="none">
  <title>MBrain — graphite lightning</title>
  <defs><linearGradient id="graphite" gradientUnits="userSpaceOnUse" x1="32" y1="22" x2="100" y2="108">
    <stop stop-color="#50555D"/><stop offset="0.5" stop-color="#30343B"/><stop offset="1" stop-color="#202329"/>
  </linearGradient></defs>
  {''.join(svg_layers)}
</svg>
'''
write('assets/brand/mbrain-logo.svg', svg)
write('assets/brand/mbrain-logo-monochrome.svg', f'<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128"><title>MBrain monochrome</title><path fill="#000000" d="{FACE}"/></svg>\n')


def vector(body, size=24):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"
    android:width="{size}dp" android:height="{size}dp" android:viewportWidth="128" android:viewportHeight="128">
  {body}
</vector>
'''


def launcher(body):
    return vector(f'<group android:scaleX="0.68" android:scaleY="0.68" android:translateX="20.48" android:translateY="20.48">{body}</group>', 108)


mono = f'<path android:fillColor="#000000" android:pathData="{FACE}"/>'
write('app/src/main/res/drawable/ic_mbrain.xml', vector(mono))
write('app/src/main/res/drawable/ic_mbrain_brand.xml', vector(''.join(android_layers), 128))
write('app/src/main/res/drawable/ic_launcher_foreground.xml', launcher(''.join(android_layers)))
write('app/src/main/res/drawable/ic_launcher_monochrome.xml', launcher(mono))

# A persistent preview of the actual SVG, including launcher masks and small sizes.
write('assets/brand/preview.html', '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>MBrain Logo</title>
<style>*{box-sizing:border-box}body{margin:0;background:#f1f2f4;color:#24272c;font:14px system-ui;padding:40px}h1{font-size:20px;margin:0 0 24px}main{display:flex;gap:24px;align-items:center;flex-wrap:wrap}.hero{width:400px;height:400px;background:white;border-radius:28px;display:grid;place-items:center}.hero img{width:360px;height:360px}.samples{display:grid;gap:28px}.row{display:flex;align-items:center;gap:24px}.tile{background:white;width:128px;height:128px;overflow:hidden}.tile img{width:100%;height:100%;transform:scale(.68)}.round{border-radius:50%}.square{border-radius:28%}.small img{width:48px;height:48px}.dark{background:#202329;padding:16px;border-radius:16px}.dark img{filter:invert(1)}p{color:#737880}</style>
<h1>MBrain · 石墨闪电</h1><main><div class="hero"><img src="mbrain-logo.svg" alt="立体闪电"></div><div class="samples"><div class="row"><div class="tile round"><img src="mbrain-logo.svg" alt="圆形桌面图标"></div><div class="tile square"><img src="mbrain-logo.svg" alt="圆角桌面图标"></div></div><div class="row small"><img src="mbrain-logo.svg" alt="48像素"><img style="width:32px;height:32px" src="mbrain-logo.svg" alt="32像素"><img style="width:24px;height:24px" src="mbrain-logo-monochrome.svg" alt="24像素单色"><div class="dark"><img src="mbrain-logo-monochrome.svg" alt="深色单色"></div></div><p>微圆角 · 9° 倾斜 · 薄侧面 · 轻投影</p></div></main></html>''')
print('Generated SVG, Android vectors and preview.')
