# Dispatch 完成归档 — 2026-07-21

## BUG-F006-R3-MATERIAL-CATEGORY-PREFIX-001

- 状态：`merged-ready`；Owner：Codex coordinator (`/root`)。
- 登记 Base SHA：`e7b4cc58936c1ff1caa7aca940a64cef3faae3e7`。
- 实现 commit：`83deeba16f7c91693e443666951b878758300945`；PR：[#1547](https://github.com/Stevenjxie/cretas/pull/1547)。
- 根因：Web 仍调用只生成16位分类码的旧预览入口，而创建边界随后独立调用业务编码前缀分配器；新双码迁移没有为全部历史分类预置前缀，因此页面先展示可保存的16位码，保存时却因另一套 resolver 拒绝。页面 catch 又重复展示已由 API 拦截器提示的同一错误。
- 修复：合法启用 L3 的只读预览和事务内创建统一使用 `MaterialBusinessCodeService`；显式祖先前缀优先，缺省时按不可变10位 L3 数字身份的 base36 生成稳定、工厂隔离、无分隔符前缀。预览不写前缀/计数器，首次保存才在 L3 行锁下持久化并原子分配；停用或冲突配置 fail-closed，绝不覆盖、猜测或桥接历史编码。
- Web：完整 L1-L3 选定后同时展示业务编码和16位兼容分类编码，保存前复用只读契约；过期异步响应被丢弃；业务 4xx 仅由共享拦截器显示一次，纯网络错误才显示单一兜底提示。
- 验收：Java 19/19，通过真实 Spring/Hibernate JPA Context，覆盖显式前缀、缺省稳定前缀、预览零写、并发首次分配、跨厂隔离、停用前缀拒绝、重复名称先拒绝；Web 12/12；`vue-tsc -b && vite build` 成功（4452 modules）。最终只读代码审查 P0=0、P1=0。
- 生产边界：`NOT_DEPLOYED`；生产业务 mutation=0；没有创建/修改分类或原料，没有历史桥接，未触碰 LIUSHANMEN。
- Scope 锁：已释放；`ENH-F006-MATERIAL-BUSINESS-CODE-001` 父任务仍在 ACTIVE，不因本阻塞子修复冒充整体完成。
