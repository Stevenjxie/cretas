# 路演演示租户系统 — 设计 (Demo Roadshow Tenants)

**日期**: 2026-06-14
**作者**: Opus organizer + Steve
**状态**: 设计待评审 → writing-plans
**触发**: Steve 要一个扫码即看的免登录公开演示 URL，用于创业大赛路演。进去是一个数据丰满的 demo 账号，餐饮 + 工厂的数据/图表/AI 分析都漂亮展示，没用的模块藏掉。

---

## 1. 目标与约束

**目标**: 公开二维码 → 扫码 → 落地选择页（🏭 工厂演示 / 🍽️ 餐饮演示）→ 点一个 → 免登录进入该业态的数据丰满 demo 租户 → 经营驾驶舱。图表 + AI 分析现场就能跑出真实感结果，无关模块隐藏。

**已锁定决策**（brainstorming 阶段与 Steve 逐项拍板）:

| 决策 | 选择 |
|---|---|
| 入口形态 | `/demo` 选择页，两个入口（工厂 + 餐饮），无第三位 |
| 数据来源 | **克隆** F001(工厂) + qhj/RES_3101_009(餐饮) 到全新 demo 租户；**不动原租户** |
| 显示名 | 中性示范名：`DEMO_FACTORY` → "白垩纪示范食品厂"；`DEMO_REST` → "白垩纪示范餐厅" |
| 脱敏 | **只洗身份/PII**（客户名/供应商名/门店名/品牌名/人名/电话/地址/联系人 → 确定性假名）；营收/销量/成本等**数字原样保留**（图表最真实） |
| 后端只读锁 | **加**（公开扫码 + 现场比赛，防数据被改坏） |
| Gold 层 | 克隆操作层 + Silver 后，**用现有 ETL 重新生成 Gold/agg 表**（派生数据不克隆） |
| URL | 复用现有 `https://admin.cretaceousfuture.com/demo`（已 HTTPS、prod web-admin） |
| 时间线 | 2 周以上，可以做扎实 |

**非目标 (YAGNI)**: 不做平台级跨租户账号；不做第三业态；不做全新从零 mock（已确认数据丰满靠克隆现成的）。

---

## 2. 关键背景事实（探查确认）

**数据丰富度**（prod 实测，详见探查）:

| | DEMO_FACTORY ← F001 (FACTORY) | DEMO_REST ← qhj/RES_3101_009 (RESTAURANT) |
|---|---|---|
| sales_orders | 113 | — |
| production_plans / batches | 74 / 125 | — |
| material_batches | 125 | 32 |
| purchase_orders | 62 | 2 |
| 配方 product_types | 14 | 136 |
| customers | 49 | — |
| suppliers | 15 | 4 |
| smart_bi_finance_data (cretas_db) | 2,881 | 736 |
| fact_pos_transaction (smartbi) | 140,541 | **444,771** |
| agg_daily (smartbi) | 1,730 | 3,634 |
| agg_product (smartbi) | 2,998 | 4,973 |
| fact_restaurant_recipe_line | 2 | 383 |
| agg_factory_batch_daily | 180 | — |

两个原租户均为**无真实客户的 mock/测试租户**，但**含部分真实经营/品牌信息**（qhj=青花椒真实品牌；F001 有真实经营数字）→ 克隆必须脱敏身份。

**关键架构事实**:
- 一个租户 = 单一业态（`factories.type` ∈ FACTORY/RESTAURANT/HEADQUARTERS/BRANCH/CENTRAL_KITCHEN）。所以"同时展示餐饮+工厂"靠两个租户 + 选择页，不靠一个账号。
- 数据跨两库：`cretas_prod_db`（操作层/Bronze）+ `smartbi_prod_db`（Silver/Gold）。按 `factory_id` 分区，但 PK 全局唯一 → 直接复制会撞 PK。
- Gold/agg 表是 ETL 从操作层 + Silver 派生的（`backend/python/smartbi/gold/*_etl.py`，编排 `backend/python/scripts/gold_etl_daily_refresh.py`，systemd timer 每日刷新）。
- 模块显隐三层：`factoryType`（`hideForFactoryTypes` 自动隐藏跨业态模块）+ Canvas `disabled-modules`（per-factory 配置）+ role/permission。详见 `web-admin/src/components/layout/menuConfig.ts`、`AppSidebar.vue` `canSeeMenuItem()`、`store/modules/permission.ts`、`router/guards.ts`。
- 租户创建：`factories` 行 + `factory_settings`（1:1）+ 用户（bcrypt 密码）。NOT NULL 列见 §5.2。

