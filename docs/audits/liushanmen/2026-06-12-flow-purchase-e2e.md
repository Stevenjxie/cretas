# §8 采购流真实数据 E2E

判定: 🔴 PARTIAL / 出纳付款信息断链  
深度: medium(SQL + API)

## 已坐实

- #773 收货价继承闭环:
  - `PO-20260612-0002` -> `RCV-20260612-1897`, `CONFIRMED`, receive item `unit_price=31.2300`, material batch `dc02c07d-75c2-4ca7-8160-6fe0c8f7046b`.
  - `PO-20260612-0001` -> `RCV-20260612-0613`, `CONFIRMED`, receive item `unit_price=31.2300`, material batch `070ac022-5c64-4943-85b1-29d004a5525e`.
- #774 purchase confirm 和 material-receipt 500 已 live 复验 200。
- cashier 三付款页基线: `GET /payment-requests/approved` 用 `f006_cashier` 返回 200。

## 断链 / 风险

- `PR-F006-20260611-5424` 返回 `bankName=null`, `bankAccount=null`, `payeeName=null`，出纳付款缺收款账户。
- `PO-20260611-0002` 同一采购单下存在 approved `4800.00` 和 paid `7040.00` 的 payment request 记录，需区分历史 DEMO 数据污染还是付款金额错配。
- 演示采购主链如果使用 `PO-20260611-0002`，收货 item 的 `unit_price` 为空；若展示 #773 价继承，推荐使用 `PO-20260612-0002`。

## 结论

采购“下单 -> 收货价继承”可演；“一键付款 -> 双审 -> 出纳付款”因银行信息为空不适合无解释演示。
