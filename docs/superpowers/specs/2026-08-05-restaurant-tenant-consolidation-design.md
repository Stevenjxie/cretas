# 餐饮租户收敛设计：只留 MOCK_REST

**日期**: 2026-08-05
**基准代码**: `origin/main` @ `37e9e2685ecd7dfb861cf33454ab23106e81d075`（所有行号锚点指此提交）
**生产实测**: 2026-08-05 14:00–15:00，只读查询（`smartbi_prod_db` + `cretas_db`），生产写入 0

---

## 0. 背景

系统里有 **38 个 `type='RESTAURANT'` 的租户**，其中 32 个完全没有数据。它们是过去几个月演示、上传测试、建租户重试留下的残骸，没有任何机制清理，也没有任何机制阻止再长出来。

目标：**餐饮板块只保留 `MOCK_REST` 一个租户可用，其余 37 个停用。**

拍板人 Steve，2026-08-05。关键前提：**当前没有付费餐饮客户**，所有租户都是演示/测试性质。

---

## 1. 现状实测（不用重查）

### 1.1 六个有数据的租户，其余 32 个全空

| 租户 | 名称 | 门店 | POS 单 | 最后 POS | daily_totals | daily_ops | 预订 | 损耗 |
|---|---|---|---|---|---|---|---|---|
| `RES_3101_009` | QHJ_PROD | 38 | 600,024 | 2026-08-04 | **8** | **39** | 16,270 | 10 |
| `DEMO_REST` | 白垩纪AI示范餐厅 | 27 | 510,200 | 2026-08-04 | **401** | **2,617** | 0 | 178 |
| `MOCK_REST` | 模拟平台餐饮租户 (假 POS 数据接入验证) | 10 | 186,766 | 2026-08-05 | 38 | 2,510 | 5,518 | 8,878 |
| `R_GML_DEMO` | 桂满陇 江浙菜 | 132 | 16,213 | 2026-01-15 | 0 | 0 | 0 | 0 |
| `R_XMX_CHAIN` | 唏嘛香·金城牛大 | 1 | 141 | 2026-02-15 | 8 | 48 | 0 | 4 |
| `F002` | 张记餐饮管理有限公司 | 0 | 0 | — | 12 | 75 | 0 | 14 |

其余 32 个：门店 0、POS 0、聚合 0。包括 `RES_3101_001`~`008`（QHJ 建租户的 8 次尝试）、唏嘛香 4 个副本（`R_XIMAXIANG_REAL` / `R_XMX_FRESH` / `FRESH2` / `FRESH3`）、桂满陇第二个 `RES_GML_001`，以及一批品牌空壳（`R_QINGHUAJIAO_REAL` 青花椒、`R_HONGDEJI_REAL` 鸿德记、`R_YUJIUJING_REAL` 御九井、`R_YONGHE_REAL` 永和豆浆等）。

> 查询方式（RLS 下 `dim_store` 的 `tenant_isolation` 策略是**严格相等**，无 `__internal__` 逃逸，且只有 `postgres` 角色 `rolbypassrls=t` 但其口令不在 `.env.prod`）：逐租户 `set app.factory_id='<id>'` 后写入 temp table，最后统一 select。

**两个反直觉的事实**：

- `RES_3101_009` 有 60 万 POS、38 门店，聚合只出 **8 行 totals / 39 行 ops**；POS 更少的 `DEMO_REST` 却有 **401 / 2617**。数据量最大的租户聚合链几乎没跑。
- `F002` 没门店没 POS，却挂着 12 行 totals / 75 行 ops / 14 行损耗——孤儿聚合行，来源不明。

### 1.2 `is_active` 是道真闸，但只在 AI 侧

`Factory.getIsActive()` 的**主代码**读取点：

| 位置 | 作用 |
|---|---|
| `ToolPrincipalPolicy.java:54` | AI Tool 网关拒绝非 active 工厂 |
| `FactoryBusinessTypeResolver.java:25` | businessDomain 解析过滤非 active |
| `FactoryCapabilityPackRoutingPolicy.java:64` | 能力包路由拒绝 |
| `AuthenticatedToolPrincipalFactory.java:27` | Tool principal 构造过滤 |

所以停用租户对 AI 路径是有效的，不是空动作。

### 1.3 但登录不检查 factory.isActive —— 只停租户会造出半死状态

`MobileAuthServiceImpl.java:127` / `:171` 只校验 `user.getIsActive()`，全文**没有一处**校验 factory 的 active 状态。

