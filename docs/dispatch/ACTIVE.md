# Dispatch 台账 — ACTIVE

**单一写者**: Opus Organizer（本 chat）。Worker 不直接修改此文件。
**读法**: Organizer 每轮只读此文件，不读全历史对话 → 薄、可重启、零成本接手。
**防撞**: 派活前查 Scope 锁地图，重叠 → 串行 / 切 scope，绝不并发改同一文件。
**规范**: 详见 `.claude/rules/organizer-protocol.md` + `.claude/rules/multi-model-dispatch.md`。

> ⚙️ **Fleet 现状 (2026-06-07)**: **Codex/GPT 暂停**(GPT 10x 额度用尽) → **出 Claude 池只剩 Composer 2.5**。
> 路由临时调整: 改文件/UI/样式/lint → Composer(唯一出池); **跑终端/headed E2E/构建/TDD/查日志 → 回 Claude 20x 桶**(Sonnet subagent 或 Steve 开的 low/med Sonnet chat),**别硬塞 Composer**(它弱在 CLI/E2E/构建);判断/红线/终审 → Opus 自留。GPT 恢复后撤销此行。

> 📌 **基线 (2026-06-07 organizer intake)**: 三份交接线侦察 + 收尾。S1 采购到付款 / S2 六扇门报工 / S3 Phase2a coref 侦察结论 = 大部分已 shipped。本轮收尾 T101–T106 已全部完成(除 T103 需 Steve 真机)。详见 Done 区。
> ⚠️ effort×model 路由按 memory `project_2026_06_07_organizer_routing_refinements_pending` 执行(未落规则);不需要 high 的活输出给 Steve 自己拨(subagent effort 锁死)。