---

## 3. 架构总览

```
扫码 → https://admin.cretaceousfuture.com/demo
          │
          ▼
   [选择页 DemoChooser.vue]  🏭 工厂演示   🍽️ 餐饮演示
          │ 点击                   │
          ▼                        ▼
   POST /auth/demo-login?tenant=factory   POST /auth/demo-login?tenant=rest
   (后端匿名端点, 发该 demo 租户 demo 账号的 token)
          │
          ▼
   存 token → router.replace('/dashboard') → 经营驾驶舱 (数据丰满)
          │
          ▼
   后续所有请求带 demo token → [只读锁 Filter] 拦写操作, 放行查询/AI
```

**五个组件**:
1. **克隆引擎**（`scripts/demo/clone_tenant.py`）— 跨库克隆 + 脱敏 + PK/FK 重映射；centerpiece。
2. **Demo 租户 provisioning** — 建 factory 行/settings/demo 用户/显示名；由克隆脚本承担。
3. **后端 demo-login 端点 + 只读锁 Filter** 🔒 — 匿名发 demo token + 拦写操作。
4. **前端 `/demo` 选择页 + 自动登录** — 升级现有单账号 Demo.vue 为选择页。
5. **模块策展 + 外观** — Canvas disabled-modules per 租户 + 显示名 + 演示模式 banner。

---

## 4. 组件 1：克隆引擎（核心）

**文件**: `scripts/demo/clone_tenant.py`（Python，asyncpg；与现有 gold ETL 同栈）。
**配置**: `scripts/demo/clone_config.py` — 表注册表 + 脱敏注册表。

### 4.1 克隆什么 / 不克隆什么

| 层 | 库 | 处理 |
|---|---|---|
| 操作层（master + 事务） | cretas_prod_db | **克隆 + 脱敏 + PK 重映射** |
| BI 源（Excel 导入的 smart_bi_*） | cretas_prod_db | **克隆**（数字保留，身份脱敏） |
| Silver（POS 导入，不可派生） | smartbi_prod_db | **克隆**（`fact_pos_*`、`dim_*`；脱敏 dim 名称） |
| Gold/agg（派生） | smartbi_prod_db | **不克隆，跑 ETL 重新生成** |

**克隆表清单**（cretas_prod_db，topological 父→子；实现时以实际 schema 为准核对 FK）:
`factories`（新行）→ `factory_settings` → `users`（**不克隆真实用户**，只建 demo 管理员，见 §5）→ `warehouses` → `suppliers` → `customers` → `raw_material_types` → `product_types` → `recipes`(+lines) → `material_batches` → `sales_orders`(+items) → `purchase_orders`(+items) → `production_plans` → `production_batches` → `finished_goods_batches` → `shipment_records` → `material_requisitions` → `wastage_records` → `stocktaking_records` → `return_orders`(+items) → `smart_bi_sales_data` / `smart_bi_finance_data` / `smart_bi_department_data`。

**克隆表清单**（smartbi_prod_db）:
`dim_store`（脱敏门店名）、`dim_product`、`dim_payment_method`、`dim_discount`、`dim_ingredient` → `fact_pos_transaction` → `fact_pos_item` / `fact_pos_payment` / `fact_pos_discount`。
（`agg_*`、`fact_restaurant_*`、`fact_production_batch` 由 ETL 重生成。）

> 实现首步必须 `\d <table>` 核对每张表的真实列名/PK/FK/NOT NULL，再落 registry。上面是 inventory，不是逐字 schema。

### 4.2 PK / FK 重映射

每张表在 registry 声明：`pk`（列名）、`pk_type`（bigint / uuid / business_string）、`fk_map`（{列名: 引用的父表}）、`factory_col`（默认 `factory_id`）。

算法（父表先于子表）:
1. 读源租户该表所有行（`WHERE factory_id = <source>`）。
2. 为每行分配新 PK：
   - **bigint**: `new = old + OFFSET`（每源租户固定大偏移，如 F001=+1e8、qhj=+2e8；实现前校验 `OFFSET > max(id)` 全库避免撞已存在行）。
   - **uuid**: 生成新 uuid，存 `old→new` 映射。
   - **business_string**（batch_number/order_number 等）：加 demo 前缀（如 `D-`）或重生成，存映射。
