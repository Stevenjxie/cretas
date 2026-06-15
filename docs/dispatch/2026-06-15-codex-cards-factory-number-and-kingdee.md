# Codex 分发卡 — 六扇门主动建版本剩余项（2026-06-15）

> Steve courier 给 Codex。两张卡**独立可并行**（不同子系统、零文件重叠）。每卡自包含。
> 🔒 两卡都含红线（Flyway 迁移 / 财务脱敏）→ Codex **只做到 PR**，Opus organizer 终审 + 从 main 部署。

---

## 卡 A → Codex：原料厂号结构化（后端 + web-admin）

**目标**：把原料厂号从自由文本升级为工厂级厂商登记表（结构化），收货下拉选厂号原子落库，web-admin 提供登记表 CRUD。满足客户「回归唯一编码、领料按厂号选批次」。

**必读（worktree 内已有，已 merge 进 main）**：
- spec: `docs/superpowers/specs/2026-06-15-liushanmen-material-factory-number-design.md`
- plan（TDD task 分解，逐 task 做）: `docs/superpowers/plans/2026-06-15-liushanmen-material-factory-number.md`

**worktree**：`git worktree add -b feat/liushanmen-factory-number ../cretas-factory-number origin/main`（永远 off origin/main）
**web-admin 依赖**：`cd web-admin && npm install --prefer-offline --legacy-peer-deps`（⛔ 禁 `mklink /J` 共享 node_modules，Windows worktree 清理会掏空主 repo）

**允许改**：
- 后端：新建 `entity/material/ManufacturerRegistry.java` + repository + dto + service(+impl) + `controller/ManufacturerRegistryController.java` + `db/flyway/V20261024_16__manufacturer_registry.sql`；改 `dto/inventory/CreateReceiveRecordRequest.java` + `service/inventory/impl/PurchaseServiceImpl.java`(createMaterialBatchFromReceiveItem)
- web-admin：新建 `src/views/warehouse/manufacturers/` + `src/api/manufacturer.ts`；改 `src/views/warehouse/materials/list.vue` + 收货录入页

**禁改**：
- ⛔ `frontend/CretasFoodTrace/**`（**RN 全部不碰**，领料屏 picker 已派别 chat，撞了出事）
- ⛔ `entity/material/MaterialCodeSegment.java` 及 16 位编码体系（厂号=独立批次属性，不进编码段）
- ⛔ 任何 finance / voucher 文件（那是卡 B）

**内联规则摘要（out-of-harness 必遵，因你看不到 .claude/rules）**：
1. **Flyway 防撞**：开工前 `git ls-tree origin/main -- backend/java/cretas-api/src/main/resources/db/flyway | grep -oE "V[0-9]{8}_[0-9]+" | sort | tail -3`。预期最大 `V20261024_15`，本卡用 `V20261024_16`；若被占用顺延。**真·迁移目录是 `db/flyway/` 不是 `db/migration/`**（后者是 legacy）。
2. **实体规范**：`ManufacturerRegistry` 继承 `BaseEntity`（必含 created_at/updated_at/deleted_at）；字段 camelCase、列名 snake_case。
3. **PostgreSQL**：soft-delete 用 `deleted_at`；唯一索引用 partial `WHERE deleted_at IS NULL`。
4. **禁降级 / 诚实 null**：收货未选厂号 → 批次 factoryNumber 留 null，不编造；不返假数据。
5. **DTO 往返全 4 处**（加字段必做）：DTO 声明 + create set + update null-guard set + convertToDTO map（漏一处=静默丢弃）。
6. **防呆**：web-admin 厂号用 `el-select` 下拉非自由文本（Rule 3）；厂号 code 重复创建 → `BusinessException`(409) message 含已存在厂商名 + existingId（Rule 4 幂等）；error toast sticky（duration:0 + showClose + 原样显示后端 message）。
7. **权限**：grep 现有 `controller/MaterialBatchController.java` 的 `@RequirePermission` 取准确权限码（读=material:read 级、写=material:read_write 级），别自己发明。
8. **校验默认松**：收货厂号不强制在登记表内（登记表仅录入辅助，保历史自由文本兼容）。
9. **多租户**：所有 query by factoryId 隔离；测试断言 A 工厂不见 B 工厂数据。

**验收**：
- 后端**全量** `./mvnw.cmd test` 绿（**不是** `-Dtest`，只跑自己测试碰不到既有旧断言会漏报）。
- web-admin `npm run build` + type-check 绿；下拉渲染截图。
- web-admin 录厂商 → 收货下拉选厂号 → 批次落库带 code（非手输）→ 物料列表显示厂号。
- 多租户隔离断言通过；诚实 null 测试通过。

**并行**：✅ 与卡 B 完全独立（不同文件）。
**🔒 收尾约束**：只做到「实现 + 全量自测 + PR off origin/main」。`gh pr create --base main`；`git diff origin/main...HEAD --stat` 确认 scope 干净（无 sister 夹带）。**不自部署 prod、不自 merge** → 回 Opus organizer 终审 + 从 main 部署。

