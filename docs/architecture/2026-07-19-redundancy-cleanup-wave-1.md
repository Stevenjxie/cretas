# Cretas 冗余结构清理第一批：CV-01/PR-01 完成、SH-01 执行与 BS-01 设计

## 1. 决策与边界

- 日期：2026-07-19
- 初始 Base SHA：`a8e9d2c42ec1070ec84682b36e12012c76dc3565`
- 最终复核基线：`c2d70343a98226e01efe77c93645b562c806a1c5`
- PR-01 实施 Base SHA：`5d0fbdab88090178afe80d576cae32856a474d91`
- 已确认实施：CV-01 删除空历史表 `cost_variance_configs`
- 已确认实施：PR-01 删除旧产品配方双轨，BOM 成为唯一运行时真值
- 当前执行：SH-01 分成“冻结旧写链”和“冻结后清测试数据”两个顺序发布；BS-01 在 SH-01 后按已确认设计实施
- 明确保留：WF-01、SCH-01
- 数据授权：PR-01、SH-01、BS-01 相关生产记录均为测试数据，可按已审查范围删除；不得扩展到其他业务域
- 禁止：在旧写链 410 尚未部署时清空 `shipment_records`、保留静默 fallback、超出候选范围删除数据
- 代码真值：PR-01 分支创建时的 `origin/main`；发 PR 前必须重新 fetch 并检查 V79、ACTIVE 和 scope 冲突。

合并与生产部署是两个独立状态；每个状态分别验证和汇报。

## 2. CV-01：删除 `cost_variance_configs`

### 2.1 唯一真值

成本差异阈值唯一真值为：

- 表：`product_cost_variance_configs`
- Entity：`ProductCostVarianceConfig`
- Repository：`ProductCostVarianceConfigRepository`
- API：`/api/mobile/{factoryId}/bom/variance-configs`
- Web：`web-admin/src/views/system/cost-variance/index.vue`

`cost_variance_configs` 仅由
`V20261011_18__sp10_cost_variance_config_fallback.sql` 创建。该 migration
声称“若 SP3 已建则无操作”，但实际使用了不同表名、不同 ID 类型和不同阈值字段，
因此创建了第二张无消费者的表。

### 2.2 删除前生产证据

2026-07-19 对 `cretas_prod_db` 的只读复核：

| 项目 | 结果 |
| --- | ---: |
| `cost_variance_configs` 行数 | 0 |
| `product_cost_variance_configs` 行数 | 1 |
| 外部入站 FK | 0 |
| 外部出站 FK | 0 |
| 自身约束 | 2：主键、`factory_id/product_type_id` 唯一约束 |
| 索引 | 2：均由上述约束拥有 |
| 非内部触发器 | 0 |
| View 依赖 | 0 |
| Entity/Repository/Service/API 消费者 | 0 |

### 2.3 删除 SQL 预览

实际 Flyway 文件为
`V20261028_78__drop_redundant_cost_variance_configs.sql`。主线的 V76 冲突已由
PR #1463 修复，gateway ledger 固定为 V77，因此本清理使用下一版本 V78。

```sql
LOCK TABLE public.cost_variance_configs IN ACCESS EXCLUSIVE MODE;

DO $cv01$
BEGIN
    IF EXISTS (SELECT 1 FROM public.cost_variance_configs) THEN
        RAISE EXCEPTION
            'CV-01 blocked: cost_variance_configs is no longer empty; review and explicitly authorize its data before cleanup';
    END IF;
END
$cv01$;

DROP TABLE public.cost_variance_configs;
```

安全属性：

1. 先取得排他锁，关闭“检查为空后又被写入”的竞态窗口。
2. 部署时只要出现一行数据就失败，不自动删除或搬迁生产数据。
3. 不使用 `CASCADE`；若新增外键、View 或其他依赖，DROP 会失败。
4. 蓝绿部署中 migration 失败应保留旧 active 槽，不得强制切流。

### 2.4 回滚 SQL 预览

如果 migration 已执行但应用需要回滚，可先恢复旧版本应用；仅当确有旧消费者需要时，
再通过新的补偿 migration 重建原结构：

