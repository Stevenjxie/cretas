# 餐饮 AI 优化最终方案：飞轮回接 + 客户需求落地

**日期**: 2026-07-28（最终定稿 v4，含分发卡，已终审）
**基准代码**: origin/main @ `125ad90b5`（所有行号锚点指此提交；主工作树落后 181 提交，实施前必须 fetch + 从 origin/main 开 worktree）
**依据**: ①Python 餐饮管道 + Java 编排层双审计（2026-07-28）②7/27 客户会议转录（`2026年07月27日14点32分_录音转录_分说话人版.txt`，51:48）③7/24-27 Google Sheet 88 条测试修复循环结论。

---

## 0. 产品定位与目标

**定位：一个"秒回的数据同事"，不是"话多的智能顾问"。**

理想使用场景：早上老板收到异常推送（"万达店上周环比 -12%"），随手追问"为什么掉了"秒得结论+表格；月底系统按客户自己的口径自动出报告文件。先把查数问答做到 95 分（客户原话："店长连报表都读不懂，先把报表做好看清楚就不错了"），顾问气质等数据和信任攒够了自然长出来。

**技术目标**: 在不动 LLM-first 精度架构的前提下，把飞轮出口接回生产主链，使系统"越用越快、越用越便宜"，兑现对客户的三个当面承诺：一个问题两三分钱（04:15/05:29）、"学过的问题以后自己答不再花钱"（11:00-12:17 D 的产品讲解 = 飞轮蒸馏，当前架构该链路是断的）、响应速度（45:08 现场卡顿被客户点名）。

**核心矛盾**: 7/27 LLM-first 改造（#1871）后，`parse_restaurant_query(semantic_first=True)` 生产主链不咨询任何零 token 层（`restaurant_intent.py:4127-4132` 注释："they are not consulted by Web or Java restaurant chat"）——飞轮采集端完备但出口断线，每轮必付一次 REVIEW 档 LLM（5s/12s），token 成本随流量纯线性。7/23 首圈晋升的 -91% 效果被架构性归零。

---

## 1. 不变红线 与 歧义防护保证

### 1.1 不变红线
- **LLM-first 不动**: 新表达/带上下文追问/复杂句 100% 由 LLM 理解；澄清反问的"缺不缺、缺哪个"判断权归 LLM，按钮内容与白名单把关归确定性代码。
- **确定性算数不动**: 数字永远 SQL 现算（15 个 resolver），LLM 永不算数字和日期。
- **fail-closed 不动**: 理解不了就明说、真歧义就反问，不猜不编不静默降级。
- **Answer Contract 11 项校验、写安全纵深（READ 三处拦截 / W0 / TCC / demo 闸）不动。**

### 1.2 歧义防护保证（回应"零 token 层会不会带回劫持病"）

旧劫持事故三特征：部分匹配（contains）/ 无人审 / 静默失败。本方案三条零 token 通道结构上均不具备：

| 通道 | 结构性无歧义的原因 |
|---|---|
| 计划缓存 | 归一化后**一字不差**才命中，回放 LLM 自己的理解（同句同答=一致性）；有会话上下文时自动跳过 |
| 晋升表 | 整句相等 + **每条人审** + 组合唯一才生效（可能对应两种理解的短语审核不放行，代码上组合不唯一也不命中）+ 否决账本 |
| 按钮续接 | 按钮是我方生成的封闭词表，语义自定义，构造上无歧义空间 |

语言固有歧义（翻台率口径、"米饭卖得怎么样"）的处理准则：**反问+按钮，绝不擅自拍板**；后有契约校验、👍/👎 反馈、每日电池三道网。晋升冻结额外消灭"答案漂移"（LLM 抖动导致同句不同答）——零 token 层是精度的天花板而非妥协。

---

## 2. 体验原则与功能分层

**六条体验原则**: ①快是第一体验且越用越快（常见问题 0.1s，新问题有状态提示）②结论先行+表格为主，图表仅显式要求、报告只出文件 ③反问少而准（最多一个缺项+按钮，说过的信息绝不再问）④记性好但不啰嗦（全程上下文，回答不复读）⑤诚实到毫米（数据截至日、缺数明说、绝不编数——产品卖的本质是"敢用"二字）⑥克制的主动性（预警只推异常和周报）。

**功能四层**（按客户真实使用频率）:
- **L1 每天用，做到极致**: 查数问答 + 口径统一（客户自定义指标/定制表/跨店菜品对齐）
- **L2 每周用，差异化**: 周环比预警 + 月度报告自动出文件（消灭 C 的重复劳动，客如云没有的东西）
- **L3 有数据再做深**: 综合分析与建议（客如云打通前不吹，建议永远标注非结论）
- **不投入**: 点评关键词深化（客户原话"太粗没参考意义"）、秒级实时监控（"跑太远了"）、全自动经营决策

---

## 3. 技术方案（分批）

### P1 批：止血 + 飞轮回接核心（最高优先，兑现客户承诺）

**1.1 重复委托修复**（纯浪费）: `TieredIntentDelegate.ATTEMPTED_CONTEXT_KEY`（`TieredIntentDelegate.java:36`）只剩读取点、无生产写入点（`acd1a5bb5` 删）。`tryRestaurantTieredDelegate` 在 orchestrator 有 4 个调用点（`:387/:705/:836/:1439`）+ 3 个兜底路径，Python 判 `delegate:false` 后同一请求最坏重复烧 4 次 REVIEW LLM。修法：恢复标记写入，调用点先查。

**1.2 否定 veto 误判修复**: `hasExplicitReadVeto`（`IntentExecutionOrchestrator.java:1905-1927`）纯 contains 匹配 16 词，"不看堂食只看外卖营收"被误判 → 整条 Java 8 层旧链复活（含短语直执行 + Stage-8 直选）。修法：判断否定对象，维度级否定放行语义规划。

