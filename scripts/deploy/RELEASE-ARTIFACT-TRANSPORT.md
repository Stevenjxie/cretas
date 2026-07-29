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
| 东京 Lightsail | `/usr/local/sbin/github-cache-put` | release asset → 缓存（已存在，本次未改） |
| 东京 Lightsail | `/usr/local/sbin/github-artifact-stage` | CI artifact(zip) → 解包 → 缓存（源码在 `lightsail/`） |
| 东京 Lightsail | `/usr/local/sbin/oss-put-artifact` | 缓存 → OSS（源码在 `lightsail/`） |
| 上海 ECS | `/usr/local/sbin/oss-sign-put.py` | 生成短时 PUT 签名（源码在 `ecs/`） |
| 上海 ECS | `/usr/local/sbin/oss-verify-artifact.sh` | 内网回拉 + 重算哈希（源码在 `ecs/`） |

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

服务器上的三个脚本是 `root:root 0750`，从本目录安装，改动前自动做 UTC 时间戳备份。

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

这两件事被**刻意分开**，脚本不会替你合并：

```
transport_verified=true          字节完整送达并重算哈希通过
deployable_trust_verified=false  没有 manifest 佐证「哪棵树 + 哪组测试」
```

`deployable_trust_verified` 只有在传入 `--manifest` 且 manifest 的 `backend_tree` /
`jar_sha256` / `tests` 三项都对得上时才为 `true`。**不传 manifest 就永远是 false** ——
一次字节完美的传输并不能证明这个 JAR 来自被 review 的树、跑过目标测试集合。

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
