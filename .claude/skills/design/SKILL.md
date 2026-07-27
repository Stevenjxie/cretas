---
name: design
description: "统一设计 Skill — 设计工作唯一入口/路由枢纽。覆盖: (1) 业务页面开发 (Vue/RN/小程序三端规范, 本 skill 权威), (2) 创意展示页 (landing page/showcase/海报 → taste/impeccable), (3) 截图提取设计系统 (→ impeccable extract), (4) UI 审查 (→ impeccable audit), (5) 品牌物料 (logo/CIP/banner/社媒图/slides → promax-design), (6) 动效 (→ gsap-*/emil-design-eng/apple-design)。当用户说\"做页面\"、\"设计UI\"、\"照着截图做\"、\"审查UI\"、\"美化\"、\"landing page\"、\"加动画\"时触发。"
---

# 统一设计 Skill (路由枢纽 v2)

自动识别设计意图，路由到正确的工作流。

**v2 (2026-07-28) 变更**: 设计 skill 群精简后重排 — `banner-design`/`slides`/`ui-styling` 已删（前两者内容 promax-design 逐字节内置；shadcn 栈本项目用不上）；`gsap-*`×8 与 `ui-ux-pro-max` 只保留全局副本（`~/.claude/skills/`）；创意页与 UI 打磨首选 `impeccable`/`taste`（Steve 主用）。

## 意图检测与路由

| 用户说的 | 检测信号 | 路由 |
|----------|---------|------|
| "做一个xx页面"、"加个表单"、"写个列表页" | 具体业务功能 | **→ Route 1 业务页面**（本 skill 权威，见下） |
| "landing page"、"展示页"、"官网"、"portfolio"、"海报" | 独立创意页 | **→ `taste`**（`~/.claude/skills/taste/`，anti-slop landing/portfolio 专精，Design Read → 三旋钮） |
| "美化"、"打磨"、"重设计"、"这界面不够好" | 已有界面改进 | **→ `impeccable`**（craft/shape/polish/refine；先按其 SKILL.md 跑 context.mjs setup） |
| "照着这个截图做"、"参考这个设计" | 提供截图/参考图 | **→ `impeccable` extract** |
| "审查UI"、"检查设计"、"accessibility" | 审查类动词 | **→ `impeccable` audit** + 对照 Route 1 references |
| "优化动画"、"动效"、"滚动动画"、"parallax" | 动画需求 | **→ `gsap-*`（全局）** 实现；动效品味 review → `emil-design-eng`；Apple 式手势/spring → `apple-design` |
| "配色建议"、"字体搭配"、"这个品类该长啥样" | 设计灵感/情报 | **→ `ui-ux-pro-max`（全局，只读情报库）** |
| "做Logo"、"CIP"、"icon"、"banner"、"社媒图"、"pitch deck/演示" | 品牌/营销物料 | **→ `promax-design`**（全内置）；其外部依赖 `brand`/`design-system` 独立可用 |
| "BP"、"商业计划书" | — | **→ `bp-creator`（全局）** |
| 图表/数据可视化 | — | **→ 内置 `dataviz` skill** |
| 不确定 | — | 问用户："你要做业务页面还是创意展示页？" |

## 外部 skill 委派地图 (v2)

| 需求 | 委派给 | 说明 |
|------|--------|------|
| 创意 landing / portfolio / 官网 / 重设计 | `taste`（frontmatter 名 `design-taste-frontend`） | 全局。真设计系统选型（Fluent/Material/Carbon/GOV.UK…）+ 反 AI 默认审美。**不接 dashboard/数据表/多步产品 UI** |
| 产品 UI 打磨 / 审查 / 截图提取 / 大胆视觉 | `impeccable` | 全局。子命令 craft/shape/audit/polish/extract/animate…；**必须先跑其 context.mjs setup 步骤** |
| 动效/微交互实现 | `gsap-core` `gsap-timeline` `gsap-scrolltrigger` `gsap-plugins` `gsap-utils` `gsap-performance` `gsap-react` `gsap-frameworks` | **全局副本**（项目副本 2026-07-28 已删）。Vue 用 `gsap-frameworks`。⚠️ 引入 gsap 依赖前先确认要采用（现有 `useCountUp` 是手写 RAF） |
| 动效/细节品味 review | `emil-design-eng`、`apple-design` | 全局。Emil 哲学（Before/After 表输出）；Apple spring/手势/材质 |
| 设计灵感 / 配色 / 字体 / 品类风格 / UX 指南 | `ui-ux-pro-max` | **全局副本**。只读情报库（84风格/161配色/73字体），只出建议不替代 Route 1 规范 |
| Logo / CIP / icon / banner / 社媒图 / slides | `promax-design` | 全内置（原 `banner-design`/`slides` 独立 skill 内容与之逐字节相同，已删）。AI 出图脚本需 `GEMINI_API_KEY`，缺则降级为规范/brief 建议 |
| 品牌 voice / 资产规范 / 一致性 | `brand` | promax 的外部依赖，独立可用（inject-brand-context.cjs 等脚本） |
| Token 架构 / CSS 变量体系 | `design-system` | promax 的外部依赖。仅 greenfield，不改现有三端 token |

### ⛔ 边界铁律 (防错栈污染)

1. **Route 1 三端业务页 (Vue Element Plus / RN Paper / 小程序 WXSS) 永远走本 skill 的 references**，绝不采纳 taste/impeccable/ui-ux-pro-max 的通用栈建议（shadcn/Tailwind/绿地配色）——会违反「不发明新样式」原则 + `fool-proof-design.md` 防呆规范。
2. taste/impeccable 只接**独立创意页**（landing/showcase/官网/海报/Artifacts）与 UI 打磨审查；业务页面落地实现回 Route 1 平台规范。
3. 防呆 (`fool-proof-design.md`) + `ux-flow` 门是 Route 1 的强制前置，外部 skill 不覆盖它们。
4. shadcn/ui + Tailwind 栈（原 `ui-styling` skill）已移除——三端都装不了；真有 React 绿地需求再从 claudekit 重装。

---

## Route 1: 业务页面开发

### 平台自动检测

| 信号 | 平台 | 加载规范 |
|------|------|---------|
| 路径含 `web-admin/`、提到 Element Plus / Vue / el-table | **Vue Web Admin** | 读 [references/vue-web-admin.md](references/vue-web-admin.md) |
| 路径含 `frontend/CretasFoodTrace/`、提到 RN / Expo / Paper | **React Native** | 读 [references/react-native.md](references/react-native.md) |
| 路径含 `MallCenter/mall_miniprogram/`、提到小程序 / WXSS | **微信小程序** | 读 [references/miniprogram.md](references/miniprogram.md) |
| 多平台对比 | 跨平台 | 读 [references/cross-platform-tokens.md](references/cross-platform-tokens.md) |

**原则**: 严格遵循平台设计规范 (颜色/间距/组件)，不发明新样式。

---

## Route 2 fallback: 创意页设计原则（委派不可用时的最低标准）

正常情况创意页直接走 `taste`/`impeccable`。若不可用，至少守住：

1. **定调**: 选一个鲜明的美学方向，输出一行 Design Read 再动手
2. **排版**: 不用 Arial/Inter/Roboto 默认组合，选有个性的字体搭配
3. **色彩**: 大胆主色 + 锐利点缀，拒绝紫色渐变白底
4. **动效**: 入场动画 > 零散微交互，CSS-only 优先，必配 prefers-reduced-motion
5. **禁止**: cookie-cutter 三等分卡片、通用玻璃拟态、一切"AI味"审美

实现要求: 可运行的 production-grade HTML/CSS/JS 或框架代码。