**1.3 READ 提示卡修复**: `handleEarlyQuestionTypeDetection:1539-1543` 丢 `mode`/`previewOnly`（同函数 `:1523-1530` 有透传），READ 下写意图显示错误卡片。补透传。

**1.4 计划缓存**（最大杠杆）: 缓存 `_t3_llm_parse` 返回的**原始 JSON**（时间仍是结构化相对描述），命中后重跑 `_semantic_spec_from_t3` 编译——日期按当天重算、数据现查。⛔ 不缓存 sealed spec（其 date_range 是具体日期，跨天=旧窗口；legacy `_cache_put:4552` 的正确语义直接搬）。接入点：semantic_first 分支、`_t3_llm_parse(:4210)` 前查/成功后写。键 `(factory_id, 归一化问句, plan_version)`；准入：①未消费 pending ②上下文继承未改写问句 ③计划完整或标准澄清。TTL 数小时，v1 进程内 LRU。

**1.5 晋升表进主链**（飞轮出口回接）: `_APPROVED_EXACT_ROUTES`（`restaurant_intent.py:83-87`，仅 3 条硬编码）改为 smartbi Postgres 表 **`ai_promoted_routes`**（⚠️ 不带 restaurant 前缀——见 §7 平台化，`domain` 列区分业态，首个值 `restaurant`）: `(domain, normalized_phrase)` PK、**完整计划 JSON**（时间存相对短语）、`plan_version`、`source`(flywheel/manual_seed)、scope(global/tenant)、reviewed_by、hit_count。⚠️ 迁移走 migration runner 且带 `GRANT ... TO smartbi_user`。晋升 CLI `--apply` 落表 + 进程内缓存带失效。semantic_first 顶部（租户门 `:4134` 后、T3 前）整句相等查表（沿用 `_normalize_exact_phrase:1905` + 批准时间词/全部门店组合唯一机制 `:1911-1950`），命中回放，`planner_authority="promoted_exact"`。
- **manual_seed 来源**: 客户提供的常问问题清单 → 离线批量跑一次 LLM 出计划 → 人审 → 落表。飞轮冷启动不等线上流量。
- 四道护栏见 §1.2；回放照走契约校验 + capture（tier="exact"）+ 每日电池。

**1.6 计划格式版本化**（1.4/1.5 的设计前提）: QuerySpec 将演进（输出偏好槽/自定义指标引用/标准菜品 ID），缓存与晋升条目从第一天带 `plan_version`，升版自动失效/迁移。

### P2 批：客户口径落地（客户清单到手即可开工）

**2.1 输出形态偏好 = QuerySpec 新槽位**: LLM 识别显式输出要求（"给我表格/画个图/生成报告文件"）→ `output_preference` 槽；未显式时取租户默认（该客户=文字+表格，图表按需，报告=文件）。渲染层按槽分支；图表/报告 token 只在被要求时发生。

**2.2 口径注册表**（复活 2026-07-08 business-concept-registry spec）: 客户自定义指标（菜品点击率/实收占比：名称/公式/依赖字段/展示格式）入注册表，resolver 与定制表从注册表取定义。⛔ 租户词汇注入 T3 prompt 必须放**动态区**（store_line 同位），不得入静态块（破坏 DashScope 前缀缓存契约 `restaurant_intent.py:3515-3518`）。这是"五份互不知晓的概念定义"的收敛起点。

**2.3 定制表头 → 固定表**: 客户给表头定义，系统拉数生成并保存为固定视图（HeaderMatcher 语义表头识别已有，延伸为保存视图）。

**2.4 菜品主数据层**: `dish_master` + 别名表，语义辅助初匹配 + 一次性人审；resolver/晋升计划/定制表引用**标准菜品 ID**（晋升计划存 ID 而非原文名，跨店稳定）。复用原料相似名匹配 pattern。解决 C 自述的最大工作量。

### P3 批：主动性（计划体系的延伸，不建新引擎）

**统一原则 R1——"计划"是系统通用货币**: 交互问答、缓存、晋升、预警、报告全部执行同一种 sealed QuerySpec。
- **3.1 预警 = 定时执行的计划 + 阈值规则**: 每日数据刷新后跑一组预设 spec，结果过规则（环比阈值/评分异常）触发推送。诚实标注非秒级；真实时取决于 POS 打通。
- **3.2 月度报告 = 计划批量执行 + 模板渲染 + 文件导出**（xlsx/pdf）: 客户口径的报告模板，cron 基建已有。

### 持续项

- **上下文两层记忆**: 原文历史 20 轮不变（`CHAT_SESSION_HISTORY_LIMIT=20`）；新增跨轮滚动"会话状态摘要"（在聊哪店/哪菜/口径/结论，两三百字封顶）持久于 `smart_bi_chat_session`；界面聊天记录完全放开（UI 与模型上下文解耦）。
- **精确日期区间**: 相对/命名时间窗已扎实；绝对区间（"6月3号到18号"）先专测，缺则 T3 `time_range` 加 absolute 类型 + `_resolve_sales_date_range` 加分支。
- **断供保命面**: planner 不可用时先查晋升表/缓存/按钮通道，命中照答；额度耗尽从"全站不可用"降为"只影响新问法"。
- **按钮免费通道扩大**: "已 sealed 问题 + 标准按钮"的续接全部确定性拼装。
- **入口收敛**（原第三批，照旧）: `ChartInsights.vue:23,55` 与 mobile-rest-ai（`api.ts:243,299`）直连收回统一编排；demo `F_DEMO` 治理（既非餐饮租户 `:3443-3454` 也不在写闸名单 `:240`）；SSE 补 tiered-first + 禁吐 1h 旧缓存（`SseStreamingService.java:154-315, 262-299`）；死代码清理（legacy 分支显式决策、`recognizeSessionAwareRestaurantContinuation:1343-1417` 删、`handleSemanticCache:1564` 过时注释）。
- **数据接入连接器 + 新鲜度元数据**: 客如云开放平台对接（先日级同步后准实时，授权流程尽早启动）；维度分级加"数据截至时间"，回答与预警明示。
- **蒸馏演进**（第四批）: 影子对比（MAPPER flash vs REVIEW 计划一致率，按问题家族统计，跑两周）→ 一致率 ≥98% 家族降档（契约兜底，失败=多打一次非答错）→ T2 向量学生（shadow 后 enforce）→ 自有小模型（capture 表的 问句→sealed plan→反馈 即训练对）。

