# 官网全面改版实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 spec `docs/superpowers/specs/2026-07-21-website-redesign-design.md` 重建官网：V6「AI 之眼」视觉 + 五条业务线（工厂/餐饮/物流/AI 中枢/定制开发）+ 深化场景内容。

**Architecture:** 纯静态 HTML/CSS/JS（沿用 `platform/` 扁平根文件），共享 `css/v6.css` 设计 tokens；每页独立完整 HTML（无构建步骤）；照片本地化到 `platform/assets/img/`。

**Tech Stack:** 静态 HTML + CSS（mask-image / backdrop-filter / ambient glow）+ 原生 JS（IntersectionObserver 渐入、LIVE 条轮播）。禁框架。

## Global Constraints（全部来自 spec，每个任务默认遵守）

- ⛔ emoji 图标；一律 inline SVG（单色描边）。⛔ hero-metric 大数字行、同款卡片阵、每节 uppercase 眉题、渐变文字。
- 颜色：底 `#fdfcfa→#faf8f4` 渐变；墨黑 `#111`；CTA 绿 `#00b377`；标注绿 `#00e28a`。
- 图片衔接三手法：hero 底边 `mask-image` 溶解、照片同色系光晕投影（无边框）、白玻璃胶囊过渡带（backdrop-blur）。
- 字体：系统中文黑体栈 `-apple-system,'PingFang SC','Microsoft YaHei',sans-serif`；标注/批次号 `ui-monospace,monospace`。
- 响应式 375/768/1024/1440；无横向滚动；正文对比度 ≥4.5:1；全部动效带 `@media (prefers-reduced-motion: reduce)` 降级。
- 脱敏 HARD：不出现真实客户名/品牌名/人名/第三方公司名；截图数据脱敏；客户原话匿名（"某食品工厂负责人"）。
- AI 能力口径：600+ 项业务能力 / 50+ 领域；⛔ 不提蒸馏、强化学习等实现机制。
- **每个页面任务开工必须先 invoke `design` skill（创意展示页路由）+ `impeccable`（craft），完成后 invoke `impeccable`（audit）**；token 落定用 `ui-ux-pro-max`；动效参考 `apple-design`。
- Commit 一律 scoped：`git add <files> && git commit -m "..." -- <files>`；worktree off origin/main。
- 部署只到 139（showcase 路径），且必须先 merge main、经 Steve 确认。

---

### Task 1: Worktree + 资产 + 设计 tokens

**Files:**
- Create: worktree `../cretas-website-v6`（分支 `feat/website-v6`）
- Create: `platform/assets/img/hero-kitchen.jpg` `warehouse.jpg` `noodles.jpg` `truck.jpg`
- Create: `platform/css/v6.css`
- Create: `platform/js/v6.js`
- Create: `design-system/MASTER.md`（ui-ux-pro-max --persist 输出，供后续任务检索）

**Interfaces:**
- Produces: CSS 类 `v6-nav` `v6-hero` `v6-hero-mask` `v6-glow` `v6-live-bar` `v6-photo-card` `v6-ai-tag` `v6-ai-box` `v6-btn` `v6-btn-ghost` `v6-quote` `v6-section`；JS 函数 `v6RevealInit()`（IntersectionObserver 渐入）、`v6LiveTicker(el, items)`（LIVE 条轮播）。所有页面任务只消费这些类名/函数，不自造。

- [ ] **Step 1: 建 worktree**

```bash
cd C:/Users/Steve/my-prototype-logistics
git worktree add -b feat/website-v6 ../cretas-website-v6 origin/main
cd ../cretas-website-v6
```

- [ ] **Step 2: 下载并本地化 4 张已验证照片（WebP 优先，JPG 兜底）**

```bash
cd platform && mkdir -p assets/img
curl -sL "https://images.unsplash.com/photo-1577219491135-ce391730fb2c?auto=format&fit=crop&w=1600&q=80" -o assets/img/hero-kitchen.jpg
curl -sL "https://images.unsplash.com/photo-1587293852726-70cdb56c2866?auto=format&fit=crop&w=800&q=75" -o assets/img/warehouse.jpg
curl -sL "https://images.unsplash.com/photo-1526318896980-cf78c088247c?auto=format&fit=crop&w=800&q=75" -o assets/img/noodles.jpg
curl -sL "https://images.unsplash.com/photo-1519003722824-194d4455a60c?auto=format&fit=crop&w=800&q=75" -o assets/img/truck.jpg
ls -la assets/img/   # 每张应 >30KB；肉眼 Read 验证内容正确（后厨/货架/卤面/货车）
```

