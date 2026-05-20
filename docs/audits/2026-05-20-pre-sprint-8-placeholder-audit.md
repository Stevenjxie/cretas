# Pre-Sprint 8 Placeholder Audit

**日期**: 2026-05-20
**触发**: Sprint 8 P0.2 audit, 修信任前提 (per `docs/superpowers/specs/2026-05-20-sprint-8-ai-workdesk-design.md` §P0.2.2)
**结果**: 5 真占位 (audit 报告说 11, 实际 grep 5), 122 总 `el-empty` 多数是合法 empty state.

## Grep 方法

```bash
# 真占位 (开发中/敬请期待/正在开发 等明确 placeholder 文案)
grep -rln 'el-empty.*description=".*\(开发中\|敬请期待\|占位\|正在开发\|coming soon\|尚未\|待\(接\|开\|后端\)\|待开通\|功能 Sprint\)' \
  web-admin/src/views/

# 总 el-empty (含合法 empty state)
grep -rln 'el-empty' web-admin/src/views/ | wc -l
# 122 — 多数合法 empty state ("暂无数据" "请添加第一条")
```

## 5 真占位分类决策

| 文件 | el-empty description | router 引用? | 决策 | 理由 |
|---|---|---|---|---|
| `equipment/maintenance/index.vue` | "功能开发中..." | ❌ (router 用 `list.vue`) | **DELETE** | dead 占位, 设备维护 Sprint 9 backlog |
| `hr/attendance/index.vue` | "功能开发中..." | ❌ (router 用 `list.vue`/`exceptions.vue`/`comptime-balance.vue`/`shift-calendar.vue`) | **DELETE** | dead 占位, HR 月考勤 6×7 矩阵 UI 推 Sprint 9 P1 |
| `system/roles/index.vue` | "功能开发中..." | ❌ (router 用 `list.vue`) | **DELETE** | dead 占位 |
| `system/role-permissions/index.vue` | "功能开发中..." | ✅ (line 916 active) | **HIDE + DELETE** | router 注释 + 删 file. 后端 API `permissionApi.ts` 已 ship, 仅缺前端. Sprint 9 P1 真做 L1/L2 矩阵 UI |
| `platform/canvas-editor/components/ModuleTree.vue` | "尚未配置任何模块" + 引导 | ✅ (component) | **KEEP** | 合法 empty state (含 "请去配置 X" 引导), 不是占位 |

## Execute

```bash
# Delete 3 dead 占位 + 1 active 占位
git rm web-admin/src/views/equipment/maintenance/index.vue
git rm web-admin/src/views/hr/attendance/index.vue
git rm web-admin/src/views/system/roles/index.vue
git rm web-admin/src/views/system/role-permissions/index.vue

# Comment out router entry for role-permissions (line 915-920)
```

## 总结

- **DELETE 4 files** (3 dead + 1 active with HIDE)
- **KEEP 1 file** (合法 empty state)
- Audit 报告的 "11 placeholder" 是基于 broader grep (含合法 empty state), 实际真占位 = 5
- T3 三大报表 audit "路径冲突" 是 false positive (T3 实际挂 `/finance/three-statements` 不是 `/finance/reports`) — 见 P0.1 audit

## Sprint 9 P1 跟进 (列入 backlog)

1. `system/role-permissions` 真做 L1/L2 权限矩阵 UI (后端 API 已 ship)
2. `hr/attendance` 月考勤 6×7 矩阵 UI (per Round 14 demo audit HJ 强项)
3. `equipment/maintenance` 维护计划 + 设备 lifecycle (Sprint 9 backlog 已列)

## AI 化评分追踪

- Sprint 8 起点: 3 / 10
- P0.1 + P0.2 完: 3.5 / 10 (信任建立初步, 用户路径无空 click)
