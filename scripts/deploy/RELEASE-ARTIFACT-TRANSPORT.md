# 发布制品传输链路（方案 1）

**最后更新**: 2026-07-30

把 168MB 制品的**数据通路**移出 Windows。Windows 只发控制命令，制品字节一次都不经过它。

```
GitHub
  → 东京 Lightsail   下载 + 校验 (size + SHA-256)
  → 短时签名 PUT     ≤900s, 单对象, 单方法
  → 上海 OSS
  → 上海 ECS         内网 endpoint 下载 + 重算 SHA-256
```

---

## 组件

| 位置 | 文件 | 作用 |
|---|---|---|
| Windows | `Publish-GitHubArtifactViaLightsailOss.ps1` | 编排器。只发控制命令 |
| 东京 Lightsail | `/usr/local/sbin/github-cache-put` | release asset → 缓存（源码在 `lightsail/`） |
| 东京 Lightsail | `/usr/local/sbin/github-cache-clean` | 缓存 LRU 驱逐，20G 上限降到 18G（源码在 `lightsail/`） |
| 东京 Lightsail | `/usr/local/sbin/github-artifact-cache-rollback` | 缓存设施回滚（源码在 `lightsail/`） |
| 东京 Lightsail | `/usr/local/sbin/github-artifact-stage` | CI artifact(zip) → 解包 → 缓存（源码在 `lightsail/`） |
| 东京 Lightsail | `/usr/local/sbin/oss-put-artifact` | 缓存 → OSS（源码在 `lightsail/`） |
| 上海 ECS | `/usr/local/sbin/oss-sign-put.py` | 生成短时 PUT 签名（源码在 `ecs/`） |
| 上海 ECS | `/usr/local/sbin/oss-verify-artifact.sh` | 内网回拉 + 重算哈希（源码在 `ecs/`） |

后三个东京脚本在 2026-07-30 之前**从未纳入版本管理** —— 服务器上跑着，仓库里没有。
现已取回（按服务器原始字节，非重写）。`check-server-script-drift.sh` 就是防这件事复发的。

## 两种制品源

| 源 | 参数 | 形态 | key 用的哈希 |
|---|---|---|---|
| Release asset | `-AssetId` | 裸文件 | 事先已知，`-ExpectedSha256` 必填 |
| CI artifact | `-ArtifactId` | **zip 包装** | **解包后才知道** —— 是 zip 内 JAR 的哈希 |

CI artifact 走 `github-artifact-stage`：下载 zip → 校验 zip size → **按精确成员名**解包
（不信任 zip 内的路径，防 zip-slip）→ 重算 JAR 的 SHA-256 并与 zip 内自带的 `.sha256`
交叉核对 → 以 JAR 哈希入缓存 → 顺带取出 `release-jar.manifest`（若有）经 stdin 转交 ECS 校验器。

**一次下载完成**。仓库外的 `fetch-ci-artifact.sh` 是"先下一次探测哈希、再下一次存储"，
168MB 下两遍；这里不这么做。

服务器上这些脚本是 `root:root 0750`，用下面的安装器从本目录安装，改动前自动做 UTC 时间戳备份
（`<name>.bak.<UTC时间戳>`，ECS 上现有 5 个）。

### 安装（唯一可重复入口）

```bash
./scripts/deploy/install-server-scripts.sh                       # dry-run, 默认不写
./scripts/deploy/install-server-scripts.sh --host ecs --only oss-verify-artifact.sh \
    --confirm YES-INSTALL
```

清单与连接逻辑都是单一来源：`server-script-inventory.conf` + `scripts/lib/server-script-common.sh`
（漂移检查器读的是同两份 —— 各存一份就等于又造一个漂移源）。

⛔ **默认拒绝覆盖已漂移的文件。** 服务器上有而仓库没有的内容，默认假定是「仓库落后」而不是
「服务器脏」。要覆盖必须显式 `--accept-overwrite-drift`，而正确做法通常是先把服务器加固取回
仓库。这条闸就是 2026-07-30 事故的直接产物。

覆盖前自动备份为 `<name>.bak.<UTC时间戳>`（沿用此前手工的惯例），装完自动跑漂移检查自证落地
—— 它不返 0，安装脚本就不返 0。

### 漂移检查（改完服务器脚本必跑）

```bash
./scripts/deploy/check-server-script-drift.sh          # 全量
./scripts/deploy/check-server-script-drift.sh --diff   # DRIFTED 时看具体差异
```

对比服务器**实际安装**的版本与仓库版本，输出 `MATCH` / `DRIFTED` / `MISSING_IN_REPO` /
`MISSING_ON_SERVER` / `UNREADABLE`。退出码 `0`=一致，`1`=有不一致，`2`=**查不出来**
（ssh/sudo 失败等）—— 后两者刻意分开：「查不出来」既不等于一致也不等于漂移。

