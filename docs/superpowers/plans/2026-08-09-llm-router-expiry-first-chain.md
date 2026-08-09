# LLM Router 到期日优先链 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 LLM Router 的链顺序从手写字面量改成「按免费额度到期日升序自动计算」,并按 2026-08-09 实测重写模型注册表,让约 1800 万 token 的可用免费额度接进链、让死条目停止空转。

**Architecture:** 拆三层 —— 事实层 `_SAFE_MODELS`(到期日)、资格层 `_SLOT_POOLS`(哪些模型配得上这个槽)、顺序层 `_build_chain()`(代码用稳定排序算出)。稳定排序保证同到期日保持人写的质量顺序,跨到期日才重排;到期日为 `None` 的地板天然沉底。

**Tech Stack:** Python 3.11 / FastAPI / pytest(`asyncio_mode=auto`)。所有命令的工作目录是 `backend/python`。

## Global Constraints

- 工作目录:worktree `C:\Users\Steve\cretas-llm-router`,分支 `codex/claude-llm-router-expiry-first`(off `origin/main`)。⛔ 不在主工作目录 `C:\Users\Steve\my-prototype-logistics` 干活。
- 测试命令一律 `cd backend/python && python -m pytest <path> -v`(pytest.ini 在 `backend/python/`)。
- 每个任务结束必须 commit,且用 `git commit -m "<msg>" -- <显式文件列表>` 锁定范围(并发 session 会污染 staging 区,见 `.claude/rules/concurrent-edit-safety.md` 规则 5b)。
- 注册表判据:只收「控制台显示有余量」∩「探针经 `_apply_slot_params` 拿到非空 content」的交集。单边证据一律不收。
- ⛔ 不改 `call_chain` / `call_chain_stream` 的重试、超时、预算逻辑;不改 `QUOTA_SKIP_TTL`(6h)与熔断阈值。
- ⛔ 不删 `ark` 的 provider 配置与代码路径(只清空它的注册表条目)。
- 本计划全部改动集中在 `backend/python/common/llm_router.py` + `backend/python/tests/` + 一个新探针脚本。

---

## 前置(必须在 Task 1 之前完成,非代码)

**线上 `llm_router.py` 比 `origin/main` 多 13 行,未推 origin。** 2026-08-09 18:50 部署,内容是把 `deepseek-r1` / `kimi-k2.6` 提到 REVIEW 链头的注释与改序,位置正好在本计划要重写的 `SLOT_MODELS` 段内。

- [ ] **确认这 13 行的归属**,二选一:
  - 若是本人/本 session 之外的并发工作 → 先问清楚再动手,否则 Task 4 会覆盖它。
  - 若已确认可覆盖 → 在 Task 4 的 commit message 里写明「本次重写取代 2026-08-09 18:50 未推的手工改序」。

复现命令:

```bash
ssh root@47.100.235.168 'wc -l /www/wwwroot/cretas/code/backend/python/common/llm_router.py'   # 1890
cd /c/Users/Steve/cretas-llm-router && git show origin/main:backend/python/common/llm_router.py | wc -l   # 1877
```

---

## File Structure

| 文件 | 职责 | 本计划动作 |
|---|---|---|
| `backend/python/common/llm_router.py` | 注册表 + 链 + 路由 | 修改:`_SAFE_MODELS`、`_MINIMAL_SAFE_SET`、`_REGISTRY_AUDIT_DATE`、`_is_quota_exhausted`;新增 `_SLOT_POOLS`、`_SLOW_MODELS`、`_THINKING_OFF_ONLY`、`_REASONING_ONLY`、`_build_chain`;`SLOT_MODELS` 改为计算生成 |
| `backend/python/tests/test_llm_router_registry.py` | 新增。注册表自身的闸(最小安全集自洽、冻结表、审计日期) | 创建 |
| `backend/python/tests/test_llm_router_chains.py` | 新增。链构建的闸(golden 快照、地板非空、慢模型、参数约束) | 创建 |
| `backend/python/tests/golden/llm_router_chains.txt` | 新增。人审冻结的链快照 | 创建 |
| `backend/python/tests/test_llm_router_quota_skip.py` | 现有。补 zhipu 429 分类用例 | 修改 |
| `backend/python/scripts/probe_llm_registry.py` | 新增。每日全量探针对账 | 创建 |

⚠️ 探针脚本必须放在 `backend/python/` 下。仓根 `scripts/` **不在部署同步范围**(2026-08-01 飞轮日报因此静默坏了 5 天)。

---

## Task 1: 修复失效的 stale fail-safe 地板

`_MINIMAL_SAFE_SET` 里的 `('tencent','hy-mt2-pro')` 不在 `_SAFE_MODELS` 中。`_refuse_reason` 的顺序是「stale 时只放行最小集 → 再查 `_SAFE_MODELS` 成员资格」,所以注册表过期时这个"非 DashScope 文本地板"会被判 `not_allowlisted`。fail-safe 的地板本身是死的 —— 而它的注释正是在修"fail-safe 退到死模型"。

**Files:**
- Create: `backend/python/tests/test_llm_router_registry.py`
- Modify: `backend/python/common/llm_router.py:273-288`(`_MINIMAL_SAFE_SET`)

**Interfaces:**
- Consumes: `llm_router._MINIMAL_SAFE_SET`、`llm_router._SAFE_MODELS`(均为现有模块级常量)
- Produces: 测试文件 `tests/test_llm_router_registry.py`,后续 Task 3 会往里加用例

- [ ] **Step 1: 写失败测试**

创建 `backend/python/tests/test_llm_router_registry.py`:

```python
"""
_SAFE_MODELS 注册表自身的闸。

与 test_llm_router_chains.py 的分工: 这里只管「注册表这张表对不对」,
链怎么拼、顺序对不对在 chains 那边。
"""
from common import llm_router


def test_minimal_safe_set_is_subset_of_safe_models():
    """stale fail-safe 只能退守到真实存在于白名单里的条目。

    _refuse_reason 的顺序是: registry_stale 时只放行 _MINIMAL_SAFE_SET
    → 紧接着查 (account, model) in _SAFE_MODELS。所以最小集里任何不在
    _SAFE_MODELS 的条目在 stale 分支下会被判 not_allowlisted ——
    fail-safe 会 fail 成「没有地板」, 恰恰是它想防的那件事。
    """
    orphans = sorted(p for p in llm_router._MINIMAL_SAFE_SET
                     if p not in llm_router._SAFE_MODELS)
    assert orphans == [], (
        f"_MINIMAL_SAFE_SET 有 {len(orphans)} 个条目不在 _SAFE_MODELS 里, "
        f"stale 时会被 _refuse_reason 判 not_allowlisted: {orphans}"
    )
```

- [ ] **Step 2: 运行测试确认它红**

```bash
cd backend/python && python -m pytest tests/test_llm_router_registry.py -v
```

Expected: FAIL,报 `_MINIMAL_SAFE_SET 有 1 个条目不在 _SAFE_MODELS 里 ... [('tencent', 'hy-mt2-pro')]`

- [ ] **Step 3: 修复**

在 `backend/python/common/llm_router.py` 的 `_MINIMAL_SAFE_SET` 里,把这一段:

```python
    ("tencent", "hy-mt2-pro"), ("zhipu", "glm-4.5-air"),
```

替换为:

```python
    # 2026-08-09: hy-mt2-pro 从未进过 _SAFE_MODELS —— 它在这里等于不存在
    # (_refuse_reason 的 stale 分支之后紧接着就查 _SAFE_MODELS 成员资格)。
    # 换成当天实测可用的 tencent/minimax-m2.7 (经 _apply_slot_params 返回非空
    # 内容, 6.7s)。闸: test_minimal_safe_set_is_subset_of_safe_models。
    ("tencent", "minimax-m2.7"), ("zhipu", "glm-4.5-air"),
```

- [ ] **Step 4: 运行测试确认它绿**

```bash
cd backend/python && python -m pytest tests/test_llm_router_registry.py -v
```

Expected: PASS

- [ ] **Step 5: 变异验证 —— 确认这道闸真的能红**

把 `("tencent", "minimax-m2.7")` 临时改成 `("tencent", "no-such-model-xyz")`,重跑 Step 4 的命令,必须 FAIL 且错误信息里点名 `('tencent', 'no-such-model-xyz')`。确认后改回。

- [ ] **Step 6: 提交**

```bash
cd /c/Users/Steve/cretas-llm-router
git add backend/python/tests/test_llm_router_registry.py backend/python/common/llm_router.py
git status --short
git commit -m "fix(llm-router): stale fail-safe 的非 DashScope 地板一直是失效的

_MINIMAL_SAFE_SET 里的 tencent/hy-mt2-pro 从未进过 _SAFE_MODELS。
_refuse_reason 在 stale 分支之后紧接着查 _SAFE_MODELS 成员资格, 所以注册表
过期时这个'地板'会被判 not_allowlisted —— fail-safe 会 fail 成没有地板,
正是它 2026-07-30 那条注释想防的事, 一行之后又犯了一次。

换成当天实测可用的 tencent/minimax-m2.7, 并补 subset 闸防止再犯。
" -- backend/python/tests/test_llm_router_registry.py backend/python/common/llm_router.py
git show --name-only --format="%h %s" HEAD
```

