# BOM 统管配方+锅序 — U8 灰度 cutover 运行手册 (GO-gated)

**状态**: 🔴 等 Steve 明确 GO 才在 prod 跑迁移。test/DEMO 验证不需 GO。
**分支**: `feat/bom-recipe-merge` (worktree `../cretas-bommerge`)
**Spec/Plan**: `2026-06-24-bom-recipe-unification-design.md` / `-unification.md`

---

## 1. 已交付 (U1–U7 + 5 轮审计, 全 merge 前就绪)

| 层 | 内容 | 验证 |
|---|---|---|
| U1 schema | `V20261027_12` — bom_recipes 加锅序列 + bom_seasoning_items 表 (FK VARCHAR(191)) | 编译 + 类型对齐 |
| U2 实体 | BomSeasoningItem / BomRecipe 扩 / SeasoningLine 契约 / repo | 201 BOM 测试无回归 |
| U3 迁移 | product_recipes→BOM (幂等 含软删/dryRun/factory_super_admin) | 5 单测 |
| U4 读路径 | RecipeCostCalculator 读 SeasoningLine (算法不变) + computeSeasoningCost BOM优先+legacy回退 | **parity 测试 + 0回归** |
| U5 API | seasoning CRUD (DRAFT-gated/跨租户/全量替换) | 5 单测 |
| U5a 版本 | buildSnapshot 纳入锅序+调料 (走版本/ECN) | version-approve 测试 |
| U6/U7 前端 | 调料配方 tab 切 BOM API + 缺BOM防呆跳转 + 克隆→改→激活 闭环 | vue-tsc + 9 vitest |

**测试总计**: 252 backend + 9 vitest + web production build ✓。**分支 scope 干净** (21 文件, 0 sister 污染)。

**5 轮审计修复**: R1 clone带配方(critical)/snapshot repo-fetch/幂等含软删 · R2 404静默/空值防呆/克隆pin+激活/返回tab · R3 缺配方warning指引 · R4 **U7 NO_BOM检测修复(critical)**/0成本可辨识/消息不吞 · R5 isNotFoundError 抽函数+单测。

---

## 2. cutover 步骤 (严格按序; 🔴 = 需 Steve GO)

### Step 0 — 部署代码 (test 先)
代码是**纯加法 + 读路径带 legacy 回退**, 部署后未迁移 SKU 自动走 product_recipes (零回归)。
```bash
# test env (10011) 先验
git checkout main && git pull              # 先 merge feat/bom-recipe-merge → main
./scripts/deploy/deploy-backend.sh --env test
./scripts/deploy/deploy-web-admin.sh --env test   # 若有 test 通道; 否则 prod web 灰度
```

### Step 1 — test 迁移 dry-run (只读, 不需 GO)
```bash
# 登录拿 token (df_admin / DEMO_FACTORY — 绝不碰 F006/LIUSHANMEN)
TOKEN=$(curl -s -XPOST http://139.196.165.140:8086/api/mobile/auth/unified-login \
  -H 'Content-Type: application/json' -d '{"username":"df_admin","password":"123456"}' | jq -r .data.accessToken)
# dry-run: 看报告 (migrated / skippedNoBom / skippedAlready + per-SKU)
curl -s -XPOST "http://<test>/api/mobile/<DEMO_FACTORY>/bom/recipes/migrate-from-product-recipes?dryRun=true" \
  -H "Authorization: Bearer $TOKEN" | jq .
```
**核对**: `skippedNoBom` 列出的 SKU = 有配方无 BOM (需先建 BOM, 不自动建)。记录 migrated 数。

