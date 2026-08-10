# Dispatch 完成记录 — 2026-08-10

- `AUTO-SOP-FACTORY-RESTAURANT-20260810` — `merged` — Owner: `/root` — Base SHA `2fcddb4230b6503043aa0e7af1a7b833bd5d01ce`；PR #2441 以 merge commit `6842d56fbcbfc3f41ce0902ac4a46f283928b0b5` 合入 `origin/main`。工厂同步短料号/可选分类、仅 Workflow 路线、无订单入库、仓库任务、库存归属、多批次小结、调拨凭证与销售生命周期；餐饮同步登记表驱动问答、通用执行器边界、相关性学习闸、真值锚点及营收预测历史回测，并清除旧 Prophet/固定 ±15–20% 口径。SOP/KB 合同 77/77、餐饮目标测试 100 通过/2 既有 xfail、CI Python/Web/secret/web-dist 全绿。三张静态页从 exact main 备份后原子发布；4 个变化 source 原子重建为工厂 69 块、餐饮完整 SOP 80 块、产品手册 240 块、指数字典 173 块，`.NEW=0`。工厂/餐饮各 3 个真实回答及 source 隔离通过；Java/Python/Embedding/数据库健康，Python 运行时代码哈希已与 main 一致故未重启；生产 ERP 业务写入 0。scope 随本归档释放。

- `UX-WEB-STATUS-SUMMARY-20260810-001` — `review` — Owner: `/root` — Base SHA `4c25c5047d1568cccbc5bbbdbd0dbeeb9fa7e0ae`；Web Admin 共用 Workflow 已由圆形流程图改为贴合现有列表卡片的可点击矩形状态摘要，移除流程箭头并保留键盘点击与长按语义；仓储“入库任务与批次”删除重复圆圈，只保留全部任务、待收货、收货中、部分入库、已入库批次这一套状态分页。销售退货、生产计划、仓储入库与库存批次页的静态大段教程/概念说明已删除，动态错误、权限、缺料和业务风险告警未改。目标 Vitest 25/25、`vue-tsc -b`、Vite production build、`git diff --check` 通过；Playwright 覆盖生产计划、库存批次、入库任务与批次、销售退货 4 页 × 1366×768/768×768 共 8 个用例，验证状态分页切换、旧圆圈/箭头与静态说明消失、无文档横向溢出且控制台错误为 0。未改销售订单文件、后端、接口、状态机或生产数据；严格 `NOT_DEPLOYED`，scope 随本归档释放。

- `CX-SHEET-VISUAL-20260731-001` — `merged` — Owner: `/root` — Base SHA `f06f8e03115f49f99d337290510222d22246bc0f`；历史台账仍标为 review，但 GitHub PR #2064 已合并，merge commit `103e5ab7e98dc765a04108836e4e3cdeaf9ec75f` 经 fresh fetch 证明属于当前 `origin/main`。原任务的 Web 56/56、Java 17/17、类型检查、Vite build、调用点变异检验与 diff check 证据保持不变；本次仅修正台账状态并释放其 scope，不改原任务代码或生产数据。

- `UX-WEB-SMALL-SCREEN-WORKSPACES-20260810` — `review` — Owner: `/root` — Base SHA `ee0c8c9e58e9ef37d120f7910a2392596aa236c7`；Web Admin 已为 371 个标准 Dialog/Drawer 使用面建立 viewport 内壳体、正文独立滚动与页脚可达的全局小屏安全基线，并把“手动新建调拨单”从 960px 长弹窗迁移为独立 `/transfer/new` 工作区。库存上限、包装换算、重复物料拦截与原 payload 契约保持不变。目标 Vitest 28/28、`vue-tsc -b`、Vite production build、`git diff --check` 通过；Playwright 完成 1366×768、1024×768、768×768、1366×768@125% 四视口，列表往返、仓库选择/空库存提示、无文档横向溢出、主操作不被客服浮球遮挡及长 Dialog 独立滚动均通过。未改后端、数据库、权限或生产业务数据；scope 随本归档释放，PR/CI、合并、exact-main Web 部署和服务级验收由同一用户授权任务继续并分别报告。