- [ ] **Step 3: 生成设计 token（skill 强制路由）**

Invoke `ui-ux-pro-max`：`python scripts/search.py "B2B food industry AI SaaS landing warm human" --design-system --persist -p "Cretas官网V6" --variance 6 --motion 5 --density 4`。将输出与 spec §3 合并写入 `design-system/MASTER.md`（**spec §3 颜色/圆角/衔接手法优先于 skill 输出**）。

- [ ] **Step 4: 写 `platform/css/v6.css`**

完整实现（可直接落盘，后续页面只引用不改）：

```css
:root{
  --v6-bg-a:#fdfcfa; --v6-bg-b:#faf8f4; --v6-ink:#111; --v6-ink-2:#57534e;
  --v6-cta:#00b377; --v6-cta-ink:#00875c; --v6-tag:#00e28a; --v6-tag-bg:rgba(4,20,13,.5);
  --v6-r-hero:22px; --v6-r-card:16px; --v6-font:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;
  --v6-mono:ui-monospace,SFMono-Regular,Consolas,monospace;
}
*{box-sizing:border-box} body{margin:0;font-family:var(--v6-font);color:var(--v6-ink);
  background:linear-gradient(180deg,var(--v6-bg-a) 0%,var(--v6-bg-b) 100%);}
.v6-nav{display:flex;justify-content:space-between;align-items:center;padding:18px clamp(16px,4vw,48px);position:relative;z-index:3}
.v6-nav a{color:#333;text-decoration:none;font-size:14px;margin-left:22px}
.v6-nav a.active{color:var(--v6-cta-ink);font-weight:700}
.v6-btn{display:inline-block;background:var(--v6-cta);color:#fff;font-weight:800;
  padding:12px 28px;border-radius:99px;text-decoration:none;box-shadow:0 6px 18px rgba(0,179,119,.35);cursor:pointer}
.v6-btn-ghost{display:inline-block;background:#111;color:#fff;font-weight:700;padding:9px 20px;border-radius:99px;text-decoration:none}
.v6-hero{position:relative;margin:4px clamp(10px,2vw,20px) 0}
.v6-hero-mask{position:absolute;inset:0;border-radius:var(--v6-r-hero) var(--v6-r-hero) 0 0;overflow:hidden;
  -webkit-mask-image:linear-gradient(180deg,#000 0%,#000 74%,transparent 99%);
  mask-image:linear-gradient(180deg,#000 0%,#000 74%,transparent 99%)}
.v6-hero-mask img{width:100%;height:100%;object-fit:cover}
.v6-hero-scrim{position:absolute;inset:0;background:linear-gradient(90deg,rgba(12,10,8,.8) 0%,rgba(12,10,8,.42) 45%,rgba(12,10,8,.04) 75%)}
.v6-glow{position:absolute;left:0;right:0;pointer-events:none;
  background:radial-gradient(ellipse 75% 55% at 50% 42%,rgba(196,120,50,.16) 0%,rgba(196,120,50,.06) 45%,transparent 72%)}
.v6-ai-box{position:absolute;border:1.5px solid rgba(0,226,138,.9);border-radius:10px;box-shadow:0 0 24px rgba(0,226,138,.14)}
.v6-ai-tag{position:absolute;background:var(--v6-tag);color:#04140d;font-size:12px;font-weight:800;
  padding:4px 12px;border-radius:99px;font-family:var(--v6-mono);white-space:nowrap}
.v6-ai-tag--dark{background:var(--v6-tag-bg);color:var(--v6-tag);backdrop-filter:blur(4px)}
.v6-live-bar{position:absolute;left:10%;right:10%;bottom:-16px;background:rgba(255,255,255,.55);
  backdrop-filter:blur(14px) saturate(160%);border:1px solid rgba(255,255,255,.65);border-radius:99px;
  padding:10px 24px;display:flex;gap:26px;align-items:center;overflow:hidden;box-shadow:0 10px 30px rgba(120,80,40,.12)}
.v6-live-bar span{font-size:13px;color:#3a3630;white-space:nowrap}
.v6-live-dot{color:var(--v6-cta-ink);font-family:var(--v6-mono);font-weight:700}
.v6-photo-card{position:relative;border-radius:var(--v6-r-card);overflow:hidden;display:block;text-decoration:none}
.v6-photo-card img{width:100%;height:100%;object-fit:cover;display:block}
.v6-photo-card .shade{position:absolute;inset:0;background:linear-gradient(180deg,transparent 28%,rgba(16,12,8,.86))}
.v6-photo-card .title{position:absolute;bottom:12px;left:14px;right:12px;color:#fff}
.v6-photo-card .title b{font-size:17px;display:block}
.v6-photo-card .title small{font-size:12px;opacity:.78}
.v6-quote{font-size:clamp(20px,3vw,28px);font-weight:800;line-height:1.6}
.v6-quote small{font-size:14px;font-weight:400;color:#888;display:block;margin-top:8px}
.v6-section{padding:clamp(28px,6vw,72px) clamp(16px,4vw,48px)}
.v6-reveal{opacity:0;transform:translateY(14px);transition:opacity .5s ease-out,transform .5s cubic-bezier(.22,1,.36,1)}
.v6-reveal.in{opacity:1;transform:none}
@media (prefers-reduced-motion: reduce){
  .v6-reveal{opacity:1;transform:none;transition:none}
  .v6-live-bar{overflow-x:auto}
}
@media (max-width:768px){
  .v6-live-bar{position:static;margin:12px 14px 0;transform:none}
  .v6-hero{margin:0}
}
```

