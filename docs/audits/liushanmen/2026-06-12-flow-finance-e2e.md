# §8 财务流真实数据 E2E

判定: ✅ CLOSED(财务导出/凭证/脱敏) + ⚠️ 月结未验  
深度: medium(SQL + API + xlsx header)

## 已坐实

- 含税凭证三行:
  - `SO-20260611-0001` -> 3 entries: 应收 `4520`, 收入 `4000`, 销项税 `520`。
  - `SO-20260611-0004` -> 3 entries: 应收 `15368`, 收入 `13600`, 销项税 `1768`。
- 金蝶导出:
  - `f006_finance_mgr` `POST /finance/voucher-export` 携带 `targetSystem=KINGDEE` 返回 200 xlsx, `8382` bytes, 文件头 `PK`.
  - 不带 `targetSystem` 返回 400 且提示 `targetSystem: 目标系统不能为空`，防呆提示明确。
- 脱敏/权限:
  - `f006_sales_mgr` 调凭证导出返回 403，无财务金额泄露。
  - 库存台账导出为数量台账列，未发现价格/金额字符串泄露。

## 未验证

- 月结流程未跑。
- voucher export 文件内容只验证 xlsx 成功和前置权限，未逐单元格对账。

## 结论

财务的关键演示点“含税三行 + 金蝶 xlsx + 非财务脱敏”可演；月结仍未验。