---

## Task 2: `_is_quota_exhausted` 认 zhipu 的「余额不足」

`zhipu/glm-4.6v` 返回 `429 {"error":{"code":"1113","message":"余额不足或无可用资源包,请充值。"}}`。报文不含 `SetLimitExceeded`,当前分类为瞬时故障 → 落进 60 秒短熔断 → 每分钟重试一次,无限空转。

**Files:**
- Modify: `backend/python/common/llm_router.py:1344`(`_is_quota_exhausted`)
- Modify: `backend/python/tests/test_llm_router_quota_skip.py`

**Interfaces:**
- Consumes: `llm_router._is_quota_exhausted(status_code: int, body_text: str) -> bool`
- Produces: 无新接口,仅行为变更

- [ ] **Step 1: 写失败测试**

在 `backend/python/tests/test_llm_router_quota_skip.py` 末尾追加:

```python
def test_zhipu_balance_exhausted_429_is_quota_not_transient():
    """智谱余额耗尽用 429 + 中文报文, 不含 SetLimitExceeded。

    2026-08-09 prod 实测 zhipu/glm-4.6v:
      429 {"error":{"code":"1113","message":"余额不足或无可用资源包，请充值。"}}
    分类成瞬时故障 → 60s 短熔断 → 每分钟重试一次, 无限空转。
    余额耗尽在结构上等同额度耗尽($0 且不会自愈), 应归 6h quota-skip。
    """
    body = '{"error":{"code":"1113","message":"余额不足或无可用资源包，请充值。"}}'
    assert llm_router._is_quota_exhausted(429, body) is True


def test_plain_429_rate_limit_is_still_transient():
    """阴性对照: 普通 429 突发限流必须仍然走短熔断, 不能被上一条误伤。

    没有这条, 上面那个断言可以靠「429 一律算额度耗尽」通过 ——
    那会把 QPS 限流罚 6 小时。
    """
    assert llm_router._is_quota_exhausted(429, '{"error":{"message":"Too many requests"}}') is False
```

- [ ] **Step 2: 运行测试确认第一条红、第二条绿**

```bash
cd backend/python && python -m pytest tests/test_llm_router_quota_skip.py -v -k "zhipu_balance or plain_429"
```

Expected: `test_zhipu_balance_exhausted_429_is_quota_not_transient` FAIL(`assert False is True`);`test_plain_429_rate_limit_is_still_transient` PASS

- [ ] **Step 3: 实现**

在 `backend/python/common/llm_router.py` 的 `_is_quota_exhausted` 里,把这一行:

```python
    if status_code == 429 and "setlimitexceeded" in lowered_body:
        return True
```

替换为:

```python
    if status_code == 429 and "setlimitexceeded" in lowered_body:
        return True
    # Zhipu: 余额耗尽用 429 + 中文报文 + code 1113, 不含 SetLimitExceeded。
    # 2026-08-09 实测 glm-4.6v。结构上等同额度耗尽($0 且不会自愈), 分到
    # 短熔断只会每 60s 空转重试一次。
    # 两个特征命中任意一个即可(OR, 不是 AND) —— 真实响应可能只带其中一个,
    # 要求同时命中会让它重新掉回 60s 空转循环, 正是本改动要消除的东西。
    # 普通 429 突发限流两个都不命中, 故不受影响, 见
    # test_plain_429_rate_limit_is_still_transient。
    if status_code == 429 and ("余额不足" in body_text or '"code":"1113"' in body_text):
        return True
```

- [ ] **Step 4: 运行测试确认两条都绿**

```bash
cd backend/python && python -m pytest tests/test_llm_router_quota_skip.py -v
```

Expected: 全部 PASS(含原有用例)

- [ ] **Step 5: 提交**

```bash
cd /c/Users/Steve/cretas-llm-router
git add backend/python/common/llm_router.py backend/python/tests/test_llm_router_quota_skip.py
git status --short
git commit -m "fix(llm-router): 智谱 429 余额不足归 6h quota-skip 而非 60s 熔断

prod 实测 zhipu/glm-4.6v 返回 429 code=1113 '余额不足或无可用资源包'。
报文不含 SetLimitExceeded, 被分类成瞬时故障 → 每 60 秒重试一次, 无限空转。
余额耗尽结构上等同额度耗尽(\$0 且不会自愈)。

配阴性对照用例: 普通 429 突发限流必须仍走短熔断 —— 否则本改动会把 QPS
限流罚 6 小时。
" -- backend/python/common/llm_router.py backend/python/tests/test_llm_router_quota_skip.py
git show --name-only --format="%h %s" HEAD
```

---

## Task 3: `_SAFE_MODELS` 按 2026-08-09 实测重写

**Files:**
- Modify: `backend/python/common/llm_router.py:95`(`_REGISTRY_AUDIT_DATE`)、`:114-254`(`_SAFE_MODELS`)、`:273-288`(`_MINIMAL_SAFE_SET`)
- Modify: `backend/python/tests/test_llm_router_registry.py`

**Interfaces:**
- Consumes: Task 1 建立的 `tests/test_llm_router_registry.py`
- Produces: 重写后的 `_SAFE_MODELS`,Task 4 的 `_SLOT_POOLS` 只能从中取条目

⚠️ 本任务会让 `tests/test_llm_router_fallback.py` 变红(它引用了被删除的模型)。Step 5 一并修复,**不允许**用跳过或放宽断言绕过。

- [ ] **Step 1: 写冻结表测试**

在 `backend/python/tests/test_llm_router_registry.py` 追加。注意这是**人写死的冻结表**比**代码里的注册表**,两边来源不同 —— 不是恒真式:

```python
import datetime

# 2026-08-09 三账号控制台截图 ∩ 生产探针(经 _apply_slot_params, 判据为非空 content)
# 的交集。单边证据不收: 控制台有余量但探针 403 的不收(aliyun_c/deepseek-v4-flash-0731);
# 探针 200 但控制台未列的更不收 —— 那说明「用完即停」没覆盖它, 可能真在计费(glm-5.2)。
_FROZEN_ALIYUN_REGISTRY = {
    ("aliyun_a", "qwen3.8-max"): datetime.date(2026, 11, 1),
    ("aliyun_a", "deepseek-v4-flash-0731"): datetime.date(2026, 10, 31),
    ("aliyun_a", "qwen3.7-flash"): datetime.date(2026, 10, 23),
    ("aliyun_a", "qwen3.7-flash-2026-07-15"): datetime.date(2026, 10, 23),
    ("aliyun_a", "qwen3.5-ocr"): datetime.date(2026, 9, 14),
    ("aliyun_a", "kimi-k2.7-code"): datetime.date(2026, 9, 14),
    ("aliyun_a", "qwen3.7-max-2026-05-17"): datetime.date(2026, 8, 24),
    ("aliyun_a", "qwen3.7-max-preview"): datetime.date(2026, 8, 24),

    ("aliyun_b", "qwen3.8-max"): datetime.date(2026, 11, 1),
    ("aliyun_b", "deepseek-v4-flash-0731"): datetime.date(2026, 10, 31),
    ("aliyun_b", "qwen3.5-ocr"): datetime.date(2026, 9, 14),
    ("aliyun_b", "kimi-k2.7-code"): datetime.date(2026, 9, 14),
    ("aliyun_b", "qwen3.7-max-2026-05-17"): datetime.date(2026, 8, 24),
    ("aliyun_b", "qwen3.7-max-preview"): datetime.date(2026, 8, 24),

    ("aliyun_c", "qwen3.8-max"): datetime.date(2026, 11, 1),
    ("aliyun_c", "kimi-k2.7-code"): datetime.date(2026, 9, 14),
    ("aliyun_c", "qwen3.5-ocr"): datetime.date(2026, 9, 14),
    ("aliyun_c", "qwen3.7-max-2026-05-17"): datetime.date(2026, 8, 24),
    ("aliyun_c", "qwen3.7-max-preview"): datetime.date(2026, 8, 24),
    ("aliyun_c", "qwen3.7-max"): datetime.date(2026, 8, 20),
    ("aliyun_c", "qwen3.7-max-2026-05-20"): datetime.date(2026, 8, 20),
    ("aliyun_c", "qwen3-next-80b-a3b-instruct"): datetime.date(2026, 8, 13),
    ("aliyun_c", "deepseek-v3.2-exp"): datetime.date(2026, 8, 13),
    ("aliyun_c", "glm-4.6"): datetime.date(2026, 8, 13),
    ("aliyun_c", "deepseek-v3.2"): datetime.date(2026, 8, 13),
    ("aliyun_c", "qwen3.6-plus-2026-04-02"): datetime.date(2026, 8, 13),
    ("aliyun_c", "qwen3.5-plus-2026-02-15"): datetime.date(2026, 8, 13),
    ("aliyun_c", "qwen3-max-2025-09-23"): datetime.date(2026, 8, 13),
    ("aliyun_c", "qwen3-vl-flash-2026-01-22"): datetime.date(2026, 8, 13),
    ("aliyun_c", "qwen3-vl-32b-instruct"): datetime.date(2026, 8, 13),
    ("aliyun_c", "kimi-k2-thinking"): datetime.date(2026, 8, 13),
    ("aliyun_c", "deepseek-r1"): datetime.date(2026, 8, 13),
    ("aliyun_c", "qwen3-235b-a22b-thinking-2507"): datetime.date(2026, 8, 13),
    ("aliyun_c", "deepseek-r1-0528"): datetime.date(2026, 8, 13),
    ("aliyun_c", "MiniMax-M2.5"): datetime.date(2026, 8, 13),
}


def test_aliyun_registry_matches_frozen_probe_result():
    """人审冻结表 vs 代码里的注册表。两边来源不同, 不是恒真式。

    改注册表必须同步改这张表, 而改这张表要求有当天的控制台+探针证据。
    """
    actual = {k: v for k, v in llm_router._SAFE_MODELS.items()
              if k[0].startswith("aliyun_")}
    assert actual == _FROZEN_ALIYUN_REGISTRY


def test_non_aliyun_registry_matches_frozen_probe_result():
    """地板: tencent 收缩到 1 个, ark 清空(provider 配置保留), zhipu 只剩文本地板。"""
    actual = {k: v for k, v in llm_router._SAFE_MODELS.items()
              if not k[0].startswith("aliyun_")}
    assert actual == {
        ("tencent", "minimax-m2.7"): None,
        ("zhipu", "glm-4.5-air"): None,
    }


def test_registry_audit_date_is_not_stale():
    """审计日期过期(>21d) → router 收缩到 _MINIMAL_SAFE_SET。

    ⏰ 这是一道**故意的定时闸, 不是缺陷**。它会在 _REGISTRY_AUDIT_DATE + 21 天
    那一刻变红, 与任何人改了什么代码无关 —— 这正是它存在的意义。

    红了怎么办: 去三个控制台核对余量 + 跑 scripts/probe_llm_registry, 按判据
    (控制台有余量 ∩ 探针非空内容)更新 _SAFE_MODELS 与本文件的冻结表, 然后把
    _REGISTRY_AUDIT_DATE 推到复审当天。⛔ 不许只推日期不复审 —— 那等于把这道
    闸拆了。

    为什么必须是红灯而不是告警: 2026-08-09 之所以出事, 恰恰是因为没人盯这个
    日期(现值 07-26, 距 staleness 只剩 7 天), 而 prod 里同时有 23 个模型在
    额度退避、约 1800 万 token 可用额度 router 够不着 —— 告警在那 6 天里
    每天都发, 没人处理。红灯拦得住, 告警拦不住。
    """
    assert not llm_router._registry_stale(llm_router._today())
```

