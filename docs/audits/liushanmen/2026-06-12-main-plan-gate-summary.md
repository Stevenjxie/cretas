# 2026-06-12 主方案 Gate 摘要

范围: 主方案 §7 19 个“假完整/孤岛”靶点 + §8 六大业务流真实数据 E2E 初轮。

## 最高优先级阻断

1. 出纳付款缺银行信息: `PR-F006-20260611-5424` cashier API 返回 `bankName=null`, `bankAccount=null`, `payeeName=null`。
2. 销售流缺出库: `SO-20260611-0001`、`SO-20260611-0004` 均无 `sales_delivery_records`，不能宣称销售全链闭环。
3. DisposalRecord 直批绕审批: DEMO record `id=3` 无 `workflow_instance_id` 直接 approve 成功。
4. stocktake 老 `/apply` 路径缺 workflow guard: 当前 F006 无盘点单，代码路径仍 OPEN。
5. 标签前缀仍 `MA`: 物料标签生成 `MA-F006-...`，F006 raw material `primary_code` 为空时未同源使用业务编码。
6. 生产深测缺口: 撤回自愈“清 null -> 重报新值”和真多段半成品链尚未运行时闭环。

## 已坐实可演

- #774 两个 500 修复后 live 200 已复验。
- cashier 三付款页不 403；但付款数据缺银行信息。
- #773 收货价继承: `PO-20260612-0002` / `RCV-20260612-1897` item `unit_price=31.2300`。
- 含税销售凭证三行: `4000 + 520 = 4520`, `13600 + 1768 = 15368`。
- 财务 `KINGDEE` 凭证导出 200 xlsx；销售角色导出 403。
- R&D `laborPerKg` 持久化、`is_trial`、三价脱敏均坐实。
- BOM 包材“每产品用量”和物料类型 16 位编码 UI headed 截图已留证。

## 产物

- `docs/audits/liushanmen/2026-06-12-section7-fake-complete-targets.md`
- `docs/audits/liushanmen/2026-06-12-flow-sales-e2e.md`
- `docs/audits/liushanmen/2026-06-12-flow-purchase-e2e.md`
- `docs/audits/liushanmen/2026-06-12-flow-rd-e2e.md`
- `docs/audits/liushanmen/2026-06-12-flow-finance-e2e.md`
- `docs/audits/liushanmen/2026-06-12-flow-production-e2e.md`
- `docs/audits/liushanmen/2026-06-12-flow-warehouse-e2e.md`
- `docs/audits/liushanmen/2026-06-12-section7-screenshots/`

## 诚实边界

本批没有改代码；没有触碰 `DEMO-771-VERIFY` 数据。微信报工照片目录已确认存在于 `六扇门工厂数据、/6.1-6.3/群内图片`，但本批未 OCR/逐图录入，因此不能把图片到生产报工链判为 deep closed。
