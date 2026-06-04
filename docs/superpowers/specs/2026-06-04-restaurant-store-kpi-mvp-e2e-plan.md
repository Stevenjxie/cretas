# 店长经营 KPI 看板 MVP — Headed E2E 测试计划

**日期**: 2026-06-04
**目标**: 验证 `/restaurant/analytics/role-kpi` 经营看板在 prod 真实餐饮租户下渲染 6 KPI + 健康灯 + RBAC + 防呆。
**状态**: 计划 (本 PR 不实跑 prod E2E; 单测 + 本地/test 验证已覆盖核心逻辑)。

---

## 环境

| 项 | 值 |
|---|---|
| 租户 | qhj_prod (RES_3101_009, business_type RESTAURANT) |
| 登录 | user `qhj_prod` / password `123456` |
| web-admin | prod 8086 (`http://139.196.165.140:8086`) |
| Python gold | prod 8083 (经 nginx 网关) |

⚠️ **本 PR 不部署 prod** (worktree off origin/main; 验证用 test 环境或本地 + 单测)。下表是 merge 后部署 prod 时的验收脚本。

---

## Playwright headed 配置 (per .claude/rules/playwright-headed-mode.md)

遵守 headed mode rule，但 **删除** `launchOptions.args` 里的 `--user-data-dir` 与
`--remote-debugging-port` (本版 Playwright `launch()` 拒绝它们 → headed 全 fail,
见 memory `feedback_playwright_launch_rejects_user_data_dir`)。改用 `ia-redesign`
project 模式: 内置 per-worker context 隔离 + `--window-position` 视觉分隔。

```ts
use: {
  headless: false,                          // ⭐ 强制 headed
  viewport: { width: 1920, height: 1080 },
  launchOptions: {
    args: [
      '--lang=zh-CN',                        // 中文 locale (无方块)
      '--font-render-hinting=none',
      '--disable-blink-features=AutomationControlled',
      '--window-position=0,0',
      '--window-size=1920,1080',
      // ❌ 不加 --user-data-dir / --remote-debugging-port (launch() 会报错)
    ],
    slowMo: 100,
  },
  screenshot: { mode: 'on', fullPage: true },
  video: { mode: 'on', size: { width: 1920, height: 1080 } },
}
```

---

## 测试用例

### TC1 — 经营看板渲染 6 KPI (price-view 角色)
1. 登录 qhj_prod (factory_super_admin / restaurant_manager — price-view)。
2. 侧边栏「餐饮运营 → 深度分析 → 经营看板」点击 → 路由 `/restaurant/analytics/role-kpi`。
3. **断言**: 6 张 KPI 卡渲染 (日营收/客单价/订单数/毛利率/食材成本率/目标完成率)。
4. **断言**: 金额卡 (日营收/客单价) 显示真实 ¥ 数值 (非 "—") — price-view 可见。
5. **断言**: 每卡 header 有健康 badge (success/warning/danger tag)。
6. **断言**: header 三元素 (门店 / 期间: 全部历史 / 角色) 可见 — 防呆 Rule 2。
7. **断言**: 整体经营健康度 tag 显示。
8. 截图: 中文真显示 (无方块 □), fullPage。

### TC2 — RBAC 金额剥零 (非 price-view 角色)
1. 用一个 **非** price-view 角色登录 (如 warehouse_mgr / viewer)。
2. 进经营看板。
3. **断言**: 日营收 / 客单价 卡显示 "—" (剥零, 非 0)。
4. **断言**: 订单数 (计数) 与 毛利率/食材成本率/目标完成率 (比率) 仍显示真实值。
5. **断言**: 底部 RBAC 提示文案出现 ("当前角色无价格查看权限...")。

### TC3 — 目标完成率防呆 (Rule 5)
1. 若 qhj_prod 未配置目标 → 目标完成率卡 status=NO_TARGET → "去配置目标" 按钮。
2. 点按钮 → 跳 `/restaurant/analytics/targets` (目标管理页)。
3. 若已配目标但无 alert_config → 显示 "预警阈值未配置，使用默认" 提示。

### TC4 — 毛利率诚实性
1. 若 qhj_prod 有配方成本数据 → 毛利率显示真实 % + "基于 X/Y 个有配方成本菜品" 提示。
2. 若无配方 → status=INSUFFICIENT → "成本数据不足" + "去录入配方" 按钮 → 跳 `/restaurant/recipes`。

### TC5 — AI Tool 路径 (经营看板意图)
1. AIChat 输入 "经营看板" / "店长KPI" / "门店经营情况"。
2. **断言**: 命中 RESTAURANT_STORE_KPI_DASHBOARD → restaurant_store_kpi_dashboard tool。
3. **断言**: 返回人类可读 message (含 6 KPI label + 值 + 健康 badge + overall_health), 非 "操作已完成"。
4. **断言**: 金额按当前角色剥零一致 (与 TC1/TC2)。
5. 注: 新意图需 embedding backfill 才走语义层; keyword 匹配 "经营看板" 立即可用。

---

## 验证 block (跑完 spec 后填)

```
- headless: false ✓
- viewport: 1920×1080 ✓
- locale: zh-CN ✓
- chromium window 真弹 ✓
- 截图字体: 中文真显示 (无方块 □) ✓
- screenshot mode: fullPage ✓
- TC1 6 KPI 渲染 ✓ / TC2 RBAC 剥零 ✓ / TC3 防呆跳转 ✓ / TC4 诚实性 ✓ / TC5 AI tool ✓
```

---

## 本地/test 替代验证 (本 PR 已做)

- pytest: `test_store_kpi_dashboard.py` (16) + `test_gold_store_kpi_endpoint.py` (2) — compute 6 KPI / 阈值边界 / 诚实 INSUFFICIENT / NO_TARGET / RBAC 剥零 fail-closed / 向后兼容 3 维度。
- Java: `StoreKpiDashboardToolTest` (7) — 角色注入 / 6-KPI message / 空态 / SKIPPED / 不可用 / camelCase。
- web-admin: vue-tsc 我的文件零新增错误; menuConfig spec 29 全绿。
- curl 烟测 (部署后, per memory `reference_prod_no_real_customers_yet` X-Internal-Secret 绕 JWT):
  ```bash
  curl -s "http://47.100.235.168:8083/api/smartbi/gold/store-kpi-dashboard?factory_id=RES_3101_009" \
    -H "X-Internal-Secret: $INTERNAL_API_SECRET" -H "X-Factory-Id: RES_3101_009" -H "X-User-Role: restaurant_manager" | jq .
  # 期望: kpis[6], 金额可见 (restaurant_manager 是 price-view); 换 X-User-Role: warehouse_mgr → 金额 null。
  ```