- [ ] **Step 2: 运行测试确认它红**

```bash
cd backend/python && python -m pytest tests/test_llm_router_registry.py -v
```

Expected: `test_aliyun_registry_matches_frozen_probe_result` 与 `test_non_aliyun_registry_matches_frozen_probe_result` FAIL(实际注册表还是旧的)

- [ ] **Step 3: 重写 `_SAFE_MODELS`**

把 `backend/python/common/llm_router.py` 第 114 行开始的整个 `_SAFE_MODELS` 字典体替换为下面内容(保留字典的类型注解行 `_SAFE_MODELS: Dict[Tuple[str, str], Optional[datetime.date]] = {`,替换其中的条目):

```python
    # ══ 2026-08-09 全量重审 ══════════════════════════════════════════════
    # 判据: 控制台显示有余量 ∩ 探针(经 _apply_slot_params, 判据为非空 content)通过。
    # 单边证据一律不收 —— 探针 200 但控制台无余量的最危险: 那说明「免费额度
    # 用完即停」没覆盖它, 那个 200 可能是真在计费(glm-5.2 即因此三账号全删)。
    # 顺序无意义, 链顺序由 _build_chain 按到期日算; 这里只是事实表。

    # ── aliyun_a (控制台 8 个有额度, 全部通过探针) ──
    ("aliyun_a", "qwen3.8-max"): datetime.date(2026, 11, 1),
    ("aliyun_a", "deepseek-v4-flash-0731"): datetime.date(2026, 10, 31),
    ("aliyun_a", "qwen3.7-flash"): datetime.date(2026, 10, 23),
    ("aliyun_a", "qwen3.7-flash-2026-07-15"): datetime.date(2026, 10, 23),
    ("aliyun_a", "qwen3.5-ocr"): datetime.date(2026, 9, 14),
    ("aliyun_a", "kimi-k2.7-code"): datetime.date(2026, 9, 14),
    ("aliyun_a", "qwen3.7-max-2026-05-17"): datetime.date(2026, 8, 24),
    ("aliyun_a", "qwen3.7-max-preview"): datetime.date(2026, 8, 24),

    # ── aliyun_b (控制台 6 个有额度, 全部通过探针) ──
    ("aliyun_b", "qwen3.8-max"): datetime.date(2026, 11, 1),
    ("aliyun_b", "deepseek-v4-flash-0731"): datetime.date(2026, 10, 31),
    ("aliyun_b", "qwen3.5-ocr"): datetime.date(2026, 9, 14),
    ("aliyun_b", "kimi-k2.7-code"): datetime.date(2026, 9, 14),
    ("aliyun_b", "qwen3.7-max-2026-05-17"): datetime.date(2026, 8, 24),
    ("aliyun_b", "qwen3.7-max-preview"): datetime.date(2026, 8, 24),

    # ── aliyun_c 长期 (> 08-13) ──
    ("aliyun_c", "qwen3.8-max"): datetime.date(2026, 11, 1),
    ("aliyun_c", "kimi-k2.7-code"): datetime.date(2026, 9, 14),
    ("aliyun_c", "qwen3.5-ocr"): datetime.date(2026, 9, 14),
    ("aliyun_c", "qwen3.7-max-2026-05-17"): datetime.date(2026, 8, 24),
    ("aliyun_c", "qwen3.7-max-preview"): datetime.date(2026, 8, 24),
    ("aliyun_c", "qwen3.7-max"): datetime.date(2026, 8, 20),
    ("aliyun_c", "qwen3.7-max-2026-05-20"): datetime.date(2026, 8, 20),

    # ── aliyun_c 08-13 到期 (优先榨干; _build_chain 会把它们排在最前) ──
    ("aliyun_c", "qwen3-next-80b-a3b-instruct"): datetime.date(2026, 8, 13),
    ("aliyun_c", "deepseek-v3.2-exp"): datetime.date(2026, 8, 13),
    ("aliyun_c", "glm-4.6"): datetime.date(2026, 8, 13),
    ("aliyun_c", "deepseek-v3.2"): datetime.date(2026, 8, 13),
    ("aliyun_c", "qwen3.6-plus-2026-04-02"): datetime.date(2026, 8, 13),
    ("aliyun_c", "qwen3.5-plus-2026-02-15"): datetime.date(2026, 8, 13),
    ("aliyun_c", "qwen3-max-2025-09-23"): datetime.date(2026, 8, 13),
    ("aliyun_c", "qwen3-vl-flash-2026-01-22"): datetime.date(2026, 8, 13),
    ("aliyun_c", "qwen3-vl-32b-instruct"): datetime.date(2026, 8, 13),
    ("aliyun_c", "kimi-k2-thinking"): datetime.date(2026, 8, 13),
    ("aliyun_c", "deepseek-r1"): datetime.date(2026, 8, 13),
    ("aliyun_c", "qwen3-235b-a22b-thinking-2507"): datetime.date(2026, 8, 13),
    ("aliyun_c", "deepseek-r1-0528"): datetime.date(2026, 8, 13),
    ("aliyun_c", "MiniMax-M2.5"): datetime.date(2026, 8, 13),

    # ── 地板 (无到期日 → _expiry_of 返回 _FAR_FUTURE → 必然排最后) ──
    # tencent 9 个条目实测只剩这 1 个: 7 个 401008 FREE_QUOTA_EXHAUSTED,
    # kimi-k2.6 走参数层仍返回空内容。ark 两个全部 SetLimitExceeded → 条目
    # 清空, 但 _provider_config 里的 ark 配置与代码路径保留, 待 owner 提供
    # 完整可用清单后按判据加回(改数据即可, 不改代码)。
    # zhipu/glm-4.6v 已因 429 余额不足死亡, 从 VL 地板剔除(见 Task 5 VL 豁免)。
    ("tencent", "minimax-m2.7"): None,
    ("zhipu", "glm-4.5-air"): None,
```

- [ ] **Step 4: 推进审计日期并更新最小安全集**

把第 95 行:

```python
_REGISTRY_AUDIT_DATE = datetime.date(2026, 7, 26)  # 三控制台实测核对
```

改为:

```python
_REGISTRY_AUDIT_DATE = datetime.date(2026, 8, 9)   # 三控制台截图 ∩ 生产探针全量核对
```

把 `_MINIMAL_SAFE_SET` 整体替换为(旧集合里 `glm-5.2` / `qwen-plus-latest` / `deepseek-v3.1` / `qwen3-vl-plus-2025-12-19` / `qwen3.7-max-2026-06-08` / `aliyun_b` 的两个 flash / `zhipu/glm-4.6v` 均已实测死亡):

