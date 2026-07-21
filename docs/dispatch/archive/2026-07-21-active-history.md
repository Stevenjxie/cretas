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

## ENH-F006-MATERIAL-BUSINESS-CODE-001 — 按当前 L3 受控映射历史编码

- 状态：`merged-ready`；Owner：Codex coordinator (`/root`)。
- 登记 Base SHA：`c7fb4f9ad3c8f86894b8edb3b04c05534aac83a4`；最新 main 重放基线：`c257d8fdf780797835a352f2c9c76dce5925e9d1`。
- 实现状态：目标代码与文档合为单一安全提交；PR 在创建后补记。
- 只读结论：历史记录的当前 L3 可证明性因工厂而异；可证明行使用同源 resolver，不能证明的行只报告、不猜测。精确生产计数不进入公开仓库。历史16位 `code` 全部保留，`displayCode` 继续由 `businessCode` 优先、旧码兜底动态派生。
- 修复：增加只读预览与显式确认回填接口；悲观锁和条件更新保证并发唯一、重放 no-op；既有码、非法旧码和无启用 L3 行安全跳过；普通新增/编辑路径不能改写历史业务码。
- 验收：最新 main 基线上 Maven 17/17 PASS，包含真实 Spring/Hibernate JPA Context、预览零写、正式映射、旧码保持、displayCode切换、无效映射跳过、并发只分配一次与 Controller 确认门禁；最终只读代码审查 P0=0、P1=0。
- 生产边界：`NOT_DEPLOYED`；生产业务 mutation=0；未执行任何历史回填，未修改生产物料或分类。
- Scope 锁：已释放；后续若要正式执行历史映射，必须在部署后按工厂先预览，并取得单独生产写入授权。

## BUG-F006-R3-MATERIAL-DISPLAY-CODE-002

- 状态：`merged-ready`；Owner：Codex coordinator (`/root`)；登记 Base SHA：`6c259992d89cd8e1a9709975ccb084d50d8236d1`。
- 实现 commit：`8a39acecd045a6338659f8a7e71eefe125bc1fdb`；PR：[#1552](https://github.com/Stevenjxie/cretas/pull/1552)。
- 根因：后端创建、DTO 与搜索已经支持 `businessCode/displayCode`，但 Web 列表、编辑态和分类文案仍以16位兼容 `code` 为主，导致新双码能力在用户界面看似没有生效。
- 修复：物料身份统一优先显示 `displayCode → businessCode → legacy code`；有短码时16位编码仅作为“兼容码”次级信息，历史未映射行安全回退并标“历史编码”。分类选择器以名称为主、分类码为次级说明，创建区和搜索文案同步双码契约。
- 验收：Web 目标测试 13/13 PASS；`vue-tsc -b` PASS；正式 Web release build PASS（735 assets），构建仅执行一次并生成可信清单。
- 生产边界：后端、数据库和生产物料均未修改；没有执行历史回填；生产业务 mutation=0；未触碰 LIUSHANMEN。合入后仅发布 Web。
- Scope 锁：已释放。
