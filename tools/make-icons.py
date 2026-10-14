#!/usr/bin/env python3
"""生成 App 图标（品牌渐变 + 账本图形）。

不依赖任何图形库，只用 numpy 做 SDF 栅格化 —— 这样边缘抗锯齿、四角透明都可控，
也不用为了生成图标去装 Pillow/ImageMagick。

用法（在 acbk-shell 目录下）：
    python3 tools/make-icons.py

产出：
    app/src/main/res/mipmap-*/ic_launcher.png              传统方形图标（圆角、四角透明）
    app/src/main/res/mipmap-xxxhdpi/ic_launcher_background.png  自适应图标·背景层（满幅渐变）
    app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png  自适应图标·前景层（透明底 + 62% 图形）
"""

import os
import struct
import zlib

import numpy as np

# 品牌色（与面板 CSS 变量 --accent / --accent-2 一致）
ACCENT = (0x4D, 0x6B, 0xFE)
ACCENT2 = (0x7C, 0x4D, 0xFF)
PAPER = (255, 255, 255)
SPINE = (230, 234, 255)
LINE_1 = (0x4D, 0x6B, 0xFE)
LINE_2 = (0x93, 0xA5, 0xFF)
BOOKMARK = (0xFF, 0xB0, 0x20)

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res")

# 自适应图标的前景/背景用 108dp 画布，内容留在中央安全区内
FOREGROUND_SCALE = 0.62


def rounded_rect_sdf(x, y, left, top, width, height, rtl, rtr=None, rbr=None, rbl=None):
    """圆角矩形有符号距离场；四角半径可分别指定（0 表示直角）。"""
    if rtr is None:
        rtr = rbr = rbl = rtl
    cx, cy = left + width / 2.0, top + height / 2.0
    hx, hy = width / 2.0, height / 2.0
    qx, qy = np.abs(x - cx), np.abs(y - cy)
    r = np.where(
        (x < cx) & (y < cy), rtl,
        np.where((x >= cx) & (y < cy), rtr,
                 np.where((x >= cx) & (y >= cy), rbr, rbl)))
    qx2, qy2 = qx - (hx - r), qy - (hy - r)
    return (np.sqrt(np.maximum(qx2, 0) ** 2 + np.maximum(qy2, 0) ** 2)
            + np.minimum(np.maximum(qx2, qy2), 0) - r)


def polygon_coverage(x, y, points, samples=4):
    """多边形覆盖率（在其包围盒内做 samples×samples 超采样）。"""
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    inside = (x >= x0 - 2) & (x <= x1 + 2) & (y >= y0 - 2) & (y <= y1 + 2)
    acc = np.zeros(x.shape, dtype=np.float32)
    n = len(points)
    for i in range(samples):
        for j in range(samples):
            px = x + (i + 0.5) / samples - 0.5
            py = y + (j + 0.5) / samples - 0.5
            mask = np.zeros(x.shape, dtype=bool)
            for k in range(n):
                xa, ya = points[k]
                xb, yb = points[(k + 1) % n]
                if ya == yb:
                    continue
                mask ^= ((ya > py) != (yb > py)) & (px < (xb - xa) * (py - ya) / (yb - ya) + xa)
            acc += mask
    return np.where(inside, acc / (samples * samples), 0.0)


def render(size, glyph_scale=1.0, with_background=True, rounded=True):
    """渲染一张图标。坐标以 512×512 为基准，按 size 缩放。"""
    scale = size / 512.0
    pixel = 1.0 / scale                     # 1 个设备像素等于多少 512 空间单位
    ys, xs = np.mgrid[0:size, 0:size]
    x = ((xs + 0.5) / scale).astype(np.float32)
    y = ((ys + 0.5) / scale).astype(np.float32)
    img = np.zeros((size, size, 4), dtype=np.float32)

    def over(alpha, color):
        c = np.array(color, dtype=np.float32) / 255.0
        a = alpha[..., None].astype(np.float32)
        img[..., :3] = c * a + img[..., :3] * (1 - a)
        img[..., 3:4] = a + img[..., 3:4] * (1 - a)

    def aa(distance):                       # 1 设备像素宽的边缘过渡
        return np.clip(0.5 - distance / pixel, 0, 1).astype(np.float32)

    if with_background:
        d = rounded_rect_sdf(x, y, 0, 0, 512, 512, 115) if rounded \
            else np.full(x.shape, -1.0, dtype=np.float32)
        alpha = aa(d)
        t = np.clip((x + y) / 1024.0, 0, 1)[..., None]
        gradient = (np.array(ACCENT, dtype=np.float32) / 255.0) * (1 - t) \
            + (np.array(ACCENT2, dtype=np.float32) / 255.0) * t
        img[..., :3] = gradient * alpha[..., None]
        img[..., 3:4] = alpha[..., None]

    def gx(v):                              # 图形坐标 → 按 glyph_scale 缩放
        return 256.0 + (v - 256.0) * glyph_scale

    def gs(v):
        return v * glyph_scale

    over(aa(rounded_rect_sdf(x, y, gx(136), gx(118), gs(240), gs(276), gs(30))), PAPER)
    over(aa(rounded_rect_sdf(x, y, gx(136), gx(118), gs(46), gs(276), gs(23), 0, 0, gs(23))), SPINE)
    over(aa(rounded_rect_sdf(x, y, gx(206), gx(190), gs(110), gs(20), gs(10))), LINE_1)
    over(aa(rounded_rect_sdf(x, y, gx(206), gx(242), gs(110), gs(20), gs(10))), LINE_2)
    over(aa(rounded_rect_sdf(x, y, gx(206), gx(294), gs(76), gs(20), gs(10))), LINE_2)
    bookmark = [
        (gx(318), gx(118)), (gx(362), gx(118)), (gx(362), gx(214)),
        (gx(340), gx(197)), (gx(318), gx(214)),
    ]
    over(polygon_coverage(x, y, bookmark), BOOKMARK)
    return img


def write_png(path, img):
    size = img.shape[0]
    rgba = (np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8)
    raw = b"".join(b"\x00" + rgba[row].tobytes() for row in range(size))

    def chunk(tag, data):
        body = tag + data
        return (struct.pack(">I", len(data)) + body
                + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(png)
    return len(png)


def main():
    jobs = [
        ("mipmap-mdpi/ic_launcher.png", 48, 1.0, True, True),
        ("mipmap-hdpi/ic_launcher.png", 72, 1.0, True, True),
        ("mipmap-xhdpi/ic_launcher.png", 96, 1.0, True, True),
        ("mipmap-xxhdpi/ic_launcher.png", 144, 1.0, True, True),
        ("mipmap-xxxhdpi/ic_launcher.png", 192, 1.0, True, True),
        ("mipmap-xxxhdpi/ic_launcher_background.png", 432, 1.0, True, False),
        ("mipmap-xxxhdpi/ic_launcher_foreground.png", 432, FOREGROUND_SCALE, False, True),
    ]
    for rel, size, glyph, background, rounded in jobs:
        written = write_png(os.path.join(RES, rel), render(size, glyph, background, rounded))
        print("  %-48s %4dpx  %6.1f KB" % (rel, size, written / 1024))


if __name__ == "__main__":
    main()