```python
_MINIMAL_SAFE_SET: frozenset = frozenset({
    # 2026-08-09 重建: 旧集合 13 个条目里 8 个已实测死亡 —— fail-safe 退守的
    # 目标本身是死的。只收「跑道最长 + 当天探针通过」的条目。
    ("aliyun_a", "qwen3.8-max"), ("aliyun_b", "qwen3.8-max"),
    ("aliyun_c", "qwen3.8-max"),                       # 11/01, 三账号各 100 万
    ("aliyun_a", "deepseek-v4-flash-0731"),            # 10/31
    ("aliyun_a", "qwen3.7-flash"),                     # 10/23 fast JSON/text
    ("aliyun_a", "qwen3.7-flash-2026-07-15"),          # 10/23
    ("aliyun_c", "kimi-k2.7-code"),                    # 09/14
    ("tencent", "minimax-m2.7"), ("zhipu", "glm-4.5-air"),   # 非 DashScope 文本地板
})
```

⚠️ 注意:新的最小集**不含任何 VL 模型**。这是有意的 —— `zhipu/glm-4.6v` 已死,VL 槽按 spec §9.1 接受空链并明确报错。

- [ ] **Step 5: 修复因删除条目而变红的现有测试**

```bash
cd backend/python && python -m pytest tests/test_llm_router_registry.py tests/test_llm_router_fallback.py tests/test_llm_router_budget.py tests/test_llm_router_cb.py tests/test_llm_router_quota_skip.py -v
```

`tests/test_llm_router_fallback.py` 会红,因为它断言的链条目已被删除。逐条按**新的事实**修正断言 —— ⛔ 不允许 `pytest.mark.skip`、不允许把断言放宽成 `>=0`。若某条断言表达的是"某槽必须有 N 个 aliyun 条目"这类会随额度自然变化的量,改成断言"该槽链非空且末位是地板"。

- [ ] **Step 6: 运行全部 router 测试确认绿**

```bash
cd backend/python && python -m pytest tests/ -k llm_router -v
```

Expected: 全部 PASS

- [ ] **Step 7: 变异验证**

把 `("aliyun_a", "qwen3.8-max"): datetime.date(2026, 11, 1),` 临时改成 `datetime.date(2026, 12, 1)`,重跑 Step 6,`test_aliyun_registry_matches_frozen_probe_result` 必须 FAIL。确认后改回。

- [ ] **Step 8: 提交**

```bash
cd /c/Users/Steve/cretas-llm-router
git add backend/python/common/llm_router.py backend/python/tests/test_llm_router_registry.py backend/python/tests/test_llm_router_fallback.py
git status --short
git commit -m "feat(llm-router): _SAFE_MODELS 按 2026-08-09 三账号实测全量重写

判据: 控制台有余量 ∩ 探针(经 _apply_slot_params, 判据为非空 content)通过。
单边证据不收 —— 探针 200 但控制台无余量的最危险(说明用完即停没覆盖它,
可能真在计费), glm-5.2 因此三账号全删。

修正的事实错误: qwen3.7-flash 登在已耗尽的 aliyun_b, 而满额 100 万的
aliyun_a 一个都没登记 —— 账号写反了。新增 qwen3.8-max / qwen3.5-ocr /
deepseek-v4-flash-0731 等约 1800 万 token 此前完全未登记的额度。
删除 aliyun_a/b 在链里那 5 个实测 5/5 全 403 的条目。

_MINIMAL_SAFE_SET 一并重建: 旧集合 13 个条目里 8 个已实测死亡, fail-safe
退守的目标本身是死的。审计日期 07-26 → 08-09(距 staleness 原本只剩 7 天)。
" -- backend/python/common/llm_router.py backend/python/tests/test_llm_router_registry.py backend/python/tests/test_llm_router_fallback.py
git show --name-only --format="%h %s" HEAD
```

---

## Task 4: `_SLOT_POOLS` + `_build_chain` 取代手写链

**Files:**
- Modify: `backend/python/common/llm_router.py:831-857`(`_TEXT_TAIL`)、`:859-863`(`_VL_CHAIN`)、`:865-1039`(`SLOT_MODELS` 整段)

**Interfaces:**
- Consumes: `_SAFE_MODELS`(Task 3)、`_expiry_of(account, model) -> datetime.date`、`_dedup_chain(pairs) -> List[Tuple[str,str]]`(均为现有)
- Produces:
  - `_SLOW_MODELS: frozenset[str]` — 实测慢的模型名
  - `_THINKING_OFF_ONLY: frozenset[str]` — 开思考会返回空或极慢
  - `_REASONING_ONLY: frozenset[str]` — 关思考会 400
  - `_SLOT_POOLS: Dict[SLOT, List[Tuple[str, str]]]`
  - `_build_chain(slot: SLOT) -> List[Tuple[str, str]]`
  - `SLOT_MODELS: Dict[SLOT, List[Tuple[str, str]]]`(签名不变,改为计算生成)

- [ ] **Step 1: 替换 `_TEXT_TAIL`**

把第 831 行开始的 `_TEXT_TAIL` 整个列表体替换为:

```python
_TEXT_TAIL: List[Tuple[str, str]] = [
    # 非 DashScope 地板 —— 到期日为 None → _expiry_of 返回 _FAR_FUTURE →
    # _build_chain 的排序必然把它们放在所有 aliyun 条目之后。这正是
    # 「先榨干会过期的, 不过期的留到最后」。
    #
    # 2026-08-09 实测收缩: tencent 9 个只剩 minimax-m2.7(6.7s, 偏慢但是
    # 唯一非阿里/非智谱的活口); 其余 7 个 401008 FREE_QUOTA_EXHAUSTED,
    # kimi-k2.6 走参数层仍返回空内容。ark 两个全部 SetLimitExceeded → 清空。
    # 两家 provider 的配置与代码路径均保留, 待补齐清单后加回。
    ("tencent", "minimax-m2.7"),
    ("zhipu", "glm-4.5-air"),
]
```

- [ ] **Step 2: 删除 `_VL_CHAIN` 并新增三个约束名单 + `_SLOT_POOLS` + `_build_chain`**

把第 859 行的 `_VL_CHAIN` 定义、以及第 865-1039 行的整个 `SLOT_MODELS` 字面量,一起替换为:

