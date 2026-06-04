# 价值可视化回馈回路 设计 Spec

**特性编号**: #56 · **版本**: 0.1 (供 Steve 审阅) · **日期**: 2026-06-04
**分支建议**: `feat/value-feedback-loop` (off `origin/main`) · **迁移版本**: `V20260918_01` 起 (避开 Wave2 的 `V20260917_xx`)

---

## 目标
把诊断引擎已算出的省钱/改善金额，按受众主动呈现给门店经理/档口长/员工，形成"行动 → 省钱 → 看见 → 自觉"的正循环，解决金毛范囊式"门店看不到价值 → 配合度崩塌"死因。

## 范围 (MVP In/Out)
**纳入**: 价值信号聚合(从 DiagnosticsEngine RxAction.expected_impact / cost_rigidity.annualizedImpact / ShrinkageEngine.topOffenders 提取金额) · 新表 `restaurant_value_snapshots`(幂等 upsert) · Python `GET /value-summary`+`POST /refresh` · Java `RestaurantValueSummaryTool`(intent RESTAURANT_VALUE_SUMMARY) · web 驾驶舱「本月价值回馈」折叠区(RBAC 脱敏) · 站内通知月度摘要(复用 DbNotificationServiceImpl)+防重表 · RN 通知中心复用(零/极小改动) · 业态门控 RESTAURANT。
**排除**: Wave2 损耗按人/价格威慑归因(预留接口) · RN 主动 Push(Phase4) · 员工个人视角(Phase2) · 处方采纳跟踪(Phase3) · 跨店对比(Phase2)。

## 价值信号模型
| 信号 | 来源(真实代码) | 口径 | 标注 |
|---|---|---|---|
| 人工刚性节省 | `services/restaurant/sections/cost_rigidity.py` annualizedImpact | 直接用 | 预估·年化 |
| 处方行动节省 | `diagnostics_engine.py` RxAction.expected_impact("人工成本-¥3.2K/月") | 正则提 ¥(K=千/W=万) | 处方预估 |
| 档口损溢超标 | `services/finance/shrinkage_engine.py` totalVarianceAmount | 实际-标准成本 | 本月实测 |
| 食材成本改善空间 | DiagnosticsEngine Diagnosis.delta_pp(food_cost_ratio_high) | delta_pp×营收 | 预估·如达标 |
| 折扣率改善空间 | 同上(discount_rate_high) | delta_pp×营收 | 预估·如达标 |
| (预留)损耗按人/价格威慑 | Wave2 两分支合并后 | before/after | 本月实测 |

**核心诚实规则**: 实测/年化 vs 预估必须标清；禁伪造金额；数据不足返 null 显"暂无数据"，**禁用 0 填 null**。

## 数据模型
### restaurant_value_snapshots (smartbi_db, V20260918_01)
```sql
CREATE TABLE IF NOT EXISTS restaurant_value_snapshots (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    period_month VARCHAR(7) NOT NULL,           -- 'YYYY-MM'
    store_id VARCHAR(100),                       -- NULL=全店汇总
    labor_rigidity_annual_est NUMERIC(14,2),    -- 预估·年化
    shrinkage_variance_amount NUMERIC(14,2),    -- 本月实测
    food_cost_savings_est NUMERIC(14,2),        -- 预估
    discount_savings_est NUMERIC(14,2),         -- 预估
    total_est_annual NUMERIC(14,2),
    diagnosis_count SMALLINT NOT NULL DEFAULT 0,
    critical_count SMALLINT NOT NULL DEFAULT 0,
    rx_action_count SMALLINT NOT NULL DEFAULT 0,
    signal_sources JSONB NOT NULL DEFAULT '[]',
    confidence_note TEXT,
    computed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
-- store_id 可空幂等用 COALESCE 部分唯一索引(吸取 Wave1 G2 NULLS DISTINCT 教训)
CREATE UNIQUE INDEX IF NOT EXISTS uq_value_snapshot_factory_period_store
    ON restaurant_value_snapshots (factory_id, period_month, COALESCE(store_id,''));
CREATE INDEX IF NOT EXISTS idx_value_snapshot_factory
    ON restaurant_value_snapshots (factory_id, period_month DESC);
ALTER TABLE restaurant_value_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_value_snapshots FORCE ROW LEVEL SECURITY;
CREATE POLICY rls_value_snapshot_tenant ON restaurant_value_snapshots
    USING (factory_id = current_setting('app.factory_id', true));
GRANT SELECT, INSERT, UPDATE, DELETE ON restaurant_value_snapshots TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE restaurant_value_snapshots_id_seq TO smartbi_user;
```
### restaurant_value_notifications_log (V20260918_02)
防重 UNIQUE(factory_id, period_month, recipient_role) + RLS + GRANT DML/sequence。

