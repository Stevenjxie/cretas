CREATE TABLE IF NOT EXISTS restaurant_demo_operations_seed (
    id VARCHAR(80) PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    scenario VARCHAR(64) NOT NULL,
    seed_tag VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_restaurant_demo_operations_seed_factory_scenario
    ON restaurant_demo_operations_seed (factory_id, scenario);

CREATE INDEX IF NOT EXISTS idx_restaurant_demo_operations_seed_tag
    ON restaurant_demo_operations_seed (seed_tag);

DELETE FROM restaurant_demo_operations_seed
WHERE seed_tag = 'RESTAURANT_ROLE_ACTION_DEMO_2026_07_03';

INSERT INTO restaurant_demo_operations_seed (id, factory_id, scenario, seed_tag, payload, created_at, updated_at)
VALUES
(
    'qhj_ops_dispatch_20260703',
    'RES_DEMO_QHJ',
    'operations_dispatch',
    'RESTAURANT_ROLE_ACTION_DEMO_2026_07_03',
    $$
    {
      "storeName": "青花椒川食山语（颛桥龙湖店）",
      "period": "2026-07-demo",
      "businessDistrict": "颛桥龙湖天街",
      "operationsMetrics": {
        "revenueVsLastWeekPct": 10.5,
        "forecastDinnerOrders": 420,
        "queueMinutesPeak": 18,
        "serveMinutesAvg": 21,
        "eventTrafficLiftPct": 18
      },
      "roleActionPlan": [
        {"role": "仓管", "todayActions": ["核对活鱼、青花椒底料、手作冰豆花原料的当前库存和安全库存", "活鱼按晚市预测 80% 先备，保留 20% 临时补货空间", "把临期豆腐、番茄、冰粉原料贴红标，优先给厨师长排进今日备料"], "watchTomorrow": ["活鱼缺货次数", "临期/报损金额", "紧急补货次数"]},
        {"role": "厨师长", "todayActions": ["晚市前只保招牌青花椒鱼、手作冰豆花、双人套餐三条主线", "招牌鱼每小时抽查咸淡、鱼片熟度、上菜时长", "低销量复杂菜晚高峰不主动推荐，避免拖慢出餐"], "watchTomorrow": ["平均上菜时长", "招牌鱼出品抽查不合格次数", "退菜/重做次数"]},
        {"role": "前台/门迎", "todayActions": ["门口只讲招牌鱼、双人价格、预计用餐时间三句话", "核销客进店先确认券，再引导招牌鱼或双人套餐", "等位超过 12 分钟时主动给明确时间并提示可拼桌"], "watchTomorrow": ["进店转化率", "核销到店率", "等位差评次数"]},
        {"role": "店长", "todayActions": ["17:30 开班前按仓管、厨师长、前台三张清单派工", "18:00-20:00 只盯等位、上菜、缺货三个异常", "打烊后复盘三个数：收入、上菜时长、报损金额"], "watchTomorrow": ["晚市收入", "平均上菜时长", "报损金额"]}
      ]
    }
    $$::jsonb,
    NOW(),
    NOW()
),
(
    'qhj_inventory_reorder_20260703',
    'RES_DEMO_QHJ',
    'inventory_reorder',
    'RESTAURANT_ROLE_ACTION_DEMO_2026_07_03',
    $$
    {
      "storeName": "青花椒川食山语（颛桥龙湖店）",
      "period": "2026-07-demo",
      "inventoryAlerts": [
        {"ingredient": "活鱼", "unit": "kg", "currentStock": 42, "safetyStock": 58, "forecastNeed": 72, "reorderQty": 30, "risk": "今晚招牌鱼可能缺货", "priority": "HIGH"},
        {"ingredient": "青花椒底料", "unit": "kg", "currentStock": 8, "safetyStock": 12, "forecastNeed": 15, "reorderQty": 7, "risk": "底料实际耗用高于 BOM 6.1%", "priority": "HIGH"},
        {"ingredient": "手作冰豆花原料", "unit": "kg", "currentStock": 18, "safetyStock": 15, "forecastNeed": 21, "reorderQty": 3, "risk": "商场活动日加购会拉高用量", "priority": "MEDIUM"},
        {"ingredient": "豆腐", "unit": "kg", "currentStock": 16, "safetyStock": 10, "forecastNeed": 8, "reorderQty": 0, "risk": "有临期，今天先用不要补", "priority": "LOW"}
      ],
      "expiryAlerts": [
        {"ingredient": "豆腐", "expireInHours": 18, "quantity": 6.5, "suggestion": "今天先消耗，不要补货"},
        {"ingredient": "番茄", "expireInHours": 30, "quantity": 9.0, "suggestion": "午市优先消耗，晚市不再追加采购"}
      ],
      "stocktake": {
        "topLossItems": ["活鱼边角损耗 18.6kg", "青花椒底料盘亏 7.2kg", "豆腐临期报损 6.5kg"],
        "varianceItems": ["活鱼实际耗用高于理论 8.4%", "底料实际耗用高于理论 6.1%"]
      }
    }
    $$::jsonb,
    NOW(),
    NOW()
)
ON CONFLICT (id) DO UPDATE SET
    factory_id = EXCLUDED.factory_id,
    scenario = EXCLUDED.scenario,
    seed_tag = EXCLUDED.seed_tag,
    payload = EXCLUDED.payload,
    updated_at = NOW();