```python
# ══ 资格层 ══════════════════════════════════════════════════════════════
# 下面三个名单是**人写的实测结论**, 独立于 _SLOT_POOLS 的定义 —— 闸拿它们
# 比对池内容时两边来源不同, 不是恒真式。

# 实测慢(关思考档 > 4s 或真实负载击穿 12s 交互预算)。禁止进交互槽的池。
# ⚠️ tencent/minimax-m2.7 也在此列, 但它属于 _TEXT_TAIL 地板, 由 _build_chain
#    单独追加, 不受本名单约束 —— 地板的职责是"前面全挂了还能答", 慢于不答。
_SLOW_MODELS: frozenset = frozenset({
    "deepseek-r1",                     # 8.6s 空载 / 13-25s 真实 REVIEW 负载
    "deepseek-r1-0528",                # 12.1s
    "qwen3-235b-a22b-thinking-2507",   # 9.5s
    "kimi-k2-thinking",                # 5.2s
    "qwen3.7-max-preview",             # 6.0-8.5s
    "minimax-m2.7",                    # 6.7s (地板, 见上)
})

# 开思考会返回空 content 或极慢 → 只进 profile 里 enable_thinking=false 的槽。
# 2026-08-09 实测: glm-4.6 推理档 44s / qwen3.6-plus-2026-04-02 17.8s /
# qwen3.5-plus-2026-02-15 21.1s (关思考档全部 ~1s)。
_THINKING_OFF_ONLY: frozenset = frozenset({
    "glm-4.6", "qwen3.6-plus-2026-04-02", "qwen3.5-plus-2026-02-15",
})

# 关思考会 400 → 只能进 REASONING(其 profile 为 {}, 不设 enable_thinking)。
# 2026-08-09 实测 aliyun_c/MiniMax-M2.5: 关思考 400, 开思考 3.6s OK。
_REASONING_ONLY: frozenset = frozenset({"MiniMax-M2.5"})


# ══ 候选池 ══════════════════════════════════════════════════════════════
# INSIGHTS 与 REVIEW 共用同一个质量档池: 两者判据逐字相同(质量优先 + 关思考档
# ≤4s), 各写一份 21 行迟早漂移成两张不一致的表。将来若真分化(例如 REVIEW 需要
# 更强的多轮上下文继承能力, 见 2026-08-09 的判别实验), 再从这里拆开。
_QUALITY_TIER_POOL: List[Tuple[str, str]] = [
    ("aliyun_c", "deepseek-v3.2"),                 # 08-13  1.1s
    ("aliyun_c", "glm-4.6"),                       # 08-13  0.9s
    ("aliyun_c", "qwen3-max-2025-09-23"),          # 08-13  1.6s
    ("aliyun_c", "qwen3.5-plus-2026-02-15"),       # 08-13  1.2s
    ("aliyun_c", "qwen3.6-plus-2026-04-02"),       # 08-13  1.1s
    ("aliyun_c", "qwen3-next-80b-a3b-instruct"),   # 08-13  0.8s
    ("aliyun_c", "qwen3.7-max-2026-05-20"),        # 08-20  1.1s
    ("aliyun_c", "qwen3.7-max"),                   # 08-20  1.2s
    ("aliyun_c", "qwen3.7-max-2026-05-17"),        # 08-24  1.9s
    ("aliyun_a", "qwen3.7-max-2026-05-17"),        # 08-24  3.2s
    ("aliyun_b", "qwen3.7-max-2026-05-17"),        # 08-24  3.9s
    ("aliyun_c", "kimi-k2.7-code"),                # 09-14  1.8s
    ("aliyun_b", "kimi-k2.7-code"),                # 09-14  1.8s
    ("aliyun_a", "kimi-k2.7-code"),                # 09-14  2.1s
    ("aliyun_a", "qwen3.7-flash"),                 # 10-23  0.5s
    ("aliyun_a", "qwen3.7-flash-2026-07-15"),      # 10-23  0.6s
    ("aliyun_b", "deepseek-v4-flash-0731"),        # 10-31  1.3s
    ("aliyun_a", "deepseek-v4-flash-0731"),        # 10-31  1.5s
    ("aliyun_c", "qwen3.8-max"),                   # 11-01  1.0s
    ("aliyun_a", "qwen3.8-max"),                   # 11-01  1.1s
    ("aliyun_b", "qwen3.8-max"),                   # 11-01  1.1s
]

# 每个槽只声明「够资格」的候选。⛔ 这里的顺序**不是**最终链顺序 ——
# 它只在「同一到期日」时生效(_build_chain 用稳定排序), 表达的是质量优先级。
# 跨到期日的先后由 _build_chain 按到期日升序算, 人不要在这里排。
_SLOT_POOLS: Dict[SLOT, List[Tuple[str, str]]] = {
    # CHAT — 高频低延迟, 关思考。只收关思考档 ≤2s 的通用文本模型。
    SLOT.CHAT: [
        ("aliyun_c", "qwen3-next-80b-a3b-instruct"),   # 08-13  0.8s
        ("aliyun_c", "deepseek-v3.2-exp"),             # 08-13  0.9s
        ("aliyun_c", "glm-4.6"),                       # 08-13  0.9s
        ("aliyun_c", "deepseek-v3.2"),                 # 08-13  1.1s
        ("aliyun_c", "qwen3.6-plus-2026-04-02"),       # 08-13  1.1s
        ("aliyun_c", "qwen3.5-plus-2026-02-15"),       # 08-13  1.2s
        ("aliyun_c", "qwen3-max-2025-09-23"),          # 08-13  1.6s
        ("aliyun_c", "qwen3.7-max-2026-05-20"),        # 08-20  1.1s
        ("aliyun_c", "qwen3.7-max"),                   # 08-20  1.2s
        ("aliyun_a", "qwen3.7-flash"),                 # 10-23  0.5s
        ("aliyun_a", "qwen3.7-flash-2026-07-15"),      # 10-23  0.6s
        ("aliyun_b", "deepseek-v4-flash-0731"),        # 10-31  1.3s
        ("aliyun_a", "deepseek-v4-flash-0731"),        # 10-31  1.5s
        ("aliyun_c", "qwen3.8-max"),                   # 11-01  1.0s
        ("aliyun_a", "qwen3.8-max"),                   # 11-01  1.1s
        ("aliyun_b", "qwen3.8-max"),                   # 11-01  1.1s
    ],
    # CHART — 紧凑 JSON (关思考 + json_object)。与 CHAT 同一批快模型。
    SLOT.CHART: [
        ("aliyun_c", "qwen3-next-80b-a3b-instruct"),
        ("aliyun_c", "deepseek-v3.2-exp"),
        ("aliyun_c", "glm-4.6"),
        ("aliyun_c", "deepseek-v3.2"),
        ("aliyun_c", "qwen3-max-2025-09-23"),
        ("aliyun_c", "qwen3.7-max-2026-05-20"),
        ("aliyun_c", "qwen3.7-max"),
        ("aliyun_a", "qwen3.7-flash"),
        ("aliyun_a", "qwen3.7-flash-2026-07-15"),
        ("aliyun_b", "deepseek-v4-flash-0731"),
        ("aliyun_a", "deepseek-v4-flash-0731"),
        ("aliyun_c", "qwen3.8-max"),
        ("aliyun_a", "qwen3.8-max"),
        ("aliyun_b", "qwen3.8-max"),
    ],
    # MAPPER — 短 JSON 字段映射。池比 CHAT 更窄: Max 级对短分类既慢又浪费。
    SLOT.MAPPER: [
        ("aliyun_c", "qwen3-next-80b-a3b-instruct"),
        ("aliyun_c", "deepseek-v3.2-exp"),
        ("aliyun_c", "glm-4.6"),
        ("aliyun_c", "deepseek-v3.2"),
        ("aliyun_a", "qwen3.7-flash"),
        ("aliyun_a", "qwen3.7-flash-2026-07-15"),
        ("aliyun_b", "deepseek-v4-flash-0731"),
        ("aliyun_a", "deepseek-v4-flash-0731"),
    ],
    # INSIGHTS / REVIEW — 共用质量档池, 见下方 _QUALITY_TIER_POOL 定义。
    SLOT.INSIGHTS: list(_QUALITY_TIER_POOL),
    SLOT.REVIEW: list(_QUALITY_TIER_POOL),
    # REASONING — 允许慢, profile 为 {} (不设 enable_thinking)。
    SLOT.REASONING: [
        ("aliyun_c", "deepseek-v3.2"),
        ("aliyun_c", "deepseek-v3.2-exp"),
        ("aliyun_c", "qwen3-next-80b-a3b-instruct"),
        ("aliyun_c", "MiniMax-M2.5"),                  # 仅此槽可用(关思考会 400)
        ("aliyun_c", "kimi-k2-thinking"),
        ("aliyun_c", "deepseek-r1"),
        ("aliyun_c", "qwen3-235b-a22b-thinking-2507"),
        ("aliyun_c", "deepseek-r1-0528"),
        ("aliyun_c", "qwen3.7-max-2026-05-17"),
        ("aliyun_a", "qwen3.7-max-2026-05-17"),
        ("aliyun_b", "qwen3.7-max-2026-05-17"),
        ("aliyun_c", "qwen3.7-max-preview"),
        ("aliyun_a", "qwen3.7-max-preview"),
        ("aliyun_b", "qwen3.7-max-preview"),
        ("aliyun_c", "kimi-k2.7-code"),
        ("aliyun_b", "kimi-k2.7-code"),
        ("aliyun_a", "kimi-k2.7-code"),
        ("aliyun_b", "deepseek-v4-flash-0731"),
        ("aliyun_a", "deepseek-v4-flash-0731"),
        ("aliyun_c", "qwen3.8-max"),
        ("aliyun_a", "qwen3.8-max"),
        ("aliyun_b", "qwen3.8-max"),
    ],
    # VL — 仅视觉。⚠️ 2026-08-13 后这两个双双过期, 链会变空, call_chain 抛
    #      All providers exhausted for vl。这是**期望行为**(spec §9.1,
    #      owner 2026-08-09 拍板): 业务用不到 VL(prod 7 天仅 1 次真实调用),
    #      且原 VL 地板 zhipu/glm-4.6v 已因余额不足死亡。明确报错优于把图片
    #      请求静默降级给文本模型瞎猜 —— CLAUDE.md 核心原则 1。
    SLOT.VL: [
        ("aliyun_c", "qwen3-vl-flash-2026-01-22"),     # 08-13  0.7s
        ("aliyun_c", "qwen3-vl-32b-instruct"),         # 08-13  1.0s
    ],
}

# VL 槽不追加文本地板 —— 文本模型看不见图片, 追加只会把「明确失败」变成
# 「拿一段瞎猜的文字冒充图片理解」。这个集合是 _build_chain 的唯一例外,
# 也是 test_every_text_slot_has_a_floor 的唯一豁免项。
_NO_TEXT_TAIL_SLOTS: frozenset = frozenset({SLOT.VL})


def _build_chain(slot: SLOT) -> List[Tuple[str, str]]:
    """按免费额度到期日升序拼链 —— use-it-or-lose-it。

    ⚠️ 这里**推翻**了旧注释 "Runtime order is authoritative (no re-sort)"。
    旧契约要求人手写最终顺序, 而 _SAFE_MODELS 的 docstring 与 _expiry_of()
    从一开始就写着 "soonest-expiry-first" —— 意图在注释里, 约束不存在, 于是
    每个到期日都要人改一次, 漏一次链就腐烂一次。2026-08-09 实测后果: 三个
    aliyun 账号约 1800 万 token 可用额度 router 一个都够不着, 而链里 5 个
    aliyun_a/b 条目实测 5/5 全 403 在空转。改成代码算, 到期日一到自动重排。

    稳定排序: 同一到期日保持 _SLOT_POOLS 里人写的顺序(= 质量优先级),
    只有跨到期日才重排。到期日为 None 的地板 → _FAR_FUTURE → 必然沉底。
    """
    entries = list(_SLOT_POOLS[slot])
    if slot not in _NO_TEXT_TAIL_SLOTS:
        entries += _TEXT_TAIL
    return _dedup_chain(sorted(entries, key=lambda p: _expiry_of(*p)))


SLOT_MODELS: Dict[SLOT, List[Tuple[str, str]]] = {s: _build_chain(s) for s in SLOT}
```

