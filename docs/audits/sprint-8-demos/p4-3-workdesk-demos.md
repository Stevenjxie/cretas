# Sprint 8 P4 三大 Workdesk Demo Scripts

**目的**: Boss 演示弹药 #4/#5/#6 — Cretas vs HJ 防呆护城河证明.
**预期时长**: 各 3 min mp4 (~2 min 真流程 + 1 min 解说).
**预期执行人**: Steve 亲跑 (本 doc 为录屏 script, 真录屏在 deploy 后).
**场景**: F006 六腾门卤味店, 3 个不同角色 Workdesk demo.
**仿照**: P3 `p3-food-safety-recall-demo-script.md` pattern.

---

## Demo 1: P4.1 仓管员工作台 (3 min)

### 场景背景
仓管员张权 (F006), 客户原话: "做仓管的他年纪都比较大文化素质很低, 你不能太依赖他们, 最好的方法就是你告诉他这个东西你要收多少就行了" — **防呆 R1 灵魂场景**.

vs HJ: 仓管员要点"采购订单 → 找到 PO → 看订单数量 → 心算还差多少 → 输入实收 → 提交 → 报错超收 → 再修改 → 再提交"
Cretas: AI 直接说"今天要收 X 公斤", 仓管员看 max 边界一键签收 < 30 sec.

### 前置条件 (录屏前确认)
- [ ] **PR Merged**: `sprint8/p4a-warehouse-keeper-workdesk` → main + V20260820_08
- [ ] **Deploy**: `./scripts/deploy/deploy-backend.sh --env all` + web-admin
- [ ] **Test Account**: F006 prod / `warehouse_mgr1` / 密码见 `.env.test`
- [ ] **测试数据准备**:
  - PO-20260520-001 (猪蹄 100 公斤) 状态 PARTIALLY_RECEIVED, 已收 30 公斤
  - PO-20260520-002 (生姜 20 公斤) 状态 PENDING_RECEIVE, 已收 0
  - PO-20260520-003 (大葱 15 公斤) 状态 PENDING_RECEIVE

### Demo Steps + 旁白

#### Step 1: 登录 + 进入工作台 (10 sec)
旁白: "F006 六腾门, 仓管员张权登录".

操作:
1. 浏览器打开 `https://admin.cretaceousfuture.com`
2. 输入账号 `warehouse_mgr1` / 密码
3. 登录后从 sidebar 点 `🏭 仓管员工作台`

#### Step 2: AI 自动列今天待收 (5 sec)
旁白: "进入页面 AI 自动回答 '今天要收什么货', 3 sec 内出待收清单".

预期看到:
- 默认输入框 "今天要收什么货?"
- 自动触发 → AI 显:
  - 卡片 1: 猪蹄 100 公斤 (已收 30 / 可入 70 + 超收 30%)
  - 卡片 2: 生姜 20 公斤 (未收 / 可入 26 含超收)
  - 卡片 3: 大葱 15 公斤 (未收 / 可入 19.5)

#### Step 3: 一键签收 (15 sec)
旁白: "点 [一键签收] 猪蹄 50 公斤".

操作:
1. 点猪蹄卡的 [✅ 一键签收] 按钮
2. Dialog 自动弹:
   - "签收 PO-20260520-001 — 猪蹄"
   - 显示: 已订 100 / 已收 30 / 还可入 70 (含 30% 超收 = 130 max)
   - 输入框 `:max="130"`, 默认 prefill 70 (剩余)
3. 改输入框为 50, 点 [预览]
4. 预览显: "✅ 入库 PO... — 猪蹄 50公斤. 创建后行项目累计 80公斤 / 100公斤. 确认提交?"
5. 点 [确认入库]
6. Toast 绿色 success: "📦 入库 PO... — 猪蹄 50公斤 已创建草稿单"

#### Step 4: 临期物料处置建议 (15 sec)
旁白: "AI 同时建议临期物料处置".

操作:
1. 切换到 [临期处置] tab
2. 显: 老蒜片库存 8 公斤, 距过期 3 天
3. AI 建议: "调拨到 b-加工车间 / 降价 50% 出货 / 退货供应商"
4. 点 [调拨] → 跳到调拨页 + 预填

#### Step 5: 价值对比 (10 sec)
旁白: "整个流程 30 sec vs HJ 系统 2-3 min, 而且防呆边界 + 一键签收 仓管员零认知负担".

录屏文件: `sprint-8-demos/p4-1-warehouse-keeper.mp4`

---

## Demo 2: P4.2 采购员工作台 (3 min)

### 场景背景
采购员小赵 (F006): "下周采购什么? 我自己点 5 个菜单, 看库存, 算销售, 看供应商, 比价 ... 1 小时才能下决定". Cretas: AI 30 sec 综合 5 维度 + 一键请购单.

vs HJ: 采购员 4 菜单 (库存 / 销售预测 / 供应商 / 价格历史) + Excel 算
Cretas: 1 屏 5 品类预警 + 综合 AI 建议 + [一键请购] preview → 提交

