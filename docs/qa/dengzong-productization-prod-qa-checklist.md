# 邓总餐饮产品化 Prod QA 清单

目标：覆盖邓总转录相关功能、转录前已有餐饮能力、Wave2/P1/P2/收尾功能，以及横切 RBAC / 防呆 / 诚实空态验证。

测试环境：
- Web Admin: `http://139.196.165.140:8086`
- 主账号: `qhj_prod / 123456`
- 租户: `RES_3101_009`, `RESTAURANT`
- RBAC 对照账号: `qhj_sales_mgr / qhj_warehouse_mgr / qhj_finance_mgr / qhj_operator`
- 浏览器要求: headed Playwright, `headless:false`, `1920x1080`, `zh-CN`, 截图 + video 留证。

## A. 本轮新功能

| ID | 功能 | 入口 / API | 验收点 | 数据前提 | 结果 |
| --- | --- | --- | --- | --- | --- |
| A1 | 组织 KPI 店长经营看板 | `/restaurant/analytics/role-kpi` | 6 KPI 卡出真数；健康度 badge；目标未配置有“去配置目标”；金额 RBAC 脱敏 fail-closed | qhj 有 POS/配方部分数据；目标可能未配置 | TODO |
| A2 | 二维火 POS adapter 骨架 | `POST /api/smartbi/{factoryId}/ingest/2dfire/sync` | 返回 `success:false`、`configured:false`、明确缺 `TWODFIRE_APP_KEY/SECRET/SHOP_ID` 和 actionHint；不 500、不假数据 | 真实对接缺 creds | TODO |

## B. P2 四项

| ID | 功能 | 入口 / API | 验收点 | 数据前提 | 结果 |
| --- | --- | --- | --- | --- | --- |
| B3 | 毛利预警 | `/restaurant/analytics/dishes?tab=margin`, AI chat | 已定价菜品 >= 3 且 coverage >= 0.80 才出预警；否则显示数据不足 | qhj 名称解析覆盖约 20%，数据不足是预期 | TODO |
| B4 | 月度阶梯提成 | `/restaurant/commission`, commission API | 月度累计套 tier；rep 只能查自己；空数据诚实显示 | 需 tier 规则 + visit/订单归属数据 | TODO |
| B5 | 配方版本化 + 毛利指标 | `/restaurant/recipes` + RecipeVersion API | 可建版本、审批、切换；重复审批 409；价格快照脱敏 | Web 审批 UI 可能仍是 follow-up | TODO |
| B6 | 供应商价格预警 | `/restaurant/price-anomaly`, price anomaly API | `baseline_mode=days` 90 天均价；金额 RBAC；异常可解释/ack | 需供应商进价历史 | TODO |

## C. P1 五项

| ID | 功能 | 入口 / API | 验收点 | 数据前提 | 结果 |
| --- | --- | --- | --- | --- | --- |
| C7 | 成本卡 / 出菜反推 | `/restaurant/recipes`, `/restaurant/analytics/dishes?tab=margin` | 成本卡使用配方/价格计算；缺价不误导成 0 | 配方和价格覆盖有限 | TODO |
| C8 | 目标拆分 | `/restaurant/analytics/targets` | 月目标可拆周/日；读回成就/预警 | 写测试值需用独立月份或测后清理 | TODO |
| C9 | 餐饮 CRM + 营销员归属 | CRM/客户跟进/commission API | 生命周期阶段、营销员归属；电话 PII 非授权脱敏 | 需客户/visit 数据 | TODO |
| C10 | 诊断深化 | AI 体检/诊断 API | 餐饮指标 + playbook next action；无数据时诚实跳过 | qhj 部分数据缺失 | TODO |
| C11 | 取数自动化 / POS 名称解析 | `/restaurant/admin/name-resolution`, `/restaurant/data-completeness` | 覆盖率、候选、人审入口；未命中进入待处理 | qhj 覆盖率约 20% | TODO |

## D. Wave2 四项

| ID | 功能 | 入口 / API | 验收点 | 数据前提 | 结果 |
| --- | --- | --- | --- | --- | --- |
| D12 | 价格异常威慑 | `/restaurant/price-anomaly` | 检测、解释/ack、连续异常高风险 | 当前 qhj 可能 0 异常 | TODO |
| D13 | 损耗按人/档口责任制 | `/restaurant/wastage` | 录入带责任人/档口；汇总按人/档口；成本字段 RBAC | qhj 有 6 条损耗记录 | TODO |
| D14 | 月结自动闭环 | `/finance/accounting-period` | 打开期间、发起结账、确认结账、锁定 | 写操作需谨慎选测试月份 | TODO |
| D15 | 价值可视化回馈回路 | 驾驶舱 ValueFeedback / `restaurant-value` API | 月度/年化价值；空态明确下一步 | qhj 当前可能暂无价值快照 | TODO |

## E. 横切必测

| 维度 | 验收点 | 结果 |
| --- | --- | --- |
| 角色 RBAC | super / finance 可看授权金额；sales / warehouse / operator 金额脱敏为 null/空，不是 0；角色缺失 fail-closed | TODO |
| 防呆写操作 | 预显边界、带身份上下文、重复提交不重复创建 | TODO |
| 错误提示 | 业务拒绝 toast sticky，文案来自后端 message，并包含下一步动作 | TODO |
| 诚实空态 | 数据不足显示“去配置/去录入/数据不足”，不返假数据、不空白 | TODO |
| 真库 prod | 走 8086 网关 + qhj 真数据；headed 截图中文正常 | TODO |

## 备注

qhj 演示租户名称解析覆盖率约 20%。所有依赖菜品成本/毛利的功能显示“成本数据不足”时，先按诚实空态处理，不直接判 bug。完整毛利链需要补齐菜品定价或人审毕业候选。
