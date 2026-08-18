"""离线跑生产代码的四件套 —— **所有探针第一行 import 它**。

    from smartbi.scripts._probe_bootstrap import bootstrap_probe
    ctx = bootstrap_probe("MOCK_REST")     # ← 必须在 import 生产模块**之前**
    pool = await ctx.pool()

## 为什么要有这个模块（2026-08-12 建立的直接原因）

配方本身不是新的 —— 它写在两个脚本的**头部注释里**，带着实测数字：

    restaurant_capability_audit.py 头部:
      🔴 离线跑生产代码必须配齐三样, 否则会**造出看起来很真的假缺陷**
         (2026-07-30 一晚踩了三次, 其中一次让经营诊断整条报
          「餐饮执行链暂时不可用」)

    restaurant_adversarial_audit.py:48 的路径自举旁边:
      这里自己接上, 而不是靠「下次记得设环境变量」——
      **靠纪律的东西在最需要它那次就会失效**。

🔴 **2026-08-12 就是那一次。** 我写第三个探针（晋升翻转集合）时没有想起去抄，
   于是 40 条里 34 条报「餐饮执行链暂时不可用」，我据此写了一份
   「③ 执行失败 34」的报告 —— 而对照实验证明**当前规则侧一样跑不动**，
   那个 34 测的是「我的进程起不来执行链」，不是「存量计划不可回放」。

   配方以**复制粘贴在两个脚本头部**的形式存在时，写第三个就要重新记得一次。
   那段注释自己写的那句话，说的就是它自己。所以抽成模块：
   **不是靠记得，是靠 import 不进来就跑不了。**

## 五件套（缺一件都会造出看起来很真的假缺陷）

1. **路径自举**（两条 `sys.path`，第二条 `smartbi` 才是关键）
   `services` 实际在 `smartbi/services/`。不加它，`from services import ...`
   抛 `No module named 'services'`，被 `except Exception` 包成
   「餐饮执行链暂时不可用」—— 和真缺陷长得一模一样。
   来源：`cretas-restaurant-audit.service` 的
   `PYTHONPATH=.../backend/python:.../backend/python/smartbi`。

2. **租户 ContextVar** `set_factory_id(fid)`
   asyncpg 池每次 acquire 据此写 `app.factory_id`；不设的话 RLS 让每个
   tenant-scoped 语句失败，表现同样和真缺陷一样。生产由 `JWTAuthMiddleware` 设。

3. **角色 `restaurant_manager`，⛔ 不是 `owner`**
   `PRICE_VIEW_ROLES` **不含 owner**。用 owner 跑，金额全被脱敏成 `***` ——
   那是**正确的 RBAC**，不是能力缺失；填错角色会让评估看起来比实际差很多。

4. **prod 的库**：`POSTGRES_DB` 等取自**活服务进程**的 environ，
   ⛔ 不看 jar/配置文件里的默认值 —— 默认值恰恰指向测试库
   （本轮已因此把整轮读数量在 `smartbi_db` 上过一次）。
   本模块只负责**检查**并大声报出来，不替你去读 `/proc`（那要 root）。

5. **LLM key**（2026-08-18 加）：`LLM_*_API_KEY` 同样取自活服务进程的 environ。
   🔴 加这一条的理由和第 1 条一模一样，只是症状换了一句话：
   探针环境没有 key 时，`parse_restaurant_query` 走 fail-closed 分支，
   产品**正确地**回一句 40 字的

       「我现在暂时无法完整理解这句话，本次没有按关键词猜测，也没有执行查询。」

   ▎**它和「这个问句问倒了产品」长得一模一样。**
   2026-08-18 我拿它当成了 PR #2812 的回归，去查一个不存在的渲染缺陷；
   救回来的是阳性对照（第一问也是同一句 ⇒ 整条链没到执行）。

   ⚠️ 这一条**特别容易漏**，因为它不像 `POSTGRES_DB` 那样一漏就连不上库 ——
   数据库照连、目录照加载、答案照返回，只是**每一条都是那句拒答**。

## 用完必须跑的那条判据（写在这里，别写在使用者的记忆里）

    怀疑是缺陷时先 grep 生产日志：
      "No module named 'services'"   → 0 次 = 探针问题，不是线上问题
      "餐饮执行链暂时不可用"          → 与上面对照
    阳性对照用 restaurant-intent 的出现次数（2026-07-30 那次是 1767 次）。

`assert_not_a_probe_artifact()` 把这条判据做成可调用的，省得每次靠记得。
"""
from __future__ import annotations

import os
import sys
from typing import Optional

