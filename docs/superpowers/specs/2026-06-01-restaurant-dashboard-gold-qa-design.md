# 餐厅经营驾驶舱 8 销售问答接 gold 层 (方案1) — 设计

**日期**: 2026-06-01
**业态**: 餐饮 (RESTAURANT)。验证工厂 RES_3101_009 (qhj_prod 青花椒, ¥78.77M gold 真数据)
**关联审计**: 本 session headed-UI 实测 + Java intent 层逐题实测 (16 问全失败/误路由)

---

## 1. 问题 (已 UI 实测确认)

经营驾驶舱 8 个快捷问答按钮 `goToAIQuery(text)` → 跳 AIQuery 页 (`/smart-bi/query?q=`) →
`handleSendMessage`: 先打 Java `/ai-intents/execute` (失败) → fallthrough 到 Python
`/api/chat/general-analysis-stream` (绑定**单个自动选中的上传文件**) 聊天。

真浏览器实测 (RES_3101_009, 截图存档):
- AIQuery 自动选数有"评价文件优先"偏置 → 选中**评价 upload 4692** → 销售类问题跑在评价数据上。
- 畅销品 Top5 → "无销售数量字段, 无法确定畅销品" (非答案)
- 哪家店业绩最好 → "未提供任何维度数据, 请先上传" (死胡同)
- 就算手动指向销售上传也救不了: 4690 商品销量报表返回的是**桂满陇品牌**菜/店(该工厂混了多品牌上传);
  4713 POS 把"微信/美团**支付方式**"误当外卖算出**外卖占比 65%**(真值≈27%); 4705 POS **编造**
  不存在的菜「川味麻辣烫」。

Java intent 层逐题实测 (8 销售问题):
- 畅销品→`RESTAURANT_BESTSELLER_QUERY` FAILED(已识别, 暂无执行器)
- 哪家店业绩最好→`RESTAURANT_OPS_STORE_MARGIN` FAILED
- 外卖占比→`RESTAURANT_ORDER_STATISTICS` FAILED
- 慢销菜品→`RESTAURANT_DISH_LIST` FAILED
- 员工里谁最厉害→`HR_LEAVE_REQUEST_QUERY` 误路由(请假!)
- 周末周中→`SHIPMENT_STATS` 误路由(出货!)
- 峰值月份→`PROCESS_TASK_ANALYSIS` 误路由(工序!)
- 优惠券使用情况→`CUSTOMER_ACTIVE` NOT_APPLICABLE

**根因**: 正确销售答案只在 gold 层(agg_product/agg_daily/agg_channel/agg_discount,= 青花椒
canonical fact_pos 数据, 绕开混品牌单上传文件); 但这 8 问当前: (A) 部分意图正确但**无执行器绑定 +
现有 Tool 查 ERP SalesOrder 表**(餐厅上传数据不在那) → 返空; (B) 部分**意图识别误判**到制造业意图。

---

## 2. 目标 (本 phase)

经营驾驶舱 8 个销售问题(餐厅业态)通过 **Java intent 层 → gold-backed Tool** 直接答对, **不再
fallthrough 到单文件聊天**。评价类 8 问(评价物化数据, 非 gold POS)是独立后续 phase, 不在本 spec。

---

## 3. 架构 (复用已验证 gold 链路)

和已能工作的 `RESTAURANT_ECONOMICS_ANALYSIS` 同机制:

```
Java Tool (ai/tool/impl/restaurant/*)
  → GoldFinanceClient (client/GoldFinanceClient.java, 已转发 X-User-Role 避免 RBAC 剥 ¥0)
  → Python gold 端点 (smartbi/api/restaurant_ops_gold.py)
  → smartbi gold 表 (agg_product / agg_daily / agg_channel / agg_discount / fact_pos_transaction)
```

铁律 (per feedback_java_python_rbac_role_forward): 金额类 gold 端点必须经 **role 转发**, 否则
RBAC money-strip 把营收剥成 ¥0。复用 GoldFinanceClient(已正确转发), **不新建裸 client**。

