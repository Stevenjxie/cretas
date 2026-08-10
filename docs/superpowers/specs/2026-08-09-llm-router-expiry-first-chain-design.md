# LLM Router 链按到期日自动排序 + 注册表按实测重写

**日期**: 2026-08-09
**状态**: 设计待评审
**触发**: prod 实测发现 23 个 (账号,模型) 处于额度退避、chat/insights/mapper 三槽塌到只剩 `zhipu/glm-4.5-air` 单点兜底,而三个 aliyun 账号手上约 1800 万 token 的免费额度 router 一个都够不着。

---

## 1. 问题

### 1.1 现象

2026-08-09 19:02 prod 实测:

| 槽位 | 链长 | 额度耗尽 | 熔断 | 仍可用 |
|---|---|---|---|---|
| chat | 19 | 17 | 0 | 2 |
| insights | 17 | 16 | 0 | **1** |
| chart | 19 | 17 | 0 | 2 |
| mapper | 6 | 5 | 0 | **1** |
| reasoning | 17 | 13 | 1 | 3 |
| vl | 6 | 1 | 0 | 5 |
| review | 26 | 21 | 1 | 4 |

`All providers exhausted` 当天触发 7 次(review 6 / mapper 1)。

### 1.2 根因(三条,互相独立)

**根因 A — 链是手写字面量,顺序会腐烂。**
`_SAFE_MODELS` 的 docstring 写着 "ordering chains soonest-expiry-first WITHIN a quality tier",`_expiry_of()` 这个函数也早就存在,但**从来没有任何代码调用它排链**。链是人手写的常量,`SLOT_MODELS` 上方注释甚至明写 `Runtime order is authoritative (no re-sort)`。意图写在注释里,约束不存在 —— 于是每个到期日都要人来改一次,漏一次就腐烂一次。

**根因 B — 注册表登记与现实脱节,且没有任何机制能发现。**
实测证据:

- `qwen3.7-flash` / `qwen3.7-flash-2026-07-15` 登记在 `aliyun_b`(实测 403 无额度),而**真正有满额 100 万的 `aliyun_a` 一个都没登记** —— 账号写反了。
- `qwen3.8-max`(三账号各 100 万,2026-11-01 到期)、`qwen3.5-ocr`(三账号各约 100 万,09-14)、`deepseek-v4-flash-0731`(a/b 各约 100 万,10-31)—— **注册表里完全没有这些条目**。
- 反向:`aliyun_a`/`aliyun_b` 在链里一共只有 5 个条目,实测 **5/5 全部 403**。这两个账号对 router 的净贡献为 0,纯粹在每次请求上空转一轮 403。

**根因 C — 地板(非 DashScope 兜底)自己已经塌了。**

- `tencent`:9 个条目 7 个返回 `401008` FREE_QUOTA_EXHAUSTED;`kimi-k2.6` 走参数层后仍返回空内容。只剩 `minimax-m2.7` 可用(6.7s,偏慢)。
- `ark`:2 个条目全部 `SetLimitExceeded`,整个 provider 归零。
- `zhipu`:`glm-4.5-air` 可用(1.0s,全链最后的兜底);**`glm-4.6v` 已死** —— 返回 `429 余额不足或无可用资源包`。由于报文不含 `SetLimitExceeded`,`_is_quota_exhausted` 不认它是额度问题 → 落进 60 秒短熔断 → **每分钟重试一次,无限空转**。

---

## 2. 判据(本次采用的决策规则)

### 判据 1 — 注册表只收「控制台显示有余量」∩「探针拿到非空内容」的交集

单边证据一律不收:

- 控制台有余量、探针 403 → 不收(如 `aliyun_c/deepseek-v4-flash-0731`,控制台显示剩 479,737 但实测 403)
- 探针 200、控制台未列 → **更要不收**。这是计费风险最大的一类:如果控制台显示无免费额度而 API 仍返回 200,恰恰说明「免费额度用完即停」没覆盖该模型,那些 200 可能是真在计费。探针只能看到"通了",看不到"谁付的钱"。

`glm-5.2` 即按此判据从三个账号全部剔除 —— 尽管它在 a/b 上实测返回非空内容 1.3s。

