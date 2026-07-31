# Dispatch 完成记录 — 2026-07-31

## `DOC-SOP-DUAL-20260731`

- 状态：`merged`
- Owner：`/root`
- Base SHA：`ce0ce86afd343d559edb385e9c93a045b63ee955`
- 检查区间：`a47fc95fa837cdb2c6c77cf270011c457e83fdd1..f4a982cd28b4969251ebf2917b2d80a8f8e9ccdb`；初次 fetch 至 `ce0ce86afd343d559edb385e9c93a045b63ee955` 共 71 个 commit，相关用户可见 commit 已逐项语义核对。
- 合入：SOP/RAG 功能 PR [#2073](https://github.com/Stevenjxie/cretas/pull/2073) squash merge `6c8268bc810d9d5994a27f5b0235de3d816a8193`；真实 canary 暴露漂移后，确定性回答合同 PR [#2074](https://github.com/Stevenjxie/cretas/pull/2074) squash merge `f4a982cd28b4969251ebf2917b2d80a8f8e9ccdb`。两项 secret regression gate 均通过。
- 工厂：同步共享 AI Assist 工厂 tab、在线 SOP 与唯一 `f006-production-full-chain-sop.md`，覆盖 RN 入库/盘点/物料需求边界、供应商/单据追踪、调拨、逐道报工投入来源和服务端短缺。真实回答 3/3，固定 BOM/Workflow 顺序、ACTIVE BOM 门禁、发布/启用验收及新增口径全部通过，来源仅 F006。
- 餐饮：同步共享 AI Assist 餐饮 tab、在线 SOP 与注册表中的 full-chain/product/metrics 三项 source，覆盖指标轴、菜单实体、领料成本、POS-only 数据可用、范围动作、建议数字和 connector 死信。真实回答 5/5，固定单菜毛利红线、损耗金额、POS-only、导览不代算与范围按钮全部通过，来源仅三项餐饮 registered source。
- 验证：首批 SOP 合同 39/39、餐饮行为回归 140/140；回答修复后 Food KB 全目标 56/56；`py_compile`、内联 JavaScript 解析、`git diff --check`、release-preflight 通过。生产 migration dry-run 为 130 已应用、`would-apply=0`。
- 生产：三页和四项 canonical source 均从 clean exact main 经 SHA-256 核对后备份、原子替换/原子 `.NEW → 正式块`；随后从 clean exact `origin/main@f4a982cd` 标准发布 Python。`cretas-python`、Embedding、PostgreSQL active，8083 healthy 且 PostgreSQL connected，运行 `manual_chat.py` 与 main SHA-256 一致。
- 页面 SHA-256：AI Assist `71085d88b1c1cdaf7ccf17cdc96bcbcb6b45e9c667a1acb3c3237f7d0c395058`；工厂 SOP `05040674100f92407aafe0aad9da204c51b822403ccb94fb8c38f6fa41acd51c`；餐饮 SOP `8fe8632171256dde823ffd585545d0a30f8b7108a857c5dac3296b0b0e0c381a`。仓库、服务器与公网均一致且 HTTP 200。
- canonical source SHA-256：F006 `b17cda8365394e89f10f3e656d2ee41576c530dd8aae3491bcd567c10b464ac4`；餐饮 full-chain `8fe8632171256dde823ffd585545d0a30f8b7108a857c5dac3296b0b0e0c381a`、product manual `487e7d815c407cbf9a18eaa42e5a04676eb6a2f57d9d44e608f4e4abf44832a2`、metrics glossary `c3c96c736b4642c9dea0bc1e64f1d82d2be80055942cdac740db0aa00bf362ea`。
- RAG 正式块：F006 62；restaurant full-chain 73；product manual 235；metrics glossary 170；均创建于本轮，`.NEW=0`，来源集合无重复或跨线污染。
- 回滚点：静态页 `/www/backup/cretas-sop/20260731T012237Z-6c8268bc`；RAG 源 `/www/backup/cretas-rag-sources/20260731T012237Z-6c8268bc`。
- 生产 ERP 业务写入：0。允许的状态变更仅三页原子替换、四项 source 同步/重建和 exact-main Python 发布/重启。
- Scope 锁：已释放。worktree 与分支按约定保留。
