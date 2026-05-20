# Voucher 异常 2 维度 决策 doc (Sprint 5 Track Z-3)

**日期**: 2026-05-19
**Owner**: Sprint 5 Track Z agent
**Status**: 📝 DECISION — 推荐 Option B (defer 3d P3 backlog)

---

## TL;DR

**Cretas Voucher* (Voucher.java / VoucherFlag.java / VoucherStatus.java) 是 single-axis 状态机, 跟 HJ 的 2 维 (审核 × 异常) **不等价但近似**.**

**推荐**: Option B — 保 Cretas 单维 + 入 P3 backlog `F-VOUCHER-ANOMALY-1` (3d, Sprint 6+ 触发 if 客户问). 不在 Sprint 5 写 code.

---

## Background

### HJ baseline (Round 13 §2 + 32-doc §E.4)

HJ vflag 是 **2 个独立 INT 字段** 组合 (Round 12 实测 + Round 13 verify):

| 字段 | 值 | 含义 |
|---|---|---|
| `checkstate` | 0 / 1 | 未审核 / 已审核 |
| `check_flag` (异常) | 0 / 1 | 正常 / 异常 |

**4 状态组合** (2×2):
| checkstate | check_flag | UI 显示 | 业务含义 |
|---|---|---|---|
| 0 | 0 | 待审核 | 初始 |
| 1 | 0 | 已审通过 | 终态正常 |
| 1 | 1 | 已审异常 | 终态需复核 (e.g. 金额异常 / 数据缺失) |
| 0 | 1 | 异常待审 | 数据有警告但还没人审 |

**关键**: 这 4 状态是 2 维**独立**, 不是单一状态机的 4 transition. 用户可单独"标异常"或单独"审核", 互不阻塞.

### Cretas 现状 (audit 2026-05-19)

#### VoucherFlag.java — 业务单上的标记 (`SalesOrder.voucherFlag` 等)

```java
public enum VoucherFlag {
    UNCREATED,  // 未生成凭证
    PENDING,    // 生成中
    CREATED,    // 已生成
    FAILED;     // 失败 (可 retry)

    // 状态机:
    //   UNCREATED → PENDING → CREATED (终态)
    //                     └→ FAILED → PENDING (retry)
}
```

**单维** — 描述 凭证生成 流程, **跟 HJ "审核 + 异常" 维度不映射**.

#### VoucherStatus.java — 凭证自身生命周期

```java
public enum VoucherStatus {
    DRAFT,    // 草稿
    POSTED,   // 已过账 (审核通过)
    VOID;     // 作废
}
```

**单维** — 描述凭证 自己的 审核状态. DRAFT → POSTED 是审核通过, **无独立"异常"标记**.

#### Voucher.java 字段

```java
private VoucherStatus status;       // DRAFT/POSTED/VOID
private Long approvedBy;            // 审核人
private LocalDateTime approvedAt;   // 审核时间
private String description;         // 说明 (free text)
// NO abnormal_flag / check_flag 字段
```

无独立"异常"字段. 若要标"异常凭证", 当前只能塞 `description` (free text) 或加 `VoucherStatus.ABNORMAL` 枚举.

---

## 维度差异 vs HJ

| 维度 | HJ | Cretas | Gap |
|---|---|---|---|
| 凭证生成 流程 | (隐式, 同步) | VoucherFlag 4-state | Cretas 比 HJ 多了 PENDING/FAILED retry |
| 审核 (checkstate) | 2-value INT | VoucherStatus DRAFT→POSTED | ✅ 等价 |
| **异常 (check_flag)** | **2-value INT 独立字段** | **❌ 无独立字段** | **缺** |
| 作废 | 隐式 | VoucherStatus.VOID | Cretas 比 HJ 显式 |

**核心 Gap**: Cretas 缺一个独立"异常"维度 — 用户无法在凭证审核通过后, 后续标"这单子还有问题需复核". 当前只能 VOID 重做.

---

## 业务场景影响

### 何时 HJ "异常" 字段被用到 (per Round 13 §2):

1. **金额突变报警**: 销售单凭证生成后, 系统发现金额比平均高 30% → check_flag=1 自动标异常, 财务复核
2. **数据缺失**: 凭证缺一些字段 (e.g. 客户银行账号空) → 标异常待补
3. **多人复核流**: 一人审通过, 另一人发现问题, 标 check_flag=1 但不撤销审核

### Cretas 客户场景

- F006 卤制品: 体量小, 复式记账非常基础, 异常凭证概率低 — 客户不抱怨
- 在谈食品厂: 中等体量, 偶尔可能踩到 — 但用 VOID + 重新生成 walkaround 可接受
- 大客户 (会计师事务所/政府): 高频复核场景, **真痛点** — 需要"异常 + 不撤销审"组合

---

## 3 个 Option 对比

### Option A: 立即加 abnormal_flag (2-3d) — Sprint 5 W2 ship

