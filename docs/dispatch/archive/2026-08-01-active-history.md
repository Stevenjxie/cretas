# 2026-08-01 ACTIVE 归档

## SOP-DUAL-LINE-SYNC-20260801

- 状态：`merged`；Owner：`/root`；Base SHA：`206e19a3edba7b3a990277a7d63e97b0f1f94c7f`。
- 合入：PR #2124 → `826c6dd81b349de50ce97d2fd10abddc6bb42003`；餐饮单菜毛利确定性合同 PR #2128 → `c77e1eab131ba49f79c5456874842ef95663785b`；门店与时间“一次问全”同步 PR #2129 → `29807b0012ab8454f3ff40f0c4dff6996f35846e`。
- 工厂：同步副产 SKU → BOM 声明 → 报工落生产仓 → 盘点确认抵扣、报工单位与库存边界；线上 SOP 与 `f006-production-full-chain-sop.md` 一致。真实回答中 BOM/Workflow 固定顺序与副产闭环均通过，仅引用 F006 source。
- 餐饮：同步四部门驾驶舱、盘点金额优先、时间范围、9 节月报、中央金额权限、单菜毛利能力边界，以及门店和时间同时缺少时“一次问全”；AI Assist 餐饮 tab、在线 SOP、产品手册与回答合同一致。
- 验证：首批 109 项、单菜毛利修复 111 项、最终澄清/合同/时间窗/月报/RBAC 193 项目标 pytest 通过；两条 Python Gate、两条 Web Vitest、Web dist 与 tracked-secret-scan 通过；`py_compile`、内联 JavaScript `node --check`、`git diff --check` 通过。
- 发布：从 clean exact `origin/main@29807b0012ab8454f3ff40f0c4dff6996f35846e` 完成 release-preflight、SmartBI migration dry-run（132 个已应用、0 个待应用）和生产 Python 发布；Python、Embedding 与 PostgreSQL 健康。
- 页面 SHA-256：AI Assist `b921106851d5efbdd7e6968469e8400d62a696feb371b98aa8868c99f8581031`；工厂 SOP `f3a83dd7b4c2a0e8eed95245686c6afe58abfb7854ae049571d7e16a25966992`；餐饮 SOP `bb002bfc5d4e4602f8b0591b0e112b80828c875bd8469b344391549cf8fc5864`。仓库、服务器与公网一致。
- RAG：工厂 `f006-production-full-chain-sop.md` 63 块；餐饮 `restaurant-full-chain-sop.html` 75 块、`restaurant-product-manual.html` 236 块、`restaurant-metrics-glossary.html` 170 块；正式 source 集与注册表一致，`.NEW=0`，未变化 source 未重建。
- 真实回答：工厂最终 2/2；餐饮单菜毛利等价三问 3/3、一次问全等价三问 3/3、导览边界 1/1。餐饮引用仅来自三个已注册 source，工厂与餐饮无交叉引用。
- 回滚点：静态页 `/www/backup/cretas-sop/20260801T012540Z-826c6dd8`、`/www/backup/cretas-sop/20260801T015438Z-29807b00`；RAG 源 `/www/backup/cretas-rag-sources/20260801T012540Z-826c6dd8`、`/www/backup/cretas-rag-sources/20260801T015438Z-29807b00`。
- 生产 ERP 业务写入：0。允许的生产变更仅为三张静态页中实际变化的目标、Python 发布和发生语义变化 source 的原子 RAG 重建。
- Scope 锁已释放；worktree 与分支按规则保留，未执行清理。
