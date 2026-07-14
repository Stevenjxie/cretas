# F006 单位治理 Phase 1 只读扫描 Runbook

## 目标与边界

本阶段只读取 F006 的产品、原料、产品换算关系和所有已保存 Workflow 版本，输出可定位的单位冲突；不会修改主数据、Workflow、启用状态或生产数据。

接口：`GET /api/mobile/F006/unit-governance/conflicts`

每条结果包含：`factoryId`、`productTypeId`、`workflowVersion`、`nodeId`、`portId`、`current`、`expected`、`errorCode`。主数据级冲突没有 Workflow 定位时，对应字段为 `null`。

## 扫描步骤

1. 使用有 F006 读取权限的账号登录并取得短期 token。
2. 请求只读扫描接口，将完整 JSON 保存为带时间戳的审计证据。
3. 按 `errorCode` 统计数量，再按 `productTypeId + workflowVersion + nodeId + portId` 分组处理。
4. 修复必须通过对应主数据或 Workflow 编辑页面完成；不要直接改生产数据库。
5. 重新调用扫描接口，确认目标冲突消失且没有新增冲突后，再发布或启用 Workflow。

示例（先按仓库凭证规范设置环境变量，不把 token 写进文档或命令历史）：

```bash
curl -sS \
  -H "Authorization: Bearer ${CRETAS_TOKEN}" \
  "http://localhost:10010/api/mobile/F006/unit-governance/conflicts" \
  > "f006-unit-conflicts-$(date +%Y%m%d-%H%M%S).json"
```

## 错误码与处理方式

| errorCode | 白话含义 | 正确修复入口 |
|---|---|---|
| `UNKNOWN_UNIT_ALIAS` | 某处保存了系统不认识的单位文字 | 单位目录或对应产品/原料/Workflow 单位字段 |
| `MATERIAL_SKU_UNIT_MISMATCH` | Workflow 物料 Cell 的单位不是所绑 SKU/原料主单位 | Workflow 编辑器重新绑定物料，或先修正主数据 |
| `PROCESS_PRIMARY_PORT_UNIT_MISMATCH` | 工序汇总单位与该方向第一端口不一致 | Workflow 工序节点单位配置 |
| `PORT_CONVERSION_REQUIRED` | 端口单位和物料主单位不同，但没有精确换算引用及版本 | 产品单位换算关系 + Workflow 端口换算选择 |
| `PORT_CONVERSION_STALE` | 换算不存在、跨工厂、版本不符、未生效、已过期或单位对不符 | 更新产品换算关系并在 Workflow 重新选择 |
| `LEGACY_GRAMS_PER_UNIT_AMBIGUOUS` | 旧 `gramsPerUnit` 没有等价的显式 NET_CONTENT 换算，不能再猜“1 件等于多少克” | 产品单位换算模块新增明确关系，例如 `1 件 = 200 g` |
| `WORKFLOW_DEFINITION_INVALID` | 已保存 Workflow 图 JSON 无法读取 | 先备份证据，再由研发修复该版本 |

## `gramsPerUnit` 判定规则

`gramsPerUnit` 仅是遗留提示。只有当产品主单位不是 `g`，且存在当前生效、来源为 `NET_CONTENT`、单位对和倍率都精确匹配的换算关系时，扫描才认为语义明确。

例如产品主单位为“件”、`gramsPerUnit=200`，必须有当前生效的 `件 -> g，factor=200`（或精确反向关系）。产品主单位本身为 `g` 却填写 `gramsPerUnit=200` 会被报告，因为“1 g = 200 g”没有可解释语义。

## 完成标准

- F006 扫描接口返回 HTTP 200，结果可重复且顺序稳定。
- 扫描前后 Workflow JSON、版本、启用状态以及产品/原料主数据均未变化。
- 所有计划发布或启用的 Workflow 不再包含 `PORT_CONVERSION_REQUIRED`、`PORT_CONVERSION_STALE`、`MATERIAL_SKU_UNIT_MISMATCH` 或 `PROCESS_PRIMARY_PORT_UNIT_MISMATCH`。
- 遗留 `gramsPerUnit` 已由显式换算承接，或保留为待治理项并明确责任人。
