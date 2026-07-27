---
paths:
  - "web-admin/**"
  - "frontend/**"
---

# 防呆设计 (Fool-Proof Design) 规范

**最后更新**: 2026-05-17
**触发**: 客户原话 (六扇门 F006 仓管员场景): "做仓管的他年纪都比较大文化素质很低, 你不能太依赖他们, 最好的方法就是你告诉他这个东西你要收多少就行了"

---

## 核心原则

**防呆 = "用户犯错前阻止", 不是 "用户犯错后报错".**

设计目标: 减少 仓管员 / 操作员 / 普通用户 的认知负荷. ERP 的实际使用者文化素质参差不齐, UI 必须明确告诉用户"可以做什么 / 应该做什么", 而不是"做错了告诉你错在哪".

---

## 5 大规则 (任何 UI 写操作必遵守)

### Rule 1 — 预先显示边界, 不要事后报错

| ❌ 反面 | ✅ 正面 |
|---|---|
| 用户填完点提交 → toast "超出上限 130" | dialog 打开即显 "下单 100, 已收 X, 可入 Y (含 30% 超收)" + input `:max="Y"` + 超限 disable 提交 |

**适用**: 入库 / 出库 / 调拨 / 完成生产数量 / 开票金额 / 收款金额 / 请假天数 / 报销金额 / 任何带 max 限制的写入操作.

**实施**:
- 后端 API 提供 `getLimits(soId, action)` 返回 `{max, current, canDo}`
- Dialog 打开时立刻 fetch limits + display
- input 加 `:max` + 实时 validate
- 提交 button `:disabled="overLimit || invalid"`
- 提示文字必须具体: "已收 30 件, 还可入 70 件 (含 30% 超收 = 130)"

---

### Rule 2 — 上下文必带身份信息 (品名 / 单号 / 责任人)

| ❌ 反面 | ✅ 正面 |
|---|---|
| dialog "请输入实际产量" + 空 input | dialog 标题 "完成生产 — 叮咚好食光卤猪蹄 200g (SO-20260516-0123)" + 显示 计划数量 200g |

**适用**: 完成 / 取消 / 审批 / 退货 / 删除 / 任何写操作 dialog. AIChat Tool 返回 message 也必带 context.

**实施**:
- Dialog header: `{action} — {品名/物料名} {规格} ({订单号/批次号})`
- 关键计划数字必显: 计划数量 / 已审金额 / 期望交货
- 责任人显示: "{流程名} — 当前节点: {审批人} → 待: {下一审批人}"
- AIChat Tool description 模板: `"为 {entity名} ({编号}) 执行 {action}"`

---

### Rule 3 — 自由文本改约束选择 (dropdown / 联动)

| ❌ 反面 | ✅ 正面 |
|---|---|
| 取消原因纯 textarea (用户瞎写无统计价值) | el-select 标准原因 (客户撤单 / 原料缺货 / 质量问题 / 排程冲突 / 其他) + 选"其他"才显 textarea |

**适用**: 取消原因 / 退货原因 / 审批意见 / 暂停原因 / 质检不合格原因 / ECN 变更原因 / 任何"why" 字段.

**实施**:
- 主因素: enum dropdown (5-10 标准选项 + "其他")
- 选"其他"时 才 v-show textarea (必填补充)
- 数字联动: 输入 2 个值自动算第 3 (e.g. 质检合格数 + 不合格数 → 总数 disable + auto fill)
- 默认 prefill: 常见场景 default selected (e.g. 退货原因 default "质量问题")

---

### Rule 4 — 写操作幂等防重复

| ❌ 反面 | ✅ 正面 |
|---|---|
| 用户重复点"快速出库" → 创建 N 个草稿 DLV 单 | 创建前查同 SO line 已有 PENDING/DRAFT → 409 "已有草稿 DLV-XXX, 是否查看?" + button 跳详情 |

**适用**: 出库 / 收款 / 开票申请 / 创建生产任务 / 调拨 / 退货 / 任何创建写入操作.

**实施**:
- 后端 Service 创建前 `findByBusinessKey + 5min window check`
- 重复 trigger 返 409 + existingEntityId + actionHint
- 前端 catch 409 → ElMessageBox.confirm "已有 {existingId}, 是否跳转查看?" + router.push
- 5min dedup 窗口 (避免 double click) + business key dedup (永久)
- AIChat Tool 同 idempotent: 第 2 次 invoke 返 `{count: 0, existingId: ...}` 不重复创建

---

