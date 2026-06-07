---
name: design
description: "统一设计 Skill — 智能路由到正确的设计工作流。覆盖: (1) 业务页面开发 (Vue/RN/小程序三端规范), (2) 创意展示页设计 (landing page/showcase/海报), (3) 从截图提取设计系统, (4) UI 合规审查。当用户说\"做页面\"、\"设计UI\"、\"照着截图做\"、\"审查UI\"、\"美化\"、\"landing page\"时触发。"
---

# 统一设计 Skill

自动识别设计意图，路由到正确的工作流。

## 意图检测与路由

| 用户说的 | 检测信号 | 路由 |
|----------|---------|------|
| "做一个xx页面"、"加个表单"、"写个列表页" | 涉及具体业务功能 | **→ 业务页面** |
| "做一个landing page"、"展示页"、"海报"、"酷一点" | 强调视觉/创意/独立页 | **→ 创意设计** |
| "照着这个截图做"、"参考这个设计" | 提供了截图/参考图 | **→ 截图提取** (调 `ui-designer` 插件) |
| "审查UI"、"检查设计"、"accessibility" | 审查/检查类动词 | **→ UI审查** (调 `web-design-guidelines`) |
| 不确定 | — | 问用户："你要做业务页面还是创意展示页？" |

---

## Route 1: 业务页面开发

### 平台自动检测

| 信号 | 平台 | 加载规范 |
|------|------|---------|
| 路径含 `web-admin/`、提到 Element Plus / Vue / el-table | **Vue Web Admin** | 读 [references/vue-web-admin.md](references/vue-web-admin.md) |
| 路径含 `frontend/CretasFoodTrace/`、提到 RN / Expo / Paper | **React Native** | 读 [references/react-native.md](references/react-native.md) |
| 路径含 `MallCenter/mall_miniprogram/`、提到小程序 / WXSS | **微信小程序** | 读 [references/miniprogram.md](references/miniprogram.md) |
| 多平台对比 | 跨平台 | 读 [references/cross-platform-tokens.md](references/cross-platform-tokens.md) |

**原则**: 严格遵循平台设计规范 (颜色/间距/组件)，不发明新样式。

---

## Route 2: 创意展示页设计

适用于: landing page、showcase demo、产品介绍页、海报、独立 HTML 页面。

### 设计思维

1. **定调**: 选一个鲜明的美学方向 (极简/奢华/复古未来/工业/有机/杂志感/brutalist...)
2. **排版**: 不用 Arial/Inter/Roboto，选有个性的字体组合
3. **色彩**: 大胆主色 + 锐利点缀，拒绝平均分配
4. **动效**: 入场动画 > 零散微交互，CSS-only 优先
5. **构图**: 不对称/重叠/对角线/打破网格/大量留白或高密度

**禁止**: 紫色渐变白底、Inter字体、cookie-cutter 卡片布局、一切"AI味"审美。

实现要求: 可运行的 HTML/CSS/JS 或框架代码，production-grade。

---

## Route 3: 截图提取设计系统

当用户提供截图/参考图时:

1. 调用 `ui-designer` 插件 — 提取颜色/字体/间距/组件模式
2. 生成 Design System Token 文件
3. 用提取的 Token 实现目标页面，确保和参考图视觉一致

---

## Route 4: UI 合规审查

当用户要求审查/检查 UI 时:

1. 调用 `web-design-guidelines` — 检查 Web 界面规范合规性
2. 对照当前平台规范 (Route 1 的 references) 检查项目一致性
3. 输出: 违规项 + 修复建议 + 优先级