### 判据 2 — 探针必须复用 router 自己的参数层,判据是「非空内容」不是 HTTP 200

本轮在同一件事上错了三次,每次都是"我验的那条路不是生产走的路":

| # | 错误判据 | 造成的误判 |
|---|---|---|
| 1 | `max_tokens=1` | 4 个能用的模型判成 `400 invalid_parameter` |
| 2 | HTTP 200 | `glm-5.2` 返回空 `content`(121 token 全烧在 `reasoning_content`,`finish_reason=length`)被判成可用 |
| 3 | 手搓 `enable_thinking:False` | 3 个 `_THINKING_ONLY` 模型判成 400 死掉(router 根本不会给它们发这个参数);`zhipu`/`tencent` 判成空内容(它们要的是 `thinking:{type:disabled}`,由 `_apply_slot_params` 负责翻译) |

结论:探针**不能自己拼 payload**,必须走 `_apply_slot_params(slot, account, model, payload)`,并且**按槽分别探** —— 同一模型在关思考槽可用、在推理槽返回空,这种差异只有分槽探才看得见(`glm-5.2`、`MiniMax-M2.5` 都是这个形状)。

### 判据 3 — 会随时间变的东西写成规则,不写成顺序

"C 用完用 B,B 用完用 A" 是一条**随时间自动生效的规则**,不是一次排序。写成规则,4 天后自己生效;写成顺序,4 天后又是一次今天这样的排查。

---

## 3. 目标 / 非目标

**目标**

1. 链顺序由代码按免费额度到期日升序算出,不再手写 —— 到期日一到自动重排。
2. 注册表按判据 1 的交集重写,把约 1800 万 token 的可用额度接进链。
3. 地板按实测收缩,并修掉 `429 余额不足` 的误分类。
4. 加每日探针 job,持续对账注册表与现实,治根因 B。
5. `_REGISTRY_AUDIT_DATE` 推进到 2026-08-09(现值 07-26,+21 天 = **08-16 就会触发 staleness fail-safe**,届时 router 自动收缩到 13 个模型的最小集)。

**非目标**

- 不改 `call_chain` / `call_chain_stream` 的重试、超时、预算逻辑。
- 不改 `QUOTA_SKIP_TTL`(6h)与熔断阈值。
- 不新增 provider、不充值。ark 的注册表条目清空,但 **provider 配置与代码路径全部保留** —— 后续补齐 ark/tencent 的可用模型时,加回来是改数据不是改代码。
- 不引入"按剩余额度排序"。阿里云只有控制台网页显示余量,API 拿不到;靠人工抄截图等于把自动化又变回手工。

---

## 4. 架构

现在一条手写链里捆着三件变化频率差两个数量级的事,这就是它腐烂的原因。拆开:

| 层 | 内容 | 谁写 | 变化频率 |
|---|---|---|---|
| 事实层 | `_SAFE_MODELS`:`(账号,模型) → 免费额度到期日` | 人写,每日探针对账驱动 | 天 |
| 资格层 | `_SLOT_POOLS`:哪些模型的能力/质量/速度配得上这个槽 | 人写 | 月 |
| 顺序层 | 链里谁在前 | **代码算** | 自动 |

```
_SAFE_MODELS (事实)  ┐
                     ├─→ _build_chain(slot) ──→ SLOT_MODELS ──→ call_chain
_SLOT_POOLS  (资格)  ┘         │
                               └─ sorted(pool + tail, key=_expiry_of)   ← 稳定排序
```

两个设计要点:

**延迟门槛做成「成员资格」,不做成运行时数值比较。** 探针测的是空载延迟(`deepseek-r1` 8.6s),真实 REVIEW 负载是 13–25s —— 拿空载数字卡 12s 门槛会算错。所以慢模型直接不进交互槽的候选池。这与仓里现有做法一致(注释已写 thinking-only 模型只进 REASONING),只是从注释变成结构。

**稳定排序承担质量。** Python `sorted` 是稳定排序:同一到期日的模型保持候选池里人写的顺序,而那个顺序就是质量优先级;只有跨到期日才重排。地板(`tencent`/`zhipu` 到期日为 `None` → `_FAR_FUTURE`)天然沉底,tencent/ark 交错防单点的顺序也原样保留。

