# 交接 — 六扇门 ERP 修复批 (bug + 真遗留全做掉)

> 新 chat: `/organizer` 读台账后读本文件。自包含, 不依赖旧对话。
> 背景: 六扇门 ERP-lite 12 子项后端+UI 已全栈上线 prod(后端蓝绿 green:10020 v20260610_105847 / web-admin 8086 604assets / RN OTA c9f8c678)。E2E 三层验证完成(造数 33/34 / web 断言 8P3W / 真机报工全链 PASS)。本批 = E2E 暴露的真 bug + 真遗留, **Steve 已 GO 全做**。
> 全程上下文: memory `project_2026_06_09_liushanmen_erp_requirements` + specs `docs/superpowers/specs/2026-06-09-liushanmen/`(蓝图00/执行计划01/UI计划02/E2E计划03)。

## 修复清单 (9 项, 按依赖分组)

### 组1 · backend (一个 worktree 一个 PR 可合并做)
1. **BUG-2(中) 报工完成不联动任务状态**: RN OUTPUT 报工完成(yield phase=COMPLETED, 见 YieldReportServiceImpl 三阶段)后 `WorkProcessTask.status` 仍 PENDING、completedBy null → RN 任务列表仍显示可报。修: OUTPUT 报工提交成功时同步置 task COMPLETED+completedBy(查 submitReport/settle 联动; 注意别破坏 SEGMENT/INPUT 阶段语义)。复现: prod 批次1973(DEMO-X-66881) task 335 已报完仍 PENDING。
2. **批次 DTO 加 batchSourceType**: RN R-A 二次加工 WIP picker 依赖 `getBatchById` 返回 `batchSourceType`(SEMI_FINISHED 才显示半成品领料), 后端 DTO 没这字段(SP2 加在 ProductionPlan.sourceType)。修: ProcessingBatch 详情 DTO 透传 plan 的 sourceType(4点DTO规则)。
3. **调拨接收 actualQuantity 持久化**: TransferController receive 端点不收实收数量, RN R-C 已展示但不持久化差异。修: receive 加 actualQuantity 可选参数+落库(差异字段), 兼容旧调用(null=照发出量)。
4. **撤回列表显姓名**: ReportReversalLog DTO 只有 submittedBy/approvedBy userId → web 列表显示数字。修: DTO 加 submittedByName/approvedByName(批量查 user, 镜像 T135 loadAssigneeNames 模式, 无 N+1)。

### 组2 · web-admin (一个 worktree)
5. **报损管理页数据源核对**: E2E 时 agent 在 `/production/wastage`(老页)看到 0 条; 新页是 `/warehouse/wastage-reports`(U-SP7 #646)。核: 新页是否真对接 `GET /{fid}/wastage-reports`(SP7 WastageReportController) 且能显示 E2E 造的那条报损(id d9d59a86, DEMO-0610)。不对齐就修对接。顺手: 若老 `/production/wastage` 是餐饮专属, 工厂菜单别露(403 体验差)。
6. **入库异常页核对**: `/procurement/exceptions`(U-SP6 #644) 应能显示 E2E 造的 OVER_RECEIVE 异常(id 9a5c21a2, 已 ACCEPT_OVER 决策)。打开核一眼, 查询参数/分页不对就修。
7. **rd three-price 入口**: `/rd/quotations/three-price` 被当详情 ID 解析(显示"报价任务未找到")。修: three-price 页改成 从报价列表行进入带 ID(`/rd/quotations/:id/three-price`)或列表页加"三价对比"按钮; 直接访问无 ID 给选择列表而非空态。

### 组3 · RN (一个 worktree, 完后 OTA)
8. **BUG-3(低) 领料下拉触摸目标**: MaterialBatchPicker 批次项只有左侧 ~60px checkbox 可点。修: 整行 TouchableRipple 可点(≥44pt), 点行=选中。
9. **报损 materialBatchId 选择器**: WastageReportScreen(R-C #651) 批次手输 → 改批次选择器(复用 MaterialBatchPicker 或简化列表)。

### 组4 · demo 配置(非代码, organizer 直接做或指导 Steve)
10. **掌中宝双产出演示**: web 工序配置给掌中宝某道工序(如滚揉)配 `semiFinishedOutputCode`(work_processes.semi_finished_output_code, SP1 V20261010_03) → RN 报工 OUTPUT 即出现「剩余转半成品」栏(F1 设计)。配完真机验一道。

## 关键事实(防踩坑)
- worktree off origin/main; commit 锁 scope; PR 不自 merge; 🔒 Opus gate 后 merge; **merge 验证 MERGED 后才删分支**(本程序踩过)。
- Flyway: 若需迁移, 号必 > 当前最高 `V20261011_22`(db/flyway 目录; merge 前 `git ls-tree origin/main ... | uniq -d` 查重 + **乱序检查**: 新号必大于已应用最高, out-of-order=false 低号会被静默跳过)。
- 部署: 后端 `bash scripts/deploy/deploy-backend.sh --env prod`(蓝绿, 从 deploy worktree off origin/main; 当前活跃 green:10020); web `echo YES-PROD | bash scripts/deploy/deploy-web-admin.sh --env prod`(⚠️默认 env=test 必须显式 --env prod); RN OTA `cd ~/cretas-t160-otainfo && git checkout -B ota-tmp origin/main && cd frontend/CretasFoodTrace && npm install --prefer-offline --legacy-peer-deps && cd ../.. && source ~/.ota-env; bash scripts/ota/push-bundle.sh production android`, 验 manifest 走 ssh 47 localhost:8083 带 expo headers。
- 测试: 后端 mvnw 新测试绿+**clean-main 验证**(worktree 全套数字可能被污染环境假报, 以新增+相关测试为准); web `npm run build`; RN `npx tsc --noEmit`。subprocess ssh 在 Windows 必 `encoding="utf-8", errors="replace"`。
- prod 真客户(F006 张权团队在用), 写操作只动 DEMO 标记数据; 真机 f79c50d6(adb: C:\Users\Steve\AppData\Local\Android\Sdk\platform-tools\adb.exe), f006_moyun/123456, 批次 DEMO-X-66881 还剩 4 道工序可验。
- E2E 工具: `scripts/e2e/liushanmen-demo/seed-demo-chain.py`(幂等可重跑) + run-20260610_124749.json(已造实体清单) + cleanup-demo-chain.py。
- 路由: 执行全 Sonnet in-harness subagent(worktree); 红线(组1 触库存/任务状态)Opus gate; 别给小活开 subagent(组4 配置 organizer 自己做)。

## 建议执行序
组1(backend, 1 agent TDD) ‖ 组2(web, 1 agent) ‖ 组3(RN, 1 agent) 并行(文件不重叠) → Opus gate 3 PR → merge → 部署(后端蓝绿+web prod+OTA) → 组4 配置+真机验双产出 → 真机复验 BUG-2(任务列表不再显示已完成道) → 报 Steve。