3. 改写 `factory_id` → demo 租户 ID。
4. 改写每个 `fk_map` 列：用父表的 `old→new` 映射查新值。
5. 应用脱敏（§4.3）。
6. 批量 INSERT。

**幂等/重置**: `--reset` 先 `DELETE ... WHERE factory_id = <demo>`（逆 topological 顺序删子表先）再克隆。给 demo "自愈" 能力（被改坏就重跑）。

### 4.3 脱敏层（只洗身份，数字保留）

`MASK_REGISTRY`: per 表声明哪些列脱敏 + 用哪个 masker。**确定性**（同源值→同假值），用稳定 hash(原值) 作 faker seed 或 memo dict，保证关系一致（"客户张三"在所有订单里一致变成同一个假名）。

| 字段类别 | 例（列） | masker |
|---|---|---|
| 人名/联系人 | `contact_person`、`operator_name`、用户 `real_name` | faker zh_CN 人名 |
| 公司/客户/供应商名 | `customers.name`、`suppliers.name` | faker 公司名 / curated 假名池 |
| 门店名 | `dim_store.store_name` | "示范门店01..NN" |
| 品牌名 | 任何含 "青花椒" 的文本 | 替换为 demo 品牌 "川渝示范" |
| 电话 | `phone`、`contact_phone` | 随机合规 CN 手机号 |
| 地址 | `address` | 通用假地址 |
| 邮箱 | `email` | 假邮箱 |
| 银行/税号（如有） | `bank_account`、`tax_id` | 假号 |
| **自由文本备注** | `remark`、`notes`、`description` | **扫描含真实名/品牌则置空或 scrub**（重点，易漏） |

**保留不动**: 所有金额/数量/成本/率/日期/状态/FK。→ 图表、KPI、AI 分析数字真实。

**脱敏审计**（验收必跑）: 克隆后对 demo 租户全表 grep 原始敏感 token（"青花椒"、真实客户名样本、真实电话样本）→ **必须 0 命中**。

### 4.4 Gold 重生成

克隆操作层 + Silver 完成后:
```bash
cd backend/python
python -m scripts.gold_etl_daily_refresh --factories DEMO_FACTORY,DEMO_REST
# 餐饮财务 ETL (POS→finance):
POST /api/smartbi/restaurant/etl/finance-etl/trigger {factoryId:DEMO_REST, startDate, endDate}
```
重生成 `agg_daily`/`agg_product`/`agg_channel`/`agg_restaurant_*`/`agg_factory_batch_daily`/`fact_production_batch`/`fact_restaurant_*` 等。

---

## 5. 组件 2：Demo 租户 provisioning（克隆脚本承担）

### 5.1 显示名 / ID
- `DEMO_FACTORY`（type=FACTORY）→ name "白垩纪示范食品厂"
- `DEMO_REST`（type=RESTAURANT）→ name "白垩纪示范餐厅"

### 5.2 必填列（实现以 prod schema 为准）
- `factories`: id, name(UNIQUE), type, is_active=true, manually_verified=true, ai_weekly_quota（给个充裕值如 9999 让 AI 演示不被限额）, created_at/updated_at。
- `factory_settings`: factory_id(UNIQUE FK), working_hours, 其余有默认。

### 5.3 Demo 管理员账号（**不克隆真实用户**）
- `demo_factory`（factory_id=DEMO_FACTORY, role=factory_super_admin, bcrypt 密码, is_active=true）
- `demo_rest`（factory_id=DEMO_REST, role=factory_super_admin, ...）
- 密码内部用（前端走 demo-login 端点不暴露）；仍设一个已知值便于排查。

---

## 6. 组件 3：后端 demo-login + 只读锁 🔒

> 红线（auth）。Opus 设计 + 终审，不外包自部署。

### 6.1 demo 租户标识
应用属性 `cretas.demo.factory-ids=DEMO_FACTORY,DEMO_REST`（无需改 schema）。Filter 与 demo-login 都读它。

