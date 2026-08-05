# 把生产 API 从 www 迁到 api. 子域

**状态**: 未执行, 阻塞在 DNS(需域名控制台权限)
**排查日期**: 2026-08-05
**结论**: 不急。现在没有故障, 想做的时候按下面的顺序做。

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

## 现状: `api.` 是个半成品

| 项 | 状态 |
|---|---|
| nginx vhost | ✅ 存在(`api.cretaceousfuture.com.conf`, 2026-05-15 建) |
| 配置正确性 | ✅ 已实测: 绕过 DNS 用 `--resolve` 直连, 返回 **HTTP 401** 且响应体与 www **逐字一致** |
| HTTP→HTTPS | ✅ 该 vhost 自带 301 |
| 扫描器拦截 | ✅ 比 www 那份更严(`.git`/`.env`/wp-* 一律 444) |
| **DNS 解析** | ❌ **无记录**(其余 www/admin/ota/aiassist 都指向 139.196.165.140) |
| **TLS 证书** | ❌ **2026-04-07 已过期**(`SEC_E_CERT_EXPIRED`) |

验证命令(不需要 DNS 就能复现):

```bash
R="api.cretaceousfuture.com:443:139.196.165.140"
# 证书过期 → 连接失败
curl -sS --resolve "$R" https://api.cretaceousfuture.com/ 2>&1 | head -1
# 忽略证书 → 代理是好的, 返回 401
curl -sk --resolve "$R" "https://api.cretaceousfuture.com/api/mobile/LIUSHANMEN/product-types/options"
```

---

## 执行顺序(⚠️ 不能调换)

**先 DNS 再证书** —— Let's Encrypt 的 HTTP-01 验证需要域名解析到本机才能签发。
顺序反了会一直签不下来。

1. **加 DNS A 记录**: `api.cretaceousfuture.com` → `139.196.165.140`
   (在域名服务商 / 阿里云控制台, 服务器上做不了)
2. **等解析生效**: `nslookup api.cretaceousfuture.com` 返回 139.196.165.140
3. **签发证书**(宝塔面板该站点 → SSL → Let's Encrypt, 或 acme.sh)
4. **验证**: 上面那两条 curl, 应为 **HTTP 401** 且**不再需要 `-k`**
5. **改 App 配置**: `.env.production` → `REACT_APP_API_URL=https://api.cretaceousfuture.com`
6. **发 App 新版**
7. **⚠️ 盯流量归零, 不要按时间猜**:
   ```bash
   ssh root@139.196.165.140 \
     'grep "$(date +%d/%b/%Y)" /www/wwwlogs/www.cretaceousfuture.com.log | grep -c "okhttp/4"'
   ```
   这个数字降到 0 才说明旧版本用户全部更新完。**在此之前 www 上的 `/api/` 代理一行都不能动。**
8. 之后 www 才自由: 主机规范化 / 缓存策略 / 安全头随便改

---

## 值不值得做

**不急做的理由**: 目前零故障; 双主机的 SEO 问题已由各页 `<link rel=canonical>` 解决
(这是标准且充分的做法); 迁移要等用户更新完, 中间任何一步出错都是工厂用户用不了。

**值得做的理由**: 不是 SEO, 是**营销站和生产 API 不该共用一个 vhost**。分开之后,
改官网再也不会有打断 App 的可能。

**顺带**: 那张 2026-04-07 过期的证书现在还挂在 vhost 上。虽然没 DNS 打不到,
但如果有人先加了 DNS 却没续证书, API 会直接因证书错误全挂 —— 这就是上面顺序不能反的原因。

---

关联: `.claude/skills/server-operations`(部署与服务器规范)、
`platform/`(官网, 与该 vhost 同一份 nginx 配置)
