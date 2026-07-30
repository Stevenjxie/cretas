# 交接：发布链路提速（CI 制品 + provenance + Web 构建复用）

**日期**：2026-07-30
**接手前必读**：本文所有数字都标了「实测 / 未实测」。**未标实测的一律当推测**，别在上面继续算。

---

## 一、一句话现状

**构建阶段从 144–163s 压到了 86s。** 再往下需要两件不需要决策的工程活（Java 预热、Web 取回），做完约 **~5s**。

> **2026-07-30 23:09 更新（Steve 拍板后执行）**：~~部署阶段一次都没测过~~ →
> **已跑完一次真实 prod 全量发布，`REMOTE_ARTIFACT_ONLY` 端到端跑通到蓝绿切流。**
> 总时长 **234s**（此前同类全量 400–463s）。详见 §四.1。
> 同一次发布顺带暴露一个真缺陷，已修并合入（**#2050**），见 §四.4。

---

## 二、今天合入 main 的 13 个 PR

| PR | 内容 |
|---|---|
| #2012 | push 到 backend 源码时 CI 自动构建制品；setup-java 换 zulu |
| #2013 | 验证过的 CI 制品落进服务器 jar 缓存 + 同步回服务器侧加固 |
| #2017 | 服务器脚本漂移检查器 + 取回 3 个从未纳入版本管理的东京脚本 |
| #2020 | **CI 制品签 Sigstore provenance + ECS 离线验签** → `deployable_trust_verified` 首次为 true |
| #2026 | 发布优先复用 provenance 已验证的 CI 制品（默认关闭） |
| #2027 | 服务器脚本安装器 + 清单/公共逻辑抽成单一来源 |
| #2028 | preflight 模块源码根按聚合 pom 推导（**已随 #2030 撤回**） |
| #2030 | **Revert #2011**（拆模块误入 main，723 文件） |
| #2031 | CI 制品选择器判据改为**集合包含** |
| #2032 | `both` 路径把取制品与 Web 构建**真并行** |
| #2035 | **Web 构建按 tree 复用**（86s → 2s） |
| #2041 | **CI 产出 web dist 制品 + provenance** |
| #2044 | 修 web dist 自检的 SIGPIPE |

---

## 三、实测数字（权威记录，全部本人实测）

### 构建阶段

| 组件 | 不复用 | 现在 | 全做完 |
|---|---|---|---|
| Java 本地 `mvn clean package` | **135–160s**（6 类选择器 194s；D: 盘 293s） | — | — |
| Java 取 CI 制品（**运输，非构建**） | — | **55s**（另测过 43/69s） | ~2-3s（预热后 claim 命中） |
| Web 全量 | **65–86s**（npm ci 14–20s + vite 51–66s） | 86s | ~5s（取回 3s + 复用 2s） |
| Web 按 tree 复用 | — | **2s** | 2s |
| **并行合计** | **144–163s**（实测两次：144 / 163） | **86s** | **~5s** |

### CI 侧（供参考，不在关键路径上）

| 作业 | 实测 |
|---|---|
| `ci.yml` 的 `java-build-test` | **231 / 254 / 256 / 258s** |
| `web-dist.yml` | **84s** |

🔴 **CI 构建 Java 比本机慢**（~4min vs 135–160s，GitHub runner 不如 32 核本机）。
**我们用 CI 不是因为它快，是因为它早** —— 在 push 时跑完，与 review 并行，成本落在发布窗口之外。

### 传输链路

| 段 | 实测 |
|---|---|
| 东京 → 上海 OSS | 20.9–23.8 MB/s |
| OSS → ECS 内网 | 228–426 MB/s |
| **GitHub → 本机（直连）** | **0.05 MB/s**（11.9MB 用 213s；8.7MB 用 169s） |
| ECS → GitHub **API**（小包） | 0.28s（其中 0.18s TLS 握手） |
| `--stage-backend` 预热 176MB | **6s**（rsync delta 8.90×，只真传 21MB） |
| Windows 承载制品字节 | **0** |

🔴 **吞吐数字不能跨通道套用**：交接文档里的「ECS→GitHub 0.001 MB/s」是**大文件通道**，API 通道完全够用。

