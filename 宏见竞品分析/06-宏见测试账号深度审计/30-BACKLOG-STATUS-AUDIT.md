# 30 — Backlog Status Audit (2026-05-16)

> **来源**: Steve 要求 "审计一下,看看应该怎么做" → 发现 28-Backlog 跟 main 严重 misaligned, 14/88 项已 ship 但 backlog 全标 ❌.
>
> **方法**: grep `--all --grep=<id>` 每项 + gh PR search + 验证 Track 命名规则.
>
> **结论**: 88 项剩余 **74 项** (14 ✅ + 3 ⚠️), 不是 88. Sign-off 锁定的 "9 月 P0+P1 = 66 项 / 252d" 真实剩余 **51 项 / ~220d nominal / ~132 工日 / ~7 月**.

---

## 1. 关键 finding

| 维度 | 28-Backlog 假设 | **Main 真实** |
|---|---|---|
| 88 项剩余 | 88 (全 ❌) | **74** (14 ✅ + 3 ⚠️) |
| P0 已 ship | 0 | **9 of 18** (50%) |
| P1 已 ship | 0 | **5 of 47** (10.6%) |
| Sprint 0-2 真实推进 | "Sprint 0 done, Sprint 1 ready start" | **Sprint 1 全 ship + Sprint 2 G/H/I/J/E/F ship + Track-B1 钉钉 in flight** |

---

## 2. P0 战略 12 项 ship 状态

| # | 编号 | 28-Backlog | **Audit 结果** | 证据 | 工时 saved |
|---|---|---|---|---|---|
| 1 | F-VFLAG-1 | ❌ | ❌ | grep 0 hits ("vflag" / "F-VFLAG") | — |
| 2 | C-LINKARRAY-1 | ❌ | ❌ | grep 0 hits | — |
| 3 | S-LOCK-1 | ❌ | ❌ | grep 0 hits | — |
| 4 | M-BOM-VER-1 升级 | ❌ | ❌ | grep 0 hits (M-BOM-1 ship 但版本/ECN 没做) | — |
| 5 | C-APPROVAL-EDITOR-1 | ❌ | ❌ | grep 0 hits | — |
| 6 | C-PRT-EDITOR-1 | ❌ | ❌ | grep 0 hits (C-PRT-1 后端 ship, EDITOR 没做) | — |
| 7 | C-AI-1 钉钉 | ❌ | **⚠️ 80% (Track-B1 Day 1-5 ship on branch, awaiting Day 6 E2E + PR)** | `529611399 [Track-B1] Day 5: Retry scheduler + 4 admin endpoints` | — |
| 8 | N20 C-ATT-1 attachment | ❌ | **✅ Track-C #658** | `f296447c6 [Track-C] C-ATT-1 通用 Attachment 系统 (Day 1-5) (#658)` | 5d |
| 9 | N24/N25 M-WP-1/2 | ❌ | **✅ Track-D2 #650** | `ec69a94dc [Track-D2] M-WP-1/M-WP-2 工序管理` | 5d |
| 10 | N32 M-BOM-1 | ❌ | **✅ Track-D1 #656** | `809fc32a7 [Track-D1] M-BOM-1 BOM 配方 + Bug-2 + Bug-3 (Track D1 全部 3 项)` | 5d |
| 11 | N13 W-ABA-1 抄码品 | ❌ | **✅ Track-B2 #649** | `f07020c7d [Track-B2] W-ABA-1 抄码品识别` | 2d |
| 12 | N48 S-RD-1 研发样品 | ❌ | **✅ Sprint2-F #680** | `c3d9a0b34 [Sprint2-F] N48 ProductSample → 自动 BOM` | 5d |

**P0 战略**: 5 ✅ ship + 1 ⚠️ in flight + 6 ❌ not started = **22d saved**

---

## 3. P0 必修 6 项 ship 状态

