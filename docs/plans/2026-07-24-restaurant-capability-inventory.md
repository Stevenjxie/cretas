# 餐饮能力盘点清单（B 方案辅助 — cysop 语料防漏项）

> 生成: 2026-07-24, Explore agent 扫描 web-admin + backend/python/smartbi。
> 用途: 《餐饮全链路 SOP》(docs/manual/restaurant-full-chain-sop.html) 写作对照表。

---

# 清单 A — 页面/板块清单

来源：`web-admin/src/router/index.ts`（约 1674–1875 行 `/restaurant` 块 + smart-bi/crm/ops 块）、`web-admin/src/components/layout/menuConfig.ts`、`web-admin/src/views/restaurant/**`。

## A-1 餐饮运营组 `/restaurant`（menuConfig 三层 IA：深度分析 / 日常录入 / 数据与系统）

| 菜单名 / 路由 | 位置(文件) | 给用户看什么 |
|---|---|---|
| 经营看板 `/restaurant/analytics/role-kpi` | `web-admin/src/views/restaurant/analytics/role-kpi-dashboard.vue` | 单店店长一屏 6 KPI（日营收/客单价/订单数/毛利率/食材成本率/目标完成率）+ 整体经营健康灯，诚实空态引导去配置目标 |
| 菜品分析 `/restaurant/analytics/dishes` | `web-admin/src/views/restaurant/analytics/dishes.vue`（含 `restaurantDishesTab.ts`） | Tab 容器：「菜品四象限」+「菜品毛利」两个子页 |
| ├ 菜品四象限（tab=quadrant） | `analytics/menu-board.vue` + `menuQuadrantGold.ts` | Kasavana-Smith 四象限散点图（明星/金牛/潜力/瘦狗），中位数 markLine 分割 |
| └ 菜品毛利（tab=margin） | `analytics/gross-margin.vue` | 各菜品毛利/毛利率排名（需价格权限） |
| 价格异常预警 `/restaurant/price-anomaly` | `price-anomaly/index.vue` | 供应商进货单价异常检测预警列表（受限角色） |
| 门店对比 `/restaurant/analytics/stores` | `analytics/store-comparison.vue` + `storeComparisonGold.ts` | 多门店营收排名柱状图 + 对比表（需价格权限） |
| 平台分析 `/restaurant/analytics/platform` | `analytics/platform.vue` + `platformReviewGold.ts` | 平台（美团/大众点评等）评价量柱状对比 + 评价量/平均星级双轴趋势图 + AI 图表解读 |
| 配方管理 `/restaurant/recipes` | `recipes/list.vue` | 菜品配方（BOM）录入/维护列表 |
| 供应商进货录入 `/restaurant/supplier-delivery` | `supplier-delivery/SupplierDeliveryNoteList.vue`（+ Detail/Upload/Import 对话框、`PriceAnomalyApprovalList.vue`） | 供应商送货单列表、上传、明细、导入、价格异常审批 |
| 领料管理 `/restaurant/requisitions` | `requisitions/list.vue` | 后厨食材领料记录 |
| 损耗管理 `/restaurant/wastage` | `wastage/list.vue`（+ `wastage/AccountabilityBoard.vue`） | 报废/损耗记录 + 损耗责任归属看板 |
| 盘点管理 `/restaurant/stocktaking` | `stocktaking/list.vue` | 食材库存盘点（盘盈/盘亏）录入 |
| 供应商月对账 `/restaurant/supplier-reconciliation` | `supplier-reconciliation/list.vue` | 供应商月度对账（finance 角色） |
| 成本归因 `/restaurant/cost-attribution` | `cost-attribution/index.vue` | 成本归因分析（finance 角色） |
| 营销员提成 `/restaurant/commission` | `commission/rep-summary.vue` | 营销员提成汇总（POS commission_rates） |
| 目标管理 `/restaurant/analytics/targets` | `analytics/target-hierarchy.vue` | 经营目标分层设置（营收/客单价等目标下达） |
| 数据完整度 `/restaurant/data-completeness` | `data-completeness.vue`（+ `data-quality-tab.vue`） | 数据完整度体检 + 数据质量 Tab |
| ETL 状态 `/restaurant/admin/etl-status` | `admin/etl-status.vue` | ETL 处理状态监控（admin） |
| 菜品名称匹配 `/restaurant/admin/name-resolution` | `admin/name-resolution.vue` | 菜品名归一化人工复核队列（admin） |

