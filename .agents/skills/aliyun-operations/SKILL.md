---
name: aliyun-operations
description: Cretas Alibaba Cloud operations runbook. Use when managing or diagnosing Cretas production servers on Aliyun, including SSH access, service health checks, systemd restarts, Java/Python logs, Nginx gateway routing, security group whitelist changes, and the 47/139 server topology.
---

# Cretas Aliyun Operations

Use this skill for Cretas production operations on Alibaba Cloud. Treat production commands as live operations: prefer read-only checks first, state the target host, and avoid changing security group or restarting services unless the user asks or the diagnosis clearly requires it.

## Topology

| Server | IP | Role |
| --- | --- | --- |
| Primary | `47.100.235.168` | Java `10010`, Python `8083`, PostgreSQL `5432`, Redis `6379` |
| Gateway | `139.196.165.140` | Nginx reverse proxy `80/443`, web-admin, showcase |

SSH:

```bash
ssh root@47.100.235.168
ssh root@139.196.165.140
```

## Critical Network Rule

Since 2026-04-11 Phase 3, public access to backend ports `10010`, `10011`, `8083`, and `8084` is closed. These ports are only allowed from the Nginx gateway security source `139.196.165.140/32`.

Implications:

- A developer machine connecting directly to `47.100.235.168:10010` should time out. This is expected and is not evidence of backend failure.
- `139.196.165.140:10010` should not work because the 139 server does not run the Java backend.
- Run health checks on the 47 server itself, or use an SSH tunnel.

Developer access through tunnel:

```bash
ssh -L 10010:localhost:10010 root@47.100.235.168
# then browse or curl http://localhost:10010
```

## Production Services

Production service management is systemd-based. Do not assume legacy `restart.sh` behavior unless using the explicit prod command below.

```bash
systemctl status cretas-backend cretas-python cretas-embedding
systemctl restart cretas-backend
systemctl restart cretas-python
journalctl -u cretas-backend -f

# Restart all prod services in dependency order.
bash /www/wwwroot/cretas/restart.sh prod
```

Common remote form:

```bash
ssh root@47.100.235.168 "systemctl status cretas-backend --no-pager"
ssh root@47.100.235.168 "systemctl restart cretas-backend"
```

## Health Checks

Run these from the 47 server because backend ports are not publicly open.

```bash
ssh root@47.100.235.168 "curl -s http://localhost:10010/api/mobile/health"
ssh root@47.100.235.168 "curl -s http://localhost:8083/health"
ssh root@47.100.235.168 "ss -tlnp | grep -E '10010|8083|5432|6379'"
ssh root@47.100.235.168 "df -h && free -h"
```

Gateway-side checks:

```bash
ssh root@139.196.165.140 "nginx -t && systemctl status nginx --no-pager"
ssh root@139.196.165.140 "ss -tlnp | grep -E ':80|:443'"
```

## Logs

Java prod:

```bash
ssh root@47.100.235.168 "journalctl -u cretas-backend -n 200 --no-pager"
ssh root@47.100.235.168 "tail -n 200 /www/wwwroot/cretas/cretas-prod.log"
```

Python prod:

```bash
ssh root@47.100.235.168 "journalctl -u cretas-python -n 200 --no-pager"
ssh root@47.100.235.168 "tail -n 200 /www/wwwroot/cretas/python-prod.log"
```

## Security Group Whitelist

Use Aliyun account A for the 47 ECS security group.

Configuration:

```bash
export ALIYUN_CRETAS_REGION="cn-shanghai"
export ALIYUN_CRETAS_SG="sg-uf64n0hcl8w37d34zfmy"
export ALIBABA_CLOUD_ACCESS_KEY_ID="$ALIYUN_CRETAS_AK"
export ALIBABA_CLOUD_ACCESS_KEY_SECRET="$ALIYUN_CRETAS_SK"
```

Do not write real AK/SK values into tracked repository files. Use environment variables, a local secret store, or the credentials already supplied by the current secure harness.

Temporarily allow the current developer IP to reach Java `10010` directly:

```bash
MY_IP="$(curl -s https://ifconfig.me)"
aliyun ecs AuthorizeSecurityGroup \
  --access-key-id "$ALIBABA_CLOUD_ACCESS_KEY_ID" \
  --access-key-secret "$ALIBABA_CLOUD_ACCESS_KEY_SECRET" \
  --region "$ALIYUN_CRETAS_REGION" \
  --SecurityGroupId "$ALIYUN_CRETAS_SG" \
  --IpProtocol tcp \
  --PortRange "10010/10010" \
  --SourceCidrIp "$MY_IP/32" \
  --Priority 1 \
  --Description "dev access"
```

Remove the temporary whitelist after use:

```bash
aliyun ecs RevokeSecurityGroup \
  --access-key-id "$ALIBABA_CLOUD_ACCESS_KEY_ID" \
  --access-key-secret "$ALIBABA_CLOUD_ACCESS_KEY_SECRET" \
  --region "$ALIYUN_CRETAS_REGION" \
  --SecurityGroupId "$ALIYUN_CRETAS_SG" \
  --IpProtocol tcp \
  --PortRange "10010/10010" \
  --SourceCidrIp "$MY_IP/32"
```

Inspect all security group rules:

```bash
aliyun ecs DescribeSecurityGroupAttribute \
  --access-key-id "$ALIBABA_CLOUD_ACCESS_KEY_ID" \
  --access-key-secret "$ALIBABA_CLOUD_ACCESS_KEY_SECRET" \
  --region "$ALIYUN_CRETAS_REGION" \
  --SecurityGroupId "$ALIYUN_CRETAS_SG"
```

## Diagnosis Rules

- If direct local access to `47.100.235.168:10010` times out, first remember the security group rule; verify with server-local `curl` before restarting anything.
- If gateway traffic fails, check 139 Nginx first, then connectivity from 139 to 47.
- If Java health fails on localhost, inspect `cretas-backend` systemd status and `journalctl` before restarting.
- If Python health fails on localhost, inspect `cretas-python` logs and port `8083`.
- Keep 10010/10011/8083/8084 closed to `0.0.0.0/0`; temporary developer whitelists must be `/32` and removed after use.