```sql
CREATE TABLE public.cost_variance_configs (
    id              VARCHAR(191)  NOT NULL PRIMARY KEY,
    factory_id      VARCHAR(191)  NOT NULL,
    product_type_id VARCHAR(191),
    threshold_pct   NUMERIC(5, 2) NOT NULL DEFAULT 10.00,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cvc_factory_product UNIQUE (factory_id, product_type_id)
);

COMMENT ON COLUMN public.cost_variance_configs.product_type_id IS 'NULL=全局默认';
COMMENT ON COLUMN public.cost_variance_configs.threshold_pct IS '超支百分比阈值 默认10%';
```

本次删除前行数为 0，因此没有待恢复的生产记录；部署前仍需再次查询，不能沿用本文快照。

## 3. PR-01：旧产品配方双轨删除

### 3.1 结论与运行时真值

- 唯一真值：`bom_recipes + bom_recipe_items + bom_seasoning_items`。
- 用户确认旧配方及其明细均为测试数据，不迁移到 BOM，允许直接删除。
- 近 14 天 Nginx gateway 日志中 `/product-recipes` 调用为 0；Web 仅剩未被引用的
  `productRecipe.ts`，RN、Python、AI Tool、定时任务、消息任务和导入导出均无消费者。
- 旧 `BomRecipeMigrationService` 只复制文本/价格，无法补现代物料与工序绑定；继续保留会重新制造
  “迁移到未绑定 BOM 明细”的错误入口，因此与旧 CRUD 一并删除。
- `ClerkProcessEntryServiceImpl` 改为只读当前 BOM。缺 BOM 或调料明细时返回显式 warning 并记成本 0，
  不再读取旧表，也不保留静默 fallback。

### 3.2 删除前生产证据

2026-07-19 11:15（UTC+8）对 `cretas_prod_db` 的只读快照：

| 项目 | 结果 |
| --- | --- |
| `product_recipes` | 2 行：均为 `DEMO_FACTORY / DF_pt10`；1 有效、1 软删除 |
| `recipe_ingredients` | 17 行：13 属于有效头、4 属于软删除头 |
| 允许的旧头 ID | `460add70-680f-4257-8f02-2d595e18c92b`、`53d9c92c-9c35-4989-bbb7-e400e1a4a5ca` |
| 入站 FK | 仅 `recipe_ingredients.recipe_id → product_recipes.id` |
| 其他入站 FK | 0 |
| View / 非内部触发器 | 0 / 0 |
| 主要索引 | 两表主键；头表 factory/product 索引和有效行唯一索引；明细 recipe 索引 |
| 近 14 天 gateway 消费者 | 0 |

删除影响仅为：旧 API 变为 404、旧测试配方不可恢复、没有当前 BOM 的 SKU 报工调料成本显式记 0 并提示配置。
现代 BOM CRUD、调料工作区、版本快照和按工序核算不受表删除影响。

### 3.3 删除顺序与 SQL 预览

代码/运行时顺序：

1. 删除旧 Controller、Service、DTO、Entity、Repository 和迁移入口。
2. 删除成本计算旧 overload 与文员报工 fallback；测试只使用 BOM 造数。
3. 删除孤立 Web API 和 `/production/product-recipes` 兼容跳转。
4. Flyway 获取两表排他锁并验证生产快照白名单。
5. 按子表 `recipe_ingredients`、父表 `product_recipes` 顺序删除。

实际文件：`V20261028_79__drop_legacy_product_recipes.sql`。