### 部署阶段

**没有任何实测数据。** 今天所有实跑都是 `--phase build`。

---

## 四、卡在哪 —— 三处，性质不同

### 1. ✅ 部署那半已跑通（2026-07-30 23:09，Steve 授权后执行）

~~`deploy-backend.sh` 的 `REMOTE_ARTIFACT_ONLY` 分支一次都没在真实部署里执行过。~~
**已执行。** 命令：

```bash
LC_ALL=C JAVA_HOME="C:/Program Files/Zulu/zulu-21" \
./scripts/deploy/release-cretas.sh --phase all \
  --base-sha 9162b60701851509cae868766fbb09ea4c8314be \
  --tests 'FactoryStocktakeAndDeliveryRepositoryQueryValidationTest,InventoryOwnershipRepositoryQueryValidationTest' \
  --prefer-ci-artifact --confirm-prod YES-PROD
```

🔴 **`--base-sha` 的选法是这次验证能成立的关键**：上次部署的 head 是 `9d9fce2655`，而 Java 源码自
`05cf9f0b02` 起**未变**（`backend_tree` 恒为 `51b31baa65`）→ 用它当 base 会判 `java:false`、走 web-only，
**这条路径根本不会被执行**。改用 `9162b60701`（早于 #2033）才让 `java:true` 成立。

同时这也让本次验证**风险最低**：部署的 Java 源码树与 prod 正在跑的**完全相同**，
功能上是空转、路径上是实战；`V20261029_33` 那条 Flyway 早已应用，重放是 no-op。

**实测结果**：

| 项 | 值 |
|---|---|
| `RELEASE_BUILD_MODE` | `ci-artifact-parallel-web` |
| `RELEASE_JAVA_STATUS` | `build:success-ci-artifact,deploy:success` |
| `ci_artifact` | `used`，**71s**（与 Web 构建并行） |
| `java_build` / `build_count` | **0s / 0** —— 一次 Maven 生命周期都没跑 |
| 蓝绿切流 | 10020 → **10010(blue)**，`BACKEND_HEALTH=pass` |
| Web 四方哈希 | local == server == gateway == public_https ✅ |
| **总时长** | **234s**（此前同类全量 400–463s） |

**独立核验**（没有只信回执）：139 网关 `_upstream_cretas.conf` 实际写着
`server 47.100.235.168:10010;  # ACTIVE=10010 (switched 2026-07-30)`；47 上 10010 → HTTP 200、
10020 → 000（旧槽已停）；`cretas-backend` active。

⚠️ **仍未验的一项**：`--stage-backend YES-STAGE` 预热路径（本次是 `not-requested`）。
发布日志里那条 HINT 就是提示这个。

### 2. Web 取回没接（收益最大且不需决策）

CI 已产出 dist 并**验证消费端接受**（构建期 + 部署期两道都过，attestation 正 1 负 3 全对），
但**没有东西去取**。

🔴 **设计被一次实测改了**：直连 GitHub 到本机 **0.05 MB/s** → 8.6MB 要 161s，**比本地构建还慢**。
而 web 部署是**从本机 `scp` 到 139 网关**（`deploy-web-admin.sh:486`，不是 ECS），所以 dist **必须落到本机** ——
Java 那条 Tokyo→OSS→ECS 链路对 web **不适用**。

**要做**：一个**纳入版本管理**的 Tokyo→本机取回脚本（8.6MB 在既有 4 MiB/s 限速下约 2–3s）。
现有 `~/github-cache-tools/fetch-from-cache.sh` **未跟踪且已降级为人工回退**，发布脚本不该依赖它。

### 3. 路1 rolldown-vite 等 owner 三选一

实测 **1.9×**（rollup 51–53s → rolldown 26–28s 冷缓存）。`vitest` **不受影响**（真基线两边都是
4 failed / 2008 passed，同 4 个既有红测文件）。

🔴 **阻塞项：`el-icons` tree-shaking 退化 68%**（171 → 287 kB）。根因**不是配置**：包已声明
`sideEffects: false`，应用 334 个文件全具名导入 —— 是 rolldown 对该 barrel 的 tree-shaking 不如 rollup。