---

## 4. 8 问 → Tool → gold 源 → 意图

| # | 问题 | gold 源 | gold 端点 | Java Tool | 意图 |
|---|---|---|---|---|---|
| 1 | 哪家店业绩最好 | agg_daily by store | 已有 `store-margin` | 现有 store/margin Tool 改接 gold | RESTAURANT_OPS_STORE_MARGIN |
| 2 | 外卖占比多少 | fact/agg by order_type | 已有 channel/order-type | RestaurantOrderStatisticsTool 改接 | RESTAURANT_ORDER_STATISTICS |
| 3 | 峰值月份 | agg_daily by month | 已有 `daily-trend` 聚合 | 新/改 Tool | (新 RESTAURANT_PEAK_MONTH 或复用 revenue-trend) |
| 4 | 畅销品 Top5 | agg_product desc | **新** dish-ranking | RestaurantBestsellerQueryTool 改接(现查 ERP→空) | RESTAURANT_BESTSELLER_QUERY |
| 5 | 慢销菜品 | agg_product asc | **新** dish-ranking(asc) | RestaurantSlowSellerQueryTool 改接 | RESTAURANT_DISH_SLOW (修正自 DISH_LIST) |
| 6 | 周末周中对比 | agg_daily by dow | **新** weekday-weekend | 新 Tool | 新 RESTAURANT_WEEKDAY_WEEKEND |
| 7 | 优惠券使用情况 | agg_discount | **新** discount-summary | 新 Tool | 新 RESTAURANT_DISCOUNT_USAGE |
| 8 | 员工里谁最厉害 | fact_pos_transaction by staff_id | **新** staff-ranking | 新 Tool(**诚实标签**) | 新 RESTAURANT_STAFF_RANKING |

**数据事实(已查 prod gold 核实)**: 全部 8 维度 gold 可算。staff_id 已填充, 但 top staff 是
"收银/点菜"等 **POS 操作员账号, 非服务员** → 员工问题诚实标为"开单操作员排行"+ caveat, 不冒充
"最厉害服务员"(防呆铁律: 不误导)。优惠券 agg_discount 富数据([美团套餐券]¥10.6M)。

---

## 5. 新建 Python gold 端点 (restaurant_ops_gold.py)

| 端点 | 数据 | 输出 |
|---|---|---|
| `GET /restaurant-ops/dish-ranking?order=desc|asc&limit=N` | agg_product JOIN dim_product | 菜品名 / 销量 / 销售额 Top/Bottom N |
| `GET /restaurant-ops/weekday-weekend` | agg_daily by extract(dow) | 周末 vs 周中 日均净额 / 总额 / 占比 |
| `GET /restaurant-ops/discount-usage` | agg_discount JOIN dim_discount | 优惠类型 / 金额 / 使用笔数 Top N + 总优惠占营收比 |
| `GET /restaurant-ops/staff-ranking` | fact_pos_transaction by staff_id JOIN dim_staff | 操作员 / 开单净额 / 单数 (响应含 caveat 字段) |

复用已有: `store-margin`(门店), `daily-trend`(峰值月份按月聚合 — 若 daily-trend 不够再加
`peak-month`), 渠道/order_type(确认现有 channel 端点; 不足则在 dish/order 端点旁加)。所有新端点
遵循 python-java-port 规则(Decimal 序列化 / HALF_UP / None-check / RLS tenant)。

**时间窗铁律**: 旧 ERP Tool 用"近7天 from today" → 历史上传(数据截止 2026-04)永远空。gold 端点
默认窗口 = **该工厂 gold 数据实际范围**(MIN/MAX date)或 recent-N-months, **不用字面"近7天"**。
端点接受可选 `start/end`(NL 时间归一化传入)。

---

## 6. 意图层 (绑定 + 误路由纠偏)