```sql
LOCK TABLE public.recipe_ingredients IN ACCESS EXCLUSIVE MODE;
LOCK TABLE public.product_recipes IN ACCESS EXCLUSIVE MODE;

DO $pr01$
DECLARE
    recipe_rows BIGINT;
    ingredient_rows BIGINT;
BEGIN
    SELECT COUNT(*) INTO recipe_rows FROM public.product_recipes;
    SELECT COUNT(*) INTO ingredient_rows FROM public.recipe_ingredients;

    IF recipe_rows > 2 OR ingredient_rows > 17 THEN
        RAISE EXCEPTION 'PR-01 blocked: legacy recipe rows changed after review (recipes=%, ingredients=%)',
            recipe_rows, ingredient_rows;
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.product_recipes
         WHERE factory_id IS DISTINCT FROM 'DEMO_FACTORY'
            OR product_type_id IS DISTINCT FROM 'DF_pt10'
            OR id NOT IN ('460add70-680f-4257-8f02-2d595e18c92b',
                          '53d9c92c-9c35-4989-bbb7-e400e1a4a5ca')
    ) THEN
        RAISE EXCEPTION 'PR-01 blocked: product_recipes contains data outside the authorized test snapshot';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.recipe_ingredients
         WHERE factory_id IS DISTINCT FROM 'DEMO_FACTORY'
            OR recipe_id NOT IN ('460add70-680f-4257-8f02-2d595e18c92b',
                                 '53d9c92c-9c35-4989-bbb7-e400e1a4a5ca')
    ) THEN
        RAISE EXCEPTION 'PR-01 blocked: recipe_ingredients contains data outside the authorized test snapshot';
    END IF;
END
$pr01$;

DROP TABLE public.recipe_ingredients;
DROP TABLE public.product_recipes;
```

安全属性：不使用 `CASCADE`，不执行单独 `DELETE`，任何新增工厂、ID、行数或依赖都会使迁移失败并阻止切流。

### 3.4 受影响 API 与页面

- 删除 `GET/POST /api/mobile/{factoryId}/product-recipes`。
- 删除 `GET/PUT/DELETE /api/mobile/{factoryId}/product-recipes/{id}`。
- 删除 `POST /api/mobile/{factoryId}/bom/recipes/migrate-from-product-recipes`。
- 删除 Web `src/api/productRecipe.ts` 和孤立路由 `/production/product-recipes`；用户继续使用
  `/production/bom` 及现有 BOM 调料工作区。
- 删除仍导航到旧 `ProductRecipeView` DOM 的孤立写入型脚本
  `tests/e2e-yield-mixed-sku/headed-seasoning-cost.mjs`；该脚本没有 runner/CI 消费者，且其自由文本调料、
  手填价格流程与当前“物料主数据 + 工序绑定”契约冲突，不能改个 URL 后继续使用。

### 3.5 验收与回滚

- 真实 JPA Context：`LegacyProductRecipeRemovalRepositoryQueryValidationTest`，断言 BOM Repository
  能启动且 metamodel 不含旧 Entity。
- SQL：`LegacyProductRecipesRemovalMigrationContractTest`，断言排他锁、白名单守卫、子表优先、无
  `CASCADE`/`DELETE FROM`。
- 计算/Service：`RecipeCostCalculatorTest`、`RecipeCostCalculatorBindingPotTest`、
  `ClerkProcessEntrySeasoningPerProcessTest`、`ClerkProcessEntryServiceImplTest`。
- 集成：`ClerkProcessEntryIntegrationTest`、`ProcessSheetServiceImplTest` 均只种 BOM 真值。
- 消费者：除历史 migration、归档文档和“旧视图不得恢复”的源码断言外，旧类名和
  `/product-recipes` 运行时引用为 0；Web 源码契约单测验证统一 BOM 路由仍存在且旧路由不再注册。

回滚必须使用新的补偿 migration 重建空旧表，再部署旧 JAR；不能只回滚 JAR，否则旧 Entity 会访问不存在的表。
被授权删除的 2+17 条测试数据不做恢复承诺。如部署前需要人工快照，可在迁移前导出两表；否则补偿 migration
只恢复结构。禁止在回滚时重新引入静默 fallback。

## 4. SH-01：旧出货写链冻结与测试数据清理

### 4.1 当前状态与迁移可行性

- 唯一业务写链目标：`sales_delivery_records/items/batch_allocations`，由仓库 confirm 扣库存。
- 旧 `ShipmentRecordService` 只保存 `shipment_records`/状态，不扣库存。
- 生产：旧表 64 行，其中有效 56；新 delivery 71、items 45、allocations 1。
- 旧表没有入站 FK；只向 customers/users/factories 出站，存在 1 个更新时间触发器，无 View 依赖。
- 旧表工厂分布：`DEMO_FACTORY=27`、`DEMO_FACTORY2=1`、`F001=27`、`F006=8`、
  `FOOD_3101_048=1`；用户已确认均可按测试数据删除。