**要显式废掉一条现有契约**:`SLOT_MODELS` 上方的 `Runtime order is authoritative (no re-sort)` 必须改写并说明原因,否则下一个人会把 re-sort 当 bug 修回去。

---

## 5. 组件

### 5.1 `_SAFE_MODELS` 重写

全部条目均满足判据 1(控制台有余量 ∩ 探针经参数层返回非空内容)。延迟为 2026-08-09 实测(关思考档 / 推理档)。

**aliyun_a**(控制台 8 个有额度,全部通过探针)

| 模型 | 到期 | 关思考 | 推理 | 备注 |
|---|---|---|---|---|
| qwen3.8-max | 2026-11-01 | 1.1s | 2.1s | 新增 |
| deepseek-v4-flash-0731 | 2026-10-31 | 1.5s | 1.7s | 新增 |
| qwen3.7-flash | 2026-10-23 | 0.5s | 6.4s | 新增(原误登在 b) |
| qwen3.7-flash-2026-07-15 | 2026-10-23 | 0.6s | 6.9s | 新增(原误登在 b) |
| qwen3.5-ocr | 2026-09-14 | 0.4s | 0.4s | 新增 |
| kimi-k2.7-code | 2026-09-14 | 2.1s | 2.2s | 已有,`_THINKING_ONLY` |
| qwen3.7-max-2026-05-17 | 2026-08-24 | 3.2s | 4.7s | 已有,`_THINKING_ONLY` |
| qwen3.7-max-preview | 2026-08-24 | 6.0s | 6.4s | 已有,`_THINKING_ONLY` |

**aliyun_b**(控制台 6 个有额度,全部通过探针)

| 模型 | 到期 | 关思考 | 备注 |
|---|---|---|---|
| qwen3.8-max | 2026-11-01 | 1.1s | 新增 |
| deepseek-v4-flash-0731 | 2026-10-31 | 1.3s | 新增 |
| qwen3.5-ocr | 2026-09-14 | 0.5s | 新增 |
| kimi-k2.7-code | 2026-09-14 | 1.8s | 新增 |
| qwen3.7-max-2026-05-17 | 2026-08-24 | 3.9s | 已有 |
| qwen3.7-max-preview | 2026-08-24 | 8.5s | 已有 |

**aliyun_c** — 长期(> 08-13)

| 模型 | 到期 | 关思考 |
|---|---|---|
| qwen3.8-max | 2026-11-01 | 1.0s |
| kimi-k2.7-code | 2026-09-14 | 1.8s |
| qwen3.5-ocr | 2026-09-14 | 0.5s |
| qwen3.7-max-2026-05-17 | 2026-08-24 | 1.9s |
| qwen3.7-max-preview | 2026-08-24 | 7.2s |
| qwen3.7-max | 2026-08-20 | 1.2s |
| qwen3.7-max-2026-05-20 | 2026-08-20 | 1.1s |

**aliyun_c** — 2026-08-13 到期(4 天,优先榨干)

| 模型 | 关思考 | 推理 | 约束 |
|---|---|---|---|
| qwen3-next-80b-a3b-instruct | 0.8s | 0.7s | — |
| deepseek-v3.2-exp | 0.9s | 0.8s | — |
| glm-4.6 | 0.9s | 44.4s | 仅关思考槽 |
| deepseek-v3.2 | 1.1s | 1.4s | — |
| qwen3.6-plus-2026-04-02 | 1.1s | 17.8s | 仅关思考槽 |
| qwen3.5-plus-2026-02-15 | 1.2s | 21.1s | 仅关思考槽 |
| qwen3-max-2025-09-23 | 1.6s | 1.9s | — |
| qwen3-vl-flash-2026-01-22 | 0.7s | 0.7s | VL |
| qwen3-vl-32b-instruct | 1.0s | 1.4s | VL |
| kimi-k2-thinking | 5.2s | 4.7s | `_THINKING_ONLY` |
| deepseek-r1 | 8.6s | 8.7s | `_THINKING_ONLY`,慢 |
| qwen3-235b-a22b-thinking-2507 | 9.5s | 8.9s | `_THINKING_ONLY`,慢 |
| deepseek-r1-0528 | 12.1s | 9.2s | `_THINKING_ONLY`,慢 |
| MiniMax-M2.5 | **400** | 3.6s | **仅 reasoning** |

