# 验证 Audit — 盘点全链 (test env) + 同因审批死角色修复

- **验证对象**: 矩阵 F-026/F-027/F-028（盘点发起/录入/差异审批过账）+ 衍生 F-019~024 报损审批路由
- **方法**: API（47 上 curl localhost:10011, f006_admin）+ DB 断言（cretas_db）
- **执行人/日期**: Opus organizer, 2026-06-10 晚
- **前置**: #663 盘点阈值 @Value 化; test env `.env.test` 加 `CRETAS_STOCKTAKE_MONTH_END_THRESHOLD=1` + 重启 cretas-backend-test（agent 假设的 Spring `test` profile 不生效——test 进程不跑该 profile，env var 路径才生效, 已验）。

## 断言与结果

| # | 断言 | 预期 | 实际 | 结论 |
|---|---|---|---|---|
| 1 | 6-10 发起盘点 (threshold=1) | 200 + INITIATED | 200, id 5a6e2bbe, **账面快照 9 行** | ✅ F-026 V1 |
| 2 | 旧约束回归 (改 env 前) | 409 月底约束 | 409 "下次可发起日期 2026-06-29" | ✅ 约束本体正确 |
| 3 | 录入实盘 PUT items (盘亏5 + 按账面) | 200 幂等 | 200 ×2 | ✅ F-027 V1 |
| 4 | diff-preview | 差异行含盘亏行 | 200, diffLines 含 MB-F006-LSM-BEEF | ✅ F-028(差异) V1 |
| 5 | submit | PENDING_APPROVAL | 200 | ✅ |
| 6 | approve (factory_super_admin) | 200 | 初跑 **403** (🔴 BUG → #667+#668) → 修复部署后 **200 已审批** | ✅ |
| 7 | apply 过账 + 库存扣减 | APPLIED + 批次 260→255 | **200 "库存差异已调整", final=APPLIED, DB receipt_quantity=255.00** (v20260610_190408) | ✅ F-028 V1 |

## 发现的 bug（全部已修/已记录）

1. **审批死角色码（盘点+报损双轨, 🔒权限）**: service 比对大写虚构码（"FINANCE"/"FACTORY_SUPER_ADMIN"/"PLATFORM_SUPER_ADMIN"/"FACTORY_MANAGER"），request role 是小写真实码 → 盘点审批/报损双轨审批 403 死路 + **报损 listPending 永远空**（按虚构 approverRole 串匹配）。修 **#667**（merged）: 真实码集合 + 大小写兜底 + listPending 改按角色推导轨道。⚠️ 待 Steve 确认: FACTORY 轨"厂长"映射 production_manager+超管是否符合六扇门组织。
2. **test 库数据漂移**: cretas_db 有 8 行 material_batches `status='ACTIVE'`（枚举早已删除该值）→ 盘点快照 JPA 反序列化 400。已 UPDATE→AVAILABLE（test 库, prod 干净已核）。
3. **#663 的 test profile 假设错误**: application-test.properties 不被 test 进程加载, 需 env var（已落 .env.test, 见前置）。

## 证据
- run 命令与响应在 organizer chat 记录; 盘点单 ST-202606-A97B9040 (cretas_db)。
- #667 测试 35/35 绿（盘点 13 + 工作流 6 + 报损 16）。

## 状态
**F-026/F-027/F-028 全链 V1**（发起→录入→差异→审批→过账→库存扣减 DB 实证）。prod 全链回归仍排 6-29 月底窗口（threshold=29 prod 默认未动）。

## 追加发现 #4（hotfix #668）
#667 初版 findPendingByTrackTypes 用嵌套枚举 HQL 字面量 → Hibernate 6 启动期校验失败 → 应用起不来。**prod 蓝绿健康闸拦住未上线**（green 持续服务）；test crash-loop 用备份 jar 恢复。修 #668: 派生方法名（零 HQL）+ **WastageReportRepositoryTest (@DataJpaTest)** 回归网——此前 Mockito 单测 mock 掉 repo 测不到这层（CI 漏报家族新成员，已堵）。最终部署 v20260610_190408 双环境健康。