| 选项 | 后果 |
|---|---|
| 保留 el-icons 拆分 | 登录页多下 116 kB |
| 不拆 el-icons | dist 总字节反而更小（17,390,580 vs 17,647,842），但冒出 `es-*` **859 kB** 新 chunk，首屏组成变 |
| 先不换 | 保持现状 |

⛔ 无论哪种，**上线前必须跑 web E2E** —— 换打包器产物字节必然不同、chunk 组成也变了，构建成功 ≠ 应用还能用。
⚠️ **el-icons 拆不拆对速度没影响**（28.37 vs 25.68，噪声内），纯粹是体积/首屏取舍。

### 4. ✅ 那次 prod 发布顺带暴露的真缺陷（已修，#2050 = `e37e136191`）

发布日志里出现：

```
grep: -P supports only unibyte and UTF-8 locales
⚠️  无法提取本地 dist 的 entry chunk, 跳过 post-deploy 内容验证
```

**两件套在一起的事**：

1. `release-cretas.sh` **必须**以 `LC_ALL=C` 运行（否则 Java preflight 的 `[A-Z]` 按 collation
   展开匹配小写、假报「import 无法解析」），而 GNU grep 在 C locale 下**直接拒绝 `-P`** →
   `deploy-web-admin.sh` 那句 `grep -oP` 提取 entry chunk **恒为空**。
   两个子脚本的 locale 要求互相矛盾，而冲突表现为「少做一项验证」不是报错。
2. **更根本**：`LOCAL_ENTRY_HASH` 全脚本只有「赋值 / 判空 / 告警」三行，**没有任何消费者** ——
   那句「跳过 post-deploy 内容验证」描述的是一项**从未实现过**的验证。
   locale 那个 bug 只是让告警每次都触发，才把它暴露出来。

**为什么这缺口有实际后果**：四方哈希比的是 `index.html` **本身**，四个观测点看到同一个坏
index.html 时**照样 pass**；站点根 200 也只证明 index.html 能被服务。若原子交换装进的
index.html 引用了一个取不到的 chunk，**两项全通过而所有用户白屏**。
而交换逻辑本身就在做「旧 assets 2932 → 新 752 + 2208 个保留期内旧 chunk 延续」。

**已修**：提取改 `grep -oE` + POSIX 字符类（两种 locale 一致）；新增
`verify_entry_chunk_reachable()` 并**真的调用**它；新增 `scripts/tests/test-deploy-web-admin-entry-chunk.sh`
（7 项断言 + 静态禁 `grep -P` + 断言调用点存在防退回死代码）。变异检验双向过：
退回 `-oP` 变红、**只删调用点保留函数体**也变红。

🔴 **这条属于本仓库那个系统性问题的又一例**：「文档/消息描述的机制 ≠ 实际运行的机制，
而且没有任何东西在检查这种脱节」。上一份交接（`HANDOFF-2026-07-29`）就把它列为最高回报方向。

---

## 五、下一步怎么做（按收益排序）

| # | 事项 | 收益 | 需决策 | 状态 |
|---|---|---|---|---|
| 1 | **Web 取回脚本** | 86s → ~5s | 否 | ⬜ 待做（现在的最高收益项） |
| 2 | **push 后预热 Java 制品** | 55s → ~2-3s | 否 | ⬜ 待做 |
| 3 | ~~真实 prod 部署验证~~ | ~~唯一没跑过的部分~~ | 是 | ✅ **已完成**，见 §四.1 |
| 4 | 路1 rolldown | 见下方修正 | 是 | 🛑 **Steve 已拍板押后** |
| 5 | 补 `#2012` 的 paths 漏洞 | 见下 | 否 | ⬜ 待做 |
| 6 | 3 个既有红测 + 1 个超时套件 | 让测试能当闸 | 否 | ⬜ 待做 |