```java
// Voucher.java 加字段
@Column(name = "abnormal_flag", nullable = false)
@Builder.Default
private Boolean abnormalFlag = false;

@Column(name = "abnormal_reason", length = 500)
private String abnormalReason;

@Column(name = "abnormal_marked_by")
private Long abnormalMarkedBy;

@Column(name = "abnormal_marked_at")
private LocalDateTime abnormalMarkedAt;
```

+ Service: `markAbnormal(voucherId, reason)` / `clearAbnormal(voucherId)`
+ REST: `POST /api/mobile/{factoryId}/vouchers/{id}/mark-abnormal`
+ Vue: Voucher list 加"异常"chip + dialog
+ Migration: `V20260620_XX__voucher_abnormal_flag.sql`

**优点**: 跟 HJ 模型对齐, 大客户 demo 时有底气
**缺点**: 2-3d 工时, 客户没问就过度工程

### Option B: 保单维 + P3 backlog (推荐) ⭐

不动代码. 加 P3 backlog `F-VOUCHER-ANOMALY-1` (3d):
- 触发条件: 任何客户问"如何标凭证异常但不撤销审核" OR 大客户 demo 时需要
- 实施: 等触发时按 Option A 实施
- 当前 walkaround: 用 `description` 字段塞异常说明 + 团队约定后缀 `[ABNORMAL: ...]`

**优点**: 不烧 Sprint 5 工时, 客户没问不动, 简单
**缺点**: 大客户 demo 时若被问"为何没有异常标记" 会被动

### Option C: 扩 VoucherStatus 加 ABNORMAL (0.5d) — Sprint 5 quick win

```java
public enum VoucherStatus {
    DRAFT, POSTED, VOID,
    ABNORMAL;  // 新加: 审核通过但标异常 (POSTED 后可转 ABNORMAL)
}
```

**优点**: 0.5d 极快, ship Sprint 5
**缺点**: **违反 2 维独立性** — ABNORMAL 跟 POSTED 互斥 (单维). 不解决"已审 + 异常"组合, 跟 HJ 模型仍不等价.

---

## 推荐决策: Option B

### 理由

1. **F006 + 在谈食品不痛**: 客户实际不抱怨"凭证异常标记缺失". walkaround OK.
2. **Sprint 5 工时紧**: 8 tracks 已满, +3d Option A 抢占 Track F-2 (辅助核算) 优先级.
3. **大客户路径已规划**: 等 Sprint 6+ 客户具体询问时 (per 31-doc §P "Sprint 6 候选: 大客户深 (复式记账)"), 一并打包 Option A.
4. **Option C 是假解**: 单维伪装 2 维, 客户演示时仍露馅.

### 入 backlog item

```yaml
id: F-VOUCHER-ANOMALY-1
title: Voucher 异常 2 维度 (HJ check_flag parity)
priority: P3
estimated_days: 3
trigger:
  - 大客户 demo 提问 "如何标异常凭证不撤销审核"
  - 客户合同要求复式记账完整 (per Sprint 6+ 候选)
scope:
  - Voucher.abnormalFlag + abnormalReason + abnormalMarkedBy + abnormalMarkedAt 字段
  - Service: markAbnormal / clearAbnormal
  - REST: POST /vouchers/{id}/mark-abnormal + POST /vouchers/{id}/clear-abnormal
  - Vue: VoucherList 加 chip + dialog
  - V*.sql migration + abnormal_at index
  - Test: 2 维独立 transition (审核通过 + 后续标异常 → POSTED + abnormal=1)
spec_link: docs/superpowers/specs/2026-05-19-voucher-anomaly-decision.md
```

### Action items for organizer

- **Sprint 5 W1 不动**, F-VOUCHER-ANOMALY-1 加 P3 backlog
- **Sprint 6 trigger 后** 按本 doc Option A 实施
- **更新 Round 13 audit doc** §2: "vflag 2 维 — Cretas 保单维 (per Z-3 decision), P3 backlog F-VOUCHER-ANOMALY-1"

---

## File:line evidence

| 文件 | 行 | 内容 |
|---|---|---|
| `VoucherFlag.java` | 16-40 | 4-state 单维 (UNCREATED→PENDING→CREATED/FAILED) |
| `VoucherStatus.java` | 7-12 | 3-state 单维 (DRAFT→POSTED→VOID) |
| `Voucher.java` | 95-113 | status / approvedBy / approvedAt 全 single-dim 审核. **无 abnormalFlag 字段** |
| HJ baseline | (`32-doc §E.4`) | check_flag INT 独立, 2 维独立 |

---

## Decision summary

- **Z-3 → 📝 DECIDED: Option B (defer)**
- 不动 code 在 Sprint 5
- 加 P3 backlog F-VOUCHER-ANOMALY-1 (3d, trigger-driven)
- Round 13 §2 audit doc 加 cross-ref 本 decision

---

**Sign-off**: Track Z agent, 2026-05-19
