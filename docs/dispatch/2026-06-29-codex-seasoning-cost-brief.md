# Codex Brief — 调料成本真值 headed 测试(F006)

> 自包含 brief(Codex out-of-harness,无 .claude/rules,所需规则已内联)。复制全文给 Codex。

---

你是 GPT-5.5·Codex,在仓库 `C:\Users\Steve\my-prototype-logistics`(Cretas 食品溯源,Java 后端 + Vue3/Element Plus web-admin)做**纯 headed Playwright 测试**。任务:补齐成本准确性链上唯一缺的一段 —— **调料成本真值**。

## 背景(为什么这个任务存在)
成本链里 原料/人工/分摊/继承/cost-analysis/成本页 都已核过准确(见 `生产录入bug.md` §9-15)。唯独**调料成本**没核过:实查 F006 全部 8 个卤味产品(含真客户 叮咚猪舌/卤猪蹄)的 `/bom/recipes/by-product/{id}/seasoning` **全部 0 条调料明细** → `computeSeasoningCost` 恒走缺配置分支计 ¥0+warning。所以**没有现成 调料配方 可复用,必须先 headed 配一个**,这正是本任务的核心("从配置配方规则开始"的精神)。

## 环境
- web-admin(prod):http://139.196.165.140:8086,工厂 F006,账号 f006_admin / 123456
- 测试目录:`tests/e2e-yield-mixed-sku/`(.mjs,ESM,node v22,@playwright/test 已装)
- 跑法:`E2E_USERNAME=f006_admin E2E_PASSWORD=123456 PLAYWRIGHT_PORT=9259 PLAYWRIGHT_CHAT_ID=codex-seasoning node tests/e2e-yield-mixed-sku/<file>.mjs`
- **复用** `tests/e2e-yield-mixed-sku/_headed-helpers.mjs`:`startHeaded`(login+api+shot+helpers) / `setupSkuAndBom`(UI 建 SKU + 配 bom_items + API 回读) / `makeHelpers`(selectByText/selectByKeyboard/fillNum/activePane/gotoTab/waitSaved)。先读它 + `headed-labor-cost.mjs`(最近一个 deep 例子,人工成本核法可借鉴)。

## 调料成本机制(后端事实,已查证)
`ClerkProcessEntryServiceImpl.computeSeasoningCost`(约 line 744):
1. 只对 **`isSeasoningStep(st)`**(即 `st.processCategory == "SEASONING"`)的工序步算调料成本。**这是触发前提** —— 熟制步必须以 SEASONING **成本类别**进来(注意:产品工序的"显示类别"是 加工/前处理/包装,跟成本类别 SEASONING 是**两回事**,需查清 SEASONING 怎么落到 StepEntry.processCategory 上 —— 看前端 `web-admin/src/views/production/components/processSheet/PROCESS_SHEET_CONFIG.ts` + `ProcessDataTable.vue` 怎么给 step 带 processCategory,以及后端 StepEntry 怎么取)。
2. 读 `findByFactoryIdAndProductTypeIdAndIsCurrentTrue` 的 BomRecipe + `bom_seasoning_items`,调 `RecipeCostCalculator.compute(subsequentPotRatio, bomSeasoning, injectionRawKg, potRawKgs)` → `SeasoningCost.total`。
3. 无调料明细 → warning「未设置调料配方,调料成本暂记0」+ 返 0(缺配置不伪装0 防呆)。
4. 锅数 potCount:`buildPotRawKgs` —— **N>1 锅必须逐锅填 potRawKgs**(否则后端抛 400「N 锅生产必须逐锅填写原料投入量」),N=1 用整批投入量。

调料配方 API(BomRecipeController):
- `GET /F006/bom/recipes/by-product/{productTypeId}/seasoning` —— 取当前 is_current 调料配方(注射段 + 熟制段明细 + 锅序参数)。
- `PUT /F006/bom/recipes/{recipeId}/seasoning` —— 全量替换调料配方(**仅 DRAFT 状态**)。
- `POST /F006/bom/recipes` 建 recipe;`POST /{recipeId}/activate` 激活(设 ACTIVE + is_current);`POST /{recipeId}/calculate-cost` 算成本(**可做 oracle**)。
- ⚠ 时序坑:PUT seasoning 仅 DRAFT,但 computeSeasoningCost 读 is_current=TRUE。需查清:建 recipe 后是否自动 is_current?activate 后还是不是 DRAFT(不能再 PUT)?正确顺序大概是 建(DRAFT)→ PUT seasoning(DRAFT)→ activate(ACTIVE+is_current)。读 controller 确认。
- 调料配方 UI:`/production/bom` 页「创建配方」→ recipe header → **调料配方 tab**(注射段/熟制段 + 锅序参数)。`web-admin/src/views/production/bom/index.vue`。