- [ ] **Step 3: 运行现有 router 测试,看链改动打破了什么**

```bash
cd backend/python && python -m pytest tests/ -k llm_router -v
```

Expected: `test_llm_router_fallback.py` 里若干断言 FAIL(它们假设了旧的链头)。逐条按新事实修正 —— ⛔ 不允许 skip、不允许放宽。

- [ ] **Step 4: 打印新链人工过目**

```bash
cd backend/python && python -c "
import sys; sys.path.insert(0,'.')
from common import llm_router as r
for s, chain in r.SLOT_MODELS.items():
    print('==', s.value, '链长', len(chain))
    for a, m in chain:
        print('   %-10s %-32s %s' % (a, m, r._expiry_of(a, m)))
"
```

肉眼确认三件事:① 每个文本槽末尾是 `tencent/minimax-m2.7` + `zhipu/glm-4.5-air`;② 到期日自上而下非降序;③ VL 槽只有 2 个条目且没有地板。

- [ ] **Step 5: 提交**

```bash
cd /c/Users/Steve/cretas-llm-router
git add backend/python/common/llm_router.py backend/python/tests/test_llm_router_fallback.py
git status --short
git commit -m "feat(llm-router): 链顺序改为按免费额度到期日升序自动计算

推翻旧契约 'Runtime order is authoritative (no re-sort)'。_SAFE_MODELS 的
docstring 与 _expiry_of() 从一开始就写着 soonest-expiry-first, 但从来没有
任何代码调用它排链 —— 意图在注释里, 约束不存在。后果: 每个到期日都要人改
一次, 漏一次就腐烂一次。

拆成资格层(_SLOT_POOLS, 人写, 月级变化)+ 顺序层(_build_chain, 代码算)。
稳定排序保证同到期日保持人写的质量顺序, 跨到期日才重排; 地板到期日为 None
→ _FAR_FUTURE → 必然沉底, 即'先榨干会过期的'。08-13/08-20/08-24/09-14/
10-23/10-31/11-01 七个到期日此后全部自动生效, 无需人工干预。

VL 槽不追加文本地板并将于 08-13 变空链 —— 期望行为, 见 spec §9.1。
" -- backend/python/common/llm_router.py backend/python/tests/test_llm_router_fallback.py
git show --name-only --format="%h %s" HEAD
```

---

## Task 5: 四道承重闸 + golden 快照

**Files:**
- Create: `backend/python/tests/test_llm_router_chains.py`
- Create: `backend/python/tests/golden/llm_router_chains.txt`

**Interfaces:**
- Consumes: Task 4 的 `_SLOT_POOLS`、`_SLOW_MODELS`、`_THINKING_OFF_ONLY`、`_REASONING_ONLY`、`_NO_TEXT_TAIL_SLOTS`、`_build_chain`、`SLOT_MODELS`;现有 `_SLOT_PARAMS`、`_FAR_FUTURE`、`_expiry_of`
- Produces: 无生产接口

⛔ **明确不写** `assert chain == sorted(chain, key=_expiry_of)`。链就是这么造出来的,左右同源,恒真式,一次都红不了。

- [ ] **Step 1: 写四道闸(先写测试,此时 golden 文件还不存在 → 必红)**

创建 `backend/python/tests/test_llm_router_chains.py`:

```python
"""
_build_chain 产出的闸。

⛔ 明确不写 assert chain == sorted(chain, key=_expiry_of) —— 链就是用这个
   key 造出来的, 左右同源, 恒真式, 一次都红不了。承重的是下面这张人审冻结的
   golden 快照: 它的另一端是人, 不是代码。
"""
from pathlib import Path

import pytest

from common import llm_router
from common.llm_router import SLOT

_GOLDEN = Path(__file__).parent / "golden" / "llm_router_chains.txt"


def _render_chains() -> str:
    lines = []
    for slot in SLOT:
        chain = llm_router.SLOT_MODELS[slot]
        lines.append(f"== {slot.value} (len={len(chain)})")
        for account, model in chain:
            lines.append(f"   {llm_router._expiry_of(account, model)}  {account}/{model}")
    return "\n".join(lines) + "\n"


def test_chains_match_human_reviewed_golden():
    """人审冻结的链快照 vs 代码算出的链。

    链顺序变了必须有人看一眼 diff 并主动更新这个文件 —— 这是本次唯一能
    抓住「排序规则被改坏」的闸。重新生成:
        python -c "import sys;sys.path.insert(0,'.');\\
        from tests.test_llm_router_chains import _render_chains;\\
        open('tests/golden/llm_router_chains.txt','w',encoding='utf-8',newline='\\n')\\
        .write(_render_chains())"
    ⚠️ 必须 newline='\\n' —— Windows 上默认写入会把整个文件转成 CRLF。
    """
    assert _GOLDEN.exists(), f"golden 文件不存在: {_GOLDEN}"
    expected = _GOLDEN.read_text(encoding="utf-8")
    assert _render_chains() == expected


def test_every_text_slot_has_a_floor():
    """每个文本槽末尾必须有一个永不过期的地板条目。

    没有这条, 某次注册表重写把 tencent/zhipu 删空后, 所有槽会在最后一个
    aliyun 条目过期的那天同时变成空链 —— 而 CI 全绿。

    VL 是唯一豁免项(spec §9.1: 业务不用, 明确报错优于文本模型瞎猜图片)。
    豁免名单硬编码在 _NO_TEXT_TAIL_SLOTS, 想再豁免一个槽必须改代码留下 diff。
    """
    for slot in SLOT:
        if slot in llm_router._NO_TEXT_TAIL_SLOTS:
            continue
        chain = llm_router.SLOT_MODELS[slot]
        assert chain, f"{slot.value} 链为空"
        floors = [p for p in chain if llm_router._expiry_of(*p) == llm_router._FAR_FUTURE]
        assert floors, f"{slot.value} 没有永不过期的地板条目, 全部 aliyun 过期那天会整槽变空"
        assert chain[-1] in floors, f"{slot.value} 末位不是地板: {chain[-1]}"


@pytest.mark.parametrize(
    "slot", [SLOT.CHAT, SLOT.CHART, SLOT.MAPPER, SLOT.REVIEW, SLOT.INSIGHTS]
)
def test_interactive_pools_exclude_slow_models(slot):
    """交互槽的候选池不能含实测慢模型。

    _SLOW_MODELS 是独立于池定义的人写实测名单, 两边来源不同, 不是恒真式。
    只约束 _SLOT_POOLS —— 地板由 _build_chain 单独追加, 慢于不答。
    """
    offenders = [p for p in llm_router._SLOT_POOLS[slot]
                 if p[1] in llm_router._SLOW_MODELS]
    assert offenders == [], (
        f"{slot.value} 池含慢模型 {offenders} —— 会把'答不出来'换成'等到超时'"
    )


def test_param_profile_constraints_are_respected():
    """关思考槽不能收 _REASONING_ONLY; REASONING 槽不能收 _THINKING_OFF_ONLY。

    2026-08-09 实测: MiniMax-M2.5 关思考直接 400; glm-4.6 开思考 44s、
    qwen3.5-plus-2026-02-15 开思考 21s。放错槽 = 稳定 400 或稳定超时。
    """
    for slot, pool in llm_router._SLOT_POOLS.items():
        profile = llm_router._SLOT_PARAMS.get(slot) or {}
        thinking_off = profile.get("enable_thinking") is False
        for account, model in pool:
            if thinking_off:
                assert model not in llm_router._REASONING_ONLY, (
                    f"{slot.value} 关思考, 但收了只能开思考的 {account}/{model} → 稳定 400"
                )
            else:
                assert model not in llm_router._THINKING_OFF_ONLY, (
                    f"{slot.value} 不关思考, 但收了开思考会空/极慢的 {account}/{model}"
                )
```

- [ ] **Step 2: 运行确认红**

```bash
cd backend/python && python -m pytest tests/test_llm_router_chains.py -v
```

Expected: `test_chains_match_human_reviewed_golden` FAIL(golden 文件不存在);其余三条应 PASS(Task 4 的池已按约束写好)。若其余三条有红,说明 Task 4 的池写错了,回去修池 —— **不要改闸**。

- [ ] **Step 3: 生成 golden 并人工审阅**

```bash
cd backend/python && python -c "
import sys; sys.path.insert(0,'.')
from tests.test_llm_router_chains import _render_chains
open('tests/golden/llm_router_chains.txt','w',encoding='utf-8',newline='\n').write(_render_chains())
"
cat tests/golden/llm_router_chains.txt
```

⚠️ `newline='\n'` 不能省。Windows 上 Python 默认写入会把整个文件转成 CRLF(2026-08-07 因此踩过一次)。

生成后**逐槽人工审阅**,确认到期日自上而下非降序、文本槽末位是 `zhipu/glm-4.5-air`、VL 槽只有 2 行。

- [ ] **Step 4: 运行确认四条全绿**

```bash
cd backend/python && python -m pytest tests/test_llm_router_chains.py -v
```

Expected: 4 组用例全部 PASS

- [ ] **Step 5: 变异验证四条闸(每条都必须真红)**

