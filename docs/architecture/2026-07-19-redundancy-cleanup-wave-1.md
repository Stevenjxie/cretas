# Cretas 冗余结构清理第一批：CV-01 实施与 PR-01/SH-01/BS-01 方案

## 1. 决策与边界

- 日期：2026-07-19
- 初始 Base SHA：`a8e9d2c42ec1070ec84682b36e12012c76dc3565`
- 最终复核基线：`c2d70343a98226e01efe77c93645b562c806a1c5`
- 已确认实施：CV-01 删除空历史表 `cost_variance_configs`
- 本批只设计、不修改运行时：PR-01、SH-01、BS-01
- 明确保留：WF-01、SCH-01
- 禁止：生产业务写入、生产 DDL、生产部署、静默 fallback、未经再次确认的生产数据迁移
- 代码真值：最新 `origin/main`。从首批审计基线 `86f40c30` 到本 Base SHA，相关候选文件没有代码变更。

合并与生产部署是两个独立状态。本批可以通过 PR 合入 migration，但没有获得生产部署授权。

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

## 3. PR-01：旧产品配方迁移与旧写链下线方案

### 3.1 当前状态与阻塞

- 唯一真值目标：`bom_recipes + bom_recipe_items + bom_seasoning_items`
- 旧链：`product_recipes + recipe_ingredients`、`ProductRecipeController`、
  `ProductRecipeServiceImpl`、`/product-recipes`
- 成本读取：`ClerkProcessEntryServiceImpl` 在当前 BOM/调味行缺失时仍读旧表。
- 生产仍有 1 条有效旧配方：`DEMO_FACTORY / DF_pt10`，13 条有效原料行，且没有当前 BOM。
- 13 条旧原料只有 2 条能按同工厂、规范化名称唯一匹配到 `raw_material_types`；
  其余 11 条没有精确物料匹配。
- `DF_pt10` 没有有效 `product_work_processes`，旧原料也不带 `work_process_id`。
- 现有 `BomRecipeMigrationService` 遇到无当前 BOM会 `SKIPPED_NO_BOM`；即使有 BOM，
  它也只复制文本和价格快照，不补 `material_type_id` 或 `work_process_id`。

因此，禁止直接执行现有实际迁移接口，也禁止把 13 行自动复制成现代“未绑定”调味行。

### 3.2 推荐迁移步骤

1. **只读 dry-run 报告**
   - 固定 factory/product/旧 recipe ID。
   - 输出 13 行原料的目标 `material_type_id`、目标工序、单位和价格来源。
   - 对无匹配、多匹配、缺工序的行全部标红；不得自动猜测。
2. **业务映射确认**
   - 先确定 `DF_pt10` 是否仍为有效 SKU。
   - 由业务确认 11 个缺失物料是新建原料、别名映射还是历史数据作废。
   - 为 INJECTION/COOKING 分别确认真实 `work_process_id`。
3. **创建当前 BOM**
   - 这是生产业务写入，必须另行确认。
   - 新 BOM 建立后先保持旧表不变，不开启双写。
4. **一次性导入并校验**
   - 每条新 `bom_seasoning_items` 必须有明确 `material_type_id` 和 `work_process_id`。
   - 对比首锅/续锅/注射成本、计价开关、剂量、价格快照与总成本。
   - 只有全量 parity 通过才允许进入下线阶段。
5. **冻结旧写**
   - `/product-recipes` 的 POST/PUT/DELETE 返回明确 `410 Gone` 业务错误及新入口提示。
   - 不把旧请求静默翻译到 BOM，因为旧 payload 缺少现代绑定字段。
   - 删除孤立的 `web-admin/src/api/productRecipe.ts`；路由继续显式跳到 BOM。
6. **删除 fallback**
   - `ClerkProcessEntryServiceImpl` 只读当前 BOM；缺配置时明确失败或返回可见配置错误，
     不再返回旧表成本。
