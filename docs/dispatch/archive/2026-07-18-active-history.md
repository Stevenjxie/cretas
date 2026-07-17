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

## Workflow 编辑器阻塞修复

| ID | Base SHA | Owner | Result |
|---|---|---|---|
| WF-UX-20260718-01 | `c39ef12f4fb87878184d114f9a69b7a42f92af05` | Codex `/root` | 完成 Workflow 编辑器自由投入、响应式单位换算、SKU 拼音搜索、下拉滚轮与外部收起、Cell/连线选择和批量删除、自动布局、产出类型转换、BOM 发布门禁、独立版本以及内嵌 AI Composer；前端 182/182 目标测试与 production build 通过，Java 6 类 93/93 目标测试和真实 JPA Context 门禁通过，可信 JAR 由 build commit `3e35c392309ff072a58d207a4cd6d3efddc31fce` 生成；[PR #1421](https://github.com/Stevenjxie/cretas/pull/1421) squash 合并为 `7b0b19e87ba2712a825f33bb28b316db72506ce7`。 |