### P4 批：飞轮运营台（web-admin 模块，蒸馏训练的驾驶舱）

把现在靠 CLI + 日志的飞轮周节律 web 化，让非工程角色也能审核，同时成为精准识别与蒸馏训练的数据工作台。web-admin 新模块（建议路由 `/system/ai-flywheel`，平台管理员权限），五个页面：

1. **总览看板**: 今日/7日/30日——问答量、LLM 调用次数与档位分布、缓存命中率、晋升命中率、token 估算、契约失败率、澄清率、👍/👎 分布。数据源=capture 表（`smart_bi_llm_fallback_log` 的 agg_meta 20 字段已够）+ `ai_promoted_routes.hit_count`。
2. **晋升审核工作台**（替代 CLI 人审，CLI 保留给自动化）: 候选队列（问法/频次/置信/契约通过率/计划 JSON 可读化渲染/最近真实答案预览）→ 一键通过（落 `ai_promoted_routes`）/ 否决（落否决账本）；**manual_seed 批量导入**（粘贴客户常问问题清单 → 离线批量跑 LLM 出计划 → 逐条人审入表）。
3. **Miss 复盘**: `RESTAURANT_OPS_MISS` 聚合视图（`aggregate_misses:283` 已有查询逻辑），标注处理状态。
4. **质量与回归**: 契约失败明细、👎 关联的问答对、每日电池结果趋势、（P4 后期）影子对比一致率 per 问题家族。
5. **蒸馏数据集**: 按条件筛选（domain/时间/contract_pass/served/feedback）导出训练对 JSONL（问句 → sealed plan → 反馈标签），供小模型微调与分档决策。

后端工作量小：基本是对 capture/晋升/反馈三张表的读 + 晋升写（复用 `restaurant_intent_promotion.py` 的聚合逻辑挂 API）。⚠️ 查询必须 `set_config app.factory_id` 处理 RLS（晋升 CLI `#1697` 踩过的坑）；平台级视图用管理员通道。所有页面自带 `domain` 筛选（首发只有 restaurant，字段从第一天就在）。

---

## 4. 运营配套

- **8/13 模型注册表保鲜大限**（`_REGISTRY_AUDIT_DATE`，`llm_router.py:94`；正逢 aliyun_c 批量额度日）: 三控制台逐条实测续期，不能只改日期；过期自动收窄 `_MINIMAL_SAFE_SET`。
- **转付费档**: prompt 静态块已排好（`:3515-3518`），付费档自动兑现前缀缓存 2 折（免费档不生效是 7/23 实测定论）。
- **每周飞轮节律**: 日报 → 人审晋升候选 → `--apply` 落表 → 52 案例电池回归；否决账本继续用。
- **REVIEW 12s 总超时 vs Java tiered 10s deadline 注释不一致**（`restaurant_intent.py:3325-3341`）: 对齐。

---

## 5. 客户会议需求映射（7/27，B=管理层 C=运营岗）

| # | 客户需求 | 出处 | 方案项 | 可行性 |
|---|---|---|---|---|
| 1 | 表格/文字优先图表按需 | B 06:44, 45:55 | 2.1 输出偏好 | 高，1-2 天 |
| 2 | 月度报告自动生成+文件下载 | B 40:30, 46:16 | 3.2 | 可行，3-5 天起 |
| 3 | 数据实时/POS 打通（"决定好用不好用"） | B 17:45, 44:06 | 连接器（先日级） | 外部授权依赖 |
| 4 | 自定义表头→定制表（自有口径） | C 31:26, 36:42; B 40:47 | 2.2+2.3 | 高 |
| 5 | 跨店菜品对齐（C 最大工作量） | C 37:04 | 2.4 | 高价值 |
| 6 | 周环比异常预警 | C 38:50（B 对实时监控泼冷水 42:14） | 3.1（日/周级） | 可行 |
| 7 | 常问问题预置 | D 42:21 | 1.5 manual_seed | 直接对齐 |
| 8 | 响应速度（现场卡顿 45:08） | — | 1.4/1.5/分档 | 已覆盖 |
| 9 | 客单价类派生指标 | B 16:43 | 基础已有+2.2 延伸 | 已有 |
| 10 | 点评关键词价值低；商圈分析认可 | C 28:18, 42:15; A 49:21 | 不投入/保留商圈 | — |
| 11 | 统一入口+手机端 | C 42:55; B 18:03 | 入口收敛+app 优化 | 已覆盖 |
| 12 | 别跑太远，先把报表做好 | B 39:41 | L1 做到极致原则 | 排期原则 |
| 13 | 数据准确（"有 BI 我也不敢用"） | C 13:15 | 确定性算数=卖点 | 已有 |

**客户三件套**（D 收尾约定，主动催）: ①表头/表单清单 ②常问问题清单 ③客如云账号授权+设备全流程试。①②到手当天可开工 2.3 与 1.5 预置。

---

## 6. 预期效果

**用户侧**: 常见问题 0.1s 秒回且越用越快；任意打字时间/口语/追问照常听懂；真歧义反问给按钮不擅自拍板；长会话不忘上下文；任何入口同一答案；模型故障时常见问题照答；数字永远现算真账+标注数据截至日。