### Step 2 — test 真迁移 + 逐 SKU 0-diff (不需 GO)
```bash
curl -s -XPOST ".../migrate-from-product-recipes?dryRun=false" -H "Authorization: Bearer $TOKEN" | jq .
```
**0-diff 验证** (核心 🔒): 对每个 migrated SKU, 比对迁移前后 `computeSeasoningCost` 输出。
方法 A (推荐, 直接比 bom_seasoning_items vs recipe_ingredients):
```sql
-- 每个迁移 SKU: bom_seasoning_items 应与 recipe_ingredients 逐行等值 (section/dosage/p1/p2/countInSeasoning)
SELECT pr.product_type_id, ri.section, ri.dosage_per_kg_g, ri.price_source1, ri.count_in_seasoning,
       bsi.dosage_per_kg_g AS bsi_dosage, bsi.price_source1 AS bsi_p1, bsi.count_in_seasoning AS bsi_cis
FROM recipe_ingredients ri
JOIN product_recipes pr ON pr.id = ri.recipe_id AND pr.status='ACTIVE'
JOIN bom_recipes br ON br.factory_id=pr.factory_id AND br.product_type_id=pr.product_type_id AND br.is_current
LEFT JOIN bom_seasoning_items bsi ON bsi.recipe_id=br.id AND bsi.section=ri.section AND bsi.name=ri.name AND bsi.deleted_at IS NULL
WHERE pr.factory_id='<DEMO_FACTORY>'
ORDER BY pr.product_type_id, ri.section, ri.seq;
-- 任一行 bsi_* 与左侧不等 或 NULL → 停, 查迁移.
```
方法 B (端到端): 对一个已知 SKU (SP-A 基线 0.55/0.34 / M67) 跑一次报工 → 比对调料成本前后一致。

### Step 3 — 🔴 **Steve GO 关卡**
把 Step 2 的 0-diff 证据 (per-SKU 等值 + skippedNoBom 清单) 给 Steve。**Steve 明确说 go 才继续。**

### Step 4 — 🔴 prod 迁移 (LIUSHANMEN/F006 真客户, 仅 GO 后)
```bash
git checkout main && git pull
./scripts/deploy/deploy-backend.sh --env prod      # 蓝绿
./scripts/deploy/deploy-web-admin.sh --env prod    # printf 'YES-PROD\nYES-PROD\n' | ...
# 备份 → dryRun=true 核对 → dryRun=false → 逐 SKU 0-diff (同 Step 2 SQL, factory=LIUSHANMEN)
```
**逐 SKU 0-diff 不通过任一条 → 立即回滚** (product_recipes 只读还在, 读路径 legacy 回退仍生效)。

### Step 5 — cleanup (灰度稳定 1 个回归周期后, 单独 PR)
删 product_recipes 读回退段 + (可选) drop product_recipes 表。**删前必确认 skippedNoBom==0** (否则那些 SKU 删表后 → 真 0 成本)。

---

## 3. headed E2E 脚本 (cutover 部署后跑; 本轮因 prod 未部署 + 浏览器被占未跑)

DEMO_FACTORY (df_admin) web-admin headed (per `playwright-headed-mode.md`, headless:false / zh-CN / 1920×1080):
1. 登录 df_admin → 生产 → BOM 配方 → **调料配方 tab**。
2. 选一个有 BOM 的产品 → 应加载锅序参数 + 注射/熟制表 (DRAFT → 可编辑)。
3. 选一个**无 BOM** 的产品 → 应显 EmptyState「该产品尚未建立 BOM」+「前往创建 BOM 配方」按钮 (验 R4 NO_BOM 修复)。点 → 跳 materials tab → 建 BOM → ReturnBanner 返回 recipe tab。
4. 选一个 **ACTIVE** BOM 的产品 → 只读 + 克隆 banner → 点克隆 → 变 DRAFT 可编辑 + 「正在编辑克隆草稿」提示 → 改调料 → 保存 → 点**激活此版本** → 确认框 → 激活成功 → 回到当前 BOM。
5. 保存时留一行空「每kg用量」→ 应前端拦「请补充」(不发 400)。
6. 截图存档 (中文无方块 / fullPage)。

---

## 4. 待 Steve 决策 (非阻塞, cutover 前确认)

1. **成本读 is_current(含DRAFT) vs ACTIVE-gating** (audit Issue 4): 当前读 is_current (与迁移/tab/"定义即生效"一致, 0-diff 不受影响)。若要更保守 (只 ACTIVE BOM 影响成本) → 改 `findBy...IsCurrentTrueAndStatus(ACTIVE)`。倾向保持 is_current, 等你拍板。
2. **U7 SP-F process-sheet 一键跳转** (后端 warning 已可操作指引 + 前端 sticky toast 已显; 一键跳转留 post-merge polish, 不碰 prod-critical 提交流)。
3. **ProductRecipeController 写端点弃用** (前端已不调; 留作 rollback。灰度后随 product_recipes cleanup 一起处理)。

---

## 5. 回滚

任一步异常: prod jar 蓝绿切回上一版 (读路径 legacy 回退 → 自动用 product_recipes, 成本不变)。
product_recipes 全程只读保留, 迁移幂等 (含软删计数), 可重跑。
