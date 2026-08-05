#!/usr/bin/env bash
# 为站内照片/截图生成多尺寸 WebP 变体, 供 <picture> 的 srcset 使用。
#
# 为什么要做: 改之前手机(390px)下载的是和桌面一模一样的 858KB —— band-kitchen.jpg
# 有 1600px 宽却显示在不到 400px 的位置。图片是这个站唯一的大头。
#
# 产物: assets/img/**/<name>-<width>.webp
# 原图保留不动, 作为 <picture> 里 <img src> 的兜底(不支持 WebP 的老浏览器)。
#
# ⚠️ 宽度选择要考虑动效层: v6-premium.js 会给 .v6-photo-card img 设 scale(1.18),
#    hero 图进场时 scale 更大。所以"显示 390px"实际需要的像素比 390 多三成,
#    再叠 2x DPR —— 这就是为什么最小档取 480 而不是 400。
#
# 依赖: python + Pillow, cwebp (libwebp)
# 用法: ./scripts/build-responsive-images.sh

set -euo pipefail
cd "$(dirname "$0")/.."          # → platform/

command -v cwebp >/dev/null || { echo "缺 cwebp (libwebp)" >&2; exit 1; }
python -c "import PIL" 2>/dev/null || { echo "缺 Pillow (pip install pillow)" >&2; exit 1; }

WIDTHS="480 768 1200 1600"
# ⚠️ 不用 mktemp -d: 在 Git Bash 下它返回 /tmp/... 这类 POSIX 路径, 而这里调的是
#    Windows 原生 python, 认不出来会直接 FileNotFoundError。用相对目录跨环境都安全。
TMP=".img-build-tmp"; rm -rf "$TMP"; mkdir -p "$TMP"; trap 'rm -rf "$TMP"' EXIT
total_src=0; total_out=0

# 照片用有损 q=78(肉眼无损, 体积约为 JPEG 的六成);
# 产品截图含大量文字与细线, 压太狠会糊, 用 q=88。
for src in assets/img/*.jpg assets/img/shots/*.png; do
  [ -f "$src" ] || continue
  base="${src%.*}"; ext="${src##*.}"
  case "$ext" in png) Q=88 ;; *) Q=78 ;; esac
  sw=$(python -c "from PIL import Image;print(Image.open(r'$src').width)")
  echo "── $(basename "$src")  源宽 ${sw}px"
  sz=$(wc -c < "$src"); total_src=$((total_src+sz))

  for w in $WIDTHS; do
    # 不放大: 目标宽超过原图就跳过(放大只会变大不会变清楚)
    [ "$w" -gt "$sw" ] && continue
    out="${base}-${w}.webp"
    python -c "
from PIL import Image
im=Image.open(r'$src'); im=im.convert('RGB')
h=round(im.height*$w/im.width)
im.resize(($w,h), Image.LANCZOS).save(r'$TMP/r.png')
"
    cwebp -quiet -q $Q "$TMP/r.png" -o "$out"
    osz=$(wc -c < "$out"); total_out=$((total_out+osz))
    printf "     %5dw  %7d bytes\n" "$w" "$osz"
  done
done

echo
echo "原图合计   $(python -c "print(f'{$total_src/1024:.0f} KB')")"
echo "变体合计   $(python -c "print(f'{$total_out/1024:.0f} KB')")  (所有尺寸之和; 单次访问只取其中一档)"
echo
echo "⚠️ 生成后别忘了:"
echo "   1) HTML 里的 <picture>/srcset 若新增了尺寸档, 要同步加进 srcset 列表"
echo "   2) 把页面里的 ?v=YYYYMMDDx 版本号往前推一位, 否则 nginx max-age=43200"
echo "      会让回访用户 12 小时内继续用旧资源"