### 6.2 `POST /api/mobile/auth/demo-login?tenant=factory|rest`（匿名）
- 校验 demo 功能开关 `cretas.demo.enabled=true`。
- `tenant` → 映射到配置的 demo 账号（factory→demo_factory、rest→demo_rest）。
- 发与 unified-login **同形** 的响应（token + 用户信息 + 设 HttpOnly cookie），token 内含 `demo=true` claim。
- 不接受任意账号 —— 只发配置死的两个 demo 账号。
- 基础限流（防滥用，非 MVP 阻塞项）。

### 6.3 只读锁 Filter（`OncePerRequestFilter`）
拦截逻辑：
```
if 请求的 factoryId ∈ demo-factory-ids (从 JWT/path 取):
    if method ∈ {POST,PUT,PATCH,DELETE} 且 path ∉ 只读允许清单:
        → 403 {success:false, message:"演示模式为只读，欢迎浏览全部数据 🙂"}
```
**只读允许清单（关键——本系统大量"读"是 POST）**: `/auth/demo-login`、`/auth/refresh`、`/auth/logout`、`/auth/me`、SmartBI 分析查询类 POST（`/smart-bi/**` 查询、`/api/smartbi/**` 分析）、AI 问答（`/{factoryId}/ai-intents/execute`、`/{factoryId}/smart-bi/query`、Python `/api/chat/**`、`/api/smartbi/nl-to-sql`）、dashboard executive/insights、文件预览。

> 允许清单需实现时按实际查询端点逐条核（探查已给主要清单）。原则：白名单只放"读取/分析"语义的 POST，其余写操作一律拦。

---

## 7. 组件 4：前端 `/demo` 选择页

升级已上线的单账号 `web-admin/src/views/demo/index.vue`:
- `/demo` → `DemoChooser.vue`：两张大卡（🏭 工厂演示 / 🍽️ 餐饮演示）+ 品牌化背景。
- 点击 → 调 `POST /auth/demo-login?tenant=...` → 存 token（复用 authStore 存储逻辑）→ `router.replace('/dashboard')`。
- 进入后页面顶部一条 subtle "演示模式 · 数据为脱敏示例" banner（提示 + 不喧宾夺主）。
- `requiresAuth:false` + guards 白名单（已加 `/demo`）。
- 失败有重试按钮（已有模式）。

---

## 8. 组件 5：模块策展 + 外观

- `factoryType` 自动隐藏跨业态模块（免费）：餐饮租户自动藏 生产/仓储/质量/设备/采购/HR/财务；工厂租户自动藏 餐饮运营等。
- 额外隐藏（系统/平台内部、薄模块）：per 租户写 Canvas `disabled-modules`（`PUT /{factoryId}/config/disabled-modules`，无代码改动）。每个 demo 租户的策展可见集在实现时用 headed 审查定稿（默认：保留 dashboard + 业态业务模块 + 数据与分析；隐藏 系统管理/权限/平台内部）。
- 显示名 §5.1；banner §7。

---

## 9. 构建顺序（Phases）

| Phase | 内容 | 模型/通道 | 🔒 |
|---|---|---|---|
| P1 | 克隆引擎 + provisioning + 脱敏；跑出 DEMO_FACTORY/DEMO_REST 数据 + ETL 重生成 Gold | Python，judgment 重（FK/脱敏）→ Opus keystone 或 Sonnet+Opus 终审 | 🔒 prod 数据 |
| P2 | demo-login 端点 + 只读锁 Filter + allowlist | Opus（auth 红线） | 🔒 auth |
| P3 | 前端 `/demo` 选择页 + 自动登录 + banner | Sonnet/Composer（可完整 brief） | |
| P4 | 模块策展（disabled-modules per 租户）+ 显示名 + cosmetics | Sonnet/Composer + headed 审查定稿 | |
| P5 | 部署（web-admin + backend）+ 双业态全 headed E2E 验收 | Opus 出货闸 | 🔒 prod 部署 |

**依赖**: P3 依赖 P2（端点）；P4 依赖 P1（租户存在）；P5 依赖全部。P1 与 P2 可并行。

---

## 10. 测试与验收

