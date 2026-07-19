# 钉钉机器人 PoC 接入指南 (Track B1 Day 6)

> **状态**: Track B1 Day 1-5 已完成 (5 commits / 46/46 单测 / `feature/asap-track-b1-c-ai-1`)
>
> **Day 6 阻塞点**: 需要 F006 (六扇门) 钉钉应用的 7 项配置 → 然后 deploy + E2E + 开 PR
>
> **预计 Day 6 总耗时**: 2-3 小时 (含 1 小时跟 IT 沟通拿凭证)

---

## §1 你 (Steve) 要从六扇门 IT 拿的 7 项凭证

跟张权 / 六扇门 IT 微信沟通模板:

```
张总, Cretas 钉钉机器人 PoC 后端代码已完成 (Day 1-5),
需要你们 IT 配合一下, 提供 7 项钉钉应用配置, 就能上线测试:

1. DINGTALK_APP_KEY (F006 钉钉应用的 AppKey)
2. DINGTALK_APP_SECRET (用于钉钉发来的消息签名校验)
3. DINGTALK_CORP_ID (六扇门企业 ID)
4. DINGTALK_OUTBOUND_WEBHOOK_URL (群机器人接收 URL, 形如
   https://oapi.dingtalk.com/robot/send?access_token=XXX)
5. DINGTALK_OUTBOUND_WEBHOOK_SECRET (群机器人独立 secret,
   注意跟 APP_SECRET 不同)
6. F006 测试群 conversationId (用作默认推送目标, 可选)
7. 您方便提供一个 F006 钉钉用户 → Cretas 用户的对应表吗?
   格式: 钉钉 senderId XXX → Cretas 用户名 XXX
   (用于把钉钉群里说话的人映射到 Cretas 权限)

我们这边收到后 1-2 小时就能上测试环境, 您派一个人在
F006 测试群 @ Cretas 机器人 试一下就行.
```

**他们提供给你的格式**应该是:

```
DINGTALK_APP_KEY=dingxxxxxxxxxxxxxxxx
DINGTALK_APP_SECRET=<REDACTED_REVOKED_CREDENTIAL>
DINGTALK_CORP_ID=dingxxxxxxxxxxxxxxxx
DINGTALK_OUTBOUND_WEBHOOK_URL=https://oapi.dingtalk.com/robot/send?access_token=xxxxxxxxxxxx
DINGTALK_OUTBOUND_WEBHOOK_SECRET=SECxxxxxxxxxxxxxxxxxxxxxxxxxx
F006_TEST_GROUP_CONVERSATION_ID=cidxxxxxxxxxxxxxxx (可选)
```

---

## §2 拿到凭证后, 你做 4 步

### Step 1: 写入测试环境 .env (~5 min)

```bash
# 登录测试服务器
ssh root@47.100.235.168

# 编辑 test 环境配置文件
cd /www/wwwroot/cretas
vi .env.test

# 末尾追加:
DINGTALK_APP_KEY=...
DINGTALK_APP_SECRET=...
DINGTALK_CORP_ID=...
DINGTALK_OUTBOUND_WEBHOOK_URL=...
DINGTALK_OUTBOUND_WEBHOOK_SECRET=...

# 保存退出, 权限 600
chmod 600 .env.test
```

### Step 2: 部署到测试环境 (~30 min)

```bash
# 本地执行 (从 worktree 内)
cd /c/Users/Steve/my-prototype-logistics
git fetch origin
git checkout feature/asap-track-b1-c-ai-1
git pull origin feature/asap-track-b1-c-ai-1

# 部署到 test
./scripts/deploy/deploy-backend.sh --env test
```

**预期结果**:
- `cretas-backend-test` (port 10011) 重启成功
- Flyway 应用 `V20260516_0X__dingtalk*.sql` (Track B1 写的 migration)
- 日志显示 "Bean DingTalkSendService initialized" + "Bean DingTalkRetryScheduler scheduled"

