# 餐饮外部平台模拟器设计（Mock Platform API）

**日期**: 2026-07-29
**状态**: 设计已确认，待写实现计划
**目标读者**: 实现本项目的三条并行线

---

## 1. 背景与目标

我们要验证「餐饮数据从外部平台实时接进系统」这条链路，但目前没有真实客户的 POS/平台授权可用。

更根本的问题是：**我们系统现在根本没有 API 拉取这条路**。真实的餐饮数据入口是文件上传——二维火 POS 导出 CSV/zip，`smartbi/ingestion/pos_router.py` 按文件名中文关键词路由（`营业概况报表`→`daily_summary_writer`、`详细日报表`→`bill_flow_writer`、`商品销售明细表`→`product_summary_writer`），写进 Silver 层的 `fact_pos_transaction` / `fact_pos_item` / `fact_pos_payment` / `fact_pos_discount`。整条链路是天粒度批处理。

所以本项目要同时交付两件事：

1. **模拟外部世界**：一个部署在 139、与本系统物理隔离的服务，按真实开放平台的接口风格暴露餐饮数据。
2. **打通拉取链路**：在 47 侧新建 connector 框架，把外部平台数据接进 Silver → Gold → 看板 / 问答。

**成功判据**：模拟端持续产生订单的同时，看板上的数字分钟级持续变化；并且能正确回答「本月全部门店外卖实得多少」这类需要跨平台对账的问题。

### 非目标

- 不接任何真实平台的真实授权。
- 不改造现有文件上传入口（它继续服务真实客户）。
- 不做平台侧的商家后台 UI。

---

## 2. 已确认的需求决策

| 项 | 决策 |
|---|---|
| 接口形态 | JSON 订单 API + 新建拉取链路（不是仿文件导出复用旧链路） |
| 数据量 | **每店 200 单/天，10 店共 2000 单/天** |
| 生成节律 | 按营业时段实时滴出（常驻进程），非每日一次性批量 |
| 项目拆分 | 三类平台**一起设计**，然后并行实现 |
| 仿真度 | 按**最真实**的平台风格（各平台各自的鉴权、包装、错误码） |
| 回调 | **v1 就做** webhook |
| 团购/外卖数据落点 | 新建 fact 表，支撑「渠道真实利润」（抽佣/补贴/券折损/实得率） |
| 租户 | **新建专用租户 `MOCK_REST`**，10 家店自有 `dim_store`，不写 DEMO_REST |

---

## 3. 架构与隔离边界

```
        139 (模拟外部世界)                    47 (我们的系统)
┌──────────────────────────────┐      ┌────────────────────────────┐
│ cretas-mock-platform         │      │ cretas-python              │
│  ├ 世界模型 (SQLite)          │◀─拉─│  connector 框架            │
│  │   store/dish/order/…      │      │   ├ meituan adapter        │
│  ├ 生成器 (常驻, 按分钟推进)  │──推─▶│   ├ douyin adapter         │
│  └ 三层平台适配               │      │   └ keruyun adapter        │
│      ├ /meituan/*  (SHA1签名) │      │        ↓                   │
│      ├ /douyin/*   (header)   │      │   Silver → Gold → 看板     │
│      └ /keruyun/*  (token+sign)│     │   租户 MOCK_REST           │
└──────────────────────────────┘      └────────────────────────────┘
```

### 3.1 隔离铁律（验收项，不是建议）

模拟端必须是一个**外部系统**，不是本系统的一个模块：

- 不持有任何 PostgreSQL 凭证
- 不 import 任何 `smartbi.*` / `common.*` 模块
- 自带 SQLite 存储，与 `smartbi_prod_db` 无任何连接
- 唯一出口是 HTTP

**验收命令**（必须零命中）：

```bash
grep -rE "smartbi|psycopg|asyncpg|cretas_prod_db|smartbi_prod_db" mock-platform/ --include="*.py"
```

