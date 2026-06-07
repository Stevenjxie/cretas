# 报告格式 (证据强制)

每个测试项必须附带证据，没证据的 PASS 视为无效。

## 报告模板

```
=== E2E 验收报告 ===
平台: Web-Admin (http://localhost:5173)

## Layer 1: 页面可访问性
✅ /sales/orders — 有 el-table, 16 rows
❌ /hr/dashboard — error toast "系统处理异常"

## Layer 2: CRUD 操作
### 销售订单 — 创建
  action: 点击"新建销售订单" → 填客户/业务员/产品 → 点"创建"
  evidence:
    - 填写字段: 客户=测试客户, 业务员=于昊(下拉), 产品=叮咚猪蹄×10
    - 提交后 toast: "创建成功" (success)
    - 列表刷新后: 第1行 SO-20260402-XXXX 存在
  result: ✅ PASS

### 采购订单 — 创建
  action: 点击"新建采购订单" → 填供应商/原料/价格 → 点"创建"
  evidence:
    - 填写字段: 供应商=青岛飞熊, 原料=鲍鱼×1×50元
    - 提交后 toast: "创建失败" (error)
    - API 响应: 500 "系统处理异常"
  result: ❌ FAIL

## Layer 3: 跨模块数据
### 原料→BOM同步
  evidence:
    - 创建原料: 成功, ID=xxx
    - BOM下拉列表: 包含"测试原料X" ✅
  result: ✅ PASS

总计: 38 通过, 4 失败, 1 警告
```

## 验证规则

| 检查项 | 无效标志 |
|--------|---------|
| Layer 2 无 `填写字段` 行 | ❌ 没填表单 |
| Layer 2 无 `提交后 toast` 行 | ❌ 没点提交 |
| Layer 2 无 `列表刷新后` 行 | ❌ 没验证持久化 |
| Layer 3 无 `下拉列表` 行 | ❌ 没检查跨模块 |
| 全部 PASS 但无证据 | ❌ 假绿灯 |

如果报告不含证据，必须重跑。

## Healer 模式 (--fix)

失败后的自愈流程: 运行→失败→snapshot→分析根因→修复→重跑。

如果应用本身有 bug（不是测试问题），标记为 KNOWN_BUG:
```
result: ❌ KNOWN_BUG
  expected: 创建成功
  actual: 500 后端缺 orderDate
  action: 需修复后端 DTO
```
