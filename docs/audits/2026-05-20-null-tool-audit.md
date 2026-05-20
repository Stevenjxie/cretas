# Null/Empty Tool Audit

**日期**: 2026-05-20
**触发**: Sprint 8 P0.3 audit (per `docs/superpowers/specs/2026-05-20-sprint-8-ai-workdesk-design.md` §P0.2.3)
**初步结论**: 原 audit "160+ null tool" 严重 overstated. 实际 sample 后多数是合法 fallback, 真 stub 估 < 30。

## Grep 范围

- Total Tool files: **480** (`find ai/tool/impl/ -name "*Tool.java" | wc -l`) — audit prompt 说 476, 实际 480
- Tool files 含 null/empty return pattern: **225** (47%)
- BUT: sample 后大部分是合法 fallback (非 stub)

## Sample 20 文件分析

随机 sample 20 个含 null/empty 的 Tool, 分类:

| 模式 | Count | 是否 stub? |
|---|---|---|
| `getRequiredParameters() returns Collections.emptyList()` | ~80% | ❌ 合法 (表示 "无必需参数") |
| Helper method `return null` (e.g. `getCellValueAsString(null)`) | ~10% | ❌ 合法 (表示 "no match") |
| `doExecute()` 主路径返 null/empty | ~10% | ⚠️ 可能 stub, 但也可能是 graceful fallback |

20 sample 实际查 doExecute() 主路径返 null: **0 个明显 stub**。
- AlertRuleCreateTool: doExecute() 真实业务逻辑 (创建告警规则)
- RestaurantDailyRevenueTool: doExecute() 查日营业额, 实际计算
- SopParseDocumentTool: doExecute() OCR 解析, helper method 才 return null

## 推断: 真 stub 数量

- 480 Tool × 含 null pattern 47% × sample 显示 stub < 5% = **真 stub ~10-30 个** (远低于 audit 报告 160+)
- 但 sample 限 20, 需要全 audit 才精确。

## 决策

### 不在 P0 删除 DEAD_CODE

- 没足够时间 inline audit 全 480 Tool (每个 read doExecute 估 1-2 min × 480 = 8-16h)
- 无 clear stub case 出现 in sample
- 推 Sprint 8 完毕 + Sprint 9 P0 启动时, dispatch 1 agent (worktree, 4h budget) 全 audit + 删 DEAD_CODE

### 现在做的

1. 输出本 audit doc 修正"160+ null tool" overstated
2. 列推荐 Sprint 9 P0 audit framework
3. 不删任何 Tool 文件 (避免误删合法 fallback)

## Sprint 9 P0 audit framework (推荐方案)

```bash
# Step 1: 仅 grep doExecute() 主路径返 null/empty (不计 getRequiredParameters / helper)
for f in $(find ai/tool/impl/ -name "*Tool.java"); do
  # Read doExecute() method body, check if main return path is null/empty
  awk '/protected Map<String,Object> doExecute/,/^    }/' "$f" \
    | grep -qE "^[^/]*return (null|Collections\.empty)" && echo "$f"
done

# Step 2: 对 step 1 列表每个 Tool 读完整 doExecute 决定:
#   - REAL_NOT_IMPLEMENTED (业务逻辑未写)
#   - GRACEFUL_FALLBACK (合法 "无数据返 null")
#   - DEAD_CODE (无 caller)

# Step 3: 删 DEAD_CODE + 修 REAL_NOT_IMPLEMENTED top 20
```

预计 Sprint 9 P0 audit 工时: 1 agent × 4h, 输出: ~30 DEAD_CODE 删 + ~50 真 stub list (Sprint 9-10 修)。

## 对 Sprint 8 影响

- P0.3 状态: **partial — sample audit 完, 修正 overstatement, 推 Sprint 9 深 audit**
- Sprint 8 P1-P4 不阻塞 (Tool 包装新增 50+, 跟 480 旧 Tool 独立)
- 新 Tool 包装 (Task 1.0-4.3) 必含 unit test 验证非空 return

## AI 化评分追踪

- Sprint 8 起点: 3 / 10
- P0.1 + P0.2 完: 3.5 / 10
- P0.3 完 (sample): 3.5 / 10 (不加分 — 无实质修复)
- 真 audit 完 (Sprint 9): 4 / 10
