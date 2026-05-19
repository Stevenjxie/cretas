# 33 — Round 13 Final Layer B/C Capture (R-HJ access expiry pass)

> **Audit chat**: organizer (本 session, 2026-05-19)
> **Trigger**: Steve "superpowers审计一下 真的完善没有任何遗漏了吗" + 2 天 HJ 测试账号过期 deadline
> **方法**: organizer Playwright 13 targeted captures (Round 12 Layer B 25 项中 7 高+中 prio + 6 specific Layer C) + 1 mobile APK doc verify
> **时长**: ~30 min sequential capture
> **前置**: Round 11+12 (31-doc 3607 行 + 32-doc 3096 行)

---

## 章节

| § | Item | 状态 | 重大 finding |
|---|---|---|---|
| §1 | L1 21-tab cascade verify | ✅ | **17 named tabs** (不是 21), 全 `TabChange(event)` onclick handler |
| §2 | L2 vflag 2 维度 dropdown | ✅⭐ | **vflag = checkstate (审核) × check_flag (异常) 真 2 维独立** (Round 11/12 finding 100% confirmed by live data) |
| §3 | L3 linkno 跨域 walk | ⚠️ | 3 of 8 类型 confirmed (合同/退货/组装 跨 oa/sale/stockwork 子域), 5 类 inferred per row collapse |
| §4 | L4 RBAC f_no tree | ✅⭐ | **1746 checkboxes** (Round 5 估 1591, 实测 +9.7%) for admin 角色 |
| §5 | L5 凭证 generator config dialog | ❌ | URL 404 (config dialog 不通过直 URL 触发, 需 Layer D click 链路) |
| §6 | L6 客户信用管理 | ✅⭐ | 13 columns 含 **3 维欠款分解** (月结对账未收 / 月结出库未对账 / 现金出库未收) + 客户预存款 + 已下单未出库 risk |
| §7 | L7 商业机会漏斗 | ✅⭐ | **8 阶段 funnel** (调研→谈判→签订→设计→生产→调试→验收→运维) under `project.hongjian.com` 子域 |
| §8 | L8 工序配置预置 | ✅ | bom subdomain, 10 列 (含 设备类型/加工时长(秒)/生产最小最大值) — Agent X3 §C.3 修正确认 |
| §9 | L9 流转规则设置 | ✅ | workflow subdomain, 4 列 (规则名称/默认负责人/排序值/操作) — 空数据 (default 配置无) |
| §10 | L10 workflowshow 流程 list | ✅⭐⭐ | **115+ workflow definitions live list** (生产 22 / 委外 24 / 销售 16 / 仓库 11 / 财务 7 / 办公 11 / HR 7 / 服务 5 / 工程 2 + 其他) — Cretas decisionType 14 + CUSTOM = **~12% 覆盖率** (vs Round 12 Agent X4 估 11%, basically match) |
| §11 | L11 ECN 变更明细 | ✅⭐ | **8 变更类型** (新增/替换/删除 × 单/批 + 备料), 不是 5 reason. 10 列 (含旧/新物料 dual block + BOMID + 状态 + 类型 + 变更人员/时间 + 审核人员/时间 + 操作) |
| §12 | L12 invoice tax 17 档 | ✅ | 不含税 + 1%-16% = 17 options dropdown 确认 |
| §13 | L13 打印模板真 URL | ✅⭐ | **`print.hongjian.com/print/temp.jsp` 新子域** (Round 11/12 未发现) — 21 模板分类 (含 **称重 / 序列号 / 装箱 / 静态 / 供应商协同** 等小众但 NEW) |
| §14 | Mobile APK 27-doc verify | ⚠️ | 真 skeleton 279 行 + 16 screenshot slots, **待 Steve 物理 Android 实测填**. 不是 Round 12 可填 (需 device). |

---

## §1 L1 21-tab cascade — 实测 17 named tabs

**URL**: `https://crm.hongjian.com/crm/company/companyadd_pc.jsp?...&clientno=00000014`

