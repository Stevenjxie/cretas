---
name: smartbi-e2e-test
description: SmartBI Chrome 自动化测试工具。使用 Playwright 进行 E2E 测试，支持 Ralph Loop 持续循环测试。适用于 SmartBI 功能验证、图表渲染检查、AI 问答测试。
allowed-tools:
  - Bash
  - Read
  - Grep
  - Glob
---

# SmartBI E2E 自动化测试 Skill

SmartBI Chrome 自动化测试工具。使用 Playwright 进行 E2E 测试，支持 Ralph Loop 持续循环测试。

## 触发词

- `/smartbi-test` - 运行 SmartBI E2E 测试
- `/ralph-loop-e2e` - 运行 Ralph Loop 持续测试

## 功能

1. **单次测试** - 运行所有 SmartBI 功能测试
2. **Ralph Loop** - 持续循环测试直到发现问题
3. **有头模式** - 显示浏览器窗口便于调试
4. **错误截图** - 自动保存失败截图

## 测试覆盖

| 模块 | 测试内容 |
|------|----------|
| 登录 | 快捷登录、表单登录 |
| Excel 上传 | 文件上传、解析、分析 (11 sheets) |
| AI 问答 | 发送问题、快捷问题、清空 |
| 经营驾驶舱 | KPI、排行榜、图表、刷新 |
| 销售分析 | 筛选、维度切换、导出 |
| 财务分析 | 周期切换、预算对比、同比环比 |
| 图表渲染 | 51+ charts, 6 chart types, 11/11 KPIs |

## 使用方法

```bash
# 进入测试目录
cd tests/e2e-smartbi

# 安装依赖
npm install
npx playwright install chromium

# 运行单次测试
npm test                    # 无头模式
npm run test:headed         # 有头模式（显示浏览器）
npm run test:debug          # 调试模式

# Ralph Loop 持续测试
npm run ralph-loop          # 无限循环
npm run ralph-loop:headed   # 有头模式
LOOPS=50 npm run ralph-loop # 运行 50 轮

# 查看测试报告
npm run report
```

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `BASE_URL` | `http://localhost:5173` | 测试目标 (本地 Vite dev server) |
| `HEADED` | `false` | 是否显示浏览器 |
| `SLOW_MO` | `0` | 操作延迟(ms) |
| `LOOPS` | `0` (无限) | Ralph Loop 最大轮数 |
| `PAUSE_ON_ERROR` | `false` | 错误时暂停 |

**生产环境测试**: `BASE_URL=http://47.100.235.168:8088 npm test`

## 测试数据

将测试用 Excel 文件放入 `tests/e2e-smartbi/test-data/` 目录：
- `Test.xlsx` - 主测试文件 (11 sheets: 1 index, 8 profit statements, 1 data, 1 detail)
- 264-row sheets 的 enrichment 约需 30-40s

## 报告输出

- HTML 报告: `tests/e2e-smartbi/reports/html/`
- JSON 报告: `tests/e2e-smartbi/reports/results.json`
- Ralph Loop 报告: `tests/e2e-smartbi/reports/ralph-loop-report-*.json`
- 错误截图: `tests/e2e-smartbi/reports/screenshots/`

## 注意事项

1. 确保目标服务器可访问
2. 登录状态会自动保存复用
3. Ralph Loop 会持续运行直到手动 Ctrl+C 停止
4. 错误时自动截图保存到 reports/screenshots/
5. Vite 常在 5173/5174/5175 端口切换，注意检查实际端口
6. ECharts canvas 点击需精确数据点定位 (非 DOM 元素)