#: 餐饮价格角色。⛔ 不是 owner —— `PRICE_VIEW_ROLES` 不含它，
#: 用 owner 跑金额全被脱敏成 `***`，评估会假性变差。
PROBE_ROLE = "restaurant_manager"

#: 探针进程起不来执行链时，生产代码会把异常包成这句话给用户。
#: 探针里看到它 = 先怀疑自己，不是先怀疑线上。
EXECUTION_UNAVAILABLE_MARK = "餐饮执行链暂时不可用"

#: 路径没接上时真正的异常。它是 `EXECUTION_UNAVAILABLE_MARK` 的常见成因。
MISSING_SERVICES_MARK = "No module named 'services'"

#: LLM 编译不出来时，`_build_spec` 打在 spec 上的**来源标记**。
#:
#: ⛔ 判据用这个结构字段，**不用那句文案**（形态 C⁸：闸量结构不量文本）。
#:    那句「我现在暂时无法完整理解这句话…」在仓里已经有三份 ——
#:    产品 `restaurant_intent.py`、`restaurant_ai_eval._CANNOT_UNDERSTAND` 锚点、
#:    `restaurant_ai_eval._is_infrastructure_failure` 的字面串 ——
#:    文案一改，按文案写的判据就**静默失效**（那个文件自己的注释记着同型前科：
#:    4 条排除项因为文案改过而恒真，"一次都不可能红"）。
LLM_UNAVAILABLE_AUTHORITY = "llm_unavailable"


def llm_key_health() -> dict:
    """每个槽的链上，有几个账号**真的有 key**（⛔ 有值，不是「变量存在」）。

    返回 ``{槽名: (有key的账号, key为空的账号)}``。

    ⛔ **账号清单和 key 的取法都问 `common.llm_router`**，这里不写第二份
       （形态 D：同一个东西两份一定会漂 —— 而这份要漂的话，漂的方向恰好是
       「探针以为 LLM 活着」）。

    ⚠️ 「存在」不等于「有值」：`LLM_DEEPSEEK_API_KEY` 存在但为空这个洞，
       在生产上活了整整三周没人发现（见 `llm_router._note_empty_key` 的注释）。
       2026-08-18 我量它时用的是 ``sed 's/=.*/=<set>/'`` —— 对空值同样打 `<set>`，
       **我的仪器犯了那条日志正在警告的错**。所以这里 `.strip()` 之后再判。
    """
    try:
        from common.llm_router import SLOT_MODELS, _provider_config
    except Exception as exc:  # noqa: BLE001 - 拿不到就说拿不到，⛔ 不猜
        return {"<拿不到 llm_router>": ((), (str(exc)[:60],))}

    health = {}
    for slot, pairs in SLOT_MODELS.items():
        seen, live, empty = [], [], []
        for account, _model in pairs:
            if account in seen:
                continue
            seen.append(account)
            try:
                _base_url, api_key = _provider_config(account)
            except Exception:  # noqa: BLE001
                api_key = ""
            (live if (api_key or "").strip() else empty).append(account)
        health[getattr(slot, "value", str(slot))] = (tuple(live), tuple(empty))
    return health


def _format_llm_health(health: dict) -> tuple:
    """把 `llm_key_health()` 压成一行自检 + 一份**真的死了**的槽名。

    🔴 三态，⛔ 不是两态（本函数第一版就是两态，被自己的用例当场抓出来）:

        链上有账号 + 至少一个有 key   → 活着
        链上有账号 + 一个 key 都没有   → **死了**，就是这个函数要报的东西
        链上**没有账号**              → 合法状态，⛔ 不是缺 key

    实测：`vl` 槽是 `0/0` —— 它**根本没配账号**。把它算成「死槽」，
    就是每次跑探针都发一条永远为真的告警，而
    ▎一条天天误报的提示，最终结局是被人默认忽略（形态 E）——
    ▎那时它真正该拦的那次也拦不住了。

    ⚠️ 同一族：形态 A¹¹「算『缺了多少』之前，先问这里的空是不是合法状态」。
    """
    dead = tuple(sorted(
        slot for slot, (live, empty) in health.items() if empty and not live))
    parts = []
    for slot, (live, empty) in sorted(health.items()):
        total = len(live) + len(empty)
        # `0/0` 单独标出来 —— ⛔ 不让它和 `0/3` 长得一样
        parts.append("%s %d/%d%s" % (slot, len(live), total,
                                     "(未配账号)" if not total else ""))
    return "、".join(parts), dead