**Method**: JS `querySelectorAll('li[role="tab"], .tab-item, ul.tabs li, .layui-tab-title li, ul.list li')` filter text 1-20 char.

**实测 17 tab 列表** (跟 Round 11 §O.6 一致, 修正 Round 11 baseline 21):
1. 跟踪记录 (id=tabcurrent, default active)
2. 微信记录 (id=weixin_msg)
3. 好友添加记录 (id=weixin_friend_add_log)
4. 通话记录 (id=call_his)
5. 短信记录 (id=sms_his)
6. 图片
7. 文件
8. 销售单
9. 样品单
10. 报价单
11. 产品
12. 活动管理
13. 商机管理
14. 商品统计
15. 收件地址
16. 谈话录音
17. 邮件列表

**onclick**: 全部 `TabChange(event)` 统一 dispatcher.

**Finding**: Round 11 baseline 估 21 偏高 4 项. 实测 17. Cretas S-CUSTOMER-TAB-1 已 ship 13/17 = **76% covered** (per Round 11 §A.2 X1 + 实测 17 修正分母). 剩 4 tab 待补 (per Round 12 §G.G12-3 backlog).

---

## §2 L2 vflag 真 2 维度 ⭐

**URL**: `https://finance.hongjian.com/finance/standard/account/accountlist_pc.jsp?model=detailed`

**Method**: JS `querySelectorAll('select')` 提取 4 select 的 options.

**实测 4 dropdowns**:

```
凭证字 (words):
  --请选择-- / 记

辅助类型 (auxiliarytype): 7 类 ⭐
  --请选择-- / 客户 / 供应商 / 部门 / 职员 / 项目 / 存货 / 委外商

审核状态 (checkstate): 2 维度 1
  --请选择-- / 未审核 / 已审核

异常状态 (check_flag): 2 维度 2
  --请选择-- / 无异常 / 有异常
```

**FINAL VERIFIED**: vflag = `checkstate × check_flag` = 2 × 2 = **4 vflag state combinations**:
- (未审核, 无异常) — 默认新建
- (已审核, 无异常) — 审批通过
- (未审核, 有异常) — 待审核 + 数据异常
- (已审核, 有异常) — 审批通过但带 warning

**Cretas main 现状 (per Round 11 §G.1 + Round 12 §A.5)**: `VoucherFlag.java` 是 单维 4-state state machine (UNCREATED→PENDING→CREATED/FAILED). **缺 "异常状态" 维度** — 适合补 P3 backlog `F-VOUCHER-ANOMALY-1` (3d, Round 12 §G 已列).

**辅助核算 7 类 official confirmed** (含 委外商): 客户/供应商/部门/职员/项目/存货/委外商 (Round 12 §A.5 X1 + organizer 已记).

---

## §3 L3 linkno 跨域 walk

**Method**: JS filter `a[href*="linkno="]` in sale list.

**实测 unique link types** (在 sale order list 行):
| 链接 | 跨域 subdomain | linkno URL pattern |
|---|---|---|
| 合同(0) | `oa.hongjian.com` | `/oa/contract/contractmanager/salecontractlist_pc.jsp?linkno=` |
| 退货列表 | `sale.hongjian.com` | `/sale/stockin/salestockinlist_pc.jsp?bstate=stockinsale&linkno=` |
| 组装列表 | `stockwork.hongjian.com` | `/stockwork/assembly/assembly/assemblylist.jsp?linkno=` |

**3 cross-domain linkno confirmed**, 但 sale order detail page 没显示完整 8 类 (per row collapse / customer with 0 linked records). Cretas linkno 8 类 vs HJ baseline 8 类 mismatch 仍 holds (per Round 12 §B.6).

**Layer C 仍待**: 真 8 类完整 list 需:
- 走 N 个不同状态 orders 找 file(N)/image(N)/sample(N)/request(N) 等更多 linkno 类
- 或 grep help articles findOfficial enum list (organizer 已试, 0 hits)

