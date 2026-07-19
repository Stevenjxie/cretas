# HANDOFF → Codex：无限测试 campaign 交接 (2026-07-06)

> Opus organizer 收尾，后续"跑流程"E2E 测试转 Codex。Codex 无 `.claude/rules`，本文档**自包含**（环境/账号/litmus/隔离纪律/待办/红线全内联）。

---

## 0. 铁律（红线，先读）
- **只测 `factory_id='F006'`（六膳门食品科技，测试租户）。绝不碰 `factory_id='LIUSHANMEN'`（六膳门，真客户）。二者是两条独立 factory 记录**——本 session 有 4 个 worker 把它俩搞混，务必分清：F006 有测试数据可写；LIUSHANMEN 只读、只做 COUNT 核实真客户有无同类问题，绝不写它的数据。
- **红线代码**（prod 部署 / DB migration / Flyway / 权限 RBAC / RLS / 业态隔离 / 财务口径资金路径 / 架构）→ 只到 PR，回 Opus organizer 终审 + 从 main 部署，Codex 不自部署。
- **prod 只从 main 部署，蓝绿**（后端 47:10010/10020，nginx 139:8086 网关）。部署脚本见 §5。

## 1. 环境 / 账号
- web-admin（prod）：`http://139.196.165.140:8086`，登 `f006_admin`/`123456`（factory_super_admin，配置类必用）。
- RN 业务屏账号：报工 operator `f006_moyun`/`123456`、质检 `f006_quality_insp`/`123456`（**不是 inspector**）、仓管 `f006_warehouse_mgr` / `f006_warehouse_worker` /`123456`。f006_admin 登 RN 进通用 dashboard 无业务屏。
- prod DB 只读：`ssh root@47.100.235.168` → `PGPASSWORD=<REDACTED_REVOKED_CREDENTIAL> psql -U cretas_user -d cretas_prod_db -h localhost`。smartbi 库另密码见 memory `db-credentials`。
- 真实产品族（测试用，别用合成品名会触发防呆过滤看空=非bug）：猪舌门腔120g `4e345886` / 掌中宝 `1d7fbd73`。

## 2. litmus 定性方法（核心）
判"软件没强制X"是不是 bug 前问：**谁掌握避免伤害的知识？**
- 人能物理看到（不良品/货架实物/报损调拨）→ 软件只需"报的地方"+诚实提示。要求软件硬强制=过度工程，非bug。
- 只有软件知道（未结投料量/并发/单位算术/撤销精确/汇总符号/跨表rollup）→ 人兜不住 → 真bug。
- 诚实 409 保护 / 诚实空 / 诚实 null = 非bug。成功 toast 但不持久 / 死按钮 / 数据静默丢 = 真bug。
- **判 bug 前必对 `origin/main` 核实**（本 session 多次假阳性 = worker 读了主目录别 session 分支 `codex/restaurant-unified-ai-entry` 的旧码）：用 `git -C /c/Users/Steve/cretas-deploy2 show origin/main:<file>` 或干净 worktree。

## 3. 浏览器 / RN 测试隔离纪律（Steve 强调，踩过死锁）
- **每个 headed worker 用独立 `chromium.launch()` Node 脚本**，**绝不用 MCP（`mcp__claude-in-chrome__*`）或 plugin（`mcp__playwright*`）的共享浏览器**（那是死锁根源）。
- 四要素全隔离：独立 `--remote-debugging-port`（9222/9223/9224/9225）+ 独立 `--user-data-dir=./.pw-<x>-<port>/` + 独立 `--window-position` + `headless:false` + `--lang=zh-CN`。
- **并发上限 4 个浏览器**。RN 真机走 adb 不占浏览器。
- **RN 真机可能跑内置包（从没 OTA）**→ 设备发现必须对 origin/main 复验（本 session 设备报的 X2/X4/X6 全是旧码假象，main 已修）。连点登录页版本 5 次看 OTA 诊断。OTA 推送：`cd <main-worktree> && source ~/.ota-env && ./scripts/ota/push-bundle.sh production android`。
- **Expo-web 测 RN**：从 **main worktree** 起（`/c/Users/Steve/cretas-rn-expo`，已在 origin/main + node_modules 装好，`ENVFILE=.env.production npx expo start --web`）。⚠️ **Playwright 默认 `.click()` 太快 RN-web 触摸响应器不认→静默 no-op→会误报按钮坏**，必须真 press 时序 `mouse.move→down→wait(80ms)→up`。Expo-web↔prod 后端无 CORS。
- **Playwright headed 必须 `headless:false`**（中文字体/客户演示价值，见 `.claude/rules/playwright-headed-mode.md`）。
- **worker 别马拉松**：时间盒 45-60 分钟；判死活看真实产物（截图/worktree/浏览器缓存）不看 output 文件大小（0字节=完成才落盘，不是死）。

## 4. 三类重点 bug（campaign 目标）
1. **前端操作能否完成**：死按钮 / 入口不可达 / 成功toast但不持久。
2. **跨端数据流转**（RN手机↔web别漏任一端）：RN 操作 → web 看得到 + 数一致。
3. **汇总/端到端口径**：别只测单据，看看板/报表/KPI 口径，四方对账（单据 vs 看板 vs 报表 vs psql）。

