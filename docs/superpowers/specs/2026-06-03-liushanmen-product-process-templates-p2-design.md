# 六扇门真实产品工序模板 (P2) 设计

**日期**: 2026-06-03
**触发**: 6.1-6.3 审核发现逐道报工用通用模板, 不符真实工序; 客户张权确认猪舌 defer, 其余 3 品先做。
**前置审核**: `docs/qa-audits/2026-06-03-liushanmen-6.1-6.3-field-process-conformance-audit.md`
**brainstorm 决策**: 幂等 seed 迁移 · 每产品独立 WorkProcess 定义(不改 schema) · 副产物本轮不加字段 · 工价 null。

---

## 1. 目标 & 范围

把 3 个产品(**猪舌 pilot / 牛腱 / 掌中宝**, 猪蹄 defer)的逐道报工工序从"通用模板"换成真实工序。3 个独立幂等 Flyway seed 迁移, 可并行(每产品一个 subagent)。只影响**未来批次**(spawnTasks 读 product_work_processes); 历史批次不变。

不含: 猪蹄(前半段混在一起客户会改)、kg↔盒精确换算(P4)、副产物预设字段(防呆 prefill, follow-up)。

---

## 2. 共享事实 (已核实, 3 个实现者都用)

**产品类型 ID (F006, prod 已有, 单位均=盒)**:
| 产品 | product_type_id | 迁移 |
|---|---|---|
| 猪舌 | `4e345886-52e4-494a-bcb3-3f0ee9e126b2` | V20260914_01 |
| 牛腱(纸片牛腱肉) | `c2974690-4ac7-4c17-9ad4-5ee5b12bb26c` | V20260914_02 |
| 掌中宝(椒麻掌中宝) | `1d7fbd73-8797-4933-83f1-46413a45992d` | V20260914_03 |

**表 NOT NULL 列**:
- `work_processes`: id(VARCHAR 手填), factory_id, process_name, unit, needs_input。其余可空: process_category, description, estimated_minutes, sort_order, is_active(默认true), standard_yield_min, standard_yield_max, output_unit, standard_hourly_rate。
- `product_work_processes`: id(BIGINT 自增, INSERT 省略走默认/序列), factory_id, product_type_id, work_process_id。其余: process_order, unit_override, estimated_minutes_override, is_active。
- `product_types`: id, created_at, updated_at, code, created_by, factory_id, is_active, name, unit (ON CONFLICT(id) DO NOTHING 时仅 test 缺才建; prod 已有跳过)。

**spawnTasks (WorkProcessTaskServiceImpl) 只 spawn `product_work_processes.is_active=true` 的行** → 停用旧工序 = `is_active=false` 即可阻止未来批次 spawn。

**猪舌旧通用工序** = `product_work_processes` id 44-53 (解冻→...→金检, 全 active), 停用方式: `UPDATE product_work_processes SET is_active=false WHERE factory_id='F006' AND product_type_id='4e345886...' AND is_active=true` (在 INSERT 新工序之前执行, 不误伤新工序)。牛腱/掌中宝若已有通用工序同样先停。

**Flyway 最新** = V20260913_01 → P2 用 V20260914_01/02/03 (PR 前 `git ls-tree origin/main db/flyway | grep V20260914` 复查防 sister 撞车)。

---

## 3. 每个迁移的结构 (幂等 ON CONFLICT DO NOTHING)

```sql
-- V20260914_0X__seed_<product>_processes.sql
-- 1. 保证产品类型存在 (prod 已有跳过; test 缺则建)
INSERT INTO product_types (id, factory_id, code, name, unit, is_active, created_by, created_at, updated_at)
VALUES ('<product_type_id>', 'F006', '<code>', '<name>', '盒', true, 1, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- 2. 停用该产品旧通用工序 (在建新工序前)
UPDATE product_work_processes SET is_active=false, updated_at=NOW()
WHERE factory_id='F006' AND product_type_id='<product_type_id>' AND is_active=true;

-- 3. 建产品专属 work_processes (每道一行, id 用 product 前缀)
INSERT INTO work_processes (id, factory_id, process_name, process_category, unit, output_unit, estimated_minutes, standard_yield_min, standard_yield_max, standard_hourly_rate, needs_input, is_active, sort_order)
VALUES
 ('WP-F006-<P>-01','F006','修油','前处理','kg','kg',NULL,0.85,0.95,NULL,true,true,1),
 ... 每道 ...
ON CONFLICT (id) DO NOTHING;

-- 4. 建 product_work_processes 链 (按真实 processOrder, is_active=true)
INSERT INTO product_work_processes (factory_id, product_type_id, work_process_id, process_order, is_active, created_at, updated_at)
SELECT 'F006','<product_type_id>', wp.id, <order>, true, NOW(), NOW()
FROM (VALUES ...) ... 
WHERE NOT EXISTS (SELECT 1 FROM product_work_processes x WHERE x.factory_id='F006' AND x.product_type_id='<pt>' AND x.work_process_id=...);
-- (product_work_processes 无 ON CONFLICT 友好唯一键名, 用 NOT EXISTS 防重入幂等; id 自增省略)
```

