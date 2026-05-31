# 进展小结 — 数据主权 / 垂直分析 grounding / 省 token / 成本审计

**日期**: 2026-05-31
**一句话**: 把官网卖点"客户数据不喂公有 AI"做成真功能(脱敏+审计), 把通用分析做成垂直行业分析且防 LLM 编数字(grounding), 用"规则优先"省 token, 再用 superpowers 审计 + prod 真实记账裁决"是否值得继续压缩"并补全测量。全部 LIVE test+prod。

---

## 一、做了什么 (按 PR)

| PR | 内容 | 状态 |
|---|---|---|
| #335 | **P0 数据主权: LLM 出境脱敏 + 出境审计** | LIVE |
| #336 | **阶段3 垂直分析提示词** (数字引用铁律/推导链/诊断顺序/纵向自比) | LIVE |
| #337/#338 | **阶段2 grounding 对账器** (确定性 metrics 纠正 LLM 数字) | LIVE |
| #339 | 边界 guard + Python 自算整体比率喂对账 + 删冗余行业表 (token 友好) | LIVE |
| #342 | **rules-first 字段映射** (规则准确则跳过 LLM, 不确定才 fallback) | LIVE |
| #343/#344 | 真实客户数据丰富字典 + 列名规范化 + 收紧只信 exact-alias | LIVE |
| #346 | **物化洞察内容哈希复用** (prompt 没变跳过 LLM, 0 token) | LIVE |
| #347 | **流式 token 记账修复** (补 ai_query_chat 记成 0 的盲区) | LIVE |

---

## 二、关键架构决策 (可复用)

1. **脱敏在共享 httpx 客户端包装层, 不是 call_chain** —— 因为很多调用方直连 LLM 绕过 call_chain 但共用 `get_llm_http_client()`。中文专名正则抓不到 → 从 df 敏感列已知值精确替换 + 占位 + 还原(数字/品类 0 损失)。
2. **出境审计表 RLS 必含 `__internal__` 三分支** —— bg flush 的 GUC 永远是 `__internal__`, 否则一行写不进 (Issue #590 复发)。
3. **grounding 走代码侧(对账器)不往提示词塞数据 = 0 LLM token** —— 对账只在指标名处于干净边界(整体口径)时动, 防把某店局部值误纠成整体值; 只信单值单义的事实(显式 metrics / 损益整体比率)。
4. **rules-first 准确性铁律**: 规则只在**确信**(整名精确匹配 curated 字典)时出手, 任何猜测(语义/substring/低置信)一律 defer LLM。错规则比调 LLM 更糟(静默 mis-map)。深核对真实数据抓出 date 语义检测误判 → 收紧只信 exact-alias。
5. **缓存分两层**: 大模型提示缓存(阿里 5 分钟 / DeepSeek 小时级但付费)只救"扎堆调用"; **跨时间真省靠自己的持久结果缓存**(物化 insights 永久 0 token = 黄金标准)。内容哈希复用: sha256(prompt) 命中即复用, 可证明正确(同 prompt→同结果)。

---

## 三、成本审计裁决 (workflow + prod 真账)

- **真实记账** (`smart_bi_llm_usage`, 4/16-5/31): 7945 调用 / 550 万 token, **基本是测试, 无真实客户**。折钱可忽略。
- **不翻倍**: "全量重算" 95% 是确定性代码(Gold ETL/模板物化/KPI, 0 token), LLM 只物化末尾 1 次 + 查询按需。实时更新/新数据源触发的是确定性重算(免费)。
- **最大消耗**: agent_orchestrator(经营看板, 查询路径)= 50%; 导入侧(数据清洗/字段/结构)≈ 35%。
- **裁决**: 安全压缩已做完; 绝对量太小 + 无真实客户 → 继续压缩低 ROI, **等真实负载再精准打**。

---

## 四、推迟 / 不做 (诚实标注)

| 项 | 原因 |
|---|---|
| 查询路径内容哈希复用 (agent_orchestrator) | 等真实查询量再做; 打法已设计(prompt 含数据 → byte-identical=答案不变=安全复用, 天然规避日期重叠判断) |
| business_type 硬门控 | Python 无 per-factory 业态表; 数据列名 detect 已够好 |
| 细粒度日期失效 | 有出旧数据风险; 内容哈希法天然规避, 用它替代 |
| FactBook 吃 Java 指标 / 纵向基线 | situational 低 ROI; 提示词约束 + metrics 对账已拿主要价值 |
| 蒸馏垂直模型 | 经济不划算(数据飞轮 n=2 未激活); 蒸馏管线已在攒样本, 达阈值再议 |

---

## 五、reusable 工具 / 知识

- **真实客户表头是丰富规则字典的金矿** —— 抽列名对比补缺 + 深核对(跑真实数据过识别管线)抓语义猜测误判, 比单测 fixture 强。
- **出境审计表 + smart_bi_llm_usage** —— 真实客户来后可拉真账精准优化(流式已补全记账)。
- 脱敏/对账/规则优先/内容哈希复用 = 四个可往别处推广的套路 (见 memory `feedback_rules_first_llm_fallback`)。

完整设计: `docs/superpowers/specs/2026-05-31-vertical-bi-and-llm-desensitization-impl.md`、`docs/decisions/2026-05-31-cloud-agent-strategy-and-system-changes.md`。