- 但近 14 天 gateway 日志在 2026-07-18 仍有一次 `okhttp/4.9.2` 的旧 GET，说明已安装 Android
  客户端尚未完全退出旧读契约。数据可删除不等于 API/table 可立即删除。
- 56 条有效旧记录中：29 条有 `order_number`，3 条有 `batch_number`，56 条有产品名称。
- 按工厂、客户和单号匹配现有销售订单，仅 1 条有唯一匹配。
- 按工厂和产品名称匹配产品类型，17 条唯一匹配、1 条多匹配。
- 按工厂和批次号匹配成品批次，仅 1 条唯一匹配。

这些比例不足以安全地把旧记录机械转换成真实 delivery，更不能对历史 `shipped/delivered`
记录补扣库存。

### 4.2 冻结写实施（Phase A）

1. **消费者已迁移**
   - Web 两个出货页面已删除旧创建、发货、送达、取消操作，旧列表标记为只读；仓库正式 confirm 保留。
   - RN `shipmentApiClient` 已变为只读，旧包装、装车、发货确认页面与导航已删除。
   - 所有新写统一进入 sales delivery + warehouse confirm；不得在客户端本地模拟库存变化。
2. **后端 mutation gate 已实现**
   - 旧 POST、普通 PUT、status PUT、DELETE 统一抛出 `410 Gone`，错误码为
     `LEGACY_SHIPMENT_WRITE_GONE`，并返回正式新入口提示。
   - `ShipmentRecordService` 和 `ShipmentRecordRepository` 已无写调用；旧 payload 不做静默转换。
3. **AI 旧写链已移除**
   - 删除 8 个旧 mutation Tool、descriptor 和 RBAC/Skill 注册；Tool 清单由 601 收敛为 593。
   - `V20261028_80__freeze_legacy_shipment_ai_writes.sql` 将 `SHIPMENT_CREATE` 绑定到
     `sales_create_delivery`，禁用无法安全翻译的旧 mutation intent，并删除对应 embedding。
4. **保留历史读取**
   - 旧 GET/list/stats/tracking 暂时保留；测试数据清空后返回空结果。
   - Android 消费者升级并连续观察无旧 GET 后，才删除读 API、Entity/Repository 和表。
5. **不迁移旧测试记录**
   - 由于关联完整度不足且用户已确认全部为测试数据，不把 64 行伪造成 sales delivery，也不补扣库存。
6. **退出门禁**
   - 旧 mutation 网关调用连续观察为 0。
   - 新链扣库存幂等、批次分配和库存台账对账通过。
   - 历史查询/导出/食安追溯均有替代来源后，才讨论删除 `shipment_records`。

### 4.3 已授权测试数据清空 SQL 预览（Phase B，须在 Phase A 生产生效后执行）

必须先部署旧 mutation 410，再重新核对快照；清数据和最终删表分成两个 migration：

```sql
LOCK TABLE public.shipment_records IN ACCESS EXCLUSIVE MODE;

DO $sh01$
DECLARE
    legacy_rows BIGINT;
BEGIN
    SELECT COUNT(*) INTO legacy_rows FROM public.shipment_records;
    IF legacy_rows <> 64 THEN
        RAISE EXCEPTION 'SH-01 blocked: shipment snapshot changed after review (rows=%)', legacy_rows;
    END IF;
    IF EXISTS (
        SELECT 1 FROM public.shipment_records
         WHERE factory_id NOT IN ('DEMO_FACTORY', 'DEMO_FACTORY2', 'F001', 'F006', 'FOOD_3101_048')
    ) THEN
        RAISE EXCEPTION 'SH-01 blocked: unexpected factory data exists';
    END IF;
END
$sh01$;

DELETE FROM public.shipment_records;
```

该预览只清用户已授权的 64 条测试数据，不删除表；Phase A 切流后必须重新查询行数、工厂分布和
ID 校验摘要，并据最新冻结快照生成独立 migration。如果快照变化，迁移必须失败，不能扩大范围。
回滚仅能从部署前导出恢复测试数据；业务真值仍为 sales delivery，禁止恢复旧 mutation。

### 4.4 验收与回滚

