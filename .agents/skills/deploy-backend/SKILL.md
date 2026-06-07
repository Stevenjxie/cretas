---
name: deploy-backend
description: >
  Use when the user asks to deploy, publish, release, restart services, upload
  a Java backend JAR, deploy Python SmartBI services, deploy web-admin, roll
  back backend services, or verify production/test service health for this
  Cretas project.
---

# Cretas Deployment

Use this skill for deployment or restart work. Read `.claude/rules/server-operations.md`, `.claude/rules/worktree-and-main-only-deploy.md`, and `.claude/rules/concurrent-edit-safety.md` before changing deploy scripts or server operations.

## Decide Scope

Infer the target from the user request:

- `backend`, `Java`, `JAR`, default "deploy": Java backend.
- `python`, `SmartBI`, `AI service`: Python service.
- `frontend`, `web-admin`, `dist`: Vue web admin.
- `all`, `full deploy`: Java + Python + web admin.
- `restart`: restart only, no build.
- `rollback`: restore previous backend artifact.

If the scope is ambiguous and the action is production-impacting, ask one concise question before running commands.

## Environments

| Service | Production | Test | Notes |
|---|---:|---:|---|
| Java backend | `47.100.235.168:10010` | `47.100.235.168:10011` | Separate processes, same JAR artifact |
| Python service | `47.100.235.168:8083` | `47.100.235.168:8084` | SmartBI/AI services |
| Web admin | `47.100.235.168:8088` | n/a | Static dist behind nginx |

Main server: `root@47.100.235.168`.

Server paths:

- Java runtime: `/www/wwwroot/cretas/`
- Java source checkout: `/www/wwwroot/cretas/code/backend/java/cretas-api`
- Python service: `/www/wwwroot/cretas/code/backend/python/`
- Web admin: `/www/wwwroot/web-admin/`

## Preferred Java Deploy

Prefer test first unless the user explicitly requests hotfix/all/prod:

```bash
./scripts/deploy/deploy-backend.sh --env test
./scripts/deploy/deploy-backend.sh --env prod
```

Useful options:

```bash
./scripts/deploy/deploy-backend.sh --env all
./scripts/deploy/deploy-backend.sh --git
./scripts/deploy/deploy-backend.sh --rollback
```

Local Java build uses:

```bash
cd backend/java/cretas-api
mvn clean package -Dmaven.test.skip=true
```

Use `-Dmaven.test.skip=true`, not only `-DskipTests`, when test compilation is known to fail.

## Local Java Runtime Warning

When starting the backend locally, prefer `mvn spring-boot:run`, not `java -jar`. Running the fat jar can lock the artifact and break later packaging.

```bash
cd backend/java/cretas-api
mvn spring-boot:run -Dmaven.test.skip=true
```

## Python Deploy

Use the project script when present:

```bash
./scripts/deploy/deploy-smartbi-python.sh --env test
./scripts/deploy/deploy-smartbi-python.sh --env prod
./scripts/deploy/deploy-smartbi-python.sh --env all
```

After deploy, verify:

```bash
curl -s http://47.100.235.168:8083/health
curl -s http://47.100.235.168:8084/health
```

Do not overwrite server `.env` files.

## Web Admin Deploy

Build locally, then sync `dist/`:

```bash
cd web-admin
npm run build
rsync -az --delete dist/ root@47.100.235.168:/www/wwwroot/web-admin/
```

Verify:

```bash
curl -s -o /dev/null -w "%{http_code}" http://47.100.235.168:8088/
```

## Restart Only

Common restart/status commands:

```bash
ssh root@47.100.235.168 "cd /www/wwwroot/cretas && bash restart.sh all"
ssh root@47.100.235.168 "cd /www/wwwroot/cretas && bash restart.sh prod"
ssh root@47.100.235.168 "cd /www/wwwroot/cretas && bash restart.sh test"
ssh root@47.100.235.168 "ps aux | grep -E 'java|uvicorn' | grep -v grep"
```

## Mandatory Post-Deploy Checks

Report the checked component, environment, and status code/body summary:

```bash
curl -s http://47.100.235.168:10010/api/mobile/health
curl -s http://47.100.235.168:10011/api/mobile/health
curl -s http://47.100.235.168:8083/health
curl -s http://47.100.235.168:8084/health
curl -s -o /dev/null -w "%{http_code}" http://47.100.235.168:8088/
```

Production and test can drift. If deploying only one Java environment, still ping the other and mention its status.

## Failure Handling

- Java crash: inspect `/www/wwwroot/cretas/cretas-backend.log`.
- Python 500/crash: inspect `/www/wwwroot/cretas/code/backend/python/python-services.log`.
- Web blank screen: inspect browser console and nginx/API proxy paths.
- Startup timeout: retry health checks after 60 seconds before declaring failure.
- Rollback Java with `./scripts/deploy/deploy-backend.sh --rollback` when the deploy script supports it.