后果：**只停租户不停用户 = 用户能登录成功，进去后 AI 全部被拒。** 闸装在入口之后，用户走得到入口却走不到功能——这比直接拒绝登录更糟（用户会以为系统坏了）。

待停用的 19 个租户身上挂着 **45 个活跃用户**：

| 租户 | 活跃用户 |
|---|---|
| `DEMO_REST` | 9 |
| `RES_3101_009` | 8 |
| `F002` | 5 |
| `R_GML_DEMO` | 5 |
| `R_XMX_CHAIN` | 5 |
| `R_SSW_DEMO` | 2 |
| `RES_3101_005`~`008`、`RES_GML_001`、`R_PPT_DEMO`、`R_QINGHUAJIAO_REAL`、`R_XMX_FRESH`/`FRESH2`/`FRESH3`、`R_YHDJ_DEMO`、`R_YJJ_DEMO` | 各 1 |

**而 `MOCK_REST` 只有 1 个用户。**

### 1.4 ⚠️ Java 的餐饮租户判定是双轨的，`MOCK_REST` 只走得通一轨

五处硬编码的 ID 前缀判定，形状一致：

```java
factoryId.startsWith("RES_") || "DEMO_REST".equalsIgnoreCase(factoryId)
```

| 位置 |
|---|
| `IntentExecutionOrchestrator.java:1763` |
| `IntentExecutionOrchestrator.java:3511` |
| `DynamicToolSelectionService.java:230` |
| `AIIntentConfigController.java:548` |
| `SseStreamingService.java:1082`（另含 `REST_` 前缀） |

`MOCK_REST` 以 `MOCK_` 开头、不等于 `DEMO_REST`、不以 `RES_`/`REST_` 开头 —— **这五处全部判否**。

它今天能工作，靠的是另一轨：`factories.type='RESTAURANT'` → businessDomain 的兜底（`SseStreamingService.java:1072` 注释明写「a factory whose id doesn't match RES_/REST_/DEMO_REST but whose domain…」）。

**这是本次唯一的真风险点**：五处里只要有一处只有前缀判定、没有 domain 兜底，`MOCK_REST` 就会静默走进非餐饮分支——不报错，只是答得不对。必须逐处核实，不能假设兜底一定覆盖。

### 1.5 `DEMO_REST → RES_3101_009` 别名造成"一个账号两条路径读两个租户"

`GoldBackedRestaurantTool.java:385`：

```java
protected String resolveGoldFactoryId(String factoryId) {
    if ("DEMO_REST".equalsIgnoreCase(factoryId)) {
        // Public no-login restaurant demo account: use the complete QHJ-style
        // Gold dataset so AI demo questions have dish/revenue/review depth.
        return "RES_3101_009";
    }
    return factoryId;
}
```

而同文件 `:251-256` 的注释说明 **Phase 2 tiered delegate 故意不走这个别名**，Python restaurant-intent 直接用 `DEMO_REST` 自己 seeded 的 Gold 数据（`V20260706_01`）。

所以同一个演示账号：**Java Gold Tool 路径读 `RES_3101_009`（8 行 totals），Python tiered 路径读 `DEMO_REST`（401 行 totals）**。这也解释了 §1.1 那个反直觉现象。

两个租户都停用后，这条别名成为死代码，与这个分裂一起删除。

### 1.6 演示身份配置全部指向 `DEMO_REST`

`application.properties:111-113`：

```properties
cretas.demo.factory-ids=${CRETAS_DEMO_FACTORY_IDS:DEMO_REST,DEMO_FACTORY2,F_DEMO}
cretas.demo.rest.factory-id=${CRETAS_DEMO_REST_ID:DEMO_REST}
cretas.demo.rest.username=${CRETAS_DEMO_REST_USERNAME:demo_rest}
```

消费者：`DemoReadOnlyInterceptor.java:41`（演示只读写闸）、`IntentExecutionOrchestrator.java:245`（AI 确认执行阶段拦截 demo 租户真实写入）、`MobileAuthServiceImpl.java:59`。

**启动期没有任何校验确认这些配置指向的租户存在或 active**（`git grep "cretas.demo.rest"` 在 `src/main` 只命中 `@Value` 注入点）。所以停用 `DEMO_REST` 不会导致启动失败，但会让这三个配置指向一个停用租户——行为未定义。

### 1.7 其余待收敛的名单载体