### 前置条件
- [ ] **PR Merged**: `sprint8/p4b-purchaser-workdesk` → main + V20260820_09
- [ ] **Deploy**: 同上
- [ ] **Test Account**: F006 prod / `purchase_mgr1` / 密码见 `.env.test`
- [ ] **测试数据**:
  - 5 品类库存预警: 猪蹄 / 生姜 / 大葱 / 酱油 / 八角
  - 历史采购价 (近 30 天) + 供应商 ETA
  - 销售订单 (近 7 天 + 预测 7 天)

### Demo Steps + 旁白

#### Step 1: 登录 + 进入工作台 (10 sec)
旁白: "F006, 采购员小赵登录".

操作:
1. 登录 → sidebar 点 `🛒 采购员工作台`
2. 默认输入 "下周采购什么?"

#### Step 2: AI 综合 5 维度 (10 sec)
旁白: "AI 串 5 Tool: 库存预警 + 销售预测 + 供应商 ETA + 价格历史 + 临期物料 (避免重复采购)".

预期看到:
- 5 品类预警卡 (红/黄/绿):
  - 猪蹄 🔴 (库存 5 公斤, 下周预测 80 公斤, ETA 2 天, 价格 38.5 元/kg)
  - 生姜 🟡 (库存 12 公斤, 预测 20 公斤, ETA 1 天)
  - 大葱 🟡
  - 酱油 🟢 (库存充足)
  - 八角 🔴 (临期 + 库存低)

#### Step 3: 一键生成请购单 (20 sec)
旁白: "点猪蹄卡的 [一键请购] preview → 确认".

操作:
1. 点 [一键请购] 在猪蹄卡
2. Dialog 弹:
   - "请购单 — 猪蹄 80 公斤"
   - 供应商 default = 王老板 (历史交期最快 2 天 + 单价最低)
   - 单价 38.5 元/kg, 总价 3080 元
3. 点 [预览]
4. 预览显: "✅ 创建请购单 PR-20260520-XXX, 物料 猪蹄 80 公斤, 总价 3080 元. 创建后流转至采购审批"
5. 点 [确认请购]
6. Toast: "📋 请购单 PR-20260520-XXX 已创建"

#### Step 4: 价值对比 (10 sec)
旁白: "采购员 30 sec 下决定 vs HJ 1 小时, 而且 AI 还防误采 (临期物料 + 库存高 自动避免)".

录屏文件: `sprint-8-demos/p4-2-purchaser.mp4`

---

## Demo 3: P4.3 质量主管工作台 (3 min) — Sprint 8 收尾

### 场景背景
质量主管李工程师 (F006): "卤猪蹄 100 公斤刚生产完, 我得点 4 菜单 (质检记录 / HACCP 监控 / 添加剂合规 / 客户标准) 综合判断 — 一批要 10 min". Cretas: AI 综合 4 维度 + 一键放行/退货.

vs HJ: 4 菜单 + 手工综合 (10 min/批 × 20 批/天 = 3.3 小时/天)
Cretas: 1 屏综合判断 + 一键决策 (30 sec/批 × 20 批 = 10 min/天) — 节省 3 小时

### 前置条件
- [ ] **PR Merged**: `sprint8/p4c-quality-and-llm-tuning` → main + V20260820_10
- [ ] **Deploy**: `./scripts/deploy/deploy-backend.sh --env all` + web-admin
- [ ] **Test Account**: F006 prod / `quality_mgr1` / 密码见 `.env.test`
- [ ] **测试数据**:
  - 3 批 INSPECTING 状态批次:
    - B-20260520-A01 (卤猪蹄 28 kg) — 质检通过 + HACCP 通过 + 添加剂合规 → 可放行
    - B-20260520-A02 (卤牛肉 35 kg) — HACCP 偏离 (冷却 2h) → 不可放行, 建议退货
    - B-20260520-A03 (卤鸡爪 15 kg) — 添加剂超限 (亚硝酸盐 35 mg/kg > 30) → 不可放行
  - HACCP / 添加剂 / QualityInspection 关联数据已 seed

### Demo Steps + 旁白

#### Step 1: 登录 + 进入工作台 (10 sec)
旁白: "F006, 质量主管李工程师登录".

操作:
1. 浏览器打开 `https://admin.cretaceousfuture.com`
2. 输入账号 `quality_mgr1` / 密码
3. 登录后 sidebar 点 `🔬 质量主管工作台`

预期看到:
- 蓝色 header `🔬 质量主管工作台` + Sprint 8 P4c tag
- AI 输入框默认 "今天哪些批次待放行?"
- 自动触发查询

#### Step 2: AI 综合 4 维度展示 (10 sec)
旁白: "AI 自动列 3 批待放行 + 4 维度 audit icon".

预期看到:
- 3 张批次卡片:
  - B-20260520-A01 卤猪蹄 (✅ 质检 ✅ HACCP ✅ 添加剂 ✅ 客户标准) → [✅ 一键放行] 绿色
  - B-20260520-A02 卤牛肉 (✅ 质检 ❌ HACCP ✅ 添加剂 ✅ 客户标准) → [✅ 一键放行] 禁用 + [❌ 退货] 红色启用
  - B-20260520-A03 卤鸡爪 (✅ 质检 ✅ HACCP ❌ 添加剂 ✅ 客户标准) → [❌ 退货] 红色启用