- [ ] **Step 5: 写 `platform/js/v6.js`**

```js
function v6RevealInit(){
  var els=document.querySelectorAll('.v6-reveal');
  if(!('IntersectionObserver' in window)){els.forEach(function(e){e.classList.add('in')});return}
  var io=new IntersectionObserver(function(es){es.forEach(function(en){
    if(en.isIntersecting){en.target.classList.add('in');io.unobserve(en.target)}})},{threshold:.12});
  els.forEach(function(e){io.observe(e)});
}
function v6LiveTicker(el,items){
  if(!el||!items.length)return; var i=0;
  if(window.matchMedia('(prefers-reduced-motion: reduce)').matches){el.textContent=items.join(' · ');return}
  setInterval(function(){i=(i+1)%items.length;el.style.opacity=0;
    setTimeout(function(){el.textContent=items[i];el.style.opacity=1},260)},4200);
}
document.addEventListener('DOMContentLoaded',v6RevealInit);
```

- [ ] **Step 6: 本地起服务验证 css/js 无 404、tokens 生效**

```bash
cd platform && python -m http.server 8899
# 浏览器(headed)开 http://localhost:8899/ 确认现有 index 未破坏；curl -s localhost:8899/css/v6.css | head -3
```

- [ ] **Step 7: Commit**

```bash
git add platform/assets/img platform/css/v6.css platform/js/v6.js design-system/MASTER.md
git commit -m "feat(website): V6 设计 tokens + 本地化摄影资产" -- platform/assets/img platform/css/v6.css platform/js/v6.js design-system/MASTER.md
```

---

### Task 1b: 产品截图采集与脱敏

**Files:**
- Create: `platform/assets/img/shots/dashboard.png`（餐饮驾驶舱 RestaurantV2Dashboard）
- Create: `platform/assets/img/shots/workflow.png`（工序/工作流编辑器画布）
- Create: `platform/assets/img/shots/logistics.png`（物流调度工作台+地图）
- Create: `platform/assets/img/shots/smartbi.png`（SmartBI 图表分析页）
- Create: `platform/assets/img/shots/receipt-rn.png`（RN 收货防呆屏，可复用仓库既有 `req1-dialog-open.png` 类截图）

**Interfaces:**
- Produces: 上述 5 个文件路径；Task 3-7 按名引用，套 `.v6-shot` 浏览器壳（Task 1 css 补 `.v6-shot{border-radius:12px;box-shadow:0 14px 34px rgba(60,60,60,.18);overflow:hidden}`）

- [ ] **Step 1: headed 浏览器登录 web-admin（demo 工厂账号），逐页截图** — 驾驶舱/工作流编辑器/物流工作台/SmartBI，1440×900 视口
- [ ] **Step 2: 脱敏处理（HARD）** — 逐张肉眼 Read 检查：工厂名/客户名/真实金额/联系人；有命中则切换 demo 数据重截或裁剪；禁止直接打码上真实数据
- [ ] **Step 3: 压缩落盘** `platform/assets/img/shots/`，单张 <300KB
- [ ] **Step 4: Commit** `git add platform/assets/img/shots platform/css/v6.css && git commit -m "feat(website): 产品截图资产(已脱敏) + v6-shot 壳" -- platform/assets/img/shots platform/css/v6.css`

