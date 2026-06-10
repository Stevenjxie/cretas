# 六扇门验证审计记录模板

> 复制本文件，重命名为 `YYYY-MM-DD-<波次>-<模块>.md`，填写后提交到本目录。
> 每次 headed 浏览器 / 真机验证结论必须在此备案，否则不计入 V1 验收。

---

## 基本信息

| 字段 | 内容 |
|------|------|
| **验证对象** | （如：六扇门 F006 生产链 E2E / 盘点 F6 / 报损 F7） |
| **波次 / Sprint** | （如：W0-EVID / SP1 / SP7） |
| **验证日期** | YYYY-MM-DD |
| **执行人** | （Chat ID 或姓名） |
| **验证环境** | （prod / test；端口；数据库） |
| **run_id** | （seed-demo-chain.py 生成的 run_id，如 20260610_124749） |

---

## 验证方法

- [ ] headed web-admin（浏览器截图）
- [ ] RN 真机 / 模拟器
- [ ] API 直调（curl / seed 脚本）
- [ ] 数据库直查（SQL 验证）

连接方式：`__SSH tunnel__` / `__gateway 139:10010__` / `__本地 localhost__`

---

## 断言清单

逐行填写每个验证点的预期与实际结果。

| # | 断言描述 | 预期 | 实际 | 结果 |
|---|---------|------|------|------|
| 1 | （示例）销售订单 SO-xxx 状态 = FINANCE_APPROVED | FINANCE_APPROVED | FINANCE_APPROVED | PASS |
| 2 | （填写…） | | | |
| … | | | | |

**汇总**：总计 ___ 条，PASS ___ 条，FAIL ___ 条

---

## 证据

### 截图 / 录屏

> 把截图粘贴到此处，或列出截图文件路径（相对于仓库根目录）。

```
screenshots/<run_id>/
  └── <步骤编号>-<描述>.png
```

### seed 脚本输出摘要

> 粘贴 seed-demo-chain.py 的 summary 输出（来自 run JSON 的 summary 字段）：

```json
{
  "total": 0,
  "passed": 0,
  "failed": 0
}
```

### 关键 API 响应 / DB 查询结果

```
（粘贴关键 curl 返回或 psql 查询结果截图/文字）
```

---

## 结论

**整体结论**：PASS / FAIL / PARTIAL

**说明**：
（简述通过/失败原因，遗留问题列 backlog）

**遗留 backlog**：
- [ ] （若有未修复的 FAIL 项，列在此处）

---

## 执行人签认

- 执行人：
- 日期：YYYY-MM-DD HH:MM
- Chat / Session 标识：
