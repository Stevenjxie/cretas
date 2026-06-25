# Handoff — 结转损益 P1 部署 + reopen 双计 bug 修复 + 47 内存治理

**日期**: 2026-06-25 晚 → 06-26
**作者**: Opus organizer session
**状态**: ✅ 全部完成并部署 prod, 无待办

---

## 一句话

结转损益 P1 (财务真账化) 完整部署 prod 并端到端验证; 期间揪出并修复一个**致命 bean 循环** (prod 起不来) 和一个 **reopen→reclose 双计 P&L bug**; 顺带治理 47 服务器内存 (永久关停 CVAT)。**全程绝没碰 LIUSHANMEN 真客户租户**, 只动 F006 测试租户。

---

## Shipped (全 merge 进 main + 部署 prod)

| PR | 内容 |
|---|---|
| **#1112** | 结转损益自动凭证 P1 (月末结转核心 6xxx→4103 + 锁定触发 scheduler + force-lock-close 逃生口 + reopen 反结账红冲) |
| **#1116** | `@Lazy` 破 PLClosing bean 循环 (prod 起不来根因之一) + `TrainingSample.embedding_blob` BLOB→bytea (H2) — 顺带把 `java-build-test` 转绿 |
| **#1122** | 结转损益聚合排除 PL_CLOSING — 修 reopen→reclose 双计 P&L |
| **#1118** | deploy-backend.sh Blue-Green 前内存预检 (可用<3500MB 临时停 test 腾内存, 部署完恢复) |

prod 运行 jar = main HEAD 构建 (含全部 4 个 PR), blue-green 零中断部署, health UP。

---

## 三个关键 bug (都是本 session 发现 + 修复)

### 1. 致命 bean 循环 (#1116) — prod 起不来根因
`VoucherService →(field) AccountingPeriodService →(field) ProfitLossClosingService →(构造器) VoucherService`。
**只要环上有一条边是构造器依赖, 纯 field 注入破不了环** → 启动崩 `currently in creation`。修: 一条边加 `@Lazy`。
- ⚠️ 我曾口头"验证 field 注入破了环" —— **错的**。教训: bean 循环别脑推, 跑一次 `@SpringBootTest`。
- 这类 @SpringBootTest 才照得到, 纯 Mockito 单测漏 (本 bug 单测全绿却炸 prod)。
- 详见 memory `feedback_lazy_breaks_constructor_closed_bean_cycle`。

### 2. reopen→reclose 双计 P&L (#1122) — 红线财务 bug
反结账 (`reversePeriodClosing→voidVoucher`) 把原结转置 **REVERSED** + POST 一张**红冲镜像** (voucherType 仍 PL_CLOSING, status POSTED)。`closePeriod` 的 `aggregateBySubjectPosted` 排除 REVERSED 却**计入** POSTED 镜像 → 二者不相抵 → 再结转把 6xxx 双计 (实测 F006 成本 3,796,601 → 7,593,202, 4103 显假亏损)。
- 修: `aggregateBySubjectPosted` 加 `AND v.voucherType <> PL_CLOSING` — 结转只聚合**业务** 6xxx, 结转产物不算业务活动。
- TDD: `@DataJpaTest` red→green (Mockito 单测照不到 SQL 过滤)。
- **通用规则**: 任何"聚合某类发生额再据此过账"的逻辑 (结转/计提/摊销/折旧/汇兑), 聚合时必须按 voucherType 排除自身产物凭证。
- 详见 memory `feedback_pl_closing_aggregation_excludes_closing_vouchers`。

### 3. (上游) F006 营收凭证 DRAFT
F006 5 张营收凭证 (6001, 6,123,550) 原是 DRAFT。closePeriod 按审计只结 POSTED (正确, 不结草稿) → DRAFT 时只结成本侧显假亏损。已 post (DRAFT→POSTED), 重结转后 4103 = 真利润。

---

## F006 端到端验证 (全 PASS)

- **单次结转**: confirm-close → force-lock-close → PL_CLOSING 凭证借贷平, 6xxx→4103, balanceCheck=true。
- **反结账红冲**: reopen → 原结转 REVERSED + 红冲镜像 POSTED 等额, closingPostedAt 清空, 期间回 OPEN。
- **真利润 demo** (营收过账 + 修复后重结转): 凭证 V-2026-0161 → 6001 借 6,123,550 / 6401 贷 3,796,601 / 6601 贷 489,884 / 6602 贷 367,413 / **4103 贷 1,469,652 (真利润)**, balanceCheck=true。

---

## 47 服务器内存治理 (14GB 共享机, blue-green 部署 OOM 根因)

- **永久关停 CVAT** (17 docker 容器 + cvat_clickhouse, ~2GB): `docker update --restart=no` 防 reboot 自动回来。ClickHouse 经查=CVAT 容器, 非 Cretas。
- **停 cretas-backend-test** (省 1.5GB) —— Steve: **目前先不用 test 环境**, 部署直接 `--env prod`, test 保持停 (memory `feedback_test_env_currently_unused`)。
- **重置 swap** (6GB 满→空)。
- 长期建议: CVAT 挪机或 47 升 32GB (否则 test+CVAT 同跑还会紧)。
- #1118 已把"部署前内存预检"固化进 deploy 脚本。

---

## 运维事实 / 坑 (本 session 新增)

- **核 deploy 要查运行进程的 jar fd, 不是磁盘 jar**: 并发 session 的 deploy 可能死在半路 (jar 落盘但没重启实例)。`/proc/PID/fd` 看 deleted jar = 在跑旧码。我曾因查磁盘 jar 误判"P1 已 live"。
- prod 当前活跃实例 = green (10020); blue-green 在 blue(10010)/green(10020) 间切。
- 测试都走单测 + headed 真客户 prod 验证; test 环境不在用。

---

## 待办

无。P1 结转损益完整 live + 验证。所有 PR 已 merge + 部署。

相关 memory: `project_2026_06_25_pl_closing_p1`, `feedback_lazy_breaks_constructor_closed_bean_cycle`, `feedback_pl_closing_aggregation_excludes_closing_vouchers`, `feedback_test_env_currently_unused`。