**验证健康**:
```bash
curl -s http://47.100.235.168:10011/api/mobile/health
# 期望: {"success":true,"data":{"status":"UP"...}}
```

### Step 3: 在六扇门钉钉应用 console 配置 Outgoing Webhook (~10 min, 客户 IT 操作)

让六扇门 IT 在他们的钉钉开放平台 (开发管理后台) 配:

```
应用 → F006 应用 → 消息接收 → 配置 Outgoing Webhook:

接收 URL: https://test.cretaceousfuture.com/api/dingtalk/webhook/inbound?factoryId=F006
签名验证: 开启
APPSECRET: (使用 §1 第 2 项 DINGTALK_APP_SECRET)
```

**注意**: URL 必须是 HTTPS, test 环境用 `test.cretaceousfuture.com` 子域 (已 nginx 配好)。

### Step 4: F006 测试群 E2E 验证 (~20 min)

让六扇门派一个 F006 用户在测试群里:

#### 测试 1 — 正向: @ 机器人查询
```
@ Cretas机器人 查询今天的生产任务
```

**期望**:
- 5 秒内, 机器人在群里回复任务列表 (或"今日无生产任务")
- 日志: `tail -f /www/wwwroot/cretas/cretas-test.log | grep DingTalk`
  - 看到 `Inbound from senderId=xxx`
  - 看到 `AIChat invoked, intent=PRODUCTION_BATCH_LIST`
  - 看到 `Outbound SENT, errcode=0`

#### 测试 2 — AIInsightCard 异常推送
触发库存告警 (后端模拟一条 raw_material_types stock=0):
```bash
curl -X POST http://47.100.235.168:10011/api/mobile/F006/alerts/manual-trigger \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"type":"INVENTORY_LOW","severity":"WARN","material":"测试物料"}'
```

**期望**: F006 测试群收到 `[WARN] [AIInsightCard] 测试物料 库存不足` 类似消息

#### 测试 3 — 失败重试机制
临时改 webhook URL 为错的 (在 .env.test):
```
DINGTALK_OUTBOUND_WEBHOOK_URL=https://oapi.dingtalk.com/robot/send?access_token=INVALID
```
重启:
```bash
systemctl restart cretas-backend-test
```

发一条消息 (@机器人 查询库存)。预期:
- 日志: `Outbound FAILED, retryCount=1, nextRetryAt=...`
- 数据库: `select * from dingtalk_webhook_logs where status='FAILED' order by created_at desc limit 1;`

恢复正确 URL + 重启, 30 秒后:
- 日志: `Retry scheduler picked up 1 OUTBOUND, retrying...`
- 日志: `Outbound SENT after retry`
- 数据库: 那条 log status=SENT

#### 测试 4 — 审计日志查询 (RBAC)
```bash
# F006 管理员 token
TOKEN=$(curl -s -X POST http://47.100.235.168:10011/api/mobile/auth/login \
  -d '{"username":"f006_admin","password":"123456"}' | jq -r .data.accessToken)

curl -s "http://47.100.235.168:10011/api/mobile/F006/dingtalk/logs?page=1&size=10" \
  -H "Authorization: Bearer $TOKEN" | jq .
```

**期望**: 返回上面测试产生的 INBOUND + OUTBOUND 日志记录, 含 sender / content / status / retryCount

---

## §3 Day 6 全部完成后, 开 PR

