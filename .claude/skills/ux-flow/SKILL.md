# UX Flow Gate Skill

低技术素养用户屏幕的两阶段 UX 关卡适配器。

**触发时机**: brainstorming 阶段，检测到以下任意信号时，必须在 propose approaches 之前先完成 Phase 1。

## 触发信号检测

角色词（任意）: operator、操作员、仓管、warehouse_worker、quality_inspector、质检员  
路径词（任意）: screens/processing、screens/warehouse、screens/quality-inspector  
功能词（任意）: 报工、入库、出库、盘点、质检、扫码收货、发货

## Phase 1：设计门控（hard gate）

### 执行顺序

1. 读取 `.claude/rules/fool-proof-design.md`，对照 5 条规则逐一检查
2. 应用内联 UX 规则（见下方「内联 UX 规则」）对屏幕功能逐项评估
3. 产出强制 spec 章节（格式见下方「强制 spec 章节格式」）
4. 门控检查：进入 writing-plans 前，spec 必须包含完整的「UX Flow Analysis」章节

### 内联 UX 规则（低技术素养用户 RN 屏幕）

**触摸目标**
- 所有可点击元素 ≥ 44×44pt（Paper `TouchableRipple` / `Button` 默认满足，自定义 View 需显式设 minHeight/minWidth）
- 相邻可点击元素间距 ≥ 8px，防误触
- 点击后 80-150ms 内有视觉反馈（ripple / opacity）

**信息密度**
- 每屏只暴露当前步骤需要的信息，不堆叠下一步的字段
- 主操作按钮唯一，副操作降级为文本链接或 icon
- 数字输入用大字号（≥ 24px），避免仓管员看错

**错误恢复**
- 错误文案用「发生了什么 + 怎么解决」双句式，禁止纯技术 message（"500 Internal Server Error"）
- 错误后保留已填数据，不清空表单
- 网络失败显示重试按钮，不显示空白屏

**扫码 / 批次选择**
- 扫码结果立即回显品名 + 数量，操作员目视确认后才允许提交
- 多批次选择屏每卡带「产品名 + 批次号 + 当前工序」（fool-proof Rule 2）
- 单批次自动跳转，不让用户多点一次

**Expo / React Native Paper 具体约束**
- 用 `TouchableRipple` 替代 `TouchableOpacity`（Material ripple 反馈更明显）
- 数量输入用 `keyboardType="numeric"` + `TextInput` Paper 组件
- 底部安全区用 `ScreenWrapper` / `SafeAreaView` edges={['bottom']}
- 加载中用 `ActivityIndicator`，不用 skeleton（仓管员不熟悉 skeleton 语义）

### 强制 spec 章节格式

spec 文件里必须包含以下章节，缺失则拒绝进入 writing-plans 并提示补全：

~~~markdown
## UX Flow Analysis（ux-flow 门控产出，不可删除）

### 用户画像
[角色名称] — [技术素养描述] — [典型使用场景 1-2 句]

### 用户旅程
| 步骤 | 用户看到 | 用户操作 | 期望结果 |
|------|---------|---------|---------|
| 1 | ... | ... | ... |

### 摩擦点清单
| # | 摩擦点描述 | 严重程度 | 来源规则 |
|---|-----------|---------|---------|
| F1 | ... | HIGH/MED/LOW | fool-proof Rule N / 内联 UX 规则 |

### 每个摩擦点的设计回应
- F1 → [具体设计决策]
- F2 → [具体设计决策]
~~~

## Phase 2：实现 Advisory（由 PostToolUse hook 触发）

开发者编辑 P1 路径下的 `.tsx` 文件时，`.claude/hooks/ux-advisory.js` 自动运行，输出以下检查清单（advisory，不阻断）：

**P1 路径**: `screens/processing/`、`screens/warehouse/`、`screens/quality-inspector/`

检查清单内容（清单由 hook 脚本打印，不需要 skill 重复输出）：
- 触摸目标 ≥ 44×44pt，间距 ≥ 8px，点击反馈 80-150ms
- fool-proof Rule 1：边界防呆（max 限制 + disabled 按钮）
- fool-proof Rule 2：上下文显示（产品名 + 批次号/单据号）
- fool-proof Rule 5：Dead-end 改导航（错误/空状态有 next action）

## 扩展路线

| 阶段 | 范围 |
|------|------|
| P1（当前） | processing / warehouse / quality-inspector |
| P2 | workshop-supervisor / hr 加入 Phase 2 advisory |
| P3 | 全角色 Phase 1 gate |