---

### Task 2: 首页 index.html 重写

**Files:**
- Modify: `platform/index.html`（整文件重写，保留 `<head>` 内 SEO meta/百度统计等既有片段）

**Interfaces:**
- Consumes: Task 1 全部类名与 `v6RevealInit` `v6LiveTicker`
- Produces: 全站导航 HTML 片段（下列 Step 2 代码为唯一权威版本，Task 3-7 原样复制）

- [ ] **Step 1: invoke skill 链（强制）**

依次 invoke：`design`（创意展示页路由，输入 spec §3+§5.1）→ `impeccable`（craft，register=brand）。产出本页版式决定后再写码。

- [ ] **Step 2: 写导航（全站唯一权威片段）**

```html
<nav class="v6-nav">
  <a href="/" style="display:flex;align-items:center;gap:8px;margin:0;text-decoration:none">
    <svg width="18" height="18" viewBox="0 0 15 15" fill="none"><rect x="1" y="1" width="5.5" height="5.5" rx="1.5" fill="#111"/><rect x="8.5" y="1" width="5.5" height="5.5" rx="1.5" fill="#111"/><rect x="1" y="8.5" width="5.5" height="5.5" rx="1.5" fill="#111"/><rect x="8.5" y="8.5" width="5.5" height="5.5" rx="2.75" fill="#00b377"/></svg>
    <b style="color:#111;font-size:17px">白垩纪</b><small style="color:#999;letter-spacing:2px;font-size:11px">CRETAS</small>
  </a>
  <div>
    <a href="/solutions-factory.html">食品工厂</a>
    <a href="/solutions-restaurant.html">餐饮连锁</a>
    <a href="/solutions-logistics.html">物流配送</a>
    <a href="/solutions-ai.html">AI 中枢</a>
    <a href="/solutions-custom.html">定制开发</a>
    <a href="/demo.html">在线演示</a>
    <a class="v6-btn-ghost" href="mailto:stevenj4xie@gmail.com">预约演示</a>
  </div>
</nav>
```

- [ ] **Step 3: Hero 段（照片+标注+文案，文案为定稿）**

结构：`.v6-glow`（top:20px;height:420px）→ `.v6-hero`（height:min(78vh,640px)）内含 `.v6-hero-mask>img(hero-kitchen.jpg)` + `.v6-hero-scrim` + 两个 `.v6-ai-box`（标签文案：`操作员 · 装盘工序 98.2%`、`出品 ×3 · 已计数`）+ 文案块 + `.v6-live-bar`。

Hero 定稿文案：
- H1：`你的生意，AI 亲眼盯着。`
- 副文：`白垩纪把 AI 装进车间、后厨、货车和账本 — 看得见每道工序，算得清每一分钱。`
- CTA：`预约演示`（v6-btn）+ `3 分钟了解 Cretas →`
- LIVE 条 items（喂 `v6LiveTicker`）：`酱卤车间 · 第 3 道工序进行中` / `冷链 4 号车 · 装载率 87% · 准点` / `今日毛利 AI 已核算 · 异常 0`

- [ ] **Step 4: 四业务照片条 + 定制开发节 + 客户原话收尾**

- 照片条 grid `1.4fr 1fr 1fr 1.15fr`（≤768px 改 2 列）：工厂(warehouse.jpg，标注 `库位 A-12 · 猪前腿 300kg ✓`，题 `食品工厂/从一块原料到一张发票，AI 全程跟单`)、餐饮(noodles.jpg，`单品毛利 62% · 正常`，`餐饮连锁/每家店的账，AI 替你盯`)、物流(truck.jpg，`6 趟 → 5 趟 · 省 ¥430/天`，`物流配送/十辆车，一个人调`)、AI 中枢（深底对话卡：问 `"上个月哪个客户回款最慢？"` 答 `华东经销-B，平均 47 天。已生成催款清单，要发出吗？`，题 `AI 中枢/大白话问，直接办事`）。各卡 `box-shadow` 用照片同色系光晕（棕/深棕/蓝灰/墨绿，参考 spec §3）。
- 定制开发节：单行大字 `你的业务没有现成软件？我们连业务一起做。` + 副文 `进销存、配额治理、小程序商城、低代码表单 — 让产品适应工厂，而不是工厂适应产品。` + 链接 `solutions-custom.html`。
- 收尾 `.v6-quote`：`"你告诉他要收多少，就行了。"` + small `— 我们把系统做到仓管大叔零培训上手，这是客户教我们的。`

