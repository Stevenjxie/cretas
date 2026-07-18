---
name: deploy-backend
description: >
  Use when the user asks to deploy, publish, release, restart, roll back, or
  verify Cretas Java, Python, or web-admin services in test or production.
---

# Cretas Deployment

Deploy only an exact, reviewed commit. Read `.codex/rules/server-operations.md`, `.codex/rules/worktree-and-main-only-deploy.md`, and `.codex/rules/concurrent-edit-safety.md` before changing deployment scripts or production state.

## Scope And Environment

| Component | Production truth | Test |
|---|---|---|
| Java | `47.100.235.168`, alternating `10010` / `10020` | `10011` |
| Python | `47.100.235.168:8083` | `8084` |
| Web admin | `139.196.165.140:8086`, `admin.cretaceousfuture.com` | n/a |

Java runtime is `/www/wwwroot/cretas/`; web admin is `/www/wwwroot/web-admin/`. Production Java traffic is routed by `139.196.165.140:/www/server/panel/vhost/nginx/_upstream_cretas.conf`.

## Preferred Unified Release Entry

For normal Java/Web releases, call the unified orchestrator instead of composing
artifact and deployment commands by hand. The Base SHA must be the value
registered in `docs/dispatch/ACTIVE.md`:

```bash
# Optional pre-merge candidate artifact build
./scripts/deploy/release-cretas.sh \
  --phase build \
  --base-sha '<registered Base SHA>' \
  --tests '<MavenTestSelector>'

# Post-merge production release from clean exact origin/main
./scripts/deploy/release-cretas.sh \
  --phase deploy \
  --base-sha '<registered Base SHA>' \
  --tests '<MavenTestSelector>' \
  --confirm-prod YES-PROD
```

It detects `backend/java/cretas-api` and `web-admin` changes, selects the
component builds/deployments or verified no-op, validates both trusted
manifests before deployment, and writes one structured release receipt. It is
safe-sequential by default and never infers API compatibility from Git diff.
Parallel deployment is allowed only with
`--parallel-if-independent YES-INDEPENDENT-SERVICES` and only when its
migration, Entity, Repository/query, Security/Auth, Controller/DTO/API, config
and explicit-order risk gates all pass. Use `--order backend-first|web-first`
for explicit serial ordering.

Keep `release-jar-manifest.sh`, `release-web-manifest.sh`, `deploy-backend.sh`,
`deploy-web-admin.sh`, and `deploy-cretas-parallel.sh` as single-component or
troubleshooting entries. They retain all blue-green, atomic-swap, health,
rollback, no-op, stale-chunk, and hash gates; do not duplicate those mechanics
in a normal Agent release flow.

## Build Once

Every release/deployment starts from a clean exact `origin/main` worktree:
require an empty `git status --porcelain` and `HEAD == origin/main` before
artifact reuse, fallback build, or deployment. A trusted candidate JAR may be
built earlier in the clean reviewed source worktree and reused after squash
merge when the backend tree remains identical. Build once for one backend Git
tree:

- In the clean reviewed source worktree, run `./scripts/deploy/release-jar-manifest.sh build --tests '<tests>'`. This executes one `mvn clean package -Dtest=<tests>` lifecycle, creates the final JAR, and writes its manifest. Do not run the target tests separately and then package again.
- After that trusted build succeeds, `./scripts/deploy/stage-backend-artifact.sh --confirm-stage YES-STAGE` may upload the verified JAR to the immutable server-side SHA-256 cache before merge. Staging never installs the JAR, restarts a service, or changes upstream. The exact `origin/main` deployment still revalidates the manifest/tree and claims the cached bytes only after SHA-256, MD5 and JAR integrity checks.
- A successful release build must generate a trusted manifest recording at least the build commit, exact `backend/java/cretas-api` Git tree, JAR SHA-256, and the information needed to check JAR integrity. A recent mtime or filename is not provenance.
- `SKIP_BUILD=1`, local cache reuse, or Artifact reuse is allowed only after validating all of the following: the manifest build commit resolves in Git; that commit's `backend/java/cretas-api` tree equals both the manifest tree and the current `origin/main` backend tree; SHA-256 matches; the JAR passes an integrity check; and the current exact `origin/main` worktree is clean. A squash merge may change the commit while preserving the backend tree; matching backend trees are reusable in that case.
- Keep using the manifest-backed backend-tree cache, including cache/no-op behavior when Java did not change. If reuse is unavailable or any validation fails, fall back exactly once to the existing local clean-package path; do not retry with a second package invocation. Write a fallback manifest only after that build and all existing JAR checks succeed.
- If the verified cached JAR MD5 already matches production, the script may return a no-op only after reading the real nginx upstream and verifying the selected systemd unit plus direct active-slot health. `FORCE_REDEPLOY=1` bypasses this optimization.
- GitHub Artifact is a manual fallback only when it already exists and passes the same trusted-manifest checks. Never trigger or wait for an Artifact during a release.