**地板**

| 条目 | 到期 | 实测 | 处置 |
|---|---|---|---|
| zhipu/glm-4.5-air | None | 1.0s | 保留,文本地板 |
| tencent/minimax-m2.7 | None | 6.7s | 保留,慢但唯一非阿里/非智谱活口 |
| tencent 其余 8 个 | — | 7×401008 + kimi-k2.6 空内容 | 剔除 |
| ark 全部 2 个 | — | SetLimitExceeded | 剔除条目,**保留 provider 配置** |
| zhipu/glm-4.6v | — | 429 余额不足 | 剔除 |

⚠️ `qwen3.5-ocr`(三账号,09-14,约 300 万 token)**登记进注册表但本次不进任何池** —— 它是 OCR 专用模型,能否承担通用 VL 未经验证(见 §9)。登记而不入池是有意的:登记让每日探针持续盯着它、到期日进入视野,入池则需要先有验证证据。本设计因此**不设**"注册表未过期项必须至少进一个链"这条闸。

**删除清单**(实测 403,当前在链里空转):`aliyun_a/b` 的 `qwen3.7-plus-2026-05-26`、`qwen3.7-max-2026-06-08`;`aliyun_b` 的 `qwen3.7-flash`、`qwen3.7-flash-2026-07-15`;`aliyun_c` 的 `qwen3.5-flash`、`qwen3.6-flash-2026-04-16`、`qwen3-coder-flash`、`qwen-plus-latest`、`deepseek-v3.1`、`deepseek-v3`、`kimi-k2.6`、`glm-5.1`、`glm-5.2`、`qwen3-max-preview`、`qwen3-vl-plus`、`qwen-vl-max`;三账号全部 `glm-5.2`。

### 5.2 `_SLOT_POOLS` — 新增,取代手写链

只声明**够资格**的候选,内部顺序 = 同到期日时的质量优先级。延迟门槛(基于关思考档实测):

| 槽 | 延迟门槛 | 说明 |
|---|---|---|
| CHAT / CHART / MAPPER | ≤ 2s | 高频交互,`_BUDGET_AWARE_FAST_SLOTS` |
| REVIEW / INSIGHTS | ≤ 4s | 12s 交互预算内允许 2–3 次 fallback |
| REASONING | 不限 | 允许慢 |
| VL | ≤ 2s | 视觉模型 |

⚠️ **这张表是「作者写池时的取舍依据」,不是代码里的运行时数值比较。** 理由见 §4:探针测的是空载延迟,与真实负载差 2–3 倍,拿它当运行时阈值会算错。代码里能被机器检查的只有 §8.2 闸 3(池 ∩ `_SLOW_MODELS` 为空),那是一份人写的名单,不是数值。

`_SLOW_MODELS`(人写的实测慢名单,独立于池定义,供闸断言用):`deepseek-r1`、`deepseek-r1-0528`、`qwen3-235b-a22b-thinking-2507`、`kimi-k2-thinking`、`qwen3.7-max-preview`、`tencent/minimax-m2.7`。

⚠️ `tencent/minimax-m2.7` 同时出现在 `_SLOW_MODELS` 和 `_TEXT_TAIL` 里,这是**有意的、不矛盾**:闸 3 只约束 `_SLOT_POOLS`,而地板由 `_build_chain` 单独追加。地板的职责是"前面全挂了还能答",此时慢于不答;`_budgeted_attempt_timeout` 会按剩余预算给它裁时间。

`_THINKING_OFF_ONLY`(开思考会返回空或极慢,仅进关思考槽):`glm-4.6`、`qwen3.6-plus-2026-04-02`、`qwen3.5-plus-2026-02-15`。
`_REASONING_ONLY`(关思考会 400):`MiniMax-M2.5`。

### 5.3 `_build_chain(slot)` — 新增,约 10 行