一旦这条命中，模拟器就退化成了「我们自己写给自己看的假数据」，失去全部验证价值。

### 3.2 部署位置

| | 位置 | 形态 |
|---|---|---|
| 模拟端 | **139** (`139.196.165.140`) | systemd `cretas-mock-platform`，FastAPI + SQLite，nginx 反代 |
| connector | **47** (`47.100.235.168`) | 现有 `cretas-python` 服务内新增包，随服务启动 |

139 是网关机（Nginx + web-admin + showcase 静态站），本来就不跑业务后端，适合放「外部世界」。参见 `.claude/skills/server-operations`。

---

## 4. 模拟端：世界模型

### 4.1 为什么不是三个独立 mock

模拟端内部只有**一份事实**：门店、菜品、订单、订单项、支付、券、评价、结算。三个平台 router 暴露的是这份事实的不同切面，是**派生**关系：

| 订单类型 | POS | 美团 | 抖音 |
|---|---|---|---|
| 堂食现金/扫码 | ✓ | — | — |
| 外卖 | ✓（渠道=外卖） | 结算单（含抽佣） | — |
| 团购到店核销 | ✓ | 券核销 + 结算 | 券核销（两步） |

因此同一笔外卖订单在三边的金额**天然应该闭合**。对不上就说明我们的 adapter 字段映射或 connector 幂等有问题——这正是本项目要暴露的一类缺陷。

如果改成三个独立进程各造各的数据，三边永远对不上或永远对得上都没有信息量，「渠道真实利润」这条主线就失去意义。

### 4.2 实体

```
store(10)          门店：编号、名称、业态（旗舰/社区/商场）、客流基准系数
dish               菜品：名称、类别、售价、成本、是否可团购
order              订单：门店、下单时间、渠道（堂食/外卖/团购核销）、状态
order_item         订单项：菜品、数量、单价、金额
payment            支付：方式（现金/微信/支付宝/平台代收）、金额
coupon             券：平台、面值、实付、核销状态、核销时间
review             评价：平台、评分、口味/环境/服务分项、内容、时间
settlement         结算：平台、账期、营收、抽佣、补贴、实得
```

### 4.3 生成器节律

常驻 asyncio 循环按分钟推进：

- **客流曲线**：午市 11:00–14:00、晚市 17:00–21:00 双峰
- **门店差异**：10 家店各带基准系数与曲线形状（商场店晚市更陡、社区店午市平缓）
- **配额**：每店 200 单/天，按曲线摊到分钟后加泊松噪声
- **回填**：`--backfill-days N` 一次性造历史订单，让看板一开始就有趋势与环比可看，不必等一个月

生成器是**唯一**的写入方，三个平台 router 只读世界模型。

---

## 5. 模拟端：三家平台的 API 表面

按各平台**真实**的开放接口约定实现，三家刻意保持不同——这正是 connector 要被压测的地方。

### 5.1 美团

- 系统参数：`appkey`、`sign`、`timestamp`、`version`
- 签名算法：所有参数（除 `sign`、`byte[]`、空值外）按参数名字典序排序 → 拼成 `参数1值1参数2值2` → 前置 `secret` → **SHA1 → 转小写**
- 需要 AppKey / AppSecret

### 5.2 抖音生活服务

- 域名风格：仅 HTTPS，`Content-Type: application/json` 固定
- 鉴权：OAuth2 `client_credentials` 换 client_token → 放 HTTP header `access-token`
- 统一响应：`data{error_code, description}` + `extra{error_code, description, sub_error_code, sub_description, logid, now}`
- `error_code = 0` 表示成功，非 0 失败
- **券核销保留两步语义**：验券准备（传券码/`encrypted_data` → 拿 `verify_token` 与加密券码）→ 验券（用 `verify_token` 执行核销）

两步核销是刻意保留的：它天然要求 connector 处理「准备成功但核销失败」的中间态，正好压测幂等。

### 5.3 客如云