**服务商侧**: demo 场景 token 预计 -60~80%，真实客户初期 -30~50% 且随晋升逐月下降；断供从全站瘫痪降为只影响新问法；分档后剩余调用大半降至 ~1/10 单价；沉淀"问法→验证计划"垂直数据资产（自有模型本钱与护城河）。

## 7. 平台化通用准备（跨业态复用：工厂及未来项目）

整套 agent 能力（LLM planner → QuerySpec → resolver → 契约 → 飞轮 → 运营台）将复用到工厂等其他垂直域。**现在的设计动作（几乎零成本）**，避免将来重构：

1. **新表新模块一律带 `domain` 维度**: `ai_promoted_routes.domain`、运营台 domain 筛选、蒸馏数据集导出按 domain。capture 表现有 template_code 前缀（RESTAURANT_OPS_*）天然可区分，不改。
2. **计划 schema 泛化命名**: 新增槽位用通用名——`focus_entity {type, name}`（餐饮 type=dish，工厂 type=material/batch/equipment）而非再造 dish 专属字段；`output_preference` 本身域无关。存量 `dish_slot` 不动（v3 演进时收敛）。
3. **明确"域无关核心 / 域包"边界**（文档级约定，不要求现在拆代码）:
   - **可复用核心**: planner 框架（prompt 模板+槽位校验+契约封章）、计划缓存、晋升表+审核流、澄清会话存储（Postgres 原子消费）、飞轮捕获/晋升/否决账本、运营台、LLM router。
   - **每域提供**: 意图目录（_INTENT_DESCRIPTIONS）、resolver 集、指标/口径注册表、领域词汇与 few-shot、澄清按钮词表、业态门控。
4. **工厂侧迁移路径（不强推，留门）**: 工厂现有 8 层意图体系继续跑；待餐饮侧 planner 框架稳定+蒸馏跑通后，工厂高频查询类意图可逐域接入同一 planner 框架（工厂 706 意图中写操作/工具类仍走 Tool-Skill 体系不动——两套各管擅长的：**计划体系管"查数问答"，Tool-Skill 管"业务操作"**，边界清晰不合并）。
5. **运营台从第一天就是平台级**: 新域接入时只需注册 domain + 提供域包，看板/审核/导出自动可用。

## 8. Tool-Skill 体系定位与治理

### 8.1 双体系宪法

337+ Tool / 16 Skill 不被计划体系替代，二者是互补器官，边界如下：

| | 计划体系（QuerySpec→resolver→契约） | Tool-Skill 体系 |
|---|---|---|
| 管什么 | 查数问答、诊断、分析（READ） | 业务操作（WRITE）+ 工厂侧现役查数 |
| 安全模型 | 契约校验 + fail-closed 澄清 | 权限门(63 工具 required_permission)+TCC 预览确认(38 工具)+W0+demo 闸 |
| 客户可见 | 咨询 tab | 操作 tab |

**四条纪律**: ①餐饮新增查数能力一律进 resolver，不再新建查询类 Gold Tool；②Gold Tool（11 个）逐步退化为纯委托适配器，本地兜底回答逻辑随入口收敛评估收掉（避免 delegate:false 时出现分叉答案）；③新增操作能力一律进 Tool（享受权限/预览/确认/审计全套），计划体系的 optimize/操作意图通过操作模式移交 Tool 执行；④运营台补 per-tool 调用遥测，长尾死工具按 7/23 审计模式周期清理（706 意图仅 17 有例句的语料债随口径注册表收敛）。

### 8.2 Tool/Skill 读写声明化（配合咨询/操作双 tab，Wave 2 实施）

**现状（审计结论）**: 读写区分已存在但是**运行时启发式**——`WriteGuardService.isWriteTool` 靠名字后缀+敏感度猜、TOOL_PERMISSION_MAP 仅覆盖 63 工具、READ 拦截靠"识别后拦"。两个已知洞：①识别层候选过滤未接线（`PythonIntentMatchRequest.mode/userPermissions` 字段存在但从不赋值，`AIIntentServiceImpl.java:332-345`）→ READ tab 下写意图先被识别再被拦，LLM 兜底层仍可能选中写意图；②启发式漏判的写工具会绕过 W0 与 demo 闸。

**改法——声明式分类，不物理拆分**（一个注册表 + 属性 + 运行时过滤；不拆两套包，同 domain 读写成对拆开反伤维护）:
1. `ToolExecutor` 接口加访问声明（如 `getAccessMode(): READ | WRITE`）；**未声明默认按 WRITE**（fail-closed）。存量 337 工具用现有启发式预标 + 人审 diff 半自动回填（写类约 60-70 个，读类占大头）。启发式保留为 CI 校验：声明与启发式矛盾 → 构建告警。
2. **Skill 访问模式 = 其编排全部 Tool 的最大值**（任一 WRITE → WRITE），可显式声明但不得低于推导值。
3. **三处消费**: ①候选过滤——mode=READ 时识别层候选集直接剔除 WRITE 工具绑定的意图（接线 mode/userPermissions 两字段 + Java 本地候选过滤），写请求在咨询 tab 得到"请切操作 tab"引导而非误执行风险；②执行门——W0/READ 拦截/demo 闸从启发式改读声明（堵漏判洞）；③UI 与目录——操作 tab 可见性（已有 hasAnyWriteAccess）、ToolRouter 动态选择按 mode 过滤函数目录、运营台按 access mode 统计。

**战略价值**: 从 Copilot 到 Agent 的下一阶段（预警→建议→一键执行）里，Tool 体系就是现成的"行动层"——写安全纵深（预览/确认/审计）正是"AI 操作"能卖给企业的前提，是竞品短期抄不走的部分。

### 8.3 能力缺口与增补机制（回答"Tool/Skill 够不够"）

**存量判断**: 数量够（337 Tool/16 Skill/15 resolver），问题是质量不均（706 意图仅 17 有例句）与缺使用遥测。⛔ 不做预测式批量造工具——历史教训是"造了不养"；增补一律需求驱动。