```python
def _build_chain(slot: SLOT) -> List[Tuple[str, str]]:
    """按免费额度到期日升序拼链 —— use-it-or-lose-it。

    稳定排序: 同到期日保持 _SLOT_POOLS 里人写的顺序(= 质量优先级),
    只有跨到期日才重排。到期日为 None 的地板 → _FAR_FUTURE → 必然沉底。
    """
    return _dedup_chain(sorted(_SLOT_POOLS[slot] + _TEXT_TAIL,
                               key=lambda p: _expiry_of(*p)))

SLOT_MODELS = {s: _build_chain(s) for s in SLOT}
```

### 5.4 `_is_quota_exhausted` — 补一个分支

```python
    if status_code == 429 and ("余额不足" in body_text or '"code":"1113"' in body_text):
        # Zhipu 余额耗尽用 429 + 中文报文, 不含 SetLimitExceeded。
        # 归 6h quota-skip; 否则落进 60s 短熔断 → 每分钟空转重试一次。
        return True
```

### 5.5 每日探针 job — 新增

- 位置:`backend/python/` 下(⚠️ 不能放仓根 `scripts/`,那里不在部署范围 —— 2026-08-01 飞轮日报曾因此静默坏 5 天)。
- 行为:遍历 `_SAFE_MODELS` 全部条目 × 该条目所在的每个槽,用 `_apply_slot_params` 构造 payload,判据为**非空 content**。
- 输出三节对账表:

```
⚠ 注册表说活、实测不可用 (n):   <账号/模型>  <原因: 403 / 空内容 / 400>
⚠ 实测可用但未登记 (n):        <账号/模型>   ← 需先核对控制台余量再登记(判据 1)
⚠ 7 天内到期 (n):              <账号/模型>  <到期日>
```

- 频率:每日一次。约 90 次调用 × `max_tokens=200`,成本可忽略。
- 告警:三节任一非空即发告警。

---

## 6. 数据流

**启动时**:`_SAFE_MODELS` + `_SLOT_POOLS` → `_build_chain` → `SLOT_MODELS` → `call_chain` 逐条尝试;`_refuse_reason` 拦过期/不在白名单,`_quota_should_skip` / `_cb_should_skip` 拦运行时死亡。

**每日**:探针 job → 对账表 → 人改 `_SAFE_MODELS` / `_SLOT_POOLS` → 链自动重排。

**到期日自然演进**:08-13 C 号大批到期 → `_refuse_reason` 当天开始硬拒 → 链自动落到 08-20 那批 → 依次 08-24 → 09-14 → 10-23 → 10-31 → 11-01 → 地板。**全程无需人工干预。**

---

## 7. 错误处理

| 情形 | 处理 |
|---|---|
| 条目已过期 | `_refuse_reason` 返回 `expired`,跳过 |
| 运行时 403/402/429-quota | 标记 6h quota-skip,fallback 下一条 |
| zhipu 429 余额不足 | **本次新增**:归入 quota-skip 而非 60s 熔断 |
| 返回非空但内容为空 | 现有 `_validate_output` 已覆盖,继续 fallback |
| 某槽链被全部拦掉 | 记 `All providers exhausted for <slot>`;测试用地板断言保证这不会因构建错误发生 |
| 注册表过期(> 21 天) | 收缩到 `_MINIMAL_SAFE_SET`。⚠️ 该集合也需按本次结果同步更新,否则 fail-safe 会退到一批已死的模型 |

---

## 8. 测试设计

### 8.1 明确不写的闸(恒真式陷阱)

**不写** `assert chain == sorted(chain, key=_expiry_of)`。链就是这么造出来的,左右同源,这个断言一次都红不了 —— 与 2026-08-09 那个 `countActions(...).sum() == tools.size()` 是同一形状。

### 8.2 承重闸