两类:
- **(A) 意图对、缺执行器**: 畅销品/哪家店/外卖(/慢销) — 迁移补 `ai_intent_config.tool_name` 绑定到
  对应 gold Tool。
- **(B) 意图误判**: 员工→请假 / 周末→出货 / 峰值→工序 / 优惠券→活跃客户 — 这些是**已知固定的驾驶舱
  + autocomplete 短语**, 用**确定性 phrase-shortcut / 精确意图映射**(不靠脆弱语义调参): 把 8 条
  驾驶舱短语(+ AIQuery autocomplete 同类变体)精确映射到目标餐厅意图。新增意图(WEEKDAY_WEEKEND /
  DISCOUNT_USAGE / STAFF_RANKING / PEAK_MONTH / DISH_SLOW)+ 关键词 + business_type=RESTAURANT
  门控(per feedback_intent_gate_must_cover_all_execution_paths: 门控放共享组件, 覆盖
  execute()/executeWithExplicitIntent()/SSE 全路径)。

迁移 V<date>_NN: 新意图 INSERT + tool_name 绑定 + phrase mappings。flyway 版本号 > prod 已应用 max。

---

## 7. 防呆 (fool-proof 铁律)

- gold 某维度无数据 → 不编造、不死胡同: "本维度暂无数据" + next-action(如某工厂 gold 无 staff/
  discount 数据时)。
- 金额字段 role-forward 防 ¥0。
- 员工诚实 caveat("POS 仅记开单操作员, 非服务员归因")。
- Tool 返回结构 LLM-friendly + OutputFormatter whitelist 防 metadata leak(对齐 RESTAURANT_ECONOMICS)。

---

## 8. 测试 (TDD, 对齐 RESTAURANT_ECONOMICS 测试模式)

- 每个新/改 Tool 一个集成测试: gold 端点 mock + 真 PG 关键算术校验(top-5 排序 / 占比 / dow 分组)。
- Python gold 端点: pytest(注意 CI 不跑 smartbi/services/*/tests, 需手动 + deployed 实测)。
- F999/F001 不适用(餐厅 gold); 用 RES_3101_009 真数据做 deployed 实测基准(§4 已记真值: 畅销
  抖音松叶蟹/招牌青花椒味/米饭; 门店大丸百货店¥10.5M; 外卖≈27%; 峰值 2026-03; 周末日均>周中)。

---

## 9. 部署 + 验收

1. worktree off origin/main → PR → merge main → 从 main 部署(prod 只从 main, per worktree rule)。
2. Python gold 端点: `deploy-smartbi-python.sh --env prod`。
3. Java Tool + 迁移: `deploy-backend.sh --env prod`(蓝绿; 部完 javap 核对活跃 jar 含修复; 迁移靠
   systemctl restart 触发 flyway, 不能只传 jar)。
4. **headed UI 复验这 8 问**(playwright-headed 规则): 真浏览器跑驾驶舱 8 问, 截图, 对照 §4 真值;
   不再 fallthrough 到单文件聊天(grep prod log 确认走 gold Tool, 非 general-analysis-stream)。

---

## 10. 不在本 phase (out of scope)

- AI问答 评价类 8 问(评价物化数据层, 独立 phase)。
- RES_3101_009 多品牌上传数据卫生治理(青花椒 + 桂满陇 混在一个 factory_id)— 单独 issue。gold 层
  本身是 canonical(本 spec 靠 gold 绕开混品牌问题)。
- AIQuery 自动选数"评价优先"偏置 — 本 phase 走 Java gold Tool 后, 销售问题不再依赖自动选数; 该
  偏置对评价类 phase 仍需处理。

---

## 11. 风险

- 改客户面 AI 路由有回归风险 → 蓝绿 + headed UI 复验 + 真值对照兜底。
- 意图识别纠偏可能影响其他相邻意图 → phrase-shortcut 走精确映射(确定性)降低 blast radius;
  business_type 门控防误伤制造业。
- staff/discount 数据质量(操作员非服务员)→ 诚实 caveat, 不冒充。
