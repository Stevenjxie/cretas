# Handoff — F006 成本/录入优化 (2026-06-25)

**客户**: F006 = 六膳门食品科技 (真客户, Steve 授权用 `f006_dept_admin` / `123456` 在 prod 测; 产品"叮咚好食光"系列, 猪舌 product id `4e345886-52e4-494a-bcb3-3f0ee9e126b2`)
**prod 状态**: main HEAD = `4fd90e9e1` (#1127)。下方所有 PR 全 live + headed 验过。test 环境停用 (部署只 `--env prod`)。

---

## 本轮 LIVE (全部已部署 + 验证)

| PR | 内容 |
|---|---|
| #1123 | 录入防呆: ProcessSheet name-mode 不过滤未匹配工序(位置默认 首道领料/其余普通) + 领料道空原料批次 sticky 红字提示 |
| #1124 | 🔒 人工没配工时单价**按0计**(去假默认26, resolveLaborRate orElseGet→ZERO) + 人数输入框加宽 + 调料无BOM容错验证 |
| #1125 | 成品出厂核算**完工批次选择器**(GET /production/batches/finished) + 计划列表"看成本核算"跳转 (M67YieldCost.vue, 不用手敲) |
| #1126 | hotfix: 批次选择 native SQL `(:productTypeId IS NULL)` PG 42P18 → 加 `CAST(:productTypeId AS text)` |
| #1127 | 🔒 **Phase 2(A) 核对结单自动预填**: GET /production-plans/{planId}/settlement-prefill → derive 报工数据(产量/工时/原料领用)+审计, 前端 dialog 预填, **一键确认仍人工**(扣库存逻辑0改动) |
| 数据 | F006 猪舌加"拆包"工序(order 0, RAW_MATERIAL=领料)+熟制=SEASONING+气调=PACKAGING → 转 role-mode, 7道全显示, 修油变普通 |

---

## 关键 context (下个 session 必知)

### 成本引擎 (OrderCostBreakdownService) — 已严格压测
- 上午跑了 8 场景矩阵(A极简→H最混地狱拓扑: 3链异价×2级diamond×交叉混锅×部分消耗×多处副产)全守恒, 抓修 5 cost bug(副产unitPrice/包装明细/Edge G混批feed=0/副产链传播/diamond路径作用域)。详见 memory `project_2026_06_25_cost_terminal_keying_deep_test`。
- 成本按需算(成品出厂核算页选批次→computeByBatch), 不预算。

### 客户成本口径需求 (录音转录)
- **先跑主料, 辅料/人工没配置算0, 不要默认, 不能 break**。
- 辅料**未来**用倒推算法: 出成率倒推 kg × 工序单价 ÷ 份数 = 每份分摊; 锚点投入或产出二选一; 比例固定。辅料不按工序统计只要总量。**本轮未做辅料, 客户明确说先跑主料。**

### Phase 2(A) 架构 (核对结单自动预填)
- settle 端点 `POST /{planId}/settle` (ProductionPlanServiceImpl.settleProduction ~line 1494) **真扣库存** (postConsumptionToInventory ~1932: 原料 used++/半成品 available--)。
- prefill 端点**只读**, derive 保守(不确定留空+audit BLOCKER), **人一键确认才触发现有 settle 扣库存**。
- derive: 产量←末道(max processOrder)产出汇总; rawMaterialConsumptions←各道 materialBatchRefs 聚合(校验 findByIdAndFactoryId+currentQuantity, 否则丢行+BLOCKER); semiFinishedConsumptions 默认空+INFO(WIP内部自耗); labor←各道工时段; variance≤5%自动/超产BLOCKER。

---

## 待办 (next session, 非阻塞, 都有人确认兜底)

1. **variance 单位精化**: 实际产量(盒) vs 计划数量 可能 盒/kg 单位不一致 → 误报超产 BLOCKER (实测 F006 plan 24a0954c: 实际4618 vs 计划1912)。人选原因即过, 安全但略烦。需查 ProductionPlan.plannedQuantity 单位 vs 末道产出单位, 对齐后再比。
2. **预填质量依赖报工有 materialBatchRefs**: 新 role-config 逐道录入有(原料能自动带入); 老计划无 → MATERIAL_CONSUMPTION_EMPTY BLOCKER 引导手工。可接受, 或考虑从别处补 derive。
3. (deferred) Phase 2 **B 方案**(气调完工全自动无人值守结单) — Steve 选了 A(预填+一键确认), B 需 derive 100% 可靠 + 确认无双扣才考虑, 当前不做。
4. (deferred) 辅料成本 — 客户说先跑主料, 辅料逻辑同主料(倒推算法), 后面加。

---

## 教训 (本轮新增 memory)
- `feedback_parallel_subagent_rebase_stale_main`: 并行 subagent 各 off origin/main, 后 merge 的 PR(off旧main)会 revert 先 merge 的; PR `--stat` 出现别人的文件=落后, merge 前必 rebase。本轮 #1125 险 revert #1124, rebase 救回。
- native SQL 的 `(:param IS NULL)` 在 PG 也炸 42P18 (database-entity-sync 规则适用 native, 用 `text` 非 JPQL `string`; H2 CI 漏报)。

详见 memory: `project_2026_06_25_f006_cost_optimization_and_picker` + `project_2026_06_25_cost_terminal_keying_deep_test`。
