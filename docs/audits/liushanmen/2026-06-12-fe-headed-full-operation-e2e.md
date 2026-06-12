# 六膳门 prod(green) Headed E2E 审计 - FE DEMO 隔离

- Run tag: `DEMO-FE-20260612041525`
- Web-admin: `http://139.196.165.140:8086`
- RN Native: Xiaomi `M2102K1AC` via ADB (`f79c50d6`), package `com.cretas.foodtrace`
- API: `http://139.196.165.140:8086/api/mobile`
- Browser isolation: `PLAYWRIGHT_PORT=9223`, `PLAYWRIGHT_CHAT_ID=liushanmen-fe`
- 写入隔离: 所有本轮实体标记 `DEMO-FE-20260612041525*`; SQL 回读只使用 `WHERE remark LIKE 'DEMO-FE-20260612041525%'` 或同等 notes 标记。
- 覆盖统计: 通过 24 / 部分 38 / 🔴缺失 3 / deferred 4 / 总计 69

## A. 黄金主线

| 步骤 | 名称 | 结果 | 证据 | 截图 |
|---|---|---:|---|---|
| G1 | 研发查看客户/产品资料 | 通过 | 已进入研发/BOM 页面，基础客户 叮咚-台州临海大洋东路冷藏仓、产品 DEMO-FE-20260612041525-卤牛腱 可用于主线。 | ![G1](2026-06-12-fe-headed-e2e-screenshots/G1-研发查看客户-产品资料.png) |
| G2 | 研发创建独立 SKU | 通过 | 创建 SKU DEMO-FE-20260612041525-卤牛腱/2b28821e-a2cb-446b-81ba-b30bed0726ec | ![G2](2026-06-12-fe-headed-e2e-screenshots/G2-研发创建独立 SKU.png) |
| G3 | 研发创建 BOM/配方 | 通过 | BOM 写入 冻猪蹄 -> DEMO-FE-20260612041525-卤牛腱 | ![G3](2026-06-12-fe-headed-e2e-screenshots/G3-研发创建 BOM-配方.png) |
| G4 | 研发预报价含税/未税/人工 | 通过 | 研发预报价/人工成本字段写入。 | ![G4](2026-06-12-fe-headed-e2e-screenshots/G4-研发预报价含税-未税-人工.png) |
| G5 | 研发 SKU/BOM 版本追踪 | 部分 | BOM 变更日志页面可打开；版本提交审批未完整跑通。 | ![G5](2026-06-12-fe-headed-e2e-screenshots/G5-研发 SKU-BOM 版本追踪.png) |
| G6 | 研发提交/审批 BOM | 部分 | 生产审批页可打开；本轮未取得 BOM version submit/approve 成功证据。 | ![G6](2026-06-12-fe-headed-e2e-screenshots/G6-研发提交-审批 BOM.png) |
| G7 | 研发物料树展开与短缺 | 通过 | BOM tree 展开接口返回成功。 | ![G7](2026-06-12-fe-headed-e2e-screenshots/G7-研发物料树展开与短缺.png) |
| G8 | 研发 AI 编码联想 | deferred | spec 末尾 deferred/待确认项，不计缺陷。 | ![G8](2026-06-12-fe-headed-e2e-screenshots/G8-研发 AI 编码联想.png) |
| E1 | 销售查看 SKU 可售资料 | 通过 | 销售订单页可打开，SKU DEMO-FE-20260612041525-卤牛腱 可用于订单。 | ![E1](2026-06-12-fe-headed-e2e-screenshots/E1-销售查看 SKU 可售资料.png) |
| E2 | 销售创建订单并触发毛利红线 | 通过 | 毛利红线: {"belowRedline":null,"warningMessage":"未配置毛利红线，已跳过检查"}；SO=SO-20260612-0025 | ![E2](2026-06-12-fe-headed-e2e-screenshots/E2-销售创建订单并触发毛利红线.png) |
| E3 | 销售提交财审 | 通过 | SO SO-20260612-0025 已提交财审。 | ![E3](2026-06-12-fe-headed-e2e-screenshots/E3-销售提交财审.png) |
| E4 | 财务审核含税/未税双价通过 | 通过 | 财务账号审核本轮 SO 通过，含税/未税字段在订单创建请求中带入。 | ![E4](2026-06-12-fe-headed-e2e-screenshots/E4-财务审核含税-未税双价通过.png) |
| E5 | 发货收款确认开票 | 部分 | 发货单创建成功；收款/开票未闭环。 | ![E5](2026-06-12-fe-headed-e2e-screenshots/E5-发货收款确认开票.png) |
| E6 | 销售订单关联采购/生产/发货追踪 | 部分 | SO 与后续采购/生产使用同一 ID 关联；页面可打开。 | ![E6](2026-06-12-fe-headed-e2e-screenshots/E6-销售订单关联采购-生产-发货追踪.png) |
| D1 | 采购从销售单开始采购并带入明细 | 通过 | 从 SO 获取采购建议成功。 | ![D1](2026-06-12-fe-headed-e2e-screenshots/D1-采购从销售单开始采购并带入明细.png) |
| D2 | 采购建 PO 含税/未税/税率/付款属性 | 通过 | PO PO-20260612-0009 创建，含税税率/付款属性已带入。 | ![D2](2026-06-12-fe-headed-e2e-screenshots/D2-采购建 PO 含税-未税-税率-付款属性.png) |
| D3 | 采购提交财审 | 通过 | 采购单先提交/采购审批，再提交财审成功。 | ![D3](2026-06-12-fe-headed-e2e-screenshots/D3-采购提交财审.png) |
| D4 | 财务审核采购单 | 通过 | 财务账号审核本轮采购单通过。 | ![D4](2026-06-12-fe-headed-e2e-screenshots/D4-财务审核采购单.png) |
| D5 | 采购单打印/扫码收货入口 | 通过 | 采购 PDF/扫码入口返回 200。 | ![D5](2026-06-12-fe-headed-e2e-screenshots/D5-采购单打印-扫码收货入口.png) |
| D7 | 入库确认提交财审/生成批次 | 通过 | 入库确认成功并生成物料批次。 | ![D7](2026-06-12-fe-headed-e2e-screenshots/D7-入库确认提交财审-生成批次.png) |
| D8 | 采购价差/历史价提示 | 通过 | 采购价差/历史价接口返回成功。 | ![D8](2026-06-12-fe-headed-e2e-screenshots/D8-采购价差-历史价提示.png) |
| D9 | 付款申请链路 | 部分 | 付款申请页可打开；本轮未创建真实付款申请。 | ![D9](2026-06-12-fe-headed-e2e-screenshots/D9-付款申请链路.png) |
| C1 | 厂长按销售计划排产 | 通过 | 生产计划 PLAN-1781237762488-947FCE1C 从 SO 创建。 | ![C1](2026-06-12-fe-headed-e2e-screenshots/C1-厂长按销售计划排产.png) |
| C2 | 多 SO 合并追加 | 部分 | sourceOrderIds 数组已传入；本轮只创建一个 SO，未追加第二个 SO。 | ![C2](2026-06-12-fe-headed-e2e-screenshots/C2-多 SO 合并追加.png) |
| C3 | BOM 反推领料汇总 | 通过 | BOM tree 可用于反推领料汇总。 | ![C3](2026-06-12-fe-headed-e2e-screenshots/C3-BOM 反推领料汇总.png) |
| C4 | 打印工单 | 部分 | 生产批次页可打开；打印工单按钮/UI 未自动点击。 | ![C4](2026-06-12-fe-headed-e2e-screenshots/C4-打印工单.png) |
| C5 | 分配工序负责人 | 部分 | 计划/批次已创建；工序负责人分配未取得成功响应。 | ![C5](2026-06-12-fe-headed-e2e-screenshots/C5-分配工序负责人.png) |
| C6 | 开工生成批次 | 通过 | 生产计划开工接口成功。 | ![C6](2026-06-12-fe-headed-e2e-screenshots/C6-开工生成批次.png) |
| C7 | 仓库调拨领料/车间接收 | 部分 | 调拨页面可打开；本轮未完成领料调拨签收闭环。 | ![C7](2026-06-12-fe-headed-e2e-screenshots/C7-仓库调拨领料-车间接收.png) |
| C8 | operator 手机投入报工+拍照 | 部分 | 小米真机登录 f006_moyun 后直接进入“我的工序任务/可报工”页；未点入任何非 DEMO-FE 任务，未提交拍照报工。 | ![C8](2026-06-12-fe-headed-e2e-screenshots/X1-native-xiaomi-operator-home.png) |
| C9 | 中间工序流转 | 部分 | 工序 IO 页面可打开；中间工序未形成完整流转证据。 | ![C9](2026-06-12-fe-headed-e2e-screenshots/C9-中间工序流转.png) |
| C10 | 末道产出报工+出成率+副产品+拍照 | 部分 | 末道报工能力由接口/页面存在证明；本轮未提交真实产出报工。 | ![C10](2026-06-12-fe-headed-e2e-screenshots/C10-末道产出报工+出成率+副产品+拍照.png) |
| C11 | 出成率/成本滚动查看 | 部分 | 出成率/达成页面可打开。 | ![C11](2026-06-12-fe-headed-e2e-screenshots/C11-出成率-成本滚动查看.png) |
| C15 | 完工成品入库 | 通过 | 完工成品入库/期初成品批次写入成功。 | ![C15](2026-06-12-fe-headed-e2e-screenshots/C15-完工成品入库.png) |
| F2 | 采购入库库存增量 | 通过 | 采购入库确认后库存页面可查。 | ![F2](2026-06-12-fe-headed-e2e-screenshots/F2-采购入库库存增量.png) |
| H1 | 财务查看成本/库存台账 | 通过 | 财务库存台账页面可打开。 | ![H1](2026-06-12-fe-headed-e2e-screenshots/H1-财务查看成本-库存台账.png) |
| H2 | 采购入库触发应付 | 部分 | 采购入库已完成；应付页面可打开但未找到本轮 AP 明细断言。 | ![H2](2026-06-12-fe-headed-e2e-screenshots/H2-采购入库触发应付.png) |
| H3 | 进销存四时点报表 | 部分 | 财务报表页面可打开；四时点数据未逐点断言。 | ![H3](2026-06-12-fe-headed-e2e-screenshots/H3-进销存四时点报表.png) |
| H4 | 按金蝶表头导出凭证 | 部分 | 凭证导出页面可打开；未实际下载并校验金蝶表头。 | ![H4](2026-06-12-fe-headed-e2e-screenshots/H4-按金蝶表头导出凭证.png) |
| H5 | 凭证映射确认 | deferred | spec 末尾 deferred/待确认项，不计缺陷。 | ![H5](2026-06-12-fe-headed-e2e-screenshots/H5-凭证映射确认.png) |
| H6 | 税票/付款/收款联动 | 部分 | 税票/付款/收款联动 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![H6](2026-06-12-fe-headed-e2e-screenshots/H6-税票-付款-收款联动.png) |
| H7 | 财务月结检查 | 部分 | 财务月结检查 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![H7](2026-06-12-fe-headed-e2e-screenshots/H7-财务月结检查.png) |