## A-2 路由存在但已重定向/合并（router 里 redirect，无独立菜单）

| 名称 / 路由 | 位置(文件) | 含义 |
|---|---|---|
| 运营总览 `/restaurant/analytics` (RestaurantAnalyticsOverview) | router 1710–1716 → 重定向 `/smart-bi/dashboard` | 旧 Excel 浏览器废弃，复用业态自适应经营驾驶舱 |
| 经营总览菜单板 `/restaurant/analytics/menu-board` | router 1748 → 重定向 `dishes?tab=quadrant` | 旧四象限入口 |
| 毛利分析 `/restaurant/analytics/gross-margin` | router 1766 → 重定向 `dishes?tab=margin` | 旧毛利入口 |
| 点评差距 `/restaurant/analytics/dianping-gap` | `analytics/dianping-gap.vue`（router 1760 → 重定向 platform） | 大众点评竞对差距雷达图（radar） |
| 运营概览 `analytics/overview.vue` | `analytics/overview.vue` | 旧概览页组件 |

## A-3 SmartBI「数据与分析」组 `/smart-bi`（餐饮共用，餐饮相关项）

| 菜单名 / 路由 | 含义（餐饮线用途） |
|---|---|
| 经营驾驶舱 `/smart-bi/dashboard` | 业态自适应经营驾驶舱（餐饮版 = RestaurantGoldGrid gold 卡片网格），餐饮总览主入口 |
| AI 问答 / 数据分析 `/smart-bi/analysis` | 餐饮老板自然语言问答（走分层意图路由，见清单 C） |
| 经营分析 `/smart-bi/analysis-hub` | 财务/销售/趋势/KPI 4-tab hub |
| 收入管理报表 `/smart-bi/revenue-report` | 餐饮收入报表（`hideForFactoryTypes: FACTORY`，餐饮可见） |
| AI 经营体检 `/smart-bi/health-report` | AI 生成经营诊断体检报告（餐饮可见） |
| 数据完整度 / 字段映射复核 / Excel 上传 / 查询模板 | 餐饮 POS/财务/评价数据上传与治理 |

## A-4 会员与营销 `/crm` + 运营分析 `/ops`（餐饮专属新顶级组）

| 菜单名 / 路由 | 含义 |
|---|---|
| 会员分析 `/crm/member-analysis` | RFM 客群分层 + 三维散点 + 生命周期 + 会员画像（仅餐饮/demo 租户） |
| 运营分析 `/ops/operations-analysis` | 撤单稽核 + 区域坪效（从驾驶舱迁出，确定性 bullets + AI 解读） |

## A-5 共享组件

| 名称 | 位置 | 含义 |
|---|---|---|
| AnalyticsStrip | `views/restaurant/components/AnalyticsStrip.vue` | 分析页顶部 KPI/指标条 |
| restaurant-shared.scss | `views/restaurant/restaurant-shared.scss` | 餐饮页共享样式 |

---

# 清单 B — 图表模板清单

图表引擎：`backend/python/smartbi/services/chart_builder.py`（兼容 shim）→ 真实实现在 `backend/python/smartbi/services/charts/`（Strategy 模式，`ChartType` 枚举在 `charts/base.py:35`）。餐饮看板/接口在此基础上组合专用图表。

## B-1 ChartType 枚举全量（`backend/python/smartbi/services/charts/base.py`）

