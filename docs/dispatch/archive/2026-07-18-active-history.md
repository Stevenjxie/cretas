# Dispatch 归档 — 2026-07-18

## 生产只读 Playwright 统一框架

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| E2E-PROD-READONLY-HARNESS-20260717 | `3ce98d4d9d3a7edca79606844c0ac34c0be7b4fb` | Codex `/root` | 建立共享 `scripts/e2e/production-readonly/` MCP/CLI/fixture 框架与 before-send mutation guard；迁移 BOM/Workflow 契约，删除两个旧 runner 与重复依赖包，把生产写业务链改为显式非生产入口；同步 `AGENTS.md`、Web/Playwright E2E skills 和 drift 门禁。Node 单元 12/12、Playwright fixture 3/3，生产业务写请求 0；[PR #1419](https://github.com/Stevenjxie/cretas/pull/1419) squash 合并为 `8e35d2a81acac5298d40320aa2ff75ef4f8bec2a`。 |

## 发布快速通道与 Web 可信制品复用

| ID | 原 Base SHA | Owner | Result |
|---|---|---|---|
| CRETAS-RELEASE-FASTLANE-20260718-POLICY | `50275a4257cba8e9894a3faf58a6c4123b034346` | coordinator | 新增受控无 PR fast-forward helper：必须使用 clean `codex/*` worktree、注册 base、线性历史、归档 ACTIVE、无 force push 与 release preflight；高风险文件默认仍需 PR，只有显式授权才能解锁。因 `origin/main` 在开发中前进到 `9b1fc5072`，已完成线性 rebase 而非覆盖远端。契约测试覆盖确认、脏工作区、ACTIVE、stale base、高风险门禁、dry-run 与无 force push。 |
| CRETAS-RELEASE-FASTLANE-20260718-WEB | `50275a4257cba8e9894a3faf58a6c4123b034346` | coordinator | 新增 Web dist manifest，以 build commit、`web-admin` Git tree、package-lock/index/assets/full-dist SHA-256 和引用 chunk 完整性作为复用依据；支持 squash 后 tree 等价复用。校验失败仅回退一次本地 build，保留原子切换与旧 chunk 保留。契约测试覆盖正常/squash/tree变更/hash损坏/dirty/缺失制品与依赖缓存路径。 |

## Web 可信制品快速验证 v2

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| CRETAS-WEB-RELEASE-FASTLANE-V2-20260718 | `3d199278f71a775ee570084eea0729bede63b4f9` | coordinator | 将逐文件 shell 哈希替换为单个不可变 `dist.tar.gz` SHA-256，保留 build commit、Web Git tree、package-lock、index、tar 完整性与引用 chunk 门禁；部署保存远端 archive 指纹，相同 archive/index 且 HTTP 200 时在 scp 前 no-op。直接契约测试覆盖 squash/tree/dirty/archive 篡改/结构损坏/引用缺失/依赖回退/远端 no-op，且既有 Web 部署门禁通过。 |

## Java/Web 并行发布快速通道

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| CRETAS-RELEASE-PARALLEL-FASTLANE-20260718 | `8b82fd7973a5ab6d3c264be15df46e50a6d7dc8e` | coordinator | 新增 Java 显式测试选择器与项目导入静态预检、Java/Web 可信制品并行构建入口、以及必须显式确认任意切换顺序 API 兼容的受控并行生产部署入口。保留 Java 单次 Maven 生命周期、双 manifest 验证、Web 原子切换、Java 蓝绿观察与各自失败日志；契约测试与既有 release manifest/Web 部署门禁全部通过。 |

## 统一发布调度与 Java 关键路径优化

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| CRETAS-RELEASE-CRITICAL-PATH-20260718 | `10b2c0195607fc891eb59b25a78a7f8a6e877ed0` | Codex `/root` | 新增 `release-cretas.sh` 统一入口，按 Java/Web 变更自动构建、部署或健康 no-op；默认安全串行，仅在显式独立确认且迁移、Entity、Repository/Query、Security/Auth、Controller/DTO/API、配置和顺序门禁均未命中时并行。统一 JSON 记录制品、组件结果、阶段耗时和生产证据，并以子部署 fail-fast 环境门禁保证每组件最多一次构建。Java 同步 SemanticRouter 启动缓存改为 ready 后异步预热，可信 JAR 已通过唯一 Maven 生命周期 3 类 9 项测试并预置远端不可变缓存；shell/manifest/cache/Web 加速契约全部通过。[PR #1424](https://github.com/Stevenjxie/cretas/pull/1424)。 |

## 统一发布真实结果与 Web 四方哈希

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| CRETAS-RELEASE-GLOBAL-OUTCOME-20260718 | `6ee89e8b4399f53a61bae27c3354968d5b654a99` | Codex `/root` | 全局生产演练发现 Web manifest fallback 实际构建并部署，但统一回执误报 `no-op`。现改为以 Java/Web 子部署回执判定最终 `no-op`/`deployed`，记录 fallback build 模式、耗时与次数；Web no-op/部署成功均强制校验 manifest、服务器文件、网关 localhost、公网 HTTPS 四方 index SHA，并写结构化子回执。Shell 目标矩阵、manifest/cache/并行/preflight/verify 回归、UTF-8 与 diff 门禁通过；未重复 Maven，也未为修复强制重部署生产。[PR #1425](https://github.com/Stevenjxie/cretas/pull/1425)。 |

## Workflow 编辑器阻塞修复

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| WF-UX-20260718-01 | `c39ef12f4fb87878184d114f9a69b7a42f92af05` | Codex `/root` | 完成 Workflow 编辑器自由投入、响应式单位换算、SKU 拼音搜索、下拉滚轮与外部收起、Cell/连线选择和批量删除、自动布局、产出类型转换、BOM 发布门禁、独立版本以及内嵌 AI Composer；前端 182/182 目标测试与 production build 通过，Java 6 类 93/93 目标测试和真实 JPA Context 门禁通过，可信 JAR 由 build commit `3e35c392309ff072a58d207a4cd6d3efddc31fce` 生成；[PR #1421](https://github.com/Stevenjxie/cretas/pull/1421) squash 合并为 `7b0b19e87ba2712a825f33bb28b316db72506ce7`。 |

## 采购入库外仓与采购订单 PDF 语义

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| PURCHASE-RECEIVE-WAREHOUSE-PDF-20260718 | `44bd057af0425e7b9655cd20f6f4f90149649155` | Codex `/root` | Web 采购入库候选仓新增外仓/委外仓，默认仓仍预选但目标仓改为必选并显式提交；后端在草稿创建和确认建批次时双重校验仓库归属、启用状态与仓型，拒绝不存在、跨工厂或停用仓库。采购订单 PDF 明确显示“采购订单号（PO）”与“来源销售订单号（SO）”；按用户纠正未修改 RN 手机端。Web 单测 4/4、`vue-tsc --noEmit` 与唯一 production build 通过；Java 唯一 Maven 生命周期 171 秒，3 类 25/25 通过，可信 JAR SHA-256 `36a5a7fcb58005746f6fecb2d74bc89bad1716a60e6dca06093dc5ddd18393cb`。 |