**三个可预见的结构缺口**（随本方案分批补）:
1. **餐饮操作包**（客户会上已承认"操作这块还没完全好"）: 定制表保存/管理、菜品别名映射确认、预警规则管理（含自然语言配预警："盯着万达店周环比，掉 10% 提醒我"）、报告模板管理/手动触发。全部走 Tool 体系（声明 WRITE，享受预览确认）。⚠️ 对客如云等第三方系统的写回（真实下架/调价）默认不做——先做"建议+人工执行"，写回第三方风险高且依赖平台开放。
2. **连接器类**（新类别，非 Tool 非 resolver）: 客如云/美团/高德等数据摄入 + 同步状态查询/手动触发同步（后者做成 READ tool）。
3. **外部数据维度的 resolver 补齐**: 21 维中 weather/mall_activity/competitor 等多为 PROXY/SIMULATED/MISSING——缺的是数据源，数据到位后补对应 resolver。

**增补分诊原则**: 读能力→resolver；写能力→Tool（声明 WRITE）；跨 Tool 多步写流程→Skill；数据摄入→连接器。**测量仪 = 飞轮 miss 捕获**（运营台页面 3）：真实问法落空自动留痕，每周复盘决定补什么——"够不够"从主观判断变成数据结论。Skill 存量 16 个先测流量（运营台遥测）再决定去留，不预先扩。

### 8.4 Tool/Skill 养护机制（防"造了不养"复发）

**理念**: 用数据养不靠人力盯；飞轮反哺语料；死的体面退役。五件套 + 每周 30 分钟节律：

1. **账本（遥测，一切的前提）**: per-tool/per-intent 调用计数、成功率、兜底率，聚合进运营台（P4 看板加"工具健康"页）。四个榜：调用榜 / 零调用榜 / 失败榜 / miss 榜。
2. **生死簿（三档生命周期）**: 现役（有流量+电池覆盖）/ 观察（90 天零调用 → 候选退役，人工确认）/ 退役（先 `is_active=false` 软下线摘意图绑定 → 观察一个月 miss 无反弹 → 删代码；类保留一季度可回滚）。
3. **出生证（新增门槛，CI 闸）**: 新 Tool 上线必须齐五样——绑定意图、≥5 条例句语料、回归电池 ≥1 条用例、读写声明（§8.2）、权限码。缺任一 → 构建告警。从源头杜绝再造孤儿工具。
4. **喂语料（"养"的主体，飞轮反哺）**: ⛔ 不人肉补 689 个意图的语料。路径：miss 复盘的真实问法 → 补进对应意图 `example_queries` → 跑 backfill-intent-embeddings --refresh + cache clear（有效链已验证，java 短语层/DB keywords 不生效）；高频误路由 → 加描述互斥负例；**按流量排序**，前 20% 高频意图优先补，长尾直接走退役不补。
5. **电池跟流量走**: 每周把新晋高频问法加进回归电池（现 52 餐饮+10 工厂读+8 工厂写），谁有流量谁进电池，不给死工具写测试。

**节律**: 并入既有飞轮周节律，每周 30 分钟运营台四榜三动作（晋升 / 补语料 / 标退役），不开新会。**分工**: 运营台让非工程角色做 80%（语料、晋升审核、退役标记），工程只管代码级（新工具、删代码、CI）。Skill 同规则：无流量 → archive；编排依赖的 Tool 退役时 CI 检查引用。

## 9. 执行顺序（两个 Wave）

```
Wave 1（五卡立即并行）: 卡1 Java修复 ∥ 卡2 Python飞轮回接 ∥ 卡3 菜品主数据 ∥ 卡4 入口收敛 ∥ 卡5 运营台UI(+卡5b API)
   ∥ 客如云授权流程（外部人工，尽早启动）
Wave 2（Wave 1 相关卡 merge 后）: 输出偏好+口径注册表+定制表(2.1-2.3) → 上下文摘要+日期区间 → 读写声明化(8.2) → P3 预警+报告 → 蒸馏演进
```

**冲突规则**（速度优先的前提是不撞文件）:
- `restaurant_intent.py` / `restaurant_intent_service.py` / `gold_reads.py` **单一 owner = 卡2**；口径注册表(2.2)、上下文摘要、日期区间都动这些文件 → 必须 Wave 2 串行。
- `IntentExecutionOrchestrator.java` / `TieredIntentDelegate.java` **单一 owner = 卡1**（orchestrator 内死代码删除已并入卡1）；卡4 禁改这两个文件。
- 卡5 依赖卡2 的 `ai_promoted_routes` 表——先按本 spec §1.5 的表定义 + 下方 API 契约用 mock 开发，卡2 merge 后联调。
- **速度优先原则（Steve 拍板）**: 各卡在自己 chat 内可自由 fan-out subagent 并行（测试/审计/多文件同时改），不为省 token 串行；跨卡靠 worktree 物理隔离。

---

## 10. 🚦 分发卡（即贴即用）

> 行号锚点基于 `125ad90b5`；开工时先 `git fetch origin main`，若 origin/main 已前进，以锚点附近的符号名（方法/常量名）重新定位，不要盲信行号。

### 分发总览（2026-07-28 调整：本轮全部走 Claude Code 子代理，Codex/Composer 通道不用）

全部六卡由主 chat（**Opus 5 organizer + high effort**）通过 Agent 工具 spawn 后台子代理并行执行，worktree 物理隔离不变。effort 依据（2026-07-28 官方文档查证）: Sonnet 5 的 xhigh 是独立档位（旧"Sonnet high 封顶"是 4.6 口径已过时），但子代理继承会话 effort——chat 设 xhigh 会让六个执行代理全部升档，而各卡 brief 已精确到行，xhigh 在已框清任务上主要买到延迟而非质量（速度优先故 high）；红线深审深度由 fable 直通③补，不靠 effort 轴。将来单个代理要 xhigh 走 `.claude/agents/` frontmatter `effort:` 覆盖，不升整会话。

