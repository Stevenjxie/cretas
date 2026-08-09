# Goal：让规划器的可选值从登记表生成 —— 打通 96% 死掉的格子

工作目录 `C:/Users/Steve/cretas-rest-ai`（不是主仓），分支 `codex/claude-restaurant-generic-executor`。
prod 47.100.235.168，租户 MOCK_REST。前情读 `HANDOFF-2026-08-09-restaurant-ai-generic-executor.md`。

## 问题（今天实测，别重新推导）

执行侧能做 **3168 个格子**，规划器穷举所有可能输出只能到达 **147 个（4%）**：

    可达指标 7/22   可达维度 6/16   可达聚合 5/9
    永远到不了的聚合: above_avg / concentration / extremes / share
    永远到不了的维度: brand category city hour meal_period staff table
                      wastage_reason wastage_type weekday

根因：规划器的输出词汇是**四张手写表**（19 类意图 + 15 指标 + 6 维度 + 4 动作），
而执行侧已经是登记表。卡点在第一层，不在最后一层。

## 核心一句

**prompt 里「你可以选这些指标/维度/聚合」必须从 `metric_registry` 渲染。**
⛔ 只把枚举从 15 扩到 22 是错的 —— 那只是把手写表改大，第四个膨胀点原地不动。
登记表加一行 → 规划器当场能指到 → 永远不用再改 prompt。这才算根治。

## 四批，按顺序，每批独立可证伪

**批 1 · 聚合槽**
规格加 `aggregation` 槽（现在只有 analysis_action + ranking_direction，
表达不了占比/集中度/两端/高于平均）；prompt 的聚合部分从 `AGGREGATIONS` 渲染。
判据：4 种死掉的形态变可达（穷举脚本重跑）；电池 ≥80/85 不退。

**批 2 · 维度**
prompt 的维度部分从 `DIMENSIONS` 渲染（6 → 16）。
判据：10 个死维度变可达；**新旧口径逐条比数字**，现有能答对的问句数字逐字不变。

**批 3 · 指标**
prompt 的指标部分从 `METRICS + DERIVED` 渲染（15 → 22）。
⛔ 数据缺口项（net_profit/table_turnover/staffing 等）**不进 prompt**，
它们没有登记，本来就该走「如实说没有」。判据同批 2。

**批 4 · 路由倒过来**
规格 → 格子 → 执行；意图码降级为「非取数问题」白名单（预测/归因/建议）。
18 个手写 resolver 逐个判定：是格子的特例 → 删；真有特殊逻辑 → 保留并写清理由。
判据：电池 + 全量口径回归；重放语料「答上了」比例 > 今天的 41%。

## 不可违反（违反即整批作废）

- 模型只输出**格子坐标**，不碰数字、不选函数；数字由登记项表达式算。
- prompt 的可选值**只能从登记表渲染**，⛔ 不许在别处再写一份清单。
- **不许建词表**。`_REQUEST_METRIC_RULES` 是现存反例，批 3 之后应缩小而不是变大。
- 置信度不作任何授权依据（实测：错计划 0.95、对计划 −1.0）。
- 缺列如实说缺，⛔ 不许算出 0 冒充答案；列在但全 NULL 显示「—」。
- 每批必须**新旧口径逐条比数字**，只比通过率不算数。
- ⛔ 不新增 `_SAFE_MODELS` 条目（计费闸，只有 Steve 能在控制台确认「用完即停」）。

## 每批都要的验收

- **变异验证**：每道新闸注入变异确认会红，且红在被测行为上。
- **穷举可达性**：跑可达性脚本，报「可达格子数 / 3168」，必须单调变大。
- **全量测试**：`cd backend/python && python -m pytest smartbi/gold/ smartbi/ingestion/ -q`（基线 **452 passed**，不许退）。
- **回归电池**：`restaurant_ai_eval --base https://admin.cretaceousfuture.com`
  （先 source .env.prod 取 RESTAURANT_EVAL_*）。基线 **80/85**，不许退。
- **语料重放**：`smart_bi_llm_fallback_log` 里 RES_GML_001 / R_XMX_FRESH / RES_3101_009
  的 490 条 distinct 问句（剔除含店名后 387 条）。今天基线：答上 41% / 反问 40% /
  契约不通过 18% / 分类失败 0。
- **prod 端到端**：真登录真问，不看单元测试就宣布完成。

## 已知陷阱（今天各栽过一次）

1. **电池分数跟着链头模型的档位走**（80→72 全是模型不是代码）。分数掉了
   先跑 `llm_pool_health` 看档位。⚠️ 它报的 `usable=N/M` 是**链内**的数 ——
   `_SAFE_MODELS` 有 86 个组合而链只用 25 个，别把它读成「没得选了」。
2. **挂钩点在 `answer_contract.validate`**（`restaurant_intent_service.py:1397`，
   单一调用点，每个答案都过）。⛔ 别挂 resolver 层：18 个里 **14 个没有失败出口**，
   它们把「答不出来」写进答案文字而不是给信号。
3. prod 有两个库 `cretas_db`(测试) / `cretas_prod_db`(生产)——连库第一条 SQL 先验判别式。
4. 服务器 `python3` 是 3.6，要用 `./venv-current/bin/python`（3.11）。
5. ssh 单引号命令里不能嵌单引号——SQL/脚本写成文件传。
6. 蓝绿槽位会变，端口用 `ss -lntp` 现查（10010/10020）。
7. 验部署产物用**能区分版本的标记**（行数/特征串）；`ast.parse` 通过 ≠ 是我的文件。

## ⏰ 时钟

08-13 LLM 免费额度到期；08-16 模型白名单算陈旧（要 Steve 在控制台确认
「免费额度用完即停」，AI 不能自行加白名单）。

完成后写交接：做了什么 / 证据 / 没做什么 / 为什么。