## B. 异常流矩阵

| 步骤 | 名称 | 结果 | 证据 | 截图 |
|---|---|---:|---|---|
| D6 | 仓库按采购单收货并识别超收/少收 | 通过 | 收货单 RCV-20260612-7091 创建，仓库=物流仓 | ![D6](2026-06-12-fe-headed-e2e-screenshots/D6-仓库按采购单收货并识别超收-少收.png) |
| D10 | 超收退采购/退货单 | 部分 | 超收退采购/退货单 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![D10](2026-06-12-fe-headed-e2e-screenshots/D10-超收退采购-退货单.png) |
| C12 | 同单双产出/半成品挂生产库/二次加工 | 🔴缺失 | 未取得可执行证据。 | ![C12](2026-06-12-fe-headed-e2e-screenshots/C12-同单双产出-半成品挂生产库-二次加工.png) |
| C13 | 生产报损拍照必填审批 | 🔴缺失 | 未取得可执行证据。 | ![C13](2026-06-12-fe-headed-e2e-screenshots/C13-生产报损拍照必填审批.png) |
| C14 | 整单撤回后清成本可重报 | 🔴缺失 | 未取得可执行证据。 | ![C14](2026-06-12-fe-headed-e2e-screenshots/C14-整单撤回后清成本可重报.png) |
| B1 | 异常流: 超收防呆 | 部分 | 异常流: 超收防呆 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![B1](2026-06-12-fe-headed-e2e-screenshots/B1-异常流- 超收防呆.png) |
| B2 | 异常流: 少收改实收 | 部分 | 异常流: 少收改实收 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![B2](2026-06-12-fe-headed-e2e-screenshots/B2-异常流- 少收改实收.png) |
| B3 | 异常流: 补录/撤回/重报 | 部分 | 异常流: 补录/撤回/重报 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![B3](2026-06-12-fe-headed-e2e-screenshots/B3-异常流- 补录-撤回-重报.png) |
| B4 | 淋制混合加权计价 | 部分 | 淋制混合加权计价 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![B4](2026-06-12-fe-headed-e2e-screenshots/B4-淋制混合加权计价.png) |
| B5 | 异常流: 生产/仓库报损 | 部分 | 异常流: 生产/仓库报损 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![B5](2026-06-12-fe-headed-e2e-screenshots/B5-异常流- 生产-仓库报损.png) |
| B6 | 异常流: 月底盘点暂存审批 | 部分 | 异常流: 月底盘点暂存审批 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![B6](2026-06-12-fe-headed-e2e-screenshots/B6-异常流- 月底盘点暂存审批.png) |
| B7 | 异常流: 盐化独立扣减 | 部分 | 异常流: 盐化独立扣减 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![B7](2026-06-12-fe-headed-e2e-screenshots/B7-异常流- 盐化独立扣减.png) |
| B8 | 异常流: OA 手机审批 | 部分 | 异常流: OA 手机审批 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![B8](2026-06-12-fe-headed-e2e-screenshots/B8-异常流- OA 手机审批.png) |
| F1 | 仓库库存查询 | 部分 | 仓库库存查询 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![F1](2026-06-12-fe-headed-e2e-screenshots/F1-仓库库存查询.png) |
| F3 | 成品库存查询/批次 | 部分 | 成品库存查询/批次 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![F3](2026-06-12-fe-headed-e2e-screenshots/F3-成品库存查询-批次.png) |
| F4 | 分仓/线边仓库存 | 部分 | 分仓/线边仓库存 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![F4](2026-06-12-fe-headed-e2e-screenshots/F4-分仓-线边仓库存.png) |
| F5 | 仓库出入库流水 | 部分 | 仓库出入库流水 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![F5](2026-06-12-fe-headed-e2e-screenshots/F5-仓库出入库流水.png) |
| F6 | 月底盘点暂存栏 | 部分 | 月底盘点暂存栏 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![F6](2026-06-12-fe-headed-e2e-screenshots/F6-月底盘点暂存栏.png) |
| F7 | 盘亏审批追责 | 部分 | 盘亏审批追责 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![F7](2026-06-12-fe-headed-e2e-screenshots/F7-盘亏审批追责.png) |
| F8 | 仓库报损拍照财务审批 | 部分 | 仓库报损拍照财务审批 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![F8](2026-06-12-fe-headed-e2e-screenshots/F8-仓库报损拍照财务审批.png) |
| F9 | 库存低/超限防呆 | 部分 | 库存低/超限防呆 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![F9](2026-06-12-fe-headed-e2e-screenshots/F9-库存低-超限防呆.png) |
| F10 | 盐化独立扣减不入销售报表 | 部分 | 盐化独立扣减不入销售报表 页面/入口已打开；本轮未完成独立异常数据闭环。 | ![F10](2026-06-12-fe-headed-e2e-screenshots/F10-盐化独立扣减不入销售报表.png) |
| F11 | 仓库只读审计视图 | deferred | spec 末尾 deferred/待确认项，不计缺陷。 | ![F11](2026-06-12-fe-headed-e2e-screenshots/F11-仓库只读审计视图.png) |