- **每日审计** `cretas-restaurant-audit.service`：现跑 `MOCK_REST` / `R_GML_DEMO` / `RES_3101_009` 三租户（脚本 `restaurant_adversarial_audit.py:315` 的 `--factory` 默认值**已经是** `MOCK_REST`，三租户是 systemd 传参给的）。
- **8/5 演示流** `cretas-restaurant-demo-stream-20260805.{service,timer}` 与 `-qhj-` 版（指向 `RES_3101_009`）：两者均已安装但 `is-enabled=disabled`，8/5 09:00–14:00 窗口内 journal 零条目，**从未运行**。演示已结束，应撤除。
- **Python refresh 脚本白名单**：按租户硬编码，需核实实际清单。

---

## 2. 决策记录

| # | 决策 | 拍板 |
|---|---|---|
| 1 | 聚合含义 = **只留一个租户，其余下线**，不做数据物理迁移（不把桂满陇 132 店/DEMO_REST 27 店搬进主租户，避免跨品牌菜品价格混排） | Steve |
| 2 | 主租户 = **`MOCK_REST`** | Steve |
| 3 | 下线力度 = **只停用，不删任何数据**（`is_active=false` + 移出所有名单；POS/Silver/Gold 行原地保留，RLS 本就隔离），可随时回退 | Steve |
| 4 | **不做**防复发闸。理由：当前无客户，无人新建租户，闸防的是尚未发生的问题 | Steve |
| 5 | `DEMO_REST` 的**公开免登录演示入口一并下线**，以后演示一律账号登录。理由：符合"只留 MOCK_REST"字面意思，且方向是收掉一个公开无鉴权入口而非新开一个；反向随时可加回 | 默认假设，Steve 未反对 |

### 2.1 已知代价（明示，非遗漏）

- `MOCK_REST` 的数据是**模拟器生成的假数据**。停用 `RES_3101_009`（60 万单）与 `DEMO_REST`（51 万单）后，全系统只剩一个跑假数据的餐饮租户，失去"处理过真实门店数据"的现场证据。
- 换来的是：`MOCK_REST` 是**唯一一条端到端活着的链路**（139 假 POS 平台每分钟在喂 → 拉取 → Silver → Gold → 餐饮 AI），数据永远新鲜，今早审计 21/22 全场最高。其余租户都是历史上传的静态快照。
- **依赖**：`MOCK_REST` 的数据新鲜度依赖 139 上的 `cretas-mock-platform` 持续运行。它一停，唯一的餐饮租户就开始陈旧。本设计不改变这个依赖，但记录在案。

---

## 3. 范围

### 3.1 做什么

| # | 动作 |
|---|---|
| T1 | 停用 37 个租户 + 它们的 45 个活跃用户 |
| T2 | 演示身份三个配置改指 `MOCK_REST`（或明确停用演示身份，见 §4.2） |
| T3 | 删除 `DEMO_REST → RES_3101_009` 别名 |
| T4 | 逐处核实 5 处餐饮判定认不认 `MOCK_REST`，缺口补齐 |
| T5 | `MOCK_REST` 补角色账号 + 改对外可见的名字 |
| T6 | 撤 8/5 演示流 systemd unit；审计与 refresh 名单收敛到 `MOCK_REST` |

### 3.2 不做什么

- **不删任何数据**（POS / Silver / Gold / 预订 / 损耗行全部原地保留）。
- **不搬数据**（不做跨租户迁移）。
- **不加防复发闸**。
- **不碰飞轮**（`ai_promoted_routes` 只有 3 条 seed、0 条 flywheel、hit_count 全 0，是独立议题）。
- **不碰今早审计的 3 条真红项**（`BUSINESS_OPTIMIZATION` 被 narrative grounding 闸驳回、`CHANNEL_MIX` 外卖占比、`STAFFING_ADVICE` 下月人效）——下一轮。
- **不改 `is_active` 的语义或登录逻辑**（登录不查 factory.isActive 是既有设计，本次用"连用户一起停"绕开，不改认证路径）。

---

## 4. 详细设计

### 4.1 T1 — 停用租户与用户

**执行位置**：`cretas_db`（Java 侧），通过 Flyway migration，不手工 psql（per `server-operations` 硬规则）。

**清单来源**：`factories` 表 `type='RESTAURANT' AND id <> 'MOCK_REST'`，共 37 个。用户清单 `users` 表按 `factory_id` 关联。

**做法**：

```sql
-- 1. 停用租户（保留行，只翻状态）
UPDATE factories SET is_active = false
 WHERE type = 'RESTAURANT' AND id <> 'MOCK_REST' AND is_active = true;

-- 2. 停用这些租户下的用户（否则登录成功但 AI 全拒，见 §1.3）
UPDATE users SET is_active = false
 WHERE factory_id IN (SELECT id FROM factories WHERE type='RESTAURANT' AND id <> 'MOCK_REST')
   AND is_active = true;
```

