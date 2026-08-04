# 交接 — 手机端报工可见性修复上线 + OSS 账号停服（2026-08-04 下午）

**状态**: 后端已上线并实测通过；RN 半边**已合并但未送达设备**，卡在阿里云 OSS 账号停服。
**上一份**: `docs/dispatch/handoff-2026-08-04-clerk-contract-and-reporting-audit.md`

---

## 0. 一句话现状

线上 Java jar = **`bb03e69ead`**（含本轮修复，已核对运行字节码 + prod API 实测）。
🔴 **下轮 `--base-sha` 用 `bb03e69ead5095be11c7c6f57534023a6605bae1`**，不再是 `fb0ca03d0b`。
四个服务全 active。

🔴 **需要 Steve 处理的一件事**：阿里云 OSS 账号（AK `LTAI5t6h…`）**全账号数据面停服**
（`403 UserDisable`, EC `0003-00000801`），详见 §4。**RN 修复因此没送达任何设备。**

---

## 1. 本轮做了什么

上一份交接留的问题是「现场操作员到底有没有在用手机报工」。**Steve 拍板：要用 → 改代码兜底。**

PR **#2274**（已合并 `bb03e69ead`）：反锁死兜底扩到入口查询 + RN 客户端过滤同步放开。

### 根因（比上一份更精确）

上一份说「`assigned_to` 全空 → 操作员看到空列表」是对的，但**没说清那道闸在哪、为什么兜底没救到**：

| 层 | 结论 |
|---|---|
| 指派配置 | prod **从未被填过**：`product_work_processes` 仅 2 行且 `responsible_worker_id` **0 行有值**；`product_work_process_assignees` **0 行**；`production_plans` **0/10** 填 supervisor |
| 入口查询 `findByFilters` | `t.assignedTo = :assignedTo` **严格相等 → NULL 全排除 → 恒返回空** ← **唯一的闸** |
| 客户端 | RN 再筛一次 `task.assignedTo === assignedTo`（4 处） |
| 鉴权 `ReportAuthGuard` | ✅ **本来就 fail-open**（空允许集合直接放行）——**没有第二层栅栏** |

🔴 **兜底早就有，只是装在了它本该打开的那扇门的里侧**：`listByBatch` 的 M1 兜底注释原文
「防止未配默认责任人的老批次把任何人锁死」，过滤式是 `assignedTo == null || equals(assignedTo)` ——
但它在**已经进了某个批次之后**才执行，操作员卡在更早的入口列表上，永远走不到那里。

`start()` 在 `assignedTo == null` 时自动认领 → 「未指派可被任何人捡起」本就是既有设计，
本次只是让入口与该设计一致。⛔ **指派给他人的仍然过滤掉**（越权），两个方向各钉一条断言。

### 已验证（不是推测）

- 变异做实：Java 改回严格相等 → **恰好 1 条红**（`Expecting [9L] to contain [10L]`），另 3 条按预期保持绿；
  RN 兜底改成 `=== -1` → **恰好 3 条红**。变异前后均 `grep -c` 确认真的落地。
- 运行 jar 字节码含新谓词（计数 1）。
- **prod API 实测**：以 `assignedTo=1311`（名下零任务的 f006_worker1）查询 → **返回 15 条全未指派**
  （修复前是 0）；跨厂隔离仍成立（没漏 LIUSHANMEN 的 7 条）。

### 顺带

- 修了一处注释与实现相反：`YieldStepReportScreen` 注释写着后端「只返回分配给自己 **+ 未分配的**任务」，
  下一行又把未分配的筛掉了。
- 📌 任务数已从上一份的 18 → **22**（LIUSHANMEN 4 条是 08-04 16:24 新建的），
  `assigned_to` **仍全为 0** —— spawn 路径**持续**产出未指派任务，不是历史遗留。

---

## 2. ⚠️ CI 不覆盖 RN

PR #2274 改了 4 个 RN 文件，但 CI 的 `rn-test` job **SKIPPED**（只有 `java-build-test` +
`tracked-secret-scan` 跑了）。RN 改动在 CI 上**没有闸**，只能靠本地跑
（本轮本地全量 92 suites / 1232 tests 全绿）。下一个人别以为 PR 绿就等于 RN 被测过。

---

## 3. 发布记录（三步照抄，全部成功）

```
prewarm: PREWARM=done attempts=8   (CI 制品对准 bb03e69ead, ci_ran=35)
deploy : DEPLOY_EXIT=0  RELEASE_FINAL_STATUS=deployed  (170s, 蓝绿切 green/10020)
核对   : unzip 运行 jar → 新谓词计数 1
```

---

## 4. 🔴 阿里云 OSS 账号停服（本轮撞上，需 Steve 处理）

### 症状

`403 UserDisable` / EC `0003-00000801`，**该账号下 4 个桶全中**：
`cretas-download` / `cretas-deploy-temp` / `cretas-server-backup` / `pomelox-download`。
读、写、stat 全部被拒；**只有 `ls oss://`（管理面）还能用** —— 这是欠费停服的典型形态。

### 时间点：在我 17:01 推完 OTA 之后才发生

⚠️ **我一度误判成「既有故障 + push 脚本的校验闸坏了」，证据不支持，已撤回**：
- `push-bundle.sh` 第 18 行 `set -euo pipefail`，早于第 29 行 source lib
- lib 里 `ossutil cp` **和** 结尾的 `ossutil stat`（就是那道 CDN 校验闸）都在 `set -e` 之下
- 脚本打印了「CDN objects ready」并走完 promote + register，`PUSH_EXIT=0`
  → **上传与校验当时必然是成功的**，桶是之后才被停的

