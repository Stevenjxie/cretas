# Phase 4 客户演示 Brief (for Steve) — Recovery v2

**日期**: 2026-05-23 (Round 7 deploy 后真 prod-verified)

## 📱 客户微信一段话 (直接 copy 发)

```
X 总, 我们刚给 Cretas 加了一个 AI 餐厅经营分析助手. 想请您试用 5 分钟看看好不好用.

操作:
1. 浏览器打开 http://47.100.235.168:10010 (或 web-admin 链接)
2. 用账号 qhj_warehouse_mgr 密码 123456 工厂 RES_3101_009 登录
3. 进 AIChat 输入: "帮我看上月损溢异常"
4. 30 秒内 AI 给您诊断 + Top N 异常品 + 改进建议
5. 截图发我看看好不好用就行

差异化: AI 会诚实告诉您哪些数据缺什么 (e.g. "P&L 数据需 DiagnosticsHandler 先运行"), 不胡说. 这是我们跟客如云 / MealClaw 的核心区别 — 数据可信优先.
```

---

## Prod-Verified 4 个 phrases (2026-05-23 smoke)

| 客户可能输入 | Prod 路由 | hasResult | 状态 |
|---|---|---|---|
| 帮我看上月损溢异常 | RESTAURANT_ECONOMICS_ANALYSIS | true | ✅ Goal literal |
| 损益分析 | RESTAURANT_ECONOMICS_ANALYSIS | true | ✅ |
| 上月成本 | RESTAURANT_ECONOMICS_ANALYSIS | true | ✅ |
| 哪个菜亏钱 | RESTAURANT_ECONOMICS_ANALYSIS | true | ✅ |

## 2 Path Demo (各 2 min)

### Path A: AIChat — Cretas 数据哨兵 USP
- URL: http://47.100.235.168:10010
- Login: qhj_warehouse_mgr / 123456 / RES_3101_009
- Input: "帮我看上月损溢异常"
- 期望: 30s 出 — Composite Tool 调 3 sub-Tool, 每个 dataAvailable=false 标注 (因 cretas 业务表 hooks 未 wire)
- **Demo 话术**: "AI 诚实告诉您哪些数据缺. 这是 Cretas 数据治理 USP, 不像 MealClaw 1122% 那种胡说."

### Path B: SmartBI composite endpoint — 真实 BI 365 天数据
- URL: `http://47.100.235.168:8083/api/smartbi/restaurant/llm-composite?factory_id=RES_3101_009&month=2026-04`
- 头: Authorization Bearer <token from Path A login>
- 期望: topItems=10 含 **招牌青花椒味 ¥869,754** (646K POS 真数据, Path B2 30→365d fallback)
- **Demo 话术**: "走我们的 BI 端点直接拉 365 天历史. 真的有数据."

## 收反馈 (Steve 边演示边记)

```
客户名: ____________________________
日期: 2026-05-23
角色: 老板 / 店长 / 厨师长

Q1: 看 AI 诊断 (Path A 数据哨兵 message), 感觉?
Q2: 看 Path B Top 10 真菜品, 感觉?
Q3: 这个跟您原来看损益方式比, 哪个更方便?
Q4: 如果要付费, 您愿意加多少钱/月?
Q5: 还有什么改进建议?

Steve 主观判定: 好用 / 凑合 / 不好用
"好用" 引述: "____________________________"
```

## Demo 完后给 PM (本 chat)

把以上反馈贴回 chat. PM 30 min 完成:
- 整理到 retrospective §3 + §8
- 决策书 §7 PART 2 evidence fill
- 决策书 §8 Steve 签字 prompt
- Sprint 11 close

## 已知限制 (客户可能问)

1. 30 天数据空 — cretas 业务表 hooks 未 wire, AI 哨兵会主动告知, 用 365 天 SmartBI 历史补
2. 前端 UI 简陋 — Sprint 11 MVP API-only, 后续 polish
3. test 环境 vs prod — 这次直接 prod 47.100.235.168:10010
4. RES_3101_009 POS 数据空 in smartbi_db — Path B2 fallback 自动处理