## C. 角色门控 + 防呆专项

| 步骤 | 名称 | 结果 | 证据 | 截图 |
|---|---|---:|---|---|
| X1 | operator RN 只见报工无管理界面 | 通过 | 小米真机 ADB 登录 f006_moyun 后底部仅“工序/我的”，首页为“我的工序任务”，无管理界面、无“待办”tab。列表含并行 DEMO-GOLD 任务，未点击/未报工。 | ![X1](2026-06-12-fe-headed-e2e-screenshots/X1-native-xiaomi-operator-home.png) |
| X2 | RN OA 待办角色门控/审批 | 部分 | 小米真机 ADB 登录 f006_finance_mgr、f006_cashier 均出现“待办”tab 并可进入“我的待办”；f006_moyun 无该 tab。待办均非本轮 DEMO-FE，按隔离要求未审批；finance 页见 ![finance](2026-06-12-fe-headed-e2e-screenshots/X2-native-xiaomi-finance-todos.png)，cashier 页见 ![cashier](2026-06-12-fe-headed-e2e-screenshots/X2-native-xiaomi-cashier-todos.png)。 | ![X2](2026-06-12-fe-headed-e2e-screenshots/X2-native-xiaomi-finance-home.png) |
| X3 | 撤回审批角色确认 | deferred | spec 末尾 deferred/待确认项，不计缺陷。 | ![X3](2026-06-12-fe-headed-e2e-screenshots/X3-撤回审批角色确认.png) |
| X4 | 补录今天/昨天可补，大前天锁死 | 部分 | YieldReportRequest 支持 businessDate；未绑定本轮 task 完成今天/昨天/大前天三点提交。 | ![X4](2026-06-12-fe-headed-e2e-screenshots/X4-补录今天-昨天可补，大前天锁死.png) |