## 5. 部署（只 Opus organizer，从干净 main）
```bash
rm -f /tmp/cretas-backend-deploy.lock
cd /c/Users/Steve/cretas-deploy2 && git fetch origin -q && git reset --hard origin/main
./scripts/deploy/deploy-backend.sh --env prod          # 后端蓝绿, Flyway 自动跑
echo "YES-PROD" | ./scripts/deploy/deploy-web-admin.sh --env prod   # web 必须 echo YES-PROD 否则部到 test
```
- **Flyway `out-of-order` 默认 false** → 迁移号必须递增部署（低号后于高号会被跳过）。加迁移前 `git ls-tree -r --name-only origin/main | grep V20261027_` 查最高号。加枚举值必查 PG CHECK 放宽（踩过多次）。
- 部署后核 jar marker（⚠️ strings 不输出中文=假阴性，用非中文 marker 或 Flyway applied 或 live API 验）。

## 6. 待你拍板项 —— 已全部解决 (2026-07-06 Steve 拍板)
- ✅ **PR #1281 🔒🔒 成品预留台账 + #1282 已发货金额 backfill**：Steve GO → 已部署 v20260706_231741 + **完全验证**（V46/V47 applied、台账建成 F006 76 归属、F001 幽灵 1500→0 释放、所有非软删已发货单 backfill、发货可用量零回归、健康 UP）。
- ✅ **RN 质检缺陷复选框 = 选项 C 删除**（#1284，`QIFormScreen.tsx` 删 3 复选框，数字评分判定完全不动）→ merged + **OTA 已推**（ts=1783351984135，设备冷启生效）。

## 7. 已 live 部署（本 session，15 修）
BUG1 结单族盘点双扣 / BUG2 付款→资金GL / BUG3 停产SFI盲区 / #1268 死退货 / #1271 菜单孤儿×35+模块化可见性 / #1272 现金流期初现金 / #1273 material_batch超扣DB CHECK+守卫 / #1274 退料漏判DEPLETED库存冻结+计划状态回写 / #1275 停产状态守卫 / #1276 批次分配绑定发货 / #1277 调拨超收封顶+清幽灵 / #1278 采购付款GL backfill / #1279 排程分页0/1-based / **#1283 生产进度打屏读真实计划状态(原查空production_reports恒显0%; Controller有重复旧逻辑一并修delegate)+质量统计todayInspections/failedBatches字段** / 🔒🔒 **#1281 成品预留台账**(根治取消/发货预留孤儿+清F001幽灵1500) / **#1282 已发货金额历史backfill** / RN-OTA **#1284 删质检无效缺陷复选框**。全真客户 LIUSHANMEN 零影响。

## 8. backlog / 已知非阻塞
- **经营驾驶舱 (/smart-bi/dashboard) 盲于真实 ERP 销售**：显示"--请传Excel"，但 F006 本月真有 56单¥304,074 sales_orders。SmartBI 只接 Excel 上传管线不读 live `sales_orders`。**SmartBI 域—别测别改**（另一 session 在改 AI/SmartBI），需 Steve/Opus 定 SmartBI 驾驶舱是否该接 live ERP。
- **财务「确认结账」简单路径不结转 P&L**（非致命）：`AccountingPeriodServiceImpl.confirmClose()` 只翻 status=CLOSED，不调 `profitLossClosingService.closePeriod()`（无 PL_CLOSING 凭证/快照/deadline 全 NULL）。靠每日 03:00 `AccountingPeriodScheduler.finalizeLockedPeriods()` 兜底 + BalanceSheet 动态合成未分配利润不失衡。缺 UI 透明度（财务看不出"已锁定但结转待定 vs 已结转"）+ 调度宕机跨天无告警。reopen→reclose 净额对账本身干净(#1122 修复仍生效)。⚠️ 有 worker 于本 session 对 F006 2026-06 点了一次「确认结账」(PENDING_CLOSE→CLOSED)，预计次日 03:00 被调度自动补结转。
- ③ 排程完成概率告警 spam（`SchedulingAIServiceImpl` 概率在计划创建时同步算、materialScore=0/manual无aiConfidence→恒落40/55/37%、920条100%未处理）—— 设计缺陷，模块无客户用，需定算法时机/阈值。
- RN 报工页 N+1（226请求，51条 `E2E-PC-*` 遗留测试批次放大→间歇卡死）→ 后端批量端点 + 清测试污染。
- quality-disposition.ts:8-13 stale 注释（说不改库存状态，#1250 后已假）→ 一行注释修。
- 设备管理 6 bug（记录维护永远400/类型静默丢/告警不落库等）—— Steve defer 不修。
- HR 12 孤儿页 defer（复杂 payroll）；簇1/2/4 已随 #1271 wire。
- reserveStock 取消孤儿的更多 backfill / SCRAP terminal / material-scoped quarantine / 中间道多工序完整链主动验证 —— 未穷尽角落。
- SmartBI / AI 意图 —— **别测**（另一 session 正在改 AI intent 路由，动靶会撞+误判）。

## 9. 已覆盖（深挖过，核心口径干净）
生产全链（混批/多工序/半成品注入/撤销矩阵R1-R11 live）/ 库存守恒 tie-out / 仓储7写操作防呆 / 数据异常状态机 / 生产计划状态机+撤销 / 调拨全生命周期 / 采购全流程 / 销售全流程 / 财务三表口径 / 端到端链(采购入库+生产消耗+成品成本传导+财务GL) / QC隔离放行食安桥 / RN跨端(报工/质检/领料，Expo)。近几轮 miner 收益递减（多数 0-1 bug）。

## 10. 关键 memory（Codex 无法读，摘要已内联上文；Opus session 可读）
`feedback_bug_vs_manual_process_litmus` / `feedback_customer_flow_testing_finds_what_system_view_misses` / `feedback_rn_device_stale_bundle_and_expo_click_quirk` / `feedback_enum_added_db_check_not_widened` / `feedback_liushanmen_production_test_only_f006` / `feedback_fable_blocking_bug_hunt_from_diffs` / `reference_f006_app_accounts` / `reference_mobile_login_ota_ops_facts`。