**幂等要点**: work_processes ON CONFLICT(id) DO NOTHING; product_work_processes 用 `WHERE NOT EXISTS` (唯一约束 factory+product+work_process); 旧工序停用是 UPDATE 幂等。Flyway 只跑一次, 但 fresh-DB/重置重跑也安全。

---

## 4. 真实工序链 + 出率 (实现者从 dump 取数, 见 `scripts/_liushanmen_dump.txt`)

**猪舌 (6 道, V20260914_01)** — Excel sheets 修油/滚揉/焯水/去舌苔/熟制/气调:
| 序 | 工序 | category | unit | outputUnit | yieldMin~Max | 备注 |
|---|---|---|---|---|---|---|
| 1 | 修油 | 前处理 | kg | kg | 0.85~0.95 | 产肥油(报工填) |
| 2 | 滚揉(保水) | 加工 | kg | kg | 1.25~1.40 | 保水>100% |
| 3 | 焯水 | 加工 | kg | kg | 0.80~0.95 | |
| 4 | 去舌苔 | 加工 | kg | kg | 0.80~0.90 | 产舌苔碎肉(报工填) |
| 5 | 熟制(卤制) | 加工 | kg | kg | 0.78~0.90 | |
| 6 | 气调(分切装盒) | 包装 | kg | 盒 | NULL~NULL | kg→盒跨单位, 出率不设 |

**牛腱 (5 道, V20260914_02)** — sheets 修油/滚揉/焯水/熟制/气调: 修油→滚揉→焯水→熟制→气调。yield 从 `纸皮牛肉（牛腱）.xlsx` 各 sheet 取真实区间; 气调 outputUnit=盒 yield NULL。

**掌中宝 (5 道, V20260914_03)** — sheets 水解化冻/焯水/油炸/熟制伴汁/气调: 水解化冻→焯水→油炸→熟制伴汁→气调。yield 从 `掌中宝.xlsx` 各 sheet 取真实区间; 气调 outputUnit=盒 yield NULL。

> yield 区间取法: 看该 sheet 实际出成率列的几行真实值, min 取略低于最低、max 取略高于最高(留余量给超收/A7告警, 不要卡太死)。保水道(滚揉)max 可 >1。气调跨单位 yield 留 NULL。standardHourlyRate 一律 NULL(Excel 无元/小时)。

---

## 5. 验证

- 部署后(test): 给每产品建一个测试生产计划→转批次→`spawnTasks` → 查 `work_process_tasks` 确认是真实工序链(猪舌 6 道 修油→...→气调, 非旧 10 步), 且旧 pwp 已 is_active=false。
- 报工某道(如修油)→ getYield 出成率用真实 standardYieldMax 区间。
- 不影响历史批次 1924(其 task 已 spawn)。

---

## 6. 实现单元 (3 并行 subagent, 各一产品)
1. **V20260914_01 猪舌**(pilot) — 6 道, 数值见 §4。
2. **V20260914_02 牛腱** — 读 dump 纸皮牛肉 sheets, 5 道。
3. **V20260914_03 掌中宝** — 读 dump 掌中宝 sheets, 5 道。

每个: 写迁移 SQL(§3 结构, §2 共享事实)→ 本地若可 `mvn -q compile`/Flyway dry 验证语法(或人工核对)→ 不 commit(orchestrator 统一 commit 避并发锁)。

P2 是 SQL seed 无 Java/单测; 正确性靠部署后 spawn 验证(§5)。
