# Handoff: 六扇门剩余"主动建版本" 规划 + 分发

> **接棒新 chat 读这个。** 上一 session(2026-06-14/15)做完了几乎所有可自主完成的六扇门需求并全部 LIVE prod。本 handoff 交接**剩余 3 项"主动建版本"**(不等客户拍板, 按转录定夺主动建一版, 像之前财务那样)的规划 + 分发。

## 你的任务
对下面 3 项, 走 **superpowers brainstorming → writing-plans → organizer 分发卡 → Codex subagent 做**。Opus organizer 终审 + 从 main 部署。

## 上一 session 已 LIVE prod(别重做, 见 `docs/audits/liushanmen/2026-06-14-session-ship-status-and-backlog.md`)
9 红线 backlog + 4 决策卡(编码严格16位/权限模块/财务账簿/G5关闭)+ 撤回deprecate + 生产退货 + 财务报表(利润表+数量账)+ B9健壮性 + 出成率自动应用 + 防呆(补录时效+证据正规列)+ web-admin 前端。Flyway 到 **V20261024_15**(下一个 V16+)。

## 标准纪律(必遵)
- **verify-first**: 任何项动手前先对 origin/main 代码核实"真缺/已做"(规划文档常把已 ship 标成缺; Explore 会幻觉假包路径)。真实包 `com.cretas.aims`(不是 com/example/cretas)。
- **长远>推荐**: 决策取长远正确(如数据用正规列非塞 notes), 但别 over-engineer 客户没要的。
- **查转录定夺**: 决策查 `docs/meetings/2026-06-09-liushanmen/transcript.txt`(前场39min)+ `transcript-2b.txt`(全员116min), 别问客户。
- Codex 卡自包含(内联 .claude/rules 摘要); worktree off origin/main; 只到 PR; Opus 终审; Flyway 预分配号防撞(本季踩 3 次撞号)。
- **部署后端记得也 deploy-web-admin**(本季遗漏过: 只部 backend, 前端积压不可见)。CI flaky 多是 H2 保留字(已修, NON_KEYWORDS), 别当随机 flaky。

## 3 项(含 verify-first 已查的现状)

### ① 原料厂号方向(P0, 转录详述)
**转录 transcript-2b [111:41-113:04]**: 原料(50CL)有 **产地 + 厂号** 两维, 厂号最细; 录入时录清楚; **生产人员领料/报工时选实际用的厂号**; 不同厂号=不同批次; "回归唯一编码"(结构化非文字, [113:02]"文字越读越识错"); 未来可能 50CL 澳大利亚(产地)。
**verify-first 现状**:
- ✅ `MaterialBatch.origin_place`(产地)字段**已有**(SP4-A4, 一物一码标签用)
- ❌ **厂号 factoryCode 缺**(结构化字段)
- ❌ MaterialCodeSegment 只 L1-3(类型/部位/品名), 无产地/厂号 level
- 领料选批次 picker(`frontend/CretasFoodTrace/src/components/processing/MaterialBatchPicker.tsx` + web-admin)存在但不显厂号
**设计方向(待 brainstorm 确认)**: 厂号作 MaterialBatch 结构化属性(每批次一厂号, 编码化非文字)+ 采购入库录厂号 + 生产人员领料/报工按厂号选批次。是否纳入 16 位编码 segment vs 独立批次属性 = brainstorm 决策点(转录"回归唯一编码"倾向编码化)。⚠️ RN 领料屏部分**已派别 chat**(协调避撞)。

### ② 多SO合并(转录倾向"不单独合并")
**转录**: 倾向"不单独做合并步骤, 直接在销售单加号追加"(中台下单习惯)。
**待 verify-first**: 查销售订单页是否已有"加号追加"多 SO 行 / 多 SO → 单生产工单。大概率**最小改或已有** → 别 over-build 一个独立合并模块(客户没要)。brainstorm 确认是否真缺。

### ③ 金蝶导入模板(账簿已有, 缺凭证导入默认版)
**转录 [26:20-29:35]**: 财务要把数据导进他们金蝶("按他们表头记录"), 账簿(凭证/科目余额/总账/明细/序时/试算)+ 数量账(进销存)上一 session **已做**。
**缺**: 凭证**导入**模板(金蝶可直接 import 的格式)。**⚠️ 真正卡客户**: 需客户金蝶具体版本(KIS/云星空/精斗云)才能精确匹配表头。
**设计方向**: 主动建**云星空默认版**(最常见)凭证导入模板, 客户给版本再微调列序。别等客户。

## 关键文件指针
- ship 状态 + backlog: `docs/audits/liushanmen/2026-06-14-session-ship-status-and-backlog.md`
- 出成率设计 spec(参考格式): `docs/audits/liushanmen/2026-06-15-yield-self-learning-autoapply-spec.md`
- 财务导出现状: `service/finance/impl/VoucherExportServiceImpl.java`(账簿+利润表+数量账)
- 编码: `MaterialCodeSegment` + `RawMaterialTypeServiceImpl`(SP8 16位)
- 转录 + 需求: `docs/meetings/2026-06-09-liushanmen/`