依次做以下四个变异,每次跑 `python -m pytest tests/test_llm_router_chains.py -v`,确认**指定的那条**用例 FAIL,然后改回:

1. 把 `_SAFE_MODELS[("aliyun_a","qwen3.8-max")]` 改成 `datetime.date(2026,8,1)` → `test_chains_match_human_reviewed_golden` 必红(它会跳到链头)
2. 把 `_TEXT_TAIL` **两条都**注释掉(`("tencent","minimax-m2.7")` 与 `("zhipu","glm-4.5-air")`)→ `test_every_text_slot_has_a_floor` 必红

   ⚠️ **只注释掉 `zhipu/glm-4.5-air` 一条是无效变异** —— Task 4 重写后 `_TEXT_TAIL` 有**两个**地板条目,删一个另一个还在,地板闸照常绿(只有 golden 快照会红)。这是本轮实测发现的:**「红了」不等于「我要验的那道闸红了」,而无效变异会让人误以为闸验过了**。变异必须打掉被守属性的**全部**支撑,不是其中一个。
3. 往 `_SLOT_POOLS[SLOT.CHAT]` 追加 `("aliyun_c","deepseek-r1")` → `test_interactive_pools_exclude_slow_models[SLOT.CHAT]` 必红
4. 往 `_SLOT_POOLS[SLOT.REVIEW]` 追加 `("aliyun_c","MiniMax-M2.5")` → `test_param_profile_constraints_are_respected` 必红

⚠️ 每个变异做完必须确认**红的是那条指定用例**,不是别的。红在别处 = 变异没打中,不算验证通过。

- [ ] **Step 6: 提交**

```bash
cd /c/Users/Steve/cretas-llm-router
git add backend/python/tests/test_llm_router_chains.py backend/python/tests/golden/llm_router_chains.txt
git status --short
git commit -m "test(llm-router): 四道承重闸 + 人审冻结的链 golden 快照

明确不写 assert chain == sorted(chain, key=_expiry_of) —— 链就是用这个 key
造出来的, 左右同源的恒真式, 一次都红不了。承重的是 golden 快照: 它的另一端
是人不是代码。

四道闸各配变异验证, 且要求红的必须是指定那条用例:
1. golden 快照      变异: 改一个到期日 → 链头变化
2. 文本槽地板非空    变异: 删 zhipu/glm-4.5-air (VL 显式豁免, 见 spec 9.1)
3. 交互池无慢模型    变异: 往 CHAT 池塞 deepseek-r1
4. 参数档位约束      变异: 往 REVIEW 池塞只能开思考的 MiniMax-M2.5

golden 写入强制 newline='\\n' —— Windows 上默认写入会把整个文件转 CRLF。
" -- backend/python/tests/test_llm_router_chains.py backend/python/tests/golden/llm_router_chains.txt
git show --name-only --format="%h %s" HEAD
```

---

## Task 6: 每日全量探针对账

治根因 B(注册表跟不上现实且无机制发现)。探针**必须复用 router 自己的参数层**,否则测的不是生产走的那条路 —— 2026-08-09 这一轮在这上面连错三次。

**Files:**
- Create: `backend/python/scripts/probe_llm_registry.py`
- Create: `backend/python/tests/test_probe_llm_registry.py`

**Interfaces:**
- Consumes: `llm_router._SAFE_MODELS`、`_SLOT_POOLS`、`_provider_config`、`_apply_slot_params`、`_expiry_of`、`SLOT`
- Produces: `classify_probe_result(status: int, body_text: str, content: str) -> str`(返回 `"ok"` / `"quota"` / `"empty"` / `"error"`)、`main() -> int`

- [ ] **Step 1: 写分类函数的失败测试**

创建 `backend/python/tests/test_probe_llm_registry.py`:

```python
"""
探针结果分类的闸。

分类必须区分「403 无额度」「200 但空内容」「其它错误」——
2026-08-09 那一轮正是把「200 空内容」读成可用, 才把 glm-5.2 写进单子。
"""
from scripts.probe_llm_registry import classify_probe_result


def test_non_empty_content_is_ok():
    assert classify_probe_result(200, "", "库存周转率是…") == "ok"


def test_http_200_with_empty_content_is_not_ok():
    """200 不等于可用。thinking 模型会把 token 全烧在 reasoning_content 上,
    content 返回空、finish_reason=length —— 长得像成功。"""
    assert classify_probe_result(200, "", "   ") == "empty"


def test_free_quota_exhausted_is_quota():
    # ⚠️ 报文必须用真实厂商格式。`_is_quota_exhausted` 的 403 分支只认
    # "FreeTierOnly" / "AllocationQuota" 子串 —— 写成人话版的
    # "Free quota exhausted" 会被判成 "error", 这条断言就成了假的。
    # (2026-08-09 prod 实测原文即 403 AllocationQuota.FreeTierOnly)
    assert classify_probe_result(403, '{"message":"AllocationQuota.FreeTierOnly"}', "") == "quota"


def test_tokenhub_401008_is_quota():
    assert classify_probe_result(402, '{"code":"401008"}', "") == "quota"


def test_zhipu_balance_message_is_quota():
    assert classify_probe_result(429, '{"code":"1113","message":"余额不足"}', "") == "quota"


def test_bad_request_is_error_not_quota():
    """阴性对照: 400 参数错误不是额度问题, 不能混进 quota 桶 ——
    否则一个参数 bug 会被读成'额度用完了'。"""
    assert classify_probe_result(400, '{"message":"InternalError"}', "") == "error"
```

- [ ] **Step 2: 运行确认红**

```bash
cd backend/python && python -m pytest tests/test_probe_llm_registry.py -v
```

Expected: 全部 FAIL(`ModuleNotFoundError: No module named 'scripts.probe_llm_registry'`)

- [ ] **Step 3: 实现探针脚本**

创建 `backend/python/scripts/probe_llm_registry.py`:

```python
#!/usr/bin/env python
"""
每日全量探针 —— 把 _SAFE_MODELS 与现实对账。

⚠️ 必须放在 backend/python/ 下。仓根 scripts/ 不在部署同步范围
   (2026-08-01 飞轮日报因此静默坏了 5 天)。

⚠️ 判据两条, 缺一不可:
   1. 走 router 自己的 _apply_slot_params —— 不能自己拼 payload。
      zhipu 关思考要 thinking:{type:disabled} 不是 enable_thinking;
      _THINKING_ONLY 模型根本不该收到 enable_thinking。手搓必然误判。
   2. 判「非空 content」不判 HTTP 200。200 + 空 content 长得像成功。

用法: python -m scripts.probe_llm_registry
退出码: 0 = 无差异; 1 = 有差异(供 cron 告警)
"""
import asyncio
import json
import sys
from typing import Dict, List, Tuple

import httpx

sys.path.insert(0, ".")

from common import llm_router as r  # noqa: E402

_PROMPT = "用一句话说明什么是库存周转率。"


def classify_probe_result(status: int, body_text: str, content: str) -> str:
    """把一次探针调用归成 ok / quota / empty / error 四类之一。"""
    if 200 <= status < 300:
        return "ok" if content.strip() else "empty"
    if r._is_quota_exhausted(status, body_text):
        return "quota"
    return "error"


def _slots_for(pair: Tuple[str, str]) -> List[r.SLOT]:
    """该条目出现在哪些槽的池里; 都没出现就用 REVIEW 档探一次。"""
    slots = [s for s, pool in r._SLOT_POOLS.items() if pair in pool]
    return slots or [r.SLOT.REVIEW]


async def _probe(client: httpx.AsyncClient, account: str, model: str,
                 slot: r.SLOT) -> Tuple[str, str]:
    base, key = r._provider_config(account)
    payload = r._apply_slot_params(slot, account, model, {
        "model": model,
        "messages": [{"role": "user", "content": _PROMPT}],
        "max_tokens": 200,
    })
    try:
        resp = await client.post(
            base.rstrip("/") + "/chat/completions",
            json=payload,
            headers={"Authorization": f"Bearer {key}"},
            timeout=90.0,
        )
    except Exception as exc:  # noqa: BLE001 — 网络层任何异常都算 error
        return "error", type(exc).__name__
    body = resp.text
    content = ""
    if 200 <= resp.status_code < 300:
        try:
            content = (json.loads(body)["choices"][0]["message"].get("content") or "")
        except Exception:  # noqa: BLE001
            content = ""
    return classify_probe_result(resp.status_code, body, content), f"{resp.status_code}"


async def _run() -> Dict[Tuple[str, str], Tuple[str, str]]:
    sem = asyncio.Semaphore(8)
    results: Dict[Tuple[str, str], Tuple[str, str]] = {}

    async with httpx.AsyncClient() as client:
        async def one(pair: Tuple[str, str]) -> None:
            async with sem:
                verdicts = [await _probe(client, pair[0], pair[1], s)
                            for s in _slots_for(pair)]
            # 任一槽拿到非空内容即算可用; 全不可用时取第一个判定作为原因。
            ok = next((v for v in verdicts if v[0] == "ok"), None)
            results[pair] = ok or verdicts[0]

        await asyncio.gather(*(one(p) for p in r._SAFE_MODELS))
    return results


def main() -> int:
    results = asyncio.run(_run())
    today = r._today()

    dead = sorted(f"{a}/{m}  ({v[0]} {v[1]})"
                  for (a, m), v in results.items() if v[0] != "ok")
    soon = sorted(f"{a}/{m}  {r._expiry_of(a, m)}"
                  for (a, m) in r._SAFE_MODELS
                  if (r._expiry_of(a, m) - today).days <= 7)

    print(f"[probe] {today} 共探 {len(results)} 个 (账号,模型)")
    print(f"\n⚠ 注册表说活、实测不可用 ({len(dead)}):")
    for line in dead:
        print(f"   {line}")
    print(f"\n⚠ 7 天内到期 ({len(soon)}):")
    for line in soon:
        print(f"   {line}")
    print("\nℹ 「实测可用但未登记」需先核对控制台余量再登记 —— 探针 200 但控制台")
    print("  无余量说明「用完即停」没覆盖它, 那个 200 可能是真在计费。本脚本")
    print("  不主动枚举未登记模型, 避免把可能计费的条目做成一键加入的清单。")

    return 1 if (dead or soon) else 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 4: 运行测试确认绿**

```bash
cd backend/python && python -m pytest tests/test_probe_llm_registry.py -v
```

Expected: 全部 PASS

- [ ] **Step 5: 真跑一次探针**

```bash
cd backend/python && set -a && . /path/to/local/.env && set +a && python -m scripts.probe_llm_registry
```

若本地无凭证,在服务器上跑:

```bash
ssh root@47.100.235.168 'cd /www/wwwroot/cretas/code/backend/python && set -a; . /www/wwwroot/cretas/.env.prod; set +a; venv-current/bin/python -m scripts.probe_llm_registry'
```

Expected:「注册表说活、实测不可用」为 **0 条**(Task 3 的注册表就是按实测写的);「7 天内到期」列出 08-13 那 14 个 + 08-20 两个。

⚠️ 若「实测不可用」非 0,说明 Task 3 的注册表与现实已经又漂移了 —— 按判据核对控制台后修注册表和 Task 3 的冻结表,**不要**改探针去迁就。

- [ ] **Step 6: 提交**

```bash
cd /c/Users/Steve/cretas-llm-router
git add backend/python/scripts/probe_llm_registry.py backend/python/tests/test_probe_llm_registry.py
git status --short
git commit -m "feat(llm-router): 每日全量探针对账注册表与现实