- `ShipmentTraceabilityFlowTest`
- `SalesDeliveryHonorBatchAllocationTest`
- `SalesDeliveryBatchAllocationServiceWarehouseTest`
- 新增旧 mutation 410、仓库 confirm 幂等、库存只扣一次、AI Tool 不再注册旧 mutation 测试
- Web/RN 目标测试以及源码消费者清零检查
- 回滚优先恢复旧历史 GET；禁止无条件恢复不扣库存的旧写 API

## 5. BS-01：工序调味参数收敛设计

### 5.1 目标模型

- 熟制续锅比例唯一真值：`bom_seasoning_items.subsequent_pot_ratio`，随具体物料绑定。
- 注射绝对量唯一真值：每 recipe/work-process 一条注射配置。
- process 级 `subsequent_pot_ratio` fallback 最终删除。

当前生产 `bom_process_seasoning=0`；12 条工序级 COOKING 调味绑定均已有
`material_type_id`，不存在历史 unbound 行，item 比例目前均为 null。

### 5.2 推荐结构

推荐把 `bom_process_seasoning` 收敛为注射专用模型，而不是整表删除：

- 表最终命名：`bom_process_injection_configs`
- Entity：`BomProcessInjectionConfig`
- 字段：`factory_id`、`recipe_id`、`work_process_id`、`injection_amount_kg`、`notes`、审计字段
- 唯一约束：有效行 `(recipe_id, work_process_id)`
- 删除字段：process 级 `subsequent_pot_ratio`
- API DTO 拆分：binding DTO 管熟制比例；injection DTO 管绝对注射量

### 5.3 实施阶段

1. 部署前重查 process 参数和未绑定 seasoning item；任一非零异常都阻断自动收敛。
2. 若出现历史 process ratio，只能向该工序下明确绑定的每个 item 回填；多物料规则冲突时人工确认。
3. 修改保存、复制、版本快照和报工成本链，使熟制只读 item 比例。
4. 删除 `RecipeCostCalculator.computeBindingPotRules` 的 process ratio 参数和兼容分支。
5. 将旧表/Entity/Repository/DTO 改为 injection-only，并增加真实 JPA Context 测试。
6. 观察无旧 payload 后，再删除旧 DTO 字段和兼容反序列化。

回滚需保留 rename 前结构和字段快照；若已写入注射配置，回滚 migration 必须无损恢复
`injection_amount_kg`，不能通过清表回滚。

## 6. WF-01 与 SCH-01

- WF-01：保留 legacy `product_work_processes` 与版本化 Workflow 双模式；不修改。
- SCH-01：保留 APS `factory_scheduling_config` 与 cron/Tool
  `factory_scheduler_configs`；不修改。

## 7. 本批验收命令

```bash
cd backend/java/cretas-api
mvn "-Dtest=LegacyProductRecipesRemovalMigrationContractTest,LegacyProductRecipeRemovalRepositoryQueryValidationTest,RecipeCostCalculatorTest,RecipeCostCalculatorBindingPotTest,ClerkProcessEntryServiceImplTest,ClerkProcessEntryPlanStatusSyncTest,ClerkProcessEntrySeasoningPerProcessTest,LaborRateConfigTest,ClerkProcessEntryIntegrationTest,ProcessSheetServiceImplTest" test

cd ../../../web-admin
./node_modules/.bin/vitest run src/views/production/bom/seasoning/__tests__/BomSeasoningIntegration.source.spec.ts -t "keeps the canonical BOM auxiliary entry"
```

2026-07-19 本批验证时，完整 Web `vue-tsc -b` 仍被未改动的
`ProductProcessWorkflowEditor.vue` 既有类型错误阻塞；完整 seasoning source spec 还存在一个
`origin/main` 已有的过期文案断言。本次旧路由专项断言已独立通过，不能把上述基线噪声误报为 PR-01 回归。

补充只读门禁：

```bash
git grep -n -E "ProductRecipe|RecipeIngredient|ProductRecipeRepository|RecipeIngredientRepository|/product-recipes"
git diff --check
git status --short
```

生产部署前必须再次运行本文 2.2 的行数、外键、触发器和 View 依赖查询；本 PR
通过或合并不等于生产表已删除。