> 🔄 **SESSION 2→3 HANDOFF (2026-06-07 晚)** — 本 chat 极长(重启过1次),换新 chat `/organizer` 接手。新 chat 必做:
> 1. **检查在飞 background agent**(切 chat 可能丢, 同重启恢复套路): **T122-impl** branch `feat/fk-block-nav-guard`(有 PR→gate, 无→按 T122 设计重派)。用 `git -C <worktree> log origin/main..HEAD` + `gh pr list --repo Stevenjxie/cretas --state open` 查状态。
> 2. ✅ **已部署+验**: #558/#559(+守卫)/#560 后端 green:10020 v195646 4标记全在; #560 前端 web-admin 139:8086。**#559 守卫曾漏 push(本地 commit 没 push)→ cherry-pick #561 找回**(教训见 memory `feedback_gate_remote_pr_diff_not_local_worktree`)。剩 T120/T121 部署时再批量后端 deploy。⚠️ 部署后必 live 验(单测过≠live 好)+ **gate 验远端 PR diff 非本地 worktree**。
> 3. **ready 可直接派**: T120(dish coref 收尾, **实测与#559不同文件, 无需串行**)、T121(工序多人负责, 设计已定 Option B)。
> 4. **T123 规格/名称**: 设计完成(方案A 两级单位 level1_unit/level1_qty/level2_unit + 复用 gramsPerUnit + base_product_name + 名称 autocomplete 推荐), **5 个产品决策待 Steve 定**(见 T123 行)→ 定后派 impl。
> 5. 部署从 main / `cretas-organizer` worktree(组织 ledger 写者); 单一前门+出货闸。

---

> ✅ **六扇门 ERP-lite 全程 SHIPPED (2026-06-10)**: 12 子项后端(PR#627-642)+11 UI PR(#643-653) 全 merged + **prod 全栈部署**(后端蓝绿 green:10020 v20260610_105847, Flyway 22/22 / web-admin 8086 604assets / RN OTA c9f8c678) + E2E 三层验证(脚本造数 33/34 / headed web 断言 8P3W0F / 小米真机报工全链 PASS)。E2E 工具: `scripts/e2e/liushanmen-demo/`。**🟡 下一批(Steve GO, 待新 organizer chat 执行): 修复批 9+1 项** → 读 `docs/dispatch/2026-06-10-handoff-liushanmen-fixbatch.md`(自包含: BUG-2 报工不联动任务状态/批次DTO batchSourceType/调拨实收/撤回显姓名/报损页数据源/异常页核对/three-price入口/触摸目标/报损选择器/掌中宝semiCode demo配置)。周五客户 demo: 系统可演, 数据已造(run-20260610_124749)。
>
> 🆕 **(已完成, 留档) 六扇门工厂 ERP-lite 需求工程 (2026-06-09 intake)**: 两份会议转录(39min前场张权+116min全员含财务/采购/研发/老板)→ superpowers 三层审计(566抽取/94漏/**94.9%覆盖 0高0中级遗漏**)→ 103 模块 gap(✅35已建/🟡43部分/🔴25缺; 端 backend101/web84/RN29)+ 排期(P0半月≈66.5人天 / 总≈197人天)。文档双份: `docs/meetings/2026-06-09-liushanmen/`(requirements-catalog.md / 需求与现状分析.md / 排期-roadmap.md / 决策选项表.md / 2转录) + 同步根目录 `六扇门工厂数据、/`。**5 决策已 Steve 拍板(全 B)**: ①半成品=同单双产出(复用SemiFinishedInventory/WIP/sourceWipNo) ②不要建议价用毛利红线 ③编码小补(16位列P1,周五当面确认) ④财务仅导凭证表(H流P2偏后) ⑤超支用百分比阈值。**下一步**: P0 首子项(生产闭环C,客户半月上线瓶颈) spec → 分发卡。**RN 操作员屏(领料/报工/撤回/调拨接收) 走 ux-flow + fool-proof; 成本核算/权限/库存事务/Flyway 红线 → Opus 终审从 main 部署。** 详见 memory `project_2026_06_09_liushanmen_erp_requirements`。

## In-flight 任务表

> ✅ **CRITICAL 2026-06-09 RN闪退 已修(#618, OTA推送中)** — 根因: **T163(#616)把照片标注3个useCallback(setPhotoLabel/setPhotoNote/buildPhotoAnnotations)放在早返回(loading/done)之后** → loading渲染少3hook,数据回来多3hook → "Rendered more hooks" 进报工屏即崩白屏. 设备错误栈+诊断agent双确认. 修=移到顶部hook块(早返回前). tsc净, #618 merged, OTA推送中. 教训: **RN加hook必在所有早返回之前**(rules of hooks). ⚠️gh pr create需--head(误删分支已重推救回).
> **#617 T164 polish 待rebase**: 基于崩溃旧base+也改YieldStepReportScreen(F3) → 需rebase到#618后main再合(否则带崩溃). #615 masking过宽(单价采购价也遮)=backlog精修.

> **2026-06-08 晚追加** — T159 全 shipped(止血+B-anchor+B-codegen+A-form 全 prod+验). #547/#545 续做评估: **#545 CLOSED**(已shipped零丢失). **#547 邓总QA计划=大多PARTIAL**(代码shipped+单测过但端到端链从未走通; **Ch02 chef需求→采购计划聚合可能是真feature gap**; 建议gap章节headed跑~6-8h, 待Steve定scope+Ch02决策, 未启动大跑). T161 RN/web反馈批(投资中).

| T547-QA | 邓总餐饮 E2E QA 链执行(#547计划, depth-first, headed Playwright) | Opus编排+Sonnet执行 | - | 按章派agent | detached `../cretas-deng-e2e` | prod qhj RES_3101_009 | ✅ DONE (Ch00-07全跑完) | - | CHAIN_ID **DENG-QHJ-20260608-2030**. 策略(Steve定): **记GAP+workaround继续跑先摸全**, 不中途修. **Ch00+01 DONE**(6deep/1medium headed 17截图). **Ch01发现**: 🔴HIGH BUG-01-RBAC(sales_mgr API读财务原始数据→修中) + GAP-01-A(CSV分类UNKNOWN+Python401 ETL不跑数据流不到分析) + GAP-01-C(供应商上传不建草稿Ch01→03桥断) + chef账号qhj_chef_cold/hot缺. **Ch02在飞**(厨房需求→采购计划, 重点探需求聚合是否存在=疑似GAP-02). 后续Ch03-07. 全跑完一次informed修gap |
| BUG-01-RBAC | 🔒SmartBI上传数据API财务RBAC守卫(Ch01 QA发现) | Sonnet | (锁死) | subagent bg | feat #608 (HELD) | SmartBI controller | 🟠 PR HELD-需改 | #608 | repro确认+同类扫到2洞(/uploads/{id}/data+/fields). **但#608一刀切finance:read_write过度拦截**: SmartBI盘全是analytics module权限, sales_mgr(analytics:read)能开UploadStatusDashboard/RestaurantV2/WhatIf, 这些盘调该端点→会拦掉他们合法的POS数据预览=回归. **正确修=amount-masking**(非财务角色脱敏金额列, 保留访问). **Opus终审HOLD**, 进QA跑完informed批量修(demo租户低风险). PR留作repro/sweep记录 |
| T163-照片标注 | 报工每张照片标注(预设chips+自由文字) RN+后端存+web显示 | 待派 | - | - | (排T162后) | RN照片上传+evidence DTO+web相册 | ⬜ queued(T162后) | - | Steve+6.1-6.3真实流程: 每张称重/装盒照都对应"这是什么"信息. 每照片: 预设chip(称重投入/称重产出/装盒[车·盒数]/副产物/托盘重/工序中/留样/其它, 对齐真实手写标签)+自由文字(Steve定both). **真实模式确认**(读6.1群内报工): 工人现在就在照片上手写大字(猪蹄第1车320盒/猪舌头第二次滚揉/托盘24.5)+文字算式(294.5+245.5=540Kg). 标注=数字化这个. 照片保留拍摄顺序. 存储 URL→{url,label,note}(后端DTO改+往返4处). web证据相册(T161-web)显示照片+标注. **与T162改同RN照片文件→T162合并后再派**(防撞) |
| T161-RN2 | RN领料picker单源+确定按钮+重选入口+A1防御+B4默认收起 | Sonnet+Opus | (锁死) | subagent+Opus | merged main | MaterialBatchPicker/YieldQuantityInput/YieldStepReportScreen/ProfileScreen | ✅ DONE | #609 | Q1单源/Q2确定按钮+反馈/Q3修改入口/A1 getUserRole/+Opus B4默认收起+向下展开. **OTA已推+验**(rv1.0.1/ts1780925089, manifest cd40b16f createdAt13:23:09Z, Opus独立curl确认). 设备冷启2次拉. A1设备侧待Steve验(连点看updateId/退出重登莫云) |
| T162-报工重设计 | RN报工屏低输入重设计+隐藏成本+下一任务导航 | Sonnet | (锁死) | subagent bg | merged main | YieldStepReportScreen | ✅ DONE | #614 | 三冗余输入真删(Opus亲核diff)/损耗自动/时间拖拽+人数步进/operator隐藏成本/下一任务导航. **OTA已推+验**(manifest 2e48a254 createdAt19:29:42Z). 设备冷启2次拉 |
| Wave1.1-RBAC | 财务列finance门控(sales看采购价不看财务P&L)+gold营收对owner放行 | Sonnet | (锁死) | subagent bg | merged main | PriceFieldResponseAdvice+Python gold | 🟢 部署中 | #615 | **v2修对**(gate改finance:read_write; agent自核prod sales=r→脱敏/finance_mgr rw→可见/owner matrix rw→可见, 40/40测试). (B)Python gold加owner营收. MERGED. **部署中**(Java+Python). 待live验 sales财务脱敏+owner营收 |
| T163-照片标注 | 报工照片逐张标注(chips+自由文字) RN+后端往返+web | Sonnet+Opus | (锁死) | subagent bg | merged main | YieldStepReportScreen+evidence DTO+web相册 | 🟢 RN-OTA已推/后端web部署中 | #616 | 每照片chip(9种)+自由文字, photo_annotations JSONB. Opus gate抓Flyway撞号(→V20261001_02)+验DTO往返4点. **RN OTA已推+验**(manifest 08a8a231 createdAt20:14Z). 后端(Flyway V20261001_02)+web 随#615一起部署中. ⚠️worktree `..cretas-photo-annot` 待清 |
| T164-报工UX polish | 报工流程低输入polish(F1大数字键盘/F2免打字登录/F3错误网络恢复/F4F6核) | Sonnet | (锁死) | subagent bg | feat/report-ux-polish | YieldStepReportScreen/YieldQuantityInput/EnhancedLoginScreen/MaterialBatchPicker | 🟡 in-flight | - | ux-flow门控分析(doc 2026-06-09-report-flow-ux-analysis)出残留摩擦. Steve定先核F3后修F1/F2/F3. F3错误恢复先investigate现状再修(双句错误+保数据+重试). 停PR→Opus亲审 |
| T161-RN | RN操作员UX修复(7项) | Sonnet | (锁死) | subagent bg | merged main | ProfileScreen/NeoButton/OperatorNavigator/MaterialBatchPicker/YieldQuantityInput/YieldStepReportScreen | ✅ DONE | #607 | 7项全做. A1隐藏dev项/A2图标重叠/C1底栏safe-area/B1领料必填/B2下拉滚动/B4按托称重改词+首道默认展开/B5相册多选. **OTA已推+验**(rv1.0.1/production/ts1780922713302, manifest e1f2a156 createdAt12:45:44Z, Opus独立curl确认). 设备冷启2次拉 |
| T161-web | web批次详情报工证据可见性(3项) | Sonnet | (锁死) | subagent bg | merged main | production/batches/detail.vue | ✅ DONE | #606 | D2-1附件卡澄清/D2-2证据数量徽章/D2-3报工证据平铺相册卡(工序名+阶段标签). **已部署 web-admin 139:8086 HTTP200**. 验:批次1924看相册卡 |

| ID | 任务 | model | effort | orchestration | 分支 | scope 锁 | 状态 | PR | 阻塞 |
|---|---|---|---|---|---|---|---|---|---|
| 🚚待部署批 | 攒一次后端 deploy: #669/#673/#676/#678/#679/#680 (含 Flyway V20261013_01/V20261014_01/V20261014_03, 号已理顺无撞) | Opus 出货闸 | - | - | main | - | ⬜ 待部署 | - | SWEEP-D 回来一起部 --env all + test env 全链验证(cashier付款链/空价SO/BOM写). R8 后端已单独部署过(v20260610_202542) |
| SWEEP-D | ✅ 批D C/F流扫荡 DONE: 57项→V1, 0新bug, 5处矩阵勘误. docs push 7f48bab4a 自查PASS. **四批扫荡全完**(A 20/B 20/C 29/D 57). 剩: C-023/024/050/051(打印E2E,同#674待部署后验)/C-058(cost variance config 0条待客户或默认)/C-033+F-032(FIXB#2/#3 DTO透传未完成)/F-049~052(WHIOStatisticsScreen前端hardcoded mock后端已建未对接)/F-055/056(SALTED仓出量记录+报告缺) | Sonnet+Opus gate | (锁死) | subagent bg | merged main | (释放) | ✅ DONE | - | audit: c-f-flow-batch-d |
| A-24-FIX | 🟡 productCode 不可变守卫(update路径拒绝改code) | Sonnet | (锁死) | subagent bg | fix/a24-productcode-immutable | ProductTypeServiceImpl | 🟡 in-flight | 停在PR | 谨慎不误伤系统内部 regeneration(line126); X-3 已 organizer 确认=硬编码approve非WorkflowEngine(同X-4, 非bug) |
| FABLE-AUDIT | ✅ 生产流程审计 A/B/C 完成(fan-out), **D 弃掉** | Sonnet×3(A/B/C) | (锁死) | 3 subagent 并行 | 只读 | (释放) | ✅ A/B/C DONE, D dropped | 返organizer | A/B/C 🔴 CRITICAL: 财审闸门缺失(createProductionPlan不查FINANCE_APPROVED)/T-3锁单测全走fail-open(mock掉真实路径)/四仓Guard形同虚设(F006无RAW/WIP/FG/SALTED仓)/缺口转计划缺失/RN进销存F-049已修待OTA(非没做). **D post-mortem**: live E2E真跑全链, **0产出**(stall于"改SSH访问方式"阶段, 0-byte输出52min被看门狗杀). 根因=单agent串行跑12步live(curl+psql over SSH)长时间无streamed输出→600s看门狗杀(同第1版单Fable stall同根因). **教训: sprawling live E2E别用单长agent, 要么拆小focused live检查, 要么用Maestro/测试harness**. D覆盖已由 batch1924先前prod E2E(6-3)+worker B代码级发现+BIG-AUDIT-WF SP1/2/3 替代, 故冗余可弃; 大审计若标某步从未E2E验→再做针对性小live检查 |
| PROD-MODEL-CHECK | 🔴 工序模型需求真相核查(Steve点出报工模型可能不匹配): 六扇门要"领料+产出两点报,中间工序留着免报",现状被迫逐道. prior-plan有mode_1/2/3但≠覆盖范围维度. 核现有config能否拼两点头尾 vs 必须补per-工序reportingRequired | Opus自做(判断密集) | - | 待BIG-AUDIT-WF | - | - | ⬜ 等大审计(改gate, 非D) | - | memory: project_2026_06_10_liushanmen_configurable_report_granularity. 范围歧义(2b第5行"中间省掉"在成本上下文)Steve已确认报工也要省. 生产侧P0. **routing改: 报工模型=判断密集→Opus自做不派Sonnet** |
| PROD-WAVE-INTEL | 🔴 生产侧实现波 intel(确认待做, 等BIG-AUDIT-WF全图后一次性informed实现, 不piecemeal): ①**财审闸门**(已核实origin/main: validateAndEnrichSalesOrderSource 只查工厂归属不查 so.status==FINANCE_APPROVED; 两路径[兼容sourceOrderId + sourceOrderItemId]都漏; createBatchFromPlan/startProduction 也不回溯. 转录C-1 line284"财审完流张权按订单排产"=existing_feature流程, **闸点决策**: createPlan硬拦 vs 转批次/开工拦, 待全图定) ②报工模型(PROD-MODEL-CHECK) ③T-3其余路径接线 ④四仓Guard(F006建RAW/WIP/FG/SALTED仓) ⑤缺口转计划草稿 | Opus自做红线+Sonnet机械 | - | 待审计后整波 | 生产/仓储 service | ⬜ 等大审计 | - | **反scattershot: 全生产侧territory, 等BIG-AUDIT-WF informed优先级+全图再按长远规划波次一次派, 红线Opus inline/subagent, Sonnet只接机械** |
| BIG-AUDIT-DONE | ✅ 12 SP 五维大审计完成(wf_3a236eda-0e8, 13agent/1.95M token). 交付: docs/plans/2026-06-11-liushanmen-{5dim-audit,todolist,matrix-5dim}.md. 热图136功能点: MISSING24%/建未测16%/建+测33%/+UIUX20%(零headed)/+前端4%/NA2%. C流就绪~60%. 15 CRITICAL/HIGH + Tier0 16项(13-18人天) + 5决策 | Opus编排+Fable综合 | - | workflow | docs/plans 新文件 | (释放) | ✅ DONE | - | **🔒organizer gate交叉验(铁律:审计=自证据要交叉)**: #07出纳DTO=**STALE误报**(我#678已加PaymentRequestApprovedDTO 6明细字段+/approved返它, 审计读pre-#678旧码)→剔除; #03 assertCanReceive零调用=**真孤岛**; #02 WHInventoryCheck line140-141直改库存=**真**; #01旧cancel端点(line323)与request-cancel并存=**真**; #10 BUG-R1 ledger#662/#657疑似已修待确认. **教训: BIG-AUDIT workers读origin/main快照可能漏本session合并(#678/#680/#683等), Tier0需逐项对账current main剔已修** |
| DECISIONS-5 | 5决策按 goal(长远规划优先): **①毛利红线**=200+warn非409硬拦(SP3 spec=非阻断+防呆Rule1: sales必须提交前看预警→放开sales_rep调check-margin; 现409+排除sales=最差) **②SP8编码**=minimal兜底先上(6.9五决策#3已定"编码小补16位列P1周五确认"=长远规划明确) **③报损双实体**=WastageReport为准(SP7已建双轨审批#667/#668验过), DisposalRecord废弃 **⑤标签前缀**=同源复用SP8 primaryCode(避双编码). **④半成品重量库存vs流水账=需Friday客户确认**(C-048客户说"只做重量库存"vs现SemiFinishedInventoryTransaction流水账; 流水账powers移动均价/撤回回放不能删→保留内部+暴露重量-only视图, Friday定) | Opus决策 | - | inline | - | - | 🟡 4决/1待Friday | - | 决策依据落 5dim-audit doc |
| DEPLOY-TEST | ✅ 待部署批 8PR(含#683) test 全链验证 **PASS = prod-ready** | Opus出货闸 | - | bg deploy(v20260611_含683) | (释放) | - | ✅ test验过 | - | **test实证**: 空价SO 200/DRAFT/totalAmount=0(NPE修验)✓ / cashier登录+/approved出纳视图 200(D9 G1)✓ / BOM getRecipe 200(#680 revert读路径不LazyInit)✓ / Flyway 3迁移applied+settlement_type列✓. 8PR=#669/673/676/678/679/680/682/683 |
| WAVE2 | ✅ 审计 Tier0 第2批 4 PR 全 merged: #687 报损枚举 / #688 财审闸门 / #689 DTO drop / **#690 报工模型(核心)** | Opus×2+Sonnet×2 | - | bg subagent | merged main | (释放) | ✅ 全栈上线prod | #687/688/689/690 | 全 organizer gate过. **#690 报工模型**: reportingRequired放ProductWorkProcess, 不改YieldCalculationService(免报工序不spawn→出成率天然两点算), DEFAULT TRUE回填=逐道报不变. **已上线+验**: 后端v20260611_025141 + 迁移V20261015_01 applied + **reporting_required 21行全true=向后兼容live确认现有F006逐道不变** + web-admin 200 + RN OTA ts=1781118014871. **FABLE-WAVE-AUDIT 启动**(8 PR vs转录看有没有弯曲, 重点#690报工模型是否真匹配"两点报中间免报") |
| FABLE-WAVE-AUDIT | ✅ Fable 对抗审计 Wave1+2 8PR: **7/7 真实现零弯曲**(含prod只读DB验#690回填63行全true). 守卫位置/放行集/fail-closed/向后兼容全独立核对对得上转录 | **Fable** | model轴 | 单subagent read-only | 无 | (释放) | ✅ DONE | - | **3 follow-up**: ①🔴**#690 F006配置未翻**(能力上线但prod F006 16工序全true=六扇门此刻仍逐道, 要按张权工序清单标中间免报才真两点报=**launch-critical, 需Steve/客户确认哪些工序算"中间"**, 我不猜) ②#686残留(WHExpireHandle报损假成功:updateBatch({status})被DTO静默丢弃却弹成功=lying UI同#689款; PUT receiptQuantity/DELETE未消耗批次仓管可改=零自主权API层未闭→Wave3) ③#685导出无成品+无类别筛选(catalog 621-622行业标准必备→backlog) |
| F006-CONFIG-CONFIRM | 🔴 launch-critical: 报工模型能力已上线但F006需配置中间工序免报才真两点报. 哪些工序算"中间"需张权确认(各产品工序结构不同, 猪舌/牛腱/掌中宝; 油炸曾被跟踪) | Steve/客户 | - | Friday/确认 | - | - | ⬜ 待确认 | - | 我不猜(猜错=客户实测报错点). 确认后organizer用新UI/API把F006各产品中间道标免报 |
| WAVE3 | ✅ Tier0 第3批 5 PR 全 merged: #691 Python打印路由(production-work-order+领料单, Java期望缺→502; 12测试) / #692 WHExpire报损假成功→改导航WastageReport真报损流(Fable发现lying UI) / #693 毛利红线409→marginWarnings(200)+checkMargin权限sales:read_write录单可预览(脱敏保留无¥) / #694 退货财审门(APPROVED→FINANCE_APPROVED→COMPLETED, finance-approve端点, 不backfill, V20261016_01) / #695 脱敏SP9/SP10(cost绝对值@PriceSensitive, 比率%放行, 复用门, 真受众=仓管/质检/operator非sales_mgr, PriceSensitiveIT) | Opus×3+Sonnet×2 | - | bg subagent | merged main | (释放) | ✅ 全栈上线prod | #691-695 | 全organizer终审过. **已上线+验**: Java v20260611_034633+迁移V20261016_01 applied(return_orders加finance_approved_by/at) / Python打印路由 401-not-404(502根治) / RN OTA #692 live. **#693 follow-up**: 前端调check-margin提交前预览+显示marginWarnings(后端已开权限, 前端wiring待Wave4). **节奏**: Wave3+4 Fable审计批量做(Tier0收尾时, 省2x). 启动Wave4 |
| WAVE1 | ✅ 审计 Tier0 #1优先级"封堵绕过+接线守卫" 3 Opus红线subagent 全 merged | Opus×3 subagent | - | bg subagent | merged main | (释放) | 🟢 merged, --env all部署在飞 | #684/685/686 | **全 Opus 终审过(新协议红线自做)**: #684 旧cancel封堵(审计#01大体夸大但揪出PENDING_APPROVAL窄真缝可被旧端点/AIChat绕在飞审批→守卫;8测试) / #685 进销存导出货不对板(审计准确; exportLedger错调凭证序时账→新exportInventoryLedger同源台账+xlsx脱敏双门fail-closed与advice full-access一致;10测试) / #686 仓库零自主权(#02 RN直改库存→重接盘点发起+StocktakeEntry原孤儿屏接launcher; #03 assertCanReceive接2条RAW入库路径422 tx-safe; **核实现有switch对LOGISTICS/WORKSHOP/null全default放行不误拦F006**;7+118测试). **RN(#686 WHInventoryCheck)待OTA推**(设备插着) |
| DEPLOY-PROD-11 | ✅ 11PR(批8+wave1 3) 全栈上线 prod | Opus出货闸 | - | bg deploy | (释放) | - | ✅ DONE | - | **后端 v20260611_015129** prod blue:10010+test:10011 双健康, jar标记全(PENDING_APPROVAL守卫/exportInventoryLedger×2/assertCanReceive). **#685导出 live验**:200+真xlsx台账4317B(PK). **#686 F006安全**:仅LOGISTICS+WORKSHOP legacy仓→守卫default放行不误拦. **web-admin prod** 139:8086 HTTP200(backup .bak.20260611_020330). **RN OTA** rv1.0.1 ts=1781114585503(含#686 WHInventoryCheck). 设备验WHInventoryCheck走盘点=Steve顺手/下轮 |
| E2-NPE-FIX | ✅ 🔒 空价SO BigDecimal.add(null) NPE 修复 — **FIX-E2(#679)遗漏的真实路径** | **Opus inline**(新协议:红线自做) | - | inline+worktree | merged main | (释放) | 🟢 merged 待重部署验 | #683 | **强实证支撑协议修订**: FIX-E2(Sonnet)只关校验规则+提审gate, 漏 createSalesOrder:470/updateSalesOrder:1063 totalAmount.add(null) NPE; **且测试E2-01 catch(Exception)吞NPE假通过(注释明知会NPE)**; 我上次终审也漏. prod test实测500(444C443D)才暴. 修=两站点null→ZERO+E2-01真断言, 8测试绿. **按新协议Opus inline自做没再派Sonnet** |
| BIG-AUDIT-WF | 🔍 Workflow 大审计 wf_3a236eda-0e8(Steve批准Fable综合): 12 SP 完整规划 vs 实现/测试/UIUX/前端 **五维成熟度**分类 + 转录漏项 → 规划文件+五维矩阵+todolist | Sonnet×12 fan-out + Fable综合 | model轴(synth) | workflow bg | 写 docs/plans/2026-06-11-* 新文件 | docs/plans/ 新文件(不冲突) | 🟡 in-flight | 返organizer+写文件 | 五维=漏/建未测/建+测/+UIUX/+前端跑通. 输入: SP1-12 spec+plan / origin/main代码+测试 / audit docs / 2份转录 / 需求目录. Fable综合去重+判断+假完整戳穿. 产出 docs/plans/2026-06-11-liushanmen-{5dim-audit,todolist,matrix-5dim}.md |
| 周五确认单 | 客户当面确认: G5付款销售方向语义 / PMC+配料员角色(批B缺) / 16位编码SP8 / 财务接API SP11 scope | - | - | - | - | - | ⬜ 周五会议 | - | 都是需判断/客户拍板项, 不猜着建 |
| 押后-T3+仓储 | T-3锁其余路径接线(领料/计划/库存调整/品控)+RN补录UI / WHIOStatistics mock→真实对接(F-049~052) / SALTED出量报告(F-055/056) / C-058 cost variance默认阈值 | Sonnet | (锁死) | - | - | 生产/仓储 service+RN | ⬜ 押后 | 等FABLE-AUDIT落地 | **故意押后**: 全在生产/仓储territory, FABLE-AUDIT 正审这片→等它出informed优先级再一次性派, 避免scattershot+撞车. RN补录UI另需真机(Steve已拔小米) |
| VMX | ✅ 六扇门456条追溯矩阵+波次计划 DONE: matrix-{1,2,3}.md + 主文档 verification-matrix.md | Sonnet×3+Opus合并 | (锁死) | 3 subagent 并行 | docs only | docs/meetings/2026-06-09-liushanmen/verification-matrix*.md | ✅ DONE | - | 合并统计: 实现✅65%/🟡17%/🔴6%; 验证V1仅~32%/V2~30%/其余V0或B. ⚠️分片2 v1因stale工作树全错已重做(取证必须git grep origin/main). 分片3=模块簇颗粒度. 波次W0基建/W1缺陷/W2实现/W3真数据/W4 P1 见主文档 |
| W0+W1 | ✅ 验证波次 W0基建+W1批1 全 SHIPPED (2026-06-10 晚) | Sonnet×4+Opus gate | (锁死) | 4 subagent 并行 | merged main, worktree已清 | (释放) | 🟢 部署中(后端蓝绿+OTA ts1781081697491) | #658-661 | **#661 E-5根因=GrossMarginRedlineService从未注入SalesServiceImpl**(F3 gap只接了PricingEngine警告日志)→修: 低于红线409+产品名+hint/成本缺失WARN放行/54测试绿, gate核实Impl是@Service必注入. **#660 B-47混合加权精确值9+11+18测试绿(100@10+50@16→12.0000), C-058阈值实际在LaborEfficiencyServiceImpl(75/150)非CostVariance(±10)**. #658 RN进销存去mock三态齐全+web侧F-053核对已对接无需改. #659 seed断言落盘steps[]+summary+audit模板+盘点约束调研(硬编码29→建议@Value, test env=1, ~20min) |
| W2批1 | ✅ 5项全 merged(#662-666): T-3时效锁(validator+报工/入库双路径,15测试; ⚠️RN未送businessDate→报工侧守卫latent待RN补录UI) / 盘点@Value(prod默认29不变,test=1) / D-6责任绑定(owner+403+通知+web列,V20261012_11) / X-6缺口=yield_operator枚举缺失致角色静默失效(已修,28测试) / BUG-R1修复(producedQuantity 2行)+R2证伪(G1守卫覆盖现存全部OUT类型,原@Disabled测试是恒绿assertTrue) | Sonnet×5+Opus gate | (锁死) | 5 subagent 并行 | merged main, worktree已清 | (释放) | 🟢 部署中(backend --env all + web prod已完) | #662-666 | gate要点: G1只覆盖SECONDARY_CONSUME/TRANSFER_OUT—未来新增OUT sourceType必须同步G1(caveat已记PR); #665纠正#656的决策枚举错字面量(实际ACCEPT_SHORT/REQUEST_RESUPPLY); 部署后跑test env盘点链验证(curl序列在#663 body) |
| W2-ROLEFIX | ✅ 🔒盘点+报损审批死角色码修复(#667)+hotfix(#668: #667的嵌套枚举HQL启动期炸→派生方法名+@DataJpaTest回归网) | Opus root-cause+Sonnet impl+Opus hotfix | (锁死) | 同worktree接力 | merged main, worktree已清 | (释放) | ✅ 部署 v20260610_190408 双环境健康 | #667+#668 | **盘点全链 F-026/027/028 V1 闭环**(test env: 发起→录入→差异→审批200→APPLIED→DB 260→255). 事故记录: #667 HQL 启动炸被 prod 蓝绿健康闸拦住(prod零影响), test 用备份jar恢复; Mockito mock repo 测不到启动期HQL=CI漏报家族→@DataJpaTest堵上. ⚠️待Steve确认: 报损FACTORY轨"厂长"=production_manager+超管. audit: docs/audits/liushanmen/2026-06-10-stocktake-chain-test-env.md |
| W2-EXT | **organizer 接棒新 chat**(本chat退役): 读 docs/dispatch/2026-06-10-handoff-liushanmen-verification-campaign.md — 含V0扫荡+R8设计两主线子brief+队列+防踩坑 | 新chat /organizer | - | Steve courier | - | - | ⬜ prompt 已给 Steve | - | 单一organizer: 新chat接手后本chat不再派活 |
| FIXB | ✅ **六扇门修复批 9+1 项全 SHIPPED+真机验 (2026-06-10)** | Sonnet×3+Opus gate | - | 3 subagent 并行 worktree | merged main, worktree/分支已清 | (全释放) | ✅ DONE | #655/656/657 | 后端green:10020 v20260610_145535(jar 5标记verified)+web 139:8086+RN OTA ts1781074853285(manifest verified). 🔒gate抓2真问题: #657撤回不复位task卡COMPLETED(Opus亲修executeReversal复位)+#656守卫放错文件(Opus补router静态redirect). 组4: WP-F006-ZZB-03油炸→SF-ZZB-YZ. **真机f79c50d6三证**: moyun列表5→4道/weizj列表66881推进到第3道·油炸/完工出成屏「剩余转半成品」栏+SF-ZZB-YZ自动. BUG-2 API live: task336 OUTPUT→即刻COMPLETED+completed_by=1616. task335已retro-fix. 备注: 异常页显裸ID(后端无denormalized name)=backlog; 337油炸留OUTPUT未报(周五demo可现场演双产出) |
| COMP-F | 参赛PPT: 川卤源工厂演示租户+3张RN截图(替六扇门F006泄露图) | Sonnet | (锁死) | 单subagent bg | 主目录 competition/ | prod `F_CLY_DEMO` + `factory-*.png` | ✅ DONE | - | 🔒租户`F_CLY_DEMO`(cly_admin/cly_op1) 3产品6工序 批次1966/1967. COMP-F2修factory-03: 卤牛腱批次1967解冻清洗报完(投入250→产出240, 出成率96%, 工时¥1000)→真出成率屏. Opus逐张核6图全干净, 嵌deck slide4. ⚠️租户留prod复用 |
| COMP-R | 参赛PPT: 蜀三味餐饮演示租户+3张web截图(替qhj泄露图) | Sonnet | (锁死) | 单subagent bg | 主目录 competition/ | prod `R_SSW_DEMO` + `restaurant-*.png` | ✅ DONE | - | 🔒租户`R_SSW_DEMO`(ssw_sales_mgr/ssw_admin) gold 5门店×30天(总营收185.6万非qhj7877万). 驾驶舱+AI排行柱图+对话3图. Opus核无qhj泄露. ⚠️租户留prod复用 |
| CI-U1 | 全图表自动洞察 Phase1 U1: Java `_meta`语义下发(B1根治, 前端Tier1地基) | Sonnet | (锁死) | 单subagent bg | feat/chart-insight-meta | DynamicChartConfigBuilder+DTO+SmartBIUploadFlowServiceImpl | 🟠 gated待merge/部署 | - | 🔒**Opus硬审✅**(2轮): 能力(fieldMappings→ChartMeta 7测试) + **gate抓接线缺口**(原agent只建方法没切生产路径→meta全null)→U1-wire修(1075/1125两call site切buildConfigWithMeta/buildConfigWithFieldsAndMeta, resolveBusinessType fail-soft, 18测试). 亲验无裸调用残留 |
| CI-U3 | 全图表自动洞察 Phase1 U3: 迁移 ai_insight_templates表 | Sonnet | (锁死) | 单subagent bg | feat/chart-insight-migration | smartbi migrations | 🟠 gated待merge/部署 | - | 🔒**Opus亲读SQL终审✅**: V20260927_01>V20260926_01(防撞号); RLS+FORCE+tenant_isolation(app.factory_id GUC, DB层多租隔离=B3加固); GRANT smartbi_user(grant-gap修); CHECK/UNIQUE/幂等/纯增量. 部署时我apply test→prod |
| CI-U2 | Phase1 U2: 前端chartInsight.ts(TREND+RANKING)+组件, 替换getChartMiniInsight | Sonnet | (锁死) | 单subagent bg | feat/chart-insight-fe | chartInsight.ts/ChartInsight.vue/SmartBIAnalysis.vue | 🟠 gated待merge/部署 | - | 🔒**Opus硬审✅**: RBAC-safe by construction(TREND/RANKING只比率%/标签零绝对¥+脱敏兜底); 建议全观察动词无因果词; 诚实null; getChartMiniInsight替换无回归; 23+15测试过vue-tsc净. 小瑕疵showAbsolute dead-code(Phase2清). 待merge+部署 |
| CI-U4 | Phase1 U4: Python ChartInsightService+端点(签名/库查/LLM结构化/蒸馏/RBAC) | Sonnet | (锁死) | 单subagent bg | feat/chart-insight-be PR#654 | chart_insight_service.py/api/chart_insight.py | 🟠 gated待merge/部署 | #654 | 🔒**Opus硬审✅**(2轮): 服务+RBAC逻辑(JWTAuthMiddleware真+cross-tenant范式+401 fail-closed+跨租户403+finance门控null+31测试) + U4-fix接线(路由移顶层main.py:1046 `/api/smartbi/chart-insight`受JWT保护亲验; 删重复迁移用U3那份). 待U3一起merge+部署 |
| CI-U5 | Phase1 U5: 集成+部署(✅) + demo前端Tier2接线/现场闭环(剩) | Opus编排 | - | - | integrate/ci-phase1→main 2c06122c2 | demo看板+slide | 🟠 部署完待demo接线 | - | 🔒**Opus亲做**: 4分支干净merge无冲突→push main(c3b908cfb..2c06122c2)→**三服务prod部署+验**(Python迁移V20260927_01 applied smartbi_prod_db+RLS; Java blue10010 _meta; web-admin 139:8086). 端点8083 401保护✓表✓. **剩**: ①前端Tier2-fetch接线(Tier1 null时调端点+AI生成/已学习徽章, U2只做了Tier1) ②阈值=1或预热demo模板 ③现场闭环验 ④成本曲线slide |
| T103 | S1 🖐️真机走一单 录音→`voiceAudioUrl` OSS 验证 | Steve(手动) | - | - | - | - | ⬜ pending | - | ✅已解锁(真实餐饮角色 qhj_chef/qhj_purchase_mgr/qhj_owner 已建+验登录);需真机(APK 已装小米 f79c50d6) |
| T159 | 🔒原料字典防呆 + UoM单位跨层锚定 | Opus框→派 | - | - | (多波) | RawMaterialType表单 + BOM/入库单位锚定 | 🟡 in-progress(B-anchor在飞) | - | **架构定**(Steve拍): 写入即拦 hard-block。计划 `docs/dispatch/2026-06-08-T159-plan.md`。原则: 主数据unit=canonical, BOM/入库单位必须同维度(复用 MaterialUomConverter tri-state 单一事实源)。侦察 T159-INV done(根因 R3 BOM自由填零校验/R4入库后端不锁/R1 code null/R7 suggest无manually-edited守卫)。**主数据unit audit done(6原料): 猪大肠 箱→kg 已修prod(0批次0BOM零风险, Steve GO)**, 其余5个口径正确, hard-block上线不误拦。worktree cretas-t159-inv 设计参考(完后清) |
| T159-B-anchor | Goal B 后端单位锚定(helper+BOM拦+入库拦+报错真实名) | Sonnet | (锁死) | subagent bg TDD | merged main | MaterialUomConverter/BomRecipeServiceImpl/MaterialBatch create/4断点 | ✅ DONE(功能E2E待) | #603 | 🔒 第一波治本. Opus终审过(fail-open保守方向)+独立mvnw验38守卫测试0失败. **已部署prod green 10020 v20260608_194133**(jar MD5 4cb7341a). **Opus jar live-verify PASS**: 运行中jar含 isWriteUnitCompatible+checkBomUnitCompatible+checkInboundUnitCompatible 各1, green UP. 功能E2E(跨维度写→409)待Steve UI验或给F006账号API打. B3 transfer报错真实名=低优先follow-up. 无Flyway |
| T159-B-codegen | Goal B 第二波: 原料编码自动生成(R1)+suggest多字段端点(R7) | Sonnet | (锁死) | subagent bg TDD | merged main | RawMaterialTypeController/Service | ✅ DONE | #604 | 编码规则 原料→YL/肉类→RL/包材→BC/其他→WL +3位序号. preview-code(只读)+suggest(多字段null禁假)+create自动生成collision retry. 18测试. **已部署prod blue 10010 v20260608_200934**(jar MD5 31e9beaee). **Opus jar live-verify PASS**: getMaterialCategoryPrefix+suggestFields+preview-code mapping 在运行jar, preview-code路由401(已部署). 无Flyway |
| T159-A-form | Goal A 前端表单防呆复刻SKU | Sonnet | (锁死) | subagent bg | merged main | web-admin material-types/list.vue + materials/list.vue | ✅ DONE | #605 | build+vue-tsc干净2文件. cascadeWriting守卫+5 manuallyEdited标记/编码预览/智能匹配cascade/包装内联换算行/批次列表单位改quantityUnit(foldable#1). **已部署 web-admin 139:8086 HTTP200**. 功能UI验=Steve(建原料→YL00x预览+智能填充+批次单位对) |
| — worktree收尾 | 保存f006p1 WIP+清3垃圾+核flywheel/debug+报2 open PR | Sonnet | (锁死) | subagent bg | git plumbing | 多worktree | ✅ DONE | - | f006p1 WIP commit 452a0e139+push origin/feat/f006-workprocess-config-p1(分支保留无PR); 3垃圾dirty清; debug harness删; **flywheel-gov KEPT**(真未合并内容NULL审计基建countNullIsActive/promoteStaged #559没带; ⚠️其迁移V20260930_01撞main的product_work_process_assignees, 将来合必重编号). 11 worktree. **2 open PR待Steve定**: #547 deng-qa(docs-only +1408 MERGEABLE) / #545 餐饮入库posting(69文件+7406 **CONFLICTING** 4迁移, 可能部分被已shipped餐饮入库超越) |
| — 气调盒止血 | ✅ DONE: 批次#112 kg→件 + BOM标签气调盒→吸塑盒2014-3.5 | Opus自做 | - | - | prod data | - | ✅ | (直改prod) | 🔒 Steve GO 后 Opus 执行(2 UPDATE+验). 根因=入库批次单位误记kg(主数据/BOM都对, 早于T158锁). Steve 决策: 物料统一名「吸塑盒2014-3.5」(改BOM标签非主数据), 单位现在止血. 治本仍 Goal B |
| — RN登录版本标签 | 登录页 version 显示改 v1.0.0.1 (显示解耦真实version) | Opus改+Sonnet推 | - | 单subagent | merged main | EnhancedLoginScreen.tsx | ✅ DONE | #601 | 硬编码显示标签(非改app.json version: runtimeVersion.policy=appVersion 改了会孤立OTA). **OTA已推+验**: rv1.0.1/production/ts1780915445880, manifest live(Opus 独立curl确认 createdAt 10:44:45Z). 自建OTA(47:8083非EAS). **设备已确认显示 v1.0.0.1**(Steve截图). worktree cretas-login-version 已清 |
| T160 | RN登录页连点版本5次→OTA诊断面板(更新编号+更新时间年月日时分) | Sonnet | (锁死) | subagent bg | merged main | EnhancedLoginScreen.tsx | ✅ DONE | #602 | Opus审过(expo-updates try/require兜底+isEmbeddedLaunch防护+本地时区fmt+2s连点窗口+Modal点外关, tsc该文件0错, 单文件scope) → merged → **OTA已推+验**(rv1.0.1/production/ts1780917252866, manifest id 3ed1367a createdAt 11:14:53Z, Opus独立curl确认). 设备冷启2次→连点v1.0.0.1×5看面板. cretas-t160-otainfo worktree 待清(OTA推完) |
| T155 | (拆分为 T155-B 后端 + 前端chunk4-5 + 单位显示修 + 单价透明) | — | - | - | - | warehouse/materials | 🟠 跟进 | - | 调查aee3b4完: 餐饮SupplierDeliveryNote富(OCR+进价+落同material_batches), 方案B已采纳。剩: 前端chunk4-5(工厂送货单页复用餐饮组件) + **单位显示bug**(mapper line68 dto.unit=materialType.unit应读quantityUnit) + 单价透明(加单价输入) + evidence(方案B自带OCR照片)。T155-B后端落后再排前端 |
| T143 | UoM 物料换算器(已部署,被T144修正口径) | #584 | 2026-06-08 | merged b265fbfea + 部署(backend v20260608_094354+web)。建 MaterialUomConverter(g↔kg/CONVERTED/UNCONVERTIBLE 抽象保留)+接4点。**但心智模型错**(假设原料stock=箱+箱因子+抄码跳过)→ Steve纠正原料是称重kg→ **T144 修正**(改用 batch kg 单位)。converter 抽象+g↔kg 留用 |

### §部署批17 ✅ 完成 (2026-06-08) — T154 级联reactivity
- web-admin HTTP 200 (main 6c0396e6f)。删/改产品名称→二次匹配重新评估: cascadeWriting守卫(cascade自写不触发manual flag=crux) + always-overwrite(非manual字段) + clearCascadeFields(名称空→清,大类回activeTab)。手动改保留。#596 merged。build绿。无Flyway。
- ✅ 至此 T120-T154 全部 prod。剩 T103 真机(Steve)。

### §部署批16 ✅ 完成 (2026-06-08) — T153 规格自动拼+基础名称推导
- backend `v20260608_161947` + web-admin HTTP 200。A 规格从克重+装箱自动拼"80g/盒 20盒/框"(non-clobber specManuallyEdited) + B1 基础名称从产品名去客户前缀+规格后缀推导 + B2 suggest带baseProductName自愈。#595 merged 27635e865。43测试+build绿。无Flyway。
- ✅ 至此 T120-T153 全部 prod。剩 T103 真机(Steve)。

### §部署批19 ✅ 完成 (2026-06-08) — T155-B + T157 + T158
- T155-B 后端 ✅(方案B工厂OCR入库)。#598。T157 后端 ✅ `v20260608_174627`(operator选择屏 DTO) + **RN OTA待推**。#599。T158 web-admin ✅(入库单位自动锁主数据)。#600 3e8998b6b。
- ✅ **T157 RN OTA 已推 prod** (rv1.0.1 production ts1780912807288, 小组长选择屏; 安卓下次启动拉)。坑: RN node_modules 缺 expo bin→npx拉全局expo@56失败, yarn install 重装后成功。
- 待: 方案B前端chunk4-5(工厂送货单页) + **T159(原料master防呆→Steve开专门chat, handoff: `docs/dispatch/2026-06-08-T159-uom-material-master-handoff.md`)** + 单位显示bug(mapper line68)。

### §部署批18 ✅ 完成 (2026-06-08) — T156 单位别名(解开工拦截)
- backend `v20260608_171531` (backend-only)。MaterialUomConverter normalize 加别名 pcs/pc/pieces/个/件/只→个, g/克→g, kg/千克/公斤→kg。同canonical→1:1 CONVERTED。个↔kg 仍 UNCONVERTIBLE。27测试。#597 merged ad46561dd。**气调盒 pcs/个 不再误报无法换算, 开工解锁**。无Flyway。

### §部署批16-17 (见下) · §部署批15 ✅ 完成 (2026-06-08) — T152 智能匹配简化关键词包含
- backend `v20260608_154521` (backend-only)。nameMatchScore 简化: LCS≥2中文字即匹配(0.4+0.2*len), 去 T151 的 40%目标限制, 最长LCS赢。名字含产品关键词(牛腱/猪舌/猪蹄/掌中宝)即匹配不管长度/位置。#594 merged 9b67cfd82。21测试绿。无Flyway。
- ✅ 至此 T120-T152 全部 prod。剩 T103 真机(Steve)。

### §部署批14 ✅ 完成 (2026-06-08) — T151 智能匹配LCS信号
- backend `v20260608_150939` (backend-only 无web-admin)。nameMatchScore 加 LCS: ≥3字→0.8(掌中宝), 2字≥40%目标→0.35(猪舌/牛腱), max(coverage,lcs)。含产品关键词的噪音名也匹配。#593 merged 36a0ba399。17测试绿。无Flyway。
- ✅ 至此 T120-T151 全部 prod。剩 T103 真机(Steve)。

### §部署批13 ✅ 完成 (2026-06-08) — T150 智能匹配再带4字段
- backend `v20260608_144932` + web-admin HTTP 200。suggest 再返 温区/规格/标准克重/出成率(emptyToNull/null passthrough 禁假数据)+ 级联非clobber填。#592 merged daa79aa59。31测试+build绿。无Flyway。
- ✅ 至此 T120-T150 全部 prod。剩 T103 真机(Steve)。

### §部署批12 ✅ 完成 (2026-06-08) — T149 SKU智能填充 + 🔴关键持久化修
- backend `v20260608_140702` + web-admin HTTP 200。#591 merged e6680a4f8。T149: A编号去重(gen碰撞重试+手填409友好) + B suggest端点(名称相似度→大类/单位/level1/换算+keyword兜底, null禁假数据) + C前端级联(名称→自动填充不clobber)。
- 🔴 **关键持久化修(Opus gate 抓+亲手修)**: 前端一直提交 level1Unit/boxConversionCoefficient 但 **ProductTypeDTO 未声明+create/update未set+convertToDTO未map → 一直静默丢弃, 装箱换算从未真正落库**(T123/T137/T146/T148 持久化层一直摆设)。修=DTO加2字段+create/update set+convertToDTO映射(镜像gramsPerUnit)。30测试+BUILD SUCCESS。**这是 Steve 装箱换算"没变化"的根因之一**。

### §部署批11 ✅ 完成 (2026-06-08) — T148 规格内联行
- web-admin HTTP 200 (main 0652dfa9e)。规格信息装箱换算改内联行「1 [一级单位▼] ＝ [换算] [二级单位▼]」始终显示, 二级=formData.unit同步顶部单位。移出 auto-collect 后 3 路径(清空/编辑加载/提交)全手接已核。#590 merged。build绿。

### §部署批10 ✅ 完成 (2026-06-08) — T147 SKU表单流程
- backend `v20260608_130440` + web-admin HTTP 200。①客户 el-select下拉 ②编号实时预览(buildGeneratedCode 共用 create+preview 保证一致) ③大类按 activeTab ④二级单位 read-only echo 进规格信息(1筐=20盒)。#589 merged 1e9d64ba9。21测试+build绿。无Flyway。
- ✅ 至此 T120-T147 全部 prod。剩 T103 真机(Steve)。

### §部署批9 ✅ 完成 (2026-06-08) — T146 规格信息UX二轮
- web-admin HTTP 200 (main e4f71562c)。规格信息组: 标签"一级→二级转换数"→"装箱换算"(消除"数"换行) + 预览守卫(level1Unit==unit→⚠️警告不显"1盒=20盒") + 加"框"单位(common-first) + 标准克重动态placeholder("每盒多少克")。#588 merged。build绿。
- 手验: 设 二级=盒/一级=筐/换算20/克重120 → 预览"当前: 1 筐 = 20 盒"。

### §部署批8 ✅ 完成 (2026-06-08) — T145 箱数记录
- backend `v20260608_120507` + web-admin HTTP 200。**Flyway 核对: `20260930.04 add material batch box count success=true` on prod, box_count 列存在** ✅。原料入库可选记箱数(粗略统计) + 库存"约N箱"显示。box_count display only 不进任何计算(T144 kg口径不动)。#587 merged e25eb266e。
- ✅ **box_count backlog 关闭**。至此今晚 T120-T145 全部 prod。剩 T103 真机(Steve)。

### §部署批7 ✅ 完成 (2026-06-08) — T138 方案A
- backend `v20260608_105259` + web-admin HTTP 200。计划三按钮收口: 转为批次→主操作「开工」+继承库存校验(tx-safe, save-never-called 已测)/开始降级「更多」(保留,3调用方)/修 PLANNED→PENDING gate。**行为变化: 转为批次现在校验库存(原跳过)**。34测试+build绿。#586 merged 0abaeeca5。
- ✅ **至此今晚 T120-T144 + T138 全部 prod**。剩: 箱数记录 backlog + T103 真机(Steve)。

### §部署批4-6 ✅ 完成 (2026-06-08) — T136-T144 全上 prod
- **批4** T142 backend `v20260608_091225` + web-admin(T139/T140/T142)。
- **批5** T143 backend `v20260608_094354` + web-admin(去配置跳转)。
- **批6** T144 backend `v20260608_102440` + web-admin(request.ts)。
- **T136-T140**: web-admin 早批次随 T137 部(去配置跳转/产品规格UX/计划详情/批次详情)。
- **UoM saga 收口(T141审计→T143→T144)**: 单位口径理对 = 原料称重kg/成品装箱/BOM克桥梁。冷冻猪舌 开始生产不再误拦。
- ✅ **至此 T120-T144 全部 prod**。剩 T138 方案A(UoM 已对, 可做转为批次主操作收口) + 箱数记录 backlog。

### §部署批3 ✅ 完成 (2026-06-08) — T128–T135 全上 prod
- **Backend prod** ✅ `v20260608_080816` blue-green HTTP 200。T128(coref安全闸)+T133(wipToFgYield)+T134(restock WH-LOG)+T135(ProductWorkProcess DTO/service)。无新 Flyway(code-only)。
- **web-admin prod** ✅ 139:8086 vite 33.13s HTTP 200。T129/T130/T131/T132/T133/T134/T135 全套。
- **live-verify**: ① **T135 工序绑定**(CUSTOMER_ORDER 计划能否真创建,SO-20260608-0002) ② T131 一键提审 ③ T134 缺口可信/WH-LOG banner ④ T133 出成率可配 ⑤ T130 弹窗防呆 ⑥ T132 看板 tooltip。
- ⚠️ **教训**: merge+branch-delete 同命令→网络失败时 delete 仍跑→PR 关闭+branch 删。#578 经 refs/pull/578/head 恢复(fetch→重建branch→reopen→merge)。**今后先验 MERGED 再清理**。

### §部署批2 ✅ 完成 (2026-06-07) — T126-A/B + T127 全上 prod
- **Backend prod** ✅ `v20260607_221419`。Flyway _03 `finished_goods_adjustment_log` 表存在。成品 opening/edit/adjust/void 端点 + 存货生产后端 live。
- **web-admin prod** ✅ 139:8086, vite 43.30s 579 assets HTTP 200。T126-B 成品 UI(期初/调整/作废) + T127 存货生产 live。(与 T120 verify 并发部署无冲突——deploy 只 npm run build 不 install)
- **至此 T120–T127 全部 prod**。
- **T120 headed 验证完(3M tokens, 真实证据)**: 危险部分**已闭**(不再静默返全Top5), coref 注入两分支都 fire(jar bytecode×2 确认 + DISH 槽存储确认)。**但残留**: W0 ABSTAIN 闸在续接 READ 查询(conf 0.45/margin 不足)→ NEED_CLARIFICATION, 没自动过滤到单菜。**我的"第5层 ToolDispatch"假设被证伪**(注入正常, 阻塞在上游 W0 abstain)。= **T128 候选**(W0-abstain vs dish-coref 续接 tension, 匹配 memory W0 follow-up "只对写/敏感 abstain")。当前安全(澄清非错答)→ 待 Steve 定优先级。
- adjustment-log GET 端点 = Phase 1 backlog(detail 占位诚实标注)。
- deploy worktree `../cretas-deploy-prod` 含 untracked `web-admin/t120-verify.mjs`(verify agent 残留, 待清)。

### §部署批1 ✅ 完成 (2026-06-07) — T120/T121/T123/T124/T125 全上 prod
- **Backend prod** ✅ `v20260607_213326` blue-green green→blue, 5/5 health 200。**Flyway 验证**: `20260930.01`(T121)+`02`(T123) success=true, `product_types.level1_unit` 列存在。Steve: 跳 test 直接 prod(无真实客户)。
- **web-admin prod** ✅ 139:8086, vite 38.58s 578 assets 原子交换 HTTP 200。(脚本 `read -p YES-PROD` 交互闸 → 管道 `echo YES-PROD` 绕过)
- deploy worktree `../cretas-deploy-prod`(含 npm install)保留复用(下次 deploy 前 reset 到最新 origin/main)。
- **⏳ live-verify 待做**(API 我验 / UI Steve 手验): ① **T120 "它呢"+"那道菜呢"**(committed 开放问题: 定第5层 ToolDispatch 要不要) ② T124 估算 API ③ T121 多人报工(Steve 手) ④ T123 规格页(Steve 手)


### §BOM 审计 gate-checklist（superpowers spec 审计产出, T124-BE/FE 终审前必逐条核）
> 审计独立于 builder,findings 全部 cross-check 成立。**5 agent 都停在 PR,无未硬化代码上 prod。**
- **B1 持久 null**: 改默认 100→null 需删 3 道哨兵 — `BomController.toBomItem()` create+update 两处 + `BomServiceImpl.saveBomItem()` null-guard + Canvas 默认。否则 FE 的 null 被后端还原成 100(静默 no-op)。**决策: 持久 null(BE 删哨兵, FE 留空发 null)**。
- **B2 apply 逐行 factoryId 鉴权**: 每个 bomItemId 写前断言 factoryId 匹配,403 mismatch(deleteBomItem 已有越租漏洞,勿复制)。
- **B3 移除 ≤100 cap**: yield_rate 列允许 9999.99,保水/腌制 105-126% 真实存在,cap 会损坏这些品 BOM 展开。suggestedYieldRate/min/max 原样返,>100 UI tooltip"增重工序出成率可超100%"。
- **H4 valid sample = COMPLETED && cumulativeYieldRate != null**(不用 BatchYieldDTO.complete — 三段报工的 SEGMENT-only 步会令 complete=false 误杀全部 F006 批次)。
- **H5 BomChangeLog changedBy**: apply 从 JWT 取 userId/username 写入(recordBomChange 现在留 null)。
- **H6 主原料行选择(Option B,无迁移)**: 单 RAW→它; 多 RAW→preview 列全部 RAW 行,用户勾选,apply 按勾选 bomItemId 写。
- **M10 staleness 409**: apply body 带 expectedCurrentYieldRate, DB 现值≠则 409 让重新 preview(fool-proof Rule 4)。
- M9: spec 例 120/0.55=218.18(非217.x),仅文档 typo。

<!--
状态: ⬜ pending / 🟡 in-progress / 🟠 review/待终审 / 🟢 已合并待部署 / ✅ done / 🔴 blocked
格式参考:
| T001 | KPI 看板前端 | Composer | default | inline | feat/kpi-ui | web-admin/src/views/kpi/ | 🟡 in-progress | - | 等后端 T002 |
-->

---

## 📋 #547 QA findings → informed 批量修 backlog (Steve定: 跑完QA一次性修)

> Ch01-07 跑出来的 bug/gap 都汇这. **QA Ch00-07 全跑完 (链条COMPLETE, 深链证通)**. Steve定: **全部修, 分波推**(防并发撞文件).
> **Wave1 状态** (🔒 Opus gate 抓出 2 个错诊断, prod 验证才揪出 — 不让自合的价值):
>  - **W1-A #610 → v2 重诊** (`fix/restaurant-inventory-posting`): #610 误诊为列缺失, prod 实查列在+V20260927_04已applied+inventory_posted_at已设→真因=过账触发但批次扣减不持久(fail-soft/doomed-tx). 保留#610对的部分(DB锁timeout/盘点systemQty), 删no-op迁移. v2 在飞.
>  - **W1-B #611 v2 ✅gate-ready**: BUG-01中文列脱敏+BUG-02送货额@PriceSensitive 真修保留. **GAP-05-C证伪**(v2 prod实测三表全200, finance:rw对, QA 403=session假象, L0硬编码已删). 415测试过. hold批量部署.
>  - **W1-A #610 v2 ✅**: GAP-04-DEDUCT **证伪**(扣减真工作, Opus prod验 used_quantity匹配明细). 只留真修: BUG-03-01事务超时 + GAP-04-C盘点systemQty + stateful测试(防回归). 删no-op迁移.
>  - **W1-C #612**: 营收→gold tool + 新应付→AP tool + Flyway V20261001_01.
>  - **3个全 merged + 已部署 green 10020** (jar 7d709efe, Flyway V20261001_01 applied success=t, idle_in_transaction配置加载). **#608 已关**. **Wave1 backend live验在飞**(脱敏不过度拦/AI路由/取raw证据).
>  - **B4 漏改修正 #613**: 上次我做B4只改注释+移面板, 漏改 defaultTrayWeighing={isFirstStep}→首道仍默认展开(Steve实测发现). #613 改{false}真默认收起, **OTA推送中**(教训: 自己做的小改也要核实际行为不只注释).
>  - **⚠️ meta: QA有假阳性**(GAP-04-DEDUCT/GAP-05-C都证伪), gate prod复验是真假分水岭. **Wave2/3 backlog 建议先逐项prod复验再建**(防为假阳性造修).
> **Wave2**(W1-A合并后): 组6 付款UI+对账409+banner+杂项. **Wave3**: 组4 数据地基/需求聚合(最大, 先设计). #608 待W1-B替代后关.

| ID | 严重 | 发现章 | 描述 | 正确修法 |
|---|---|---|---|---|
| BUG-01-RBAC | HIGH | Ch01 | sales_mgr(analytics:read) API读财务上传原始数据(/uploads/{id}/data+/fields) | **amount-masking**(非财务角色脱敏金额列, 非一刀切finance:read_write—会拦analytics盘POS预览回归). #608 HELD作记录 |
| GAP-01-A | MED | Ch01 | POS/财务CSV自动分类总UNKNOWN→ETL不跑→数据流不到分析(name-resolution Python 401) | 分类列schema匹配器(识别POS/金蝶列) + Java→Python auth token转发修 |
| GAP-01-C | MED | Ch01 | 供应商CSV上传不自动建送货草稿(Ch01→Ch03桥断) | upload确认→POST /restaurant/supplier-delivery草稿创建桥 |
| NOTE-chef | INFO | Ch00 | qhj_chef_cold/hot账号缺(Ch02厨房流程需要) | 建账号(同T110) 或确认用现有角色 |
| GAP-02-A | MED | Ch02 | 无 MaterialRequisition→PurchaseRequisition 需求聚合API/实体(daily-summary只读无push-to-plan) | 建需求→采购计划聚合(或确认两系统职责后接桥) |
| GAP-02-B | MED | Ch02 | 无 web-admin 聚合UI(/procurement/purchase-orders 404, 领料管理只管单条) | 采购计划聚合页 |
| GAP-02-D | LOW | Ch02 | MaterialRequisition: create的quantity不映射requestedQuantity(返null); approve必须带actualQuantity否则400 | 修字段映射 + approve参数文档/校验 |
| BUG-03-01 | **CRIT** | Ch03 | saveAndFlush(POSTING)在HTTP连接断时无限持行锁→idle-in-transaction阻塞所有后续确认(要手动pg_terminate_backend) | 加 idle_in_transaction_session_timeout/事务超时 + POSTING状态机防卡 |
| BUG-03-02 | HIGH | Ch03 | 过账失败后postingError banner残留旧文案误导 | 新尝试前清error state |
| BUG-03-03 | HIGH | Ch03 | 送货映射物料无单位自动建议, 可自由填错单位(跟T159 UoM相关) | 映射时按主数据单位auto-suggest/校验 |
| GAP-03-A | LOW | Ch03 | 黄瓜/洗洁精不在53项物料目录 | 补物料主数据(或确认用现有) |
| GAP-03-C | MED | Ch03 | /restaurant/supplier-reconciliation 404(Ch05对账核心?) | Ch05核实是否未实现 |
| GAP-03-D | LOW | Ch03 | confirmed_by(过账人)POSTED后为null | 过账写入JWT userId |
| ~~GAP-04-DEDUCT~~ | ✅非bug | Ch04 | ~~库存不扣~~ → **W1-A v2 + Opus独立prod验: 扣减真工作**(batch 07ca3121 used_quantity=3.0 USED_UP 匹配过账明细1.95+1.05). QA看的batch 0b4a6ae8是被别操作扣的=误判. 证伪. W1-A 只留真修(BUG-03-01+GAP-04-C)+加stateful测试(旧mock测试查不出回归) | (closed) |
| GAP-04-C-SYS | HIGH | Ch04 | 盘点账面库存=全品种聚合非按批次+创建时不可见(完成才显) | 按批次账面量+创建时可见 |
| GAP-04-E-LIE | MED | Ch04 | 成本归因banner谎称"真实扣库"但实际没扣(诚实性违规) | 改文案或真扣库(随GAP-04-DEDUCT) |
| ~~GAP-05-C~~ | ✅非bug | Ch05 | ~~finance_manager三表403~~ → **W1-B v2 prod实测三表全返200, finance:finance=rw** → QA的403是session假象(同Ch03 404). 证伪, 无需修 | (closed) |
| GAP-05-D | HIGH | Ch05 | 无对外付款(付款管理)UI, 应付挂账无"标记已付"工作流(/finance/payments只收款) | 建对外付款disbursement+evidence页 |
| GAP-05-A | MED | Ch05 | 月对账重复POST draft 返200"草稿已生成"但空操作(已有CONFIRMED时) | 改返409 CONFLICT |
| GAP-05-C2 | 设计注记 | Ch05 | SmartBI上传→分析gold层 vs 经营finance表 两层无桥(Ch01上传对不上经营P&L=设计如此) | 若要链通需建桥(或确认双层意图) |
| GAP-05-E | LOW | Ch05 | AP同笔标签不一致(应付挂账 vs 应付开票) | 统一标签 |
| GAP-06-A | HIGH | Ch06 | AI"问数据"tab绑CSV session, "本月营业收入"返供应商CSV(940)非gold(¥78.77M), 餐饮用户问营收不自动路由gold | 营收类问题路由到gold分析层 |
| GAP-06-C | MED | Ch06 | "应付多少"路由到佣金应付(¥0)非AP应付(¥458.30) cross-domain误路由 | 应付意图路由到ArAp |
| GAP-06-B | INFO | Ch06 | 在线客服widget"客服系统尚未配置"(诚实+next action, 无第三方CS) | 接入或保持诚实空 |
| BUG-02-RBAC | MED | Ch07 | 送货单totalAmount对sales_mgr未脱敏(¥470可见), 列表端点无字段级脱敏 | 随BUG-01-RBAC一起字段级脱敏 |
| — Ch07 注 | — | Ch07 | matrix里wastage/stocktake/三表/KPI/cost-attr"404"=agent打错REST路径, 非真未实现(Ch04-06 UI都访问过能用) | 无需修 |

## Scope 锁地图

> 派活前必查。两 task 重叠同一路径 → 串行 或 重切 scope，绝不并发改同一文件。

| 文件 / 目录 | 锁定 task | 预计解锁 |
|---|---|---|
| `YieldReportServiceImpl` + ProcessingBatch DTO + `TransferController/Service` + ReportReversalLog DTO | FIXB-BE | PR merge 后 |
| web-admin `warehouse/wastage-reports` + `procurement/exceptions` + `rd/quotations` three-price + 路由/菜单 | FIXB-WEB | PR merge 后 |
| RN `MaterialBatchPicker.tsx` + `WastageReportScreen.tsx` | FIXB-RN | PR merge 后 (⚠️若重启 T164 polish 必排其后) |
| `menuConfig.ts` + `DashboardProduction.vue` + RN management screens | T125 | T125 PR merge 后 |
| — (T108/T110 已合并部署,锁释放) | — | — |

---

## Done（待清理）

> PR 合并后的 task 移到这里。每周清一次。

| ID | 任务 | PR | 完成时间 | 备注 |
|---|---|---|---|---|
| ⚠️LEDGER事故 | 2026-06-10 验证战役 session 台账重建 | - | 2026-06-10 | **本块由 organizer 在 ledger 事故后重建**: 整个 session 的 ACTIVE.md 编辑做在 stale 主目录(落后 origin/main 数十 commit)未提交→被 git 操作冲掉。教训: **台账编辑必须在干净 worktree(off origin/main)做并立即 commit/push, 绝不在 stale 主目录改**。以下行从 session 记忆重建, PR 全部已 merge+核实在 origin/main(ab46d1ea6) |
| R8-双栈合并 | 报工双栈合并: 设计稿+方案A实现(ProcessOperationScreen 主按钮→YieldStepReportScreen 三阶段, 旧版保留次级入口) | #670+#672 | 2026-06-10 | 🔒Opus终审. Steve拍板方案A+旧路径保留. **后端已部署 v20260610_202542 蓝绿green:10020 + RN OTA ts1781094836360 已推**(jar标记2/2, test API断言 wptId/batchNumber/processOrder全填充). WPT双路径解析(WPT-前缀+BATCH-三键). 待F006真机抽验(WS主管工序操作→三阶段) |
| SWEEP-A | 验证扫荡 批A D/E流: 22项→20 V1 | #669 | 2026-06-10 | 🔒#669 Flyway V20261013_01 补 purchase_receive 3列. D-11含税双值精确断言. E-11矩阵误报更正. audit: d-flow/e-flow. D-19/E-13打印 502→V1(根因=test Java 缺 cretas.python.base-url 跨env JWT 401, #674; +wqy-zenhei中文字体). 剩B阻塞: D-9(→已测绘) E-2(→已修#679) |
| SWEEP-B | 验证扫荡 批B H/X流: 22项→20 V1, 0代码bug | - | 2026-06-10 | **纠正矩阵12处误标**(凭证导出/进销存台账/T-3锁 标🔴缺但代码实际在=stale工作树grep遗毒). 六扇门实现完成度比矩阵高. SP11表名复数纠正. audit: h-x-flow. 剩V0: 凭证模板编辑器UI/PMC配料员无独立角色/X-3退货审批挂接 |
| SWEEP-C | 验证扫荡 批C A/B/G/I流: 36项→29 V1 | #680 | 2026-06-10 | audit: batch-c-ab-gi-flow. 挖出🔒BUG#1 BOM orphanRemoval→#680. BUG#2 productCode可变(A-24 backlog) |
| BOM-ORPHAN | 🔒 BOM orphanRemoval 集合替换异常(6写方法 setItems→clear()+addAll) | #680 | 2026-06-10 | 🔒Opus终审**抓回归+修**: agent把无@Transactional的getRecipe也改clear()→detached LAZY会抛LazyInitException→**revert getRecipe回setItems**(B-10实证本就好); +test mock isWriteUnitCompatible修编译. 解锁批C 7条B阻塞 |
| ISO-4H | 🔒 多租户隔离审计4 HIGH 修复 | #676 | 2026-06-10 | 🔒Opus终审. foodsafety两工具=国标全局参考数据EXEMPT(prod DB核实两表无factory_id列); workdesk两收货写工具=factory-scoped finder+checkDuplicate查询级隔离. 31测试. audit HIGH 4→0 |
| D9-付款链 | 🔒 D-9 付款申请链 全链测绘+gap修复批(G1出纳DTO/G2全入库前置PREPAID豁免/G3 null-balance/G6死代码/G7 settlementType继承 V20261014_01) | #675+#678 | 2026-06-10 | X-4答案: 硬编码状态机非WorkflowEngine(DB 0实例). 🔒终审**抓BLOCKING+修**: G4 cashier seed迁移 Flyway跑prod→已知密码进F006真客户租户=越权→**删迁移**改organizer直接psql种test库(已种+登录验证role=cashier). #677误删分支被关→refs/pull救回rebase→#678. G5销售方向→周五问客户 |
| FIX-E2 | E-2 空价SO草稿放行(create放行+submitForFinanceReview强制价格 行717) | #679 | 2026-06-10 | 根因=全局factory_validation_rules POSITIVE_AMOUNT CREATE规则(DB驱动). SO总DRAFT起步语义安全. 🔒终审**抓Flyway乱序**: V20261013_02<已merge V20261014_01→renumber V20261014_03. + E2-CLEAN: organizer清test库111行规则垃圾(软删, 现0条生效) |
| FIX-E4 | E-4 SalesOrderItem.getLineAmountWithTax @Transient(镜像PO侧) | #673 | 2026-06-10 | divide scale-6 HALF_UP→setScale(2), @PriceSensitive, null/零税率短路, SO含折扣. 7测试. Opus对照PO实现逐行核一致 |
| FIX-PRINT | D-19/E-13 打印链502修复(systemd test加cretas.python.base-url)+wqy-zenhei中文字体 | #674 | 2026-06-10 | 根因反转: 非路由缺失=test Java代理prod 8083跨env JWT 401. 服务器已apply systemd+装wqy-zenhei.ttc(ReportLab读不了Noto CFF-TTC). PDF 2KB→17KB内嵌中文实证 |
| RULE-FABLE-V2 | Fable升级闸v2落规则(organizer-protocol+multi-model-dispatch) | #671 | 2026-06-10 | Steve拍板: 卡死阈值2轮→1轮认真尝试 + 三类预授权直通(prod事故计时/同族前科/不可逆小diff终审) + Opus轮产物回收进brief + 经济学根据. Opus亲做keystone docs |
| CIG-A.5 | Chart-Insight 蒸馏重设计 v4 (claims-pinning) | merged main 7f62959c1 | 2026-06-10 | brainstorm→spec v1(Fable否决)→v2(3-critic否决)→v3→**Fable二审 BUILD+6MF**→v4. 8 task TDD shipped+prod验. 砍在线模板飞轮(定理:可缓存=Tier1可表达)+砍memo(日churn假命中). 留: Tier1安全主力 / 活LLM serve(claims-pinning结构杀幻觉: LLM返结构化声明→服务端按series重算+数字邻接闸, 不节流智能) / corpus(persist_distillation_sample, 渐进替代桥) / 离线策展M4(defer). +revert U1.8跨租户RLS(dead-on-arrival, V20260929_01 factory-scoped). **4轮real-path修(单测过prod不fire)**: value coercion / 邻接nearest-preceding(中文entity在前) / **prose-vs-closure(Fable MF1真意, claims降advisory, 排complement防2元swap绕过)** / trend slots只x_dim=time(proportion无趋势义防数值coincidence). prod验: 锦里店55%/3.7倍 clean无幻觉, 读回缓存0-token, corpus写入, finance_hidden无¥, Tier1三bar无回归. ⚠️残留: 1条stale corpus row含growthRate噪声(fix前写的,新生成已无);U1 abstain对读也fire(W0 follow-up); M4离线+自有模型训练是后续 |
| CIG-A.6 | Chart-Insight 程序 Fable 战略审计→纠正资源分配 | (策略决定无PR) | 2026-06-10 | **Fable 战略审计: 架构对, 资源分配反了** —— 重心飘向 flywheel/自有模型(seeding/M4), 而最高价值"铺洞察到其余36面"没开工. 与 **2026-05-31 vertical-model verdict(现在绝不自训, trigger-gated)** 重合, 无门触发. **纠正顺序**: ①Trivial-19铺开(drop-in useChartInsight, 兑现目标#1) ②Moderate-17(用已建`<ChartInsightProvider>` keystone) ③serve遥测(tier1/接受/拒/cache/null+token=下游决策依据) ④organic corpus自然积累(铺开副产物) ⑤M4 defer到有量+有簇. **砍**: 合成seeder扩量(--n500已按住; 合成=易区→训出脆, 脚本搁置作训前gap-fill) / 修45%接受率 / Exotic-7砍6(GAUGE=KPI映射, FUNNEL遥测门控, SCATTER/HEATMAP/RADAR/SANKEY/WATERFALL跳过=null诚实) / 自有模型+shadow/canary非工作流(Tier1+cache+M4已结构趋零). 清理删stale row 726(growthRate噪声), corpus现10行(1manual+9 synthetic_seed pilot可分离). **自有模型不上任何sprint直到May-31门触发** |
| T121 | 工序多人负责(join表+任一可报) | #564 | 2026-06-07 | 🔒Opus 终审过(authz 无 regression: empty=open 同旧 null-assignedTo; @Where 软删过滤 removed assignee 失权; currentRole 请求属性非死 SecurityContext; 迁移 partial unique+幂等 backfill+real FK product_work_processes; 143测试)。**merged main f9490b0d3, Flyway V20260930_01。⏳ 待批量后端部署+live 验多人报工** |
| T120 | 菜品 coref 续接分支(裸指代"它呢") | #562 | 2026-06-07 | 🔒终审过+merged main f3f0aca21。**仅修裸指代续接分支**(byte-safe)。⚠️"那道菜呢"走 ELSE 路径(已有解析)→ **部署后必 live 验两种 phrasing**, "那道菜"仍挂=第5层 ToolDispatch(届时新任务)。Steve: 两种都要支持 |
| T127 | 「存货生产」独立来源(复用 SAFETY_STOCK)+备货看板对齐 | #569 | 2026-06-07 | merged main ddf4619a7。**后端零改**(SAFETY_STOCK 已 skip SO 校验+FG链自动入库)。前端: plans/list.vue 加存货生产 radio(SO 选择器仅 CUSTOMER_ORDER required→#3 回归安全)+来源 label 全映射+restock-board MANUAL→SAFETY_STOCK。26测试+build绿。**⏳ web-admin 批量部署**(随 T126-B)。doc 3 路径对照表 |
| T126-A | 三层库存 Phase1 后端(opening/edit/adjust/void+adjustment_log) | #568 | 2026-06-07 | 🔒终审过+merged main 1165f62be(Flyway V20260930_03)。审计 12 findings 全实现+我 gate 验: F1 operator_id request.getAttribute✓/F2 422✓/F5 void-shipped 409✓/F12 getFinishedGoodsBatchById 隔离✓。**gate 自修 F8 双事件**: 旧 delegate-then-republish→TriggerChainExecutor 期初双触发→改单事件 sourceType worker(887473f24)。11 文件+13测试。**⏳ 待部署**(随 T126-B + T127 批量) |
| T142 | WorkProcessTaskDTO 补 assignedToName(批次工序显真名) | #583 | 2026-06-08 | merged main d4ebb873b。5文件。镜像 T135(loadAssigneeNames batch findByIdIn 无N+1, null-safe 避 Map.of NPE, fullName/username)。前端 assignedToName||#ID。13测试+build绿。无Flyway。补 T140 缺口。**⏳ web-admin+backend 待部署** |
| T139 | 计划详情弹窗 工序显负责人名 + UI/UX 重设计 | #581 | 2026-06-08 | merged main 6af5d2e7e。plans/list.vue 1文件。getProductWorkProcesses(responsibleWorkerName,T135) → 详情弹窗3段(基本/时间/工序全宽有序列表带负责人tag)。build绿。**⏳ web-admin 待部署** |
| T140 | 批次详情 工序明细(可点击)+修复生产时间线 | #582 | 2026-06-08 | merged main 6af5d2e7e。2文件。时间线根因=字段名 {time,event} vs {timestamp,title} 不匹配→修; 工序明细 section(GET /batches/{id}/work-process-tasks)卡片+点击drawer。**⚠️ T140-FLAG 后端缺口**: WorkProcessTaskDTO 有 assignedTo(ID) 无 assignedToName→前端显"负责人 #ID"。**需后端补 assignedToName(镜像 T135)**=follow-up。build绿。**⏳ web-admin 待部署** |
| T137 | 产品编辑 规格信息组 UX(单位字典下拉+修截断+一二级打通) | #580 | 2026-06-08 | merged main c843f1f40。3文件。level1Unit+top单位→字典 el-select(filterable+allow-create, /system-config/units+静态兜底); boxConversionCoefficient placeholder "1筐=?盒"computed+span24全宽不截断; gramsPerUnit/wipToFgYield placeholder缩短。DynamicEntityForm 加 filterable(always,benign)+allowCreate(opt-in安全)。build绿。**⏳ web-admin 待部署** |
| T136 | 「未配置」警告→权限门控去产品字典配置跳转 | #579 | 2026-06-08 | merged main 846b04eff。2文件(SO弹窗+看板)。`canAccess('system')` 门控(仓管/操作员无按钮=Steve要求)+`/system/products?_returnTo=`(T122模式)。build绿。**⏳ web-admin 待部署**(随 T137) |
| T134 | 备货看板 WH-LOG 可发量拆分(fgShippableQty)+banner | #577 | 2026-06-08 | merged main dffa37903。新 repo 查询 WH-LOG+盒 双过滤; **覆盖公式不变**(fgAvailableQty 全厂照旧, fgShippableQty 纯加); banner=plan覆盖足但WH-LOG不足。9测试(验WH-WKS批次不入shippable)+build绿。无Flyway。**⏳ 待部署** |
| T133 | wipToFgYield 半成品出成率 写入路径 + 产品表单 | #576 | 2026-06-08 | merged main ffbd5c3ea。镜像 gramsPerUnit(DTO+create/update/convertToDTO 三处+UI 规格信息组)。无 Flyway。7测试+build绿。**⏳ 待部署**(backend+web-admin)。配了出成率→看板 WIP 不再 1:1 虚高 |
| T131 | SO 列表 提审(链式)+多选批量操作 | #575 | 2026-06-08 | merged main 90d3fe380。纯前端1文件。修 submit→confirm bug + CONFIRMED 补 inline 提审; **链式半失败3桶验过**(confirm败→failed/confirm成submit败→confirmedOnly可重试/双成→success)+单行 sticky 消歧; batch `:selectable`+viewMode guard+Promise.allSettled 跳过不合格+三桶汇总; submittingIds per-row loading。cancelOrder 接 CONFIRMED 已核(SO_CANCELLABLE)。build绿。**⏳ web-admin 待部署** |
| T130 | SO 弹窗防呆(自动加行提交修复+业务员预填+仓库记忆+单位/箱数/Tab) | #574 | 2026-06-08 | merged main e75c6a0d9。纯前端零后端。**A 提交两层修验过**(getSubmittableItems filter productTypeId→只校验/只提交 selected, 空行不入payload, 选品零量报错)。B @change 预填(②assignedSalesUserName 校验 emp.fullName→③当前用户, Option A)。C localStorage 仓库记忆 cretas_so_last_warehouse_{userId}+修F9。D calcBox 两层(份qty/coeff round2 / 箱qty)+单位 el-select 默认份+箱数只读+规格只读+Tab跨行。⚠️ **审计误判**: 称 auto-row infra 已存在, 实际 post-T129 base 零(agent 从头建+234行)。build绿。**⏳ web-admin 待部署**。Steve 手验 17431726 四行箱数 |
| T132 | 备货看板 列头 ? tooltip 防呆 + UX(可用总量/缺口列/默认日期) | #573 | 2026-06-08 | merged main 48ce2b1a6。UI only 1文件。每列头 ? tooltip(锚 RestockBoardService 验过)+可扣减覆盖→可用总量/原料估算→原料可产+缺口列红字 fixed+警告 icon 收+日期列分隔+默认日期动态(非硬编码)。build绿。C3: createPlan 已 SAFETY_STOCK(T127 已改, 审计读 stale)。去配置 link 留 TODO(无可靠路由)。**⏳ web-admin 待部署** |
| T128 | 菜品 coref 续接直达继承意图(跳过 abstain) | #572 | 2026-06-08 | 🔒安全闸终审过+merged main 9e2b5c991。续接命中白名单(5全READ意图)→buildContinuationInheritResult 直达继承意图(conf0.97)跳过重识别+abstain。**安全 guard isSafeToInheritIntent 验过**: 白名单+hasWriteSuffix+isWriteIntent(cfg) sensitivity, **异常 fail-closed**(写意图即便误入白名单也拦)。12新+159存测(parity70/golden15 字节不变)。**⏳ 待部署**(intent backend) |
| T129 | SO 草稿删除(软删) + 行操作整合 | #571 | 2026-06-08 | merged main 9e2b5c991→**解锁 list.vue**。deleteDraft DRAFT-only→409 + getSalesOrderById 404+403 隔离 + @SQLDelete 软删(无Flyway) + @Loggable, 5测试。整合去重: 移除 rowActionsConfig 中 hardcoded 重复(详情/编辑/确认/取消 仍 inline)+ 删 buggy submit(T131 重加提审)。**无功能丢失**。**⏳ web-admin 待部署**(随 T130/T131) |
| T125 | 转换率(RPF) 隐藏前端入口(留后端fallback)+补单位字典菜单 | #567 | 2026-06-07 | merged main d4eda01ab。隐 4 入口(web菜单/dashboard/RN×2)+删 ImpactAnalysis 死inject+补 T123 单位字典菜单。RPF API/表/fallback/bom-unified tab 全留(F001 load-bearing)。build+test-compile绿。RN 待 Steve 手动验。**⏳ web-admin 批量部署** |
| T124 | BOM 出成率评估(估算/预览/一键重算) BE+FE | #565+#563 | 2026-06-07 | 🔒终审过+merged main 00065b3b6。superpowers spec 审计救回多项: B3 移≤100cap(保水>100不损坏)/H4 cumYield≠null 样本(非complete,否则三段报工批次全杀)/B2 逐行 factoryId/H6 主料行选择/M10 staleness 409/B1 删3哨兵持久null(verified 无 NPE: BomBatchOp:84 null-guard)。**H5 gate 自修**: agent 用 SecurityUtils(空SecurityContext twin-trap)→ 我改 RequestContextHolder 读 userId/username(ac66ffd64)。无 Flyway。**⏳ 待批量部署**(backend + web-admin) |
| T123 | 规格两级单位+单位字典管理页+名称分离+客户打通 | #566 | 2026-06-07 | 🔒终审过+merged main 1716e52f0。**⚠️gate 抓 Flyway 撞号**: agent re-verify 在 T121 merge 前→也用了 V20260930_01,我重编号→V20260930_02(教训复发: re-verify 时点早于 sister merge)。复用 boxConversionCoefficient(sales_order 安全)/gramsPerUnit; 规格信息组重组(成品显标准克重); UnitOfMeasurement CRUD 接已存端点(SystemConfigController). 15测试+build绿。**⏳ 待批量后端部署**(Flyway _02 apply)。RN base_product_name 待 Steve 手动验。菜单入口由 T125 补 |
| T101 | S1 API 冒烟自动造数(高价异常+绑供应商 DRAFT) | #540 | 2026-06-07 | 5 次 prod 实跑审批链全 PASS;test-script 无需 deploy |
| T102 | S1 对账冻结月 `unReconciledNotes` 只读可见性(Option B) | #543 | 2026-06-07 | 🔒财务 Opus 终审过;**已部署 prod** — jar 含 `buildUnReconciledNotes`/`findOrphanNotesNotInReconciliation`,web-admin 8086 含防呆 banner(Rule 5 next-action);prod 实证 20 孤儿单 |
| T104 | S2 发货应收幂等 | #542 | 2026-06-07 | 守卫早 live(`3f26931f5` on main);#542 仅补缺失回归测试(3 case)test-only |
| T105 | S3 Phase2a coref prod live 验收 | 证据 md | 2026-06-07 | 4/4 判据 PASS;active jar 确含 STORE coref;工厂 SUPPLIER 零回归。证据: `docs/superpowers/handoffs/2026-06-07-phase2a-store-coref-prod-live-verification.md` |
| T106 | f006p1 两 bug(carry-over override + preview LLM 抽参) | #544 | 2026-06-07 | 🔒AI执行路径 Opus 终审过;**已部署 prod** — jar 含 `getEstimatedMinutesOverride` carry-over;backend blue:10010 v20260607_104835 |
| T109 | #2 全天备货看板(restock board) | #466 | 2026-06-07 | **✅ T113 深验 PASS**:2 产品(白卤猪舌/猪蹄)逐层 DB 对账=API 完全吻合;FG 软删过滤/WIP deletedAt/Scheduled仅PLANNED+PENDING(IN_PROGRESS排除无双计)/shortfall=max(0,需求−合计)。三层去重真确认正确(不止"存在于main")。 |
| T107 | #3 澄清 padding COMMON-overload | #549 | 2026-06-07 | 🔒Flyway Opus 终审过(`V20260928_03` 幂等)。根因=`MATERIAL_BATCH_QUERY`/`PROCESSING_BATCH_LIST` business_type=COMMON 泄漏餐饮澄清→重标 FACTORY。测试 6/6+70/70+15/15。**已部署** green:10020 v20260607_122604(prod 实证 business_type=FACTORY)。 |
| T110 | 餐饮专属角色 chef/purchaser/owner(scope A 增量) | #550 | 2026-06-07 | 🔒权限+业态 Opus 终审过。enum+权限矩阵最小化(chef warehouse:rw/purchaser procurement:rw+价格可见/owner 全+财务审核),@RequireRole 增量,**无 Flyway**,42测试。**已部署** green:10020 + **prod 建 3 账号验登录**(qhj_chef/qhj_purchase_mgr/qhj_owner /123456,factoryType=RESTAURANT)。界面已由 factoryType 分开,本次只分角色命名空间。 |
| T108 | 菜品续接 Phase2b(DISH coref 镜 2a) | #551 | 2026-06-07 | 🔒AI执行路径 Opus 终审过("它"仅 DISH 槽+解析序 DISH→STORE→SUPPLIER,107测试+70/70+15/15)。**已部署** blue:10010 v20260607_132848。D4 STORE/SUPPLIER 零回归实测 PASS。**⚠️ T113 复验暴露: 菜品数据其实存在(D1 返真 Top5),但 D2"那道菜"返全 Top5 未过滤=coref DORMANT**(gate miss: 我只验单测+D4 没 live 验真功能)→ **T116 修中**。 |
| — | 📌 餐饮问答 A+B+C 基线侦察结论 | — | 2026-06-07 | speed agent("消灭LLM")+ content agent("8%/0 gold 实现")**均过度声称,Opus 交叉验证否决**。真相: gold 工具全在+营收返真数据(¥940 6月/¥11.57M 3月峰),"暂无菜品数据"=诚实空(demo工厂)。真问题窄=跨域误路由(平台/VIP/美团)+飞轮投毒+少数真缺维度→T112。 |
| T112 | 餐饮问答 反投毒(守卫+绑峰值+清毒) | #553 | 2026-06-07 | 🔒 **deployed+verified**: 守卫 live(jar shouldLearnExpression✓)/峰值绑定(DB✓)/25 DISH_DELETE 毒行 deactivated(DB✓ 25). green:10020 v20260607_151634 |
| T116 | 修菜品 coref D2(ensureDishReferenceResolved) | #554 | 2026-06-07 | ⚠️ deployed 但 **D2 仍 dormant**(post-deploy live: 返全Top5 未过滤). #554 修 D2 解析侧, 真因在 D1 slot-写入侧→ T118. ✅gate教训: 这次 post-deploy live 验抓到(没像 T108 漏) |
| T117 | AI配工序草稿渲染 bug | #555 | 2026-06-07 | 前端 WorkProcessAIChatPanel.vue 缺 PRODUCT_WORK_PROCESS_DRAFT renderer→吐 raw type; 修=渲染工序卡+应用按钮. **deployed** web-admin 8086 HTTP200(rsync) |
| T114 | §8 基建 deploy-staging CI libcrypto | #552 | 2026-06-07 | CI-only(`echo key>id_rsa` 丢尾换行→OpenSSH8.9+严格PEM拒→改 `webfactory/ssh-agent@v0.9.0`,secret不变)。1文件10/10,下次CI生效无需部署。§8b test采购账号401=**非bug**(e2e_purchase_mgr 实测存在+active+登录success,历史401是seed未跑;无码改)。 |
| T119 | AI配工序解析保真(不凑数) | #558 | 2026-06-07 | 根因=F006 catalog **3行都叫焯水**→单个输入焯水全匹配→焯水×3 + 子串误配(滚揉保水⊂二次滚揉保水/气调⊂气调包装)。修=E1 catalog 去重 + E2 **分段驱动**(切用户输入为N段→N步, 50%长度守卫防子串)+ E3 重复警告。非catalog步→"请先新建"(不丢不替)。11/11。**已部署** green:10020 v20260607_191026。**Steve UI 复验 10 步忠实**。 |
| T115 | 飞轮治理 v2(分层写入 + 一致性重提议 promote) | #559+#561 | 2026-06-07 | #556 promote 硬伤(staged 不路由→无 hits→永不 promote)关闭重设计。**修正认知: NULL+staged 都不路由=dormant 安全**(我曾误判 NULL=活跃毒, 读 query 纠正)。v2: ≥0.9 active/0.70-0.89 staged; dedup 命中→proposal_count++, 第3次+**#553守卫(fail-closed)**→promote(防遗留跨域毒复活)。Flyway V20260929_02。18/18+70/70+15/15。**已部署+验** green:10020 v195646(守卫 isPromoteAllowedByGuard=1, proposal_count列=1)。⚠️ #559 守卫 commit 漏 push → cherry-pick #561 找回。 |
| T122 | FK 引用阻删 → 通用防呆导航(全站) | #560 | 2026-06-07 | 🔒跨切面。interceptor 级,41 delete handler 自动覆盖,19 FK表→中文模块名+route。后端 GlobalExceptionHandler FK_BLOCK + 前端 request.ts 拦截弹窗→跳转(_returnTo)+ ReturnBanner 浮条。headed 验(弹窗→跳转→返回→关闭)。**已部署+验** 后端 green:10020(FK_BLOCK=1)+ 前端 139:8086(FK_BLOCK in request.js + ReturnBanner in AppLayout.js, HTTP200)。fool-proof Rule 5。 |
| T118 | 菜品 coref round-3 D1 slot-写入 | #557 | 2026-06-07 | getOrCreateContext 补在 normal execute 路径(updateEntitySlot 无 session 行时静默 bail)。**已部署** v20260607_184448。**live 验: D1 现在真写 DISH 槽**(DB 实证 `{"DISH":{"id":505,...}}`,rounds 1-2 是 0 行)。**但 D2 仍全 Top5** —— 暴露第4层(续接分支跳过 coref)→ T120 收尾。多轮慢=4层 bug 层层遮挡。 |

---

## Deferred (Opus organizer 有意决定 2026-06-07)

- **Maestro 9 步原生 E2E** → DEFER。程序流已有 RN Expo Web smoke + API 全链 E2E 覆盖;Maestro 原生设备自动化边际价值低 + 装/testID/跑需真机(半阻塞)+ demo 无真实客户 = gold-plating。有真原生回归需求时再投。
- **③ 平台/VIP 营收拆分 ETL** → BLOCKED on 二维火 POS creds(gold 无渠道维度,无数据源)。用户面风险已由 T112 反投毒+abstain 兜底(诚实空状态非自信错答)。Steve 给二维火 creds 时解锁:ETL + gold 渠道维度 + 绑工具。
- **③ 平台/VIP 评价** → 工具就绪(17 个 RESTAURANT_REVIEW_* 已绑+emptyMessage),仅缺数据上传(大众点评报表),非代码问题。

## housekeeping (非任务,待清理)

- `git worktree prune` — `cretas-liushanmen-wip-close` / `cretas-liushanmen-e2e-run` 目录已消失但 ref 还在 (prunable)。
- 删远端分支 `origin/feat/restaurant-store-coref-p2a` (已 merge,0 ahead)。
- 清理 deploy worktree `cretas-deploy-543544` (本轮 #543/#544 部署用,完成后可删)。
- 主目录 `my-prototype-logistics` 落后 origin/main ~42 commit,且有 organizer 早期对 stale 台账的误编辑(未 commit,可 `git checkout -- docs/dispatch/ACTIVE.md` 丢弃) — Steve 择机 pull。

---

## 使用流程（给 Organizer 自己）

```text
1. 接到 Steve 新任务
   → 查 Scope 锁地图：有无冲突？
   → 拆解 → 写 brief 卡 → 填入 In-flight 表（ID/model/effort/orchestration/分支/scope锁）
   → 不需要 high effort 的活 → 输出 brief 卡给 Steve 自己拨(subagent effort 锁死)
   → 更新 Scope 锁地图

2. 派发 brief 卡
   → In-harness (Sonnet): organizer spawn subagent (effort 锁死, 只能选 model)
   → Out-of-harness (Composer / Steve 自开 Sonnet chat): 出卡 → Steve courier(可拨 effort)

3. PR 回来
   → 验 scope 干净: git diff origin/main...HEAD --stat
   → 🔒 risky: Opus 终审 → merge main → 从 main 部署 prod
   → 例行: Sonnet review → merge main

4. 完成后
   → In-flight 表标 ✅ done → 移到 Done 区 → 释放 Scope 锁
```
