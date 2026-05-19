# Customer 17 Tab — HJ 对齐 + Sprint 5 F-1 缺口 spec

**触发**: Sprint 5 Track F F-1 (P1 5d → MVP 2h slice)
**来源**: R-HJ Round 13 §1 实测 17 named tabs vs Cretas main S-CUSTOMER-TAB-1 (Sprint 4 W1) ship 13/17
**owner**: Sprint 5 Track F → Sprint 6 follow-up
**status**: 2 tab ship (Sprint 5 F-1 MVP), 2 tab spec backlog (Sprint 6)

---

## 1. HJ 17 named tabs vs Cretas 现状

来源 URL: `https://crm.hongjian.com/crm/company/companyadd_pc.jsp?...&clientno=00000014`
方法: JS `querySelectorAll('li[role="tab"], .tab-item, ul.tabs li, .layui-tab-title li, ul.list li')` filter 1-20 char.

| # | HJ tab name | Cretas tab key | 现状 | Sprint 5 F-1 处置 |
|---|---|---|---|---|
| 1 | 跟踪记录 | `tracking` | ✅ ship Sprint 4 W1 | — |
| 2 | 微信记录 | `wechat` | ⚠️ placeholder | **Sprint 6 spec** (§3.1) |
| 3 | 好友添加记录 | (无对应 key) | ❌ missing | Sprint 7+ backlog |
| 4 | 通话记录 | `call` | ⚠️ placeholder | **Sprint 6 spec** (§3.2) |
| 5 | 短信记录 | `sms` | ⚠️ placeholder | Sprint 7+ backlog |
| 6 | 图片 | (折入 attachments) | ✅ ship (合并到附件) | — |
| 7 | 文件 | `attachments` | ✅ ship Sprint 4 W1 | — |
| 8 | 销售单 | `orders` | ✅ ship Sprint 4 W1 | — |
| 9 | 样品单 | `samples` | ✅ ship Sprint 4 W1 | — |
| 10 | 报价单 | `quotes` | ✅ ship Sprint 4 W1 | — |
| 11 | 产品 | `products` | ✅ ship Sprint 4 W1 | — |
| 12 | 活动管理 | `campaign` | ⚠️ placeholder | Sprint 7+ (CRM 模块依赖) |
| 13 | 商机管理 | `opportunity` | ⚠️ placeholder | Sprint 7+ (CRM 模块依赖) |
| 14 | 商品统计 | `itemStats` | ✅ ship Sprint 4 W1 | — |
| 15 | 收件地址 | `shipAddr` | ✅ ship Sprint 4 W1 | — |
| 16 | 谈话录音 | `audio` | ✅ **MVP stub ship Sprint 5 F-1** | UI ready, backend Sprint 6 |
| 17 | 邮件列表 | `email` | ✅ **MVP stub ship Sprint 5 F-1** | UI ready, backend Sprint 6 |

**Cretas extra (HJ 无)**:
- `aftersales` 售后 — Sprint 6+ 上线
- `priceMemory` 价格记忆 — Sprint 4 ship
- `salesUserHist` 业务员变更 — Sprint 4 ship

**Coverage 计算**:
- Sprint 4 baseline: 13/17 = **76.5%**
- Sprint 5 F-1 MVP: 15/17 = **88.2%** (+2 stub tabs)
- Sprint 6 后 (per §3 spec): 17/17 = **100%** (wechat + call backend)
- 全 100% 含 好友添加 / 短信 / 活动 / 商机 等待 CRM module 完成

---

## 2. Sprint 5 F-1 MVP ship 内容

### 2.1 `AudioRecordingsTab.vue` (谈话录音)
- 显示 "Sprint 6 上线" alert banner (录音上传 / ASR 转文字 / 关联跟踪记录 3 个 promise)
- 上传 button disabled + 4 位一体 sticky toast on click
- R5 next-action button "去跟踪记录补录" + R2 客户名/编号 header

### 2.2 `EmailsTab.vue` (邮件列表)
- 显示 "Sprint 6 上线" alert banner (IMAP/Exchange 接入 / 邮件模板 / 关联订单 3 个 promise)
- 发邮件 button disabled + 4 位一体 sticky toast on click
- R5 next-action button "去跟踪记录补录" + R2 客户名/编号 header

**两个 tab 都满足**:
- 防呆 R2 (sticky context with 客户名 + customerCode)
- 防呆 R5 (next-action navigate to tracking tab — 不 dead-end)
- 4 位一体 toast (sticky duration:0, showClose, backend-style message)
- No `as any`, typed `defineProps<{ customerId: string; customer: Customer | null }>()`

---

## 3. Sprint 6 backend wiring spec (2 tabs)

### 3.1 微信记录 (`wechat`)

**Backend**:
- `WechatRecordController` (P1 3d)
  - `GET /api/mobile/{factoryId}/customers/{customerId}/wechat-records?page=&size=`
  - `POST /api/mobile/{factoryId}/customers/{customerId}/wechat-records` (手工补录)