**按 multi-model-dispatch 复核后的分工（2026-07-28 二次审计修正）**：🔒🔒 最硬红线子集（prod 迁移 / 权限·租户·业态）**暂留 Opus 自做**——卡3 的 migration 文件与 F_DEMO 治理从执行卡中拆出，归 organizer 本体；红线 diff 终审 = organizer 本体逐行审（🔒 risky review 不可外包 Sonnet）；**三个不可逆小 diff（卡2 migration / 卡3 migration / F_DEMO 权限）追加 fable 单点终审**（预授权直通③，read-only）。例行卡（5/5b）review 可派 Sonnet。

| # | 任务 | 模型（Agent spawn） | 可否并行 | worktree 分支 | 🔒红线 |
|---|------|---------|---------|--------------|--------|
| 1 | Java 编排器三修复+死代码 | Sonnet 5（严格 brief 已具备） | ✅ | feat/rai-java-fixes | 意图路由敏感，organizer 终审 |
| 2 | Python 飞轮回接（缓存+晋升表） | **Opus 5**（🔒🔒 prod 迁移必须） | ✅ | feat/rai-flywheel-reconnect | 🔒 migration → fable 直通③终审 |
| 3 | 菜品主数据服务层（**migration 由 organizer 自写提供，本卡禁改**） | Sonnet 5 | ✅ | feat/rai-dish-master | 🔒 migration（organizer 自做）→ fable 直通③终审 |
| 4 | 入口收敛（SSE+前端直连；**F_DEMO 治理已拆出归 organizer**） | Sonnet 5 | ✅（禁改卡1文件） | feat/rai-entry-converge | organizer 终审 |
| 5 | 运营台 web-admin UI | Sonnet 5 | ✅（mock 先行） | feat/rai-flywheel-console | 例行 review 可派 Sonnet |
| 5b | 运营台后端 API | Sonnet 5 | ✅（依赖卡2表，可 spec 先行） | feat/rai-flywheel-api | 终审专查 RLS GUC 用法 |
| — | **organizer 本体自做**: 卡3 两张表 migration DDL、F_DEMO 治理（租户判定二选一+写闸名单）、全部红线 diff 终审、merge、从 main 部署 | Opus 5 本体（high） | - | main | 🔒 |
| — | **fable 单点**: 三个不可逆小 diff 终审（预授权直通③）+ 任何卡 1 轮认真尝试没收敛的升级 | Fable 5 subagent（read-only） | - | - | |

> 原卡2/卡4/卡5 写给 Codex/Composer 的"规则摘要"段在 Claude 子代理下自动满足（in-harness 规则可见），保留不删——将来若切回 out-of-harness 通道仍可即贴即用。

### 卡1 → Sonnet in-harness（Claude chat，Sonnet 5 + high）
**目标**: 修 Java 编排器三个缺陷 + 删死代码。rule-heavy（backend/java 规则自动加载），修法已精确到行，Sonnet 足够。
**worktree**: `git fetch origin main && git worktree add -b feat/rai-java-fixes ../cretas-rai-java-fixes origin/main`
**改动四项**（均在 `backend/java/cretas-api/.../service/execution/`）:
1. 重复委托：`TieredIntentDelegate.ATTEMPTED_CONTEXT_KEY` 恢复写入（首次 `tryRestaurantTieredDelegate` 后写 context 标记），orchestrator 4 个调用点（`:387/:705/:836/:1439`）+ `noToolResponseWithRestaurantFallback`/`executeAnalysisFlow`/`executeRestaurantOwnerActionChat` 兜底路径先查标记再委托。
2. `hasExplicitReadVeto`（`IntentExecutionOrchestrator.java:1905-1927`）：contains 匹配改为判断否定对象——否定整个查询才 veto，维度级否定（"不看堂食只看外卖营收"）放行语义规划。
3. `handleEarlyQuestionTypeDetection:1539-1543` 补透传 `mode`/`previewOnly`（照抄同函数 `:1523-1530` 的写法）。
4. 死代码：删 `recognizeSessionAwareRestaurantContinuation:1343-1366` + `resolveSessionAwareRestaurantContinuationInput:1368-1417`（生产无调用点，仅测试引用一并删）；修正 `handleSemanticCache:1564-1567` 过时注释。
**允许改**: `IntentExecutionOrchestrator.java`、`TieredIntentDelegate.java`、对应测试文件。**禁改**: 其余一切（尤其 SseStreamingService/AIPublicDemoController——卡4 领地）。
**验收**: mvn 编译+相关单测过；新增回归测试三条（同请求委托仅 1 次的计数断言 / "不看堂食只看外卖营收"进语义规划断言 / READ 模式短语路由返回 READ_MODE_WRITE_BLOCKED 断言）；`git diff origin/main...HEAD --stat` 只含上述文件。
**交接**: PR off origin/main，**不自 merge 不部署**——意图路由敏感，回 main 由 Opus 终审。