**约束**：

- migration 必须 **fail-closed**：执行前断言 `MOCK_REST` 存在且 `type='RESTAURANT'`，否则中止（防止把所有餐饮租户都停了）。
- 必须**记台账**：停用前把受影响的 `(factory_id, user_id)` 写进一张回滚台账表，回滚脚本据此精确恢复——**不能**用"把所有 RESTAURANT 改回 true"回滚，那会把本来就该停的也打开。
  - ⚠️ 台账表的 `object_id` 列类型必须与 `users.id` / `factories.id` 的实际类型一致（`factories.id` 是 varchar，`users.id` 需核实）。类型不匹配时 INSERT 有隐式转换看不出问题，**只有回滚时的 `IN (SELECT object_id)` 比较才炸**——这个坑在 `V20261029_44` 踩过。
- **干跑必须连回滚一起跑往返**（prod `BEGIN … ROLLBACK`，用 migration 里的真实 SQL 字符串，不手抄）。

**验证**：停用后 `select count(*) from factories where type='RESTAURANT' and is_active=true` 恰好 = 1。

### 4.2 T2 — 演示身份

两条路，实施前二选一（取决于 T5 是否给 `MOCK_REST` 建演示账号）：

- **方案 (a)** 三个配置改指 `MOCK_REST`：`cretas.demo.rest.factory-id=MOCK_REST`、`.username` 改成 `MOCK_REST` 上的演示账号名、`cretas.demo.factory-ids` 里 `DEMO_REST` 换成 `MOCK_REST`。
  - ⚠️ `cretas.demo.factory-ids` 是**演示只读写闸**名单。把 `MOCK_REST` 放进去 = 它变成只读租户，**AI 无法对它做任何写操作**。若 `MOCK_REST` 需要保留写能力（例如 AI 改菜品、录损耗的演示），则不能放进这个名单。
- **方案 (b)** 演示身份整体停用：`cretas.demo.rest.factory-id` 留空并让消费者 fail-closed 跳过演示分支，`demo.factory-ids` 移除 `DEMO_REST`。

**决策依据**：§2 决策 5 已定"公开免登录入口下线"，倾向 **(b)**；但若仍需"演示租户不可写"这道保护，则走 (a) 且明确接受 `MOCK_REST` 只读。**此项在实施计划里必须先确认，不能默认。**

### 4.3 T3 — 删除 Gold 别名

删除 `GoldBackedRestaurantTool.resolveGoldFactoryId` 的 `DEMO_REST → RES_3101_009` 分支，方法退化为恒等返回（或整个删掉并让调用点直接用 `factoryId`）。

**断言必须钉住行为不是钉住字符**：新增测试断言"传入 `DEMO_REST` 返回 `DEMO_REST`"，且**变异验证**——把别名改回去，测试必须变红。

### 4.4 T4 — 餐饮判定核实（本次唯一真风险）

对 §1.4 的五处，逐处回答同一个问题：**`MOCK_REST` 走到这里会进哪个分支？**

方法：

1. 读每一处的完整判定链，确认前缀判否之后**是否**有 `businessDomain`/`factories.type` 兜底。
2. 对**没有**兜底的，补齐（优先复用已有的 domain 解析，不要再加一个 ID 前缀特例——那是把同一个闸的第六个承载点造出来）。
3. 每处补一条断言：`MOCK_REST` 被判定为餐饮租户。
4. **变异验证**：把兜底摘掉，断言必须变红。若摘掉后仍绿，说明测试打的不是这条路径。

⚠️ **不要**用"把 `MOCK_REST` 加进前缀白名单"来解决。那会让第 6 处、第 7 处继续复制这个模式。正确方向是让这五处收敛到 domain 判定。

### 4.5 T5 — `MOCK_REST` 升格

- **改名**：`factories.name` 从「模拟平台餐饮租户 (假 POS 数据接入验证)」改为对外可见的名字（具体名称由 Steve 定）。
- **补角色账号**：现有 1 个用户 `mock_rest`。需要补齐审计与角色工作台假设的角色覆盖（至少 `restaurant_manager`；8/3 那批角色工作台/排班功能涉及的其他角色需按实际功能清单确定）。
  - 账号创建走既有用户创建路径，不手工 INSERT。
  - 密码不进仓库，记入 `.claude/skills/server-operations/db-credentials.md`（gitignored）。

### 4.6 T6 — 名单收敛与 unit 撤除