| 图表类型(枚举值) | 实现文件 | 展示什么 |
|---|---|---|
| line / bar / pie / area / scatter | `charts/basic/*.py` | 基础趋势/对比/占比/面积/散点 |
| bar_horizontal / donut / nested_donut | `charts/basic/*` | 横向条形、环形、嵌套环形 |
| heatmap / matrix_heatmap / correlation_matrix | `charts/statistical/heatmap.py` | 热力图、矩阵热力、相关性矩阵 |
| boxplot / parallel | `charts/statistical/*` | 箱线图、平行坐标 |
| waterfall（瀑布） | `charts/financial/waterfall.py` | 财务增减瀑布（营收→成本→利润拆解，带万/亿刻度） |
| sankey（桑基） | `charts/financial/sankey.py` | 资金/客流流向桑基图 |
| bullet（子弹） | `charts/financial/bullet.py` | 实际 vs 目标达成子弹图 |
| dual_axis（双轴） | `charts/financial/dual_axis.py` | 双 Y 轴组合（如评价量+星级、量+率） |
| radar（雷达） | `charts/specialized/radar.py` | 多维竞对/门店对比雷达 |
| funnel（漏斗） | `charts/specialized/funnel.py` | 转化漏斗 |
| gauge（仪表盘） | `charts/specialized/gauge.py` | 健康度/达成率仪表 |
| treemap / sunburst | `charts/specialized/*` | 矩形树图、旭日图（层级占比） |
| pareto（帕累托） | `charts/specialized/pareto.py` | 长尾/ABC 帕累托 |
| slope（斜率） | `charts/specialized/slope.py` | 两期对比斜率图 |
| combination（组合） | `charts/specialized/combination.py` | 混合柱线组合 |
| gantt / wordcloud | `charts/specialized/*` | 甘特、词云（评价关键词） |
| budget_comparison | `charts/base.py` | 预算对比 |

注册中心：`charts/registry.py`；主题 `ACTIVE_THEME="business"`（`charts/common.py:159`）。

## B-2 餐饮线实际用到的图表（板块 → 图表 → 展示）

| 图表名 | 出现在哪个板块/接口 | 展示什么 |
|---|---|---|
| 四象限散点（Kasavana-Smith） | 菜品四象限页 `menu-board.vue`；后端 `services/restaurant/menu_engineering.py`（`MenuQuadrant`: star/cash_cow/puzzle/dog）、`gold/queries.py:1483 menu_quadrant`（明星/金牛/潜力/瘦狗）；API `GET /restaurant-ops/menu-quadrant` | 菜品按销量×毛利率分四象，中位数分割线，识别招牌/走量/高利无人点/淘汰候选 |
| 营收排名柱状图 | 门店对比 `store-comparison.vue`；API `GET /restaurant-ops/store-comparison`、`/store-margin` | 各门店营收/毛利横向排名 |
| 双轴图（柱+线） | 平台分析 `platform.vue` | 评价量(柱)+平均星级(线)按月趋势 |
| 平台对比柱状图 | 平台分析 `platform.vue` | 各平台评价量对比 |
| 竞对差距雷达图 | `dianping-gap.vue` | 本店 vs 大众点评竞对多维评分差距 |
| 时段营业热力图 | `services/restaurant/dining_period_heatmap.py`（7×24 HeatmapCell）；section `dining_heatmap`；analyzer `diningHeatmap` | 星期×小时营收热力，找高峰/低谷时段 |
| 日营收趋势 / 同比环比 | API `GET /restaurant-ops/daily-trend`；section `temporal_comparison`；意图 `RESTAURANT_OPS_TREND_ANALYSIS` | 营收日趋势、同比(yoy)/环比(mom)对比 |
| Top 食材条形 | API `GET /restaurant-ops/top-ingredients` | 领料/损耗 Top N 食材排行 |
| RFM 客群 + 三维散点 | 会员分析 `/crm/member-analysis`；`services/restaurant/member_rfm.py`；section `member_rfm` | 会员 Recency/Frequency/Monetary 分层 + 生命周期散点 |
| 费用拆解 | section `expense_breakdown`、`department_pnl`、`store_pnl_one_pager`（finance data_kind） | 门店/部门损益、费用科目拆解（P&L one-pager） |
| gold 卡片网格(RestaurantGoldGrid) | 经营驾驶舱 `/smart-bi/dashboard`；API `restaurant_ops_gold.py`（summary/gross-margin/daily-trend 等） | 餐饮版驾驶舱多卡片 KPI+图表网格 |
| KPI 卡 + 健康灯 | 经营看板 `role-kpi-dashboard.vue`；section `store_kpi_dashboard` | 6 项店长 KPI + 健康度徽章 |

