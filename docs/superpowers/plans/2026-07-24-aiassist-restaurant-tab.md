# AI Assist 餐饮 Tab + 学习模式 + /cysop/ 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** aiassist.cretaceousfuture.com 升级为「工厂 / 餐饮」双 tab 助手，餐饮 tab = 内容人员导览 RAG + 零 token 学习课程 + `/cysop/` 餐饮全链路 SOP 静态页。

**Architecture:** 前端单文件 `aiassist.html` 加 tab 状态机（per-tab 欢迎区/快捷问/会话存储，payload category 跟随 tab）；语料写成《餐饮全链路 SOP》HTML 挂 `/cysop/` 并 ingest 进 food_kb（subcategory=restaurant）；后端仅加"不做计算"护栏与 ingest 源。

**Tech Stack:** 原生 HTML/JS（无框架，沿用 Jul 23 版设计语言）、Python food_kb（pgvector RAG）、nginx（139 网关）。

**Spec:** `docs/superpowers/specs/2026-07-24-aiassist-restaurant-tab-design.md`

## Global Constraints

- 餐饮 tab 红线：只解释、不计算、不碰业务数据；涉及具体数据引导"去 SmartBI 餐饮 AI"
- 学习模式除「深入问一问」外 0 次 LLM 调用
- 工厂 tab 行为与 Jul 23 版完全一致（回归验收）
- 并发安全：所有 commit 用 `git commit -m "..." -- <paths>`（新文件先 add）；里程碑式频繁 commit
- 部署只从 main（worktree-and-main-only-deploy HARD rule）；Python 部署用 `deploy-smartbi-python.sh`，从干净 worktree 跑
- 服务器分布：静态文件（aiassist.html、cysop）只上 139；Python 只上 47

---

### Task 1: 服务器版 aiassist.html 回传基线 commit

**Files:**
- Modify: `web-admin/public/aiassist.html`（已从 139 scp 回，working tree 有 diff）

- [x] **Step 1:** 确认 diff 是 Jul 23 改版内容（工厂操作助手单页，1484 行）：`git diff --stat web-admin/public/aiassist.html` 预期 ~1454 insertions
- [x] **Step 2:** Commit：
```bash
git add web-admin/public/aiassist.html
git commit -m "chore(aiassist): 回传 139 服务器 Jul23 工厂操作助手改版作为基线" -- web-admin/public/aiassist.html
```

### Task 2: B 盘点 — 餐饮能力清单

**Files:**
- Create: `docs/plans/2026-07-24-restaurant-capability-inventory.md`

**Interfaces:**
- Produces: 三个清单章节（页面/板块清单、图表模板清单、AI 意图/诊断能力清单），供 Task 3 写 SOP 时对照防漏

- [x] **Step 1:** 扫描来源（只读）：
  - `web-admin/src/views/restaurant/` + `web-admin/src/router/index.ts`（restaurant 路由）→ 页面/板块清单
  - `backend/python/smartbi/knowledge/restaurant/diagnostics_registry.yaml` → 诊断能力清单
  - `backend/python/smartbi/api/restaurant_analytics.py` + `backend/python/smartbi/services/restaurant/analyzer.py` → 分析维度清单
  - 餐饮分层意图路由配置（`backend/python/smartbi` 内 T1 关键词表）→ 意图能力清单
  - 图表模板：`backend/python/smartbi/services/chart_builder.py` 中 restaurant 相关 chart type
- [x] **Step 2:** 汇成 markdown 清单（每项一行：名称 | 在哪 | 干什么），写入 inventory 文件
- [x] **Step 3:** Commit：`git add docs/plans/2026-07-24-restaurant-capability-inventory.md && git commit -m "docs(cysop): 餐饮能力盘点清单（B 方案辅助）" -- docs/plans/2026-07-24-restaurant-capability-inventory.md`

### Task 3: 《餐饮全链路 SOP》HTML（/cysop/ 内容 + RAG 语料）

**Files:**
- Create: `docs/manual/restaurant-full-chain-sop.html`