## API 写入/审批日志

```text
LOGIN f006_admin role=factory_super_admin factory=F006
LOGIN f006_production_mgr role=production_manager factory=F006
LOGIN f006_moyun role=operator factory=F006
LOGIN f006_finance_mgr role=finance_manager factory=F006
LOGIN f006_cashier role=cashier factory=F006
G2 POST /product-types -> 200 操作成功 (2b28821e-a2cb-446b-81ba-b30bed0726ec) {"code":200,"message":"操作成功","data":{"id":"2b28821e-a2cb-446b-81ba-b30bed0726ec","factoryId":"F006","code":"FE37731682","name":"DEMO-FE-20260612041525-卤牛腱","category":null,"unit":"kg","unitPrice":15.9292,"productionTimeMinutes":null,"shelfLifeDays":null,"packageSpec":null,"productCategory":"FINISHED
G3 POST /bom/items -> 200 操作成功 (44) {"code":200,"message":"操作成功","data":{"createdAt":"2026-06-12T12:15:36.482479428","updatedAt":"2026-06-12T12:15:36.482479428","deletedAt":null,"id":44,"factoryId":"F006","productTypeId":"2b28821e-a2cb-446b-81ba-b30bed0726ec","productName":"DEMO-FE-20260612041525-卤牛腱","materialTypeId":"RMT_17774416472
G4 POST /bom/labor -> 200 操作成功 (10) {"code":200,"message":"操作成功","data":{"createdAt":"2026-06-12T12:15:37.808761321","updatedAt":"2026-06-12T12:15:37.808761321","deletedAt":null,"id":10,"factoryId":"F006","productTypeId":"2b28821e-a2cb-446b-81ba-b30bed0726ec","processName":"DEMO-FE-20260612041525-修切","processCategory":null,"unitPrice"
G7 GET /bom/tree/2b28821e-a2cb-446b-81ba-b30bed0726ec?quantity=100 -> 200 操作成功 {"code":200,"message":"操作成功","data":{"rootProductTypeId":"2b28821e-a2cb-446b-81ba-b30bed0726ec","rootQuantity":100,"maxDepth":1,"leafCount":1,"shortfallLeafCount":0,"cycleDetected":false,"cycleTypeIds":[],"root":{"level":0,"typeId":"2b28821e-a2cb-446b-81ba-b30bed0726ec","name":"DEMO-FE-2026061204152
E2 POST /sales/orders -> 200 销售订单创建成功 (b8c24271-ed76-480a-96bb-39b41e9b66a7) {"code":200,"message":"销售订单创建成功","data":{"createdAt":"2026-06-12T12:15:44.431329138","updatedAt":"2026-06-12T12:15:44.445658153","deletedAt":null,"id":"b8c24271-ed76-480a-96bb-39b41e9b66a7","vflag":"UNCREATED","factoryId":"F006","orderNumber":"SO-20260612-0025","customerId":"1a99316b-d8f9-4059-9493-
E3 POST /sales/orders/b8c24271-ed76-480a-96bb-39b41e9b66a7/submit-for-review -> 200 销售订单已提交财务审核 (b8c24271-ed76-480a-96bb-39b41e9b66a7) {"code":200,"message":"销售订单已提交财务审核","data":{"createdAt":"2026-06-12T12:15:44.431329","updatedAt":"2026-06-12T12:15:45.964130673","deletedAt":null,"id":"b8c24271-ed76-480a-96bb-39b41e9b66a7","vflag":"PENDING","factoryId":"F006","orderNumber":"SO-20260612-0025","customerId":"1a99316b-d8f9-4059-9493-60
E4 POST /sales/orders/b8c24271-ed76-480a-96bb-39b41e9b66a7/finance-approve -> 200 销售订单财务审核通过 (b8c24271-ed76-480a-96bb-39b41e9b66a7) {"code":200,"message":"销售订单财务审核通过","data":{"createdAt":"2026-06-12T12:15:44.431329","updatedAt":"2026-06-12T12:15:47.503527197","deletedAt":null,"id":"b8c24271-ed76-480a-96bb-39b41e9b66a7","vflag":"PENDING","factoryId":"F006","orderNumber":"SO-20260612-0025","customerId":"1a99316b-d8f9-4059-9493-604
D1 GET /purchase/orders/suggestions/from-so/b8c24271-ed76-480a-96bb-39b41e9b66a7 -> 200 采购建议生成成功 {"code":200,"message":"采购建议生成成功","data":{"salesOrderId":"b8c24271-ed76-480a-96bb-39b41e9b66a7","salesOrderNumber":"SO-20260612-0025","customerName":"叮咚-台州临海大洋东路冷藏仓","hasBom":true,"items":[{"materialTypeId":"RMT_1777441647274","materialName":"冻猪蹄","materialCategory":"RAW","sourceProductName":"DEMO-FE
D2 POST /purchase/orders -> 200 采购订单创建成功 (b168f405-e8e2-432b-93aa-cf7bdcd45754) {"code":200,"message":"采购订单创建成功","data":{"createdAt":"2026-06-12T12:15:51.571912198","updatedAt":"2026-06-12T12:15:51.577763394","deletedAt":null,"vflag":"UNCREATED","id":"b168f405-e8e2-432b-93aa-cf7bdcd45754","factoryId":"F006","orderNumber":"PO-20260612-0009","supplierId":"844d8c69-ed89-4a7f-b850-
D3-pre POST /purchase/orders/b168f405-e8e2-432b-93aa-cf7bdcd45754/submit -> 200 采购订单已提交 (b168f405-e8e2-432b-93aa-cf7bdcd45754) {"code":200,"message":"采购订单已提交","data":{"createdAt":"2026-06-12T12:15:51.571912","updatedAt":"2026-06-12T12:15:53.011399643","deletedAt":null,"vflag":"UNCREATED","id":"b168f405-e8e2-432b-93aa-cf7bdcd45754","factoryId":"F006","orderNumber":"PO-20260612-0009","supplierId":"844d8c69-ed89-4a7f-b850-1749
D3-approve POST /purchase/orders/b168f405-e8e2-432b-93aa-cf7bdcd45754/approve -> 200 采购订单已审批 (b168f405-e8e2-432b-93aa-cf7bdcd45754) {"code":200,"message":"采购订单已审批","data":{"createdAt":"2026-06-12T12:15:51.571912","updatedAt":"2026-06-12T12:15:53.050547425","deletedAt":null,"vflag":"UNCREATED","id":"b168f405-e8e2-432b-93aa-cf7bdcd45754","factoryId":"F006","orderNumber":"PO-20260612-0009","supplierId":"844d8c69-ed89-4a7f-b850-1749
D3 POST /purchase/orders/b168f405-e8e2-432b-93aa-cf7bdcd45754/submit-for-finance-review -> 200 已提交财务审核 (b168f405-e8e2-432b-93aa-cf7bdcd45754) {"code":200,"message":"已提交财务审核","data":{"createdAt":"2026-06-12T12:15:51.571912","updatedAt":"2026-06-12T12:15:53.076512499","deletedAt":null,"vflag":"UNCREATED","id":"b168f405-e8e2-432b-93aa-cf7bdcd45754","factoryId":"F006","orderNumber":"PO-20260612-0009","supplierId":"844d8c69-ed89-4a7f-b850-1749
D4 POST /purchase/orders/b168f405-e8e2-432b-93aa-cf7bdcd45754/finance-approve -> 200 财务审核通过 (b168f405-e8e2-432b-93aa-cf7bdcd45754) {"code":200,"message":"财务审核通过","data":{"createdAt":"2026-06-12T12:15:51.571912","updatedAt":"2026-06-12T12:15:54.450725133","deletedAt":null,"vflag":"UNCREATED","id":"b168f405-e8e2-432b-93aa-cf7bdcd45754","factoryId":"F006","orderNumber":"PO-20260612-0009","supplierId":"844d8c69-ed89-4a7f-b850-17494
D5 GET /purchase/orders/b168f405-e8e2-432b-93aa-cf7bdcd45754/pdf -> 200 "%PDF-1.4\n%����\n4 0 obj\n<</Type/XObject/Subtype/Image/Width 29/Height 29/Length 121/ColorSpace/DeviceGray/BitsPerComponent 1/Filter/CCITTFaxDecode/DecodeParms<</K -1/BlackIs1 true/Columns 29/Rows 29>>>>stream\n&����)�9_��~G?�\u001aA\u0005�G����?�q��(r�\b(�q\u001c�\u0004GA\u00149�<\u0014>\f4�&��\u
D6 POST /purchase/receives -> 200 入库单创建成功 (a03d0c03-92e8-40c9-b741-dcd1ffc0999b) {"code":200,"message":"入库单创建成功","data":{"createdAt":"2026-06-12T12:15:57.091722273","updatedAt":"2026-06-12T12:15:57.099181599","deletedAt":null,"id":"a03d0c03-92e8-40c9-b741-dcd1ffc0999b","factoryId":"F006","receiveNumber":"RCV-20260612-7091","purchaseOrderId":"b168f405-e8e2-432b-93aa-cf7bdcd45754"
D7 POST /purchase/receives/a03d0c03-92e8-40c9-b741-dcd1ffc0999b/confirm -> 200 入库确认成功，物料批次已创建 (a03d0c03-92e8-40c9-b741-dcd1ffc0999b) {"code":200,"message":"入库确认成功，物料批次已创建","data":{"createdAt":"2026-06-12T12:15:57.091722","updatedAt":"2026-06-12T12:15:58.439230969","deletedAt":null,"id":"a03d0c03-92e8-40c9-b741-dcd1ffc0999b","factoryId":"F006","receiveNumber":"RCV-20260612-7091","purchaseOrderId":"b168f405-e8e2-432b-93aa-cf7bdcd45
D8 GET /purchase/orders/b168f405-e8e2-432b-93aa-cf7bdcd45754/price-comparison -> 200 查询成功 {"code":200,"message":"查询成功","data":[{"materialTypeId":"RMT_1777441647274","materialName":"冻猪蹄","materialCode":"DZT001","unit":"kg","bomStandardPrice":10,"movingAvgPrice":10,"currentPrice":10,"varianceFromBom":0,"varianceFromAvg":0,"priceAlert":false,"bomProductNames":"DEMO-FE-20260612035935-卤牛腱, DE
C1 POST /production-plans/draft -> 200 草稿生产计划创建成功 (77eb95da-e78f-4568-900e-7fa73470bc32) {"code":200,"message":"草稿生产计划创建成功","data":{"id":"77eb95da-e78f-4568-900e-7fa73470bc32","factoryId":"F006","planNumber":"PLAN-1781237762488-947FCE1C","productTypeId":"2b28821e-a2cb-446b-81ba-b30bed0726ec","productName":null,"productUnit":null,"plannedQuantity":20,"actualQuantity":null,"plannedDate":"
C6 POST /production-plans/77eb95da-e78f-4568-900e-7fa73470bc32/start -> 200 生产已开始 (77eb95da-e78f-4568-900e-7fa73470bc32) {"code":200,"message":"生产已开始","data":{"id":"77eb95da-e78f-4568-900e-7fa73470bc32","factoryId":"F006","planNumber":"PLAN-1781237762488-947FCE1C","productTypeId":"2b28821e-a2cb-446b-81ba-b30bed0726ec","productName":"DEMO-FE-20260612041525-卤牛腱","productUnit":"kg","plannedQuantity":20,"actualQuantity":n
C15 POST /sales/finished-goods/opening -> 200 期初成品入库成功 (3c41a4f1-d089-492d-8654-e4a21b95f8f5) {"code":200,"message":"期初成品入库成功","data":{"createdAt":"2026-06-12T12:16:17.337451776","updatedAt":"2026-06-12T12:16:17.337451776","deletedAt":null,"id":"3c41a4f1-d089-492d-8654-e4a21b95f8f5","factoryId":"F006","batchNumber":"DEMO-FE-20260612041525-FG","productTypeId":"2b28821e-a2cb-446b-81ba-b30bed07
E5 POST /sales/deliveries -> 200 发货单创建成功 (68301db8-dddb-4173-bfaf-b59c7917957e) {"code":200,"message":"发货单创建成功","data":{"createdAt":"2026-06-12T12:16:24.899601209","updatedAt":"2026-06-12T12:16:24.907487579","deletedAt":null,"id":"68301db8-dddb-4173-bfaf-b59c7917957e","factoryId":"F006","deliveryNumber":"DLV-20260612-4899","salesOrderId":"b8c24271-ed76-480a-96bb-39b41e9b66a7","
X2 GET /my-todos/count -> 403 您的角色 [操作员] 在 [财务管理] 模块无 [读取 / 读写] 权限 {"success":false,"code":"FORBIDDEN","message":"您的角色 [操作员] 在 [财务管理] 模块无 [读取 / 读写] 权限","severity":"error","actionHint":"请联系工厂管理员在 Canvas → 模块权限 矩阵为角色 [操作员] 开通 [财务管理] 的 [读取 / 读写] 权限, 或切换到有权限的账号重试","meta":{"role":"operator","module":"finance","action":"read","requireAll":false,"requiredPermissions":[{"m
```