### 卡2 → 贴给 GPT-Codex
**目标**: 飞轮出口回接——计划缓存 + 晋升表进主链 + 断供保命面。本方案的核心杠杆。
**背景**（自包含）: 生产餐饮问答入口是 `parse_restaurant_query(semantic_first=True)`（`backend/python/smartbi/gold/restaurant_intent.py`，callers: `gold_reads.py:1203`、`restaurant_intent_service.py:624`）。semantic_first 分支（`:4127-4296`）目前每个新问题必调一次 REVIEW 档 LLM，legacy 分支的零 token 机制（`_cache_get:4463`、`_approved_exact_route:4373`）生产够不到。你的任务是把这两个机制以正确形态接进 semantic_first 分支。
**worktree**: `git fetch origin main && git worktree add -b feat/rai-flywheel-reconnect ../cretas-rai-flywheel origin/main`
**三项改动**:
1. **计划缓存**: 在 semantic_first 分支 `_t3_llm_parse(:4210)` 前查/成功后写。⛔ 缓存 `_t3_llm_parse` 返回的**原始 parsed JSON**（时间是结构化相对描述），命中后重跑 `_semantic_spec_from_t3` 编译使日期按当天重算——**绝不缓存 sealed spec**（date_range 是具体日期，跨天=旧窗口；legacy `_cache_put:4552` 注释记载了这个坑的正确语义）。键 `(factory_id, 归一化问句, plan_version)`；准入：未消费 pending 澄清 && 上下文继承未改写问句（`semantic_query == norm_query`）&& 计划完整或标准澄清。TTL 4-6h，进程内 LRU（500 条，双 worker 各自热身可接受）。
2. **晋升表**: 新表 `ai_promoted_routes`——`(domain, normalized_phrase)` PK、plan_json JSONB（完整计划，时间存相对短语）、`plan_version`、`source`('flywheel'/'manual_seed')、`scope`('global'/factory_id)、`reviewed_by`、`created_at`、`hit_count`。⚠️ **smartbi 库 schema 变更硬规则：走 migration runner（禁手动 psql DDL），migration 必须带 `GRANT SELECT,INSERT,UPDATE ON ai_promoted_routes TO smartbi_user`**（V20260428_03 惯例；漏 GRANT 会 permission denied 静默 fail-open，V20260708_02 踩过）。semantic_first 顶部（租户门 `:4134` 之后、T3 之前）整句相等查表：沿用 `_normalize_exact_phrase:1905` 归一化 + `_approved_exact_shape:1911-1950` 的"批准时间词/全部门店组合且组合唯一"机制；命中回放计划 JSON→编译→`planner_authority="promoted_exact"`（已在 TRUSTED 白名单）。有 pending 或上下文继承时**跳过**查表。晋升 CLI（`scripts/restaurant-intent-promote.py` `--apply`）从写 JSON 文件改为写此表+清进程缓存。存量 3 条 `_APPROVED_EXACT_ROUTES` 作为首批 seed 数据进 migration。
3. **断供保命面**: `_t3_llm_parse` 返回 None 的 fail-closed 分支（`:4217-4229`）改为先查晋升表/计划缓存，命中照答；未命中才返回现有"稍后重试"澄清。
**允许改**: `backend/python/smartbi/gold/restaurant_intent.py`、`restaurant_intent_promotion.py`、`scripts/restaurant-intent-promote.py`、smartbi migration 目录、对应 tests。**禁改**: Java 一切、`chat.py`/`gold_reads.py`（只在必要的最小接线处动，动了在 PR 里单独说明）。
**验收**: 全量 pytest（含 `test_restaurant_intent*` 既有电池）过；新增测试：同句二问第二次零 LLM 调用（mock 断言）、缓存跨天日期重算、晋升命中回放过契约、pending/上下文时跳过零 token 层、planner 挂时晋升表兜底；capture 落库 tier 字段正确。`git diff origin/main...HEAD --stat` scope 干净。
**规则摘要**（out-of-harness 必读）: ①worktree 永远 off origin/main；②commit 用 `git commit -m "..." -- <files>` 锁 scope（防并发 session 文件混入）；③Python 服务统一 8083 单进程，禁新端口新进程；④**🔒 含 migration：只做到实现+自测+PR，不自 merge、绝不部署 prod**——部署统一回 main 由 Opus 走 `release-cretas.sh`。
**交接**: PR off origin/main + `git diff origin/main...HEAD --stat` 截图证 scope 干净 + 测试输出。

### 卡3 → Sonnet in-harness（Claude chat，Sonnet 5 + high）
**目标**: 菜品主数据层——解决跨店同菜不同名（客户运营岗自述最大工作量）。
**worktree**: `git fetch origin main && git worktree add -b feat/rai-dish-master ../cretas-rai-dish-master origin/main`
**改动**: ①两张表 `dish_master`（standard_dish_id, canonical_name, factory_id/scope）+ `dish_alias`（alias_name, store 维度, standard_dish_id, confirmed_by）——**⛔ migration 文件由 organizer 自写提供（🔒🔒 prod 迁移暂留 Opus），本卡按该表结构实现、禁改 migration 文件**；②语义辅助初匹配（复用仓库里原料相似名匹配 pattern，参考 `RawMaterialTypeRepository.findSimilarByNameAndCategory` 一族）产候选，落 pending 状态待人审确认接口；③resolver 查询链路加别名解析（alias→standard id），**行为兼容**：无映射时按原文名查询保持现状。
**允许改**: smartbi migration、`backend/python/smartbi/gold/` 下菜品解析相关、新增服务文件、tests。**禁改**: `restaurant_intent.py` 主文件（卡2 领地——若需在 spec 里引用 standard_dish_id，只加 TODO 注释，字段接入放 Wave 2）。
**验收**: migration 可重跑幂等；匹配单测（同名/相似名/无匹配三态）；既有餐饮测试零回归。🔒 migration：PR 止步，不部署。

