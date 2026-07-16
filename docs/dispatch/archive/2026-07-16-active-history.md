# Dispatch 归档 — 2026-07-16

## BOM 生命周期、物料单位与发布

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| CRETAS-BOM-20260715-01 | `d5a4cb019a0620c71bb39ba1aaa23a9d298334f6` | `/root` | BOM 版本生命周期、只读历史出成率、分区添加、物料主数据价格真值与 Web E2E 完成；[PR #1374](https://github.com/Stevenjxie/cretas/pull/1374) 合并为 `16d0e32d14b629eea5d2941c938176d1e7d63d52`。 |
| CRETAS-BOM-BE-20260715-02 | `d5a4cb019a0620c71bb39ba1aaa23a9d298334f6` | `/root/bom_backend` | 单 SKU 单 ACTIVE、正式批次历史出成率、价格继承、Flyway 迁移与目标 Maven 测试完成。 |
| CRETAS-WF-UNIT-20260715-03 | `d5a4cb019a0620c71bb39ba1aaa23a9d298334f6` | `/root/workflow_units` | Workflow 权威单位即时传播、半成品单位编辑、非重量 SKU 比例恢复；workflow 136 tests 通过。 |
| CRETAS-BOM-REVIEW-20260715-04 | `d5a4cb019a0620c71bb39ba1aaa23a9d298334f6` | `/root/bom_review` | 只读审查完成，发现项已在合并前处理。 |
| CRETAS-BOM-HOTFIX-20260716-05 | `16d0e32d14b629eea5d2941c938176d1e7d63d52` | `/root` | 修复 Hibernate 6 无法解析 JPQL 嵌套 enum 常量；新增 Repository 启动校验，11 tests 通过；[PR #1375](https://github.com/Stevenjxie/cretas/pull/1375) 合并为 `3df6c6b1ef8182e091dcbf0e5a4c3948fae5665c`。 |
| CRETAS-JPA-GATE-20260716-06 | `3df6c6b1ef8182e091dcbf0e5a4c3948fae5665c` | `/root` | AGENTS 与 CI 增加 Repository/JPQL 真实 JPA Context 门禁；目标测试、YAML 与 PR checks 通过；[PR #1376](https://github.com/Stevenjxie/cretas/pull/1376) 合并为 `7f6deff3d3e849bd947eac596c8cfae26e28123f`。 |
| CRETAS-DEPLOY-FAST-20260716-07 | `7f6deff3d3e849bd947eac596c8cfae26e28123f` | `/root` | CI 发布精确 main SHA 的已验证 JAR，部署脚本核验 commit/SHA-256 后复用；idle 连续重启快速失败并输出有限日志；Release 摘要按真实存在性显示；保留 5×6 秒切流观察。Shell/YAML/编码与 PR checks 通过；[PR #1377](https://github.com/Stevenjxie/cretas/pull/1377) 合并为 `c6e3dad2bf980ad7f83c252dcd995201c4d305e2`。 |
| CRETAS-BOM-SKU-FOLLOWUP-20260716-08 | `c6e3dad2bf980ad7f83c252dcd995201c4d305e2` | `/root` | BOM 历史版本可直接重新激活且单 SKU 仅一个当前版本，最多保留 10 个版本；成本单位与移动平均估值语义、调料单位、半成品基本单位、SKU Excel/图片批量导入完成；[PR #1378](https://github.com/Stevenjxie/cretas/pull/1378) 合并为 `f1b644e1882719db1b4f19e1e6d6b07b6e3aaea5`。 |
| CRETAS-BOM-BE-20260716-09 | `c6e3dad2bf980ad7f83c252dcd995201c4d305e2` | `/root/bom_backend` | BOM 生命周期、单当前版本、版本上限、删除约束与实时成本 DTO 完成；目标测试 24 + 13 通过，最终只读审查完成。 |
| CRETAS-BOM-FE-20260716-10 | `c6e3dad2bf980ad7f83c252dcd995201c4d305e2` | `/root/workflow_units` | BOM 版本/成本/备注、调料 g/kg、快速价格配置、半成品单位与 SKU 导入预览 UI 完成；Vitest 与 `vue-tsc` 通过。 |
| CRETAS-BOM-REVIEW-20260716-11 | `c6e3dad2bf980ad7f83c252dcd995201c4d305e2` | `/root/bom_backend` | 最终审查发现并关闭同包装单位不同换算被误判、预览缺少生成规格两个 P1；关闭后无剩余 P0/P1。 |
| CRETAS-SKU-IMPORT-BE-20260716-12 | `c6e3dad2bf980ad7f83c252dcd995201c4d305e2` | `/root/bom_review` | 四工作表 `.xlsx` 模板、内容标记示例行、空格/单位归一、图片安全上传与映射、预览/原子确认、双包装规格持久化完成；目标测试 14 + 10 通过。 |
| SETTLEMENT-RECONCILIATION-20260716 | `aa2af813c2dc8281deb6369dd3c13e5e1f807139` | `/root` | 核对结单改为逐道报工汇总，一键结束计划；保留原料批次与逐道工时，兼容 g/kg 存储单位，并在预填和写入两层阻断跨计划重复占用。定位 F006 同一100kg批次被两个未结计划各占100kg；后端目标测试9/9、Web目标测试5/5及生产构建通过。 |
| FRESH-DB-FMR-BOOTSTRAP-20260716 | `21883e32013522f0d65bb0f4722cea4bab885151` | `/root` | Docker Desktop/Engine/Compose 在 Windows 重启后可用；新增 active Flyway 路径中的 FMR 空库 bootstrap，真实 PostgreSQL 17 + pgvector 门禁通过 Repository 1/1、Flyway 526 个迁移和完整 Spring/JPA 健康启动；修复 Git Bash 清理 Maven/Java 进程树残留。直接快进 `origin/main` 为 `26c0021191e6a227b325a847b271412973c25b4b`，未使用 PR。 |
| DEPLOY-SOURCE-CACHE-20260716 | `3cb1ce54d4c14a853506179c631317b724bf2754` | `/root` | 部署脚本增加 manifest-backed 后端 Git tree 缓存，纯文档提交不再重复 Maven；生产 JAR MD5、真实 upstream、active systemd 和直连健康全部匹配时安全 no-op，并增加独立阶段耗时。目标 shell/契约测试通过，直接快进 `origin/main` 为 `e18776ea2`，未重复部署 Java。 |
| DEPLOY-NOOP-RUNTIME-JAR-20260716 | `46aa0807c686a0cb446b0d50f4a473787c2f357c` | `/root` | PR #1386 合并并从精确 main 发布 Java/Web；Java 从 blue/10010 切到 green/10020，启动健康 69 秒、5/5 稳定观察，总耗时 283 秒；Web 原子发布 HTTP 200；F006 只读验收业务写请求 0。修复 no-op 错比上传文件名的问题，改为校验真实 `aims-0.0.1-SNAPSHOT.jar`，线上 MD5/upstream/systemd/健康证明通过，脚本修复合并为 `13bd1f1af`。 |
| MAVEN-RELEASE-BUILD-20260716 | `9e9331607f64a22b0055fb779ab24368ab3182b4` | `/root` | protobuf 仅在 `.proto` 变化时重新生成，避免每次 Maven 调用误触发 3,894 个 Java 源文件全量重编译；移除 Spring Boot parent 已提供的重复 repackage 绑定。相同源码 warm package 从 114.2 秒降至 45.5 秒（节省 68.7 秒，约 60%）；clean package 与合同测试通过。Docker clean build 实测 162.7 秒，未纳入默认发布路径。直接快进 `origin/main` 为 `60cad0910`，未重启无运行时变化的生产 Java。 |
| PRIVATE-REPO-READINESS-20260716 | `83d610b24f2cee20971ce8eaff401212c21f6741` | `/root` | Embedding JAR 改为真实目录下 rsync/scp 直传并由 `cretas-embedding` systemd 管理，模型 private 时跳过匿名 Release，legacy pull 使用显式鉴权。Actions 保留每日集成/JPA/E2E/隔离门禁；Java JAR 改为 `[ci-artifact]` 或手动才生成，成功测试/coverage 不再堆 artifact，KB 已知红灯 schedule 暂停。清理 1,310 个有效 artifact（约 10.47GB），开启 Actions 创建 PR 权限；代码直接快进 main 为 `024b233b2`。仓库切为 `PRIVATE` 后 fetch/no-op push、7 个 active workflow、CI run `29487280326` 与 Daily integration run `29487395857` 均验证成功；未重启生产服务、未操作生产数据。 |

## 合同一致性审计

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| AUDIT-MATERIAL-CONTRACT-20260716 | `21883e32013522f0d65bb0f4722cea4bab885151` | `/root/audit-material-agent` | 只读审计确认：16位编码/L1-L3目前只是 Web 创建页门禁，后端 DTO、AI、Excel、初始化与快速创建仍可生成无层级或旧式编码；历史数据无法仅凭名称安全推断 L2/L3，需先确认映射与迁移策略。 |
| AUDIT-PRODUCTION-CONTRACT-20260716 | `21883e32013522f0d65bb0f4722cea4bab885151` | `/root/audit-production-agent` | 只读审计确认：核对结单仍信任前端重复提交的完整事实，原料主导多产出、报工状态筛选、投入单位与半成品扣减存在服务端合同缺口，需改为服务端锁定后按计划快照/正式报工重算。 |
| AUDIT-CROSSMODULE-OPS-20260716 | `21883e32013522f0d65bb0f4722cea4bab885151` | `/root/audit-crossmodule-agent` | 只读审计确认：BOM RAW标准用量前后端不一致、AI旧年份仅部分修复、治理审计误报固有g/kg换算；线上GREEN健康且Metaspace/监控已生效，但生产部署落后origin/main，systemd单元文件还存在明文云凭证风险。 |
| CROSSMODULE-VERIFY-20260716 | `9e9331607f64a22b0055fb779ab24368ab3182b4` | `/root/audit_material_contract` | 当前 main 只读复核：价格语义、BOM成本单位、BOM订单ID、AI日期/主数据匹配、供应商草稿、物料需求单快照已修；0%用免税口径合理。另确认采购/销售批量入口空明细与缺日期、UTC隐藏日期、BOM 0%回填及跨模块箱/g/kg集成证明仍需修复。前端目标测试13项通过。 |
| SETTLE-CONTRACT-20260716 | `9e9331607f64a22b0055fb779ab24368ab3182b4` | `/root/settlement_contract_impl` | 核对结单改为服务端锁定计划后，从正式提交的逐道报工重新派生投入、终端产出和工时；正常路径只提交确认和幂等键，差异阻塞才要求补充异常原因。修正 inputUnit、草稿报工过滤、半成品定点扣减与原料主导多成品入库保护。后端35项、Web 5项及 vue-tsc 全部通过，提交 `c6ea3dd26` 已整合。 |
| MAT16-CONTRACT-20260716 | `9e9331607f64a22b0055fb779ab24368ab3182b4` | `/root/material_contract_impl` | 原料创建和历史短码修复统一要求有效 L1-L2-L3 父链并由服务端生成16位编码；类别/primaryCode 从 L1 派生，Web/RN 支持三级筛选，AI/Excel/默认初始化不再绕过契约。F006 线上230/230条已符合，无需写入补码；其他租户缺本地编码树，未做不可靠自动匹配。后端44项含真实 JPA 启动门禁、Web 6项和 vue-tsc 通过，RN改动完成语法编译检查，提交 `aa7d42a25` 已整合。 |
| CROSSMODULE-FIX-20260716 | `0f81b78e7` | `/root` | 采购/销售一维和二维入口因没有明细编辑器而禁用，代码层同时阻止空明细请求；普通新建和 BOM 展开保留。采购隐藏日期改用上海工厂本地日历日期，BOM 编辑保留显式0%税率。新增10箱→500件→200g/件→100000g领料链路证据。后端20项、Web 12项和 vue-tsc 全部通过，提交 `3cfd0a279`。 |

## 发布证据

- PR #1374 Java/Web 发布：真实 upstream 从 10020 切换到 10010，5/5 次 HTTP 200，旧槽停止；F006 只读 E2E 通过，业务写请求为 0。
- PR #1378 Web Admin 从合并后的 `origin/main` 原子发布到 139 网关：710 个 assets，线上 HTTP 200，marker 通过。
- PR #1378 Java 从同一 `origin/main` 蓝绿发布：真实 upstream 从 blue/10010 切换到 green/10020；新槽 104 秒健康，切流后 5/5 轮 HTTP 200，旧 10010 已停止且无监听。
- `verify-release.sh`：backend green/10020 health/marker 通过；Web HTTP/marker 通过。
- F006 线上只读验证：登录成功；SKU Excel 模板接口 HTTP 200，XLSX magic `504b0304`，16,155 bytes；业务写请求为 0。
- PR E2E Gate 的失败发生在既有 `V20261028_69` 对全新 CI 空库的前置列假设，后端启动前即失败；PR #1378 未修改迁移文件。Java 发布 JAR clean package 成功，JAR 完整性和 MD5 校验通过。
- 未操作 LIUSHANMEN 数据。