治根因: 注册表是手写的, 现实每天在变, 此前没有任何机制能发现二者脱节 ——
aliyun_b/qwen3.7-flash 死了三天没人知道, qwen3.8-max 有 100 万额度躺着
没人知道。

探针两条判据缺一不可: (1) 走 router 自己的 _apply_slot_params, 不自己拼
payload —— zhipu 关思考要 thinking:{type:disabled}, _THINKING_ONLY 模型
根本不该收到 enable_thinking, 手搓必然误判; (2) 判非空 content 不判
HTTP 200 —— 200 加空内容长得像成功。

刻意不枚举「实测可用但未登记」的模型: 探针 200 但控制台无余量恰恰说明
用完即停没覆盖它, 做成一键加入的清单等于诱导把可能计费的条目写进白名单。
" -- backend/python/scripts/probe_llm_registry.py backend/python/tests/test_probe_llm_registry.py
git show --name-only --format="%h %s" HEAD
```

---

## Task 7: 合入与生产验证

**Files:** 无代码改动

- [ ] **Step 1: 本地全量回归**

```bash
cd backend/python && python -m pytest tests/ -v
```

Expected: 全部 PASS。若有与本改动无关的既有失败,记录下来但不修(不扩大范围)。

- [ ] **Step 2: 确认 PR scope 干净**

```bash
cd /c/Users/Steve/cretas-llm-router
git diff origin/main...HEAD --stat
```

Expected: 只有 `backend/python/common/llm_router.py`、`backend/python/tests/*`、`backend/python/scripts/probe_llm_registry.py`、`docs/superpowers/{specs,plans}/2026-08-09-*`。若夹带其它 session 的文件 → worktree 没 off origin/main,用 cherry-pick 到干净 worktree 重做。

- [ ] **Step 3: 开 PR**

碰了 `backend/python` → 必须走 PR(不能 fastlane),CI 的 JPA gate 挂在 PR 上。

```bash
cd /c/Users/Steve/cretas-llm-router
git push -u origin codex/claude-llm-router-expiry-first
gh pr create --title "feat(llm-router): 链按免费额度到期日自动排序 + 注册表按实测重写" --body "见 docs/superpowers/specs/2026-08-09-llm-router-expiry-first-chain-design.md"
```

- [ ] **Step 4: merge 后从 main 部署**

⛔ 绝不从 feature 分支部署 prod(`.claude/rules/worktree-and-main-only-deploy.md`)。

```bash
cd /c/Users/Steve/my-prototype-logistics
git checkout main && git pull origin main
./scripts/deploy/deploy-smartbi-python.sh --env prod
```

- [ ] **Step 5: 核对运行中的代码确含本次改动**

⚠️ 「语法 OK」不等于「是我的文件」—— 要用能区分版本的标记(2026-08-09 memory)。

```bash
ssh root@47.100.235.168 'grep -c "_build_chain" /www/wwwroot/cretas/code/backend/python/common/llm_router.py'
```

Expected: ≥ 2(定义 + 调用)。为 0 说明部署没同步到,不要继续往下验。

- [ ] **Step 6: 生产验证三条(缺一不可)**

```bash
# ① All providers exhausted 归零(部署后 10 分钟起算)
ssh root@47.100.235.168 'grep "All providers exhausted" /www/wwwroot/cretas/python-prod.log | tail -5'

# ② 实际命中的模型属于 08-13 那批 —— 这是"物尽其用"真生效的正向证据
ssh root@47.100.235.168 'grep "OK via" /www/wwwroot/cretas/python-prod.log | tail -40 | grep -oE "OK via [a-z_]+/[A-Za-z0-9._-]+" | sort | uniq -c | sort -rn'

# ③ 死条目不再空转: quota-exhausted 的 skip 日志应显著减少
ssh root@47.100.235.168 'grep -c "quota-exhausted, re-probe after TTL" /www/wwwroot/cretas/python-prod.log'
```

②「命中的是 08-13 那批」是本次的核心验收 —— 只看「没报错」不够,那与"链没生效但地板顶住了"无法区分。

- [ ] **Step 7: 跑 85 条回归电池**

基线 **80/85**。低于基线不算通过,需按 spec §8.3 把掉分链头降到尾部后重跑。

- [ ] **Step 8: 更新 memory**

在 `C:\Users\Steve\.claude\projects\C--Users-Steve-my-prototype-logistics\memory\` 记一条,钩子是「注册表手写、现实每天变、没有机制发现二者脱节 —— 而 `_expiry_of()` 这个能自动排序的函数在仓里躺了很久从没被调用过」,并记下本轮探针连错三次的形状(`max_tokens=1` / HTTP 200 / 手搓 `enable_thinking`)。

---

## Self-Review

**Spec 覆盖检查:**

| spec 章节 | 对应任务 |
|---|---|
| §5.1 `_SAFE_MODELS` 重写 | Task 3 |
| §5.2 `_SLOT_POOLS` | Task 4 |
| §5.3 `_build_chain` | Task 4 |
| §5.4 `_is_quota_exhausted` 补分支 | Task 2 |
| §5.5 每日探针 job | Task 6 |
| §7 `_MINIMAL_SAFE_SET` 同步更新 | Task 3 Step 4 |
| §8.2 闸 1 golden 快照 | Task 5 |
| §8.2 闸 2 地板非空(VL 豁免) | Task 5 |
| §8.2 闸 3 交互槽无慢模型 | Task 5 |
| §8.2 闸 4 池 ⊆ 注册表 | 已存在于 `tests/test_llm_router_fallback.py:54-58`,Task 3 Step 5 维护 |
| §8.2 闸 5 参数档位约束 | Task 5 |
| §8.2 闸 6 审计日期未过期 | Task 3 Step 1 |
| §8.3 上线验证三条 | Task 7 Step 6-7 |
| §9.1 VL 接受空链 | Task 4 `_NO_TEXT_TAIL_SLOTS` + Task 5 豁免 |
| §9.2 ark/tencent 待补齐 | Task 3/4 保留 provider 配置,注释写明加回方式 |
| §10 部署前置 13 行 | 「前置」章节 |

额外发现并纳入:`_MINIMAL_SAFE_SET` 的 `tencent/hy-mt2-pro` 不在 `_SAFE_MODELS` → Task 1。

**类型一致性:** `_build_chain(slot: SLOT) -> List[Tuple[str, str]]` 在 Task 4 定义、Task 5 与 Task 6 引用,签名一致。`classify_probe_result(status, body_text, content) -> str` 在 Task 6 Step 1 的测试与 Step 3 的实现中签名一致,四个返回值 `ok/quota/empty/error` 两处一致。`_NO_TEXT_TAIL_SLOTS` 在 Task 4 定义、Task 5 引用。`_SLOW_MODELS` / `_THINKING_OFF_ONLY` / `_REASONING_ONLY` 均为 `frozenset[str]`(存模型名,不存 pair),Task 5 的闸按 `p[1] in ...` 和 `model in ...` 取用,一致。