## 后端组件
**Python(8083)**:
- `services/restaurant/value_signal_extractor.py` — ValueSignal dataclass + `extract_from_diagnosis(diagnoses, shrinkage_report, revenue_current)` + `extract_rx_action_impact(rx)`(正则 `[¥￥]([\d,]+(?:\.\d+)?)[KkWw万]?`)。唯一与 DiagnosticsEngine/ShrinkageEngine 交互入口。
- `services/restaurant/value_snapshot_service.py` — `compute_and_upsert_snapshot(...)` 幂等 upsert(数据不足金额=None 不填0不报错) + `get_value_summary(...)` 未命中返 None。
- `api/restaurant_value.py` — GET value-summary(RBAC 复用 `smartbi_compat._rbac_strip.PRICE_VIEW_ROLES`,非金额角色金额 null 只返 count) + POST refresh。注册进 main.py。
- `services/restaurant/value_notifier.py` — `maybe_notify_monthly(...)` 幂等(查防重)→调 Java 通知→写防重日志。

**Java(10010)**:
- `ai/tool/impl/restaurant/RestaurantValueSummaryTool.java`(bean restaurantValueSummaryTool, 继承 AbstractRestaurantDiagnosticTool, getSectionName="value_summary", FollowUp 查看处方/上月对比/哪档口损耗高)。
- PythonSmartBIClient 确认 callRestaurantSection("value_summary",req) 路径; Flyway 意图绑定 RESTAURANT_VALUE_SUMMARY(ai_intent_configs 复数表+priority/business_type, 版本号实施查 origin/main)。

## 前端/RN 呈现(受众路由)
| 受众 | 角色 | 看什么 | 界面 |
|---|---|---|---|
| 老板/总监 | FACTORY_SUPER_ADMIN/ADMIN | 全店汇总(实测+估算) | web 驾驶舱 |
| 店长/运营 | RESTAURANT_MANAGER/OPERATIONS | 本店 totalEstAnnual+criticalCount | web 驾驶舱 + RN 通知 |
| 档口长 | 低权限 | 仅 count(金额 null) | RN 通知(无金额文案) |
| 员工 | — | 不展示(Phase2) | — |
- web `views/smart-bi/components/ValueFeedbackStrip.vue`(新建)嵌驾驶舱, 业态门控; 数字 Chip + "预估"(橙#FF9800)/"实测"(绿#4CAF50)/灰(暂无); `services/restaurantValueApi.ts`。
- RN `NotificationCenterScreen.tsx` 零改动复用 INFO; actionUrl="/smart-bi/dashboard?tab=value"。

## 回路数据流
月度上传 → hooks.py 物化(已有) → ValueSnapshotService.compute_and_upsert_snapshot(DiagnosticsEngine.run + ShrinkageAnalysisHandler + ValueSignalExtractor → upsert) → maybe_notify_monthly(防重→Java notifyRole→写日志)。触达 A)RN 通知 B)web ValueFeedbackStrip C)AI问答"本月省了多少"→Tool。触发点: hooks.py save_materialization_results 后 fire-and-forget(try/except 不阻塞主流程)。

## 防呆五规则
R1 金额带"预估/实测"标签+颜色, null 显"暂无数据"禁裸¥0; R2 通知带期间+门店+金额+count, 无金额角色用"X项指标需关注请联系店长"; R3 只读不涉自由文本; R4 通知防重表+快照幂等; R5 无数据显"未检测到本月数据[前往上传]→/smart-bi/upload"。

## 错误处理(禁降级假数据)
compute 异常 try/except 只记日志金额=None 不填0; API 快照不存在返 success:true/data:null/"暂无价值快照"(正常空态); Java Tool data:null→buildError success:false 前端显"暂无"不静默; 通知失败只 WARNING 不写防重日志(下次重试); 任何 null 禁前端替 0/占位。

## 测试计划
Python: 邓总火锅算例(cost_rigidity0.56,revenue731047→labor≈18349 对 playbook); 食材46%(中位42%)50万→≈20000; 数据不足→None 不入 signal_sources; "¥3.2K/月"→3200/"≈¥15K"→15000; null 防降级不填0; 幂等 upsert 第二次行数不增; RBAC 低权限金额全 null count 正常。Java: Tool 成功→ValueCard; data:null→success:false 诚实 message。headed E2E: 上传 qhj→物化→查表有行(curl X-Internal-Secret)+web ValueFeedbackStrip 金额非零截图 + RN 通知截图。

## 文件结构
**新建**: migrations V20260918_01/02; value_signal_extractor.py/value_snapshot_service.py/value_notifier.py/api/restaurant_value.py+2 测试; RestaurantValueSummaryTool.java; ValueFeedbackStrip.vue/restaurantValueApi.ts。
**修改**: materialized_analytics/hooks.py(+~8行追加快照调用); main.py(include_router); 驾驶舱入口(嵌 ValueFeedbackStrip+业态门控); Flyway 意图配置。

