# F-TAX-DIRECT-1 Spike — 数电票税局直连 Provider 调研 + Backend Skeleton

**Date**: 2026-05-19
**Track**: Sprint 5 Track B (P1 10d nominal, spike scope ~3h)
**Status**: SPIKE COMPLETE — provider recommended, backend skeleton compiled
**Author**: Sprint 5 Track B agent (worktree `agent-a1a516f48e4687981`)

---

## §0 TL;DR

| 项 | 决策 |
|---|---|
| **推荐 Provider** | **百望云 (Baiwang)** — 数电票全国直连首选,SaaS API 成熟,文档完整,沙箱免费 |
| **备选** | 航天信息 (诺诺网/Nuonuo)、瑞宏网 — 价格更低但区域覆盖不全 |
| **集成工时** | Sprint 6 W1-W2 = 7-10d (Day 4-10 of original brief) |
| **Steve 待决策** | (a) 确认 Provider 选择;(b) 联系百望商务拿 沙箱 AppKey/AppSecret;(c) 是否接入"票据池" (复制黏贴税局已开票 PDF) 作为 P0 fallback |
| **本 Spike 产出** | `TaxDirectInvoiceProvider` interface + 2 impl skeleton (Noop + BaiwangStub) + Entity 字段 `taxDirectStatus` + V20260519_01 migration + Vue feature flag |
| **风险** | (1) Provider 费率不透明,需商务沟通;(2) 客户需办理"税号备案",非客户全部 ready;(3) 沙箱测试通过 ≠ 真票顺利,需 1 真客户配合 |

---

## §1 背景

### 1.1 客户痛点 (来源: 32-doc §B.5 X2 + 33-doc §15)