7. **观察与最终删除**
   - 网关日志连续观察旧 API 无调用。
   - 再次提交旧表行数、依赖计数和导出文件摘要，经确认后按
     `recipe_ingredients → product_recipes` 顺序删除。

### 3.3 验收与回滚

- 真实 JPA Context：BOM Repository 和移除后的旧 Repository 扫描门禁。
- Service：`BomRecipeMigrationServiceTest`、`RecipeCostCalculatorBomParityTest`、
  `RecipeCostCalculatorBindingPotTest`、`ClerkProcessEntrySeasoningPerProcessTest`。
- API：旧 mutation 明确 410；新 BOM CRUD 和成本路径通过。
- 消费者：Web/RN/Python/AI Tool 全仓搜索不得保留旧写调用。
- 回滚：保留旧表只读导出和上一版 JAR；可以恢复旧读链，但不得默认恢复静默 fallback 或旧写链。

## 4. SH-01：旧出货写链冻结与历史迁移方案

### 4.1 当前状态与迁移可行性

- 唯一业务写链目标：`sales_delivery_records/items/batch_allocations`，由仓库 confirm 扣库存。
- 旧 `ShipmentRecordService` 只保存 `shipment_records`/状态，不扣库存。
- 生产：旧表 64 行，其中有效 56；新 delivery 71、items 45、allocations 1。
- 56 条有效旧记录中：29 条有 `order_number`，3 条有 `batch_number`，56 条有产品名称。
- 按工厂、客户和单号匹配现有销售订单，仅 1 条有唯一匹配。
- 按工厂和产品名称匹配产品类型，17 条唯一匹配、1 条多匹配。
- 按工厂和批次号匹配成品批次，仅 1 条唯一匹配。

这些比例不足以安全地把旧记录机械转换成真实 delivery，更不能对历史 `shipped/delivered`
记录补扣库存。

### 4.2 冻结写方案

1. **先迁移消费者，再封后端**
   - Web：`sales/shipments/list.vue`、`warehouse/shipments/list.vue` 的创建和状态操作。
   - RN：ShipmentManagement、仓库首页、装车、包装、发货确认等旧写入口。
   - AI：旧 shipment create/update/delete/status/cancel/complete/confirm/notify 类 Tool。
   - 所有新写统一进入 sales delivery + warehouse confirm；不得在客户端本地模拟库存变化。
2. **后端 mutation gate**
   - 旧 POST、普通 PUT、status PUT、DELETE 返回明确 `410 Gone`。
   - 响应包含新业务入口和所缺字段提示。
   - 禁止把旧 payload 静默转成新 delivery：它缺少可靠的 sales order、product type、
     finished-goods batch/allocation 主键。
3. **保留历史读取**
   - 旧 GET/list/stats/tracking 暂时保留，只读展示时标注 `LEGACY_MANUAL_SHIPMENT`。
   - 食安追溯和报表应同时读取新 delivery 与经确认的旧历史来源，不能混成同一种库存事实。
4. **历史分层处理**
   - A 类：订单、产品、批次均唯一匹配，人工复核后可迁移为“历史导入”记录；禁止触发库存扣减。
   - B 类：只有部分匹配，保留只读并建立人工映射清单。
   - C 类：无法匹配，进入历史归档表或只读 View，不伪造外键。
5. **退出门禁**
   - 旧 mutation 网关调用连续观察为 0。
   - 新链扣库存幂等、批次分配和库存台账对账通过。
   - 历史查询/导出/食安追溯均有替代来源后，才讨论删除 `shipment_records`。

### 4.3 验收与回滚

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
mvn "-Dtest=CostVarianceConfigRepositoryQueryValidationTest,CostVarianceServiceImplTest,CostVarianceServiceTest,CostVarianceConfigsRemovalMigrationContractTest" test
```

补充只读门禁：

```bash
git grep -n -I "cost_variance_configs"
git diff --check
git status --short
```

生产部署前必须再次运行本文 2.2 的行数、外键、触发器和 View 依赖查询；本 PR
通过或合并不等于生产表已删除。