- `cretas-restaurant-audit.service` 的传参改为只跑 `MOCK_REST`。
- 撤除 `cretas-restaurant-demo-stream-20260805.{service,timer}` 与 `-qhj-20260805.{service,timer}`（4 个 unit 文件 + 2 个 `.before-*` 备份），并从仓库 `scripts/systemd/` 与 `scripts/deploy/install-restaurant-demo-stream.sh` 一并移除。
- Python refresh 脚本白名单：先 grep 出实际清单再改（**按功能搜不要按前缀搜**——`refresh_*` 之外可能还有别的载体）。

---

## 4.7 实施前必须确认的三项（不得默认）

这三项是设计里**刻意留给 Steve 的决策**，实施计划的第一步就是拿到答案，不许实施者自行假设：

| # | 待确认 | 影响 |
|---|---|---|
| C1 | §4.2 走 (a) 还是 (b) —— 演示身份改指 `MOCK_REST`，还是整体停用 | 走 (a) 且放进 `demo.factory-ids` 会让 `MOCK_REST` 变只读，AI 无法演示任何写操作 |
| C2 | §4.5 `MOCK_REST` 对外显示的名字 | 纯命名，但会出现在演示界面上 |
| C3 | §4.5 除 `restaurant_manager` 外还需要哪些角色账号 | 决定 T5 工作量；应从 8/3 那批角色工作台/排班的实际功能清单反推，而不是凭印象列 |

---

## 5. 风险

| 风险 | 判据 / 缓解 |
|---|---|
| 五处餐饮判定有缺口，`MOCK_REST` 静默走错分支 | T4 逐处核实 + 变异验证。**这是必须做实的一项**，不能靠"审计还是 21/22"反推——审计只覆盖 22 个问句 |
| 停用后审计掉分 | 停用前后各跑一次审计对比，`MOCK_REST` 必须 ≥21/22 |
| 回滚台账 id 类型不匹配 | §4.1 已列，干跑必须跑回滚往返 |
| `MOCK_REST` 进了 demo 只读名单导致无法写 | §4.2 已列，实施前二选一必须确认 |
| 139 模拟器停摆导致唯一租户陈旧 | 本设计不解决，记录在 §2.1 |
| 有未发现的第 6 处名单载体 | grep 时**按功能搜不要按前缀搜**；实施后用"随便挑一个已停用租户登录"做反向验证 |

---

## 6. 验证判据

停用完成后，全部满足才算过：

1. `select count(*) from factories where type='RESTAURANT' and is_active=true` **= 1**，且该行是 `MOCK_REST`。
2. 每日审计跑 `MOCK_REST`：**≥ 21/22**（与停用前持平，不许掉分）。
3. `MOCK_REST` 各角色账号能登录，且 AI 能正常作答（至少覆盖 sales / dish / wastage / staffing 四类）。
4. 任取一个已停用租户的账号登录 → **被明确拒绝**，不是登进去半死。
5. 两个演示流 systemd unit 已不存在于服务器与仓库。
6. Java 目标测试 + 真实 JPA Context（碰 Entity/Repository 时）通过；T3/T4 的新增断言各自变异验证过（红 → 回退 → 绿）。
7. 生产 ERP 业务数据写入为 **0**（本次只翻 `is_active` 状态位与新增账号，不动业务行）。

---

## 7. 回滚

- **T1**：按台账表精确恢复受影响的 `(factory_id, user_id)` 的 `is_active`。禁止用"全部 RESTAURANT 改回 true"。
- **T2/T3/T4**：代码回滚（revert commit）。
- **T5**：新建账号停用；`factories.name` 按台账恢复原值。
- **T6**：systemd unit 重新安装（仓库里有 tracked 副本，revert 即可取回）。

数据零删除，故不存在不可逆的数据损失。

---

## 8. 后续（不在本轮）

1. **飞轮空转**：`ai_promoted_routes` 3 条 seed / 0 条 flywheel / hit_count 全 0，采集端 `smart_bi_llm_fallback_log` 7 天 2342 条仍在写，web-admin 五页审核台已就绪但**从未有人批准过一条候选**。「学过的问题以后自己答不再花钱」的承诺目前兑现度 0。
2. **审计 3 条真红项**：`BUSINESS_OPTIMIZATION`（narrative grounding 闸驳回 LLM 编的因果断言）、`CHANNEL_MIX` 外卖占比、`STAFFING_ADVICE` 下月人效（后者在两个租户上同型显形）。
3. **`RES_3101_009` 聚合链为何不跑**：60 万 POS 只出 8 行 totals。停用后此问题随之封存，若将来复用该租户需重查。
4. **`F002` 孤儿聚合行**：无门店无 POS 却有 12/75/14 行，来源不明。
