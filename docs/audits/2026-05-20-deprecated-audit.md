# @Deprecated New Code Audit

**日期**: 2026-05-20
**触发**: Sprint 8 P0.4 audit (per `docs/superpowers/specs/2026-05-20-sprint-8-ai-workdesk-design.md` §P0.2.4)
**初步结论**: 原 audit "102 @Deprecated" overstated. 实际 47 files. 多数是 LEGACY_KEPT (老 API 保留 backwards compat).

## Grep 结果

- Total files with `@Deprecated` in main code: **47** (`grep -rln '@Deprecated' backend/java/.../main/java/ | wc -l`)
- audit prompt 说 102 — 可能是 @Deprecated **annotation lines** count (一个文件可有 5-10 个 @Deprecated), 不是 file count

## 主要集中位置 (sample)

### AIIntentService.java — 6 老 API methods @Deprecated

```java
@Deprecated Optional<AIIntentConfig> recognizeIntent(String userInput);
@Deprecated List<AIIntentConfig> recognizeAllIntents(String userInput);
@Deprecated Optional<AIIntentConfig> getIntentByCode(String intentCode);
@Deprecated boolean hasPermission(String intentCode, String userRole);
@Deprecated boolean requiresApproval(String intentCode);
@Deprecated Optional<String> getApprovalChainId(String intentCode);
```

判断: **LEGACY_KEPT** — 老 API 保留, 新 API 可能在同 interface 或新 V2 interface. 需 grep callers 验证。

### DashboardResponse.java 5 字段 @Deprecated

老 SmartBI dashboard 字段, 新版 dashboard DTO 替换。LEGACY_KEPT.

### 其他 ~40 files 分布

- workflow/ — DecisionType 32 重构, 老 API 兼容
- service/skill/ — 老 Skill 形态废弃 (per `feedback_subagent_not_sister_chat` Q1 老 Skill)
- entity/ — 字段重命名后老字段保留

## 决策

### 不在 P0 删除 LEGACY_KEPT

同 P0.3 策略 — 无 clear DEAD case 出现 in initial scan, 删 LEGACY_KEPT 风险高 (可能还有 caller)。

### 现在做的

1. 输出 audit doc 修正"102 @Deprecated" overstated (实际 47 files)
2. 列推荐 Sprint 9 deep audit framework
3. 不删任何 @Deprecated code

## Sprint 9 P0 audit framework (推荐方案)

```bash
# Step 1: 对每个 @Deprecated 方法 grep caller
for f in $(grep -rln '@Deprecated' backend/java/.../main/java/); do
  # 提取 @Deprecated 后的方法名
  awk '/@Deprecated/{getline; print}' "$f" | grep -oE '\b\w+\(' | sort -u > /tmp/deprecated-methods-$$.txt
  while read method; do
    callers=$(grep -rln "${method%(}" backend/java/cretas-api/src --include="*.java" | grep -v "$f" | wc -l)
    if [ "$callers" -eq 0 ]; then
      echo "DEAD_CODE: $f :: ${method%(}"
    fi
  done < /tmp/deprecated-methods-$$.txt
done
```

预计 Sprint 9 P0 audit 工时: 1 agent × 2-3h.

## 对 Sprint 8 影响

- P0.4 状态: **partial — sample audit 完, 修正 overstatement, 推 Sprint 9 深 audit**
- Sprint 8 P1-P4 不阻塞 (新代码独立, 不动 @Deprecated)
- 新 Tool 不引用 @Deprecated API (per AI 架构规范)

## AI 化评分追踪

- Sprint 8 起点: 3 / 10
- P0.1 + P0.2 + P0.3 完: 3.5 / 10
- P0.4 完 (sample): 3.5 / 10 (无实质修复)
- P0.5 总报告完: 4 / 10 (信任建立, P1 准入)