| # | 编号 | 28-Backlog | **Audit** | 证据 | Saved |
|---|---|---|---|---|---|
| 13 | M1 三价对比刷新 | ❌ | **⚠️ BLOCKED** | T3-14 test env seed blocker (issue #538) | — |
| 14 | M2 生产工序通用 | ❌ | **⚠️ partial #567, follow-ups OPEN** | issues #622 / #623 (P3 follow-ups) | — |
| 15 | M3 PDF + 扫码 RN | ❌ | **✅ Track-B2 #653** | `8bf5fbc93 [Track-B2] Bug 修 PDF 扫码 RN 端` | 4d |
| 16 | M4 BOM 物料选择器 | ❌ | **✅ Track-D1 #656** | `809fc32a7 [Track-D1] M-BOM-1 BOM 配方 + Bug-2 物料选择器` | 2d |
| 17 | M5 单位转换强校验 | ❌ | **✅ Track-D1 #656** | `809fc32a7 ... + Bug-3 单位换算` | 2d |
| 18 | N3 C-RBAC-1 仓管隔离 | ❌ | **✅ #661 + 多 follow-up** | `e7c864004 feat(rbac): C-RBAC-1` + #667/668/671/672/673/674 | 2d |

**P0 必修**: 4 ✅ + 2 ⚠️ blocked = **10d saved**

---

## 4. P1 战术 47 项 ship 状态 (5 ✅)

| 编号 | 28-Backlog | **Audit** | 证据 | Saved |
|---|---|---|---|---|
| S-MRP-1 (隐含 P1) | ❌ | **✅ Sprint2-E #682** | `b936d19e3 [Sprint2-E] S-MRP-1 销售订单→采购自动分流` | (5d) |
| P-FIN-1 | ❌ | **✅ Sprint2-J #675** | `b7846a918 [Sprint2-J] P-FIN-1 采购订单财务审核+三价标红` | 3d |
| U-NAV-1 | ❌ | **✅ Sprint2-G #683/#684** | `d984dd1e0 [Sprint2-G-1]` + `8f0a6f8ce [Sprint2-G-2]` | 6d |
| U-ACT-1 | ❌ | **✅ Sprint2-H #678** | `10d9e4d36 [Sprint2-H] U-ACT-1 行末操作下拉` | 6d |
| U-FOOTER-1 | ❌ | **✅ Sprint2-I #681** | `a86e40bd5 [Sprint2-I] U-FOOTER-1 Sticky Footer` | 4d |

**P1**: 5 ✅ + 42 ❌ = **24d saved** (含 S-MRP-1 5d)

P1 剩余 42 项 (含 CRM 11 / 销售 6 / 采购 3 / 仓库 4 / 生产 5 / 财务 3 / HR 5 / 品质 2 / 系统 7 / UX 11 / 其他)... 详见 28-Backlog.

---

## 5. P2 选做 15 项 + P3 长期 8 项

**P2**: 0 ✅ — 15 项全 ❌ (大客户/餐饮多门店/食品扩展不在 Sprint 1-2 范围)

**P3**: 0 ✅ — 8 项全 ❌ (TV 大屏 / 微服务 / 1591 RBAC 长期战略)

---

## 6. 工时累计修正

| 类别 | 88-Backlog 原估 | **真实剩余** | Δ |
|---|---|---|---|
| P0 战略 12 | 86d | 64d (剩 6 全做 + Track-B1 收尾 6d) | -22d |
| P0 必修 6 | 14d | 4d (剩 M1+M2, blocked 不算 backlog 直接做) | -10d |
| P1 战术 47 | 152d | 128d (剩 42 项) | -24d |
| P2 选做 15 | 126d | 126d (无变) | 0d |
| P3 长期 8 | 51d | 51d (无变) | 0d |
| **88 项合计** | **429d nominal** | **373d nominal** | **-56d (-13%)** |

按 Claude 1.7× 加速 + 25% buffer:
- 原估: 258d 实际工日 ≈ **15 月**
- 真实: ~224d 实际工日 ≈ **13 月**

按 Steve sign-off "9 月 P0+P1 = 66 项":
- 原估: 252d nominal / 152 工日 / **9 月**
- 真实: 51 项 (66 - 14 已 ship - 1 in flight) ≈ **196d nominal / ~118 工日 / 7 月**

**=> Sign-off 锁定的"9 月"真实只需 7 月** (省 2 月).

---

## 7. ⚠️ Blocker 项独立追踪

### M1 三价对比刷新 (BLOCKED)
- **阻塞**: T3-14 test env seed (issue #538)
- **要解锁**: F006 test factory 在 test DB 上 seed 完整数据
- **行动**: 填 #538 — F006 factory missing on test DB
- **工时**: 2d (修 + 验证)

### M2 生产工序通用未关联 (PARTIAL — P3 deferred, 不修)
- **现状**: #567 partial ship; follow-ups #622/#623 open P3
- **真相 (2026-05-16 reconcile)**: 读完 issue body 发现 — 原作者明确标 P3 "Demo-OK; testing rigor gap" + "feature works in production usage" + "Customer ask describes chain conceptually, not at instance-trace level"
- **行动**: ⛔ 不 dispatch (我之前推 "P3→P0" 是 28-Backlog metadata stale, 不是真相). 尊重原 P3 deferral.
- **追溯到根 rule 违反**: 这是 `feedback_signoff_requires_reconcile_with_main_first.md` HARD + `feedback_brief_must_grep_existing_endpoint_paths.md` HARD 的另一个 instance — 我用 backlog metadata 而不是 issue body 真相做决策
- **工时**: 0d (deferred)

---

## 8. Track-B1 钉钉机器人 进度 (in flight)

**5 day commits 在 branch (无 PR)**:
- Day 1: scaffold
- Day 2: DingTalk inbound webhook (entity + migration + controller) `5def64a2e`
- Day 3: Inbound consumer → AIChat (non-streaming) + 22 unit tests `c4daa2278`
- Day 4: Outbound send service + rate limiter + 2 AIChat Tools `859a18e63`
- Day 5: Retry scheduler + 4 admin endpoints `529611399`

**待 Day 6**: deploy --env test + configure DingTalk Outgoing Webhook URL + E2E in F006 test group + open PR

**预估收尾**: 1-2 day (E2E + PR) = ~1-2d

---

## 9. 推荐 next step (基于 audit)

### 9.1 立即 (本周)
1. **更新 28-Backlog status markers** (本 audit doc + 28-doc inline ✅/⚠️/❌) — done by this doc
2. **修 sign-off scope**: "9 月" → 真实剩余 "7 月" 重 sign-off
3. **填 issue #538**: F006 test factory seed (解锁 M1)
4. **推 #622 / #623** P3→P0 (解锁 M2)
5. **催 Track-B1 Day 6** PR (钉钉机器人 ship)

### 9.2 Sprint 3 dispatch (本月)
剩余 6 P0 项需 dispatch:
- F-VFLAG-1 凭证 hook (10d) — backend, 跟 ApprovalChainConfig 集成
- C-LINKARRAY-1 跨业务关联 (2d) — backend quick win
- S-LOCK-1 锁定/备货/缺料 (1d) — frontend quick win
- M-BOM-VER-1 BOM 工程级升级 (15d) — backend major (BomVersion + ECN + BomLog 反查)
- C-APPROVAL-EDITOR-1 工作流可视化 (20d) — frontend major
- C-PRT-EDITOR-1 打印模板可视化 (10d) — frontend major

**总 58d / 35 工日 / 7 周** (单人, Claude 加速). 跟 Sprint 1 一样按 6 Track 并行 (Track-E/F/G/H/I/J) 可压缩到 ~4 周.

### 9.3 Sprint 4-5 P1 推进
P1 剩 42 项 / 128d / 77 工日 / 16 周 (单人).

### 9.4 Sprint 6+ P2 视客户实际需求

---

## 10. 元教训 (memory candidates)

1. **每次写战略 doc 前必 grep main** — 我犯了 May 13/15 HARD rule 违反 (grep before assume + gh PR search before dispatch outstanding)
2. **Backlog 跟生产线异步** — 写 audit 期间 main 在 ship, 不 reconcile 就 sign-off = 用 stale data 决策
3. **Sign-off 之前必 verify** — Steve 信我说"9 月 P0+P1 66 项 / 252d", 实际数字早期就 stale
4. **Organizer mode 假设错误** — Steve 不是 single dev coder, 是 multi-chat organizer dispatching tracks

---

## 11. 完成度

- ✅ 88 项 ship 状态 grep 验证完成
- ✅ Track-A through Track-D2 (Sprint 1) + Sprint2-E through J (Sprint 2) 全 mapping
- ✅ 真实剩余 73 项 / ~373d nominal / 7 月 (单人 P0+P1)
- ✅ Blocker 项 (#538 + #622/#623) 独立追踪
- ⚠️ Track-B1 钉钉 in flight, Day 6 待 ship
- ✅ Sprint 3 dispatch 6 项 P0 推荐就绪

---

## 13. ⚡ Round 11 Update (2026-05-19, organizer)

> **Trigger**: Steve "目前分析书来的内容不完整, 需要再一次细节的去核对宏见的ERP测试网站, 每一个已经核对过的内容继续深度去抓一次"
>
> **方法**: 6 parallel subagents (Agent A-F) 分工 88 项 deep re-audit + organizer Layer B 13 项 Playwright fresh capture (HJ test account lyh01/admin).
>
> **完整报告**: `../04-最终决策/31-DEEP-RE-AUDIT.md` (**3517 行, 16 sections §A-§P**)
> **Fresh screenshots**: `screenshots/round11/` (10 张)

### 13.1 关键发现 — ship 数字大幅修正

| 维度 | 30-Audit (本文件 2026-05-16) | **Round 11 (31-doc 2026-05-19)** |
|---|---|---|
| ✅ FULL SHIPPED | 12 of 88 | **~49 of 88** |
| ⚠️ PARTIAL | 0 | **~7** |
| 🟡 IN-FLIGHT | 3 | **~3** |
| ❌ NOT DONE | 73 | **~29** |
| 真实剩余工时 | 373d nominal / 7 月 | **~150d nominal / ~3 月** |

**Round 11 多发现 ship 37 项**, 原因:
1. **2026-05-16 之后又 ship 14+ PRs** (PR #690/#693/#763/#770/#773/#822/#823/#831/#832/#834/#844/#862/#863/#870 等)
2. **本文件 grep 关键字漏判** — 用 `Voucher` 漏 `VoucherFlag` enum + lowercase `vflag` column; 用 `linkListArray|linkno=` 漏 Track-F linkno 实装; 等
3. **Sprint 4 W1-A bundle (PR #764) + Chat L (PR #727)** 集中 ship 多 quick wins, 本文件没拆分到每 backlog 项

### 13.2 30+ 项 ❌→✅ Reconcile (本文件应更新)

详见 31-DEEP-RE-AUDIT.md §P.4 reconcile 表. 关键修正:

**P0 战略 12 项 (本文件 §2)**:
| # | 编号 | 本文件标 | **Round 11 修正** | 证据 |
|---|---|---|---|---|
| 1 | F-VFLAG-1 | ❌ grep 0 hits | **✅ Sprint3-E PR #693** (7 generator + vflag 4 状态 + 4 listener + 借贷必平 + 2 AIChat Tool) | 31-doc §G.1 |
| 2 | C-LINKARRAY-1 | ❌ grep 0 hits | **✅ Track-F** | 31-doc §I.3 |
| 3 | S-LOCK-1 | ❌ grep 0 hits | **✅ PR #690 Sprint3-G** (锁/备/缺 chip + 公式 tooltip) | 31-doc §O.1 |
| 4 | M-BOM-VER-1 升级 | ❌ grep 0 hits | **⚠️ backend ✅ PR #694 (BomVersion + ECN + 反查 + 4 批量 + PG trigger) + frontend 3d follow-up** | 31-doc §E.1 |
| 5 | C-APPROVAL-EDITOR-1 | ❌ grep 0 hits | **⚠️ Phase 1 ✅** (Sprint 3 Track-I 758-line VueFlow editor + Canvas PR #862, 4 执行模式含 N-of-M HJ 没有) — 剩 3-5d incremental | 31-doc §I.1 |
| 6 | C-PRT-EDITOR-1 | ❌ grep 0 hits | **✅ Track-J 3-pane editor** | 31-doc §I.2 |

**P1 战术 47 项 (本文件 §4)**: 累计 ~32 ship (vs 本文件 5). 包括但不限于:
- CRM/销售域: S-CRM-FULL-1 (#A.1) / S-CUSTOMER-TAB-1 (#A.2, 13/21 active 62%) / S-CRM-1 (#A.3, PR #822) / S-PRICE-1 (#A.4) / S-CREDIT-1 (#A.5, PR #834) / S-INVOICE-CLIENT-1 (#B.1) / S-PROFIT-DETAIL-1 (#B.2) / S-REMIND-1 (#B.3, scanner auto + bell badge 优于 HJ OA 跨域) / S-NEED-1 (#B.4) / S-PAYMENT-DATE-1 (#B.5)
- 采购/仓库: P-NUCLEAR-1 (PR #824/#30) / P-IMPORT-1 (PR #764 `7a4b2da49`) / W-CLASS-1 (PR #764 `91cdf7897` 扩 13 类 超 HJ 10)
- 生产/品质: M-WIP-1 / M-PREP-1 / M-DELIVERY-WARN-1 / M-MATTREE-1 (Sprint 4 W2 Chat G #732/#734/#737/#738) + Q-MODE-1 / Q-PROCESS-1 / Q-RETURN-1 (Sprint 4 W1+W2 Chat A+H #729/#733/#735/#764)
- 财务/HR: F-AR-1 (ArApTransaction) / F-INV-1 (+ENHANCED tax_breakdown JSONB + OCR PR #763) / H-WAGE-FULL (#833/#844/#863/#870) / H-LEAVE-1+OVT+EXP (PR #770)
- 系统/平台: C-CHECKPOWER-1 / C-LOG-AUDIT-1 / C-EXPORT-CENTER-1 / C-IMPORT-CENTER-1 / C-WIDGET-1 (PR #823, 10 endpoint widget framework) / C-INLINE-CS-1
- UX 全 11/11 ship: U-NAV-1 (#683/#684) / U-ACT-1 (#678) / U-FOOTER-1 (#681) / U-VIEW-1 / U-NEW-1 / U-ICON-1 / U-MARKER-1 / U-FEED-1 / U-DESKTOP-MODAL-1 / U-DEPT-1 (PR #821 接入) / U-CHIP-MULTI-1 (全 Chat L #727 + W1-A #764 bundle)

### 13.3 真实剩余 ❌ (Sprint 5+)

按优先级排序 (详见 31-doc §P.5):

**Sprint 5+ P0 剩 ~5d**:
- M-BOM-VER-1 frontend follow-up 3d
- M1 三价对比刷新 (still blocked by #538) 2d
- C-APPROVAL-EDITOR Phase 2 收尾 3-5d (WorkflowRule UI / OpinionTemplate dialog / decisionType 扩枚举)

**Sprint 5+ P1 backlog ~30d (按需触发)**: S-REPORTS-PRESETS 9 stub / M-WP-CONDITION-1 / S-COMPLAINT-1 收尾 / C-WF-RULE-1 / C-WF-VAR-1 UI / C-OPINION-1 dialog 等

**Sprint 6+ P2 ~60d (客户群触发)**: F-VOUCHER-2-1 / F-PERIOD-1 / F-3REPORT-1 / C-CUSTOM-1 / H-ATT-FULL 矩阵 / C-STORE-1 / S-STORE-REPLEN-1 等

**Sprint 7+ P3 ~50d (Steve sign-off 延后)**: C-TV-DASHBOARD-1 / C-MENU-ENGINE-1 / C-RBAC-FNO-1 / C-MICROSERVICE-1 / 等

**Archive (不抄)**: S-CALL-STAT-1 / S-COMMISSION-1 / M-MOULD-1 / F-PARTNER-FULL / 委外/办公自动化/mould/wxshop/mail/sms

### 13.4 元教训 (Round 11 抓到, 本文件应吸取)

1. **单关键字 grep 不可靠** — 本文件用 `Voucher` / `vflag` 等漏判 6+ 项 ship. 必须 multi-synonym (entity / enum / column / camelCase / lower). Sister rule 升级 `feedback_brief_must_grep_existing_endpoint_paths.md` HARD.
2. **Sprint bundle 难 track** — Chat L PR #727 一次 ship 8 UX items / W1-A PR #764 一次 ship 7 quick wins, 本文件按单项 grep 漏判. **未来按 bundle 标 commit ↔ backlog item mapping**.
3. **Backlog metadata drift 快** — 本文件 5-16 写完, 5-17/18/19 又 ship 14+ PRs, status 立即过时. Daily reconcile 必要.
4. **真 gap 是 decisionType 覆盖度** (Cretas 10 vs HJ 126 = 8%), 不是单 feature.
5. **5 处 Cretas 已超 HJ** — S-LOCK-1 chip 颜色+tooltip / M-MATTREE-1 BOM tree + 库存短缺 / W-CLASS-1 13 类 / S-REMIND-1 scanner+bell / decisionType N-of-M. 销售话术应突出.

### 13.5 Steve sign-off 重新建议

| 项 | 原 sign-off (2026-05-16) | **Round 11 建议** |
|---|---|---|
| P0+P1 总时间 | 9 月 (66 项 / 252d) | **3 月** (P0 5d 收尾 + P1 30d 按需触发) |
| 客户群 | 食品 + 餐饮 | 维持 |
| 团队规模 | 单人 (Steve) | 维持 (真实剩余 ≤ 1 季度) |
| Sprint 5+ 重点 | 推 backlog | **客户深度试用 + bug fix + 新客户 onboarding** (backlog 已接近完成) |

### 13.6 本文件后续 (取代关系)

- ✅ **本文件 (30-Audit) 保留作为历史 baseline** — 5-16 时点的 ship 数字
- ✅ **新 audit truth source**: `31-DEEP-RE-AUDIT.md` (5-19 时点, 16 sections + 10 fresh screenshots)
- ⚠️ **28-Backlog 应 inline 更新** ship marker (30+ 项 ❌→✅), 见 task #8
- ⚠️ **MUST_COPY.md 应加附录 P Round 11 amend**, 见 task #9
