# Sprint 8 P3 食品安全召回 Workdesk Demo Script

**目的**: Boss 演示弹药 #3 — Cretas vs HJ 杀手锏证明 (差异化护城河).
**预期时长**: 3-5 min mp4 (~2 min 真流程 + 1-2 min 解说).
**预期 Steve 执行人**: Steve 亲跑 (本 Phase C 仅备 script, 真录屏在 Phase 部署完后).
**场景**: F006 六腾门卤味店, 鲜湘缘餐厅客户投诉吃了拉肚子, 启动召回闭环.

---

## 前置条件 (录屏前确认)

- [ ] **PR Merged**: `sprint8/p3c-food-safety-workdesk-vue` → main
- [ ] **Deploy**: `./scripts/deploy/deploy-backend.sh --env all` (Java) + 自动跟随 web-admin deploy
- [ ] **Test Account**: F006 prod / `quality_mgr1` / 密码见 `.env.test` (per `reference_f006_liutengmen_prod_accounts.md` memory)
- [ ] **测试数据准备**:
  - 鲜湘缘餐厅客户 (CustomerName 含 "鲜湘缘") in F006
  - 2026-05-18 出货 batch B-20260518-A03 (卤猪蹄) 给鲜湘缘
  - HACCP 5/18 记录 (中心温度 76℃ / 卤煮 90 min / 冷却 2h 含 deviation)
  - GB 2760 添加剂 seed (亚硝酸盐 28 mg/kg 实测 / 30 mg/kg 限量)
  - 其他 11 家客户买过 B-20260518-A03

---

## Demo Steps (流程 + 旁白)

### Step 1: 登录 + 进入工作台 (10 sec)

旁白: "F006 六腾门卤味店, 质量主管登录 Cretas".

操作:
1. 浏览器打开 `https://admin.cretaceousfuture.com`
2. 输入账号 `quality_mgr1` / 密码
3. 登录后进入 Canvas 首页

### Step 2: 进入质量主管工作台 (5 sec)

旁白: "从顶部菜单 🏪 我的工作台 进入 🚨 质量主管工作台".

操作:
1. 点 sidebar `🚨 质量主管工作台`
2. 进入 `/workdesk/quality-manager`

预期看到:
- Header 含 🚨 emoji + "质量主管工作台" 标题
- 红色边框 (R5 视觉提醒高风险场景)
- 右上角 `🚨 启动召回` 红色按钮 + 重新查询按钮
- AI 对话框 (默认: "今天 HACCP 监控全通过吗?")

### Step 3: 点 "启动召回" 红色大按钮 (5 sec)

旁白: "客户投诉到了, 一键启动召回".

操作:
1. 点右上角 `🚨 启动召回`
2. Dialog 弹出: "🚨 启动食品安全召回 — 输入投诉信息"

### Step 4: 输入投诉信息 (20 sec)

旁白: "三个字段: 客户名 + 投诉日期 + 投诉描述".

操作:
1. 客户名: `鲜湘缘餐厅`
2. 投诉日期: `2026-05-18`
3. 投诉描述: `客户吃了拉肚子, 怀疑卤猪蹄变质`
4. 点 `开始召回分析` (红色按钮)

预期 loading 5-10 sec (loading-card 显示 "AI 正在运行 food-safety-recall Skill 串 8 Tool...").

### Step 5: 3-10 sec 后召回分析全部输出 (核心 demo 时刻 — 60 sec)

旁白: "Skill 串 8 个 Tool 同时跑 — 原料追溯 + HACCP audit + GB 2760 复查 + 影响客户 + 损失预估, 全部 5 sec 内完成. **HJ 模式这步要 30 分钟手动跳 6 个屏**".

预期看到 (从上到下):

#### 5.1 召回分析结果 (LLM 输出汇总)
formattedText 显示 LLM aggregate 的"召回行动方案":
- 原料: 鲜湘缘 5/18 收到的 = B-20260518-A03 (卤猪蹄), 用了原料 PI-X 猪蹄 / PI-Y 老抽
- HACCP audit: 5/18 中心温度 76℃ ✓ / 卤煮 90 min ✓ / **冷却 2h ⚠️ 超 1.5h 上限** (BLOCKING)
- GB 2760 复查: 亚硝酸盐 28 mg/kg ✓ 合规 (限量 30), 其他全合规
- 影响范围: 12 家客户 / 28 斤库存
- 推荐 4 行动: 冻结库存 / 通知客户 / 生成监管报告 / 关闭事件

#### 5.2 原料追溯卡 (🔗)
- 批次 B-20260518-A03
- 涉及原料批次 2 个 / 影响出货 12 笔 / 影响客户 12 家

#### 5.3 HACCP 监控审查卡 (🌡️)
- 红色 ⚠️ 偏差 tag (1 个 deviation)
- Table 显示:
  - 中心温度 76℃ (达标 70-80℃) ✅
  - 卤煮时长 90 min (达标) ✅
  - **冷却时长 120 min (上限 90 min) ⚠️ 偏差** (红底高亮)

#### 5.4 GB 2760 添加剂合规卡 (🧪)
- 绿色 ✅ "全部合规" tag
- Table: 亚硝酸盐 28 mg/kg / 限量 30 mg/kg ✓

#### 5.5 影响客户列表 (👥)
- Table 12 行: 鲜湘缘餐厅 50 斤 / 老张烤吧 30 斤 / 川香烧腊 20 斤 / ... (含联系电话)

#### 5.6 召回损失预估卡 (💸)
- 冻结库存价值: ¥420
- 客户退货预估: ¥1,200
- 行政成本: ¥220
- **总损失预估: ¥1,840** (红色高亮)