### Rule 5 — Dead-end 改导航 (cancel 不让用户卡住)

| ❌ 反面 | ✅ 正面 |
|---|---|
| 点 "调拨" → toast "待接调拨流程" (用户懵) | ElMessageBox.confirm "调拨工作流未配置, 是否前去工作流设计器配置?" → `router.push('/system/workflow-designer?entityType=TRANSFER')` 直接跳 |

**适用**: 任何 "X 流程未配置" / "Y 待 Day N" / "暂未开通" placeholder / 数据为空状态 / 权限不足.

**实施**:
- 空状态 component: `<EmptyState :icon="..." :description="..." :action-text="..." @action="goConfig" />`
- 错误状态附 next action button (跳到配置/开通/申请页)
- defer tab placeholder: "此功能 Sprint X 上线 — 当前可用替代方案: ..." + link
- 权限不足: "需要 X 权限 — 请联系管理员开通" + 一键申请 button

---

## 跨规则铁律 (4 位一体)

任何 error toast / 业务规则拒绝 / 4xx/5xx response 必同时满足 4 项:

| # | 检查 | 反面 | 正面 |
|---|---|---|---|
| a | **网络 response.message** | "操作失败" generic | "发货行 51 未完成批次分配, 无法确认发货" |
| b | **UI toast 文案 = 后端 message** | 前端 catch 吞 message 用 fallback "操作失败" | 前端原样 display `e.response.data.message` |
| c | **toast sticky (duration:0 + showClose)** | 3s 自动消失 (流程依赖错误用户错过关键信息) | `ElMessage({ message, type:'error', duration: 0, showClose: true })` |
| d | **含 next action 提示** | message 只说"失败" | message 含 "请先分配批次" / actionHint 字段含跳转 url |

实施代码 (web-admin/src/api/request.ts 已有):
```typescript
const showMessage = async (message, type) => {
  const { ElMessage } = await import('element-plus');
  ElMessage({
    message, type,
    duration: type === 'error' ? 0 : 3000,   // error sticky
    showClose: type === 'error',              // 手动关
  });
};
```

---

## 适用场景速查

| 场景 | 必触发 Rule |
|---|---|
| 入库 / 出库 / 调拨 数量 | Rule 1 (max) + Rule 2 (context) + Rule 4 (幂等) |
| 取消 / 退货 / 暂停 | Rule 2 (context) + Rule 3 (原因 dropdown) |
| 审批 / 审核 | Rule 2 (context: 谁审/审什么) + Rule 3 (意见模板) |
| 开票 / 收款 | Rule 1 (金额 max) + Rule 2 (订单号) + Rule 4 (幂等) |
| 完成生产 | Rule 1 (计划数量 max) + Rule 2 (品名) + Rule 3 (产量 / 合格数联动) |
| 任何"流程未配置" placeholder | Rule 5 (跳到配置页) |
| 错误 toast | 4 位一体 全 4 项 |

---

## Cretas 现状 Audit

按本规范 audit Cretas web-admin / RN 现有写操作场景:
- ✅ 完成生产 dialog 已有品名 (Sprint 3 改进)
- ✅ error toast sticky 已实施 (per QA prompt v2.4 Rule 8)
- ⚠️ 大部分 cancel reason 仍 textarea (Sprint 4 W2 audit 抓)
- ⚠️ 出库幂等 check 漏 (BR-13 历史 bug)
- ⚠️ "未配置" placeholder 多处 dead-end (Sprint 4 应改 next action)

Sprint 4 W2 + Wave 1 9 chat dispatch 必须按本规范设计.

---

## 何时 audit 本规范

- 任何 PR 含 dialog / form / write op → 必 4 位一体 check (per QA prompt v2.4)
- 任何"新建/编辑/审核/取消"button → 必 Rule 1 (max display) + Rule 2 (context) + Rule 4 (幂等)
- 任何 "其他原因" textarea → 必 Rule 3 (改 dropdown)
- 任何空状态 / placeholder → 必 Rule 5 (next action)

PR review 时如发现违反, 阻塞 merge 直到修.

---

## 客户原话证据 (避免推翻规范)

> 张权-昆山 (F006 仓管员场景):
> "做仓管的他年纪都比较大文化素质很低, 你不能太依赖他们, 最好的方法就是你告诉他这个东西你要收多少就行了"

防呆 = Cretas 跟金蝶 / 用友 等 ERP 差异化 (它们 UI 复杂度对仓管员不友好). **本规范是 Cretas product 差异化的核心之一**, 不可妥协.
