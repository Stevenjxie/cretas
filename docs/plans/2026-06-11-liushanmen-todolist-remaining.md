# 六扇门 ERP 剩余 Todolist — 35 PR 后 (只列真没做)

**日期**: 2026-06-11 (本 session #684-716 全部上线 prod 后)
**基线**: `2026-06-11-liushanmen-todolist.md` (v1, 42 项) · 配套: `2026-06-11-liushanmen-matrix-5dim-v2.md`

> **一句话总览**: 35 PR 后整体就绪度 **~92%** (Tier0 16/16 + 集成断层 5/5 + Tier1 11/12 全部代码 done 并部署 prod 验证)。
> **剩余只有 3 类**: A. **缺 E2E 证据** (代码 done, 1 项) · B. **Tier2/P1 真没做** (11 项, 多需独立 spec/客户依赖/post-launch) · C. **数据配置依赖** (3 项, Friday 客户确认, 非代码)。
> **真正阻断首日上线的代码缺口 = 0**。

---

## A. 缺 E2E 证据 (代码 done, 仅证据维度未升满)

| # | 做什么 | 严重度 | 工作量 | 依赖 | mock-data 可跑? |
|---|---|---|---|---|---|
| **26** | **headed E2E + RN E2E 三条关键链截图存档**: ①报工双产出→批次详情双产出展示 ②盘点全链 ③撤回审批。RN 报工链 (领半成品 WIP→投入/产出报工) 用 e2e-native skill (Maestro) | MEDIUM | M | 需小米真机跑 RN; web 链可 headed Playwright | ⚠️ 部分: web 链 mock 数据可跑; RN 报工链需真机+真数据 (F006 工价配置后才能验成本回填闭环) |

> 唯一未做的本 session 项。**27 个 v1 标 UIUX 的功能点代码全在**, 只是零 headed/RN 截图证据。`web-admin/liushanmen-e2e.spec.ts` 是 Sprint9 旧 spec, 非本 session #26 — 需新写覆盖双产出/盘点/撤回三链。

---

## B. Tier2 / P1 真没做 (上线后迭代, 多需独立 spec / 客户 / post-launch 低优先)

| # | 做什么 | SP | 严重度 | 工作量 | 依赖 | 备注 |
|---|---|---|---|---|---|---|
| 29 | 销售方向付款审批 (PaymentRequest 加 salesOrderId 路径) | SP6 | HIGH→P1 | M | 独立 spec | D 流外, 转录明确但非 C 流闭环 |
| 30 | PaymentRequest 改接真 WorkflowEngine (当前硬编码状态机能用) | SP6+12 | MED | L | 客户要可配置审批流 | 现状能用, 非阻断 |
| 31 | 含税/不含税口径设计 (SP3 成本+SP11 凭证+进销存) | SP3+4+11 | HIGH→P1 | L | **独立 spec** | 金蝶导入正确性; SP4 已有半边 (#708) |
| 34 | 盐化仓独立扣量/报表端点 (SP7 F11) | SP7 | HIGH→P1 | M | — | 上线时盐化走通用仓+人工口径; 转录 4 次强调 |
| 35 | SP9 M4 工序达成率 + M5 看板图表 + step-breakdown 端点 + 供单/每日工作流建模 | SP9 | HIGH→P1 | L | — | 双口径基础已通 (#707); 达成率/图表/逐工序拆分是增强 |
| 36 | SP6 RN 出纳屏 (CashierTerminalScreen) + 异常决策屏 (PurchaseExceptionScreen); web detail.vue ×3 (exceptions/payment-requests/invoices) | SP6 | MED | L | — | 出纳/异常 web 已可用; RN+detail 页是补充触点 |
| 37 | 多 SO 合并公单 (打印模板 payload 支持多 SO) | SP5+12 | MED | M | 打印链已通 (#691/#703) | — |
| 38 | SP11 金蝶 per-movement 摘要模板 + Controller 层测试 (R2/幂等 HTTP 级) | SP11 | MED | M | — | 盘盈盘损分列已做 (#715); 摘要模板+Controller 测是补充 |
| 39 | SP3 双口径人工对比视图 + 成本组/公单级聚合 + 超支推送研发/销售 | SP3 | MED | L | — | §12 视图未建 |
| 40 | SP1 组合装嵌套 BOM 成本聚合 + 半成品"先做后用"场景建模 | SP1 | MED | L | **客户确认场景** | — |
| 41 | SP2 E2E Playwright spec (sp2-reversal 4 场景) + SP12 Sprint6 stub builder 替换 | SP2+12 | MED | M | (与 #26 部分重叠) | sp2-reversal.spec.ts 仍无文件 |
| 42 | 库存预警双向通知采购 / 账期到期自动提醒 / 发票回传销售 / 研发中试库 / 计件制存根 / 包材极简建档 | 多SP | LOW-MED | L | — | 杂项 nice-to-have 集合 |

> Tier2/P1 共 **11 项 (#29-42 减去已做的 #32/#33/#38 部分)**。
> 注: **#32 委外加工费** (#716 已做结构占位, 功能性追踪待 WorkProcess.is_outsourced 数据模型 = deferred); **#33 销售价≥预估价校验** (#714 已做, warn 范式); **#38 盘盈盘损分列** (#715 已做, 剩摘要模板+Controller 测)。

---

## C. 数据配置依赖 (Friday 客户确认, 非代码 — 不算 todolist 但阻断端到端验证)

| 项 | 内容 | 阻断什么 | 谁做 |
|---|---|---|---|
| C1 | **F006 工序免报清单** — 哪些中间工序算"中间"(两点报: 领料+成品/半成品产出, 中间免报)。代码能力已上线 (#690 reportingRequired 放 ProductWorkProcess, DEFAULT TRUE 回填), 但 prod F006 16 工序全 true (此刻仍逐道报) | 真"两点报"未生效 (能力 done, 配置未翻) | Steve/客户 (张权工序清单) |
| C2 | **F006 工价配置 + 报工审批** — standard_hourly_rate 全 null + 13 报工全 SUBMITTED 0 APPROVED | 成本链端到端 (#699 代码断点已修, 但要配工价+审批报工才真走通 = #12 成本链验证 + #26 RN E2E 成本回填) | Steve/客户 (配工价) |
| C3 | **半成品重量库存 vs 流水账** — SP1 半成品分类账设计 vs 客户"只做重量库存" | SP1 流水/库存口径 (设计升级合理, 需客户确认, 无代码改动, 补 spec 决策记录) | 客户确认 |

> ⚠️ C1/C2 直接影响 #26 (A 类) 和 #12 成本链验证能否真跑通端到端: 没配工价/审批报工, "报工 IN→unitCost→移动均价→回填→财审 breakdown→进销存金额" 链跑不出真数字 (代码断点 #699 已修, 数据是空的)。

---

## #18 等 spot-check 结论 (台账漏标核实)

| 项 | 台账标注 | spot-check origin/main 结论 |
|---|---|---|
| **#18 SP12 T5 报损 workflow REST** | 台账未单独明确标 done | ✅ **已做**: `WastageReportController` 含 POST /{id}/submit·/approve·/reject 三端点 (双轨校验 + 驳回); WastageReportService(Impl) 全在; 双实体归一拍板 WastageReport 为准 (DisposalRecord 旧 approve 仍在但不再是报损主路径)。**非缺口** — 由 #687 (枚举对齐) + WastageReport workflow 共同闭合 |
| #20 A3 LabelService 接线 | done | ✅ 实证: `LabelServiceImpl:242` 注释 + 调 primaryCode 前三位 (同源 SP8, 降级 MA) — 真接线 |
| #21 SP4 A8 ProductType 税换算 | done | ✅ 实证: `ProductTypeServiceImpl` + `ProductTypeTaxConversionTest` 在 main |
| #22 GrossMarginConfig CRUD | done | ✅ 实证: Controller+DTO×2+Entity+Repo+ControllerTest+web api 全在 |
| #26 headed E2E | 未做 | ✅ 确认未做: main 无本 session headed E2E spec/证据 doc (旧 spec 是 Sprint9-P1.2 May) |

**结论: 台账无漏标的真缺口。** #18 看似台账没明说, 实则 WastageReport workflow REST 端点确实存在于 main (#687 同批附带), 不是真缺口。

---

## 剩余工作量速算

| 类 | 项数 | 估工作量 | 阻断上线? |
|---|---|---|---|
| A. 缺 E2E 证据 (#26) | 1 | M (需真机) | ❌ 不阻断 (代码已 prod 验证) |
| B. Tier2/P1 真没做 | 11 | 多 L, 共 ~20-30 人天 | ❌ post-launch 迭代 |
| C. 数据配置 (非代码) | 3 | 客户侧 | ⚠️ 阻断端到端验证, 非阻断代码上线 |

**整体: 35 PR 后代码就绪度 ~92%, 剩 1 项 E2E 证据 + 11 项 P1 迭代 + 3 项客户数据配置。首日上线代码缺口 = 0。**
