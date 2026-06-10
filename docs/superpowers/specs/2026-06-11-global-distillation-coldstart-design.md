# 全局自我蒸馏系统 — 冷启动语料设计 (Fable 二审定稿)

**日期**: 2026-06-11
**状态**: 设计定稿（Steve 北极星 goal + Fable 设计审计裁定）。执行中。
**北极星 goal (Steve)**: 全局自我 LLM 蒸馏系统 + 所有图表 insight + 高质量蒸馏语料 + 使用舒适度。决策序 **长远 > 审计推荐 > 直接推荐**。所有语料系统都有冷启动基础数据 + 足够好质量回复。

---

## 0. 与历史决策和解（核心裁定）
- 5-31 verdict + 早审 gate 的是**"训练 in-house 模型"这一步**（GPU 经济性 break-even 不到）。
- Steve mandate 的是**"系统"**（采集→质量门→冷启动→策展→导出→评估→语料即服务闭环）。
- **裁定**：立即把系统建到"训练只差一条命令"；训练步保留经济门。质量三担忧（分布失配/45%/冷启动质量）不被推翻，全部正面解掉。
- **关键事实**：语料表已是 serving 路径（chart-insight read-back cache 直接从 `smart_bi_distillation_samples` 回答用户，`chart_insight_service.py:629-667`）→ **语料质量 = 用户今天看到的回复质量**。这是无模型版自我蒸馏（serve-from-corpus），长远×UX 的交点。

## 1. 现状 bug（必修，file:line 已核）
- ⛔ `quality` 列从未被写入（死列，全仓 `quality=` 零命中）；导出 min-quality `quality IS NULL OR >= X` → NULL 全放行 → **质量门今天是 no-op**（`export_distillation_dataset.py:61`）。
- ⛔ **chat_qa input_text = 裸 query 无数据上下文** → (问题→含租户数字答案) 无上下文 → 训练它 = **教模型凭问题幻觉数字**（最重缺陷，`chat.py:92` vs `_corpus_input_text`）。
- ⛔ chat_qa 写死 `business_type="unknown"` 污染分桶（`chat.py:94`）。
- ⚠️ seeder 手调均匀分布生成器（非真实形状）= 分布失配（`seed_chart_insight_corpus.py:238-318`）。

## 2. 五系统冷启动方式（最高质量 = 真管线跑真数据，不是合成）
| 系统 | 冷启动 | 备注 |
|---|---|---|
| chart_insight | seeder 改**真实形状采样**(从 gold 抽 series 形状×匿名标签×±10-15% jitter) + gap-fill | 合成 ≤50%/桶 |
| materialization | **backfill sweep** 全历史 uploads/gold 重物化 | 零合成最干净 |
| agent_insight | **warmup sweep** 跨真实租户×3窗口×报告类型 | 零合成 |
| chat_qa | **题库回放**(餐饮21题+意图集)在真数据上走真 chat 管线 | **前置必修 input_text 嵌上下文** |
| intent_llm (Java/cretas_db) | 一次性+周期 **ETL** `intent_match_records.llm_response` 成功对→统一表 | admin 角色,量小 |
| ai_training_samples | **独立**(意图分类器学生,不同标签格式) | 按自己 golden/canary 计 done |
| 工具型 LLM(~25文件) | **跳过**(机械映射,缓存>蒸馏) | 决定性不做 |

## 3. "系统"= 语料表 + 5 件
1. **激活 quality 列**(全 4+1 采集点写入)。
2. **就绪度仪表**(分桶普查: organic/synthetic 计数 + quality 分布 + 对阈值差距)。
3. **评估资产**(每桶冻结 ~50 golden eval 切片 + LLM-judge 批跑)。
4. **导出器收紧**(NULL 不放行 + 合成占比 cap + 按桶 train-ready JSONL)。
5. **周期刷新 cron**(M4 闭环: 普查→gap-fill→judge→换强 teacher 重教)。
- 不需要(现在): 训练学生/GPU/RLHF/复杂反馈 UI。仅加轻 👍/👎→quality ±。

## 4. "足够好质量" 四层门 + GREEN 判据
- **G1 确定性验证**(写入时): claims-pinning(chart_insight 已有)/JSON·schema·长度·no-fake-data·RBAC-¥ lint。quality: 5=claims验证过 / 4=结构验证+正常served / 3=裸organic。
- **G2 LLM-judge**(离线批): 不可重算来源(chat_qa/agent_insight) qwen3-max 评事实性/可操作/流畅 1-5, ≥4 入训练池。
- **G3 人工抽检**: 导出每桶抽30, ≥90% 通过; 不过→修prompt根因re-judge, 不许手挑。
- **G4 使用信号**: cache命中served无投诉/👍。
- **桶 GREEN(可训) = ≥1000 条 quality≥4 + 合成≤50% + 抽检≥90% + judge均分≥4.2。**

## 5. in-house 模型边界
- "全局自我蒸馏系统"现在 = 上述闭环(含 serve-from-corpus read-back)；训练学生仍按经济门。
- 建到"任一桶 GREEN → 一条命令出训练集"；首 GREEN 桶做 **LoRA eval-only 试点**(只评估不上 prod, 验管线, 不违经济门)。
- 重开训练门: 桶 GREEN **且**(该任务月 LLM 开销过 break-even / 离线·延迟·私有化需求 / 客户要本地模型)。

## 6. 构建顺序
| P | 工作 | 为什么先 |
|---|---|---|
| **P0-1** | 激活 quality(4采集点) + 修 chat.py:94 business_type + 修 chat.py:92 input_text 嵌上下文摘要(仿 `_corpus_input_text`) + 导出器 NULL 不放行 + 分桶普查脚本 | 不修这4处后面全是污染量; chat_qa 当前是幻觉教材生产线 |
| **P0-2** | seeder 真实形状采样(gold抽形+jitter+匿名标签) + 拒绝原因计数 + `--gap-fill` | 正面解分布失配 |
| **P1-1** | 三零合成 sweep: materializer backfill / agent_insight warmup / chat_qa 题库回放(P0-1后) | 真管线×真数据 = 质量上限 |
| **P1-2** | intent ETL(cretas_db→统一表, admin, 一次性+周期) | 收编碎片 |
| **P2** | LLM-judge批 + eval切片 + 抽检协议 + 👍/👎 hook + 周刷新 cron | M4 闭环 |
| **P3**(门控) | 首 GREEN 桶 LoRA eval-only 试点 | 行权演练 |

## 7. 🔒 安全铁律(brief 必带)
- 合成种子用 SEED_R/SEED_F 哨兵 + factory 入 hash → **永不碰真租户 read-back cache**(现状已对, 不许破坏)。
- 去重 hash 保持 `sha256(input_text)`(`distillation_capture.py:22-27`, read-back cache 依赖)。新源防撞靠把 factory/上下文烤进 input_text, **不许改 hash 函数**。
- chat_qa input_text 修复必须含数据上下文(否则训练即幻觉)。

## 8. "Done" 诚实定义
5 个 SFT 语料系统(chart_insight/materialization/agent_insight/chat_qa/intent_llm)各自: 桶集已定 + 达地板量 + quality 覆盖 ≥95% 行 + 普查仪表给每桶 GREEN/YELLOW/RED + 抽检 ≥90%; 加 ai_training_samples 按分类器自己机制单独 done。**只有行数没有 quality 分布+抽检 = 未完成**(这是 comforting artifact 与真语料的分界)。