- **行数 parity**: demo 租户每表行数 == 源租户（克隆完整性）。
- **FK 完整性**: demo 租户无悬挂 FK（join 校验）。
- **脱敏审计**: demo 租户全表 grep 真实敏感 token（青花椒/真实客户名/电话样本）→ **0 命中**（硬门）。
- **数字保真**: demo 租户 agg_daily 营收总额 == 源租户（数字未被脱敏破坏）。
- **只读锁**: 对 demo token 发 PUT/DELETE → 403；发查询/AI POST → 200。对非 demo 租户写 → 正常（不误伤）。
- **Headed E2E**（per `playwright-headed-mode.md`，headless 禁用，zh-CN）:
  - `/demo` → 选择页两卡渲染。
  - 工厂演示 → 自动登录 DEMO_FACTORY → 经营驾驶舱图表 + KPI + AI 洞察渲染，生产/质量模块有数据，餐饮模块隐藏。
  - 餐饮演示 → 自动登录 DEMO_REST → 经营驾驶舱(营收/客单价/门店)图表 + AI 问答 + AI 经营体检渲染，生产/仓储模块隐藏。
  - 中文字体无方块；截图存证。
  - 尝试一个写操作（如新建）被只读锁友好拦截。

---

## 11. 风险与开放项

| 风险 | 缓解 |
|---|---|
| **PK/FK 重映射错** → 悬挂引用/数据错乱 | registry 以实际 schema 核对；topological 顺序；FK 完整性硬门；先在 test 库(cretas_db/smartbi_db)演练再上 prod |
| **脱敏漏字段**（尤其自由文本备注）→ 泄露真实信息 | 脱敏审计硬门（grep 0 命中）；free-text 列默认 scrub/置空 |
| **只读 allowlist 漏放查询 POST** → 演示中查询被误拦 | headed E2E 实跑所有要演示的查询/AI；allowlist 按探查清单逐条核 + 演示彩排 |
| **Gold ETL 对 demo 租户跑不出**（数据窗口/RLS GUC 事务坑，见 memory） | ETL 后校验 agg_* 行数 > 0；注意 asyncpg RLS GUC 必在事务内（[[feedback_asyncpg_rls_guc_must_be_in_transaction]]） |
| **OFFSET 撞已存在 PK** | 克隆前校验 OFFSET > 全库 max(id) |
| **prod 克隆误伤源租户/其他租户** | 脚本只 INSERT demo factory_id；`--reset` 只删 demo factory_id；先 test 库演练；prod 跑前 dry-run 打印计划 |
| **克隆体量**（餐饮 444K POS） | 批量 INSERT；可接受（一次性）；如慢则分批 |

**开放项**（实现时定，非阻塞）:
- demo 租户每业态的"策展可见模块集"精确清单 → P4 headed 审查定稿。
- 只读 allowlist 精确端点清单 → P2 实现时按 controller 核 + P5 彩排。
- 二维码生成 + 落地页是否要更品牌化（路演物料）→ 可选增强。

---

## 12. 分发卡（dispatch，per organizer-protocol）

| # | 任务 | 模型 | worktree 分支 | 🔒 |
|---|---|---|---|---|
| 1 | 克隆引擎 + 脱敏 + provisioning（P1） | Opus 设计 keystone + Sonnet 实现 + Opus 终审 | feat/demo-clone-engine | 🔒 prod 数据 |
| 2 | demo-login + 只读锁 Filter（P2） | Opus（auth 红线，自做或严格 brief + 终审） | feat/demo-readonly-auth | 🔒 auth |
| 3 | 前端选择页 + 自动登录 + banner（P3） | Sonnet/Composer | feat/demo-chooser-ui | |
| 4 | 模块策展 + 显示名（P4） | Sonnet/Composer + headed 定稿 | feat/demo-curation | |
| 5 | 部署 + 双业态 headed E2E 验收（P5） | Opus 出货闸 | (从 main 部署) | 🔒 prod 部署 |

隔离铁律：每任务独立 worktree off origin/main；prod 只从 main 部署；克隆/只读锁是 🔒 由 Opus 终审。**克隆先在 test 库（cretas_db/smartbi_db）演练通过再碰 prod。**

---

## 13. 关联
- [[project_2026_06_14_demo_nologin_url]]（已上线的 `/demo` 单账号 MVP，本设计升级它）
- [[fool-proof-design]]（只读锁友好提示遵循 4 位一体）
- [[feedback_asyncpg_rls_guc_must_be_in_transaction]]（ETL RLS 坑）
- [[feedback_worktree_main_only_deploy]] / [[concurrent-edit-safety]]（隔离/部署）
- `.claude/rules/python-services-architecture.md`、`server-operations.md`（ETL/部署/systemd）
