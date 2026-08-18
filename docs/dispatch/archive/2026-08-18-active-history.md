# 2026-08-18 ACTIVE 完成记录

## SOP-RAG-SYNC-20260818

- 状态：`merged`。从上次基线 `dd2fc14f89b697d23bb744b63a7cea1f9e1a7299` 审计到最终 `origin/main@3b1fc59094fc574cfc4651a0d18f9278c3927bb5`，工厂与餐饮均有用户可见语义变化；实现 PR #2815 合入 `c2868c8d4e1aaaa2e11c66d15489d314360b183f`，餐饮读路径 #2816 随后合入后由跟进 PR #2817 补齐门店×时段同源排序口径。
- 同步：工厂更新报工任务/工序幂等、同段“本段已记录”、驳回反向冲销、单位字面与标签坐标复核；餐饮更新统一门店目录、渠道/损耗/领料/盘点覆盖声明、门店×时段首段/表格第一行/显式排序同源和盈利回答边界。没有新增或修改 `MANUAL_SOURCES` 注册关系。
- 验证：首批 SOP 合同 `120 passed`，跟进时段实现与完整合同 `139 passed`；`py_compile`、HTML/内联 JavaScript parse、`git diff --check` 与两轮 PR Python/Web/secret/artifact CI 全部通过。两次 exact-main release-preflight 通过；production migration dry-run 与正式 runner 均为 `142 skipped / 0 pending`。
- 发布：最终从 clean exact main 发布 Python，`cretas-python`/Embedding/PostgreSQL 健康且 `NRestarts=0`；统一 Web 回执 `cretas-1787016885-8808.json` 为 `RELEASE_FINAL_STATUS=deployed`、Java 未选择，Web 备份 `web-admin.bak.20260818_093638`。两条 SOP 与 3 个餐饮 docs 以 `.incoming` 传输哈希校验后备份并原子替换，备份后缀 `sop-20260818T093700+0800`。
- 页面：AI Assist / 工厂 SOP / 餐饮 SOP 的 repo、服务器与公网 SHA-256 分别为 `b62a3e43d6e8dfb34b3dbe28c1835a9293e6fa039078e1a7d48c8b48b255fa88`、`9371203c07e8e01fcc9951b0df20397a0f6a27f6695f86af90d1f71f587890df`、`7268a987ff4a16cbf863d1da8523a933e3f0744b3f6f0e7d684c0ec5cca1af83`；浏览器分别确认工厂建议 30 与餐饮建议 29“门店拆分、覆盖与排序”。
- RAG：仅重建 4 个变化 source；F006 `71` 块，餐饮 full/product/metrics `86/249/176` 块，创建时间均为 2026-08-18 09:38–09:39，关键断言命中，正式 source 与注册表一致，`.NEW=0`。最终真实回答工厂 `3/3` 只引用 F006 source，餐饮 `4/4` 只引用 3 个 registered source；固定 BOM/Workflow、单菜毛利、门店覆盖/排序和导览不代算均通过。
- 边界：生产 ERP 业务写入 `0`；无阻塞。保留工作分支/worktree，未执行清理；本归档释放本任务全部 scope 锁。

## UX-LIUSHANMEN-LABEL-QC-REVIEW-20260818-001

- 状态：实现与候选验收完成；随最终合并释放 Label-QC Web scope，生产 Web 发布已获用户授权并在 exact-main 阶段执行。
- Base SHA：`7ed9877dbad5669bce57169dd38689d0e289a1f7`。
- 范围：仅 Web Admin Label-QC 审核工作台、纯函数与目标测试、UX 规格和 dispatch；Java、Python、模型、阈值、数据库、生产标注与原图均未修改。
- 交付：盒子/白标/彩标三层独立显隐；照片内文字默认移除；深青/深红/深紫实线框；白标/彩标横排实线辅助线；鼠标锚点缩放；`Q/W/E`、`A`、`Space/Enter`、`Backspace` 快捷键；短屏桌面审核抽屉保持 96% 宽。
- 语义：实物缺标选择“实物缺标”且不画假框；AI 漏框使用“补白标框/补彩标框”；遮挡无法判断选择“看不清”。草稿切图保留，但只有提交整单才写入服务器。
- 重复框：不再叠画 AI 参考框与已接纳最终框；同类别明显重叠的 2 个或 3+ 个 AI 提议按连通组自动收敛，只保留较完整的大框并记录其余 AI key 为拒绝提议；人工已审核对象不做自动去重。
- 验证：Label-QC Vitest `26/26`；`vue-tsc -b` 通过；Playwright 模拟人工审核闭环 `1/1`；1280×720 本地浏览器确认抽屉宽 `1228.8/1280`、最终框 `9`、可见 AI 参考框 `0`、照片内文字标签 `0`、白/彩辅助线各 `1`，图层联动归零正确；滚轮锚点归一化误差小于 `0.000001`；浏览器验收仅使用本地 mock，生产业务写入 `0`。

## AUDIT-RN-TRANSFER-ORPHAN-20260818

- 状态：`review` 候选完成；随本提交归档并释放 RN 库存调拨孤儿 scope。
- Base SHA：`eabd408f4da732c895a24fe1b066ca9a08cc5342`。
- 发现：RN 当天已摘掉全部 `WHInventoryTransfer` 真实入口，但危险原型屏仍注册在 Stack；其提交只更新批次 `storageLocation`，不使用调拨数量、不生成调拨单且绕过 `TransferServiceImpl`，旧恢复状态或内部深链仍可进入。
- 交付：删除 595 行原型屏、Stack route/import、ParamList 字段和旧 i18n 迁移条目；源码契约测试同时钉住文件、route、类型和迁移残留均不存在。保留后端 Transfer API、Web 正式调拨、RN 调拨接收，以及库存行操作“请在网页端「库存 - 调拨」办理”的下一步提示。
- UX Flow：仓管员不再看到或进入会假成功的移动写路径；在真实仓库、单位、数量和幂等契约完成前不恢复移动端调拨。
- 验证：目标 Jest `1 suite / 8 tests` 通过；`npx tsc --noEmit --skipLibCheck` 通过；`git diff --check` 通过。未触及 Repository/Entity/JPQL/`@Query`/Flyway，JPA startup gate 不适用。
- 边界：`NOT_DEPLOYED`；生产业务写入 `0`；未修改 LIUSHANMEN、后端调拨或 Web 调拨。