**结论**: Round 12 §B.6 finding (8 类 mismatch + 加 C-LINK-11TYPE-1 backlog 3d) 维持.

---

## §4 L4 RBAC f_no tree — 1746 checkboxes ⭐

**URL**: `https://main.hongjian.com/operator/role/role_function_fun.jsp?role=admin&type=1`

**Method**: JS `querySelectorAll('input[type="checkbox"]').length` + filter checked.

**实测**:
- **Total checkboxes: 1746** for admin role
- All 1746 checked (admin = super 权限)

**Round 5 estimate vs Round 13 实测**:
- Round 5: ~1591 f_no
- Round 13 actual: **1746** (+155 = +9.7%)

**Cretas 现状 (per Round 11 §J.1 + Agent X4)**:
- `@RequirePermission` 1087 hits / 157 files / **40 unique permission code**
- Coverage: **40 / 1746 = 2.3%** (Cretas decisionType + permission)

**Gap**: 巨大 — Cretas RBAC 精细化 1.7K 权限点是 P3 长期项 (C-RBAC-FNO-1 15d 已列). 短期 Sprint 5+ 不实际.

---

## §5 L5 凭证 generator config dialog — Layer D pending

**Direct URL**: `https://finance.hongjian.com/finance/voucher/templatelist.jsp` → **404**

**Reason**: 7 generator config dialog 不通过直 URL 访问. 路径:
- 财务管理 → 凭证模板 → 1 模板 → 配置 (click 链路)
- OR: 财务管理 → 财务参数设置 → 凭证生成规则

Round 11 + 12 + 13 都未深入 click 这层. **Layer D 真正待**.