---

## 卡 B → Codex：金蝶云星空凭证导入模板（后端 + web-admin 按钮）

**目标**：新增导出端点，从已有凭证数据产出金蝶云星空 import 向导可直接导入的 xlsx（借/贷分列留空、凭证字「记」、汇率「1」），config-driven。**不做 parser，不接 API**。

**必读（worktree 内已有）**：
- spec: `docs/superpowers/specs/2026-06-15-liushanmen-kingdee-import-template-design.md`
- plan: `docs/superpowers/plans/2026-06-15-liushanmen-kingdee-import-template.md`

**worktree**：`git worktree add -b feat/liushanmen-kingdee-template ../cretas-kingdee origin/main`
**web-admin 依赖**（若做按钮）：`cd web-admin && npm install --prefer-offline --legacy-peer-deps`（⛔ 禁 mklink /J）

**允许改**：
- `entity/enums/VoucherTargetSystem.java`（加 `KINGDEE_YXSKY`）
- `service/finance/VoucherExportService.java`(接口) + `impl/VoucherExportServiceImpl.java`（新方法 `exportKingdeeImportTemplate`）
- 凭证导出 Controller（grep `VoucherExport` 的 `@*Mapping` 定位）+ 端点
- web-admin 财务导出页（加「金蝶导入模板」按钮）

**禁改**：
- ⛔ 现有 8 个导出方法的行为（只新增，不动既有凭证序时账/科目余额/利润表等）
- ⛔ 任何 material / 厂号 / RN 文件（那是卡 A）
- ⛔ **无 Flyway 迁移**（target_system 是 VARCHAR 字符串列，加枚举值天然兼容；不要建迁移）

**内联规则摘要（out-of-harness 必遵）**：
1. **复用不重造**：复用 `exportSequentialLedger` 的取数 + `resolveConfig(factoryId, KINGDEE_YXSKY)` 列名兜底（line ~965，无配置返内存默认无 NPE）+ EasyExcel 写法。
2. **云星空列布局**（11 列，spec §2.3）：`凭证字|凭证号|日期|摘要|科目编码|科目名称|借方金额|贷方金额|币别|汇率|辅助核算`。
3. **借贷分列留空**：借方分录行→贷方列写**空字符串/不写单元格**（**断言非 "0.00"**，否则金蝶可能拒行）；贷方分录行反之。
4. **常量**：凭证字 "记"、汇率 "1"、币别 "人民币"（凭证字/币别 config 可覆盖；汇率本卡为常量，不提升 config 列=避免加列迁移）。
5. **金额**：BigDecimal `setScale(2, RoundingMode.HALF_UP)`（不要 banker's rounding）。
6. **禁降级 / 诚实空**：无凭证期间 → 仅表头 xlsx，不编造行。
7. **脱敏对齐**：grep 现有凭证导出端点的 `@RequirePermission` + `@PriceSensitive` 受众，**照搬**（财务可导、sales 等脱敏/403），别自己发明权限。
8. 文件头/sheet 备注：「云星空默认版；精斗云/KIS 请联系管理员调列序」。

**验收**：
- 后端**全量** `./mvnw.cmd test` 绿。
- 测试逐条覆盖：借方行贷方列空（非0.00）/ 贷方行借方列空 / 凭证字"记" / 汇率"1" / 币别"人民币" / 金额 HALF_UP / 空期间仅表头 / config 覆盖列名生效 / 无 config 默认无 NPE。
- web-admin build 绿 + 下载按钮截图。

**并行**：✅ 与卡 A 完全独立。
**🔒 收尾约束**：只做到「实现 + 全量自测 + PR off origin/main」。`git diff origin/main...HEAD --stat` 确认 scope 干净。**不自部署、不自 merge** → 回 Opus organizer 终审 + 从 main 部署。

---

## Organizer 终审 checklist（PR 回来时 Opus 用）

- [ ] `gh pr diff <PR>` 验**远端** diff（非本地 worktree；本季踩过守卫 commit 没 push 漏过 gate）。
- [ ] 卡 A：Flyway 号无撞（origin/main 当前最大复查）；厂号未碰 16 位编码 / 未碰 RN；DTO 往返 4 处全；多租户隔离；诚实 null。
- [ ] 卡 B：无迁移；借贷留空非 0.00；脱敏对齐现有；诚实空；未动既有 8 导出。
- [ ] 集成层验证（非只代码）：前后端路径逐字匹配 / 端点权限 / web-admin 按钮真调对端点（per memory `feedback_terminal_review_verify_integration_not_just_code`）。
- [ ] 全量测试绿 + CI `java-build-test` 真绿（`gh pr view --json statusCheckRollup`，别只信 agent 自报；H2 保留字 flaky 已修别当随机）。
- [ ] merge main → 从 main 部署 prod（蓝绿）→ 核对运行 jar 含修复 → **部署后端记得也 deploy-web-admin**。