#### 5.7 召回行动卡 (⚡ 4 个一键执行按钮)

### Step 6: 一键执行 4 个行动 — 展示防呆 R1 preview (60-90 sec)

旁白: "每个按钮先 preview 后 execute, 防呆设计避免误操作".

#### 6.1 [🧊 冻结库存] (15 sec)
1. 点 `🧊 冻结库存` 按钮
2. Dialog 弹出 (header: "🧊 冻结库存 — 召回事件 #5 / 批次 B-20260518-A03")
3. 冻结原因预填: `食品安全召回, 防止次生污染`
4. 点 `预览` → 显示 "🚨 [BLOCKING] 确认冻结 → 批次 B-20260518-A03, 28 斤, 原状态 NORMAL. 冻结后此批次无法用于生产/出货"
5. 点 `确认冻结` → 1 sec 后 ✅ "已冻结批次 B-20260518-A03 (28 斤), 原状态 NORMAL → FROZEN"

#### 6.2 [📱 通知客户] (15 sec)
1. 点 `📱 通知客户` 按钮
2. Dialog 弹出 (header: "📱 通知客户 — 召回事件 #5 / 批次 B-20260518-A03")
3. 点 `预览` → 显示 "ℹ️ 将通知 12 家客户" + 短信草稿 ("【六腾门食品安全召回】尊敬的鲜湘缘餐厅, 我司召回 B-20260518-A03 批次产品...")
4. 点 `确认发送` → 1 sec 后 ✅ "已群发召回通知 12 条"

#### 6.3 [📑 生成监管文件] (15 sec)
1. 点 `📑 生成监管文件` 按钮
2. Dialog 弹出 (header: "📑 生成监管上报文件 — 召回事件 #5")
3. 点 `生成报告` → 1 sec 后输出 markdown 全文 (含召回事件信息 + HACCP 数据 + 影响客户 + GB 2760 复查结论)
4. 点 `📥 下载 Markdown` → 自动下载 `召回上报文件-5-{timestamp}.md`

#### 6.4 [✅ 关闭事件] (10 sec)
1. 点 `✅ 关闭事件` 按钮
2. Dialog 弹出 (header: "✅ 关闭召回事件 #5")
3. 关闭总结: `已冻结 28 斤库存, 通知 12 家客户, 监管文件已上报食药监`
4. 点 `确认关闭` → 1 sec 后 ✅ "召回事件 #5 已关闭"

### Step 7: Boss 总结台词 (20-30 sec)

旁白:
> "**全流程从客户投诉进来到 4 行动执行完毕 < 2 min**.
> 同样场景 HJ ERP / 用友 ERP 模式: 客户列表 → 微信记录 → 出货记录 → 批次 → HACCP 报表 → 供应商 → GB 2760 手册翻阅 → Word 写报告 — **6 屏来回切, 30 分钟工作量**.
> Cretas 把 8 个数据源串成 1 个 Skill, AI 5 sec 完成原本人工 30 min 的活. 这是 Cretas 的核心差异化 — 不是查询界面, 是 **业务决策助手**".

---

## 后续 Phase 待优化项 (本 Phase 已知 stub)

| 项目 | 当前 Phase 1 状态 | 后续 Phase |
|---|---|---|
| RecallEventService Controller | 不存在 → "关闭事件" 走 RECALL_EVENT_CLOSE intent (兜底 toast 提示) | Phase 4 接入完整 Service |
| PDF 真生成 | 仅返 markdown 内容 + 前端下载 .md | Phase 4 接 FoodSafetyRecallPdfService |
| 短信网关真发 | NotificationService 当前是 logging | Phase 5 接钉钉/阿里短信 |
| 客户名 → customerId 自动解析 | 通过自然语言 + customer_search Tool 自动解析 | OK (本 Phase 已有) |
| HACCP 数据展示阈值高亮 | 已实现 isDeviation 红底 | OK |
| GB 2760 超限红标 | 已实现 isExceed 红底 | OK |

---

## 录屏工具建议

- **OBS Studio** (免费) 或 **ScreenRec** (1080p / 30fps / mp4)
- **音频**: 配合 OBS 内置麦克风录制旁白
- **分辨率**: 1920x1080 (商务汇报标准)
- **画面遮罩**: 真客户名 / 真账号密码可后期模糊 (剪映自带)

---

## 验证清单 (Steve 录屏前)

- [ ] F006 quality_mgr1 登录 prod 成功
- [ ] 鲜湘缘餐厅 测试数据 in F006 prod (CustomerName LIKE '%鲜湘缘%')
- [ ] B-20260518-A03 batch 存在 + 状态 NORMAL (未 FROZEN)
- [ ] HACCP 5/18 至少 1 条 deviation (冷却时长超 1.5h)
- [ ] 12+ 个 ShipmentRecord 关联 B-20260518-A03
- [ ] food-safety-recall Skill 5-10 sec 内返结果 (E2E smoke test)
- [ ] 4 个 dialog 按钮全 preview → execute 流程正常

---

## 关联文档

- Spec: `docs/superpowers/specs/2026-05-20-sprint-8-ai-workdesk-design.md` §P3.5 / §P3.6
- Plan: `docs/superpowers/plans/2026-05-20-sprint-8-ai-workdesk-plan.md` Task 3.3
- Skill: `backend/java/cretas-api/src/main/java/com/cretas/aims/service/skill/impl/SkillRegistryImpl.java` (food-safety-recall)
- Tools: `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/` (8 个)
- Vue: `web-admin/src/views/workdesk/QualityManagerWorkdesk.vue`