- [ ] **Step 5: headed 浏览器验证**

```bash
cd platform && python -m http.server 8899
```
Playwright/浏览器 headed 打开 `http://localhost:8899/index.html`，检查：照片溶解无硬边、LIVE 条轮播、375px 无横滚、控制台 0 报错、中文无方块。截图存 `test-screenshots/website-v6/index-{375,768,1440}.png`。

- [ ] **Step 6: invoke `impeccable` audit** — 修完 P0/P1 才过。

- [ ] **Step 7: Commit**

```bash
git add platform/index.html && git commit -m "feat(website): 首页 V6 重写 — AI之眼 hero + 四业务 + 定制开发" -- platform/index.html
```

---

### Task 3: solutions-factory.html（食品工厂，视觉 AI 重头）

**Files:** Modify: `platform/solutions-factory.html`（整文件重写）
**Interfaces:** Consumes Task 1 类名 + Task 2 导航片段（原样复制，factory 链接加 `class="active"`）

- [ ] **Step 1: invoke `design` + `impeccable` craft**（同 Task 2 Step 1 流程）
- [ ] **Step 2: Hero** — warehouse.jpg，H1 `从一块原料，到一张发票。`，副文 `没有固定产线也管得住 — 收货、生产、质检、出库、开票，每一步 AI 都在场。`，标注 `库位 A-12 · 批次 MB-0721-08 ✓`
- [ ] **Step 3: 七个场景模块**（spec §5.2 定稿骨架；每模块 = 场景痛点开场句 + 解法段 + 绿色 AI 标注点 + 真实截图 `.v6-shot`：模块2 用 `shots/workflow.png`、模块1 用 `shots/receipt-rn.png`，其余模块无截图则纯排版不留空框）：

| # | 模块标题（定稿） | 痛点开场（定稿） | AI 标注点 |
|---|---|---|---|
| 1 | 收多少，AI 说了算 | "仓管师傅年纪大，你不能指望他记住每张单。" | `下单 100 · 已收 30 · 还可入 70（含 30% 超收）` |
| 2 | 报工不用教 | "换个产品换套工序，老系统就废了。" | 可视化工作流编辑器 + 多工序良率报工 + NFC 打卡 |
| 3 | ⭐ 车间装上 AI 的眼睛（大段） | "你不可能盯着每个摄像头。" | 人效识别（工人数/干活空闲/动作计件/11 类工序自动识别/效率评分）、穿戴合规检测、异物检测、海康大华即插即用 |
| 4 | 出了事，30 秒追到人 | — | 批次全链溯源 + 消费者扫码公开溯源 |
| 5 | 配方是本清楚账 | "一个产品几十种料。" | BOM 版本/ECN 审批、一句话转 BOM、FIFO 推荐、过期低库存预警 |
| 6 | 质检长在流程里 | — | CCP 监控、拍照+语音质检、不合格处置闭环 |
| 7 | 下单到开票，一条线 | — | 销售→排产→出库批次分配→开票，冷链设备监控 |

- [ ] **Step 4: 差异化对比条**（定稿）：`传统 ERP 让工厂适应产品；Cretas 让产品适应工厂。` 三点：`零培训上手` / `没有固定产线也能管` / `AI 主动预警，不等你来查`
- [ ] **Step 5: 客户之声**：`"做仓管的年纪都比较大，最好的方法就是你告诉他要收多少就行了。" — 某食品工厂负责人`
- [ ] **Step 6: headed 验证 + `impeccable` audit**（同 Task 2 Step 5-6，截图 `factory-*.png`）
- [ ] **Step 7: Commit** `git add platform/solutions-factory.html && git commit -m "feat(website): 工厂页 V6 — 7场景+视觉AI重头" -- platform/solutions-factory.html`

---

### Task 4: solutions-restaurant.html（餐饮连锁）