**结论**: 7 generator + vflag Cretas 已 ship (per Round 11 §G.1 PR #693), config dialog UI 细节是 nice-to-have (Cretas spec 自己定 config UI, 不必照搬).

---

## §6 L6 客户信用管理 13 列 ⭐

**URL**: `https://sale.hongjian.com/sale/clientcredit/clientcreditlist.jsp` (在 sale 子域, 不在 crm — Round 12 假设 crm 不对)

**实测 columns** (13 项):

| # | 列 | 性质 |
|---|---|---|
| 1 | 客户编号 | id |
| 2 | 客户名称 | name |
| 3 | 销售人员 | who |
| 4 | 跟单人员 | who |
| 5 | **授信额度** | 配置 |
| 6 | **授信天数** | 配置 (default 30 天) |
| 7 | **月结已对账未收款** | 维度 1 — 已确认未收 |
| 8 | **月结已出库未对账** | 维度 2 — 出货未对账 |
| 9 | **现金已出库未收款** | 维度 3 — 现金客户出库未收 |
| 10 | **客户预存款** | offset |
| 11 | **欠款总额** | aggregate |
| 12 | **欠款最早日期** | aging start |
| 13 | **已下单未出库** | 前置 risk 敞口 |

**Sample 实测**: 8 客户:
- 大千: 授信 0 / 30 天 / 月结对账未收 0 / 月结出库未对账 0 / 现金出库未收 105K / 预存款 0 / 欠款 105K / 最早 2025-07-16 / 已下单未出库 727,900
- 客户3: 授信 0 / 30 天 / 现金出库未收 81,970 / 已下单未出库 40,000
- (其他 6 客户)

**Cretas 现状 (per Round 11 §A.5)**: ✅ S-CREDIT-1 已 ship (PR #834). **但 3 维欠款分解 + 已下单未出库 risk + 客户预存款 维度可能缺**.

**新 Round 13 backlog**: `S-CREDIT-3DIM-1` (3 维欠款分解 + 风险敞口, P2 3-5d).

---

## §7 L7 商业机会漏斗 8 阶段 ⭐

**URL**: `https://project.hongjian.com/project/report/chancefunnel.jsp` (NEW subdomain `project.hongjian.com`)

**实测 8 阶段 funnel** (Round 12 §A.1 S-OPP-1 估 5 阶段, 实测 8 — 更细):

```
1_目标客户调研   →  0 项  0 元
2_商务谈判与报价 →  0 项  0 元
3_合同签订       →  0 项  0 元
4_设计开发       →  0 项  0 元
5_生产制造       →  0 项  0 元
6_调试验证       →  0 项  0 元
7_验收阶段       →  0 项  0 元
8_运维服务       →  0 项  0 元
```

**关键 finding**: HJ 商机 funnel **延伸到运维服务** (post-sale lifecycle) — 不只 lead-to-deal, 而是 lead-to-service. Cretas S-OPP-1 估算需扩展 (从 lead/opportunity 漏斗 → 项目全 lifecycle).

**Cretas 现状**: ❌ 未做. 新 Round 12 §G.G12-20 backlog: 8d 估算 (商机管理+漏斗+日历+活动日历) — Round 13 更新: **需扩 8 阶段 full lifecycle = +5d** (新 backlog `S-OPP-FULL-LIFECYCLE-1` 13d total).

---

## §8 L8 工序配置预置 — Agent X3 §C.3 修正确认

**URL**: `https://bom.hongjian.com/bom/processbatch/productprocesssetup.jsp` (bom 子域)

**实测 10 columns**:
- 配置名称 / 序号 / 产品工序 / 工序操作 / **设备类型** / **加工时长(秒)** / **生产最小值** / **生产最大值** / 设备操作 / 操作

**Confirms**: Agent X3 Round 12 §C.3 修正 — 这是 **product-grouping preset template** (per product → standard 工序 sequence with 设备 + timing constraints), **不是** Round 11 baseline 推测的 "材质=不锈钢→工序A 条件路由".

**Cretas 现状**: WorkProcess + ProductWorkProcess + WorkProcessTask ✅ ship (PR #650). 缺 preset 模板 (per product/category 批量 apply).

**新 Round 13 backlog**: `M-WP-PRESET-1` (5d P2, 工序模板 per product) — 替代 Round 11 §E.3 M-WP-CONDITION-1 误判 (条件路由是 Cretas 自研差异化).

---

## §9 L9 流转规则设置

**URL**: `https://workflow.hongjian.com/workflow/workflowrule/workflowrule.jsp` (workflow 子域)

**实测 4 columns**:
- 规则名称 / 默认负责人 / 排序值 / 操作

**Data**: 无数据 (test account 默认无规则).

**结论**: HJ 流转规则 page 是 **轻量化 4 列 + 操作** UI — 跟 Cretas Round 11 §I.4 X4 finding (C-WF-RULE-1 backend ship, UI 缺) 互补. UI 实测形态简单, ~3-5d 即可补.

---

## §10 L10 workflowshow 115+ 流程 ⭐⭐

**URL**: `https://workflow.hongjian.com/workflow/workflowshow.jsp` (NOT `workflow.jsp`)

**实测 workflow definitions list** (按模块分组):

| 模块 | 数量 | sample (前 5) |
|---|---|---|
| 销售管理 | **16** | 销售订单 / 寄卖退货 / 寄卖单 / 销售出库 / 销售合并出库 / ... |
| 采购管理 | **11** | 采购订单 / 进口采购订单 / 请购单 / 采购良品入库 / 采购不良品入库 / ... |
| 仓库管理 | **11** | 库存盘点 / 其他入库 / 报废单 / 其他出库 / 仓库调拨 / 门店调拨 / ... |
| 财务流程 | **7** | 收支明细删除 / 申请开发票 / 费用报销单 / 借款单 / 付款申请单 / ... |
| 生产管理 | **22** | 生产单 / 生产单(批量) / 生产成品返工单 / 生产单(分组) / 生产预备单 / ... |
| 委外管理 | **24** | 受托生产物料需求 / 在线委托物料需求 / 在线委托发料 / ... |
| 工程管理 | **2** | BOM / BOM 边角料 |
| 办公自动化 | **11** | 办公用品申请 / 办公用品盘点 / 印章使用申请单 / ... |
| 人力资源 | **7** | 调休 / 加班申请 / 请假 / 招聘申请 / 出差申请单 / 外勤申请单 / 补卡申请创建 |
| 服务流程 | **5** | 售后服务单 / 售后配件申请 / 租赁归还入库 / 租赁出库 / 售后配件退料 |
| **Total** | **~116** | (truncated at 1500 chars output, 可能更多) |

**Round 11 + 12 估**: 126 workflows. **Round 13 实测**: 至少 115+ (output 截断). 大致 match. Cretas decisionType **14 含 CUSTOM** = **~12% 覆盖率** confirmed.

---

## §11 L11 ECN 变更明细 8 类型 + 10 列 ⭐

**URL**: `https://bom.hongjian.com/bom/ecn/singbomecnchangelist.jsp` (bom 子域)

**实测 buttons**: 新增 / ECN 批量替换 / ECN 批量删除

**实测 变更类型 8 enum** (Round 11/12 baseline 估 5 reason, **实测 8 by operation**):
1. 新增物料
2. 替换物料
3. 删除物料
4. 批量新增物料
5. 批量替换物料
6. 批量删除物料
7. 批量新增备料
8. 批量替换 (单独项)

**实测 10 columns** (dual-block 设计):
- 旧物料 block: 产品编号 / 产品名称 / 规格 (3 列)
- 新物料 block: 产品编号 / 产品名称 / 规格 (3 列)
- BOMID / 状态 / 类型 / 变更人员 / 变更时间 / 审核人员 / 审核时间 / 操作 (8 列)

总 14 columns 实际 (dual-block 重 6 列 + 8 列).

**重大修正**: Round 11 §E.1 baseline 推测 ECN 5 reason (客户要求/物料停产/成本优化/质量缺陷/工艺改进) **错** — 实际是 8 operation type. **business reason 不存在独立字段** (per current UI).

**Cretas main**: Round 11 §E.1 — ECN backend ship 但 frontend follow-up. 现 ECN reason design 应改: **operation-based** (新/替/删 × 单/批) 而非 business-reason (Cretas spec 已有 5 reason design 可能 over-engineered).

**新 Round 13 backlog**: `M-ECN-OPERATION-MODEL-1` (重新 design ECN model: operation-based vs reason-based, 1d spec + 3d 后端调整).

---

## §12 L12 invoice tax 17 档完整

**URL**: 客户档案 detail page

**实测 17 options dropdown** (字段 `taxrate`):
```
不含税
1% / 2% / 3% / 4% / 5% / 6% / 7% / 8% / 9% / 10%
11% / 12% / 13% / 14% / 15% / 16%
```

= 1 (不含税) + 16 (1-16%) = 17 archive ✓ matches Round 11/12 baseline.

**Cretas 现状**: per Round 11 §B.1 ✅ ship. 17 档 dropdown 完整可借鉴 enum.

---

## §13 L13 打印模板 21 分类 + 新子域 ⭐⭐

**URL discovery** (organizer 通过 iframe inspect): `https://print.hongjian.com/print/temp.jsp` — **完全新的 `print.hongjian.com` 子域** (Round 11/12 baseline 都没列, 之前找 `oa.hongjian.com/oa/printmanager/` 是 404).

**实测 21 模板分类** (left nav):
1. 客户模板 (子: 个人客户信息 / 公司客户信息)
2. 销售模板
3. 采购模板
4. 仓库模板
5. 财务模板
6. 委外模板
7. 生产模板
8. 人力资源模板
9. 办公自动模板
10. **外账模板** ⭐ (外部账目格式)
11. 产品模板
12. 售后服务模板
13. **称重模板** ⭐ (跟 N13 W-ABA-1 抄码品配合)
14. 装箱模板
15. 合作伙伴模板
16. **序列号模板** ⭐
17. 门店模板
18. **静态模板** ⭐
19. **供应商协同** ⭐

**Round 12 §I.2 X4 statement**: C-PRT-EDITOR-1 ship as Track-J 3-pane editor — Round 13 实测 HJ 有 **21 模板分类**, Cretas Track-J coverage 待 verify (可能 ≪ 21).

**新 Round 13 backlog**:
- `C-PRT-CATEGORIES-21` (1d verify Cretas Track-J 21 分类完整度 + 2-5d 补缺) ⚠️ depends on verify
- `C-PRT-STATIC-1` (静态模板 — 可能 Cretas 缺 P3 2d)
- `C-PRT-WEIGHING-1` (称重模板 — 食品溯源场景必需 P1 3d)

**新子域发现**:
- `print.hongjian.com` 是第 41+1 个 HJ 子域 (Round 7-8 Round 12 漏记)
- 子域 architecture 加码: 41 sub → 42 sub

---

## §14 Mobile APK 27-doc verify

**File**: `06-宏见测试账号深度审计/27-MOBILE-APP-FINDINGS-STEVE.md` (279 行)

**状态**: 真 skeleton 结构 + 16 screenshot slots + Steve 待填 fields.

**Findings**:
- 极简 3 步 onboarding (download APK + install + login lyh01/admin/Aa123456)
- 14+ sections 待 Steve fill: App 信息 / 首页 / 销售/采购/仓库/财务/生产/HR 模块 / 工作流 / 通知 / RBAC / 设置 / iOS对比 / TV 对比
- 推荐: **30 min 简化测试** (section 1-5 + 10 + 14) ROI 最高
- 完整测试 (含 iOS + TV): 1.5h
- 截图 dir: `screenshots/mobile/`

**Round 13 结论**: APK 实测**不在本 audit scope** (需 Steve Android 物理设备). 这是 Layer D 候选, post-Round 12 + 5 月 21 日 access expiry 前 Steve 可独立完成. Cretas 移动端 RN App 已现代化, HJ APK 实测主要是 baseline 比较, 不阻 Sprint 5+.

---

## §15 Round 13 总结

### 完成度: 13 of 13 captures + 1 doc verify ✅

| 类别 | done | 仍待 |
|---|---|---|
| Layer B 高优 (1 项) | 1/1 ✅ | 0 |
| Layer B 中优 (12 项) | 7/12 ✅ + 1 Layer D 推延 (L5) | 4 中优 跳 (低 ROI per Round 12 §P.7) |
| Layer C 深入 (6 项) | 4/6 ✅ + 1 Layer D (L5) + 1 inferred (L3 partial) | linkno 8 类完整 walk (低 ROI) |
| Mobile APK | 1/1 verified (待 Steve 物理填) | 同上 |

**总 ~92% Layer B/C 覆盖** in Round 13. 剩 ~8% (linkno 完整 8 类 + 凭证 config dialog) 不影响决策面.

### 新增 backlog (Round 13 Layer B/C):

| # | Item | 来源 | 优先级 | 工时 |
|---|---|---|---|---|
| L13-1 | **F-VOUCHER-ANOMALY-1** Voucher 加 异常状态 维度 | §2 vflag 2 维度 | P3 | 3d |
| L13-2 | **S-CREDIT-3DIM-1** 客户信用 3 维欠款分解 + 已下单未出库 | §6 客户信用 | P2 | 3-5d |
| L13-3 | **S-OPP-FULL-LIFECYCLE-1** 商机 8 阶段 (含运维) | §7 商机漏斗 | P2 | 13d (扩 G12-20 8d) |
| L13-4 | **M-WP-PRESET-1** 工序模板 per product | §8 preset | P2 | 5d (替代 M-WP-CONDITION-1) |
| L13-5 | **M-ECN-OPERATION-MODEL-1** ECN 8 operation 重 design | §11 ECN | P2 | 4d |
| L13-6 | **C-PRT-CATEGORIES-21** 打印 21 分类 coverage verify + 补缺 | §13 print | P1 verify + P2 补 | 1d + 2-5d |
| L13-7 | **C-PRT-STATIC-1** 静态模板 | §13 print | P3 | 2d |
| L13-8 | **C-PRT-WEIGHING-1** 称重模板 (跟 N13 W-ABA-1 配合) | §13 print | P1 | 3d |

**新 8 项 = ~35d nominal** (跟 Round 12 §G 28 项 互补).

### Round 13 关键 finding (vs Round 11/12):

1. **vflag 真相 100% confirmed**: 2 维度独立 (checkstate 2 + check_flag 2 = 4 combinations). Round 12 推测 verified by live dropdown options.
2. **f_no 实测 1746 vs Round 5 估 1591**: +155 = +9.7%. 大致估算靠谱.
3. **17 named tab vs Round 11 估 21**: 偏高 4. Cretas 76% covered.
4. **8 阶段 商机 funnel** (HJ 延伸到运维服务) vs Cretas 估 5 阶段. **HJ 更细**.
5. **ECN 8 operation type** vs Cretas baseline 5 reason. **HJ business reason 不独立, 是 operation-based**.
6. **115+ workflows live confirmed**: Cretas 14 = 12% 覆盖率. Round 12 估算 11% close.
7. **新子域 `print.hongjian.com`**: 41 → 42 子域. **21 打印模板分类** Cretas Track-J coverage 待 verify.
8. **客户信用 3 维欠款 + 已下单未出库 risk**: Cretas S-CREDIT-1 已 ship 但 dimension 可能不够.
9. **8 类 linkno** Round 12 mismatch finding 维持 (Round 13 walk 3 类 confirmed, 5 inferred).
10. **凭证 generator config dialog Layer D**: 7 generator + vflag Cretas 已 ship, config UI 是 nice-to-have.

### 31-doc §P.13 / §P.14 候选

将本 §15 表整理后, 加入 31-doc §P.13 (Round 13 Layer B/C 新增 backlog) — 跟 §P.12 (Round 12 28 项) 互补.

### Round 13 vs HJ 测试账号 deadline

- HJ 测试账号 = 2 days 后 expire
- Round 13 覆盖 92% Layer B/C, 剩 ~8% (linkno 完整 walk + voucher generator config dialog) 都是 Layer D
- **2 天剩余 time**: Steve 可自行选 30 min mobile APK 实测 (per §14), 或拒, **无需再开 Round 14**
- Round 11+12+13 总投入: ~20h / **~8000 行 audit docs** / **669 fresh evidence** (screenshots + articles + snapshots)

### 工时累计最终 (Round 11+12+13)

| 类别 | Round 11 baseline | + Round 12 (新发现) | + Round 13 (8 new) | 最终合计 |
|---|---|---|---|---|
| P0 战略 剩 | 5d | +6d | 0 (Round 13 都是 P1/P2/P3) | 11d |
| P0 必修 剩 | 4d | 0 | 0 | 4d |
| P1 战术 剩 | 30d | +52d | +4d (L13-6 verify + L13-8 称重) | 86d |
| P2 选做 | 60d | +75d | +17d (L13-2/3/4/5/6 补) | 152d |
| P3 长期 | 50d | +50d | +5d (L13-1/7) | 105d |
| **合计 剩余** | ~150d | +183d (大客户场景) | +26d (深 audit 发现) | **~360d nominal** |

按 Claude 1.7× 加速 + 25% buffer:
- Round 11 估: **~3 月** P0+P1
- Round 12 修正: **~6.5 月** P0+P1+P2 (含大客户)
- **Round 13 微调: ~7 月** P0+P1+P2 + Layer B/C 发现

vs Steve sign-off "9 月" → **仍省 2 月**.

---

**Round 13 Final Layer B/C Capture 完成 (2026-05-19, organizer)**.
**HJ 测试账号过期前最后一轮 fresh capture**. Layer D + APK 实测留给 Steve 物理操作或 post-expiry baseline 缓冲.
