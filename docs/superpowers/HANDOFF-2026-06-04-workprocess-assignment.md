# Handoff — 工序-小组长分配 Phase 1 (开建用)

**日期**: 2026-06-04
**状态**: spec + 计划 + 审计 全部就绪, **build 未开始**。下次 session 照本文 + 计划直接开建。

---

## 0. 一句话目标
每个小组长登录只看/报自己负责的工序;工序责任在 web-admin 配默认(产品级,过夜延续),批次 spawn 自动按工序归属到对应小组长,**后端强制归属鉴权**。代报工/早会改派/RN配置 = Phase 2 defer(客户铁律"前期越简单越好,先落地")。

## 1. 先读这些(按序)
1. **计划(主文档, 8 Task TDD, 直接执行)**: `docs/superpowers/plans/2026-06-04-workprocess-teamleader-assignment.md`
2. **spec(v2, 审计修订, 背景+决策)**: `docs/superpowers/specs/2026-06-04-workprocess-teamleader-assignment-design.md`
3. **memory**: `project_2026_06_04_workprocess_teamleader_assignment.md` + `feedback_rn_web_alert_invisible_yield_report.md` + `feedback_fg_complete_kg_equiv_and_prod_db_owner.md` + 规则 `worktree-and-main-only-deploy.md` / `database-entity-sync.md` / `fool-proof-design.md`

## 2. 开建前必做(worktree 基线)
**当前 worktree `cretas-e2e-replica` 落后 origin/main 9 commit, 不能在它上面建。** 开全新 worktree off **当前** origin/main:
```bash
cd C:/Users/Steve/my-prototype-logistics
git fetch origin main
git worktree add -b feat/workprocess-teamleader-assignment ../cretas-wpassign origin/main
cd ../cretas-wpassign
# 把 spec+plan 拷进来(它们提交在 e2e 分支 commit 20481c671, 不在 origin/main)
git show 20481c671 -- docs/superpowers/specs/2026-06-04-workprocess-teamleader-assignment-design.md > /tmp/spec.md  # 或直接 cp 现有文件
```
(spec/plan 两个 md 直接从 `C:/Users/Steve/cretas-e2e-replica/docs/superpowers/{specs,plans}/2026-06-04-workprocess-*` 拷过去即可。)
npm: `cd frontend/CretasFoodTrace && npm install --prefer-offline --legacy-peer-deps`(别用 mklink junction)。

## 3. 审计抓的必修(已并入计划, 实现期别忘)
- **M3[安全, 必做]**: 报工三链都加归属守卫 —— `YieldReportServiceImpl.submitReport` + `recordMaterialInput` + `ProcessWorkReportingController.submitNormalReport`: `task.assignedTo` 为 null 或 ==登录userId 才允许, 主管豁免; `targetWorkerId` 仅主管可传(防伪造报工人)。
- **C1[关键]**: 本仓库 **`@PreAuthorize` 是 NO-OP**(SecurityAutoConfiguration excluded)→ 限主管必须用自定义 **`@RequireRole`**, 不是 @PreAuthorize。
- **M1**: spawn 现在根本不写 assignedTo → 加; 报工列表"该批全 null 则不过滤显示全部"兜底(否则 f006_worker1 死锁)。
- **M2**: RN 调的 `listByBatch` 不吃 assignedTo → 给它加参数; 过滤点在 `YieldStepReportScreen` tasks 加载, 不是选批次屏。
- **M4**: 迁移加 `to_regclass('public.product_work_processes')` 守卫(entity-only 表)。
- **M5**: 迁移号 `git ls-tree origin/main .../db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | tail` 取最高+1(现 ≥V20260919_02), 必查 origin/main。
- **M6**: responsibleWorkerId 改 entity/DTO/create/update/toDTO **五处** + 清空 sentinel(`-1L`)。
- **C6**: 组长只看自己 → done 卡永不可达 → 完工入库归主管(不过滤)。
- 确认正确(别改): 责任人列不加 FK; 主库 cretas 无需 GRANT/RLS(区别 smartbi); assigned_to=BIGINT=Long; 一人多道天然支持。

## 4. 已建好的资源(直接用)
- **4 个小组长账号 prod F006**(role operator, 密码 123456): 莫云=**1615**, 魏振江=**1616**, 徐师傅=**1617**, 永珍=**1618**。f006_worker1=1311。
- prod 网关: `http://139.196.165.140:8086/api/mobile`(本地可达 + 对 localhost:3010 放行 CORS)。活跃端口蓝绿轮换(本次 10020 green)。
- prod DB(读写核查): `PGPASSWORD=cretas123 psql -h 127.0.0.1 -U cretas_user -d cretas_prod_db`(SSH root@47.100.235.168;cretas_user 是真 owner,cretas 是受限用户读不了业务表)。
- Maven: `C:/tools/apache-maven-3.9.6/bin/mvn.cmd`。

## 5. RN 报工 验收要点(headed, playwright-rn MCP)
- Expo: `npx expo start --web --port 3010`(必须 3010 = CORS);登录 RN 用小组长账号。
- 验收: 配默认(猪舌 修油→徐/滚揉→莫云/焯水·熟制→魏振江/装盒→永珍)→ 建批次 spawn → 查 DB 各道 assigned_to=对应组长 → 莫云登录只见滚揉、魏振江只见焯水/熟制 → **反向安全: 莫云 API 报魏振江的焯水 → 403** → **回归: f006_worker1 全null批次 → 看到全部道** → 主管完工。
- RN headed 已知坑: Alert.alert 不渲染(已用 AppDialog 替, 测确认框用 `app-dialog-btn-N` testid); 点击常 5s 超时但实际生效; 浏览器偶发 about:blank 需重导航。

## 6. 收尾遗留(开建前/中处理)
- **RN Alert/clip 修复**(commit `5ee0ca754` 在 e2e 分支): 独立有用的 UX 修, 需另起小 PR 进 main。**注意**: feature 的 Task7 会改同一个 `YieldStepReportScreen` 并清理代报死代码(operators/reporterFields/3处注入)—— 若 Alert/clip 修复先 merge 进 main, feature worktree off main 就自带它们; 若没 merge, Task7 要在干净 origin/main screen 上重新加 Alert→appAlert + minWidth:0(那时屏上没有死代码, 更干净)。**建议: Alert/clip 先 cherry-pick/PR 进 main, 再开 feature worktree**。
- **prod F006 残留测试数据**(本轮 RN 演示): 批次 1940(完工 FG 513盒)+ 1941(6.3 修油/滚揉)+ 其 SO/plan + 发货单 f2020d0a。Steve 未决定是否清。要清走定向 DELETE(参考 e2e-replica/cleanup.sql 模式, cretas_user, 按 batch_id/plan/SO 范围)。
- `cretas-e2e-replica` worktree + `e2e-replica/` 驱动+照片 = 忠实复刻的脚本/产物, 保留作记录(未 commit, 不进 product code)。

## 7. 执行方式
计划是 8 Task TDD。用 **subagent-driven-development**: 每 Task 派 fresh subagent 实现 + spec-review + quality-review, Task 间把关。后端 Task1-5 串行(互相 import), web Task6 / RN Task7 接口定后可并行, headed Task8 串行末尾。完成 merge 进 main(`git diff origin/main...HEAD --stat` 确认 scope 干净), 从 main 部署 prod(后端迁移 + web + RN)。
