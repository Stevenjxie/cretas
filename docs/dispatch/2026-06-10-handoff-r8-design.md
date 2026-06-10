# 交接 — R8 双栈合并设计 chat (只设计不实现)

> 新 chat 读本文件开工。自包含。**产出 = 设计稿 docs PR，停在设计稿**，方案选项给 Steve 拍板后由 organizer 派实现。先 invoke superpowers:brainstorming 再写设计。

## 问题（六扇门 C 流闭合性最大缺口, 追溯矩阵 R8/B阻塞）
RN 报工存在**两套并行栈**，半成品/出成率数据不互通：
- **栈A (新, SP1/三阶段)**: RN `YieldStepReportScreen` → `YieldReportController` (`/api/mobile/{fid}/production/batches/{batchId}/reports`) → `YieldReportServiceImpl`（INPUT/SEGMENT/OUTPUT 三阶段、WIP SemiFinishedInventory 写入、双产出 semiCode、出成率派生、撤回 SP2、成本滚动）。**今天大量强化验证过的就是这套**。
- **栈B (旧)**: RN `ThreeStepReportScreen` 等（ProcessWorkReporting 栈）→ 旧报工链路，半成品**不一定**写 SemiFinishedInventory。
03-e2e 计划 R8 原话: 「SP1 双产出走 YieldReportController 栈；RN 阶段 B 用 ThreeStepReportScreen（ProcessWorkReporting 栈），半成品不一定写入 SemiFinishedInventory — 演示时明确说"双栈并行"」。

## 任务
1. **摸清两栈现状**（⛔ 主目录工作树 STALE：代码一律 `git fetch origin main` 后 `git grep/ls-tree/show origin/main`）：
   - 栈B 入口 RN 屏幕清单、后端端点、写哪些表；哪些角色/导航路径还在用栈B（grep RN navigation）；prod 数据里两栈各有多少存量（可 ssh 47 psql cretas_prod_db 只读查）。
   - 栈A 能力边界：今天刚 shipped 的报工三阶段/任务联动/撤回/T-3 守卫全在栈A。
2. **出 2-3 个合并方案** + 推荐（如: ①栈B RN 入口全部切到栈A 屏幕、后端栈B 端点 deprecate 只读；②栈B 后端适配层转发到栈A service；③栈B 整体下线+数据迁移），每个方案给: 改动面/风险/工作量/迁移与回滚/对 F006 现役操作员的影响（**prod 真客户在用，不能断报工**）。
3. **验收设计**: 合并后如何证明闭合（半成品台账一致性断言、E2E 链）。
4. 产出: `docs/superpowers/specs/2026-06-09-liushanmen/R8-dual-stack-merge-design.md`，PR 提交（worktree off origin/main, `git commit -- <file>`, 不许 merge）。设计里待 Steve 拍板的点单独列表。

## 约束
- 只设计不动业务代码。
- 单一 organizer 在别的 chat 持台账，本 chat 不碰 `docs/dispatch/ACTIVE.md`。
- 参考规则: `.claude/rules/organizer-protocol.md` 红线表、`fool-proof-design.md`（操作员低技术素养, 入口切换必须无感）。