Before a release from the exact merged `origin/main`, run the single fast gate
instead of repeating shell, YAML, encoding, Flyway, and diff checks manually:

```bash
./scripts/deploy/release-preflight.sh
```

It must stay read-only and must not run Maven or contact production. During
feature development, use `--allow-non-main --allow-dirty --skip-fetch` only as
a diagnostic; the real release gate remains strict on clean exact `origin/main`.
After it passes, let `deploy-backend.sh` check the manifest-backed local
backend-tree cache. On a cache/Artifact miss or validation failure, run one
local clean-package fallback without waiting for GitHub and generate the
trusted manifest only after that build succeeds.

## Reuse A Merged Feature Worktree

Avoid creating a second release worktree when the reviewed feature worktree is clean and its PR is already merged:

1. Require an empty `git status --porcelain`, then fetch `origin`.
2. Record `feature-head=$(git rev-parse HEAD)`, then use merged PR metadata to obtain its head and merge commits. Require `feature-head == <pr-head>`, `git merge-base --is-ancestor <merge-commit> origin/main`, and `git diff --quiet "$feature-head" <merge-commit>` to pass. Do not infer a squash merge from feature-branch ancestry.
3. Run `git switch --detach origin/main`, then require both an empty status and `HEAD == origin/main` before any production build or deploy.

If any condition fails, create a fresh clean release worktree from `origin/main`. Never discard uncommitted work to enter this fast path.

## Controlled Direct-Main Fast Lane

PR remains the default. Use the no-PR path only when the user explicitly asks
for direct publication and one coordinator has completed review and scoped
verification in a clean `codex/*` worktree based on the ACTIVE Base SHA.

Archive every ACTIVE task and release every scope lock in the final commit,
then run:

```bash
./scripts/deploy/publish-main-fastlane.sh \
  --base-sha <registered-origin-main-sha> \
  --confirm YES-DIRECT-MAIN
```

The helper fetches immediately before publication, rejects a stale base,
dirty worktree, non-linear history, unfinished ACTIVE task, and any force-push
path. High-risk scopes remain PR-only unless the user explicitly authorized
this exact high-risk direct publication and all required deep gates passed; in
that case add `--allow-high-risk YES-HIGH-RISK-REVIEWED`. Any rejection falls
back to one PR. Publishing to `main` never authorizes production deployment.

## Java Blue-Green Deploy

1. Require a clean release worktree whose `HEAD` equals `origin/main`.
2. Read the upstream file; never assume `10010` or `10020` is permanently active.
3. Run the project script:

```bash
./scripts/deploy/deploy-backend.sh --env test
./scripts/deploy/deploy-backend.sh --env prod
```

The deploy prints its normal timing summary and atomically writes a JSON receipt under `~/.cache/cretas/deploy-reports/` (override with `CRETAS_DEPLOY_REPORT_PATH`). The trusted build writes `release-jar.report.json` beside its manifest with Maven wall time, tests, commit/tree, JAR SHA-256 and size.