```bash
# 在 worktree 内
cd .worktrees/asap-track-b1-c-ai-1

# 写好 PR body 含 4 个测试结果 + 截图
gh pr create \
  --title "[Track-B1] C-AI-1 钉钉机器人 PoC (Day 1-6 完整)" \
  --body "$(cat <<'EOF'
## 概述
Track B1 完成所有 6 天工作: 钉钉机器人 PoC, F006 测试群双向通信验证.

## 测试结果
- ✅ 5 commits 推送, 46/46 单测 PASS
- ✅ Test 环境部署成功 (cretas-backend-test 10011)
- ✅ 测试 1 (@ 机器人查询) — 5 秒内回复
- ✅ 测试 2 (AIInsightCard 推送) — 库存告警推 F006 群
- ✅ 测试 3 (失败重试) — 30s 后 retry SENT
- ✅ 测试 4 (审计日志 RBAC) — f006_admin 可查, 仓管员 403

## 文件改动
27 文件 / +2764 lines (per STATUS Day 5 summary)

## Phase 2 backlog (本 PR 不含)
- 24h 幂等
- 离线扫描队列
- 仓管员人脸签字
- 称重历史详情页

## Reviewer 操作建议
1. Read STATUS file: 04-最终决策/STATUS/TRACK_B1_STATUS.md
2. 验证 4 个测试在 test env 重跑 (跟 §3 测试步骤一致)
3. Merge to main → CI auto-deploy
EOF
)" \
  --base main
```

---

## §4 你可能遇到的坑

### 坑 1: 测试群 IP 白名单
**症状**: webhook 配好但消息进不来, 日志看不到 inbound
**解决**: 六扇门 IT 在钉钉应用 console 把 `47.100.235.168` 加入 IP 白名单 (或 `test.cretaceousfuture.com` 解析的真实 IP)

### 坑 2: HTTPS 证书
**症状**: 钉钉 console 报 webhook URL 不通
**解决**: 验 `curl -v https://test.cretaceousfuture.com/api/dingtalk/webhook/inbound?factoryId=F006`, 应该返 405 (GET not allowed, expects POST) — 不应是 SSL 错

### 坑 3: factoryId 参数
**症状**: 群里 @ 机器人没反应, 后端日志 `factoryId not found`
**解决**: webhook URL 必须含 `?factoryId=F006`, 钉钉 console 配置时**不要漏 query string**

### 坑 4: 限流误触发
**症状**: 测 3 次后机器人不回复
**解决**: 默认 20 次/分钟/群, 测试时如果触发, 等 1 分钟或调 `application.properties`:
```
dingtalk.rate-limit-per-minute=100  # 测试期临时调高
```

### 坑 5: 签名校验失败
**症状**: 日志 `Inbound signature mismatch`
**解决**: 检查 `.env.test` 里的 `DINGTALK_APP_SECRET` 跟钉钉应用 console 里的 APPSECRET **完全一致** (注意复制时是否多/少了字符)

---

## §5 安全考虑 (上 prod 前必做)

⚠️ **PoC 阶段, 暂不上 prod**。Phase 2 上 prod 前需要:

1. `DINGTALK_APP_SECRET` + `DINGTALK_OUTBOUND_WEBHOOK_SECRET` **必须** 用 Vault / 阿里云 KMS 管理, 不可写 .env 明文
2. 限流改 production-grade (Redis-based, 分布式)
3. Inbound webhook 加 rate limit (避免被 DDoS)
4. 审计日志 retention policy (按月分区或归档到 OSS)
5. RBAC 验仓管员/普通员工无法通过 API 推送任意消息到群
6. 监控告警 (DingTalkSendService 失败率 > 5% 报警)

---

## §6 钉钉文档参考

- 钉钉开放平台: <https://open.dingtalk.com/>
- Outgoing Webhook 文档: <https://open.dingtalk.com/document/orgapp/receive-message>
- 群机器人推送: <https://open.dingtalk.com/document/orgapp/custom-robot-access>
- 签名算法: <https://open.dingtalk.com/document/robots/customize-robot-security-settings>

---

## §7 完成 checklist

- [ ] 微信问张权拿 7 项凭证 (§1)
- [ ] 写入 `.env.test` (§2 Step 1)
- [ ] 部署 test (§2 Step 2)
- [ ] 客户 IT 在钉钉 console 配 Outgoing Webhook (§2 Step 3)
- [ ] F006 测试群跑 4 个 E2E 测试 (§2 Step 4)
- [ ] 开 PR (§3)
- [ ] Phase 2 backlog 8 项归档到 issue tracker