## 任务
1. **调研钉死前提**(verify-first,别硬造):
   - SEASONING 成本类别怎么落到熟制步的 StepEntry.processCategory(前端发 or 后端按工序配置派生?)。若熟制步现在不是 SEASONING 成本类别 → 查怎么 headed 配成 SEASONING(工序配置页?),或选一个熟制步本就是 SEASONING 类别的产品/模板。
   - 建 recipe → PUT seasoning → activate 的正确时序(读 BomRecipeController)。
2. **headed 配一个 调料配方**:用 `setupSkuAndBom` 建新 SKU(复用含 熟制(卤制) 的工序链,已知模板链:拆包/修油/滚揉/焯水/去舌胎膜/熟制(卤制)/气调),然后**headed 在 创建配方→调料配方 tab** 配一个最小调料配方(熟制段 1-2 条调料,单价/用量已知,锅序参数 subsequentPotRatio 已知)。配完 API 回读 `/seasoning` 确认 items 落库。
3. **headed 跑到熟制步报工**:逐道录入跑完整链到 熟制(卤制),熟制步填 potCount=1(单锅,投入量即 potRawKgs)+ 投入/产出 + 保存。
4. **核调料成本真值**:
   - oracle:用 `POST /bom/recipes/{recipeId}/calculate-cost`(同 RecipeCostCalculator)或按配方手算(Σ 熟制段调料 用量×单价,按 injectionRawKg/potRawKgs)。
   - 实测:熟制批的 SEASONING 成本桶(读 cost-analysis 的 material/seasoning,或 ProductionReport costCategory=SEASONING 行,或 batch 的 rowTotalCost 减去原料+人工+继承)。
   - **断言**:实测调料成本 == oracle(±0.01);且配方配好后 warning **不再**报「未设置调料配方」(从缺配置→有配置的正向证明)。
5. **(可选,加分)缺配置不伪装0 对照**:另一个无调料配方的 SKU 熟制步 → warning「未设置调料配方,暂记0」+ 调料成本 0(防呆负向)。

## 验证口径(plan-scoped,避坑)
- ⚠ **`?productionPlanId=` / `?productTypeId=` query 过滤被后端静默忽略**(返回跨计划/全工厂数据)。取本计划批**必须**用 path-scoped 端点 `GET /production-plans/{planId}/process-sheet/inventory/yield-card`,别信 query 过滤。
- ⚠ **测试自污染防护**:原料批次发现必须客户端排除计数单位包材(`!/件|个|只|pcs/i.test(unit)` 且 `/kg|g|千克|克|斤/i.test(unit)`),否则把吸塑盒当原料投产污染 WIP。
- 三方核对:oracle(配方手算/calculate-cost)== 实测(cost-analysis/报工)== 渲染(成本页/出成率卡可选)。

## 铁律(内联,Codex 无 .claude/rules)
1. **Headed 强制**:`headless:false`,viewport 1920×1080,args `['--lang=zh-CN','--font-render-hinting=none','--remote-debugging-port='+PORT]`。截图存 OUT。
2. **prereq 缺失 fail-fast**,不静默跳过记 PASS。深度 deep(真填+真提交+toast+回读+数字核对),≥1 断言能在后端 500/数字错时 FAIL。
3. **逐道录入 UI**:抽屉 `.el-drawer__body`,工序 tab `.el-tabs__item`,**必 scope 到可见 pane `.el-tab-pane:visible`**(非活动 tab 元素 hidden 会误点)。保存 toast 可能是 warning「已保存(含提示)...」也算成功(检查含"已保存/成功"且不含"失败")。
4. **模态/页级 el-select headed**:`getByRole('option')` 或 `.el-select-dropdown__item:visible` 点击;allow-create 输入框要**点 option 不是回车**;BOM 页产品选择以**网络回读**(`/bom/items/{ptid}` 或 `/seasoning`)作真实选中依据,别信空 input 显示值(见 _headed-helpers setupSkuAndBom 已实现的 pattern)。
5. **安全提交**:改前 `git status --short`;只提交你的文件 `git commit -m "..." -- <files>`(不 `git add .`);msg 末尾加 `Co-Authored-By: GPT-5.5 Codex <noreply@openai.com>`。**别动 prod 部署/DB/后端代码**(纯测试)。
6. 每脚本独立 SKU(名带时间戳),末尾输出 `*-result.json` + 截图,状态 PASS 才算过。

## 交付
- `tests/e2e-yield-mixed-sku/headed-seasoning-cost.mjs`(+ 可能扩展 `_headed-helpers.mjs` 加 `setupSeasoningRecipe`),prod F006 headed 全绿:调料配方 headed 配成 + 熟制步真值 == oracle + 缺配置负向对照。
- 调研结论写进脚本头注释或 `生产录入bug.md §17`(SEASONING 成本类别怎么配、recipe 时序)。
- **顺带标给 Steve**:F006 真客户产品(叮咚猪舌/卤猪蹄)都缺调料配方,生产侧调料成本普遍未计入 —— 是否要给真产品补调料配方(产品数据事项,非本测试范围)。

完成先攻任务 1 的两个前提(SEASONING 类别怎么落 + recipe 时序),通了再配配方 + 核真值。
