# 交接 — 六扇门验证扫荡 chat (V0/V2 → V1)

> 新 chat 读本文件开工。自包含，不依赖旧对话。**本 chat 是执行者不是 organizer**（organizer 在另一个 chat 持台账）——你产出验证证据 + 修复 PR，**一律停在 PR 不许 merge 不许部署**，完成后输出汇总让 Steve 带回 organizer。

## 目标
追溯矩阵（`docs/meetings/2026-06-09-liushanmen/verification-matrix{,-1,-2,-3}.md`）里 **V0 未验证 / V2 弱验证** 的条目，逐项做带断言的验证，把状态提到 V1（持久化证据），发现 bug 记录+修复（PR）。最终目标：456 条需求全链路可证。

## 批次顺序（按风险）
1. **批A**: D/E 流 V0（~24 项：付款链 D-9 全链 / 财审 D-10 / 双值显示 D-11 / SO→采购 D-12 / 请购 D-13 / E 流发货收款开票链）
2. **批B**: H/X 流 V0（凭证导出 SP11 三迁移已落库待验 / 进销存台账 web+RN 已对接待数据断言 / 审批流 X 项）
3. **批C**: A/B 流 V2→V1（36 项弱验证补断言，重点 B 流成本类）
4. **批D**: C/F 流 V2→V1（65 项, 注意今天已 V1 的别重复做）

## 验证方法与铁律
- **证据落盘才算 V1**: 每项验证写 `docs/audits/liushanmen/` 下 audit doc（模板 `_template-verification-audit.md`，规则见同目录 README.md）。headed 截图存同目录。验完更新对应 matrix 分片文件的 验证/证据 列（matrix 文件你可以改，`docs/dispatch/ACTIVE.md` 不许碰）。
- **⛔ 主目录工作树 STALE**: 代码取证一律 `git fetch origin main` 后 `git grep/ls-tree origin/main` 或 `git show origin/main:<path>`，禁止直接 Read/Grep 主目录源码（已有 agent 栽过导致整片矩阵重做）。
- **API 验证**: ssh root@47.100.235.168 上 curl localhost:10010(prod 蓝绿看活跃)/10011(test)。登录 `POST /api/mobile/auth/unified-login` {username,password}→data.token。测试账号 f006_* / 123456（moyun 操作员/weizj 操作员/xushifu）；财务/采购角色账号先查 `cretas_prod_db.users`（psql -U cretas_user -h 127.0.0.1, PGPASSWORD=cretas123）。
- **prod 是真客户(F006 张权团队在用)**: 写操作只打 **test env 10011 + cretas_db**；必须 prod 验证的写操作只动 DEMO- 前缀数据。
- **headed UI**: 必须 headless:false + zh-CN（规则 `.claude/rules/playwright-headed-mode.md`，web-admin http://139.196.165.140:8086）。
- **发现 bug**: 修复走独立 worktree off origin/main（`git worktree add -b fix/sweep-X ../cretas-sweep-X origin/main`）+ TDD + PR 停住；🔒 红线类（权限/迁移/库存事务/核价）PR 标 🔒 等 organizer gate。commit 锁 scope（`git commit -- <files>`）。
- **Flyway**: 如需迁移，号 > 当前最高（merge 前 `git ls-tree -r origin/main --name-only | grep flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d` 查重 + 乱序检查），当前最高 V20261012_11。
- **诚实**: 断言失败就是发现，不许美化；验证不了写 B阻塞+原因。

## 已知阻塞（别撞）
- 盘点全链: test env 已可验（#663 threshold=1, organizer 会先跑一轮）；prod 等 6-29。
- 成本数字正确性: 等周五真实 BOM 数据（W3, 不在本 chat scope）。
- R8 双栈: 设计中，C 流 RN 报工↔Yield 栈闭合验证暂跳。
- T-3 报工守卫: 后端已落(#666)但 RN 未送 businessDate，报工侧守卫验证只能 API 直打 businessDate。

## 产出
每批一份汇总（条目→V1 数量/发现 bug 清单/PR 列表/仍 V0+原因），全部跑完后总汇报。