**Files:** Modify: `platform/solutions-restaurant.html`（整文件重写）
**Interfaces:** Consumes Task 1 类名 + Task 2 导航（restaurant 加 active）

- [ ] **Step 1: invoke `design` + `impeccable` craft**
- [ ] **Step 2: Hero** — noodles.jpg，H1 `账不用你盯，AI 每天来汇报。`，副文 `营收、毛利、损耗、供应商 — 大白话问，答案直接给，异常主动找你。`，标注 `单品毛利 62% · 正常`
- [ ] **Step 3: 六个场景模块**（spec §5.3 定稿）：

| # | 模块标题 | 核心内容 |
|---|---|---|
| 1 | 每天早上 5 条信息 | AI 主动说今天干什么：巡哪几家店、A 店看什么、B 店差评多看什么（老板行动建议） |
| 2 | 营收跌了，AI 追到根 | 归因树演示：营收↓→客数还是客单→平日还是周末→新客老客→"每周一中午客人明显少"→给行动；配 `shots/dashboard.png` |
| 3 | 供应商的账，暗箱翻不了 | 价格异常即时到老板（"供应商临时加价，仓管不报你也知道"）、对账 |
| 4 | 单品毛利算得清 | 菜品成本/损耗/滞销、大白话问答、语音叫料 |
| 5 | 烂表格也能用 | "每家收银系统表头都不一样" → 任意 Excel 扔进来，AI 认表头、洗数据、出图表/预测；配 `shots/smartbi.png` |
| 6 | 你的数据在你手里 | 本地部署盒子、你的数据不喂平台、中立方定位 |

- [ ] **Step 4: 差异化条**：`平台既当裁判又当运动员；Cretas 只站在你这边。` 三点：`中立，不吃平台饭` / `归因到根，不止报表` / `数据放你自己手里`
- [ ] **Step 5: headed 验证 + `impeccable` audit**（截图 `restaurant-*.png`）
- [ ] **Step 6: Commit** `git add platform/solutions-restaurant.html && git commit -m "feat(website): 餐饮页 V6 — 6场景(录音提炼)" -- platform/solutions-restaurant.html`

---

### Task 5: solutions-logistics.html（物流配送，新增）

**Files:** Create: `platform/solutions-logistics.html`
**Interfaces:** Consumes Task 1 类名 + Task 2 导航（logistics 加 active）

- [ ] **Step 1: invoke `design` + `impeccable` craft**
- [ ] **Step 2: Hero** — truck.jpg，H1 `十辆车，一个人调。`，副文 `订单扔进来，AI 排线、装车、跟车 — 少跑一趟，就是省下真金白银。`，标注 `6 趟 → 5 趟 · 省 ¥430/天`
- [ ] **Step 3: 五个场景模块**（spec §5.4）：智能调度（派车派司机/按天运力，配 `shots/logistics.png`）、装载率优化（运力诊断）、地图路线与执行跟踪（多地图路由/冷链温层/执行状态独立跟踪）、任意 Excel 一键导入（表头智能匹配+地址智能纠错）、异常与准点。
- [ ] **Step 4: 差异化条**：`调度靠老师傅的经验；Cretas 把经验变成算法，还比他快。`
- [ ] **Step 5: headed 验证 + `impeccable` audit**（截图 `logistics-*.png`）
- [ ] **Step 6: Commit** `git add platform/solutions-logistics.html && git commit -m "feat(website): 物流页 V6 新增 — 5场景" -- platform/solutions-logistics.html`

---

### Task 6: solutions-ai.html（AI 中枢，新增）

**Files:** Create: `platform/solutions-ai.html`
**Interfaces:** Consumes Task 1 类名 + Task 2 导航（ai 加 active）

- [ ] **Step 1: invoke `design` + `impeccable` craft**
- [ ] **Step 2: Hero** — 深底（`#0d1210→#0a1a13` 渐变，不用照片）+ 对话演示卡（复用首页 AI 对话卡样式放大版），H1 `不是聊天框，是会干活的员工。`，副文 `600+ 项业务能力，问一句就动手 — 查得到、算得清、干得了。`
- [ ] **Step 3: 五段**（spec §5.5）：600+ 项能力/50+ 领域；看得懂（视觉）听得懂（语音）算得清（智能分析）；动手有分寸（写操作先预览确认、按角色限权）；主动吭声（过期/缺料/价格异常/目标预警）；越用越懂你（只写效果：`它记得你怎么问、怎么改，下次答得更准。`）。
- [ ] **Step 4: 差异化条**：`别家的 AI 负责聊天；Cretas 的 AI 负责上班。`
- [ ] **Step 5: headed 验证 + `impeccable` audit**（截图 `ai-*.png`）
- [ ] **Step 6: Commit** `git add platform/solutions-ai.html && git commit -m "feat(website): AI中枢页 V6 新增" -- platform/solutions-ai.html`