- `WechatRecord` entity (`wechat_records` table):
  - `id` UUID PK
  - `factory_id` (RLS)
  - `customer_id` FK
  - `wechat_id` String (对方微信号)
  - `nickname` String
  - `message_time` TIMESTAMP
  - `message_type` enum (TEXT/IMAGE/FILE/VOICE/VIDEO/LOCATION)
  - `content` TEXT (text 类型直存; 其他类型存 OSS URL)
  - `direction` enum (INBOUND/OUTBOUND)
  - `recorder_user_id` Long (手工补录人)
  - BaseEntity audit
- Migration `V20260710_01__wechat_records.sql`

**Integration option (Sprint 7+)**:
- 企业微信 callback webhook (msg event) → auto-populate
- 现 Sprint 6 仅手工补录 form

**Frontend**:
- 替换 `WechatTab.vue` (新建, mirror TrackingTab pattern)
- Dialog: 日期 / 微信号 / 消息类型 dropdown (R3) / 内容 textarea / 方向 radio
- Table: 时间 / 类型 / 方向 / 内容 / 微信号 / 补录人

**工作量**: 3d (1d entity + repo + service, 1d controller + DTO + 测试, 1d Vue + dialog + i18n)

### 3.2 通话记录 (`call`)

**Backend**:
- `CallRecordController` (P1 3d)
  - `GET /api/mobile/{factoryId}/customers/{customerId}/call-records`
  - `POST .../call-records` (手工补录 + 上传录音)
- `CallRecord` entity (`call_records` table):
  - `id` UUID PK
  - `factory_id` (RLS)
  - `customer_id` FK
  - `phone_number` String
  - `contact_person` String
  - `call_time` TIMESTAMP
  - `duration_seconds` Integer
  - `direction` enum (INBOUND/OUTBOUND/MISSED)
  - `audio_url` String NULL (OSS object key, 关联谈话录音 tab)
  - `notes` TEXT
  - `recorder_user_id` Long
  - BaseEntity audit
- Migration `V20260710_02__call_records.sql`

**Integration option (Sprint 7+)**:
- 呼叫中心 (e.g. 阿里通信 / 容联云) SDK callback → CDR auto-populate
- 现 Sprint 6 仅手工补录

**Frontend**:
- 替换 `CallTab.vue` (新建)
- Dialog: 时间 / 号码 / 联系人 / 方向 dropdown / 时长秒 / 备注 / 录音文件上传 (OSS)
- Table: 时间 / 号码 / 方向 / 时长 / 联系人 / 备注 / 录音播放

**工作量**: 3d (1d entity + 1d controller + 1d Vue)

**联动**:
- 通话记录上传录音 → `audio_url` 自动出现在「谈话录音」tab (Sprint 5 F-1 stub 时打开此功能)
- 「谈话录音」tab 改为读 `audio_records` 联合视图 (来自 call_records.audio_url + 手工上传)

---

## 4. Sprint 7+ backlog (5 tabs)

| Tab | 工作量 estimate | 主要依赖 |
|---|---|---|
| 好友添加记录 (HJ #3) | 2d | 企微 friend-event webhook |
| 短信记录 (HJ #5) | 2d | 短信平台 SDK (阿里/腾讯) |
| 活动管理 (HJ #12) | 5d | **CRM 模块依赖** — campaign entity + ROI 追踪 |
| 商机管理 (HJ #13) | 5d | **CRM 模块依赖** — opportunity stage + pipeline |

总后续工作: 14d (Sprint 7-8 单独 track).

---

## 5. R-HJ Round 13 §1 verbatim 引用

> **实测 17 tab 列表** (跟 Round 11 §O.6 一致, 修正 Round 11 baseline 21):
> 1. 跟踪记录 (id=tabcurrent, default active)
> 2. 微信记录 (id=weixin_msg)
> 3. 好友添加记录 (id=weixin_friend_add_log)
> 4. 通话记录 (id=call_his)
> 5. 短信记录 (id=sms_his)
> 6. 图片
> 7. 文件
> 8. 销售单
> 9. 样品单
> 10. 报价单
> 11. 产品
> 12. 活动管理
> 13. 商机管理
> 14. 商品统计
> 15. 收件地址
> 16. 谈话录音
> 17. 邮件列表
>
> **onclick**: 全部 `TabChange(event)` 统一 dispatcher.
> **Finding**: Round 11 baseline 估 21 偏高 4 项. 实测 17. Cretas S-CUSTOMER-TAB-1 已 ship 13/17 = **76% covered**

Sprint 5 F-1 把 coverage 推到 88% (15/17), Sprint 6 拿下 100% (17/17), 其余 HJ 没列但 Cretas 想做的 (售后 / 价格记忆 / 业务员变更) 是 Cretas 额外加分项.
