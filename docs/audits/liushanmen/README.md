# 六扇门验证审计存档

本目录存放六扇门 ERP-lite demo 所有 headed/真机 验证的结论文档。

## 规则（强制）

> **任何 headed 浏览器 / 真机验证结论，必须在本目录归档一份审计文件，否则不计入 V1 验收。**
> 仅在 chat 交接文档里口头描述"通过"不算数。

## 命名约定

```
YYYY-MM-DD-<波次>-<模块>.md
```

示例：
- `2026-06-29-W0-EVID-full-chain.md`
- `2026-06-29-SP1-production-loop.md`
- `2026-06-30-SP7-warehouse-inbound.md`

## 创建新审计文档

1. 复制 `_template-verification-audit.md`
2. 重命名为上述格式
3. 填写全部字段（含截图/seed summary/断言清单）
4. commit 进本目录

## 与 seed 脚本的关联

`scripts/e2e/liushanmen-demo/seed-demo-chain.py` 每次运行生成 `run-{run_id}.json`，
其中包含：

```json
{
  "run_id": "20260610_124749",
  "steps": [
    { "step_id": "A-1", "name": "A-1 unified-login", "result": "PASS", "assertion": "token length=256", ... }
  ],
  "summary": { "total": 34, "passed": 33, "failed": 1 },
  "created": [...],
  "errors": [...]
}
```

审计文档中引用 `run_id` 可追溯到具体 seed 运行的完整断言记录。

## 当前存档

| 文件 | 波次 | 日期 | 结论 |
|------|------|------|------|
| （待首次 headed 验证后填入） | | | |