---

### Task 7: solutions-custom.html（定制开发轻页，新增）

**Files:** Create: `platform/solutions-custom.html`
**Interfaces:** Consumes Task 1 类名 + Task 2 导航（custom 加 active）

- [ ] **Step 1: invoke `design` + `impeccable` craft**
- [ ] **Step 2: Hero** — 无照片浅底，H1 `让产品适应工厂，而不是工厂适应产品。`，副文 `传统定制开发动辄数百万；Cretas 用细颗粒模块 + AI 编排，把定制做成快速、可负担的事。`
- [ ] **Step 3: 案例胶囊 5 枚**（全脱敏，只讲能力）：进销存一体（PSI）/ AI 用量配额治理 / 商城+小程序（含 AI 店铺装修）/ 低代码页面与表单编辑器 / AI 生成打印模板。每枚 = 名称 + 一句能力描述，⛔ 不出现客户信息。
- [ ] **Step 4: CTA**：`聊聊你的业务` → mailto。
- [ ] **Step 5: headed 验证 + `impeccable` audit**（截图 `custom-*.png`）
- [ ] **Step 6: Commit** `git add platform/solutions-custom.html && git commit -m "feat(website): 定制开发页 V6 新增" -- platform/solutions-custom.html`

---

### Task 8: 全站一致性 + 归档 + sitemap

**Files:**
- Modify: `platform/demo.html` `platform/privacy.html`（仅换导航片段为 Task 2 版本）
- Modify: `platform/index-v3.html` 等 5 个 v3 页（`<head>` 加 `<meta http-equiv="refresh" content="0;url=/">`）
- Modify: `platform/sitemap.xml`（新增 3 个 solutions 页，移除 v3 条目）

- [ ] **Step 1: 替换 demo/privacy 导航**；v3 页加 meta 跳转；更新 sitemap.xml（lastmod 2026-07-21）
- [ ] **Step 2: 全站点击遍历验证**：headed 浏览器从首页点全部导航链接，无 404、导航 active 态正确
- [ ] **Step 3: Commit** `git add platform/demo.html platform/privacy.html platform/*-v3.html platform/dashboard-v3.html platform/sitemap.xml && git commit -m "chore(website): 全站导航统一 + v3归档跳转 + sitemap" -- platform/demo.html platform/privacy.html platform/index-v3.html platform/ai-bi-v3.html platform/ai-calibration-v3.html platform/ai-scheduling-v3.html platform/dashboard-v3.html platform/sitemap.xml`

---

### Task 9: 终审 + 合并 + 部署（需 Steve 确认）

- [ ] **Step 1: 全站 `impeccable` audit（6 页跑一遍）** — AI slop test / 对比度 / 375px / reduced-motion / 脱敏复查（grep 真实客户名清单确认 0 命中）
- [ ] **Step 2: scope 检查 + PR**

```bash
git diff origin/main...HEAD --stat   # 应只有 platform/ + design-system/ + docs/
gh pr create --title "feat(website): 官网 V6 全面改版 — 五业务线 + AI之眼视觉" --body "spec: docs/superpowers/specs/2026-07-21-website-redesign-design.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

- [ ] **Step 3: ⏸ 等 Steve 审 PR** — merge 到 main 后才可部署
- [ ] **Step 4: 从 main 部署到 139（仅 showcase 路径）**

```bash
git checkout main && git pull origin main
rsync -avz --delete platform/ root@139.196.165.140:/www/wwwroot/showcase/cretaceousfuture/
# 部署后必验（exit 0 ≠ 上传成功）:
curl -s https://www.cretaceousfuture.com/ | grep -c "AI 亲眼盯着"   # 期望 ≥1
curl -s https://www.cretaceousfuture.com/solutions-logistics.html | grep -c "十辆车"  # 期望 ≥1
```

- [ ] **Step 5: 清理 worktree** `git worktree remove ../cretas-website-v6`