#### Step 3: 一键放行 (15 sec)
旁白: "B-A01 4 项全通过, 点 [一键放行]".

操作:
1. 点 B-20260520-A01 卡的 [✅ 一键放行] 按钮
2. Dialog 弹:
   - 标题: "✅ 放行批次 — B-20260520-A01 (卤猪蹄)"
   - 批次号 / 物料名 disabled 显示
   - 决策 tag: "放行 (INSPECTING → AVAILABLE)" 绿色
   - 备注: 默认填 "综合 audit 通过, 放行" (R3 prefill)
3. 点 [预览]
4. 预览显: "🔒 [BLOCKING] 确认决策 → 批次 B-20260520-A01 (28 kg), 从 INSPECTING 变更为 AVAILABLE. 放行后可用于生产/出货. 此决策记入 audit log 不可撤销."
5. 点 [确认放行]
6. Toast: "✅ 批次 B-20260520-A01 决策已执行: INSPECTING → AVAILABLE"
7. 卡片自动消失, 重新查询

#### Step 4: 一键退货 — HACCP 偏离 (20 sec)
旁白: "B-A02 HACCP 冷却 2h 偏离, 不可放行, 点 [❌ 退货]".

操作:
1. 点 B-20260520-A02 卡的 [❌ 退货] 按钮
2. Dialog 弹:
   - 标题: "❌ 退货批次 — B-20260520-A02 (卤牛肉)"
   - 决策 tag: "退货 (INSPECTING → DEFECTIVE)" 红色
   - 备注: 必填 ✶ (R3 退货必填理由)
3. 备注框输 "HACCP 冷却 2h 超限, 微生物风险"
4. 点 [预览]
5. 预览显: "🔒 [BLOCKING] 确认决策 → 批次 B-20260520-A02 (35 kg), 从 INSPECTING 变更为 DEFECTIVE. 退货后不可用, 待后续处置."
6. 点 [确认退货]
7. Toast: "⛔ 批次 B-20260520-A02 决策已执行: INSPECTING → DEFECTIVE"

#### Step 5: 详细 audit 查看 (15 sec)
旁白: "点 B-A03 [详情] 查看 4 维度细节".

操作:
1. 点 B-20260520-A03 卡的 [详情] 按钮
2. 弹"详细 audit 区":
   - 4 个 metric box:
     - 质检合格率: ✅ 全通过
     - HACCP: ✅ 全通过
     - 添加剂: 🚨 1 项超限 (亚硝酸盐 35 mg/kg > 30) - 红色 fail box
     - 客户标准: ✅ 工厂默认
3. 点 [❌ 退货] → dialog → "添加剂超限"

#### Step 6: 价值对比 (10 sec)
旁白:
"质量主管 30 sec/批 vs HJ 10 min/批, 节省 90% 时间.
20 批/天 → 3 小时 → 10 min, 多出 3 小时干其他事."
"AI 综合 4 维度判断 + 一键决策, 防止人工漏检 (HJ 4 菜单容易漏看一两个维度)."

录屏文件: `sprint-8-demos/p4-3-quality-chief.mp4`

---

## 录屏汇总 (Sprint 8 共 5 demo mp4)

| # | Demo | 时长 | Boss 弹药角度 |
|---|------|------|------|
| 1 | P1 销售老板 Workdesk | 5 min | AI 综合判断 + 一键跟进 |
| 2 | P2 财务主管 Workdesk | 5 min | 月结进度 + 三表 + 应收账龄 |
| 3 | P3 食品安全召回 Workdesk | 5 min | 杀手锏 — HJ 0 食品垂直能力 |
| 4 | P4.1 仓管员 Workdesk | 3 min | 防呆 R1 灵魂 — 告诉他要收多少 |
| 5 | P4.2 采购员 Workdesk | 3 min | AI 5 维度综合 + 一键请购 |
| 6 | P4.3 质量主管 Workdesk | 3 min | 4 维度综合 + 一键放行/退货 |

**Total**: 6 mp4 (Demo 1-3 各 5 min + Demo 4-6 各 3 min) = ~24 min Boss 演示池

---

## 录屏前 Steve checklist

1. [ ] 6 PR 全部 merge + 部署到 prod (47.100.235.168:10010 + 8086 web-admin)
2. [ ] 6 个测试账号能登入 (warehouse_mgr1 / purchase_mgr1 / quality_mgr1 / sales_mgr1 / finance_mgr1)
3. [ ] 6 个 Workdesk 路由可达 (`/workdesk/sales-owner` / `/workdesk/finance-manager` / `/workdesk/quality-manager` / `/workdesk/warehouse-keeper` / `/workdesk/purchaser` / `/workdesk/quality-chief`)
4. [ ] 测试数据已 seed 在 F006 (per 各 demo 前置条件)
5. [ ] 屏幕分辨率 1920x1080, OBS 录屏 30 fps
6. [ ] 旁白用普通话或 subtitles
7. [ ] 录完压缩 mp4 < 50 MB/个, 上传到 OSS `cretas-media/sprint-8-demos/`
