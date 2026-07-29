# Product

白垩纪AI Agent（CretasFoodTrace）— 食品加工厂现场作业与溯源 App。

## Register

product

## Platform

android

> 两端都出包（`ios/AIAgent.xcworkspace` 与 `android/` 均为真实原生 target），但两端渲染同一套 Material 皮（`react-native-paper` MD3，全仓无 Cupertino / ActionSheetIOS / SegmentedControlIOS）。按「一套皮就取那个平台的值」判定为 `android`，加载 Material 3 规范。若将来 iOS 要做真正的 per-OS 适配，此值改为 `adaptive`。

## Users

**主要用户：一线操作工** —— `operator` / `warehouse_worker` / `quality_inspector`。他们在车间与仓库现场使用，做报工、入库、出库、盘点、扫码收货、质检。高频、低技术素养、边干活边操作。

代码中另有 8 个角色码（`factory_super_admin` / `hr_admin` / `dispatcher` / `production_manager` / `warehouse_manager` / `quality_manager` / `workshop_supervisor` / `viewer`），由 `roleThemeStore` 按角色动态换肤与换权限。**这些角色的设计主次序尚未确认**，需要时再补。

## Product Purpose

让一线操作工把现场发生的事准确记下来，并且**新人不经培训就能上手**——流动率高的现场，新员工当天就能独立完成一整套操作。

零学习成本是这个 App 的成功定义。任何增加理解负担的设计取舍（新术语、新交互范式、需要解释的图标、多层级导航）都与它冲突。

## Positioning

**全链路可溯源**——原料到成品每一步可追。这是每个屏幕都要强化的那一个主张：屏幕上收集的每一项，都应当能说清它为溯源链条贡献了什么。

## Brand Personality

**工具感、可靠、不装饰。** 像仪表和扫码枪，不追求好看。高对比、大按钮、零多余装饰。

## Anti-references

- **传统 ERP（金蝶 / 用友）** —— 密密麻麻的表单、一屏三十个字段、术语堆砌、必须培训才会用
- **Excel 搬到手机上** —— 表格横向滑、单元格密密麻麻、手指点不中
- **花哨的消费级 App** —— 渐变、动画、弹窗、运营色彩；现场干活的人不需要这些

## Design Principles

1. **零学习成本优先于一切。** 任何取舍在「更强大」与「更好懂」之间，选更好懂。
2. **每个屏幕一个主任务。** 操作工在干活的间隙用它，不是坐下来研究它。
3. **触控尺寸按戴手套/湿手设计**，不是按裸手的最小可用值。
4. **正文字号按老花设计**，信息密度相应降低；灰色小字直接废弃。
5. **不装饰。** 一个视觉元素若不承载信息或不指示可操作性，就删掉。
6. **一致性高于新颖性。** 高频使用的屏幕，可预测比出彩重要——这一类屏幕不做视觉发散。

关联的仓库内强制规范（本文件不重复其内容，落地时按它们执行）：

- `.claude/rules/fool-proof-design.md` —— 防呆 5 规则
- CLAUDE.md 的 **UX Flow Gate** —— 涉及 operator / 仓管 / 质检角色或 `screens/processing`、`screens/warehouse`、`screens/quality-inspector` 路径的屏幕，brainstorming 阶段必须先跑 `ux-flow` skill

## Accessibility & Inclusion

已确认的现场硬约束：

- **戴手套 / 湿手操作** —— 触控精度差。按键必须明显大于 44×44，且间距拉开；相邻的破坏性操作与常用操作不得挨在一起。
- **中老年用户 / 老花** —— 正文字号不能小，信息密度要降。

> 其他常见现场约束（强光/昏暗环境、弱网与断网）**尚未确认**是否适用于本产品，暂不写入。需要时补一轮确认再加。