**Interfaces:**
- Produces: 单文件 HTML，`<h1>` 章节结构（parse_html_to_sections 按 h1/h2 切 chunk），Task 5 ingest、Task 6 部署 `/cysop/` 都用它

**内容结构**（每条目按固定模板：是什么 → 去哪看 → 怎么读 → 老板视角重点 → 典型结论示例）：
- §0 这份 SOP 是什么、给谁看（内容人员定位声明 + 红线：本助手不做计算，数据分析去 SmartBI 餐饮 AI）
- §1 系统导览层：餐饮板块地图（对照 Task 2 页面清单逐板块写"想看什么去哪"）
- §2 图表模板层：逐模板（4 象限/桑基/RFM/瀑布/趋势/对比…对照 Task 2 图表清单）写含义+读法+能得出什么
- §3 分析方法层：支持的维度分析地图（对照 Task 2 维度清单：毛利主轴/同比环比/反回扣/校准因子/异常检测/诊断引擎…）
- §4 餐饮业务认知层：
  - 老板在意什么：毛利、翻台、食材率、人力成本、现金流、客单价（素材：邓总详细分析v2 + 指标字典）
  - 大众点评口径：为什么从点评切入、头图/套餐/评价维护的规律性、"分析→市场对标→方案→一键执行"哲学、一次性 vs 持续性动作（素材：2026-07-11 转录 00:00-10:00 段落）
  - 业态差异：中餐/快餐/茶饮不可混比，先切人均 80-120 中餐（素材：转录 02:46-03:30）
  - 常见误区与问题清单
- 样式：复用 lsmsop index.html 的 CSS 骨架（纸质感 + 侧边目录），标题《白垩纪餐饮全链路 SOP》

- [x] **Step 1:** 精读素材提炼要点：`docs/customer/2026-06-03-邓总-餐饮需求-详细分析v2.md`、`2026-06-03-邓总-Cretas产品能力契合分析.md`、`docs/customer/2026-07-11-餐饮渠道交流-语音转录.txt`（重点 00:00-15:00 产品哲学段 + 全文扫痛点）
- [x] **Step 2:** 写 HTML（§0-§4 全部章节，无占位符）
- [x] **Step 3:** 本地校验：浏览器打开渲染正常；`python -c` 用 `food_kb.services.manual_ingester.parse_html_to_sections` 解析该文件，确认 section 数 ≥ 25 且每 section 有 title
- [x] **Step 4:** Commit：`git add docs/manual/restaurant-full-chain-sop.html && git commit -m "docs(cysop): 餐饮全链路 SOP v1（三层语料，模板化写法）" -- docs/manual/restaurant-full-chain-sop.html`

### Task 4: 前端 aiassist.html 双 tab + 学习模式

**Files:**
- Modify: `web-admin/public/aiassist.html`

**Interfaces:**
- Consumes: Task 1 基线；`POST /api/food-kb/manual-chat` 已支持 `category:'restaurant'`
- Produces: `state.tab ∈ {'factory','restaurant'}`；sessionStorage key `cretas_aiassist_tab`；会话存储 per-tab（factory 沿用 `cretas_factory_ai_session_v2`，restaurant 新增 `cretas_restaurant_ai_session_v1`）

