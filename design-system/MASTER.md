# Cretas 官网 V6「AI 之眼」设计系统 — MASTER

**生成于**: 2026-07-21
**来源**: `python ui-ux-pro-max/scripts/search.py "B2B food industry AI SaaS landing warm human" --design-system -p "Cretas官网V6" --variance 6 --motion 5 --density 4`（skill 原始输出附于文末）+ `docs/superpowers/specs/2026-07-21-website-redesign-design.md` §3「视觉系统 — V6『AI 之眼』融合版」

**⚠️ 优先级声明**: 当 skill 输出与 spec §3 冲突时（本次主要冲突点：色板、字体、动效风格），**spec §3 的颜色 / 圆角 / 图片衔接手法为准**，skill 输出仅作补充参考（如动效 easing 细节、可访问性 checklist、避免事项）。原因：spec §3 已由 Steve 定稿并存档概念稿（`.superpowers/brainstorm/1248-1784612471/content/visual-style-v4/v5/v6.html`），是产品签名视觉语言，不可被通用 skill 推荐的紫粉 AI SaaS 配色替换。

---

## 最终生效 Token 表（spec §3 优先，已写入 `platform/css/v6.css`）

| Token | 值 | 来源 | 用途 |
|---|---|---|---|
| `--v6-bg-a` | `#fdfcfa` | spec §3 | 背景渐变起点（暖灰白） |
| `--v6-bg-b` | `#faf8f4` | spec §3 | 背景渐变终点 |
| `--v6-ink` | `#111` | spec §3 | 主文字/按钮墨黑 |
| `--v6-ink-2` | `#57534e` | spec §3 派生 | 次级文字 |
| `--v6-cta` | `#00b377` | spec §3 | CTA 绿（AI 工作色） |
| `--v6-cta-ink` | `#00875c` | spec §3 派生 | CTA 深绿（active/hover 文字） |
| `--v6-tag` | `#00e28a` | spec §3 | 识别标注绿 |
| `--v6-tag-bg` | `rgba(4,20,13,.5)` | spec §3 派生 | 深底标注背景（`.v6-ai-tag--dark`） |
| `--v6-r-hero` | `22px` | spec §3（hero 容器 18-22px） | hero 圆角，取上限 |
| `--v6-r-card` | `16px` | spec §3（照片卡 14-16px） | 照片卡圆角，取上限 |
| `--v6-font` | `-apple-system,'PingFang SC','Microsoft YaHei',sans-serif` | spec §3 | 中文黑体正文/标题栈 |
| `--v6-mono` | `ui-monospace,SFMono-Regular,Consolas,monospace` | spec §3 | 批次号/标注等宽字体 |

**图片衔接三手法**（spec §3，已实现于 CSS 类）：
- `hero` 底边 `mask-image` 渐变溶解 → `.v6-hero-mask`（`mask-image:linear-gradient(180deg,#000 0%,#000 74%,transparent 99%)`）
- 照片同色系光晕投影（无边框）→ `.v6-glow`（`radial-gradient` ambient glow）
- 白玻璃胶囊过渡带（backdrop-blur）→ `.v6-live-bar`（`backdrop-filter:blur(14px) saturate(160%)`）

**识别框/标注**（spec §3）：`.v6-ai-box`（绿描边 1.5px + 圆角 10px + 光晕阴影）、`.v6-ai-tag`（胶囊 99px、等宽字体、7-8px 级识别标签用 `font-size:12px` 实测视觉等效）。

**图标**：⛔ 禁 emoji，一律 inline SVG（Lucide 风格单色描边）— 沿用 spec §3，后续页面任务需自行内联 SVG，不在本任务范围内生成图标库。

**响应式断点**：375 / 768 / 1024 / 1440，已在 `v6.css` 的 `@media (max-width:768px)` 落地基础断点；其余断点由页面任务按需扩展。

**动效**：`prefers-reduced-motion: reduce` 全降级（`.v6-reveal` 与 `.v6-live-bar` 均已覆盖），滚动渐入用 `v6RevealInit()`（IntersectionObserver, threshold .12），LIVE 字幕轮播用 `v6LiveTicker(el, items)`（4.2s 间隔，reduced-motion 时静态拼接展示）。

**图片衔接补充类**（Task 1b 接口需要，已追加至 `v6.css` 末尾）：`.v6-shot{border-radius:12px;box-shadow:0 14px 34px rgba(60,60,60,.18);overflow:hidden}` — 用于产品截图套浏览器壳。

---

## Skill 补充参考（非色板，仅采纳可访问性/动效细节）

从 skill 输出中，**采纳**以下与 spec §3 不冲突的建议：
- Pre-delivery checklist: 无 emoji 图标（用 SVG）、可点击元素 `cursor:pointer`、hover 过渡 150-300ms、light mode 对比度 ≥4.5:1、键盘焦点可见、`prefers-reduced-motion` 尊重、响应式 375/768/1024/1440 — 均已在 spec §3 Global Constraints 中同样要求，两者一致。
- Motion 参考：page transition duration 400-600ms / easing power2.inOut，可供后续页面切换动效参考（本任务 `v6.css` 的 `.v6-reveal` 用 `cubic-bezier(.22,1,.36,1)` 0.5s，视觉更贴近产品克制感，未采用 skill 默认值）。
- Avoid: 过度动画 + 默认深色模式 — 与 spec §3 一致（V6 是暖白底浅色系）。