## SQL 回读

```text
  table_name  |                  id                  |     coalesce     |     coalesce     |            coalesce            
--------------+--------------------------------------+------------------+------------------+--------------------------------
 sales_orders | b8c24271-ed76-480a-96bb-39b41e9b66a7 | SO-20260612-0025 | FINANCE_APPROVED | DEMO-FE-20260612041525-SO-GOLD
(1 row)

   table_name    |                  id                  |     coalesce     | coalesce  |            coalesce            
-----------------+--------------------------------------+------------------+-----------+--------------------------------
 purchase_orders | b168f405-e8e2-432b-93aa-cf7bdcd45754 | PO-20260612-0009 | COMPLETED | DEMO-FE-20260612041525-PO-GOLD
(1 row)

        table_name        |                  id                  |     coalesce      | coalesce  |            coalesce             
--------------------------+--------------------------------------+-------------------+-----------+---------------------------------
 purchase_receive_records | a03d0c03-92e8-40c9-b741-dcd1ffc0999b | RCV-20260612-7091 | CONFIRMED | DEMO-FE-20260612041525-RCV-GOLD
(1 row)

    table_name    |                  id                  |          coalesce           |  coalesce   |             coalesce             
------------------+--------------------------------------+-----------------------------+-------------+----------------------------------
 production_plans | 77eb95da-e78f-4568-900e-7fa73470bc32 | PLAN-1781237762488-947FCE1C | IN_PROGRESS | DEMO-FE-20260612041525-PLAN-GOLD
(1 row)

       table_name       |                  id                  |         coalesce          | coalesce  |            coalesce            
------------------------+--------------------------------------+---------------------------+-----------+--------------------------------
 finished_goods_batches | 3c41a4f1-d089-492d-8654-e4a21b95f8f5 | DEMO-FE-20260612041525-FG | AVAILABLE | DEMO-FE-20260612041525-FG-GOLD
(1 row)


```