### 卡4 → 贴给 GPT-Codex
**目标**: 入口收敛——同一餐饮问题任何入口同一答案，飞轮数据不再被多链路污染。
**背景**（自包含）: 餐饮语义规划（tiered-first）只在同步 execute 链生效；SSE 流式链没有它，且会直吐 1h 内语义缓存旧答案；两处前端直连 Python 绕过全部契约；demo 租户 F_DEMO 三不管。
**worktree**: `git fetch origin main && git worktree add -b feat/rai-entry-converge ../cretas-rai-entry origin/main`
**三项改动**（⛔ F_DEMO 治理已拆出归 organizer 本体自做——🔒🔒 权限/租户暂留 Opus，本卡不碰）:
1. SSE：`SseStreamingService.java`（`:154` executeStreamAsync 起）加餐饮 tiered-first 分支（对齐 `IntentExecutionOrchestrator.execute()` `:385-400` 的条件与 fail-closed 行为，通过已有 `sseTieredIntentDelegate` 复用 `:945` 的委托实现）；餐饮租户问题禁走语义缓存直返（`:262-299`）。
2. `web-admin/src/components/.../ChartInsights.vue:23,55` 从直连 `askRestaurantSynthesis` 改走统一 `executeIntent`（参考 `RestaurantChatPanel.vue:85` 的现行写法）。
3. `mobile-rest-ai/src/api.ts:243,299` 直连改走 Java 统一入口（保留 synthesis 直连仅用于纯图表数据拉取，问答类走 executeIntent）。
**允许改**: `SseStreamingService.java`、web-admin/mobile-rest-ai 上述文件、tests。**⛔ 禁改**: `IntentExecutionOrchestrator.java`、`TieredIntentDelegate.java`（卡1 领地）、`AIPublicDemoController.java`/`application.properties`（organizer 领地）——若 SSE 分支需要 orchestrator 侧配合，先在 PR 里提出，由主 chat 协调进卡1。
**验收**: SSE 通道对餐饮问题走 tiered 的集成证据（curl SSE 输出含语义规划回答而非 8 层旧链结果）；demo 前端问答与 web-admin 抽屉同问题同答案的对比证据；**UI 验收一律 headed Playwright**（headless 禁用，`--lang=zh-CN`，项目 playwright-headed-mode 规则）。
**规则摘要**: worktree off origin/main / commit 锁 scope / PR 止步不自 merge。

### 卡5 → 贴给 Composer 2.5
**目标**: 飞轮运营台 web-admin 模块（`/system/ai-flywheel`，平台管理员权限）——五个页面：总览看板 / 晋升审核工作台 / Miss 复盘 / 质量与回归 / 蒸馏数据集导出。页面明细见本 spec §P4 批（照抄布局要求）。
**worktree**: `git fetch origin main && git worktree add -b feat/rai-flywheel-console ../cretas-rai-console origin/main`（⛔ 需要 node_modules 时在 worktree 内 `npm install --prefer-offline --legacy-peer-deps`，**绝对禁止 mklink /J 共享主仓 node_modules**——Windows worktree 清理会掏空主仓）。
**API 未就绪前用 mock**，契约如下（卡5b 按同一契约实现）: `GET /api/smartbi/flywheel/overview?domain=&days=`（看板聚合）、`GET /candidates`（晋升候选列表）、`POST /candidates/approve|reject`、`GET /misses`、`GET /quality`（契约失败+反馈关联）、`POST /dataset/export`（JSONL）。所有列表带 `domain` 筛选参数。
**允许改**: web-admin 新增模块目录 + 路由注册 + menu 配置。**禁改**: 既有页面/组件/api 封装（新增文件为主）。
**验收**: `npm run build` 过；桌面 1440 + 移动 390 无横向溢出；**headed Playwright 截图**五页面（中文字体真渲染）；权限：非平台管理员不可见入口。
**规则摘要**: worktree off origin/main / commit 锁 scope / 不部署。

### 卡5b → Sonnet in-harness（Claude chat，Sonnet 5 + high）
**目标**: 运营台后端 API（FastAPI，挂 `/api/smartbi/flywheel/*`），实现卡5 的契约。
**要点**: 读三张表（capture=`smart_bi_llm_fallback_log` 的 agg_meta 聚合、`ai_promoted_routes`、user_feedback 列）+ 晋升写（复用 `restaurant_intent_promotion.py` 的 `aggregate_candidates/aggregate_misses/apply_promotions` 逻辑挂 API）。⚠️ **RLS 坑**: `smart_bi_llm_fallback_log` 带 FORCE RLS，查询前必须 `set_config('app.factory_id', ...)`，平台级聚合视图用管理员通道（晋升 CLI #1697 修过同款假空坑）。⚠️ 依赖卡2 的表——先按 §1.5 表定义开发，卡2 merge 后 rebase 联调。
**允许改**: `backend/python/smartbi/api/` 新增 flywheel 路由文件 + `main.py` 注册一行 + tests。**禁改**: `restaurant_intent.py`（卡2 领地）。
**验收**: pytest 新增用例（聚合正确性/RLS GUC 设置断言/晋升写入落表）；8083 单进程规则（不开新端口）。

### 主 chat（Opus 5 organizer，high）自留
1. **自做两件小 diff（🔒🔒 暂留 Opus）**: ①卡3 的 `dish_master`/`dish_alias` migration DDL（走 runner + GRANT smartbi_user，写完给卡3 分支）；②F_DEMO 治理——将 F_DEMO 纳入 `isRestaurantOwnerActionFactory`（`IntentExecutionOrchestrator.java:3443-3454`，与卡1 协调避免撞文件——建议卡1 merge 后再做）或映射 DEMO_REST（二选一并记录理由），同时把 F_DEMO 加进 `cretas.demo.factory-ids`（`application.properties:97`）。
2. Wave 1 全部 PR 的 diff 终审——**红线项本体逐行审，不外包 Sonnet**；例行卡（5/5b）可派 Sonnet 子代理审。
3. **fable 单点（预授权直通③）**: 卡2 migration、卡3 migration、F_DEMO 权限三个不可逆小 diff，各派一个 read-only `fable` 子代理终审。
4. 逐卡 merge 进 main → 从 main 统一部署（`release-cretas.sh`，Java+Python+web-admin 按卡涉及面）→ 部署后核对：晋升表命中日志、同句二问零 LLM、SSE tiered 生效、F_DEMO 写闸。
5. Wave 2 卡在 Wave 1 merge 后按 §9 顺序出卡。