| # | 闸 | 独立来源 | 变异验证 |
|---|---|---|---|
| 1 | **golden 快照**:7 个槽算出的链连同到期日打印进 `tests/golden/llm_router_chains.txt`,人审冻结 | 人审冻结表 vs 代码算出的链 | 把 `qwen3.8-max`(11-01)挪到 08-13 那批之前 → diff 必红 |
| 2 | **地板非空**:每个**文本槽**的链末尾至少有一个 `_expiry_of == _FAR_FUTURE` 的条目。**VL 槽显式豁免**并在闸里写明理由(见 §9),豁免名单是硬编码的单元素集合 —— 想再豁免一个槽必须改代码并留下 diff | 结构不变量 | 删掉 `zhipu/glm-4.5-air` → 必红;把 VL 加进文本槽集合 → 必红 |
| 3 | **交互槽无慢模型**:`_SLOT_POOLS[fast] ∩ _SLOW_MODELS == ∅` | `_SLOW_MODELS` 是独立人写的实测名单 | 把 `deepseek-r1` 塞进 CHAT 池 → 必红 |
| 4 | **池 ⊆ 注册表**:每个池条目 ∈ `_SAFE_MODELS`(现有 CI 闸,沿用) | — | 加一个不在注册表的条目 → 必红 |
| 5 | **参数约束**:`_REASONING_ONLY` 条目不出现在关思考槽;`_THINKING_OFF_ONLY` 条目不出现在 REASONING | 人写名单 | 把 `MiniMax-M2.5` 塞进 REVIEW 池 → 必红 |
| 6 | **审计日期未过期**:`not _registry_stale(today)` | — | 把 `_REGISTRY_AUDIT_DATE` 改回 07-26 → 必红 |

**每个变异脚本必须 `assert 锚点命中次数 == 1`** —— 否则"变异没生效"会被读成"闸瞎了"。

### 8.3 上线验证

1. 部署后跑 85 条回归电池,**基线 80/85**,低于基线不放行。
2. 部署后 10 分钟查 prod 日志:`All providers exhausted` 计数为 0。
3. 查实际命中的模型属于 **08-13 那批** —— 这是"物尽其用"真生效的正向证据,而不是"没报错"。

---

## 9. 已知缺口

### 9.1 VL 槽 2026-08-13 后变空链 —— 已拍板:接受,不投入

**owner 决定(2026-08-09):业务用不到 VL,不为它做额外工作。**

支撑证据(prod 日志 08-03 → 08-09,共 7 天):`slot=vl` 总计命中 **4 条日志 = 1 次真实请求**(08-07 14:57,两次 403 fallback 后由 `qwen3-vl-plus` 答成),`/vision` 接口访问 **1 次**。代码里有 6 处消费点(`label_qc/analyzer` 与 `hybrid_analyzer`、`efficiency_recognition` 的 `scene_understanding_service` 与 `tracking_service`、`food_kb/manual_chat` 的图片 OCR、`llm/api/endpoints` 的 `POST /vision`),但这些路今天基本不被执行。

**处置**:

- VL 槽**保留**,不删消费点、不删 slot。
- 08-13 后 `qwen3-vl-32b-instruct` / `qwen3-vl-flash-2026-01-22` 双双过期,`_refuse_reason` 硬拒 → VL 链变空 → `call_chain` 抛 `All providers exhausted for vl`。**这是期望行为**,符合 CLAUDE.md 核心原则 1「禁止降级处理 —— 不返回假数据,明确显示错误」:那 1 次/周 的请求应当明确失败,而不是被静默降级成文本模型的瞎猜。
- §8.2 闸 2 对 VL 显式豁免(否则 CI 会因 VL 空链常红,而常红的闸最终会被人关掉)。
- `qwen3.5-ocr`(三账号,09-14,约 300 万 token)**继续留在注册表但不入池**,作为将来 VL 复活时的现成候选;每日探针会持续盯着它。

**若将来 VL 重新启用**,三个方向按成本排序:实测验证 `qwen3.5-ocr` 能否承担通用 VL(零成本)/ 给 zhipu 充值恢复 `glm-4.6v` / 接新 provider。

### 9.2 ark 与 tencent 的可用模型待补齐

本次按实测把这两家收缩到 2 个条目(`tencent/minimax-m2.7`、剔空 ark)。owner 将稍后提供两家的完整可用清单,届时按判据 1 加回 —— **改数据即可**,provider 配置与代码路径本次全部保留,这正是本次不删 ark provider 配置的原因。

---

## 10. 部署前置

⛔ **线上 `llm_router.py` 比 `origin/main` 多 13 行**(2026-08-09 18:50 部署,未推 origin)。内容是把 `deepseek-r1` / `kimi-k2.6` 提到 REVIEW 链头的注释与改序。本设计会重写这一段,**动手前必须先确认这 13 行的归属**,否则会覆盖另一个 session 正在调的东西。
