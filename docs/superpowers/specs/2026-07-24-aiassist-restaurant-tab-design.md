# AI Assist 餐饮 Tab（RAG 导览助手 + 学习模式）设计

**日期**: 2026-07-24
**状态**: 设计已与 Steve 逐节确认
**入口**: https://aiassist.cretaceousfuture.com

---

## 1. 背景与现状核实

| 组件 | 现状 |
|---|---|
| nginx (139) `aiassist.cretaceousfuture.com` | `/` → `aiassist.html`（Jul 23-24 Codex 改版，纯工厂操作助手，`category:'factory'` 写死）；`/lsmsop/` → F006 生产全链路手工测试 SOP 静态页；`/api/food-kb/` → 反代 47:8083 |
| 后端 `backend/python/food_kb/` | `POST /api/food-kb/manual-chat` 传统 RAG（pgvector + rerank + query 改写 + LLM 免费链），已支持 `category: 'restaurant'\|'factory'` 检索隔离、跨域拒绝、prompt 注入防护、LRU 1h 答案缓存。无意图系统、不做计算 |
| 餐饮语料（已入库，2026-04 底版本） | `restaurant-product-manual.html`（24 章）+ `restaurant-metrics-glossary.html`（指标字典），subcategory='restaurant' |
| 历史 | repo `web-admin/public/aiassist.html` 是旧版双卡选择页（餐饮版入口曾存在，Jul 23 改版时被移除）。**服务器版比 repo 新，动手前先拉回作基线** |

## 2. 目标与定位

- **用户**: 内部内容人员（可能不懂餐饮）
- **目的**: 导览 + 教学 —— 想看什么去哪个板块、图表什么意思、系统支持哪些维度分析、餐饮老板在意什么、我们对大众点评等外部数据的口径
- **红线**: 只解释、不计算、不做数据分析、不碰业务数据。涉及具体数据时引导"去 SmartBI 餐饮 AI 问 XXX"（不内嵌、不链接对话入口——内部用 A 方案）
- **token 控制**: 模板化语料（答案骨架预写进语料）+ 已有答案缓存 + 学习模式纯静态零 token

## 3. 前端（单文件 `aiassist.html` 双 tab）

1. 顶部加「工厂 / 餐饮」tab；工厂 tab 完全保留现状（F006 口径 + `/lsmsop/` 链接）
2. 餐饮 tab：
   - 调同一接口，`category: 'restaurant'`
   - 独立快捷提问（示例）："我们支持哪些维度分析？""餐饮老板最看重什么？""大众点评的数据我们怎么用？""菜品 4 象限图怎么读？"
   - 头部「完整 SOP」链接指向 `/cysop/`
   - **「学习」按钮** → 学习模式（见 §5）
3. tab 状态存 sessionStorage；样式沿用 Jul 23 新版设计语言

## 4. 语料：《餐饮全链路 SOP》HTML（大头）

对标 F006 lsmsop 形态，写一份权威 HTML 手册，三层知识、每条按固定模板（是什么 → 去哪看 → 怎么读 → 老板视角重点 → 典型结论长什么样）：

| 层 | 内容 | 主要素材 |
|---|---|---|
| 系统导览层 | 板块地图、想看什么去哪、图表模板清单与含义 | **B 辅助盘点**：扫餐饮 AI 图表模板/意图配置/驾驶舱页面导出能力清单（保证不漏项） |
| 分析方法层 | 支持的维度分析（多少维度、每维怎么分析、能得出什么） | 7 月餐饮 AI R1-R7 方法论沉淀（加权毛利率主轴、反回扣、校准因子等） |
| 餐饮业务认知层 | 老板在意什么（毛利/翻台/食材率/现金流）、大众点评口径与态度、业态差异、常见误区 | 邓总 4 件套（`docs/customer/2026-06-03-邓总-*`）+ `docs/customer/2026-07-11-餐饮渠道交流-语音转录.txt`（92min，点评切入/解决方案哲学/业态切分/持续性动作） |

完成后 ingest 进 food_kb（subcategory='restaurant'），替换 4 月过时章节；同时挂 `/cysop/` 静态页供直接阅读。

## 5. 学习模式（预编排课程，零 token）

纯静态分步卡片 + 「下一步」，暂定 6-8 课：

1. 餐饮模块存在的目的与重要性（AI 切入餐饮为什么慢、我们的定位）
2. 老板视角核心指标（毛利/翻台/食材率/现金流/客单价）
3. 系统支持的维度分析地图
4. 图表模板怎么读（4 象限、桑基、RFM、瀑布…）
5. 常见问题与解决方案套路（分析 → 市场对标 → 方案 → 落地跟进；一次性 vs 持续性动作）
6. 外部数据口径（大众点评：头图/套餐/评价维护的规律性、我们的态度）

每课末尾 2-3 个「深入问一问」预设按钮，点击才走 RAG。课程内容从 §4 认知层素材提炼。

## 6. 后端改动（极小）

- system prompt 增加护栏："不做计算/不做数据分析，涉及具体数据引导去 SmartBI 餐饮 AI"
- 新语料 ingest（`manual_ingester.py` SOURCES 加一条 + 重跑）
- 接口、路由、缓存均复用现有

## 7. 部署

1. **先把服务器 `/www/wwwroot/web-admin/aiassist.html`（Jul 23-24 版）拉回 repo 作基线**（并发安全）
2. nginx aiassist conf 加 `/cysop/` location（复制 lsmsop 段）；`/www/wwwroot/cysop/index.html` 放《餐饮全链路 SOP》
3. `aiassist.html` 推 139 web-admin；Python 侧 ingest 在 47 上跑

## 8. 验收

- 餐饮 tab 问"翻台率是什么/去哪看" → 按模板结构回答，含板块路径
- 餐饮 tab 问"帮我算一下毛利" → 拒绝计算并引导去餐饮 AI
- 工厂 tab 行为与改版前完全一致
- 学习模式全程 0 次 LLM 调用（除"深入问一问"）
- `/cysop/` 可访问，餐饮 tab 头部链接正确

## 9. 素材清单

- `docs/customer/2026-06-03-邓总-餐饮需求-语音转录.md` + 详细分析v2 + brainstorm + 产品能力契合分析
- `docs/customer/2026-07-11-餐饮渠道交流-语音转录.txt`（本次从 session scratchpad 抢救存档）
- `docs/plans/restaurant-metrics-glossary.html`（复用）
- `docs/plans/restaurant-product-manual.html`（部分复用，需刷新）
- B 盘点产物：餐饮 AI 图表模板/意图/页面能力清单（实施时生成）