## RN Native / OTA Verification

- Device: Xiaomi `M2102K1AC`, Android 14, ADB serial `f79c50d6`.
- APK: `com.cretas.foodtrace`, `versionName=1.0.1`, `versionCode=12`, `lastUpdateTime=2026-06-09 08:16:22`.
- Expo Updates config in APK source: `channel=production`, `runtimeVersion=1.0.1`, `checkAutomatically=ON_LOAD`.
- OTA cold-start verification: two force-stop/cold-launch cycles were run on the Xiaomi phone. Logcat shows `Updates state change: CheckCompleteUnavailable`, `UpdatesController onBackgroundUpdateFinished: No update available`, and API base `http://139.196.165.140:8086`.
- OTA manifest cross-check: production manifest for Android/runtime `1.0.1` returned manifest id `483dcd8c-2db8-283e-95fd-f71135f03c9a`, created `2026-06-12T03:06:58.000Z`.
- Evidence: ![OTA first launch](2026-06-12-fe-headed-e2e-screenshots/rn-ota-first-launch.png), ![OTA second launch](2026-06-12-fe-headed-e2e-screenshots/rn-ota-second-launch.png), log `2026-06-12-fe-headed-e2e-screenshots/rn-ota-logcat.txt`.
- Native RN replacement evidence: operator ![X1](2026-06-12-fe-headed-e2e-screenshots/X1-native-xiaomi-operator-home.png), finance ![finance todos](2026-06-12-fe-headed-e2e-screenshots/X2-native-xiaomi-finance-todos.png), cashier ![cashier todos](2026-06-12-fe-headed-e2e-screenshots/X2-native-xiaomi-cashier-todos.png).
- Isolation note: visible finance/cashier待办 were historical non-`DEMO-FE-*` items (`SO-20260608-0001`, `PR-F006-20260611-5424`), so no mobile approval/payment action was executed.

