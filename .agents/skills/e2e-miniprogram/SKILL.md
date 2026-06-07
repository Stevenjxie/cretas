---
name: e2e-miniprogram
description: 微信小程序 E2E 自动化测试。通过 miniprogram-automator 连接微信开发者工具，执行页面导航、元素交互、数据验证等 E2E 测试。覆盖 MallCenter 小程序 43 个页面。
allowed-tools:
  - Bash
  - Read
  - Write
  - Edit
  - Glob
  - Grep
---

# 微信小程序 E2E 自动化测试

通过 [miniprogram-automator](https://www.npmjs.com/package/miniprogram-automator) 连接微信开发者工具，实现小程序页面的自动化导航、交互和验证。

## 命令解析

解析用户输入的参数（`$ARGUMENTS`）：

| 输入 | 动作 |
|------|------|
| _(空)_ / `run` | **运行全部测试套件** |
| `run core` | 核心流程（首页 + 登录 + Tab） |
| `run shopping` | 购物流程（商品 → 购物车） |
| `run merchant` | 商家中心测试 |
| `run ai` | AI 分析/RAG/装修Chat 测试 |
| `run trace` | 溯源功能测试 |
| `run decoration` | 装修 AI Chat 专项测试 |
| `setup` | 启动 DevTools 自动化 + 验证连接 |
| `check` | 检查环境连通性 |
| `verify <feature>` | 验证指定功能的数据正确性 |

---

## 架构

```
┌─────────────────┐                    ┌──────────────────────┐
│  Node.js 脚本    │  WebSocket 9420   │  微信开发者工具        │
│  (run-e2e.js)   │ ◄════════════════► │  (DevTools)           │
└─────────────────┘                    └──────┬───────────────┘
       │                                      │
       │ miniprogram-automator               │ 模拟器
       │ (npm library)                        ▼
       │                               ┌──────────────────────┐
       └──────────────────────────────► │  MallCenter 小程序    │
                                        │  mall_miniprogram/    │
                                        └──────────────────────┘
```

**重要**: 本 Skill 使用 `miniprogram-automator` Node.js 库直接编写测试脚本，而非依赖 weapp-dev-mcp。MCP server 可作为辅助交互工具使用，但自动化测试脚本更可靠。

---

## 前置条件

### 1. 微信开发者工具

| 项目 | 值 |
|------|-----|
| 安装路径 (Windows) | `C:\Program Files (x86)\Tencent\微信web开发者工具\` |
| CLI 路径 (Windows) | `cli.bat` |
| 小程序项目路径 | `MallCenter/mall_miniprogram` |

**必须开启**：
1. 设置 → 安全设置 → **服务端口** → 开启
2. 设置 → 安全设置 → **自动化测试** → 开启

### 2. Node.js + miniprogram-automator

```bash
node --version  # >= 18.0.0
cd tests/e2e-miniprogram
npm install     # 安装 miniprogram-automator
```

---

## 连接方式 — 关键差异

### ⚠️ `launch()` vs `connect()` — 必须使用 connect()

**`automator.launch()` 在 Windows 上会失败**，因为 `child_process.spawn()` 无法处理含中文字符和空格的路径。

**正确流程**: 手动/脚本启动自动化 → `automator.connect()` 连接。

```bash
# Step 1: 启动 DevTools 自动化模式 (在新终端窗口运行)
"C:\Program Files (x86)\Tencent\微信web开发者工具\cli.bat" auto \
  --project "C:\Users\Steve\my-prototype-logistics\MallCenter\mall_miniprogram" \
  --auto-port 9420
```

```javascript
// Step 2: 在测试脚本中使用 connect()
const automator = require('miniprogram-automator');
const mp = await automator.connect({ wsEndpoint: 'ws://localhost:9420' });
```

### 端口占用问题

如果 9420 端口被之前的 DevTools 占用但无响应：
1. 关闭微信开发者工具
2. 检查端口: `netstat -ano | findstr 9420`
3. 杀掉占用进程: `taskkill /PID <pid> /F`
4. 重新启动 DevTools 并运行 `cli.bat auto` 命令

---

## 核心编程模式 (经验证可靠)

### 1. safeNav() — 标准导航方法

**`navigateTo()`/`reLaunch()` 的 Promise 经常不 resolve**（但导航实际已成功）。必须使用 timeout 包装 + currentPage() 验证：

```javascript
async function safeNav(mp, method, url) {
  try {
    await Promise.race([
      mp[method](url),
      new Promise((_, r) => setTimeout(() => r(new Error('timeout')), 15000))
    ]);
  } catch(e) {}  // 超时是正常的，不是错误
  await new Promise(r => setTimeout(r, 2000));  // 等待页面渲染
  return await mp.currentPage();  // 始终通过 currentPage() 确认
}

// 使用方式
let page = await safeNav(mp, 'reLaunch', '/pages/home/index');
page = await safeNav(mp, 'navigateTo', '/pages/merchant-center/decoration-chat/index');
```

### 2. 数据优先验证

**page.data() 是最可靠的验证手段**，比 DOM 选择器更稳定：

```javascript
const page = await mp.currentPage();
const data = await page.data();

// 验证页面数据
console.log('messages:', data.messages?.length || 0);
console.log('sessionId:', !!data.sessionId);
console.log('themes:', data.themes?.length || 0);
```

### 3. 调用页面方法 + 等待结果

```javascript
// 设置数据
await page.setData({ inputText: '帮我换成蓝色主题' });
await sleep(300);

// 调用方法
try {
  await Promise.race([
    page.callMethod('sendMessage'),
    new Promise((_, r) => setTimeout(() => r(new Error('timeout')), 5000))
  ]);
} catch(e) {}

// 轮询等待异步结果
for (let i = 0; i < 25; i++) {
  await sleep(1000);
  const d = await page.data();
  if (d.messages?.length > oldCount + 1 && !d.isTyping) {
    console.log('AI 回复:', d.messages[d.messages.length - 1].text);
    break;
  }
}
```

### 4. 截图

**⚠️ `screenshot()` 是 MiniProgram 对象的方法，不是 Page 对象的方法**。

```javascript
// ✅ 正确
await mp.screenshot({ path: './screenshots/home.png' });

// ❌ 错误 — page.screenshot 不存在
await page.screenshot({ path: './screenshots/home.png' });
```

注意: screenshot() 在某些 DevTools 版本下可能超时，应包装 try/catch。

---

## API 参考

### MiniProgram 对象 (mp)

| 方法 | 说明 | 注意事项 |
|------|------|----------|
| `mp.navigateTo(url)` | 跳转页面 | Promise 可能不 resolve，用 safeNav |
| `mp.reLaunch(url)` | 重启到页面 | 同上 |
| `mp.switchTab(url)` | 切换 Tab | 仅限 TabBar 页面 |
| `mp.navigateBack()` | 返回上一页 | |
| `mp.currentPage()` | 获取当前 Page 对象 | 始终可靠 |
| `mp.screenshot({path})` | 截图 | 可能超时，加 try/catch |
| `mp.disconnect()` | 断开连接 | 测试结束时调用 |

### Page 对象

| 方法 | 说明 | 注意事项 |
|------|------|----------|
| `page.path` | 当前页面路径 | 字符串属性 |
| `page.data()` | 获取页面 data | **最可靠的验证方式** |
| `page.setData(obj)` | 设置页面 data | 模拟用户输入 |
| `page.callMethod(name, args)` | 调用页面方法 | Promise 可能不 resolve |
| `page.$(selector)` | 查询单个元素 | 不支持自定义组件内部 |
| `page.$$(selector)` | 查询多个元素 | 返回数组 |
| `page.waitFor(selector\|ms)` | 等待条件 | 自定义组件内选择器不可靠 |

### Element 对象

| 方法 | 说明 |
|------|------|
| `el.tap()` | 点击元素 |
| `el.input(text)` | 输入文本 |
| `el.value()` | 获取值 |
| `el.wxml()` | 获取 WXML |
| `el.attribute(name)` | 获取属性 |

---

## 已知限制 (经实测确认)

| 限制 | 影响 | 替代方案 |
|------|------|----------|
| **launch() Windows 失败** | 无法自动启动 DevTools | 手动运行 `cli.bat auto` + `connect()` |
| **navigateTo 不 resolve** | 测试脚本挂死 | 用 `safeNav()` timeout 包装 |
| **screenshot 在 Page 上不存在** | 调用报错 | 使用 `mp.screenshot()` |
| **HTTPS 网络请求阻断** | 自动化模式下小程序无法请求后端 | 用 `page.data()` 验证已有数据 + `curl` 独立验证 API |
| **页面不在 app.json** | navigateTo 返回 "page not found" | 检查 app.json 页面注册 |
| **自定义组件** | `waitFor` 无法穿透 | `page.data()` + 轮询 |
| **扫码/支付/登录** | 模拟器无摄像头/支付/微信登录 | `setData()` 注入 mock 数据 |

### 网络阻断的替代验证策略

在自动化模式下，小程序无法发起真实 HTTPS 请求。验证策略：

1. **验证已有数据**: 页面 onLoad 之前可能有 localStorage 缓存的历史数据
2. **独立 API 测试**: 用 `curl` 直接测试后端端点
3. **预设数据验证**: 用 `setData()` 注入测试数据，验证 UI 渲染逻辑

---

## 页面清单 (43 页)

### 主包 (22 页)

| 路径 | 说明 | 测试套件 |
|------|------|----------|
| `pages/home/index` | 首页 | core |
| `pages/base/search/index` | 搜索 | core |
| `pages/base/webview/index` | WebView | - |
| `pages/auth/login/index` | 登录 | core |
| `pages/auth/register/index` | 注册 | core |
| `pages/auth/bind-merchant/index` | 商家绑定 | merchant |
| `pages/goods/goods-category/index` | 商品分类 (Tab) | shopping |
| `pages/goods/goods-list/index` | 商品列表 | shopping |
| `pages/goods/goods-detail/index` | 商品详情 | shopping |
| `pages/goods/price-calculator/index` | 价格计算器 | shopping |
| `pages/shopping-cart/index` | 购物车 (Tab) | shopping |
| `pages/order/order-confirm/index` | 订单确认 | shopping |
| `pages/order/order-list/index` | 订单列表 | shopping |
| `pages/order/order-detail/index` | 订单详情 | shopping |
| `pages/order/order-logistics/index` | 物流跟踪 | shopping |
| `pages/appraises/form/index` | 评价表单 | shopping |
| `pages/user/user-center/index` | 个人中心 (Tab) | user |
| `pages/user/settings/index` | 设置 | user |
| `pages/user/user-address/list/index` | 地址列表 | user |
| `pages/user/user-address/form/index` | 地址编辑 | user |
| `pages/notification/index` | 通知 | user |
| `pages/orders/checkout/index` | 结算 | shopping |

### 分包 — 溯源 (3 页)

| 路径 | 说明 | 测试套件 |
|------|------|----------|
| `pages/traceability/scan/index` | 扫码溯源 | trace |
| `pages/traceability/detail/index` | 溯源详情 | trace |
| `pages/traceability/quality-report/index` | 质量报告 | trace |

### 分包 — AI RAG (2 页)

| 路径 | 说明 | 测试套件 |
|------|------|----------|
| `pages/ai-rag/chat/index` | AI 客服对话 | ai |
| `pages/ai-rag/history/index` | 对话历史 | ai |

### 分包 — AI 分析 (3 页)

| 路径 | 说明 | 测试套件 |
|------|------|----------|
| `pages/ai-analysis/product/index` | 产品分析 | ai |
| `pages/ai-analysis/factory/index` | 工厂分析 | ai |
| `pages/ai-analysis/industry/index` | 行业分析 | ai |

### 分包 — 推荐/分销 (3 页)

| 路径 | 说明 | 测试套件 |
|------|------|----------|
| `pages/referral/share/index` | 分享 | user |
| `pages/referral/my-referrals/index` | 我的推荐 | user |
| `pages/referral/rewards/index` | 奖励 | user |

### 分包 — 商家中心 (9 页)

| 路径 | 说明 | 测试套件 |
|------|------|----------|
| `pages/merchant-center/index/index` | 商家首页 | merchant |
| `pages/merchant-center/orders/index` | 订单管理 | merchant |
| `pages/merchant-center/product-list/index` | 商品管理 | merchant |
| `pages/merchant-center/product-edit/index` | 商品编辑 | merchant |
| `pages/merchant-center/staff/index` | 员工管理 | merchant |
| `pages/merchant-center/stats/index` | 数据统计 | merchant |
| `pages/merchant-center/settings/index` | 商家设置 | merchant |
| `pages/merchant-center/shop-design/index` | 店铺装修 | merchant |
| `pages/merchant-center/decoration-chat/index` | **AI 装修 Chat** | ai, decoration |

### TabBar 配置

| Tab | 页面 |
|-----|------|
| 首页 | `pages/home/index` |
| 分类 | `pages/goods/goods-category/index` |
| 购物车 | `pages/shopping-cart/index` |
| 我的 | `pages/user/user-center/index` |

---

## 测试套件

### Suite 1: core — 核心流程

```
1.1 首页加载      reLaunch → pages/home/index → data() 验证
1.2 登录页        navigateTo → pages/auth/login → path 验证
1.3 跳过登录      tap .skip-login → 验证回到首页
1.4-1.6 Tab 导航  switchTab × 3 → path 验证
```

### Suite 2: shopping — 购物流程

```
2.1 商品分类      switchTab → goods-category → data() 验证
2.2 商品列表      navigateTo → goods-list → path 验证
2.3 购物车        switchTab → shopping-cart → data() 验证
```

### Suite 3: user — 用户中心

```
3.1 个人中心      switchTab → user-center → data() 验证
3.2 设置页        navigateTo → settings → screenshot
```

### Suite 4: merchant — 商家中心

```
4.1 商家首页      navigateTo → merchant-center/index → data() 验证
4.2 商品管理      navigateTo → product-list → data() 验证
4.3 店铺装修      navigateTo → shop-design → FAB 按钮验证
```

### Suite 5: ai — AI 功能

```
5.1 AI RAG 对话   navigateTo → ai-rag/chat → data() 验证初始化
5.2 产品分析      navigateTo → ai-analysis/product → screenshot
5.3 装修 Chat     navigateTo → decoration-chat → data() 验证:
    - quickQuestions 4 条
    - themes 15 套
    - sessionId 存在
    - 发送消息 → 轮询 AI 回复 → 验证 themeCard/action
```

### Suite 6: trace — 溯源功能

```
6.1 扫码页        navigateTo → traceability/scan → screenshot
6.2 溯源详情      navigateTo → traceability/detail?id=TEST001 → data() 验证
```

---

## 执行流程

### `setup` 命令

```bash
# 1. 检查 DevTools 安装
ls "C:/Program Files (x86)/Tencent/微信web开发者工具/cli.bat"

# 2. 检查端口占用
netstat -ano | findstr 9420

# 3. 启动自动化模式 (需在新终端窗口运行)
"C:\Program Files (x86)\Tencent\微信web开发者工具\cli.bat" auto \
  --project "C:\Users\Steve\my-prototype-logistics\MallCenter\mall_miniprogram" \
  --auto-port 9420

# 4. 验证连接
cd tests/e2e-miniprogram
node -e "
const a = require('miniprogram-automator');
a.connect({wsEndpoint:'ws://localhost:9420'}).then(async mp => {
  const p = await mp.currentPage();
  console.log('Connected! Page:', p.path);
  await mp.disconnect();
}).catch(e => console.error('Failed:', e.message));
"
```

### `check` 命令

```bash
# 环境检查
node --version                    # >= 18
netstat -ano | findstr 9420       # 端口活跃
curl -s http://139.196.165.140:8080/actuator/health  # 后端健康
```

### `run` 命令

```bash
cd tests/e2e-miniprogram
node run-e2e.js          # 全部
node run-e2e.js core     # 仅核心
node run-e2e.js merchant # 仅商家
```

**输出格式**：

```markdown
| # | 测试 | 套件 | 状态 | 耗时 | 备注 |
|---|------|------|------|------|------|
| 1.1 | 首页加载 | core | ✅ PASS | 2.1s | |
| 5.3 | 装修Chat | ai | ✅ PASS | 15.2s | quickQ=4, themes=15 |

**总计**: X/Y Passed
**截图**: tests/e2e-miniprogram/screenshots/
```

---

## 故障排查 (实测经验)

| 症状 | 原因 | 解决方法 |
|------|------|----------|
| `connect()` 超时 | 9420 端口被旧进程占用 | 关闭 DevTools → 杀进程 → 重新 `cli.bat auto` |
| `launch()` 报 "cliPath" 错误 | Windows 路径有中文/空格 | **不用 launch()，用 connect()** |
| `page.screenshot is not a function` | screenshot 在 MiniProgram 上 | 改用 `mp.screenshot()` |
| `navigateTo` 挂死 | Promise 不 resolve | 使用 `safeNav()` 包装 |
| "page not found" | 页面未注册 app.json | 检查 app.json subpackages |
| "网络连接失败" | 自动化模式阻断 HTTPS | 用 `page.data()` 验证 + `curl` 测 API |
| `mp.screenshot()` 超时 | DevTools 版本问题 | try/catch 跳过截图，用 data 验证 |
| `data()` 返回 `{}` | 页面还没加载完 | 增加 `sleep(2000-3000)` |
| 分包页面 404 | 未从主包导航 | 先 `reLaunch('/pages/home/index')` |

---

## 配置

| 项目 | 值 |
|------|-----|
| MINIPROGRAM_DIR | `MallCenter/mall_miniprogram` |
| DEVTOOLS_PORT | `9420` |
| WS_ENDPOINT | `ws://localhost:9420` |
| BACKEND_URL | `http://139.196.165.140:8080` |
| 测试脚本目录 | `tests/e2e-miniprogram/` |
| 截图输出 | `tests/e2e-miniprogram/screenshots/` |
| 结果 JSON | `tests/e2e-miniprogram/results.json` |

---

## 文件结构

```
tests/e2e-miniprogram/
├── run-e2e.js              # 主测试脚本 (6 suites, 16 tests)
├── final-e2e.mjs           # 装修 Chat 专项验证脚本
├── quick-test.js           # 快速连接验证
├── verify-fixes.js         # 修复验证
├── verify-extended.js      # 扩展验证
├── verify-functional.js    # 功能验证
├── verify-shop-design.js   # 店铺装修验证
├── package.json            # miniprogram-automator 依赖
├── screenshots/            # 截图输出
├── results.json            # 测试结果 JSON
└── E2E_TEST_REPORT.md      # 测试报告
```
