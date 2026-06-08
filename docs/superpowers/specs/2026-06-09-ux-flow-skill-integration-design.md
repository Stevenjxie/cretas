# UX Flow Skill Integration Design

**日期**: 2026-06-09  
**作者**: brainstorming session (Steve + Claude)  
**状态**: 待实现

---

## 背景与目标

Cretas RN App 有 10 个角色，其中 operator（操作员）、warehouse（仓管员）、quality_inspector（质检员）是低技术素养用户群体。这类用户的 UX 摩擦成本最高——犯错难以恢复，依赖他人处理，严重影响产线效率。

**目标**：在现有 superpowers 开发流程里嵌入两道 UX 关卡：
- 设计阶段（hard gate）：brainstorming 时强制产出用户旅程图 + 摩擦点清单
- 实现阶段（advisory）：改 tsx 文件时自动触发 UX 检查清单提醒

**方案选择**：安装外部 skill（ui-ux-pro-max + Expo building-native-ui）+ 项目适配器 skill（ux-flow）

---

## 整体架构

```
外部 skill 层
├── ui-ux-pro-max               ← 99 条 UX 规则库
└── expo/building-native-ui     ← Expo 官方 RN 最佳实践

项目适配层
└── .claude/skills/ux-flow/SKILL.md
    ├── Phase 1: 设计门控（hard gate）
    └── Phase 2: 实现 advisory（hook 触发）

触发层
├── brainstorming skill         ← Phase 1 触发点
│   └── 检测到低素养用户屏幕 → 必须先跑 ux-flow Phase 1
└── settings.json PostToolUse   ← Phase 2 触发点
    └── Edit|Write on screens/processing|warehouse|quality-inspector/**/*.tsx
```

### 路径范围

| 优先级 | 路径 | 角色 | 门控强度 |
|--------|------|------|---------|
| P1（本期实现） | `screens/processing/` | operator 报工 | Phase 1 hard gate + Phase 2 advisory |
| P1 | `screens/warehouse/` | 仓管入库/出库/盘点 | Phase 1 hard gate + Phase 2 advisory |
| P1 | `screens/quality-inspector/` | 质检员 | Phase 1 hard gate + Phase 2 advisory |
| P2（后续扩展） | `screens/workshop-supervisor/` | 车间主任 | Phase 2 advisory only |
| P2 | `screens/hr/` | HR | Phase 2 advisory only |

---

## Phase 1：设计门控

### 触发条件

brainstorming 阶段，任意以下条件满足即触发 ux-flow Phase 1：

**角色词**：operator、仓管、warehouse、quality_inspector、质检、操作员、仓库、warehouse_worker  
**路径词**：screens/processing、screens/warehouse、screens/quality-inspector  
**功能词**：报工、入库、出库、盘点、质检、扫码入库、收货、发货

### 执行顺序

1. invoke `ui-ux-pro-max` → 针对屏幕功能描述，拉取相关 UX 规则子集
2. invoke `expo/building-native-ui` → 拉取 Expo 原生组件建议
3. 读取 `.claude/rules/fool-proof-design.md` 5 条规则，逐一对照
4. 产出强制 spec 章节（格式见下）
5. 门控检查：spec 缺少该章节 → 拒绝进入 writing-plans

### 强制 spec 章节格式

```markdown
## UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像
[角色 + 技术素养描述 + 典型使用场景]

### 用户旅程
步骤 N → 步骤 N+1（每步：用户看到什么 / 做什么 / 期望结果）

### 摩擦点清单
| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|-----------|---------|---------|
| F1 | ... | HIGH/MED/LOW | fool-proof Rule N / ui-ux-pro-max |

### 每个摩擦点的设计回应
- F1 → [具体设计决策，说明如何消除该摩擦]
- F2 → [...]
```

---

## Phase 2：实现 Advisory Hook

### settings.json 配置

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          {
            "type": "command",
            "command": "node .claude/hooks/ux-advisory.js"
          }
        ]
      }
    ]
  }
}
```

### Hook 脚本逻辑（`.claude/hooks/ux-advisory.js`）

1. 从 `CLAUDE_TOOL_INPUT` 环境变量读取工具调用 JSON
2. 提取 `file_path`
3. 匹配 P1 路径（processing / warehouse / quality-inspector）
4. 匹配到 → 打印 UX Advisory 清单到 stdout
5. 不匹配 → 静默退出（exit 0，不干扰其他文件编辑）

### Advisory 清单内容（stdout 输出）

```
⚠️  UX Advisory [低技术素养用户屏幕]
检查以下项目（advisory，不阻断提交）：

触摸交互
□ 所有可点击元素 ≥ 44×44pt
□ 相邻可点击元素间距 ≥ 8px
□ 点击后 80-150ms 内有视觉反馈（ripple/opacity）

上下文显示（fool-proof Rule 2）
□ 写操作 dialog 标题包含：产品名 + 批次号/单据号
□ 关键计划数字可见（计划数量、已处理量）

边界防呆（fool-proof Rule 1）
□ 数量输入有 max 限制 + 当前可用量显示
□ 超限时提交按钮 disabled，不依赖提交后报错

Dead-end 检查（fool-proof Rule 5）
□ 错误状态有 next action 按钮
□ 空状态有引导操作，不留死路

来源：ui-ux-pro-max + fool-proof-design.md
```

---

## 文件变动清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `.claude/skills/ux-flow/SKILL.md` | 新建 | 适配器 skill，Phase 1 + Phase 2 规则 |
| `.claude/hooks/ux-advisory.js` | 新建 | PostToolUse hook 脚本 |
| `.claude/settings.json` | 更新 | 加 PostToolUse hook 配置 |
| `CLAUDE.md` | 更新 | 加 UX Flow Gate 规则段落 |
| `.claude/skills/design/references/react-native.md` | 更新 | 末尾加指向 ux-flow 的引用 |

---

## 安装步骤

```bash
# 1. 安装外部 skill
/install nextlevelbuilder/ui-ux-pro-max-skill
/install expo/building-native-ui

# 2. 新建适配器 skill（由 writing-plans → subagent 执行）
# 创建 .claude/skills/ux-flow/SKILL.md

# 3. 新建 hook 脚本
# 创建 .claude/hooks/ux-advisory.js

# 4. 更新 settings.json、CLAUDE.md、react-native.md
```

---

## 扩展路线

| 阶段 | 内容 |
|------|------|
| P1（本期） | operator + warehouse + quality_inspector，Phase 1 hard gate + Phase 2 advisory |
| P2 | workshop_supervisor + hr 加入 Phase 2 advisory |
| P3 | 全角色 Phase 1 gate（含 factory_admin 的复杂管理屏幕） |
| P4 | 接入 UX Heuristics（Nielsen 10 法则审计）作为 P1 gate 的额外检查层 |

---

## 验收标准

- [ ] `/ux-flow` 可 invoke，两个外部 skill 正确被调用
- [ ] brainstorming 时输入"操作员报工屏幕"→ 自动触发 Phase 1 → spec 含 UX Flow Analysis 章节
- [ ] 缺少 UX Flow Analysis 章节时，brainstorming 拒绝进入 writing-plans
- [ ] 编辑 `screens/processing/` 下任意 tsx → PostToolUse hook 触发 → advisory 清单出现
- [ ] 编辑 `screens/factory-admin/` 下 tsx → hook 静默，不触发
