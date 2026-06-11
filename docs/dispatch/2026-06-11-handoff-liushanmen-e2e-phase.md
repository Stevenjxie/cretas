# 六扇门验证战役 — E2E 阶段交接（新 chat 接棒，自包含）

**日期**: 2026-06-11
**接棒方式**: 新 chat invoke `organizer` skill + 读本 doc。零上下文损失。

---

## 一句话状态
六扇门 6.9 转录需求**实现侧实质穷尽**（18 PR #754-771 全 merged + 全栈部署 prod），转录逐行扫确认 **0 实质遗漏**。当前在**端到端验证阶段** —— Codex 跑 headed E2E（handoff: `docs/dispatch/2026-06-11-codex-e2e-fullflow-handoff.md`）。新 chat 任务 = gate Codex 回来的结果 + 修 bug（🔒 红线回 main 终审部署）。

---

## 已完成（本轮 18 PR，全 merged + 部署 prod）
| 类 | PR | 内容 |
|---|---|---|
| deferred 10 项 | #754-763 | 16位码/WorkflowEngine/同单续报/中试库/调拨差异/包材规格入配比/票务字段/财务联动/多SO合并/包材收尾 |
| 长尾 6 项 | #764-766 | 双单号PDF+调拨指示单(silent-drop修)/气调货标称vs实收(V20261024_01)/三层价格同屏+含税未税双值;报损双实体证伪(DisposalRecord=报废≠WastageReport报损,非死代码,只加Javadoc) |
| 测试缺口 | #767-768 | SettlementType(11)/研发域(65: SampleApprovedEventListener+RD AI工具+RdController+R11) |
| UI最后一公里 | #769 | P8关联客户控件/价位选料面板/BOM packQtyPerProduct/销售付款菜单(404根因=menuConfig缺项) |
| **多段成本** | #770 | 两点报工成本核心:段=一次两点报工转化,每段料+人工+制费,半成品unitCost逐段滚,人工"登下一期"诚实null。端点 `/orders/{orderId}/multi-stage-cost` |
| **跨路径3断点修** | #771 | Fable流程审揪的财务正确性:①多SO合并→成本回填遍历sourceOrderIds ②撤回→清costUnitPrice自愈 ③采购自动级联→recipe-first(三路同源) |

**Flyway 最高**: V20261024_01（气调货）。全唯一无撞。

---

## 转录覆盖结论（逐行扫，不是声称）
4 路逐行扫 2 份转录（39min + 116min，5360行）= **0 实质遗漏**。catalog 逐时间戳穷尽（连口语噪音都标）。所有可执行需求 = 已实现 / 客户拍板defer / 客户自己没定义的ambiguity。唯一ambiguity（每段每盒人工成本）= #770 已实现。

**盘点**（FactoryStocktake状态机+财务审批门+盘盈盘损,角色守卫bug已修W2）/ **复盘**（三价对比+成本拆分+进销存,解决客户线下表痛点）/ **单独采购**（PurchaseType.DIRECT,salesOrderId可空,两路:从SO #748 + 直接建PO）—— 都确认做了。

---

## 在飞 / 下一步
1. **Codex 跑 E2E**（handoff `2026-06-11-codex-e2e-fullflow-handoff.md`，295行自包含）:
   - **第一轮**（现在）: §1-6 完整9阶段流程 + SQL坐实附录（链路通+数据对，重点 A跨路径断点/B含税三行/C多段成本/D两点报工/E UI控件）
   - **第二轮**（第一轮后）: §7 headed UI真实操作 + 防呆设计5条逐验 + 快速关联/一键操作 + UX优化（用真实低权角色操作员/仓管/财务走）
2. **新chat接棒**: Codex 回来 → gate 结果 → 修 bug。🔒 红线（财务/权限/迁移）回 main 终审 + 从 main 部署（绝不 feature 分支部署 prod）。
3. **Friday = 演示日**（不是确认日）: 演示全部 → 没问题就上线。F006 真数据已坐实（含税订单/盐化/销售付款/三价/成本链）。

---

## 🔑 本轮关键教训（新 chat 注意，写进了 memory）
1. **silent-drop bug ×2**: DTO往返字段被静默丢弃(C-051双单号 sourceOrderId / 之前 level1Unit)。加entity字段必查 DTO声明+create set+update set+convertToDTO map 全4处。
2. **最后一公里反复复发**: 并行 agent 加后端+列表显示但漏编辑表单控件(孤儿)。headed E2E/Fable 每次抓到。**验证靠 headed 真跑，不靠"merge了就对"**。
3. **主线修好≠交叉路径修好**: Fable流程审揪3个跨路径断点(多SO/撤回/采购双源)都是"happy path通但拐弯断"。流程级审计专抓这个。
4. **两点报工人工null=诚实**: F006做不了逐道工序,人工"登下一期",null不是bug。
5. **mock过≠真实路径过**: 含税凭证单测过但真实路径走seed模板=2行。Codex是首个真实路径验证者。
6. **Flyway撞号**: 并行agent全选同号,merge前必 `git ls-tree origin/main flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d` 查重+重编号。
7. **台账/doc编辑必在干净worktree commit+push**(主目录STALE会丢)。

---

## 铁律（继承）
- 主目录工作树 STALE → 取证必 `git show/grep origin/main`
- prod 真客户 F006 在用 → 写操作 test 或 DEMO 前缀；prod 只从 main 部署
- PR 不自 merge，organizer gate
- 验证证据必落 `docs/audits/liushanmen/` 否则不算 V1
- 每任务 worktree off origin/main；commit 锁 scope；🔒 红线执行者只到 PR

---

## 关键文件
- Codex E2E handoff: `docs/dispatch/2026-06-11-codex-e2e-fullflow-handoff.md`
- 追溯矩阵: `docs/meetings/2026-06-09-liushanmen/verification-matrix.md`
- 需求 catalog: `docs/meetings/2026-06-09-liushanmen/requirements-catalog.md`
- 转录: `docs/meetings/2026-06-09-liushanmen/transcript.txt` + `transcript-2b.txt`
- 真实数据: `六扇门工厂数据、/`
- 部署: `scripts/deploy/deploy-backend.sh --env all` / `deploy-web-admin.sh`