**不采纳**（与 spec §3 冲突，作废）：
- Style: "Trust & Authority" 紫色权威风格 — 与 Cretas 暖光真实摄影签名语言不符，不用。
- Colors: Primary `#7C3AED` / Secondary `#6366F1` / Accent `#EC4899` / Background `#FAF5FF` — 全部紫粉色系，与 spec §3 暖灰白 + 墨黑 + AI 绿完全不同，**不采用**。
- Typography: Calistoga / Inter 双字体 — 面向拉丁字符设计，Cretas 官网中文为主，采用 spec §3 系统中文黑体栈 + 等宽字体替代。
- Pattern: "AI Personalization Landing"（含个性化 hero/推荐算法）— 超出本次静态官网范围，不采用。

---

## Skill 原始输出（存档，未 --persist，手动誊抄）

```
╔═════════════════════════════════════════════════════════════════════════════════════════╗
║  TARGET: Cretas官网V6 - RECOMMENDED DESIGN SYSTEM                                         ║
╚═════════════════════════════════════════════════════════════════════════════════════════╝
┌─── DESIGN DIALS ───────────────────────────────────────────────────────────────────────┐
│  Variance: 6/10 — Balanced / Modern
│  Motion:   5/10 — Standard
│  Density:  4/10 — Standard
├─── PATTERN ────────────────────────────────────────────────────────────────────────────┤
│  Name: AI Personalization Landing
│     Conversion: 20%+ conversion with personalization. Requires analytics integration. Fallback for new users.
│     CTA: Context-aware placement based on user segment
│     Sections: 1. Dynamic hero (personalized), 2. Relevant features, 3. Tailored testimonials, 4. Smart CTA
├─── STYLE ──────────────────────────────────────────────────────────────────────────────┤
│  Name: Trust & Authority
│     Mode Support: Light Full / Dark Full
│     Keywords: Certificates/badges displayed, expert credentials, case studies with metrics,
│     before/after comparisons, industry recognition, security badges
│     Best For: Healthcare/medical landing pages, financial services, enterprise software,
│     premium/luxury products, legal services
│     Performance: Excellent | Accessibility: WCAG AAA
├─── COLORS ─────────────────────────────────────────────────────────────────────────────┤
│  Primary:       #7C3AED  (--color-primary)
│  On Primary:    #FFFFFF  (--color-on-primary)
│  Secondary:     #6366F1  (--color-secondary)
│  Accent/CTA:    #EC4899  (--color-accent)
│  Background:    #FAF5FF  (--color-background)
│  Foreground:    #0F172A  (--color-foreground)
│  Muted:         #F7F3FD  (--color-muted)
│  Border:        #EFE7FC  (--color-border)
│  Destructive:   #DC2626  (--color-destructive)
│  Ring:          #7C3AED  (--color-ring)
│  Notes: AI purple + generation pink
├─── TYPOGRAPHY ─────────────────────────────────────────────────────────────────────────┤
│  Calistoga / Inter
│     Mood: saas, boutique, electric, warm, editorial, bold, premium, fintech, business,
│     dual font, human warmth
│     Best For: B2B SaaS mobile, fintech apps, analytics dashboards, marketing tools, operations platforms
│     Google Fonts: https://fonts.google.com/share?selection.family=Calistoga:ital@0;1|Inter:wght@300;400;500;600;700|JetBrains+Mono:wght@400;500
├─── KEY EFFECTS ────────────────────────────────────────────────────────────────────────┤
│  Badge hover effects, metric pulse animations, certificate carousel, smooth stat reveal
├─── MOTION ─────────────────────────────────────────────────────────────────────────────┤
│  Page Transition (Standard)
│     Trigger: route change | Duration: 400-600ms | Easing: power2.inOut
│     GSAP: const tl = gsap.timeline(); tl.to('.transition-overlay', { yPercent: 0,
│     duration: 0.4, ease: 'power2.inOut' }).call(navigate).to('.transition-overlay', {
│     yPercent: -100, duration: 0.4, ease: 'power2.inOut', delay: 0.1 });
│     Framework: Keep the overlay element mounted at the layout root (outside the page
│     component) so it survives the route swap
├─── AVOID ──────────────────────────────────────────────────────────────────────────────┤
│  Excessive animation + Dark mode by default
├─── PRE-DELIVERY CHECKLIST ────────────────────────────────────────────────────────────┤
│  [ ] No emojis as icons (use SVG: Heroicons/Lucide)
│  [ ] cursor-pointer on all clickable elements
│  [ ] Hover states with smooth transitions (150-300ms)
│  [ ] Light mode: text contrast 4.5:1 minimum
│  [ ] Focus states visible for keyboard nav
│  [ ] prefers-reduced-motion respected
│  [ ] Responsive: 375px, 768px, 1024px, 1440px
└──────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 消费方式（后续任务）

Task 3-7（各业务页）只引用 `platform/css/v6.css` 中已定义的类名（`v6-nav` `v6-hero` `v6-hero-mask` `v6-glow` `v6-live-bar` `v6-photo-card` `v6-ai-tag` `v6-ai-box` `v6-btn` `v6-btn-ghost` `v6-quote` `v6-section` `v6-shot`）与 `platform/js/v6.js` 中的 `v6RevealInit()` / `v6LiveTicker(el, items)`，**不自造新 token / 不新增全局 CSS 变量**。如页面确需新增视觉元素，先回本文件补充 token 定义，再消费。