def _bootstrap_sys_path() -> tuple:
    """两条 `sys.path`。⛔ 第二条 `smartbi` 才是关键 —— `services` 在它下面。

    抄自 `scripts/restaurant_adversarial_audit.py:48`，那里带着实测：
    不加第二条时 `No module named 'services'` 会被包成
    「餐饮执行链暂时不可用」，而生产日志里那个异常出现 **0 次**。
    """
    # 本文件在 backend/python/smartbi/scripts/ → 上两级是 backend/python
    here = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    added = []
    for path in (here, os.path.join(here, "smartbi")):
        if path not in sys.path:
            sys.path.insert(0, path)
            added.append(path)
    return tuple(added)


class ProbeContext:
    """四件套装好之后的句柄。⛔ 不缓存 pool —— 由调用方决定生命周期。"""

    __slots__ = ("factory_id", "role", "added_paths", "db_name", "llm_dead_slots")

    def __init__(self, factory_id: str, role: str, added_paths: tuple, db_name: str,
                 llm_dead_slots: tuple = ()):
        self.factory_id = factory_id
        self.role = role
        self.added_paths = added_paths
        self.db_name = db_name
        #: 一个活账号都没有的槽。非空 ⇒ 这些槽上的读数是「探针没 key」，
        #: ⛔ 不是「产品答不上来」。
        self.llm_dead_slots = llm_dead_slots

    async def pool(self):
        from smartbi.config import get_pg_pool
        pool = await get_pg_pool()
        if pool is None:
            raise RuntimeError(
                "拿不到连接池 —— 检查 POSTGRES_* 是否取自**活服务进程**的 environ")
        return pool

    def __repr__(self) -> str:  # pragma: no cover - 调试用
        return (f"ProbeContext(factory={self.factory_id!r}, role={self.role!r}, "
                f"db={self.db_name!r})")


#: 生产代码在执行链上真正会 import 的顶层包。缺任何一个，异常都会被
#: `except Exception` 包成「餐饮执行链暂时不可用」——和真缺陷长得一模一样。
#:
#: 🔴 2026-08-12 加这一条的理由：原配方只写了「路径自举」，因为那两个探针本来就
#:    跑在**完整的** `backend/python` 树里。我在 `/tmp` 只 cp 了 `smartbi/` 和
#:    `common/`，于是修好 `services` 之后立刻露出 `No module named 'smartbi_compat'`
#:    —— **一层修完还有下一层**，而每一层的症状都是同一句话。
#:    与其一层层撞，不如一次把该在的都点名。
_REQUIRED_PACKAGES = ("smartbi", "common", "services", "smartbi_compat")


def assert_tree_is_complete() -> None:
    """一次性点名所有必需顶层包，缺哪个说哪个。

    ⛔ 不用 try/except 逐个撞 —— 逐个撞时每次只能看见一层，
       而每一层的用户可见症状都是「餐饮执行链暂时不可用」。
    """
    import importlib
    missing = []
    for name in _REQUIRED_PACKAGES:
        try:
            importlib.import_module(name)
        except Exception as exc:  # noqa: BLE001 - 这里就是要把失败收集起来
            missing.append(f"{name}({str(exc)[:40]})")
    if missing:
        raise RuntimeError(
            "探针跑在**残缺的树**上，缺: " + "、".join(missing)
            + "。生产代码 import 不到它们时，异常会被包成「"
            + EXECUTION_UNAVAILABLE_MARK + "」——**和真缺陷长得一模一样**。"
            + " 用部署树的完整副本跑，别只 cp 你以为要用的那几个包。")


