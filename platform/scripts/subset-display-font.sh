#!/usr/bin/env bash
# 重新生成标题展示字面的子集 (assets/fonts/noto-sans-sc-900-subset.woff2)
#
# 什么时候要跑:
#   改了 h1/h2/h3/.v6-quote 的文案, 且用到了站内此前从未出现过的汉字。
#   不跑的后果: 那几个字回落到系统字体, 与相邻字重量不一, 一眼看得出来。
#   自查命令(浏览器控制台, 页面加载完后跑):
#     [...document.querySelectorAll('h1,h2,h3,.v6-quote')]
#       .flatMap(e=>[...e.textContent]).filter(c=>c.trim())
#       .filter(c=>!document.fonts.check('900 40px "Noto Sans SC"',c))
#   返回空数组 = 子集是全的。
#
# 依赖: python + fonttools (pip install fonttools brotli)
# 源字体: Noto Sans SC 可变字体 (SIL OFL)。仓库里不放源文件(数 MB), 用时现取:
#   https://github.com/notofonts/noto-cjk/releases  → Variable/OTF/NotoSansSC-VF.otf
#   Windows 自带同一款: C:/Windows/Fonts/NotoSansSC-VF.ttf
#
# 用法: ./scripts/subset-display-font.sh [源字体路径]

set -euo pipefail
cd "$(dirname "$0")/.."          # → platform/

SRC="${1:-/c/Windows/Fonts/NotoSansSC-VF.ttf}"
OUT="assets/fonts/noto-sans-sc-900-subset.woff2"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

[ -f "$SRC" ] || { echo "找不到源字体: $SRC" >&2; echo "见本脚本顶部的下载地址" >&2; exit 1; }

# 1) 抽出全站实际用到的字符。
#    刻意取「全站正文」而不是「只取标题」—— 多几百字只多几十 KB, 换来的是改文案时
#    大概率不用重跑这个脚本(新标题多半是站内已有词汇的重组)。
echo "→ 抽取全站字符…"
node -e '
const fs=require("fs");
const files=fs.readdirSync(".").filter(f=>/^(index|demo|privacy|solutions-.*)\.html$/.test(f));
let t="";
for(const f of files) t+=fs.readFileSync(f,"utf8")
  .replace(/<script[\s\S]*?<\/script>/g,"").replace(/<style[\s\S]*?<\/style>/g,"").replace(/<[^>]+>/g," ");
const u=[...new Set([...t])].filter(c=>c.trim()&&c.codePointAt(0)>31);
fs.writeFileSync(process.argv[1], u.join(""));
console.log("  唯一字符 "+u.length+" 个");
' "$TMP/chars.txt"

# 2) 把可变字体实例化到 wght=900 (Black)。
#    直接子集化可变字体会把整条字重轴一起带上, 体积翻几倍且用不到。
echo "→ 实例化 wght=900…"
python -c "
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
import sys
f=TTFont(sys.argv[1])
if 'fvar' in f:
    f=instantiateVariableFont(f,{'wght':900})
f.save(sys.argv[2])
" "$SRC" "$TMP/black.ttf"

# 3) 子集化 + woff2。--layout-features='' 丢掉排版特性表(中文用不到, 占体积)。
echo "→ 子集化…"
pyftsubset "$TMP/black.ttf" \
  --text-file="$TMP/chars.txt" \
  --output-file="$OUT" --flavor=woff2 \
  --layout-features='' --no-hinting \
  --unicodes="U+0020-007E,U+00A0,U+2018,U+2019,U+201C,U+201D,U+2014,U+2026,U+00B7,U+3001,U+3002,U+FF0C,U+FF1A,U+FF1B,U+FF08,U+FF09,U+FF1F,U+FF01,U+2192"

echo "✅ $OUT  ($(wc -c < "$OUT") bytes)"
echo
echo "⚠️ 别忘了把 css/v6.css 与各页面里的 ?v=YYYYMMDDx 版本号往前推一位,"
echo "   否则 nginx 的 max-age=43200 会让回访用户 12 小时内继续用旧字体文件。"
