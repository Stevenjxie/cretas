# 餐饮 AI 能力真值与闭环审计（2026-08-03）

## 结论

餐饮 AI 不是一个单体能力，而是三个运行面：通用餐饮问答、预测排班 FactBook、受限毛利归因 Agent。当前最完整的业务闭环是预测排班；通用问答覆盖 16 个核心意图；受限 Agent 只有一条毛利下降归因路线。页面存在、代码存在、测试通过和生产真实回答必须分别记账，不能互相代替。

本轮不修改 TokenHub、火山、Aliyun、模型账号、provider 或路由配置。所有预测排班数字只能来自预测 FactBook；历史实际人效和目标人效只作为趋势证据，不能把 `actual < target` 直接解释成缺人。

## 状态口径

| 状态 | 含义 |
|---|---|
| `PROVEN` | 已从真实用户入口走完整链路，答案、来源和安全断言均有当次证据 |
| `IMPLEMENTED_NOT_PROVEN` | 代码和目标测试存在，但本次尚未完成真实入口深度验收 |
| `PAGE_ONLY` | 页面或入口存在，尚未证明对应 AI 闭环 |
| `BLOCKED` | 设计上应支持，但缺数据、权限、运行时或外部前提 |
| `UNSUPPORTED` | 当前产品契约明确不支持，必须诚实澄清而非改走相邻分析 |

## 三个运行面

| 运行面 | 用户入口 | 数字来源 | 大模型职责 | 当前边界 | 本轮初始状态 |
|---|---|---|---|---|---|
| 通用餐饮问答 | 餐饮分析页 AI、移动餐饮 AI、canonical SSE | Gold/确定性 resolver | 理解问题、补齐意图与维度、综合解释；不得改写确定性数字 | 16 个核心意图 | `IMPLEMENTED_NOT_PROVEN` |
| 预测排班 | `/restaurant/staffing` 内嵌 AI | 预订 + 7/30/365 天趋势 + 技能/工时/目标人效生成的 FactBook | 必须参与理解、综合结果、解释原因、给可调整建议；候选文本不得自行写数字 | 只执行明天、下周、下个月 | `IMPLEMENTED_NOT_PROVEN` |
| 受限 Agent | RestaurantV2 对话面板中的毛利下降运行卡 | 只读工具返回的 `EvidenceEnvelope` | 在有界轮次内规划/归因，数字声明必须可追溯到 evidence | 仅 `GROSS_MARGIN_DECLINE_ATTRIBUTION` | `IMPLEMENTED_NOT_PROVEN` |

老板行动编排是第四种相邻机制：它服务行动建议和确认流，不等于预测排班 FactBook，也不等于通用 Agent。任何页面 starter prompt 都不能因此被当成已接入排班数字真值。

## 16 个核心意图真值矩阵