另一个把我带偏的假判据：我先拿「上一发的**目录** 403」当证据 —— **OSS 对目录本来就 403**。
换成上一发的**真实文件**才看清它是 **200**。

### 为什么上一发还活着

上一发（`1785775252343`，08-04 00:42）的资产**已在 CDN 边缘缓存**里 → 走 CDN 200；
直连 OSS 同样 403。我这一发从没被缓存过 → 回源即 403。
**「CDN 上能拉到」不等于「OSS 还活着」。**

### 我做了什么

把我那一发移出服务目录，让线上退回上一个已知 good：

```
/www/wwwroot/ota/updates/1.0.3/production/1785833966857
  → /www/wwwroot/ota/quarantine/1785833966857-undeliverable-oss-userdisable
```

（`find_latest_bundle` 取时间戳最大的目录；⚠️ 它对目录名 `sort(key=int)`，
**不能在原地改成非数字名**，只能移走。）

验证：manifest 现指向 `1785775252343`，其 launch 资产服务器侧 **200**。

### OSS 恢复后要做的

```bash
# 1. 确认数据面恢复
ossutil64 -c ~/.ossutilconfig-apk stat "oss://cretas-download/app-updates/updates/1.0.3/production/1785775252343/_expo/static/js/android/index-84d74af5fa8317a54b6aaa041b0d2ea3.hbc"

# 2. 把隔离的那发移回去 (对象当时已上传成功, 大概率直接可用)
ssh root@47.100.235.168 'mv /www/wwwroot/ota/quarantine/1785833966857-undeliverable-oss-userdisable \
    /www/wwwroot/ota/updates/1.0.3/production/1785833966857'

# 3. 核对 manifest 指向它, 且 launch 资产 200 (⛔ 必须测真实文件, 别测目录)
```
或者干脆重跑一次 `./scripts/ota/push-bundle.sh production android`（更干净）。

### 影响面（已查实，别扩大恐慌）

- ✅ **后端附件/图片不受影响** —— 后端走的是**另一个 OSS 账号**
  （buckets `cretas-audio` / `cretas-media`，不在本账号桶列表里；AK 也不同），
  今日后端日志 `UserDisable` / `OSSException` **0 命中**。
- ✅ **Java 发布不受影响** —— 本轮部署实测成功，走的是 CI 制品 + 服务器
  `/www/wwwroot/cretas/release-cache/`，没碰 OSS。
- ❌ 受影响：新 OTA bundle 下发、`dl.cretaceousfuture.com` 上**未被 CDN 缓存**的对象
  （含 APK 下载）、`cretas-server-backup` 备份。

---

## 5. ⏸ 刻意没做 / 仍未做

1. **RN 修复未送达设备** —— 代码在 main，bundle 也构建成功，只差 OSS 恢复后重推。
   ⚠️ `checkAutomatically: "NEVER"`，即使推成功也要 app 内显式触发检查才会更新。
2. **包装单位批次进不了可投量** —— 上一份 §6.1 原样保留，仍是独立一轮的量，全库仅 1 条且 EXPIRED。
3. **271 条悬空多态引用** —— 不补数据，理由见上一份 §5。
4. **没去补指派配置** —— Steve 选的是改代码兜底而非补配置。兜底上线后，
   即使将来有人填了责任人也不冲突（填了就按填的走，没填谁都能捡）。

---

## 6. 🔴 本轮的判据（都是我自己踩出来的）

1. 🔴 **`grep`/`ls` 的输出被截断时，"没看到" 不等于 "没有"。**
   我用 `git grep ... | head -25` 查 `responsibleWorkerId`，25 行全被 Java 占满，
   据此差点报「web-admin 没有指派入口」——**实际有完整的多人指派 UI**
   （`views/system/product-processes/index.vue`）。`grep -c` 一数就露馅。
2. 🔴 **测目录不等于测文件。** 拿「上一发目录 403」当「既有故障」的证据是错的，
   OSS 对目录本来就 403。换真实文件才看清是 200，结论整个反过来。
3. 🔴 **宣布"某某脚本的闸坏了"之前，先把它的 `set -e` 与调用链读完。**
   我差点把 OSS 停服误判成 `push-bundle.sh` 校验失效；读完第 18 行的 `set -euo pipefail`
   才确认上传当时必然成功。**基础设施故障不要往代码上赖。**
4. **"CDN 能拉到" ≠ "对象存储还活着"** —— 边缘缓存会把已死的源站掩盖几个小时。
   判死活要**直连 origin**。
5. **改了共享查询语义前先数消费者。** `findByFilters`（WorkProcessTask 那个）只有 1 个消费者，
   RN 另两个调用点都不传 `assignedTo` → 放开 NULL 对它们零影响。数完才敢改谓词而不是加开关。
6. **两个端点同名参数含义不同 = 缺陷形状。** `listByBatch` 的 `assignedTo` 是「我的+未指派」，
   `findByFilters` 是严格相等 —— 与其加第三种方言，不如拉齐成同一口径。

---

## 7. 环境（与上一份一致）

- prod: `root@47.100.235.168`，库 `cretas_prod_db`，`sudo -u postgres psql`
- Web: `https://admin.cretaceousfuture.com`（网关 `139.196.165.140`）
- F006 账号 `f006_admin` / `123456`
- OTA: `~/.ota-env` 提供 `OTA_ADMIN_TOKEN`；bundle 根 `/www/wwwroot/ota/updates/<rv>/<channel>/<ts>`
- ⚠️ 密集 ssh 会触发封禁。判据：**GitHub 通但两台 Cretas 都不通 = 我被封**。
  本轮实测这条判据好用——CDN 超时时一测就排除了封禁（GitHub/ota/admin 全 200，只有 `dl.` 不通）。