前端图表引擎为 ECharts（`@/utils/echarts`，`useChartResize`/`useChartInsight` composables），后端为图表配置构建器输出 option。

---

# 清单 C — AI 分析 / 意图能力清单

## C-1 诊断能力（`backend/python/smartbi/knowledge/restaurant/diagnostics_registry.yaml`，共 16 metric）

引擎 `shared/diagnostics_engine.py` 加载此表，实测对比 benchmark 后触发 `playbooks/{id}.yaml`。逐条：

| 能力名(metric) | 触发方式/所在 | 能回答什么问题 |
|---|---|---|
| food_cost_ratio 食材成本率 | benchmark 子行业阈值 → playbook `food_cost_ratio_high` | 食材成本占营收比是否偏高 |
| labor_cost_ratio 人力成本率 | benchmark 子行业 → `labor_cost_ratio_high` | 人力薪资+福利占营收比是否偏高 |
| discount_rate 折扣率 | benchmark 子行业 → `discount_rate_high` | 折扣金额占折前营收比是否过高 |
| void_rate 撤单率 | 内联阈值(<3 健康/≥6 严重) → `void_rate_high` | 撤单率是否提示排班/培训/流程问题 |
| table_turnover 翻台率 | benchmark 子行业(越低越糟) → `table_turnover_low` | 翻台率是否偏低 |
| cost_rigidity 成本弹性指数 | 内联阈值(需≥2 月数据) → `cost_rigidity_high` | 营收下滑时成本是否跟着降（"邓总救命指标"，刚性=继续亏损） |
| channel_collection_rate 渠道收款率 | 内联(≥0.78 健康) → `channel_collection_rate_low` | 扣平台抽佣后实际到手率 |
| channel_gross_margin 渠道毛利率 | 内联(需 BOM/COGS) → `channel_margin_low` | 渠道维度含 COGS 的真实毛利率 |
| stored_value_dependency 充卡赠送依赖度 | 内联(<0.05 健康) → `stored_value_dependency_high` | 充卡赠送(隐性折扣)占营收比是否过高 |
| delivery_dependency 外卖依赖度 | 内联(≥0.70 严重) → `delivery_dependency_high` | 外卖营收占比是否高度依赖平台 |
| review_score_decline 评分趋势下滑 | 内联(需时序) → `review_score_decline` | 5 星占比环比是否下滑(<-5pp 严重) |
| ingredient_waste_rate 食材损耗率 | benchmark waste_loss_rate → `ingredient_waste_rate_high` | 报废损耗占食材成本比是否偏高 |
| avg_ticket_vs_target 客单价达标率 | 内联(DynamicConfig 目标) → `avg_ticket_below_target` | 实际客单价 vs 目标偏差(<-15% 严重) |
| gross_margin_per_dish 菜品毛利率 | 内联(需 #57 BOM 成本卡) → `gross_margin_per_dish_low` | 有配方+单价菜品聚合毛利率(有价菜<3 道不算) |
| recipe_coverage_rate 配方覆盖率 | 内联(需 BOM) → `recipe_coverage_low` | 已配配方+单价菜品占在售比，成本分析是否失真 |

其余 playbook 文件（`playbooks/`）：cost_rigidity_high、discount_rate_high、food_cost_ratio_high、labor_cost_ratio_high、table_turnover_low、avg_ticket_below_target、channel_collection_rate_low、delivery_dependency_high、gross_margin_per_dish_low、ingredient_waste_rate_high、recipe_coverage_low、review_score_decline、void_rate_high 等。子行业 benchmark：`benchmarks/{火锅,奶茶,咖啡,快餐,日料,烧烤,西餐,牛肉面,中式海鲜,鱼类餐饮,餐饮连锁}.yaml`。

## C-2 分层意图路由 T1→T2→T3（`backend/python/smartbi/gold/restaurant_intent.py` + `restaurant_ops_router.py`）

设计：T1 关键词(<1ms, conf=0.95) → T2 向量(code_prefix=RESTAURANT_OPS_, ~30ms, 阈值 0.78/0.70) → T3 LLM 结构化解析(SLOT.MAPPER, temp=0, 5s 超时, conf≥0.6)。业态门控(仅餐饮租户跑 T2/T3)、fail-open、路由缓存(500 LRU)、澄清续接(Postgres `restaurant_pending_clarifications`, 5min TTL, 一跳)。

| 能力名(意图 code) | 触发方式/所在 | 能回答什么问题 |
|---|---|---|
| RESTAURANT_OPS_WASTAGE_TOP | `_OPS_PATTERNS`(T1) + `resolve_wastage_top` | 损耗/浪费/报损排行(按食材/类型的损耗量+金额) |
| RESTAURANT_OPS_STOCK_SHORTAGE | T1/T2/T3 + `resolve_stock_shortage` | 盘点差异(盘亏/盘盈)排行 |
| RESTAURANT_OPS_RECIPE_COST | + `resolve_recipe_cost` | 菜品食材成本排行(不含毛利/售价) |
| RESTAURANT_OPS_REQUISITION_TREND | + `resolve_requisition_trend` | 近 N 天领料趋势 + Top5 食材领用量 |
| RESTAURANT_OPS_GROSS_MARGIN | + `resolve_gross_margin` | 菜品级毛利/毛利率分析 |
| RESTAURANT_OPS_STORE_MARGIN | + `resolve_store_margin` | 门店级毛利/毛利率对比 |
| RESTAURANT_OPS_SALES_SUMMARY | + `resolve_sales_summary` | 总体经营概览(营收/订单/客单价/是否盈利) |
| RESTAURANT_OPS_TREND_ANALYSIS | + `resolve_trend_analysis` | 营收同比/环比/月度趋势 |
| RESTAURANT_OPS_INVENTORY_WARNING | + `resolve_inventory_warning` | 食材库存水位预警(低于补货点/安全库存) |
| RESTAURANT_OPS_STAFFING_ADVICE | + `resolve_staffing_advice` | 按时段(午/晚/下午茶/夜宵)人效诊断，建议加/减人 |

槽位检测（三层共享，确定性层）：`_detect_dimensions`(store/dish/ingredient)、`_detect_comparison`(yoy/mom/wow)、`_profit_intent`(wants_margin/asks_profitability)、`_resolve_sales_date_range`(相对时间窗，日期永不缓存)。飞轮：`log_intent_capture` + `list_promotion_candidates`（T3→SAMPLE_QUERIES/向量库人工晋升）。相关模块：`restaurant_intent_service.py`、`restaurant_intent_promotion.py`、`restaurant_ops_router.py`、`gold/answer_contract.py`。

## C-3 分析器支持的分析维度（`backend/python/smartbi/services/restaurant/analyzer.py` — RestaurantAnalyzerV2）

`analyze()` 编排层，按 section 顺序委托 40 个 handler（`services/restaurant/sections/*` + `api/restaurant_sections.py` HANDLERS）。核心维度：

| 能力名 | 触发方式/所在(section handler) | 能回答什么 |
|---|---|---|
| 财务指标(毛利主轴) | `financialMetrics`(FinancialMetrics) | 营收/成本率/毛利/cost_rigidity 等核心财务 |
| 诊断引擎 | `diagnostics`(DiagnosticsHandler) | 跑 C-1 全部 16 metric → 触发 playbook 建议 |
| Benchmark 告警 | `benchmark_alerts`(BenchmarkAlertsHandler) | 与子行业基准对比的红黄绿告警 |
| 成本弹性/刚性 | `cost_rigidity` | 成本随营收变化弹性(反亏损预警) |
| 渠道毛利 | `channel_margin`(ChannelMarginHandler) | 各渠道扣佣/扣 COGS 后毛利 |
| 菜单工程 | `menu_engineering` | Kasavana-Smith 四象限分类 |
| 菜品归一化 | `menu_normalization`(+ `menu_normalizer.py`) | POS 菜名归一/名称匹配 |
| 长尾 SKU | `long_tail_sku`(+ `long_tail_sku_detector.py`) | 长尾滞销单品识别 |
| 时段热力 | `dining_heatmap` | 星期×小时营收热力 |
| 充卡赠送依赖 | `stored_value`(+ `stored_value_analyzer.py`) | 隐性折扣依赖度 |
| 评价分析 | `review_analysis`(+ `review_analyzer.py`/`review_analyzer_llm.py`) | 评价情感/关键词(LLM，regex 兜底) |
| 竞对评价 | `review_competitive` | 竞对评价对比 |
| 会员 RFM | `member_rfm`(+ `member_rfm.py`) | 客群分层/画像 |
| 同比环比(时序) | `temporal_comparison` | 期间对比 yoy/mom |
| 多门店对比 | `multi_store_comparison`(+ `multi_store_comparator.py`) | 跨店排名对比 |
| 跨连锁基准 | `cross_chain_benchmark`(+ `cross_chain_benchmark.py`) | 连锁跨店 benchmark |
| 校准历史(校准因子) | `calibration_history`(+ `monthly_purchase_calibrator.py`/`monthly_calibration_report.py`) | 月度采购校准因子历史(账实校准) |
| 门店/部门 P&L | `store_pnl_one_pager`(+ `store_pnl_one_pager.py`)、`department_pnl` | 门店/部门损益一页纸 |
| 费用拆解 | `expense_breakdown` | 费用科目树拆解 |
| BOM 分层/差异 | `bom_layer_status`、`bom_variance`(+ `bom_resolver.py`) | 配方成本卡分层就绪度/差异 |
| 损耗/盘亏分析 | `shrinkage_analysis` | 报废损耗率/盘亏 |
| 退单异常(反回扣/稽核) | `return_anomaly` | 撤单/退单异常稽核 |
| 人效/排班 | `labor_productivity`、`shift_analysis`、`piecework_calc`、`performance_eval` | 人效比、班次、计件、绩效评估 |
| 销售计划跟踪 | `sales_plan_tracking` | 目标达成跟踪 |
| 座位坪效 | `seat_occupancy` | 上座率/坪效 |
| 套餐拆分 | `combo_split` | 套餐收入拆分到单品 |
| 智能补货/采购预测 | `smart_reorder`、`procurement_forecast`、`forecast`(RestaurantForecastHandler) | 补货点、采购与营收预测 |
| 日对账 | `daily_reconciliation` | 每日账实对账 |
| 高级客流画像 | `advanced_traffic_persona` | 客流人群画像 |
| 老板决策简报 | `boss_decision_brief`(+ `demo_owner_action_scenarios.py`) | 汇总各 section → 老板一句话决策建议 |
| 月度 PPT 导出 | `monthly_ppt_export`(+ `ppt_templates/monthly_default.pptx`) | 生成月度经营 PPT |
| 门店 KPI / 价值汇总 | `store_kpi_dashboard`、`value_summary` | 店长 6 KPI、价值总结 |

补充能力文件：健康检查 `services/restaurant/health_check_metrics.py`、SKU 表单 `sku_form_manager.py`、渠道毛利 `channel_margin_calculator.py`。API 层还有 `restaurant_analytics.py`（上传/computeV2/评价源/SKU/月采购）、`restaurant_llm_composite.py`、`restaurant_cost_card.py`、`restaurant_outliers.py`、`restaurant_targets(_p1).py`、`restaurant_value.py`、`restaurant_completeness.py`、`restaurant_health_check.py`、`restaurant_name_resolution_admin.py`、`restaurant_ops_recipes.py`、`restaurant_etl_admin.py`。

---

## 说明与替代位置

- `chart_builder.py` 是兼容 shim，真实图表实现已迁至 `backend/python/smartbi/services/charts/`（`ChartType` 枚举在 `charts/base.py`）。
- 餐饮驾驶舱图表结构主要由 `restaurant_ops_gold.py`（gold 卡片网格）+ `restaurant_sections.py`（40 个 section handler，`POST /api/smartbi/restaurant/sections/{name}`，`GET /list` 可发现）提供。
- 分层意图 T1 关键词表实体在 `restaurant_ops_router.py` 的 `_OPS_PATTERNS` / `SAMPLE_QUERIES`（约 35–240 行），T2/T3 编排与澄清续接在 `restaurant_intent.py`。