| 用户问题示例 | 意图 | 确定性事实/工具 | LLM 作用 | 主要页面/角色 | 初始状态 |
|---|---|---|---|---|---|
| 你们能做什么 | `CAPABILITIES` | 能力目录 | 语言组织 | 通用 AI；所有餐饮 AI 角色 | `IMPLEMENTED_NOT_PROVEN` |
| 今天天气怎么样 | `OUT_OF_DOMAIN` | 域边界 | 解释不能回答 | 通用 AI | `IMPLEMENTED_NOT_PROVEN` |
| 毛利率低有什么行业参考做法 | `PLAYBOOK` | 维护的行业方法文本 | 组织建议 | 老板/店长 | `IMPLEMENTED_NOT_PROVEN` |
| 我一共有几家店 | `STORE_DIRECTORY` | 门店目录 | 理解范围 | 老板/店长 | `IMPLEMENTED_NOT_PROVEN` |
| 最近30天生意不好，怎么提升 | `BUSINESS_OPTIMIZATION` | 多维经营 synthesis | 综合原因和行动建议 | 老板/店长 | `IMPLEMENTED_NOT_PROVEN` |
| 外卖占比多少 | `CHANNEL_MIX` | 渠道订单事实 | 解释结构 | 老板/店长 | `IMPLEMENTED_NOT_PROVEN` |
| 哪些食材损耗最多 | `WASTAGE_TOP` | 损耗 Gold | 解释排行 | 后厨/店长 | `IMPLEMENTED_NOT_PROVEN` |
| 哪些食材经常盘亏 | `STOCK_SHORTAGE` | 盘点差异 Gold | 解释异常 | 后厨/采购/店长 | `IMPLEMENTED_NOT_PROVEN` |
| 哪道菜食材成本最高 | `RECIPE_COST` | 配方/食材成本事实 | 解释成本 | 后厨/采购/财务 | `IMPLEMENTED_NOT_PROVEN` |
| 最近领料趋势 | `REQUISITION_TREND` | 领料事实 | 解释趋势 | 采购/后厨 | `IMPLEMENTED_NOT_PROVEN` |
| 哪道菜毛利最低 | `GROSS_MARGIN` | 菜品收入与成本事实 | 解释毛利 | 老板/店长/财务 | `IMPLEMENTED_NOT_PROVEN` |
| 哪家店最赚钱 | `STORE_MARGIN` | 门店收入与成本事实 | 解释门店差异 | 老板/店长/财务 | `IMPLEMENTED_NOT_PROVEN` |
| 本月营收和订单怎么样 | `SALES_SUMMARY` | POS Gold | 解释概览 | 老板/店长 | `IMPLEMENTED_NOT_PROVEN` |
| 今年比去年增长多少 | `TREND_ANALYSIS` | 多周期 POS Gold | 解释同比/环比 | 老板/店长 | `IMPLEMENTED_NOT_PROVEN` |
| 哪些食材快没了 | `INVENTORY_WARNING` | 库存水位/补货点 | 解释预警 | 采购/后厨/店长 | `IMPLEMENTED_NOT_PROVEN` |
| 明天怎么排班 | `STAFFING_ADVICE` | 预测排班 FactBook | 必须综合解释且不得创作数字 | 老板/店长/人事 | `IMPLEMENTED_NOT_PROVEN` |

## 预测排班闭环

```text
每日预订接口/模拟
  -> 来源、门店、日期、时段、桌数、人数、状态、更新时间
  -> 当前预订覆盖 + 7/30/365 天 POS/客流/历史人效趋势
  -> 明天/下周/下个月、门店×时段需求预测
  -> 岗位技能 + 班次工时 + 周工时上限 + 目标人效
  -> 建议人数、现有人数、缺口、置信度 FactBook
  -> LLM 只读 FactBook 做综合解释和可调整建议
  -> Web 角色视图与一键调整
```

约束：

- 预订模拟必须可追溯，不能伪装成真实平台数据。
- 需求预测和人数建议都先由确定性代码写入 FactBook。
- LLM 候选叙述通过无数字校验；页面数字由代码渲染。
- 历史人效只用于趋势，不是缺口公式输入。
- “最近30天晚市人手够不够”“这个月各岗位人效怎么样”不是未来预测请求。系统必须要求改成明天、下周或下个月，不能默认成明天。

## 角色与真入口

| 角色 | 通用餐饮 AI | 预测排班页 | 受限 Agent | 应看到的重点 | 当前缺口 |
|---|---|---|---|---|---|
| 老板 `restaurant_owner` | 有 | 有 | 有资格时可见 | 跨店需求、总缺口、资源调配 | 三个运行面尚未统一会话 |
| 店长 `restaurant_manager` | 有 | 有 | 有资格时可见 | 本店时段、班次执行、调整入口 | 调整后的保存/审批业务闭环需单独验收 |
| 人事 `hr_admin` | 无独立通用餐饮角色体验 | 有 | 无 | 技能、工时、兼职需求 | 只有排班真入口，不能宣称有完整餐饮 AI 工作台 |
| 采购 `restaurant_purchaser` | 有 | 无 | 有资格时可见 | 库存、领料、采购风险 | 与排班缺口没有直接权限耦合 |
| 后厨 `restaurant_chef` | 有 | 无 | 无 | 损耗、库存、领料、菜品成本 | 不应看到跨店人事/价格越权信息 |
| 财务角色 | 有权限时可问价格/毛利 | 无 | 部分通用财务角色可有资格 | 毛利、成本、门店经营 | 价格异常、供应商对账、完整成本归因仍有 intent 缺口 |

