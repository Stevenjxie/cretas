# 交接：发布链路收尾 + contract-repair 根因修复（2026-07-31）

**起点**：上一份交接 `handoff-2026-07-30-release-pipeline-acceleration.md`（#2047）
**终点**：`origin/main = 9850096b2c`，那份交接的 §五 六项**全部完成**

---

## ⚠️ 接手先读这条

**这份交接和上一份一样，写的是「我知道的状态」，不是「仓库的真实状态」。**
本 session 亲身踩过：上一份交接的三处结论（诊断/验收标准/剩余项）读起来自洽但是错的。

**先跑三条自检，再信任下面任何一句：**

```bash
git fetch origin && git log origin/main --oneline -5
gh pr list --state open                       # 应只有 #2072 daily-integration（机器人）
gh run list --branch main --limit 5            # CI 历史红没红
```

本文所有数字都标了「实测 / 推算」。**未标实测的一律当推测。**

---

## 一、现在的构建+部署流程（这是最该知道的一段）

### 标准动作

```bash
# ① 合并后先预热（丢后台，它会等到 CI 产出为止）
./scripts/deploy/prewarm-main-artifact.sh --tests '<选择器>' --wait 420

# ② 看到 PREWARM=done / already-warm 再发布（命令没变）
./scripts/deploy/release-cretas.sh --phase deploy \
  --base-sha '<Base SHA>' --tests '<同一选择器>' --confirm-prod YES-PROD
```

这两步已写进 `AGENTS.md` 与**两份** deploy skill（见 §四.3 为什么是两份）。

### 实测时长

| 场景 | 构建段 | 总计 | 来源 |
|---|---:|---:|---|
| 不预热（合并后立刻发） | 204s | **382s** | ✅ 实测 07-31 02:34 |
| **预热后**（冷本地缓存） | **25s** | ≈204s | ✅ 构建段实测；总计=实测构建+实测部署段 |
| web-only + CI 制品 | 18s | **74s** | ✅ 实测 07-31 20:11 |
| 完全无变更 | 0 | 48s | ✅ 实测 |

**瓶颈已经从构建移到部署。** 部署段 178s 的构成（实测）：
`idle_startup 45s`（Spring 启动，服务器端，无单一浪费）+ `post_switch_observation 22s`（已从 57→32→22）+ 上传 13s（预热后消失）+ 其余。

🛑 **再往下每一秒都要拿安全余量或架构改动去换**，不是工程浪费了。

### ⛔ 别再试的方向（都已实测否决）

| 方向 | 否决依据 |
|---|---|
| javac 110s 提速 | 单线程；ecj 死于 Lombok 不兼容 |
| `target/` 缓存 | 四轮实测**净收益为负** |
| jar 不压缩 | 净亏（IO 涨过 CPU 省） |
| 传输提速 / 并行分片 | 本机上行 ~12MB/s 是物理天花板；**并行分片实测零收益**（单流 6.1s / 4 路 6.4s / 8 路 6.1s） |
| 经东京中继 | 与直连是同一条上行 |
| rolldown-vite | Steve 押后；Web 现在 18s，收益已缩水 |
| **把并行部署改成默认** | ⛔ `AGENTS.md` 与代码都明写「**API compatibility is never inferred from Git diff**」「底层门禁不得复制或削弱」。风险检测器只能**否决**并行，永远不能**批准**。要那 22s 就按设计传 `--parallel-if-independent YES-INDEPENDENT-SERVICES` |

---

## 二、发布失败的**唯一**已知形态：构建被漂移作废

最近 20 次发布 18 成功 2 失败，两次**形状完全相同**：

```
构建成功 → deploy_mode: none, deploy_total: 0, production 全空 → exit 1
```

发布脚本在构建完成后再确认 HEAD 仍等于 `origin/main`，不等就中止（拒绝把基于旧 main 的产物部上去，**这是对的**）。

| 失败 | 构建 | 期间发生 |
|---|---|---|
| 07-30 19:45 | 166s ✅ | 19:42:51 别的 session 合了 PR |
| 07-31 15:30 | 227s ✅ | **15:28:35** 合了 PR（构建才跑 1.5 分钟） |

main 相邻合并间隔中位数 ~15 分钟，**相当比例 ≤4 分钟**（正好一次 fallback 构建）。
👉 **预热把构建压到 25s，这个窗口同比例缩小约 8 倍** —— 这是「先预热」的第二个理由。

---

## 三、本 session 合入的 12 个 PR

| PR | 内容 |
|---|---|
| #2050 | web entry chunk 验证在统一入口下恒失效，**且那项验证从来没被实现过** |
| #2054 | Web dist 取回接通（86s→22s） |
| #2055 | CI 制品预热 + 短路（61s→2.6s） |
| #2058 | ci.yml paths 漏洞 + 四个「既有红测」（**产品代码零改动**） |
| #2061 | `--prefer-ci-artifact` 改默认开 |
| #2065 | `--phase deploy` 两边回退改并行，与 `--phase all` 拉齐 |
| #2066 | 探测未命中时把状态写回，别让回执报成 `disabled` |
| #2099 | 「合并后先预热」写进 AGENTS.md + 两份 skill |
| #2101 | 审计补「毛利最低的菜品」用例 |
| #2102 | 「盘点亏了多少」补确定性覆盖 —— ⚠️**说明是错的，见 §四.1** |
| #2103 | **contract-repair 根因修复**（见下） |
| #2052 | 上一份交接文档更新 |

### #2103 是本轮最有价值的一个

`contract-repair` 的正当性来源写在它自己的注释里：

> its raw resolver label is not executable when it contradicts the metric/object slots in **the user's own wording**