## 待 Steve 拍板的决策
| # | 决策 | 默认推荐 | 备选 |
|---|---|---|---|
| D1 | 快照触发时机 | hooks.py 物化尾部 fire-and-forget(上传即触发) | 每月1日 cron(减压但等次月) |
| D2 | 通知接收角色 | 仅 RESTAURANT_MANAGER+FACTORY_ADMIN(运营 web 可看不推) | 也推 RESTAURANT_OPERATIONS(广但噪音) |
| D3 | 金额展示单位 | 月度数字(年化仅 tooltip,防夸大) | 年化大数字(冲击强但估算易质疑) |
| D4 | 驾驶舱 API 路径 | 前端直调 Python(139 Nginx 已反代 47:8083) | 经 Java 转发(统一 JWT 多一跳) |
| D5 | RN 通知改动 | 零改动复用 INFO 类型 | 新增 VALUE_FEEDBACK 专属类型+紫色图标 |

## ✅ 决策已拍板 (2026-06-04, Steve)

- **D1 = BOTH 双触发 (DECIDED)**: (a) 每月1日 cron 自动计算上月快照(遍历所有 RESTAURANT 工厂, 兜底保证每月有快照); (b) 月度数据上传时 hooks.py 物化尾部 fire-and-forget 也触发即时重算(上传即看见)。两路径走同一 `compute_and_upsert_snapshot`(幂等 upsert, 重复触发不产生重复行); cron 跑全工厂遍历, upload 只算当前工厂当前期间。
- **D2 = 店长+老板 (DECIDED)**: 仅 `RESTAURANT_MANAGER` + `FACTORY_SUPER_ADMIN`/`FACTORY_ADMIN` 收推送通知。运营经理(`RESTAURANT_OPERATIONS`)可在 web 驾驶舱主动查看 ValueFeedbackStrip, 但不主动推送(避免噪音)。
- **D3 = 月度+年化, 期间切换 (DECIDED)**: ValueFeedbackStrip 顶部加期间切换器(本月 / 年化), 两口径都展示按需切。月度=本月实测/预估数字; 年化=annualizedImpact 大数字。两者都带"预估/实测"标签(年化恒预估口径)。快照表两列都存(`total_est_month` + `total_est_annual`), 前端切换不重新请求(一次返回两口径)。
- D4 = 默认: 前端 `restaurantValueApi.ts` 直调 Python(139 Nginx 已反代 47:8083), 与现有 gold 分析 API 一致。
- D5 = 默认: RN 通知零改动复用 INFO 类型, actionUrl 跳驾驶舱 value tab。

### D1/D3 对 schema 的影响 (实施必读)
- V20260918_01 表加 `total_est_month NUMERIC(14,2)` 列(月度口径汇总), 与 `total_est_annual` 并存。
- cron 脚本遍历所有 RESTAURANT 业态工厂 + 各自最近完整月份; per-factory `SET app.factory_id` 后再 upsert(RLS)。
- API `GET /value-summary` 一次返回 `{month: {...}, annual: {...}}` 两口径; 前端期间切换本地切不二次请求。

## 依赖
当前可开工(无阻塞): 两表迁移/ValueSignalExtractor(纯计算)/Snapshot+Notifier service/Java Tool(复用 AbstractRestaurantDiagnosticTool)/ValueFeedbackStrip/单测。
依赖 Wave2: w2-wastage 合并后加 extract_from_wastage_agg() 读 agg_restaurant_daily_ops; w2-price-deterrence 合并后加 price_deterrence_savings_est 列(新迁移)。
依赖 G4 health-check(已建): 输出可直接作 DiagnosticsEngine metrics 输入。

## 关键代码参考点(实现核对)
- diagnostics_engine.py L46-73 RxAction.to_dict().expectedImpact; L155-172 run() 按 severity 降序
- services/restaurant/sections/cost_rigidity.py L72-84 annualizedImpact(年化可节省,正值)
- knowledge/restaurant/playbooks/cost_rigidity_high.yaml L140-160 expected_savings_formula+邓总算例(单测期望)
- materialized_analytics/hooks.py L122-129 save_materialization_results 后追加点
- ai/tool/impl/restaurant/diagnostic/AbstractRestaurantDiagnosticTool.java L36-193 模板方法(子类只实现 getSectionName/buildFollowUps)
- service/notification/impl/DbNotificationServiceImpl.java L37-90 notifyRole(targetRole,...)
- screens/common/NotificationCenterScreen.tsx L42-57 TYPE_ICONS/TYPE_COLORS(INFO 蓝色已有,零改动兼容)