⚠️ **服务器更严格时以服务器为准**：`DRIFTED` 的修法通常是把服务器加固取回仓库，而不是
用仓库版本覆盖服务器。2026-07-30 就栽过一次 —— 有人在 ECS 上加固了
`oss-verify-artifact.sh` 的信任模型却从未提交，另一个 session 基于仓库版本改完装上去，
把 `deployable_trust_verified` 改回了 `true`，等于重新装回一个漏洞，而跑出来的那个
`true` 还被当成「链路打通」的证据。

它要两台跨境 ssh，**不挂在发布热路径上**，不省任何部署时间。它省的是"文档描述的机制
与实际运行的机制脱节，而且没有任何东西在检查这种脱节"。

## 用法

```powershell
scripts\deploy\Publish-GitHubArtifactViaLightsailOss.ps1 `
  -Repository <owner/name> -AssetId <id> `
  -ExpectedSize <bytes> -ExpectedSha256 <64位小写hex> `
  -TreeSha <git tree sha> -DestinationPrefix deploy/backend/ `
  -ManifestPath /path/to/release-manifest
```

对象 key 固定为 `<前缀>/<tree-sha>/<jar-sha256>.jar`，前缀白名单只有 `deploy/backend/` 和
`codex-network-test/`（后者是可丢弃的验收前缀）。

---

## ⚠️ 传输成功 ≠ 制品可信

这几件事被**刻意分开**，脚本不会替你合并：

```
transport_verified=true            字节完整送达并重算哈希通过
manifest_consistency_verified=…    manifest 内部自洽 (tree / jar_sha256 / 测试选择器)
attestation_verified=…             Sigstore 签名验过, 且 workflow + commit 都对得上
deployable_trust_verified=…        以上后两项同时为 true 才为 true
```

**为什么 manifest 自己不够**：`release-jar.manifest` 和 JAR 装在同一个 ZIP 里。任何能造出
这个 ZIP 的人都能自己写一份「测试通过了」。所以它只能证明内部自洽，证明不了出处，
`trust_reason` 会明说 `manifest_consistent_but_unauthenticated`。

**签名补上了出处**。CI 用 `actions/attest-build-provenance` 对 **JAR**（不是 ZIP —— ZIP 只是
运输包装，中途会被东京拆掉）签 SLSA provenance。ECS 侧验：

```bash
gh attestation verify <jar> \
  --bundle <随制品下发的 bundle> \
  --custom-trusted-root /etc/cretas/sigstore-trusted-root.jsonl \
  --repo Stevenjxie/cretas \
  --signer-workflow Stevenjxie/cretas/.github/workflows/ci.yml \
  --source-digest <要部署的那个 commit> \
  --deny-self-hosted-runners
```

于是**贵重的那句话**成立：`ci.yml` 在打包**之前**跑测试选择器，所以「存在一份 commit X 的
已签名制品」本身就意味着 X 上那组测试过了 —— 这个论断传输路径上**没有任何一方能伪造**。
vouching 权从一个搭便车的文本文件，转移到了「那个 commit 上的 workflow 定义」。

**信任基里有谁**：只有 GitHub Actions 与 ECS 上钉住的那个信任根。Windows 编排器、东京中继、
OSS **都不在**里面 —— 它们只搬运 bundle，改一个字节验签就挂。这是相对旧链路的实质变化：
旧链路里客户端的 `jar_sha256` **取自东京的 stdout**，等于东京说什么就是什么。

⚠️ **trusted root 绝不能随制品下发**。信任根必须走独立通路并钉在 ECS 上，否则就是把
「验证者」和「被验证者」装进同一个包，犯的正是上面 unsigned manifest 同一个错。装法：

```bash
# 未认证即可用; ECS 到 api.github.com 实测 0.28s
gh attestation trusted-root > /etc/cretas/sigstore-trusted-root.jsonl   # 34,634 bytes
```

`--stage-to-cache` 现在要求 `deployable_trust_verified=true`。它写入的正是
`claim_remote_sha256_artifact` 取 jar 的那个目录，落在那里的东西就是生产候选品；
「字节完整送到了」和「来自我们 CI、且是我们要部署的那个 commit」是两回事。

### 实测的信任判定矩阵（2026-07-30，真实制品，非推断）

制品 = run `30524013751` / commit `fd731af6f3642823f15c3b0e5e3f27114f83df59` /
jar `22b2fc750b29…`。bundle 11,194 bytes。

| # | 场景 | 结果 |
|---|---|---|
| 1 | 常规离线验签 | `exit 0` ✅ |
| 2 | **死代理强制断网**（`HTTPS_PROXY=http://127.0.0.1:1`）| `exit 0` ✅ 证明真离线 |
| 3 | 错的 `--source-digest` | `exit 1` — `expected SourceRepositoryDigest to be 0000…, got fd731af…` |
| 4 | 错的 `--signer-workflow`（指向 e2e-pr.yml）| `exit 1` |
| 5 | 错的 `--repo` | `exit 1` |
| 6 | 信任根文件不存在 | `exit 1` — 不回退网络默认根 |
| 7 | **篡改 jar 一个字节**（sha 22b2fc75→196a2bf7）| `exit 1` |
| 8a | **篡改 bundle 的 payload**（改 subject digest）| `exit 1` |
| 8b | **篡改签名** | `exit 1` |
| 9 | 空 bundle | `exit 1` |

