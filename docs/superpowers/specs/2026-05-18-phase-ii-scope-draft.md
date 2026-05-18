# Phase II Scope Draft — Cretas P2/P3 Direction

**Date**: 2026-05-18
**Status**: DRAFT (pending Steve direction)
**Source**: 宏见竞品分析/06-宏见测试账号深度审计/28-CRETAS-PRIORITIZED-BACKLOG.md §4 (P2) + §5 (P3)
**Context**: P0+P1 (~95% by item count, ~99% by user-visible feature value) shipped this session. Phase II is the next strategic chapter.

---

## §1 Phase II ≠ continuation of Phase I

Phase I scope = mirror 宏见 ERP features for F006 manufacturing/food traceability customer profile. Phase II scope = **shift product positioning** to address customer segments Phase I didn't:

| Segment | Phase I status | Phase II addressable |
|---|---|---|
| 中小制造业 / 食品 (F006-like) | ✅ saturated | Polish / retain only |
| **大型企业 / 上市公司** (≥500人 / IPO) | ❌ unaddressed | §4.1 (复式记账/期间结账/三表) |
| **大销售团队 / B2B 协同** (≥50销售人员) | ❌ unaddressed | §4.2 (商机管理/CRM 50项/外呼统计) |
| **餐饮 / 多门店连锁** (QHJ升级) | ✅ Phase IIa shipped (May 14) | §4.3 polish (门店补货/图片库) |
| **食品扩展** (上游农牧/下游零售) | ⚠️ basic | §4.3 extend |

Phase II needs **customer-segment-first thinking**, not feature-first.

---

## §2 P2 15 items — segment groupings + dependencies

### §2.1 大企业 / 财务合规 (4 items / ~60d)

| # | 编号 | 项 | 工时 | 依赖 | 客户群 |
|---|---|---|---|---|---|
| 67 | F-VOUCHER-2-1 | 复式记账凭证 (借/贷 + 辅助核算) | 20d | 现有 voucher #773 升级 | 大企业财务合规 |
| 68 | F-PERIOD-1 | 期间结账 (月结/年结锁定) | 8d | #833 H-WAGE + #870 年度汇算 | 上市公司 |
| 69 | F-3REPORT-1 | 三表 (资产负债/损益/现金流) | 12d | 全财务模块 | 上市公司 (强制) |
| 70 | C-CUSTOM-1 | 资料定制 (字段/公式) | 20d | 跨域 schema 改造 | 多行业 |

**关键洞察**: 67-69 是"上市公司合规套装" — 任何一项缺失整套不可用. 客户 buy 要 67+68+69 全部. C-CUSTOM-1 (70) 是独立的 customization platform, 跨域 schema 改造影响巨大, 建议 P3+.

### §2.2 大销售 / B2B (7 items / ~50d)

| # | 编号 | 项 | 工时 | 依赖 |
|---|---|---|---|---|
| 71 | S-OPP-1 | 商机管理 lead/opportunity 漏斗 | 8d | Customer entity (现有) |
| 72 | P-SPLIT-1 | 采购订单按供应商拆单 | 5d | 现有采购 |
| 73 | P-RFQ-1 | 询价管理 (多供应商比价) | 5d | #824 P-NUCLEAR-1 升级 |
| 74 | S-COMPLAINT-1 | 售后投诉 12 字段 | 4d | ✅ #892 OPEN |
| 75 | S-COMMISSION-1 | 合作伙伴佣金报表 | 5d | 跨域 |
| 76 | S-CALL-STAT-1 | 外呼通话统计 (15s/30s/60s/120s) | 8d | **硬件依赖** (云通话) |
| 77 | C-CRM-FULL | 客户 50 项含商机 3 / 报表 6 / 资料定义 6 | 15d | 集大成 |

**关键洞察**: 71+74+77 = "B2B 大销售 360°" — 商机漏斗 + 售后跟踪 + 完整客户档案. S-CALL-STAT-1 (76) 需要硬件, 跳过. 77 包含 71/74 子集, 可能 absorb 71+74 into 77.

### §2.3 餐饮 / 多门店 (4 items / ~16d)

| # | 编号 | 项 | 工时 | 依赖 |
|---|---|---|---|---|
| 78 | C-STORE-1 | 门店管理 5 子项 (QHJ 升级) | 5d | Phase IIa 已 ship |
| 79 | S-STORE-REPLEN-1 | 门店补货 10 列 | 5d | #78 |
| 80 | C-IMAGE-LIB-1 | 公共图片库 | 3d | ✅ #891 OPEN |
| 81 | C-FILE-DOMAIN-1 | 文件管理独立子域 | 3d | 独立 |