🛑 **关于 #4（2026-07-30 Steve 拍板押后）**：原文说「只有 1+2 做完后才有意义」，更准确的说法是
**取回脚本一落地，rolldown 的价值就大幅缩水** —— 构建走缓存约 5s，rolldown 省的那 25s
只在**缓存未命中**时兑现。而它的代价是 `el-icons` +68% 和一轮必跑的 web E2E。
**先拿到真实未命中率再定**，别为不确定收益提前付确定代价。

**关于 #1**：55s 之所以存在，只因为**运输是在发布那一刻才由本机发起的**。制品在 push 后几分钟就躺在
GitHub 上了。`release-ci-artifact.sh` 已经能完成「取回 + 验签 + 落进服务器缓存」——
把它改成 **push 后就跑**（watcher / 定时任务 / 合入后手动跑一次），发布时只剩一次 claim 命中。

**关于 #5**：`#2012` 的 push `paths` 只覆盖 `src/main/**` + pom + lib，而 `backend_tree` 覆盖整个
`cretas-api`（含 `src/test/**`）→ **纯测试改动会改 tree 但不触发 CI 构建** → 那种 commit 上取不到制品，
回退本地构建。

---

## 六、别再试的方向（已实测否决）

| 方向 | 否决理由（实测） |
|---|---|
| `reportCompressedSize: false` | 71s vs 66s **无收益**（该阶段仅占 3s） |
| 钉 JDK 补丁版本求字节可复现 | `pom.xml` 没设 `project.build.outputTimestamp` → JAR 内嵌时间戳 → **同棵树两次构建 sha256 本来就不同**。实测同树三个值：本地 Azul `7f8015bb` / CI zulu `22b2fc75` / 更早 CI `c8dfc1b5` |
| 直连 GitHub 取制品到本机 | **0.05 MB/s** |
| 让 ECS 走东京 VPN | **ECS 压根够不到东京**（`10.66.66.1:22` 不可达，无 wg/tun，默认路由直出 eth0）。那条 VPN 钉在 **Windows 本机出口** |
| 把 `web-admin/**` 加进 `ci.yml` 的 push paths | `on.push.paths` 是 **workflow 级**的，会让 `java-build-test` 被纯前端改动触发白跑 4–5 分钟（测试里有断言拦这个） |

---

## 七、今天踩的坑（对下一个 chat 最有价值的部分）

### 🔴 最该记的：三次「测了坏配置就下结论」

1. 报过 rolldown **5.6×（9.51s）** —— 那是 `manualChunks` **分块塌掉**时测的（echarts 从 1251kB 碎成 1.5kB），塌了自然少做工作。分块修对后是 **1.9×**。
2. 报过「vitest 与基线一致」—— 第一次的"基线"只 checkout 了 `package.json` **没还原 `package-lock.json`**，`npm ci` 按 lock 装的仍是 rolldown，等于同一套跑两遍。判据=**`node -p` 打出实际装的包名再说**。
3. 报过「vite 摘不下来」—— 只试了 `reportCompressedSize` 一项就下结论。

**共同点：改动没生效，而结果看起来是正常的。** 判据是先证明「我改的东西真生效了」，再看数字。
同类：**变异测试必须先断言变异本身生效**（`before=2 after=1`）再看结果。

### 🔴 混淆两个变量导致两次「确定性复现」都归因错

把「是否带 `-StageToCache`」和「从 bash 还是 PowerShell 会话启动」混在一起测，两次都指向前者。
**补齐 2×2 才发现与它完全无关。** 判据：一次只动一个变量，且**四格都要填**。

### 🔴 MSYS 的 ssh.exe 会截断 14KB 命令行

bundle ~14KB，远端命令 14,111 字符：
- 经 `C:\Windows\System32\OpenSSH\ssh.exe` → 完整送达
- 经 `C:\Program Files\Git\usr\bin\ssh.exe` → **尾部截断**，排最后的 `--source-digest` 静默消失

**从 bash 起的 pwsh 继承 MSYS 的 PATH，命中的正是坏的那个** —— 也就是 `release-cretas.sh` 的真实路径。
判据：`pwsh -NoProfile -Command '(Get-Command ssh).Source'` 在两种上下文里对比。
修法**不是钉 ssh 路径**，是别把大 blob 放命令行（改走 `--payload-stdin`）。