- `UX-WEB-WAREHOUSE-WORKSPACE-20260809-001` — `review` — Owner: `/root` — Base SHA `ae92496307df263ac9f26b7bb933dd317bad945a`；Web Admin 新增工厂内会话级多任务标签，支持保留页面状态、显式复制任务页签，并把任一已打开页签拖到右侧作为同源只读参考；右侧以交互遮罩阻止业务操作，不引入第二套业务数据。仓储“无订单入库申请”继续只负责申请、审批、驳回和撤回，审批通过后才进入统一“入库任务与批次”；统一页按全部任务、待收货、收货中、部分入库和已入库批次分页，前三个状态以圆形任务概览呈现，任务分类互斥且部分入库优先。销售订单、无订单入库申请、入库任务与批次三个菜单显示真实可处理数量，接口失败或结果集不完整时隐藏红点，不伪装为 0 或少算值。目标 Vitest 83/83（含既有仓储来源边界与路由契约）、`vue-tsc -b`、`git diff --check` 和本地模拟只读 Playwright 通过；浏览器证据为生命周期 `3/1/1/1/2`、菜单红点 `2/2/3`、两个工作页签及拖拽右侧参考，控制台错误与失败请求均为 0。未改 Java、Entity/Repository/Flyway/Security/Auth 或业务写服务，生产业务写入为 0；scope 随本归档释放，唯一 Web 发布构建、PR/CI、合并和 exact-main 部署由同一用户授权任务继续并分别报告。

## BUG-F006-RN-PURCHASE-SUPPLIER-SELECT-20260810

- 状态：`merged`（随本次最终变更进入 `origin/main` 后生效）
- Owner：`/root`
- Base SHA：`869e51bb94aea4b249811895a329ee3fa1ce786b`
- 范围：RN Formily 可搜索 Select、采购订单创建页的原料/单位选择器、对应目标测试与回归证据；未修改权限、后端或其他业务流程。
- 实现：将 Expo Web 间歇性不挂载的 Paper Portal 长列表替换为原生 Modal 选择面；供应商支持搜索，采购原料与单位使用同一稳定选择层，并保留原包装规格及抄码品单位逻辑。
- 验证：目标 Jest 2 个套件、5/5 通过；`git diff --check` 通过；TypeScript 仅保留开工前相同的 6 个既有基线错误，改动文件无新增类型错误。
- Expo Web：供应商 9 条可见且搜索后唯一命中；原料 305 条、单位 3 条均可见可选；非提交路径业务写入 0。
- F006 受控写入：使用已启用的供应商—物料关系创建 `PO-20260810-0001`（`be925c7f-e651-432c-aa58-f421e02246cf`），`orderDate` 与明细回读正确，随后取消，最终状态 `CANCELLED`；非 F006 写入 0。另以不兼容关系验证后端正确返回 409 及修复指引，未产生订单。
- 遗留基线：Expo Web 仍报告既有 `Unexpected text node` 警告，本任务开工前已出现，未影响选择器或建单链路。

## AUDIT-PR-DIFFCHECK-20260810-001

- 状态：`review`
- Owner：`/root`
- Base SHA：`f72683089a9e665162b655c1c036b0565a6470fc`
- 范围：仅新增根 `.gitattributes`，对 PR #2437 涉及的 4 个既有 CRLF Python 测试文件声明 `cr-at-eol` whitespace 语义；未改测试或生产代码内容。
- 结论：保留这些 Windows-console 测试文件的既有 CRLF，仍检查 CR 前的真实尾随空格；删除条件是四文件统一迁移为 LF 后移除对应属性。
- 验证：`git check-attr whitespace` 四文件均为 `trailing-space,cr-at-eol`；`git diff --check 68bc8a0d8e61403f4c1512071cc41605170286e2..HEAD` 与当前 diff 均通过。
- 边界：只进入 daily integration；未部署、未修改生产数据。scope 随本归档释放。