**关键洞察**: 78+79 = 多门店补货链路 (餐饮连锁 daily ops). 80+81 = 跨企业资源共享 (low priority).

---

## §3 P3 8 items — strategic / architectural

| # | 编号 | 项 | 工时 | 备注 |
|---|---|---|---|---|
| 82 | C-TV-DASHBOARD-1 | TV 大屏 Android app | 15d | 餐饮厨房屏/工厂车间屏 ($$$ market) |
| 83 | C-MENU-ENGINE-1 | menu.jsp?m=X 配置驱动菜单 | 8d | Cretas 当前 hardcoded |
| 84 | C-RBAC-FNO-1 | 细粒度 f_no 权限点 (1591 项) | 15d | 跟 #730 C-CHECKPOWER-1 |
| 85 | C-MICROSERVICE-1 | 38 子域微服务 | 长期 | 战略 (re-architect monolith) |
| 86 | C-WECHAT-DOMAIN-1 | 微信子域 | 5d | F006 用钉钉, 暂不需 |
| 87 | C-PARTNER-DOMAIN-1 | 合作伙伴独立子域 | 3d | 跟 #75 commission |
| 88 | C-DOCS-DOMAIN-1 | help.cretas.com docs 子域 | 5d | 独立 |
| 89 | C-SERVICE-CODE-1 | 服务代码 footer | 0.5d | ✅ #886 OPEN |

---

## §4 推荐 Phase II execution order

**Wave 1** (1-2 weeks, 客户立即可用):
- 78+79 多门店补货 (5+5=10d) — 餐饮连锁高 ROI, 现有 Phase IIa 基础
- 71 商机管理 (8d) — B2B 销售线索追踪
- 88 docs 子域 (5d) — 文档基础设施

**Wave 2** (2-3 weeks, 大企业 readiness):
- 67 F-VOUCHER-2-1 复式记账 (20d) — 大企业合规第一关
- 68 F-PERIOD-1 期间结账 (8d) — 必随 67
- 77 C-CRM-FULL (15d) — absorb 71 升级

**Wave 3** (3-4 weeks, 上市公司 readiness):
- 69 F-3REPORT-1 (12d) — 三表合规
- 72+73 采购升级 (5+5=10d) — B2B 采购协同
- 75 S-COMMISSION-1 (5d) — 分销支持

**Defer (P3 long-term)**:
- 70 C-CUSTOM-1 (20d) — 跨域 schema 改造, 重新设计
- 84 C-RBAC-FNO-1 (15d) — 细粒度权限 1591 项
- 85 C-MICROSERVICE-1 — 长期战略
- 82 C-TV-DASHBOARD-1 (15d) — Android app 新平台

**Total Wave 1+2+3**: ~88d = 17.6 周 (单人) = ~4.4 月

---

## §5 关键决策点 (Steve)

1. **客户优先级**: 大企业 (Wave 2-3) vs 餐饮多门店 (Wave 1) — 哪个客户群先 ship?
2. **复式记账 (67)** 20d 是 Phase II 最大投入. 跟现有 voucher #773 升级路径? 或全新模块?
3. **C-CRM-FULL (77)** absorb 商机 (71) + 售后投诉 (74) + ? — 是否合并 spec?
4. **微服务 (85)** — 是否启动? Cretas monolith 当前 ~280k LOC 单 jar, 微服务化 = 6+ 月战略级 commitment.

待 Steve direction 后, brainstorming skill 走标准 spec → plan → impl 流程.

---

## §6 当前 session 已部分 ship 的 Phase II items

| PR | Item | 状态 |
|---|---|---|
| #886 | P3 #89 service code footer | OPEN (待 merge) |
| #891 | P2 #80 image library | OPEN (待 merge) |
| #892 | P2 #74 service complaint | OPEN (待 merge) |

3 PRs blocked by GitHub account suspension (2026-05-18). Will merge on resolution.

---

## §7 Phase II spec gaps to fill

- 大企业客户访谈 (real F-VOUCHER-2-1 requirements vs spec assumptions)
- 餐饮连锁客户访谈 (QHJ 之外其他 brand 反馈)
- 上市公司合规 audit (三表 spec 是否 IFRS / CAS / US-GAAP 适配)
- Microservice cost-benefit analysis (re-arch 长期投入 vs 短期 monolith 优化)

待 Steve approve Phase II scope + customer prioritization, 再走 brainstorming flow.