**改动点（全部在单文件内）：**
1. Header 品牌区下加 tab 条：`<div class="tab-bar"><button data-tab="factory">工厂</button><button data-tab="restaurant">餐饮</button></div>`；激活态样式沿用 segment 按钮风格
2. `switchTab(tab)`：更新 `state.tab` + sessionStorage；切换 header 标题（工厂操作助手 / 餐饮导览助手）、副标题（餐饮版："解释板块、图表与分析逻辑，不做计算"）、welcome 内容、SOP 链接（factory→`/lsmsop/`，restaurant→`/cysop/`）、composer placeholder；保存/恢复各自会话消息列表
3. 餐饮 welcome：eyebrow「餐饮全链路导览」；标题「想了解哪一块？」；隐藏工厂的测试深度/业务线 scope-panel；快捷问 4 条：`我们支持哪些维度分析？` / `餐饮老板最看重什么指标？` / `大众点评的数据我们怎么用？` / `菜品 4 象限图怎么读？`；外加「📖 学习：餐饮分析入门」按钮
4. `sendQuestion` payload：`category: state.tab`；depth/business_line 仅 factory tab 附带
5. 学习模式：`LESSONS` 常量数组（6 课，每课 `{title, cards:[{heading, body}], asks:[{label, question}]}`，内容从 Task 3 §4 精简改写，纯静态）；点「学习」进入 overlay 卡片流（上一步/下一步/进度点/退出）；`asks` 按钮点击 = 退出 overlay + `sendQuestion(question)`（走 RAG）；除此之外零网络请求
6. 六课内容骨架（执行时从 Task 3 成稿抽）：①餐饮模块的目的与定位 ②老板视角核心指标 ③维度分析地图 ④图表怎么读 ⑤解决方案套路（分析→对标→方案→落地跟进；一次性 vs 持续性）⑥大众点评与外部数据口径

- [x] **Step 1:** 实现上述 1-5（含 CSS：tab-bar、learn overlay、lesson card，沿用现版设计 token）
- [x] **Step 2:** 本地验证：`python -m http.server 8099 -d web-admin/public` + Playwright/浏览器打开 `http://localhost:8099/aiassist.html`：
  - 默认工厂 tab，界面与基线一致
  - 切餐饮 tab → 标题/快捷问/SOP 链接变化；刷新后记住 tab
  - 学习模式 6 课可翻页、退出；network 面板确认学习翻页无请求
  - 发送问题（本地无后端会失败）→ DevTools 确认 request body `category:'restaurant'` 且无 depth 字段
- [x] **Step 3:** Commit：`git commit -m "feat(aiassist): 工厂/餐饮双 tab + 餐饮学习模式（零 token 预编排课程）" -- web-admin/public/aiassist.html`

### Task 5: 后端护栏 + ingest 源注册

**Files:**
- Modify: `backend/python/food_kb/api/manual_chat.py`（SYSTEM_PROMPT，~L179-274）
- Modify: `backend/python/food_kb/services/manual_ingester.py`（SOURCES，~L20-65）

- [x] **Step 1:** SYSTEM_PROMPT 增补一条规则（放在跨域识别规则旁）：
```
【禁止计算与数据分析】你不做任何数值计算、汇总、对比运算，也不分析用户的具体业务数据。
当用户要求"帮我算/汇总/分析一下(某数据)"时，回答：本助手只负责解释板块、图表与分析方法；
具体数据分析请前往 SmartBI 餐饮 AI（或工厂对应分析板块）提问，并告诉用户该去哪个板块、怎么问。
```
- [x] **Step 2:** SOURCES 加条目：
```python
{
    "path": "docs/manual/restaurant-full-chain-sop.html",
    "source": "restaurant-full-chain-sop.html",
    "subcategory": "restaurant",
},
```
（parser 用默认 `parse_html_to_sections`，与其他 html 源字段保持一致——照抄现有条目字段名）
- [x] **Step 3:** 本地跑相关单测（若 food_kb 有）：`cd backend/python && python -m pytest food_kb -x -q`；无测试则 `python -c "import food_kb.api.manual_chat, food_kb.services.manual_ingester"` 校验语法
- [x] **Step 4:** Commit：`git commit -m "feat(food-kb): 不做计算护栏 + 餐饮全链路 SOP ingest 源" -- backend/python/food_kb/api/manual_chat.py backend/python/food_kb/services/manual_ingester.py`

### Task 6: 部署（main → 139 静态 + nginx，47 Python + ingest）

**Interfaces:**
- Consumes: Task 1-5 全部 commit 在 main