签名绑定的 subject 是 `{"name":"cretas-backend-system-1.0.0.jar","digest":{"sha256":"22b2fc750b29…"}}`
—— 按名字 + 摘要绑定，不是按 ZIP。

⚠️ 一个失败的测试写法留在这里当反面教材：最初的「篡改 bundle」用字符串替换十六进制
`22b2fc750b29`，但 bundle 里的 digest 在 base64 的 DSSE payload 内，**替换根本没生效**，
于是验签通过、看起来像「篡改也能过」的重大缺陷。实际是测试无效。要改必须改
`.dsseEnvelope.payload` 解码后的 `subject[0].digest.sha256` 再重新编码。

### 字节可复现是做不到的（别再试着靠钉 JDK 统一）

`pom.xml` 没有设 `project.build.outputTimestamp`，JAR 内嵌构建时间戳，所以**同一棵树两次
构建的 sha256 本来就不一样**，与 JDK vendor / 补丁版本是否对齐无关。实测同一棵树
`7053da8bf39e`：本地 Azul 21.0.10 → `7f8015bb…`，CI(zulu 21.0.12) → `22b2fc75…`，
更早一次 CI → `c8dfc1b5…`。

这就是为什么快路径**按 commit 认制品**而不是按字节比对：provenance 回答「这些字节来自哪」，
它不需要两边产出相同的字节。

## 不可变性（已实测，非推断）

key 一旦存在就不可覆盖。这个保证由两条实测断言共同支撑，缺一不成立：

| 断言 | 实测结果 |
|---|---|
| 带 `x-oss-forbid-overwrite: true` 对已存在 key PUT | `409 FileAlreadyExists` — 覆盖被拒 |
| 同一签名 URL **去掉**该 header | `403 SignatureDoesNotMatch` — header 被签名绑定，去不掉 |

第二条是关键：如果 header 不在签名覆盖范围内，上传方只要不发它就能覆盖，
「不可覆盖」就只是个君子协定。签名把它钉死了，所以签名 URL 交给可信度较低的主机也安全。

若 key 已存在且 size 一致 → `artifact_status=hit`，跳过上传直接复用；size 不一致 → 直接失败，不覆盖。

---

## 实测数字（2026-07-30，CI artifact 模式，真实 Cretas JAR）

制品来自 run `30472785736`，zip 176,283,672 bytes / 内含 JAR 176,283,021 bytes。

| 段 | 结果 |
|---|---|
| GitHub → 东京（含解包） | `cache_status=stored`，`sha_source=archive_and_recomputed` |
| 东京 → OSS | 7.064s，**23.80 MB/s**，HTTP 200 |
| OSS → ECS（内网） | 0.608s，**276.5 MB/s** |
| **Windows 收字节增量** | **522,582 bytes = zip 的 0.30%** |
| 信任判定 | `transport_verified=true` / `deployable_trust_verified=false`（该制品由旧 ci.yml 产出，无 manifest）|
| 验收对象残留 | 0 |

## 实测数字（2026-07-30，release asset 模式，OBS 167,106,178 bytes）

| 段 | 结果 |
|---|---|
| GitHub → 东京 | `cache_status=hit`（本次命中，首次填充另测 51 MB/s） |
| 东京 → OSS | 7.059s，**22.58 MB/s**，HTTP 200 |
| OSS → ECS（内网） | 0.384–0.410s，**389–415 MB/s** |
| SHA-256 | 完全匹配 |
| **Windows 收字节增量** | **1,083,780 bytes**（含 gh API 的 TLS 与本机背景流量；制品 167MB 一字节未过） |
| 验收对象残留 | 0 |

## 人工回退

`C:\Users\Steve\github-cache-tools\fetch-from-cache.sh` 仍可用，但**已降级为人工回退**，
不再是默认发布路径。它会把整个对象拉到 Windows。

已加硬约束（实测生效）：

- 连接数 **上限 64**，请求 128 直接 `exit 2`（`error=connections_exceed_cap`）
- 聚合限速 **4 MiB/s**，由每连接 `--limit-rate` 分摊；实测 64 路拉 11.9MB 为 3.21 MB/s

历史上 128 路能跑 24.64 MB/s，但 Windows 侧无约束并发会打满整条上行链路、导致全网断流。
**速度不是唯一指标**，故不再采用。

## NGINX

东京的私有缓存站点仍在跑，`10.66.66.1:18081`，**只绑 WireGuard 内网**，公网不可达。
不要停 —— 它是人工回退的入口，空闲开销极低。

方案 1 连续完成 3 次真实发布后再单独评估退役；退役时只撤专用缓存站点 / UFW 规则 / 清理 timer，
先确认 NGINX 没有承载其他服务，不要为撤缓存而停整个 NGINX。