- 鉴权：token + sign 计算
- 提供订单、会员、供应链风格的接口

客如云的公开文档比另外两家薄，实现时以「token + sign 两段式鉴权、与美团/抖音都不同」为准即可——本项目要的是**三家鉴权互不相同**这个性质，而不是逐字复刻客如云。

### 5.4 三家共有

- 增量游标分页（`since` + `cursor` + `has_more`）
- 限流：429 + 平台风格的限流错误体
- 各自风格的错误码体系

### 5.5 参考来源

- [美团到餐 API 接入文档](https://h5.dianping.com/app/bep-docs/sky-doc/canyinopenapi/daocan_api.html)
- [美团开放平台 SDK 自动生成技术与实践](https://tech.meituan.com/2023/01/05/openplatform-sdk-auto-generate.html)
- [抖音 OpenAPI 接口调用约定](https://developer.open-douyin.com/docs/resource/zh-CN/local-life/develop/preparation/openapiinterfacecallconvention)
- [客如云开放平台 API 接入流程](https://open.keruyun.com/docs/zh/ZMeVEXQBzPVmqdQu3lpr.html)

---

## 6. 我们这边：connector 框架 + 平台 adapter

### 6.1 职责切分

**框架**（三家共用）：

- 每平台每店的游标持久化与推进
- 幂等：`(platform, platform_order_no)` 唯一键
- 重试退避、429 处理
- **失败隔离**：一个平台故障不拖垮另外两个

**adapter**（每平台一个）：

- 签名 / 鉴权
- 分页参数拼装
- 字段映射到 Silver

### 6.2 调度

沿用现有 `cretas-python` 已有的常驻循环模式——`main.py` 启动时 `asyncio.create_task(_xxx_forever())`（narrative cache 清理、external benchmark 刷新都是这么写的），并复用现有的 uvicorn 启动锁防多 worker 重复拉取。

拉取周期：**默认 60 秒**一次增量，可配置。选 60 秒是为了让看板的变化肉眼可感；真实平台通常给到分钟级配额，这个频率不会触发限流。

---

## 7. 回调（webhook）

### 7.1 端点

`POST /api/platform-callback/{platform}`

### 7.2 三层校验，缺一不可

1. **源 IP 白名单**：只认 139
2. **HMAC-SHA256 验签**：签 `body + timestamp + nonce`，共享密钥走环境变量
3. **防重放**：timestamp 5 分钟窗口 + nonce 去重（Redis）

**该端点必须独立鉴权，不依赖 URL 中能否解析出 factoryId。**

这是 2026-07-29 匿名访问事故的直接教训：本仓的登录校验一度挂在「URL 能否解析 factoryId」上，导致 `/ai/*`、`/upload/*`、`/workflow/*`、`/system/*` 整类顶层路径对公网无鉴权，prod 实测匿名 POST `/ai/chat` 能拿到真实 LLM 回复（PR #1936 修复）。回调路径同样不含 factoryId，绝不能重蹈。

### 7.3 回调只作触发器，不作数据通道

回调体只携带「有新数据」的信号与游标提示，**实际数据仍走拉取**。

真实平台的回调是带数据的，这里刻意牺牲一点真实度：回调丢一次就永久少一笔数据，而改成触发器后，回调丢失由定时拉取兜底，两条路径最终指向同一个幂等写入。用一点点仿真度换掉一整类「数据永久丢失」故障。

---

## 8. 数据落点

### 8.1 复用现有（A / C）

| 数据 | 落点 | 状态 |
|---|---|---|
| POS 交易 / 订单项 / 支付 / 折扣 | `fact_pos_transaction` / `fact_pos_item` / `fact_pos_payment` / `fact_pos_discount` | 现成 |
| 评价 | `restaurant_reviews` / `restaurant_review_sources` / `dim_store_review_alias` | 现成，`gold/review_queries.py` 已在消费 |

### 8.2 新建（B）：`fact_channel_settlement`

```
factory_id / store_id / platform / biz_date / platform_order_no
gross_amount        平台口径营收
commission_amount   抽佣
coupon_discount     券折损
subsidy_amount      活动补贴
net_receivable      实得
settle_status / settle_date
```

RLS 照现有 fact 表：`ENABLE` + `FORCE ROW LEVEL SECURITY` + 四条 policy（SELECT / INSERT / UPDATE / **DELETE**）。

migration 走 `backend/python/smartbi/database/migrations/V<YYYYMMDD>_<NN>__*.sql`，由 `deploy-smartbi-python.sh` Step 3.5 自动 apply（禁手动 psql DDL，见 server-operations skill）。

这张表撑起目标形态：

```
外卖营收      ¥390,513
  − 平台抽佣  ¥ 78,102  (20%)
  − 券折损    ¥ 31,240
  + 活动补贴  ¥  6,800
  = 实得      ¥287,971   实得率 73.7%
```

---

## 9. 错误处理：禁降级

本仓核心原则第 1 条是「禁止降级处理——不返回假数据，明确显示错误」。落到本项目：

- 拉取失败 → 明确失败 + 告警，**绝不写 0，也不静默跳过**
- 三边对账不平 → **显式标记不平**，不许挑一边的数当真值
- 平台限流 → 退避重试，重试耗尽后标记该窗口未同步，而非当作「无数据」

### 9.1 对账必须比期间口径，不只比金额

2026-07-29 验证月度报告时踩到一个 fail-closed 挡不住的错误类型：损耗 resolver **无视请求的时间窗**，问「2026年6月」返回的是「近 30 天」数据，数字一字不差。resolver 确实返回了数据，只是答的是另一个问题——fail-closed 只挡「没拿到数据」，挡不住「答了另一个时间窗」。

跨平台对账同理：「美团给的是自然月结算、POS 给的是营业日」这类口径错位会伪装成正常数据通过所有非空校验。**对账断言必须同时校验期间边界，不能只比金额是否相等。**

---

## 10. 测试策略

| 层 | 验什么 |
|---|---|
| 模拟端世界模型 | 派生一致性——同一笔外卖单在 POS / 美团 / 抖音 三边金额闭合 |
| adapter | 签名算法对拍，用各平台文档给出的示例向量 |
| connector 框架 | 游标推进、幂等重放、断点续拉、单平台故障不影响其他平台 |
| 回调 | 验签失败拒绝、过期 timestamp 拒绝、重放 nonce 拒绝、重复推送不重复写 |
| 隔离 | `grep -rE "smartbi\|psycopg\|asyncpg" mock-platform/` 零命中 |
| 端到端 | 模拟端生成 → 拉取 → Silver → Gold → 问「本月全部门店外卖实得多少」答对 |

---

## 11. 并行实现的三条线

三条线共享第 3、4 节（隔离边界 + 世界模型），需先落地世界模型骨架再并行。

| 线 | 内容 | 依赖 |
|---|---|---|
| **线 1：POS 交易** | 客如云风格 API + POS adapter + 现有 Silver 落点 | 世界模型骨架 |
| **线 2：团购核销与外卖** | 美团/抖音结算与核销 API + `fact_channel_settlement` migration + adapter | 世界模型骨架 |
| **线 3：点评内容** | 大众点评/抖音评价 API + review adapter + 现有 review 落点 | 世界模型骨架 |

**共同前置**（不可并行，必须先做）：世界模型 + 生成器 + 三家共用的鉴权/分页/限流骨架 + connector 框架 + 回调端点。

---

## 12. 待定项

- 模拟端对外端口与 nginx 路由前缀（139 上已有 web-admin 8086 与 showcase，需选不冲突的端口）
- `MOCK_REST` 租户的 10 家门店命名与业态分布
- 各平台抽佣率取值（可参考 `smartbi/knowledge/restaurant/pos/commission_rates.yaml` 现有配置）

这三项不阻塞设计，实现计划阶段确定。
