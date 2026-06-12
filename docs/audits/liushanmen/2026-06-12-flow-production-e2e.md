# §8 生产流真实数据 E2E

判定: 🔴 PARTIAL / 撤回自愈与真多段未深跑  
深度: medium(SQL + API)

## 已坐实

- 生产批次/报工数据存在:
  - `DEMO-COST2-BATCH-223637` batch `1985`, reports `1`, WIP `DEMO-COST2-SF-223637`, `availableQuantity=8`, `unitCost=40`, `accumulatedCost=320`.
  - `DEMO-771-VERIFY-BATCH` batch `1981`, `COMPLETED`, actualQuantity `18.00`, reports `12`；该数据由其他 agent 标记，未触碰。
- 两点/人工诚实:
  - sales multi-stage endpoint 对 `SO-20260611-0004` 返回单段成本，`laborCost=null` 且有“人工登下一期”提示，不按 bug 处理。
- 撤回记录:
  - `GET /reversals` 返回 F006 4 条 DONE；DB `report_reversal_logs` 存在 DONE 记录。
  - 代码路径会软删报工、写 REVERSE、复位任务、清 `SalesOrderItem.costUnitPrice`。

## 未验证 / 断点

- 未新跑“整单撤回 -> cost_unit_price 清 null -> 重报 -> 新成本回填”的运行时闭环。
- 当前 `SO-20260611-0004` 是单段 WIP；真多段半成品链未构造，不能判多段成本深测完成。
- `六扇门工厂数据、/6.1-6.3/群内图片` 下有真实微信报工照片，但本轮未 OCR/逐图转录进系统。

## 结论

生产主数据可支撑演示一段两点成本；撤回自愈和多段链仍是最高优先级深测缺口。