## 闭环盘点

| 业务闭环 | 入口问题 | 数据到答案 | 答案到动作 | 状态 |
|---|---|---|---|---|
| 预订 → 客流 → 排班 | 明天怎么排班 | 已接 FactBook | 页面可调整；生产写入不在只读验收范围 | `IMPLEMENTED_NOT_PROVEN` |
| POS → 经营概览/趋势 | 本月生意怎么样 | Gold resolver | 经营优化给建议 | `IMPLEMENTED_NOT_PROVEN` |
| 菜品收入+成本 → 毛利 | 哪道菜毛利最低 | Gold resolver | 行动建议/受限归因 | `IMPLEMENTED_NOT_PROVEN` |
| 库存 → 补货 | 哪些食材快没了 | 库存水位 resolver | 只给建议，不直接写采购单 | `IMPLEMENTED_NOT_PROVEN` |
| 领料/损耗/盘点 → 异常 | 哪些食材浪费最多 | Gold resolver | 给治理建议 | `IMPLEMENTED_NOT_PROVEN` |
| 毛利下降 → 多轮 Agent 归因 | 为什么毛利下降 | `EvidenceEnvelope` 只读工具 | 只输出可追溯结论 | `IMPLEMENTED_NOT_PROVEN` |
| 当前/历史人效诊断 | 各岗位这个月人效怎么样 | 无独立历史人效 resolver | 应澄清，不转成预测 | `UNSUPPORTED` |
| 财务价格异常/供应商对账 | 最近有没有食材价格异常 | 无对应核心 intent | 不得改走相邻轴 | `UNSUPPORTED` |

## 验证深度

| 深度 | 证据 | 能证明什么 | 不能证明什么 |
|---|---|---|---|
| Smoke | 路由、resolver、页面组件存在 | 已实现 | 真实账号能答、数字正确 |
| Medium | 目标单测/集成测试通过 | 路由、边界、FactBook 合同稳定 | 生产数据与权限链可用 |
| Deep | 真实入口完整问答，核对 intent、FactBook/source marker、角色页面与零业务写 | 当前生产能力可用 | 未执行的写入动作或其他角色能力 |

本轮深度验收至少包括：

1. “明天怎么排班”“下周需要多少兼职”“下个月各店人效安排”分别返回正确未来范围。
2. 输出同时存在 FactBook 与大模型解读标识，数字全部可追溯，禁止模型创作数字。
3. “各岗位这个月的人效怎么样”返回范围澄清，不出现已执行的预测 FactBook。
4. 生产只读验收 `actualBusinessWrites == 0` 且 `blockedMutationAttempts == 0`；AI 会话/审计遥测与业务 mutation 分开报告。

## 分批打磨

### P0：真值与安全

- 修复历史/当前人效问法被默认成明天预测。
- 把三条真实预测问题和一条历史边界加入日常回归电池。
- 更新问题库与能力矩阵，去掉“页面存在即 AI 支持”的表述。

验收：目标测试、compileall、Ruff fatal、`git diff --check`；真实入口四问逐条出证据，生产业务写入为 0。

### P1：统一用户体验

- 为人事补独立餐饮 AI 角色体验，但只暴露授权范围。
- 通用餐饮问答识别排班问题后，显式跳转/委派到预测排班 FactBook，而不是复用老板行动建议。
- 把通用问答、排班和受限 Agent 的来源/置信度/可执行动作展示规范统一。

### P2：扩展 Agent 与闭环动作

- 按独立 evidence contract 扩展库存、损耗、门店经营等 Agent route；不能用一个毛利路线冒充全域 Agent。
- 排班调整进入预览、精确确认、权限校验、乐观锁、原子写入和审计回执；生产只读入口继续零写。
- 为历史人效诊断建立独立 resolver 后，才可把当前 `UNSUPPORTED` 提升为已支持。
