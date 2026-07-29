#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成演示用桌牌二维码 —— 版式仿客户现有桌牌（黑底 + 大桌号 + 底部扫码区）。

用法:
    python tools/make_qr.py                      # 默认 A01 A05 B02
    python tools/make_qr.py A01 A02 B07 --base http://139.196.165.140:8089/

输出: platform/foodcourt/qr/<桌号>.png
"""
import argparse
import os
import sys

import qrcode
from PIL import Image, ImageDraw, ImageFont

W, H = 900, 1800
INK = (11, 11, 12)
INK2 = (20, 20, 23)
CREAM = (242, 237, 228)
RED = (192, 57, 43)
FONT_BD = r"C:\Windows\Fonts\msyhbd.ttc"
FONT_RG = r"C:\Windows\Fonts\msyh.ttc"
OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "platform", "foodcourt", "qr")


def font(path, size):
    try:
        return ImageFont.truetype(path, size)
    except OSError:
        return ImageFont.load_default()


def draw_doodles(d):
    """手绘风格白线条 —— 呼应客户桌牌上的手绘覆盖层。"""
    line = (242, 237, 228, 255)
    # 碗
    d.arc([90, 560, 430, 800], start=0, end=180, fill=line, width=4)
    d.arc([125, 585, 395, 762], start=8, end=172, fill=line, width=3)
    d.line([90, 620, 430, 620], fill=line, width=4)
    # 蒸汽
    for x, off in ((190, 0), (255, -18), (320, 8)):
        d.arc([x - 26, 430 + off, x + 26, 545 + off], start=250, end=60, fill=line, width=3)
    # 盘子
    d.ellipse([520, 620, 830, 800], outline=line, width=4)
    d.ellipse([565, 645, 785, 775], outline=line, width=3)
    # 杯
    d.arc([560, 400, 760, 560], start=0, end=180, fill=line, width=4)
    d.line([560, 480, 760, 480], fill=line, width=4)
    d.line([660, 560, 660, 610], fill=line, width=4)
    d.line([615, 612, 705, 612], fill=line, width=4)


def make(table, base_url):
    table = table.upper()
    url = base_url.rstrip("/") + "/?table=" + table

    card = Image.new("RGB", (W, H), INK)
    d = ImageDraw.Draw(card)

    # ---- 手绘装饰层（低透明度）----
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw_doodles(ImageDraw.Draw(layer))
    layer.putalpha(layer.getchannel("A").point(lambda a: int(a * 0.30)))
    card.paste(Image.alpha_composite(card.convert("RGBA"), layer).convert("RGB"), (0, 0))
    d = ImageDraw.Draw(card)

    # ---- 桌号大字 ----
    f_table = font(FONT_BD, 250)
    d.text((72, 90), table, font=f_table, fill=CREAM)

    # ---- 右上角刀叉 ----
    x, y = W - 148, 118
    for dx in (0, 22):
        d.line([x + dx, y, x + dx, y + 52], fill=CREAM, width=5)
    d.line([x + 11, y + 52, x + 11, y + 168], fill=CREAM, width=5)
    d.arc([x + 62, y - 6, x + 126, y + 108], start=180, end=360, fill=CREAM, width=5)
    d.line([x + 94, y + 62, x + 94, y + 168], fill=CREAM, width=5)

    # ---- 分隔 ----
    d.line([72, 372, W - 72, 372], fill=(48, 48, 52), width=2)
    d.line([72, 372, 172, 372], fill=RED, width=5)

    # ---- 底部扫码区 ----
    panel_top = 950
    d.rounded_rectangle([56, panel_top, W - 56, H - 56], radius=28, fill=INK2)

    f_brand = font(FONT_BD, 50)
    f_tag = font(FONT_RG, 22)
    f_hint = font(FONT_RG, 34)
    f_foot = font(FONT_RG, 24)

    d.text((W // 2, panel_top + 78), "天物书岛食集", font=f_brand, fill=CREAM, anchor="mm")
    d.text((W // 2, panel_top + 132), "F O O D   I S   N E W   F A S H I O N",
           font=f_tag, fill=(150, 146, 138), anchor="mm")
    d.line([W // 2 - 40, panel_top + 176, W // 2 + 40, panel_top + 176], fill=RED, width=4)
    d.text((W // 2, panel_top + 232), "就餐请扫描下方二维码", font=f_hint, fill=CREAM, anchor="mm")

    # ---- 二维码 ----
    qr = qrcode.QRCode(version=None, error_correction=qrcode.constants.ERROR_CORRECT_H,
                       box_size=10, border=2)
    qr.add_data(url)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white").convert("RGB")
    size = 372
    img = img.resize((size, size), Image.Resampling.NEAREST)
    frame = Image.new("RGB", (size + 36, size + 36), "white")
    frame.paste(img, (18, 18))
    qr_top = panel_top + 272
    card.paste(frame, ((W - frame.width) // 2, qr_top))

    d.text((W // 2, qr_top + frame.height + 48), "扫码后请确认页面顶部桌号为 " + table,
           font=f_foot, fill=(150, 146, 138), anchor="mm")
    d.text((W // 2, qr_top + frame.height + 88), url,
           font=font(FONT_RG, 19), fill=(96, 94, 90), anchor="mm")

    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, table + ".png")
    card.save(path, quality=95)
    return path, url


def main():
    p = argparse.ArgumentParser()
    p.add_argument("tables", nargs="*", default=["A01", "A05", "B02"])
    p.add_argument("--base", default="http://139.196.165.140:8089")
    a = p.parse_args()
    for t in (a.tables or ["A01", "A05", "B02"]):
        path, url = make(t, a.base)
        print("%-6s -> %s   (%s)" % (t, path, url))


if __name__ == "__main__":
    sys.exit(main())