def bootstrap_probe(factory_id: str, *, role: str = PROBE_ROLE) -> ProbeContext:
    """装好四件套。**必须在 import 生产模块之前调用**（路径要先接上）。

    ⚠️ 库名只**检查并报出来**，不替你改 —— 探针连错库是本仓反复踩的坑，
       但改它需要活服务进程的 environ（要 root），不该藏在一个 import 里。
    """
    added = _bootstrap_sys_path()

    assert_tree_is_complete()

    from smartbi.tenant_ctx import set_factory_id
    set_factory_id(factory_id)

    db_name = os.environ.get("POSTGRES_DB", "")
    if not db_name:
        print("⚠️ POSTGRES_DB 没设 —— 会落到配置默认值，而默认值指向**测试库**。",
              file=sys.stderr)
    elif not db_name.endswith("_prod_db"):
        print(f"⚠️ POSTGRES_DB={db_name!r} 看起来不是 prod 库。"
              f"量 prod 一律取自活服务进程的 /proc/<pid>/environ。", file=sys.stderr)

    # ── 第 5 件套：LLM key ────────────────────────────────────────────────
    # ⛔ 这一行**每次都打**，不是只在坏的时候打 —— 与「每条读数带来源标记」
    #    同一条纪律。2026-08-18 那次，如果日志里有这一行，诊断会短掉十几分钟。
    summary, dead = _format_llm_health(llm_key_health())
    print(f"[探针自检] LLM 各槽有 key 的账号数: {summary}", file=sys.stderr)
    if dead:
        print(
            "🔴 这些槽一个活账号都没有: " + "、".join(dead)
            + " —— 这些槽上拿到的**每一条**答案都会是那句"
            "「暂时无法完整理解这句话」的 fail-closed 拒答，"
            "而它和「产品被这个问句问倒了」**长得一模一样**。"
            " ⇒ 从活服务进程取 key 再跑:"
            " eval \"$(tr '\\0' '\\n' < /proc/<pid>/environ"
            " | grep -E '^LLM_' | sed 's/^/export /')\"",
            file=sys.stderr)

    return ProbeContext(factory_id, role, added, db_name, dead)


def assert_not_a_probe_artifact(answers, *, prod_log_hits: Optional[int] = None) -> None:
    """看到「餐饮执行链暂时不可用」时，先证明它不是探针自己造的。

    `answers` 是本轮拿到的答案文本序列。`prod_log_hits` 是你在**生产日志**里
    grep `MISSING_SERVICES_MARK` 的次数 —— `None` 表示还没查。

    ⛔ 这个函数不判「线上有没有缺陷」，它只拦住一件事：
       **拿探针造出来的失败去写报告**。2026-08-12 我就是那么写的，
       34 条「执行失败」全是这个来源。
    """
    hit = [a for a in (answers or []) if EXECUTION_UNAVAILABLE_MARK in (a or "")]
    if not hit:
        return
    if prod_log_hits == 0:
        raise AssertionError(
            f"{len(hit)} 条答案是「{EXECUTION_UNAVAILABLE_MARK}」，而生产日志里 "
            f"{MISSING_SERVICES_MARK!r} 出现 0 次 —— **这是探针问题不是线上问题**。"
            f"检查四件套是否装齐（路径自举/租户ContextVar/角色/prod 库）。")
    raise AssertionError(
        f"{len(hit)} 条答案是「{EXECUTION_UNAVAILABLE_MARK}」。"
        f"先 grep 生产日志 {MISSING_SERVICES_MARK!r}（阳性对照用 restaurant-intent 的次数），"
        f"0 次就是探针问题；把次数作为 prod_log_hits 传进来再调一次。")


def assert_not_an_llm_artifact(specs, *, llm_dead_slots=None) -> None:
    """看到 `llm_unavailable` 的 spec 时，先证明它不是**探针自己**没 key 造的。

    `specs` 是本轮拿到的 spec 序列（有 `.planner_authority` 的对象，或直接给字符串）。
    `llm_dead_slots` 传 `ctx.llm_dead_slots`；`None` 表示现场重新问一次。

    ⛔ 与 `assert_not_a_probe_artifact` 同一个职责：它不判「线上 LLM 有没有问题」，
       只拦住一件事 —— **拿探针自己缺 key 造出来的拒答去写报告**。
       2026-08-18 我差一步就那么写了（把它当成 PR #2812 的渲染回归）。

    ⚠️ 判据取 `planner_authority`，⛔ 不取文案：见 `LLM_UNAVAILABLE_AUTHORITY`。
    """
    hit = [s for s in (specs or [])
           if (s if isinstance(s, str) else getattr(s, "planner_authority", ""))
           == LLM_UNAVAILABLE_AUTHORITY]
    if not hit:
        return
    if llm_dead_slots is None:
        _summary, llm_dead_slots = _format_llm_health(llm_key_health())
    if llm_dead_slots:
        raise AssertionError(
            f"{len(hit)} 条 spec 是 {LLM_UNAVAILABLE_AUTHORITY}，"
            f"而这些槽在**本进程里**一个活账号都没有: {'、'.join(llm_dead_slots)}"
            f" —— **这是探针问题不是线上问题**。"
            f"从活服务进程的 /proc/<pid>/environ 取 LLM_* 再跑。")
    raise AssertionError(
        f"{len(hit)} 条 spec 是 {LLM_UNAVAILABLE_AUTHORITY}，而本进程每个槽都有活账号"
        f" ⇒ 不是缺 key。去查供应商侧（配额/限流/到期）："
        f"grep 'All providers exhausted' 看每个账号各自的原因。")
