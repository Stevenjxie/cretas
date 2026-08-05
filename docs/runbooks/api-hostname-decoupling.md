# 把生产 API 从 www 迁到 api. 子域

**状态**: 基础设施已就绪(2026-08-05), 剩下发 App 新版这一步
**排查日期**: 2026-08-05

---

## 为什么会有这件事

`www.cretaceousfuture.com` 这个 nginx vhost **同时承载营销站和生产 API**:

- 官网全部页面
- `location /api/mobile/` → `proxy_pass http://cretas_backend`(47 上的 Java 后端)

生产手机 App 的 API 基址就是它 —— `frontend/CretasFoodTrace/.env.production`:

```
REACT_APP_API_URL=https://www.cretaceousfuture.com
```

日志实测(2026-08-05): `okhttp/4.9.2` 每天 **1000~1500 次**真实调用走这个主机。

**风险**: 任何针对营销站的 nginx 改动(主机跳转 / 缓存策略 / 安全头)都可能打断 App。
2026-08-05 做 HTTP→HTTPS 时, 原计划顺手加 `www → apex` 规范化 —— 如果没先查日志和
`.env.production`, 上线后 App 的每个 POST 都会被 301 打断, 跨主机还可能丢认证头,
工厂用户直接用不了。**这条差一点就发生了。**

---

## 基础设施: ✅ 已就绪 (2026-08-05 完成)

| 项 | 状态 |
|---|---|
| nginx vhost | ✅ `api.cretaceousfuture.com.conf`(2026-05-15 建), 自带 HTTP→HTTPS, 扫描器拦截比 www 那份更严 |
| DNS A 记录 | ✅ `api` → `139.196.165.140`, TTL 600(2026-08-05 添加, RecordId 2084962776417793024) |
| TLS 证书 | ✅ Let's Encrypt ECC, 有效期至 **2026-11-03** |
| **自动续期** | ✅ **已纳入 acme.sh**, 下次续期 2026-09-03 |
| 端到端实测 | ✅ 返回 HTTP 401, 与 www **除 timestamp 外逐字段一致**(同一个后端) |

### ⚠️ 当初为什么会过期(根因, 别再犯)

acme.sh 管着所有其他子域(admin / aiassist / centerapi / www / ota / test / download)
并自动续期, **唯独 api 不在里面** —— 2026-01-08 那张证书是从别处签的(阿里云 SSL 服务,
对应 DNS 里那条 `_dnsauth.api` TXT 记录), 游离在自动续期体系外, 于是 2026-04-07 过期后
无人察觉, 一直挂到 8 月。

现已改用 acme.sh + DNS-01(阿里云 API)重签, 纳入统一续期。
**判据: 新增子域证书后, 用 `acme.sh --list` 确认它出现在列表里, 否则就是游离状态。**

签发命令(留档, 将来加子域可照抄 —— DNS-01 不受 vhost 里 80 端口 301 的影响):

```bash
/root/.acme.sh/acme.sh --issue --dns dns_ali -d <子域> --keylength ec-256 --server letsencrypt
/root/.acme.sh/acme.sh --install-cert -d <子域> --ecc \
  --key-file       /www/server/panel/vhost/cert/<子域>.key \
  --fullchain-file /www/server/panel/vhost/cert/<子域>.pem \
  --reloadcmd      "nginx -t && nginx -s reload"
```

---

## 剩下的步骤

1. **改 App 配置**: `frontend/CretasFoodTrace/.env.production`
   → `REACT_APP_API_URL=https://api.cretaceousfuture.com`
2. **发 App 新版**
3. **⚠️ 盯流量归零, 不要按时间猜**:
   ```bash
   ssh root@139.196.165.140 \
     'grep "$(date +%d/%b/%Y)" /www/wwwlogs/www.cretaceousfuture.com.log | grep -c "okhttp/4"'
   ```
   这个数字降到 0 才说明旧版本用户全部更新完。
   **在此之前 www 上的 `/api/` 代理一行都不能动。**
4. 之后 www 才自由: 主机规范化 / 缓存策略 / 安全头随便改

验证命令(现在就能跑, 应返回 401 且不需要 `-k`):

```bash
curl -s -o /dev/null -w "%{http_code} 证书=%{ssl_verify_result}\n" \
  https://api.cretaceousfuture.com/api/mobile/LIUSHANMEN/product-types/options
```

---

## 值不值得做

**不急做的理由**: 目前零故障; 双主机的 SEO 问题已由各页 `<link rel=canonical>` 解决
(这是标准且充分的做法); 切换要等用户更新完, 中间任何一步出错都是工厂用户用不了。

**值得做的理由**: 不是 SEO, 是**营销站和生产 API 不该共用一个 vhost**。分开之后,
改官网再也不会有打断 App 的可能。基础设施已经备好, 什么时候发版什么时候切。

---

关联: `.claude/skills/server-operations`(部署与服务器规范)、
`.claude/skills/aliyun-operations`(阿里云)、`platform/`(官网, 与 www vhost 同一份配置)
