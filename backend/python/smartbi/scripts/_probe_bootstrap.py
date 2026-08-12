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

## 四件套（缺一件都会造出看起来很真的假缺陷）

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

    __slots__ = ("factory_id", "role", "added_paths", "db_name")

    def __init__(self, factory_id: str, role: str, added_paths: tuple, db_name: str):
        self.factory_id = factory_id
        self.role = role
        self.added_paths = added_paths
        self.db_name = db_name

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

    return ProbeContext(factory_id, role, added, db_name)


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
