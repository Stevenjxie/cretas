# Dispatch 完成记录 — 2026-08-09

- `BUG-F006-CUSTOMER-STOCK-INTERIM-OWNERSHIP-20260809` — `merged` — Owner: `/root` — Base SHA `e34717e3d0864d8a66bbe9d2d943aa13b14fb90b`；F006 写入型 Playwright E2E 在客户归属库存生产执行“生产小结”前发现，小结入口创建成品批次时未继承计划的 `outputOwnership/customerId/sourceOrderId`，会把客户来料成品误作公司库存并阻断后续销售订单绑定。修复后小结成品与普通结单入库保持同一归属口径：客户库存生产继承 `CUSTOMER_OWNED` 与同一客户，且没有销售订单时不虚构订单来源；公司库存生产继续保持 `COMPANY_OWNED` 并清空客户归属。`InterimSettleServiceTest` 29/29 通过，scope 随本归档释放；离线 exact-local-main 发布及 F006 小结、成品库存、销售绑定写入型 E2E 由当前协调任务继续记录。
