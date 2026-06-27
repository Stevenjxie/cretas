# 阿里云账号凭证 (模板 — 占位)

> 这是 tracked 模板。复制为 `aliyun-credentials.md`(被 `.gitignore` 忽略)并填真值。
> ⛔ 真值绝不写进本 `.example.md`(它会进 public 仓库)。

## ⚠️ 后端端口对公网关闭 (2026-04-11 Phase 3)

`10010/10011/8083/8084` 仅放行 nginx 网关 `139.196.165.140/32`。开发机直连 `47.100.235.168:10010` 超时是预期, 走 SSH 隧道 `ssh -L 10010:localhost:10010 root@47.100.235.168`。

## 账号表

| 账号 | AK ID | AK Secret | 用途 |
|------|-------|-----------|------|
| A (47 ECS + SG) | `<填真值>` | `<填真值>` | 47 ECS + 安全组 `sg-uf64n0hcl8w37d34zfmy` |
| B (139 ECS + DashScope + OSS) | `<填真值>` | `<填真值>` | 139 ECS/SG + cretas-media OSS |
| C (域名 DNS) | `<填真值>` | `<填真值>` | cretaceousfuture.com AliDNS |

Region: `cn-shanghai`。

## 服务器

| | 47 (主, 后端) | 139 (网关) |
|---|---|---|
| SSH | `ssh root@47.100.235.168` | `ssh root@139.196.165.140` |
| 跑什么 | Java(10010)+Python(8083)+PG(5432)+Redis | Nginx + web-admin + showcase |

## 安全组临时白名单 (账号 A)

```bash
AK=<填真值>; SK=<填真值>; SG=sg-uf64n0hcl8w37d34zfmy; REGION=cn-shanghai
MY_IP="$(curl -s https://ifconfig.me)"
aliyun ecs AuthorizeSecurityGroup --access-key-id $AK --access-key-secret $SK --region $REGION \
  --SecurityGroupId $SG --IpProtocol tcp --PortRange "10010/10010" --SourceCidrIp "$MY_IP/32" --Priority 1 --Description "dev access"
# 用完撤销: 把 AuthorizeSecurityGroup 换成 RevokeSecurityGroup, 去掉 --Priority/--Description
```