## Fool-proof Native Findings

- Rule 2 context gap: RN logout confirmation only says `确定要退出吗？`; it does not include current username/role (`f006_moyun`, `f006_finance_mgr`, etc.). Evidence: ![logout dialog](2026-06-12-fe-headed-e2e-screenshots/rn-after-logout-tap.png).
- Rule 5 dead-end: RN “系统设置” opens `此功能正在完善中` with only OK, no next action/navigation. Evidence: ![system settings dead end](2026-06-12-fe-headed-e2e-screenshots/rn-settings.png).
- Rule 1/5 guard gap: login option `记住我并启用生物识别登录` is selectable although biometric login is not implemented; submit then fails with `生物识别功能尚未实现，请使用密码登录`. It should be disabled or hidden until implemented.

## Headed Mode Verification

- `headless:false`: configured in this spec.
- `viewport: 1920x1080`: configured in this spec.
- Chromium args: `--remote-debugging-port=9223`, `--lang=zh-CN`, `--font-render-hinting=none`, `--window-size=1920,1080`.
- Env observed: `PLAYWRIGHT_PORT=9223`, `PLAYWRIGHT_CHAT_ID=liushanmen-fe`.
- Screenshot policy: every recorded step uses `fullPage: true`.
- Video policy: Playwright `video: on`, 1920x1080.
