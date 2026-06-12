# §8 仓储流真实数据 E2E

判定: 🔴 PARTIAL / 盘点与报损审批有缺口  
深度: medium(SQL + API)

## 已坐实

- 仓库配置齐:
  - `WH-RAW`, `WH-WIP`, `WH-FG`, `WH-RD`, `WH-LOG`, `WH-WKS`, `SALTED-01` 均 active。
- 直入库低权守卫:
  - `f006_sales_mgr`、`f006_viewer` 调 `POST /material-batches` 均 403，无低权绕过。
- 16 位编码:
  - `material-segments/tree` 已有 L1 原料/包材/辅料；本轮补 DEMO L2/L3 后 generate-code 返回 `0019990001000001`。
  - headed UI 新建原料类型弹窗显示 16 位编码级联入口。

## 断链 / 风险

- `factory_stocktakes` F006 当前 0 条；`GET /stocktakes` 返回空页。盘点“发起 -> 审批 -> 盈亏应用”未跑。
- `/stocktakes/{id}/apply` 老路径代码仍缺 `workflowInstanceId` 校验，存在绕审批风险。
- `DisposalRecord` 直批绕过已复现: record `id=3` 无 workflow 直接 approve 成功。

## 结论

仓库基础字典和低权入库守卫可演；盘点和报损/处置审批不应宣称闭环。
