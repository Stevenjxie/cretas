---
name: smartbi-test-e2e
description: SmartBI E2E 上传测试。将生成的测试 Excel 通过浏览器上传到 SmartBI，验证完整流程：SSE 解析 → 图表生成 → KPI → AI 分析。使用 Playwright MCP 浏览器交互。
allowed-tools:
  - Bash
  - Read
  - Glob
  - Grep
  - mcp__plugin_playwright_playwright__browser_navigate
  - mcp__plugin_playwright_playwright__browser_snapshot
  - mcp__plugin_playwright_playwright__browser_click
  - mcp__plugin_playwright_playwright__browser_type
  - mcp__plugin_playwright_playwright__browser_fill_form
  - mcp__plugin_playwright_playwright__browser_file_upload
  - mcp__plugin_playwright_playwright__browser_wait_for
  - mcp__plugin_playwright_playwright__browser_take_screenshot
  - mcp__plugin_playwright_playwright__browser_evaluate
  - mcp__plugin_playwright_playwright__browser_console_messages
---

# SmartBI E2E 上传测试

## 命令解析

解析用户输入的参数（`$ARGUMENTS`）：

| 输入 | 动作 |
|------|------|
| _(空)_ | 交互式：列出可用文件，让用户选择 |
| `edge` | 上传所有 6 个边界用例文件 |
| `factory food normal` | 上传指定工厂文件 `Test-mock-food-normal-s42.xlsx` |
| `factory` | 上传所有 12 个工厂文件 |
| `restaurant` | 上传所有 12 个餐饮文件 |
| `stress l1` | 上传 2 个 L1 压力测试文件 |
| `stress l2` | 上传 2 个 L2 压力测试文件 |
| `all` | 顺序上传所有 <10MB 文件 |

可选 URL 覆盖：末尾加 `--url http://xxx` 覆盖默认地址。

## 配置

| 项目 | 默认值 |
|------|--------|
| BASE_URL | `http://localhost:5173` |
| 用户名 | `factory_admin1` |
| 密码 | `123456` |
| 截图目录 | `tests/test-data/screenshots/` |
| 上传超时 | 120 秒 |

## 文件定位

| 参数 | 文件路径模式 |
|------|-------------|
| `factory {industry} {scenario}` | `tests/test-data/Test-mock-{industry}-{scenario}-s42.xlsx` |
| `restaurant {industry} {scenario}` | `tests/test-data/restaurant/Restaurant-{industry}-{scenario}-s42.xlsx` |
| `edge` | `tests/test-data/edge-cases/Edge-*.xlsx` |
| `stress l1` | `tests/test-data/stress/Stress-*-L1-s42.xlsx` |
| `stress l2` | `tests/test-data/stress/Stress-*-L2-s42.xlsx` |

## 执行流程

### Phase 0: 准备

1. 确定目标 URL（默认 `http://localhost:5173`，解析 `--url` 参数覆盖）
2. 用 Glob 确认目标文件存在于 `tests/test-data/`
3. 如文件不存在，提示用户先运行 `/smartbi-test-data gen {type}`
4. 创建截图目录 `tests/test-data/screenshots/`（如不存在）

### Phase 1: 登录

1. `browser_navigate` → `{BASE_URL}/login`
2. `browser_snapshot` 获取页面结构
3. `browser_fill_form` 填写用户名 `factory_admin1`、密码 `123456`
4. `browser_click` 点击登录按钮
5. `browser_wait_for` 等待跳转成功（URL 变为 `/dashboard` 或主页）
6. `browser_snapshot` 确认登录成功

### Phase 2: 上传文件

对每个目标文件执行：

1. `browser_navigate` → `{BASE_URL}/smart-bi/analysis`
2. `browser_snapshot` 获取上传界面结构
3. 查找 `input[type="file"]` 元素（可能在上传区域内，可能需要先点击上传按钮）
4. `browser_file_upload` 设置文件路径（使用**绝对路径**）
5. `browser_click` 点击 "开始分析" / "上传" 按钮
6. `browser_wait_for` 等待 SSE 流处理完成：
   - 监控进度指示（进度条、百分比、或 "处理中" 文本）
   - 等待 "成功处理" 或 "分析完成" 文本出现
   - 超时 120 秒后截图并标记失败
7. `browser_take_screenshot` 保存上传结果截图

### Phase 3: 验证结果

逐项检查并记录：

| 检查项 | 方法 | 通过标准 |
|--------|------|----------|
| **SSE 完成** | `browser_wait_for` 等待成功文本 | "成功处理 N 个 Sheet" 出现 |
| **Sheet 标签** | `browser_snapshot` 获取 tab 列表 | tab 数量 = 预期 sheet 数 |
| **数据表格** | 点击第一个 sheet tab → 检查表格 | 表格行数 > 0 |
| **图表渲染** | `browser_evaluate` 查询 `.chart-container canvas` 或 ECharts 实例 | 至少 1 个 canvas 元素 |
| **KPI 卡片** | `browser_snapshot` 查找 KPI 相关元素 | 至少 1 个 KPI 卡片 |
| **AI 分析** | `browser_snapshot` 查找 AI 分析文本区域 | 文本长度 > 50 字符 |
| **控制台错误** | `browser_console_messages` level=error | 无 500/CORS/致命错误 |

验证后截图保存：`tests/test-data/screenshots/{filename}-result.png`

### Phase 4: 报告

测试完成后输出汇总表格：

```markdown
## SmartBI E2E 测试报告

| 文件 | Sheets | 解析 | 图表 | KPI | AI | 结果 |
|------|--------|------|------|-----|----|------|
| Test-mock-food-normal-s42.xlsx | 22/22 | PASS | 4 charts | 4 KPIs | 200字 | PASS |
| Edge-wide-120col.xlsx | 1/1 | PASS | 2 charts | 2 KPIs | 150字 | PASS |

### 统计
- 总文件: N, 通过: N, 失败: N
- 总图表: N, 总 KPI: N
- 截图保存: tests/test-data/screenshots/
```

### Phase 5: 多文件模式

当指定 `all`、`edge`、`factory`、`restaurant` 等批量模式时：

1. 对每个文件顺序执行 Phase 2 → Phase 3
2. 每个文件测试完后：
   - 如果需要上传新文件，查找 "上传新文件" / "新建分析" 按钮
   - 或直接 `browser_navigate` 回到上传页面
3. 所有文件完成后执行 Phase 4 汇总报告

## 错误处理

| 场景 | 处理 |
|------|------|
| **文件不存在** | 提示运行 `/smartbi-test-data gen {type}` |
| **登录失败** | 截图 + 提示检查服务：`curl http://localhost:5173` 和 `curl http://localhost:10010/api/mobile/health` |
| **上传超时 (>120s)** | 截图 + 标记 TIMEOUT，跳过到下一文件 |
| **SSE ERROR** | 截图 + 记录失败的 sheet 名称 |
| **图表渲染失败** | `browser_console_messages` 检查 500 错误，记录具体 API |
| **页面未响应** | 截图 + 尝试刷新一次，仍失败则跳过 |

## 与 /smartbi-test-data 的协作

```
/smartbi-test-data gen         →  生成文件到 tests/test-data/
                                      ↓
/smartbi-test-e2e edge         →  上传边界文件，验证极端场景
/smartbi-test-e2e factory      →  上传工厂文件，验证常规场景
/smartbi-test-e2e stress l1    →  上传 50K 行文件，验证性能
```

如果 E2E 测试发现 Python/Java 解析问题，用截图和控制台日志辅助定位 bug。
