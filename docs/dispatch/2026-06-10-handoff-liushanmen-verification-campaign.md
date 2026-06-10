# 交接 — 六扇门需求验证战役 (organizer 接棒)

> 新 chat: `/organizer` 读台账 `docs/dispatch/ACTIVE.md` 后读本文件。自包含。前任 organizer chat 已退役（上下文过长），**你是唯一 organizer**（单一前门+出货闸，merge/部署只能你做）。

## 目标 (Steve 拍板)
昨天两份会议转录的 456 条需求（`docs/meetings/2026-06-09-liushanmen/requirements-catalog.md`）→ **全部确保可用 + 完整链路验证走完**。地基已建好：追溯矩阵 `verification-matrix{,-1,-2,-3}.md`（同目录，主文档含波次计划 W0-W4 + 合并统计：实现 ✅65%/🟡17%/🔴6%，验证 V1 仅 ~1/3）。

## 已完成 (2026-06-10, 全部有证据)
- **修复批 9+1** (#655-657) + **W0 基建+W1 批1** (#658-661) + **W2 批1** (#662-666)，共 13 PR merged。
- 已部署: W1 后端 prod blue:10010 v20260610_165456（毛利红线 jar 标记已核）/ web-admin 8086（17:37 D6 责任人列已含）/ RN OTA ts1781081697491（进销存去 mock + 批次选择器）。
- 关键修复: 毛利红线真实生效(#661, 根因=服务从未注入)/半成品计价38测试(#660)/BUG-R1 producedQuantity(#662)/T-3时效锁(#666)/盘点@Value(#663)/D-6责任绑定(#665, V20261012_11)/X-6 yield_operator枚举(#664)/报工联动任务状态+撤回复位(#657)。

## 接棒时状态更新 (前任退役前补)
- #662-666 的 `--env all` 部署已完成并验证（v20260610_173623, 5 jar 标记全核, prod+test 双健康）。
- **盘点链 test env 验证已跑到第 6 步**并挖出 🔒审批死角色 bug（盘点+报损双轨全死+报损待审永远空）→ **#667 已 merge**，其 `--env all` 部署在飞。详见 `docs/audits/liushanmen/2026-06-10-stocktake-chain-test-env.md`（断言表+3 bug）。
- **你接手后**: 确认 #667 部署完（jar 标记 `STOCKTAKE_APPROVAL_ROLES`）→ 用 f006_admin 在 test 10011 对盘点单 `5a6e2bbe-0b80-4d39-99cd-5cb6f283f2a0` 补 approve→apply→DB 断言批次 MB-F006-LSM-BEEF 扣减 260→255 → audit doc 补最后两行 → F-026~028 全 V1。再顺手验报损 listPending/approve（同 #667 修复面）。
- ⚠️ 待 Steve 确认: 报损 FACTORY 轨"厂长"映射 = production_manager+超管（#667 body）。

## ⚠️ 原"第一件事"(已被上面取代, 留参考): 收口在飞部署
前任退役时 `deploy-backend.sh --env all` 在飞的处理套路:
1. 查健康: `ssh root@47.100.235.168 "curl -s -o /dev/null -w '%{http_code}\n' http://localhost:10010/api/mobile/health; curl -s -o /dev/null -w '%{http_code}\n' http://localhost:10011/api/mobile/health"`（蓝绿活跃端口可能切到 10020，看 nginx upstream 或 systemctl is-active cretas-backend{,-green}）。若部署没完成/失败 → 从 ../cretas-deploy-prod（先 `git fetch && git checkout --detach origin/main`）重跑 `bash scripts/deploy/deploy-backend.sh --env all`。
2. jar 标记核对（活跃 jar=/www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar, unzip -p + strings）: `BackdateWindowValidator`(666) / `setProducedQuantity`@ReportReversalServiceImpl(662) / `ownerUserId`@PurchaseException(665) / `yield_operator`@FactoryUserRole(664) / `monthEndThreshold`@FactoryStocktakeServiceImpl(663)。
3. **test env 盘点全链验证**（F-026~028 解锁，curl 序列在 PR #663 body；test 10011 + threshold=1）→ 结果落 `docs/audits/liushanmen/`（模板见 README）。

## 然后: 两条主线 (子 brief 已写好)
1. **V0/V2→V1 验证扫荡**（大头）: 按 `docs/dispatch/2026-06-10-handoff-v0-sweep.md` 分批派 Sonnet subagent（批A D/E → 批B H/X → 批C A/B → 批D C/F）。该文件原本写给独立 chat，你作为 organizer 把它当 subagent 批次 brief 用即可（其中"不许 merge"对 subagent 仍成立，你 gate）。
2. **R8 双栈合并设计**: 按 `docs/dispatch/2026-06-10-handoff-r8-design.md` 派设计 subagent（或你亲自框架——这是难架构判断）。产出方案选项 → Steve 拍板 → 再派实现。

## 队列 (按序)
| 项 | 触发 |
|---|---|
| W3 数字正确性: M-C9 三价算例/B-23 每盒人工/B-27 分摊 | **周五真实 BOM 数据** + 客户拍板 B-27 |
| 盘点 prod 全链回归 | 6-29 月底窗口 |
| RN 补录日期 UI（T-3 守卫报工侧 latent: #666 后端已落但 RN 未送 businessDate） | 可随时派 RN agent + OTA |
| T-3 其余路径接线（领料/计划/库存调整/品控, #666 body follow-up 清单） | 可随时 |
| 周五客户确认: 16位编码(SP8)/财务接API否(SP11 scope) | 周五会议 |
| W4 P1: BOM跟采购价/进项发票/金蝶导出/四时点报表/盐化仓报工 | 排期 roadmap |

## 防踩坑 (前任血泪, 必读)
- **⛔ 主目录工作树 STALE**(落后 origin/main 几十 merge): 代码取证一律 `git fetch origin main` 后 `git grep/ls-tree/show origin/main`。矩阵分片2 v1 因此全错重做过。给 subagent 的 brief 必须带这条（或让它在新 worktree 里干活）。
- Flyway 当前最高 **V20261012_11**；merge 前查重+乱序（`git ls-tree -r origin/main --name-only | grep flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d`）。
- prod 真客户 F006 在用: 写操作只打 test 10011/cretas_db 或 DEMO- 标记数据。psql: `PGPASSWORD=cretas123 psql -U cretas_user -h 127.0.0.1 -d cretas_prod_db`（47 上）。
- merge 验证 MERGED 才删分支; gate 验远端 `gh pr diff` 非本地 worktree; 部署后核 jar 标记。
- web deploy 默认 env=test, **必须显式 `echo YES-PROD | bash scripts/deploy/deploy-web-admin.sh --env prod`**。
- OTA: `cd ~/cretas-t160-otainfo && git fetch && git checkout -B ota-tmp origin/main && cd frontend/CretasFoodTrace && npm install --prefer-offline --legacy-peer-deps && cd ../.. && source ~/.ota-env && bash scripts/ota/push-bundle.sh production android`。
- G1 守卫只覆盖 OUT{SECONDARY_CONSUME,TRANSFER_OUT}: 任何人新增 OUT sourceType 必须同步 G1（#662 caveat）。
- #656 异常页接口曾用错决策枚举字面量, #665 已纠正为 ACCEPT_SHORT/REQUEST_RESUPPLY——审 web 改动时留意这类"interface 凭猜"问题。
- 验证证据必须落 `docs/audits/liushanmen/`（模板+规则在该目录），否则不算 V1——这是本战役的核心纪律。