但条件只写了 `bool(code) or bool(explicit_requested_metrics)` —— 只要 planner 给了 resolver 就成立。于是**纯粹由 LLM 编出来的指标槽也能否决 planner 自己的判断**。

prod 实拍：「盘点亏了多少」→ `contract-repair STOCK_SHORTAGE -> WASTAGE_TOP metrics=('wastage',)`，而该问句确定性编译出的指标是**空的**。

修法：新增 `_repair_backed_by_user_wording()`，覆盖已有 resolver 时要求驱动指标能从用户措辞编译得出。
**实测**：修复前 `WRONG_INTENT WASTAGE_TOP` → 修复后 `STOCK_SHORTAGE`，答错轴归零。

---

## 四、🔴 本 session 踩的坑（对下一个 chat 最有价值的部分）

### 1. 我诊断错了一整轮，而证据就在我自己引用的那行日志里

审计报 `WRONG_INTENT`，日志是：

```
contract-repair resolver RESTAURANT_OPS_STOCK_SHORTAGE -> RESTAURANT_OPS_WASTAGE_TOP
```

**planner 当时就已经选对了。** 我却去补路由关键词（#2102），补的是一个本来没坏的东西 —— 而那行日志从第一次就摆在那里。

**判据：读日志要读它到底说了什么，别读成你以为它说的。**
⚠️ #2102 的 commit/PR 说明因此是假的，已在 #2103 里更正，但**历史里那条留着**。

### 2. 「套件绿」经常什么都没证明

三次踩到同一件事：

- 改 `--phase deploy` 两边回退时以为「套件覆盖了 both 路径」→ 核对才发现**只有单边 web-fallback，`java+web-fallback` 一个用例都没有**
- 改 contract-repair 后 670 项全过 → **变异检验（guard 置恒真）照样 670 全过**，说明新行为零覆盖
- fixture 里 `release-ci-artifact.sh` **从没打过桩**，靠「命令不存在→非 0→回退」偶然工作

**判据：先补用例 → 证明它在改动前是红的 → 再改实现。** 变异检验是唯一硬手段。

### 3. 同一件事由多处承载，只改一处静默失效

- `.agents/skills/` 与 `.claude/skills/` 是**各自独立跟踪、且早已分叉**的两份（前者 Codex 读，后者 Claude 读）。我先只改了 `.claude` 那份 → Codex 永远看不到。已加跨三处断言。
- `#2050`：locale bug 只是把「那项验证从未实现」暴露出来。

### 4. 手写精确串做批量替换，**本 session 挂了四次**

sed/python 里手写多行精确串匹配失败四次（变异检验两次、拆函数一次、加规则一次），每次都是「断言拦住了所以没写坏文件」，但白跑一轮。
**判据：从文件本身取行号，或用 Edit 工具。** 相关：`grep` 把 `[...]` 当字符类（一次匹配了 275 行）、`grep` 把 `-` 开头的模式当选项、`$'\r'` 展开成空串导致误报 CRLF。

### 5. guard 只能更严，不能替换原条件

改 contract-repair 时我把原条件**替换**成新的，结果在某一格从 False 变 True，**反而放宽了闸**。第二版改成纯追加才对。

### 6. 离线脚本会制造假缺陷

审计报 `password authentication failed for user "cretas_user"` —— 去生产日志 grep 该报错**0 次**，是我跑审计时环境变量过滤漏了主库凭证。
**判据：任何离线探针报的错，先去生产日志 grep，0 次就是探针问题。**

---

## 五、剩下的 / 可以接着做的

| # | 事项 | 状态 |
|---|---|---|
| 1 | `#2072` daily-integration PR（机器人开的） | 唯一 open PR，没人看过 |
| 2 | 回执里 `ci_artifact: "disabled"` 在 **web-only** 发布下没有区分度 | 已知小瑕疵，#2066 只修了 `--phase deploy` both 分支 |
| 3 | `_REQUEST_METRIC_RULES` 里**没有任何盘点类指标** | #2103 让它不再出错，但「盘点亏了多少」现在是 `CLARIFY`（反问）。要它直接出答案得加指标 —— **属指标口径变更，等 Steve 拍** |
| 4 | 部署段 178s | 要动就得碰安全余量或 Spring 启动，**别自己决定** |
| 5 | 拆模块 | ⛔ **本仓库不做**，在新仓 `Stevenjxie/cretas-modular`，另一个 chat 负责 |

---

## 六、环境与自证命令

```bash
# 发布链测试（10 个套件；test-release-cretas 需 ≥600s 超时，它本身要 473s）
for t in test-release-ci-artifact test-release-ci-artifact-prewarm test-release-web-ci-artifact \
         test-release-jar-manifest test-release-pipeline-acceleration \
         test-web-admin-deploy-acceleration test-server-script-drift; do
  bash scripts/tests/$t.sh; done

# 服务器脚本一致性（应 MATCH=6 DRIFTED=0）
./scripts/deploy/check-server-script-drift.sh

# 餐饮 AI 能力审计（跑在真实数据上；每日 04:10 有 systemd timer）
# 服务器上跑，环境变量要【整份】取，别过滤
```

**环境陷阱**（都是实测踩出来的）：
- 本机跑 `release-cretas.sh` 必须 `LC_ALL=C` + `JAVA_HOME`；但 `deploy-web-admin.sh` **不要**加 `LC_ALL=C`
- 蓝绿槽位**会交替**（10010 / 10020），任何检查都别写死；当前活跃 **10010**
- `release-cretas.sh` 只管 Java + Web，**Python 要单独** `deploy-smartbi-python.sh --env prod`
- python `open()` 拿不到 MSYS 的 `/tmp/...`，要 `D:/...`
- 部署 prod 只从 clean exact `origin/main`；每任务开独立 worktree
