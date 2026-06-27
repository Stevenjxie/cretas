# 测试账号 + E2E 凭证 (模板 — 占位)

> 这是 tracked 模板。复制为 `test-credentials.md`(被 `.gitignore` 忽略)并填真值。
> ⛔ 真实密码绝不写进本 `.example.md`。

## 账号

全项目测试账号默认密码统一(见 gitignored 真值文件)。

| 账号 | 角色 | 工厂 | 备注 |
|------|------|------|------|
| `f006_admin` | factory_super_admin | F006 | 六扇门**真客户**, 配置类操作必用 |
| `f006_dept_admin` | (受限只读) | F006 | — |
| `factory_admin1` | factory_super_admin | F001 | 标准测试工厂(非真客户) |
| `workshop_sup1` / `warehouse_mgr1` / `hr_admin1` / `dispatcher1` / `quality_insp1` | 各角色 | F001 | — |

## 访问地址

| 目标 | URL |
|------|-----|
| web-admin prod | `http://139.196.165.140:8086` |
| web-admin test | `http://139.196.165.140:8097` |
| Java API (开发机) | `http://localhost:10010` (需 SSH 隧道) |

## E2E 环境变量

```bash
E2E_USERNAME=${E2E_USERNAME:-factory_admin1}    # F006 链路用 f006_admin
E2E_PASSWORD=${E2E_PASSWORD:-<填真值>}
E2E_ADMIN_URL=${E2E_ADMIN_URL:-http://139.196.165.140:8086}
E2E_API_BASE=${E2E_API_BASE:-http://localhost:10010}   # 直连需 SSH 隧道
```
`E2E_ALLOW_MUTATION` / `E2E_RUN_LIVE` 不是项目内置变量, 是执行者自定的实写护栏, 要做真实写入需 Steve 明确授权。

## ⛔ E2E 红线

1. 真实写入需逐次授权; F006 是真客户租户, 跑完清理测试数据。
2. 绝不自部署 prod / 绝不自 merge —— 做到 "实现+自测+报告"。Playwright 必须 headed。