### 🔴 `tar ... | grep -q` 在 `set -o pipefail` 下必挂

`grep -q` 一命中就关读端 → tar 被 SIGPIPE(141) → pipefail 判整步失败，报 `tar: stdout: write error`。
**`release-cretas.sh` 顶部 `matches_any_line` 那段长注释就是讲这个坑的**，我在同一个仓库里还是踩了。
⚠️ **本机(MSYS)复现不出来**（造到 174KB listing 仍通过，管道缓冲行为与 Linux 不同）→ 只能靠断言禁写法。

### 🔴 `grep -P` 与 `LC_ALL=C` 不共存（2026-07-30 新增，已加断言）

`release-cretas.sh` **必须** `LC_ALL=C`，而 GNU grep 在 C locale 下拒绝 `-P`。
**任何被统一入口调用到的脚本都不许用 `grep -P`** —— 失败表现是提取恒为空、只打一行 warning。
已加静态断言：`scripts/tests/test-deploy-web-admin-entry-chunk.sh`。

写正则时用 POSIX 字符类（`[[:alnum:]]`/`[[:upper:]]`）而不是区间（`[A-Za-z]`/`[A-Z]`）——
后者在 `en_US.UTF-8` 下按 collation 展开，这是同一个坑的另一面。

### 🔴 其它反复踩的

- **管道会吃掉退出码**：`cmd | tail` 之后的 `$?` 是 `tail` 的。本轮误判过多次。
- **python `open()` 拿不到 MSYS 的 `/tmp/...`**，要 `D:/...`。
- **python `print()` 在 MSYS 下输出 CRLF** → sha 带尾随 `\r` → 长度 41 → 校验不过 → 报「没有可用制品」，**与真的没有一模一样**。改用 `jq @tsv` 少一个解释器。
- **`[0-9a-f]{64}` 在 `en_US.UTF-8` 下匹配大写**（区间按 collation 展开）→ 用不含区间的枚举 `*[!0123456789abcdef]*`。
- **单反斜杠字面量别用 sed/python 写**（连栽三次，错误还报在 `bundleConfigFile` 层像是 vite 坏了）→ 用 Edit 工具直接写。
- **D: 盘比 C: 慢很多**：同一次本地 Java 构建 C: 135s / D: 293s。别拿 scratchpad 里的克隆量性能。

---

## 八、状态自证命令

```bash
# 服务器与仓库一致性（应 MATCH=7 DRIFTED=0 exit 0）
./scripts/deploy/check-server-script-drift.sh

# 服务器脚本安装（默认 dry-run；拒绝覆盖漂移）
./scripts/deploy/install-server-scripts.sh

# 相关测试套件
for t in test-release-ci-artifact test-release-web-manifest test-server-script-drift \
         test-deploy-backend-remote-cache test-deploy-backend-source-cache; do
  bash scripts/tests/$t.sh
done
```

**既有红测（有 origin/main 基线证据，不是新引入的）**：
`test-release-jar-manifest.sh`、`test-release-pipeline-acceleration.sh`、
`test-web-admin-deploy-acceleration.sh`（**零输出** exit 1）、
`test-release-cretas.sh`（两边**都** 480s 超时 exit 124）。

---

## 九、⛔ 千万别碰

**后端拆模块（#2011）本仓库已不做。** 工作阵地在新仓 `Stevenjxie/cretas-modular`，由另一个 chat 负责。
主仓 PR #2011 曾被人改 base + 取消 draft 合进 main，已由 #2030 撤回（723 文件）。
**不要在主仓再合它，也不要动分支 `codex/claude-mod-logistics-phase0`。**

---

## 十、相关 memory

- `project_2026_07_30_ci_artifact_provenance.md` — provenance 信任链
- `project_2026_07_30_ci_artifact_first_release.md` — CI 制品优先发布
- `project_2026_07_30_ci_web_dist_reuse.md` — Web 构建复用两半
- `reference_web_build_rolldown_spike.md` — Web 构建成分拆解 + rolldown spike
- `project_2026_07_30_modularization_new_repo.md` — 拆模块迁新仓
