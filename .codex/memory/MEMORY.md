# Codex Project Memory

**最后更新**: 2026-06-26

Codex 侧长期记忆入口。项目入口规则见 `AGENTS.md`; 详细规范见 `.codex/rules/`。

## 凭证位置 (真值已就位, gitignored 不进 public 仓库)

- **阿里云 AK/SK + 服务器 SSH/宝塔 + 安全组** → `.codex/rules/aliyun-credentials.md`
- **数据库 / 服务密码** → `.codex/rules/db-credentials.md` (线上其他真值 SSH 读 `/www/wwwroot/cretas/.env.prod`)
- **测试账号 + E2E 凭证** → `.codex/rules/test-credentials.md`
- 这三个文件被 `.gitignore` 的 `.codex/rules/*-credentials.md` 忽略。**禁止把真值复制进任何 tracked 文件** (两个 GitHub 仓库都 public)。

## 关键运维事实 (最容易踩的)

1. **后端端口对公网关闭** (2026-04-11 Phase 3): `10010/10011/8083/8084` 仅放行 nginx 网关 `139/32`。开发机直连 `47:10010` 超时**是预期, 不是故障**。访问走 SSH 隧道 `ssh -L 10010:localhost:10010 root@47.100.235.168` 或临时白名单。
2. **服务是 systemd 管理, 不是 restart.sh**: `systemctl restart cretas-backend` (prod Java 10010) / `cretas-python` (8083) / `cretas-embedding` (9090)。
3. **内容分布**: Java/Python/DB 在 **47**; web-admin/showcase/nginx 在 **139**。Showcase 只部署 139, 别传 47。
4. **test 环境目前停用** (省内存给 blue-green), 实写 E2E 只能打 prod。
5. **web-admin prod = 139:8086, test = 139:8097** (deploy-web-admin 默认部 test, 部 prod 要 `--env prod`)。

## 红线 (执行者不许独立收尾)

- prod 部署 / DB migration / 权限 RLS / 架构 → 只做到 "实现+自测+PR off origin/main", 不自部署不自 merge, 回 main 由 organizer 终审。
- 每任务独立 worktree off `origin/main`; prod 只从 main 部署 (见 worktree-and-main-only-deploy.md)。
- 真值不进 tracked 文件。

## 迁移记录

- 2026-06-26: 从 `.claude/rules` 生成 `.codex/rules` 兼容副本。
- 2026-06-26: `aliyun-credentials.md` / `db-credentials.md` / `test-credentials.md` 填入真值 (gitignored), 替换原脱敏占位。Codex 现可自主读取凭证, 无需每次手动喂。
