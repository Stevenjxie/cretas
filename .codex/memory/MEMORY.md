# Codex Project Memory

**最后更新**: 2026-08-04

Codex 侧长期记忆入口。项目入口规则见 `AGENTS.md`; 详细规范见 `.codex/rules/`。

## 现行发布链路 (2026-07-31 起, 三步不是两步)

```bash
# ① 合并前(干净候选 worktree): 唯一一次构建 + Java 制品预上传, 不安装/不重启/不切流
./scripts/deploy/release-cretas.sh --phase build --base-sha '<Base SHA>' --tests '<tests>' --stage-backend YES-STAGE
# ② 合并后先预热: 把 CI 制品的跨境运输挪出发布窗口。幂等, 可丢后台
./scripts/deploy/prewarm-main-artifact.sh --tests '<同 ③ 的选择器>' --wait 420
# ③ 看到 PREWARM=done / already-warm 再部署(强制 clean HEAD == origin/main)
./scripts/deploy/release-cretas.sh --phase deploy --base-sha '<Base SHA>' --tests '<tests>' --confirm-prod YES-PROD
```

- **`--prefer-ci-artifact` 已默认开** (PR#2061)。命中时 Java 侧零次 Maven, 构建段实测 204s → 25s。
  关掉要显式导出 `CRETAS_RELEASE_PREFER_CI_ARTIFACT=0` (只加 `--no-` 而环境里已有 `=1` 会变成
  Java 侧关、Web 侧照样开)。
- **② 不能省**: 合并后立刻发布时 CI 还没建完, 探测必然落空 → 回退本地构建 200s+,
  而 main 相邻合并间隔中位数 ~15min 且相当比例 ≤4min → 构建期间 main 前进,
  **构建全成功却被 exact-main 复检整体作废**(2026-07-30 / 07-31 各实测一次)。
- **部署成败唯一可靠判据**: `DEPLOY_EXIT=0` 且日志里 `RELEASE_FINAL_STATUS` 恰好出现 1 次。
  `RELEASE_FINAL_STATUS` 不出现本身就是失败信号; 后台任务通知里的 exit code 不可信。
- `deploy-backend.sh` / `deploy-web-admin.sh` / `deploy-smartbi-python.sh` 保留为**单组件与排查入口**
  (只动 Python、紧急单点、排查部署链路本身), 不要再拼接它们组成全栈发布。

## 凭证位置 (真值已就位, gitignored 不进 public 仓库)

- **阿里云 AK/SK + 服务器 SSH/宝塔 + 安全组** → `.codex/rules/aliyun-credentials.md`
- **数据库 / 服务密码** → `.codex/rules/db-credentials.md` (线上其他真值 SSH 读 `/www/wwwroot/cretas/.env.prod`)
- **测试账号 + E2E 凭证** → `.codex/rules/test-credentials.md`
- 这三个文件被 `.gitignore` 的 `.codex/rules/*-credentials.md` 忽略。**禁止把真值复制进任何 tracked 文件** (两个 GitHub 仓库都 public)。

## 关键运维事实 (最容易踩的)

1. **后端端口对公网关闭** (2026-04-11 Phase 3): `10010/10011/8083/8084` 仅放行 nginx 网关 `139/32`。开发机直连 `47:10010` 超时**是预期, 不是故障**。访问走 SSH 隧道 `ssh -L 10010:localhost:10010 root@47.100.235.168` 或临时白名单。
2. **服务是 systemd 管理, 不是 restart.sh**: `systemctl restart cretas-backend` (prod Java 10010) / `cretas-python` (8083) / `cretas-embedding` (9090)。
3. **内容分布**: Java/Python/DB 在 **47**; web-admin/showcase/nginx 在 **139**。Showcase 只部署 139, 别传 47。
4. **test 环境已于 2026-07-13 正式下线** (原为省内存给 blue-green 而停用)。实写 E2E 只能打 prod;
   任何「先部 test 验证再部 prod」的旧口径都已无法执行, 见 `server-operations` 规范。
5. **web-admin prod = 139:8086** (`--env prod` + `YES-PROD`)。`deploy-web-admin.sh` 无参数时默认部
   test(139:8097) 而 test 已下线 —— 常规发布走 `release-cretas.sh` 统一入口, 它自己判范围。
6. **prod Java 蓝绿槽 `10010` 与 `10020` 交替 active**。部署/核对前必须读
   `139.196.165.140:/www/server/panel/vhost/nginx/_upstream_cretas.conf`, 禁止假设某槽永久停用。

## 红线 (执行者不许独立收尾)

- prod 部署 / DB migration / 权限 RLS / 架构 → 只做到 "实现+自测+PR off origin/main", 不自部署不自 merge, 回 main 由 organizer 终审。
- 每任务独立 worktree off `origin/main`; prod 只从 main 部署 (见 worktree-and-main-only-deploy.md)。
- 真值不进 tracked 文件。

## 迁移记录

- 2026-06-26: 从 `.claude/rules` 生成 `.codex/rules` 兼容副本。
- 2026-06-26: `aliyun-credentials.md` / `db-credentials.md` / `test-credentials.md` 填入真值 (gitignored), 替换原脱敏占位。Codex 现可自主读取凭证, 无需每次手动喂。