- [x] **Step 1:** push main：`git push origin main`（若被并发 push 挡，`git pull --rebase origin main` 后重推）
- [x] **Step 2:** 静态上 139：
```bash
scp web-admin/public/aiassist.html root@139.196.165.140:/www/wwwroot/web-admin/aiassist.html
ssh root@139.196.165.140 "mkdir -p /www/wwwroot/cysop && chown www:www /www/wwwroot/cysop"
scp docs/manual/restaurant-full-chain-sop.html root@139.196.165.140:/www/wwwroot/cysop/index.html
ssh root@139.196.165.140 "chown www:www /www/wwwroot/cysop/index.html"
```
- [x] **Step 3:** nginx 加 `/cysop/`（139，编辑 `/www/server/panel/vhost/nginx/aiassist.cretaceousfuture.com.conf`，在 CODEX MANAGED LSM SOP 块后加同构块）：
```nginx
    # BEGIN CLAUDE MANAGED CY SOP
    location = /cysop { return 308 /cysop/; }
    location ^~ /cysop/ {
        root /www/wwwroot;
        index index.html;
        try_files $uri $uri/ =404;
        add_header Cache-Control "no-cache, no-store, must-revalidate" always;
        add_header Strict-Transport-Security "max-age=31536000" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header X-Frame-Options "SAMEORIGIN" always;
        add_header Referrer-Policy "no-referrer" always;
    }
    # END CLAUDE MANAGED CY SOP
```
改前备份 conf（`cp conf conf.bak.$(date +%Y%m%d_%H%M%S)`），`nginx -t` 通过再 `nginx -s reload`
- [x] **Step 4:** Python 上 47（从干净 worktree 部署，防主目录并发污染）：
```bash
git worktree add ../cretas-deploy-clean origin/main
cd ../cretas-deploy-clean && ./scripts/deploy/deploy-smartbi-python.sh --env prod
cd - && git worktree remove ../cretas-deploy-clean
```
- [x] **Step 5:** 47 上跑 ingest（SOP html 先 rsync 到 47 code 目录，再跑 manual_ingester）：
```bash
scp docs/manual/restaurant-full-chain-sop.html root@47.100.235.168:/www/wwwroot/cretas/code/docs/manual/
ssh root@47.100.235.168 "cd /www/wwwroot/cretas/code/backend/python && source venv38/bin/activate && python -m food_kb.services.manual_ingester 2>&1 | tail -20"
```
（ingester 是全量重刷；确认日志含 restaurant-full-chain-sop.html 的 chunk 数）

### Task 7: 线上验收（spec §8）

- [x] **Step 1:** `curl -s https://aiassist.cretaceousfuture.com/cysop/ -o /dev/null -w "%{http_code}"` → 200；`/lsmsop/` 仍 200
- [x] **Step 2:** 餐饮导览问答：
```bash
curl -s -X POST https://aiassist.cretaceousfuture.com/api/food-kb/manual-chat -H 'Content-Type: application/json' \
  -d '{"question":"翻台率是什么，去哪个板块看？","category":"restaurant"}' --max-time 60
```
预期：answer 含翻台率解释 + 板块指引
- [x] **Step 3:** 计算拒绝：question=`帮我算一下这个月的毛利率`，预期 answer 拒绝计算并引导去 SmartBI 餐饮 AI
- [x] **Step 4:** 工厂回归：question=`报工后成品还没进主仓，先查哪里？` category=factory，预期正常 SOP 回答（与改版前行为一致）
- [x] **Step 5:** 浏览器（headed）打开 https://aiassist.cretaceousfuture.com 走一遍：双 tab 切换、餐饮快捷问真答、学习模式 6 课翻页零请求、截图留档
- [x] **Step 6:** 更新 memory（project 条目）+ 如有新踩坑记 feedback

---

## 并行工作建议（fallback）
### Subagent: ✅ Task 2（盘点）与 Task 3 Step 1（素材精读）可并行；Task 3 成稿与 Task 4 前端可并行（学习课程文案依赖 Task 3 §4，最后填装）
### 多Chat: ❌ 不建议 — 单文件 aiassist.html 与部署链路集中，多 chat 反增并发覆盖风险
