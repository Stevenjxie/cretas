# Aliyun SMS prod 配置 — Steve self-service

**目标**: 把 Cretas notification system 从 DB-only logging 切到真发短信。

**Why this is self-service**: 我不应 handle 凭证 (chat transcript 风险, per `CREDENTIAL-MANAGEMENT.md` HARD)。Steve 自己 SSH 编辑 `.env.prod` 最安全。

---

## Phase 1: Aliyun 控制台审批 (多天, 不阻塞)

如果还没做：
1. 阿里云 → 短信服务 → 国内消息 → **签名管理** → 添加签名（如 "白垩纪食品"）→ 等待审批（通常 1-2 工作日）
2. 同处 → **模板管理** → 添加模板（如订单提醒/工单通知/库存预警 — 至少 1 个）→ 等待审批
3. 拿到 `sign-name` (签名名称) 和 `template-code` (模板 CODE，形如 `SMS_XXXXXXX`)

**注意**: 阿里云 SMS 用 **账号 B**。AccessKey ID + Secret 从 `.claude/rules/aliyun-credentials.md` 账号 B 段落取（2026-04-22 rotation 后的值）。不在本 doc 落盘 — 防 GitHub push protection block + transcript 泄露。

---

## Phase 2: 服务器配置 (审批通过后, 5 分钟)

SSH 到 47.100.235.168，编辑 `.env.prod`：

```bash
ssh root@47.100.235.168
nano /www/wwwroot/cretas/.env.prod
```

添加这 5 行（替换 `<...>` 为审批结果）：

```env
# Aliyun SMS — 2026-05-21 enabled
NOTIFICATION_GATEWAY=aliyun-sms
ALIYUN_SMS_ACCESS_KEY_ID=<account-B AK from .claude/rules/aliyun-credentials.md>
ALIYUN_SMS_ACCESS_KEY_SECRET=<account-B Secret from .claude/rules/aliyun-credentials.md>
ALIYUN_SMS_SIGN_NAME=<审批通过的签名名称>
ALIYUN_SMS_TEMPLATE_CODE=<SMS_XXXXXXX>
```

Spring 属性映射 (FYI)：
| 环境变量 | Spring property |
|---|---|
| `NOTIFICATION_GATEWAY` | `notification.gateway` |
| `ALIYUN_SMS_ACCESS_KEY_ID` | `aliyun.sms.access-key-id` |
| `ALIYUN_SMS_ACCESS_KEY_SECRET` | `aliyun.sms.access-key-secret` |
| `ALIYUN_SMS_SIGN_NAME` | `aliyun.sms.sign-name` |
| `ALIYUN_SMS_TEMPLATE_CODE` | `aliyun.sms.template-code` |

Endpoint 默认 `dysmsapi.aliyuncs.com`，无需配（如要改 region 加 `ALIYUN_SMS_ENDPOINT`）。

---

## Phase 3: 重启 + smoke

```bash
# 重启 (blue active 当前)
systemctl restart cretas-backend

# 等 ~80s Spring Boot 启动 + 验证 bean 注册
journalctl -u cretas-backend --since '2 min ago' --no-pager | grep -iE "aliyun|sms|notification" | head -20
# 期望看到: "AliyunSmsNotificationServiceImpl initialized" 或类似 bean log

# Health check
curl -s http://localhost:10010/api/mobile/health
# 期望: {"status":"UP", ...}
```

---

## Phase 4: 发 1 条测试短信

需要 Steve 手机号（或测试号）：

```bash
# 通过 AI Tool 发 (推荐, 走完整链路)
curl -X POST http://localhost:10010/api/mobile/ai/intent/execute \
  -H "Authorization: Bearer <f006_admin token>" \
  -H "Content-Type: application/json" \
  -d '{
    "intent": "send_notification",
    "params": {
      "channel": "SMS",
      "recipientPhone": "<your phone>",
      "messageType": "TEST",
      "data": {}
    }
  }'
```

或直接看 `service/notification/impl/AliyunSmsNotificationServiceImpl.java` 找 `sendSms()` 入口对应的 endpoint。

收到短信 ✅ = 配置成功。

---

## 回滚

若有问题，移除这 5 行 + restart → 自动降级回 `DbNotificationServiceImpl` (logging-only)，不破业务。

```bash
sed -i '/^NOTIFICATION_GATEWAY=/d; /^ALIYUN_SMS_/d' /www/wwwroot/cretas/.env.prod
systemctl restart cretas-backend
```

---

## 验证当前状态 (做之前先看一眼)

```bash
ssh root@47.100.235.168 "grep -iE 'sms|notification.gateway' /www/wwwroot/cretas/.env.prod"
# 当前 (2026-05-21): 0 hit → confirmed unconfigured

ssh root@47.100.235.168 "journalctl -u cretas-backend --since today | grep -iE 'aliyun.*sms|AliyunSmsNotification' | head"
# 当前: 0 hit → bean 没注册
```

完事告诉我，我落 memory `feedback_aliyun_sms_prod_config_done.md` 跟进。