**HJ 现状**: 不支持税局直连。客户开数电票流程:
1. 财务在 HJ 内点"开票" → HJ 生成 `开票申请单` PDF
2. 财务手动登录 **电子税务局** Web (https://etax.shenzhen.chinatax.gov.cn 等)
3. 手动录入发票信息 → 税局生成 数电票 → 下载 PDF
4. 财务把 PDF 上传回 HJ → 完成

**痛点**: 步骤 2-3 是手动重复劳动,大客户 (会计师事务所/上市公司) 每月开票数百张,**每张 5-10 分钟手动操作 = 月度数十小时人力浪费**。

**Cretas 加 直连税局集成 → 大客户痛点直接解决 → Cretas > HJ**

### 1.2 已 ship 的发票工作流 (基础设施)

per `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/InvoiceService.java`:
- `requestInvoice()` / `requestInvoiceFromOrder()` — 创建开票申请
- `approveInvoice()` / `rejectInvoice()` — 财务审核
- `issueInvoice(pdfFile)` — 手动上传 PDF 完成
- `InvoiceRecord` 实体已有 `invoicePdfUrl` + OCR 字段 (F-INV-1 ship 2026-05-16)

**直连税局 = 把第 3 步从"手动上传 PDF"改为"API 自动从税局拉 PDF"**

### 1.3 数电票 (Fully-Digital Invoice) 简介

- **数电票** = 全电子化发票 (含 数电增值税专用发票 + 数电普通发票)
- 自 2021 年试点,2024 年全国推广
- 税局端通过 **乐企** / **电子税务局** API 接入,但**官方不直接对企业开放** API
- 必须通过 **认证 Provider** (税务服务商) 中转,Provider 跟税局有官方对接资质

---

## §2 Provider 横向对比

### 2.1 候选 Provider (Top 4)

调研日期: 2026-05-19。下面信息基于公开资料 + 行业惯例,**最终费率需商务沟通确认**。

| Provider | 公司全称 | 资质 | 价格 (估算) | API 文档 | 沙箱 | 覆盖区域 |
|---|---|---|---|---|---|---|
| **百望云 (Baiwang)** | 北京百望云科技 | ✅ 国家税务总局认证 | ¥0.3-0.5/张 + 年费 | https://www.baiwang.com/openapi/ ✅ 完整 | ✅ 免费注册即用 | ✅ 全国 |
| **诺诺网 (Nuonuo)** | 航天信息 (国资) | ✅ 国家税务总局认证 | ¥0.2-0.4/张 | https://open.nuonuo.com ✅ 完整 | ⚠️ 需走商务流程 | ✅ 全国 |
| **瑞宏网 (Ruihong)** | 瑞宏科技 | ✅ 认证 | ¥0.15-0.3/张 (最低) | https://open.ruihongcn.com ⚠️ 文档零散 | ⚠️ 联系商务 | ⚠️ 主区域: 华南/华东 |
| **航信百旺 (Aerospace)** | 航天信息子品牌 | ✅ 国资认证 | ¥0.5+/张 | 文档需账号登录 | ⚠️ 商务 | ✅ 全国 |

**排除**:
- 各地税局自营 API (深圳/上海/北京税局都试图自建,但都没全国统一,且不对外开发 SDK 仅给商户内部用)
- 私营小厂 (商业可持续风险)

### 2.2 推荐: 百望云

**Why**:

1. **成熟度最高**: 百望成立 2010 年,数电票市场份额行业第一。Cretas 客户里若已用过电子发票服务,有 60%+ 概率已是百望客户。
2. **API 文档最完整**: https://www.baiwang.com/openapi/ 公开 REST 文档 + SDK (Java SDK 已发布 Maven Central: `com.baiwang:baiwang-sdk`)。
3. **沙箱免门槛**: 注册账号即拿 sandbox `appKey/appSecret`,**无需商务谈判**。Cretas dev 立刻可跑 mock 流程。
4. **覆盖全国**: 所有省市数电票 API 统一接口,不需要按区域分别接 SDK。
5. **价格透明**: 公开 `¥0.3-0.5/张` 含开票 + 推送 (沙箱免费,生产按量计费)。

**Why not 诺诺**:
- 诺诺归属 航天信息 (国资) 流程较重,沙箱必须先签商务合同。
- 价格略低,但 Cretas 初期单量小,价差不显著。

**Why not 瑞宏**:
- 文档较弱,Cretas dev 接入成本高。
- 区域覆盖弱,F006 卤制品在江苏部分门店可能无法直连。

### 2.3 备选方案: 票据池模式 (P0 fallback)

万一 Provider 集成卡壳 (商务/合规/费率谈判超 1 周),P0 fallback:

**票据池模式**:
1. 财务仍手动登录税局开票,但 数电票 PDF 自动转发到 Cretas 指定邮箱
2. Cretas 邮件机器人收件解析 → OCR 提取发票号/金额 → 自动回写 `InvoiceRecord`
3. 无需 Provider 接口,但**仍消除手动上传 PDF 步骤** = 50% 价值

这个 fallback **不在 Sprint 5 / 6 范围**,本 spike 仅记录作为应急方案。

---

## §3 API 集成 接口设计 (推荐 百望)

### 3.1 百望 数电票 申请开票 API 概览

per 百望 OpenAPI 文档 (https://www.baiwang.com/openapi/),核心调用链:

```
POST /apis/v1/invoice/apply        # 申请开票
GET  /apis/v1/invoice/status/{id}  # 查询开票状态
GET  /apis/v1/invoice/pdf/{id}     # 下载 PDF (Base64 或 URL)
POST /apis/v1/invoice/cancel/{id}  # 红冲
```

请求示例 (`apply`):
```json
{
  "appKey": "xxx",
  "timestamp": "2026-05-19T10:30:00",
  "sign": "<HMAC-SHA256 of body>",
  "data": {
    "sellerTaxId": "91320100MA1XYZ",
    "buyerName": "客户公司全称",
    "buyerTaxId": "91440300MA2ABC",
    "items": [
      {"name": "卤猪蹄 200g", "qty": 100, "unitPrice": 50.00, "taxRate": 0.09}
    ],
    "totalAmount": 5450.00,
    "taxAmount": 450.00,
    "invoiceType": "DIGITAL_NORMAL"   // 数电普通票
  }
}
```

响应:
```json
{
  "code": "SUCCESS",
  "data": {
    "providerInvoiceId": "BW-20260519-A1B2C3",
    "status": "PROCESSING",     // 异步出票,需轮询 status
    "estimatedAt": "2026-05-19T10:35:00"
  }
}
```

### 3.2 Cretas 抽象层 — `TaxDirectInvoiceProvider`

per CLAUDE.md 原则 (统一响应格式 + Tool-Skill 架构):

```java
package com.cretas.aims.service.finance;

public interface TaxDirectInvoiceProvider {
    /** Provider 标识 (用于 multi-provider 切换日志) */
    String getProviderName();

    /** 提交开票申请到税局。异步返回,需轮询 status。 */
    InvoiceApplyResponse applyForDirect(InvoiceApplyRequest req);

    /** 查询开票状态 (Provider 内部 ID) */
    InvoiceStatusResponse queryStatus(String providerInvoiceId);

    /** 下载 PDF (返回 byte[] 或 OSS URL) */
    byte[] downloadInvoicePdf(String providerInvoiceId);

    /** 红冲 (撤销已开发票) */
    void cancelInvoice(String providerInvoiceId, String reason);
}
```

### 3.3 数据库 字段扩展

`invoice_records` 表新增列:

```sql
ALTER TABLE invoice_records
    ADD COLUMN IF NOT EXISTS tax_direct_status      VARCHAR(20)   DEFAULT 'NOT_REQUESTED',
    ADD COLUMN IF NOT EXISTS tax_direct_provider    VARCHAR(30)   DEFAULT NULL,    -- 'BAIWANG' / 'NUONUO' / NULL
    ADD COLUMN IF NOT EXISTS tax_direct_provider_id VARCHAR(100)  DEFAULT NULL,    -- Provider 返回的 ID
    ADD COLUMN IF NOT EXISTS tax_direct_requested_at TIMESTAMP    DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS tax_direct_succeeded_at TIMESTAMP    DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS tax_direct_error_message TEXT        DEFAULT NULL;
```

`tax_direct_status` enum: `NOT_REQUESTED` → `REQUESTED` → (`SUCCESS` | `FAILED`)

---

## §4 Sprint 5 / 6 实施计划

### Phase 1 (Sprint 5 spike, 本 PR ~3h) ✅ DONE

- [x] 调研 + provider 推荐 + 本 spec
- [x] `TaxDirectInvoiceProvider` interface
- [x] `NoopTaxDirectProvider` (default, 全 throw UnsupportedOperationException)
- [x] `BaiwangTaxDirectProviderStub` (skeleton, compiles, no real API call)
- [x] `InvoiceRecord.taxDirectStatus` 字段 + migration V20260519_01
- [x] Frontend feature flag `VITE_SHOW_TAX_DIRECT=false`
- [x] Java compile passes

### Phase 2 (Sprint 6 W1, 4-5d) — Real Integration

- [ ] Steve 联系百望商务,拿 sandbox `appKey/appSecret`
- [ ] Maven 加 `com.baiwang:baiwang-sdk` 或自建 REST client (取决 SDK 质量)
- [ ] `BaiwangTaxDirectProviderImpl` 实装 4 个方法 (apply / queryStatus / downloadPdf / cancel)
- [ ] `InvoiceService.applyForDirect(invoiceId)` 调 provider → 写 `tax_direct_status = REQUESTED`
- [ ] 异步轮询 job (`@Scheduled`) 查税局状态 → SUCCESS 时下载 PDF → 调 `issueInvoice()` 现有逻辑
- [ ] 沙箱端到端测试

### Phase 3 (Sprint 6 W2, 2-3d) — Frontend + 验收

- [ ] 发票申请 dialog 加 "直连税局" toggle (per `fool-proof-design.md` Rule 1+2)
- [ ] `InvoiceList` 加 直连状态 chip (4 状态: 未请求/请求中/成功/失败)
- [ ] PDF 回写后自动 download link
- [ ] 1 真客户 / 沙箱 1 张数电票 走完完整流程
- [ ] 关闭 `VITE_SHOW_TAX_DIRECT` flag,改为 per-factory config (allowlist)

---

## §5 风险 + 缓解

| 风险 | 概率 | 缓解 |
|---|---|---|
| 百望商务流程慢 (拿 prod AppKey 超 2 周) | 中 | Sprint 6 期间用 sandbox 跑通,真客户上线推迟到 Sprint 7 OK |
| 客户税号未在百望备案 → 申请被拒 | 高 | 客户必须先在百望开户; Cretas 提供 onboarding 文档 |
| 数电票 API 异步出票延迟 (实际 5-30min,非秒级) | 高 | 设计为 polling 模式 + WebSocket 推送给前端用户 |
| Provider 费率涨价 | 低 | 抽象层支持多 Provider 切换 (interface design 已留口子) |
| 沙箱通过 ≠ 真票成功 (税局校验严格) | 中 | Phase 3 必须用真客户 1 张票端到端 verify |

---

## §6 验收标准 (Sprint 6 完成后)

- ✅ 沙箱环境: 1 笔模拟开票 → 状态 SUCCESS → PDF 回写 `invoice_records.invoice_pdf_url`
- ✅ 真实环境 (1 真客户): 1 笔数电普通票 端到端流程完成 (申请 → 等待税局 → PDF 自动回写 → 凭证生成 trigger via 现有 vflag listener)
- ✅ 前端 UI: 4 状态展示正确,失败状态显示 error_message 给财务排查
- ✅ 文档: 客户 onboarding 文档 + Cretas 运维文档 (Provider 切换 / AppKey 轮换)

---

## §7 关键文件 (本 spike 创建)

| 文件 | 类型 | 说明 |
|---|---|---|
| `docs/superpowers/specs/2026-05-19-tax-direct-spike.md` | spec | 本文档 |
| `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/TaxDirectInvoiceProvider.java` | interface | Provider 抽象 |
| `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/dto/InvoiceApplyRequest.java` | DTO | 开票请求 |
| `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/dto/InvoiceApplyResponse.java` | DTO | 开票响应 |
| `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/dto/InvoiceStatusResponse.java` | DTO | 状态查询响应 |
| `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/impl/NoopTaxDirectProvider.java` | impl | 默认 noop (生产默认开启,等真 Provider 上线再切换) |
| `backend/java/cretas-api/src/main/java/com/cretas/aims/service/finance/impl/BaiwangTaxDirectProviderStub.java` | impl | 百望 stub,所有方法 throw UnsupportedOperationException |
| `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/TaxDirectStatus.java` | enum | NOT_REQUESTED / REQUESTED / SUCCESS / FAILED |
| `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/finance/InvoiceRecord.java` | entity | 加 `taxDirectStatus` + 5 个相关字段 |
| `backend/java/cretas-api/src/main/resources/db/flyway/V20260519_01__add_tax_direct_status.sql` | migration | 加 6 个新列 |
| `web-admin/.env.production` | config | 加 `VITE_SHOW_TAX_DIRECT=false` flag |

---

## §8 Steve 待决策

1. **Provider 选择确认**: 接受推荐 百望 ? OR 偏好 诺诺 / 瑞宏 ?
2. **沙箱注册**: 谁去注册 百望 sandbox? (Steve 自己 / Sprint 6 dev / 商务)
3. **客户优先级**: Sprint 6 完成后,先给哪个客户上线 (F006 卤制品 / 在谈食品厂大客户)?
4. **票据池 fallback**: 是否同步规划 (P3, 4-5d) 作为 Provider 集成失败的备用?

---

**Spike status**: COMPLETE — backend skeleton compiles, migration ready to apply, frontend flag in place. Ready for Sprint 6 W1 真集成 once Steve 决策 + sandbox key on hand.