4. The script must deploy the inactive slot, verify it before switching, atomically update upstream, pass all post-switch health rounds, then stop the old slot.
5. Report commit, release version, artifact MD5, old/new slot, health rounds, and rollback artifact.

Useful modes:

```bash
./scripts/deploy/deploy-backend.sh --env all
./scripts/deploy/deploy-backend.sh --rollback
```

Do not use `--git` unless the user explicitly requests the legacy server-build path.

## Web Admin Deploy

Build the reviewed Web release once and record its trusted dist manifest:

```bash
./scripts/deploy/release-web-manifest.sh build
```

After merge/direct publication, deploy only from clean exact `origin/main`.
The deploy script reuses one immutable `dist.tar.gz` when the build commit
resolves, the build and current `web-admin` Git trees match (including squash
merges), and the package-lock, index, archive SHA-256, tar integrity, and
referenced chunks all pass. The archive SHA covers every release byte; do not
spawn one hash process per dist file. Any miss or validation failure performs
exactly one normal local build and refreshes the manifest; it never trusts
mtime, filename, or a present `dist/`. If the remote archive and index
fingerprints already match and HTTP is healthy, return a verified no-op before
upload; otherwise retain the atomic swap and stale-chunk behavior.

Use the atomic project script, not manual `rsync --delete`:

```bash
./scripts/deploy/deploy-web-admin.sh --env prod --confirm-prod YES-PROD
```

Interactive runs may omit `--confirm-prod` and type `YES-PROD` at the prompt.
Automation and other non-interactive callers must pass the flag or set
`CRETAS_WEB_PROD_CONFIRM=YES-PROD`; never pipe a synthetic prompt response.

After deployment, compare local `dist/index.html`, server file, localhost response, and public response hashes. All must reference the same release.

## Concurrent Java + Web Release

For one clean reviewed candidate worktree, the two independent artifact builds
may overlap:

```bash
./scripts/deploy/release-cretas-artifacts.sh --tests '<MavenTestSelector>'
```

It runs the non-Maven Java selector/import preflight first, then launches the
single Java `clean package` lifecycle and the single Web build concurrently.
The preflight catches missing target test classes and unresolved project
imports; it does not replace Maven compilation or Mockito runtime validation.

Production deployment may overlap only after merge, only from clean exact
`origin/main`, and only when the caller explicitly confirms that frontend and
backend are compatible in either activation order:

```bash
./scripts/deploy/deploy-cretas-parallel.sh \
  --confirm-prod YES-PROD \
  --confirm-independent-services YES-INDEPENDENT-SERVICES
```

Do not use it for migrations, incompatible API changes, auth/security changes,
or any release with an ordering dependency. The wrapper validates both trusted
manifests before it starts either child, but each child remains responsible for
its own atomic switch, health gates and rollback behavior.

## Python And Restart

```bash
./scripts/deploy/deploy-smartbi-python.sh --env test
./scripts/deploy/deploy-smartbi-python.sh --env prod
ssh root@47.100.235.168 "cd /www/wwwroot/cretas && bash restart.sh prod"
```

The Python deploy script verifies a requirements hash, interpreter fingerprint,
installed-package hash, and `pip check` before reusing the remote virtualenv.
Do not force a repeated `pip install` when that manifest-backed cache is valid.

Never overwrite server `.env` files.

## Mandatory Verification

- Re-read the upstream file and confirm its active port.
- Confirm the new systemd unit is active and only the active production port listens.
- Verify direct active-slot health and the nginx/public route appropriate to the service.
- Verify Web HTTP 200 and content hashes when Web changed.
- If production assertions fail, roll back first; do not debug for an extended period on the active bad release.

Stop after service-level verification unless the user explicitly asks Codex to
run full online business acceptance or F006 UI E2E. By default the user/QA owns
that suite. When explicitly requested, use `e2e-web-admin`, keep business
mutation count at zero, and do not count slot smoke checks as the full E2E.

Production and test may drift. Mention the untouched environment's observed status without expanding a scoped production deploy into an unrequested test deployment.
