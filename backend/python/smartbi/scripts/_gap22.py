# -*- coding: utf-8 -*-
"""22 项缺口的**当前状态**逐条实测。

⛔ 不凭印象、不从注释推断。每一项配一条机械判据 + 一条阳性对照
（会读出「全都没有」的仪器必须有对照，否则分不清真的没有 / 仪器坏了）。

判据分两类：
  AST  —— 「这个东西有没有被【调用】」，⛔ 不 grep 文本（docstring 会混进来）
  RUN  —— 「跑一次让它自己说」，用于行为类

输出三态：  ✅ 做了 / 🔴 没做 / ⚠️ 判不了(仪器不足, 单独列)
"""
from __future__ import annotations

import ast
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, ROOT)

R = os.path.join(ROOT, "smartbi", "gold", "restaurant")


def _src(name: str) -> str:
    with open(os.path.join(R, name), "r", encoding="utf-8", newline="") as fh:
        return fh.read()


def _tree(name: str):
    return ast.parse(_src(name))


def calls_of(name: str, func: str) -> int:
    """AST 数**调用**次数 —— ⛔ 不数文本出现次数（docstring/注释会混进来）。"""
    n = 0
    for node in ast.walk(_tree(name)):
        if isinstance(node, ast.Call):
            f = node.func
            if isinstance(f, ast.Name) and f.id == func:
                n += 1
            elif isinstance(f, ast.Attribute) and f.attr == func:
                n += 1
    return n


def defines(name: str, func: str) -> bool:
    return any(isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name == func
               for n in ast.walk(_tree(name)))


def imports_module(name: str, needle: str) -> bool:
    for node in ast.walk(_tree(name)):
        if isinstance(node, ast.ImportFrom) and needle in (node.module or ""):
            return True
        if isinstance(node, ast.Import):
            for a in node.names:
                if needle in a.name:
                    return True
    return False


def has_param(name: str, func: str, param: str) -> bool:
    for node in ast.walk(_tree(name)):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == func:
            args = node.args
            names = ([a.arg for a in args.args] + [a.arg for a in args.kwonlyargs]
                     + [a.arg for a in args.posonlyargs])
            return param in names
    return False


FILES = ("restaurant_intent.py", "restaurant_intent_service.py",
         "restaurant_ops_router.py")

ROWS = []


def rec(no, title, ok, evidence):
    ROWS.append((no, title, ok, evidence))


# ── 阳性对照：仪器本身活着吗 ────────────────────────────────────────────────
CONTROL = []
CONTROL.append(("AST 能数到调用",
                calls_of("restaurant_ops_router.py", "_markdown_table") > 0,
                "_markdown_table 调用 %d 次" % calls_of("restaurant_ops_router.py",
                                                        "_markdown_table")))
CONTROL.append(("AST 能认出 def",
                defines("restaurant_ops_router.py", "resolve_channel_mix"),
                "resolve_channel_mix 已定义"))
CONTROL.append(("AST 能认出形参",
                has_param("restaurant_ops_router.py", "resolve_daypart_performance",
                          "dimensions"),
                "resolve_daypart_performance(dimensions=…) 存在"))
CONTROL.append(("阴性对照：不存在的名字数出 0",
                calls_of("restaurant_ops_router.py", "__不存在的函数__") == 0,
                "拿一个必然不存在的名字，数出 0"))

# ── 1 菜单目录接进核对 ─────────────────────────────────────────────────────
_cat_calls = sum(calls_of(f, "_catalogue_says_not_a_dish") for f in FILES)
_where = {f: calls_of(f, "_catalogue_says_not_a_dish") for f in FILES}
rec(1, "菜单目录接进 dish 槽位核对",
    _where.get("restaurant_intent.py", 0) > 0,
    "_catalogue_says_not_a_dish 调用点: %s（总 %d）" % (_where, _cat_calls))

# ── 2 绝对日期核对「原话里有没有」 ────────────────────────────────────────
# 🔴 2026-08-18 先重测再判(owner 判据: 单轮观测不算数, 复现不了就登记不做)。
#    同一句原话「前天和昨天哪天好」直接调 T3(`_t3_llm_parse`, prefer_high_
#    accuracy=True, MOCK_REST, temperature=0)跑 10 轮(两批各 5), 每轮清
#    4 个缓存(semantic_plan/route/tenant_gate/promoted_routes)。
#    判据(逐字用 owner 的话): LLM 吐出的日期, 在原话里出现过吗? 没出现过而
#    它自己编了一个 ⇒ 复现。10 轮读数: **0/10** 命中 prompt 里写死的示例
#    日期(2026-06-03/2026-06-18)。⇒ **单轮观测未复现, 登记不做**, 详见
#    docs/decisions/2026-08-18-缺口2-绝对日期核对-复测登记.md。
#    ⚠️ 副产品(不在本条判据范围内, 单独记在那份文档里): 10 轮里 2 轮
#    (20%) LLM 把 type 判成 "absolute" 却填了 "前天"/"昨天" 这种非 ISO
#    日期的字面词——被 `_parse_t3_time_range` 的 ISO 正则挡下(不会污染成
#    错误日期, 但会让那一轮解不出时间窗)。这是「类型选择不稳定」, 不是
#    「编日期」, 与 #2 要判的缺口是两件事, 不改这里的判据。
_d = any(defines(f, n) for f in FILES
         for n in ("_absolute_date_in_user_wording", "_date_backed_by_user_wording"))
rec(2, "绝对日期核对「原话里有没有」", _d,
    "找不到 _absolute_date_in_user_wording / _date_backed_by_user_wording"
    if not _d else "已定义")

# ── 3 实体向量检索（门店/菜品模糊匹配） ───────────────────────────────────
_ent = any(imports_module(f, "entity_resolution") for f in FILES)
rec(3, "实体向量检索接进读路径", _ent,
    "三个读路径文件里 import entity_resolution = %s" % _ent)

# ── 4 菜单 + 能力表走检索进 prompt ────────────────────────────────────────
_p = _src("restaurant_intent.py")
_full_store = "只能从这里选择，禁止编造" in _p
rec(4, "菜单/能力表走检索（⛔ 不全量塞 prompt）", not _full_store,
    "prompt 里仍有「只能从这里选择，禁止编造」= 全量塞门店" if _full_store
    else "已改成检索")

# ── 5 能力表登记「为什么不能」三态 ────────────────────────────────────────
# 🔴 2026-08-18 订正: 原判据找那三句**字面文案**，全判 0 ——
#    只读复测线用更硬的判据（读完整函数体 + 追跨调用点数据流）证明是**假阴性**:
#    结构上有 4 条互不相同的拒答路径，各自独立触发条件、独立措辞来源。
#    原三句字面文案在三个文件里 grep 为 0（**真的不在了**，是被替换掉的旧文案）。
#    ⇒ 判据换成「这四条路径各自存在」，⛔ 不再找措辞。
_five_paths = {
    "无维度 _dimension_gap_advice": any(
        defines(f, "_dimension_gap_advice") for f in FILES),
    "方法未实现 capability_clarification_question": any(
        defines(f, "capability_clarification_question") for f in FILES),
    "无基准 asks_reasonableness": "asks_reasonableness" in _src(
        "restaurant_ops_router.py"),
    "无数据 _UNSUPPORTED_REQUIREMENT_LABELS": (
        "_UNSUPPORTED_REQUIREMENT_LABELS" in _src("restaurant_intent.py")),
}
rec(5, "能力表「为什么不能」多态", all(_five_paths.values()),
    "四条独立拒答路径: %s" % {k: v for k, v in _five_paths.items()})

# ── 6 冲突批量收集 + 一次性重试 ───────────────────────────────────────────
_retry = any(defines(f, n) for f in FILES
             for n in ("_collect_all_conflicts", "_retry_with_conflicts"))
rec(6, "冲突批量收集 + 一次性重试", _retry,
    "找不到 _collect_all_conflicts / _retry_with_conflicts" if not _retry else "已定义")

# ── 7 prompt 加「信息不足必须 clarify + 列候选维度」 ──────────────────────
_clar = ("信息不足" in _p and "clarify" in _p.lower())
rec(7, "prompt 要求信息不足必须 clarify", _clar,
    "prompt 里同时有「信息不足」和 clarify = %s" % _clar)

# ── 8 反问一次问全 + 按钮 + 拼完整句重走 ─────────────────────────────────
# 🔴 2026-08-18 订正（假阴性）: 它**不是独立函数**，是 `_parse_continuation`
#    里的局部变量 concatenated（原句 + 答案片段拼接），随后被喂给
#    T1 关键词 / T2 向量 / T3 LLM 三层入口 —— 那正是「整句重走」。
#    ⇒ 判据换成数那个变量的引用数。
_rewalk_refs = sum(
    1 for n in ast.walk(_tree("restaurant_intent.py"))
    if isinstance(n, ast.Name) and n.id == "concatenated")
rec(8, "反问回答后拼完整句整句重走", _rewalk_refs >= 6,
    "`concatenated`（原句+答案片段）被引用 %d 次" % _rewalk_refs)

# ── 9 dimensions 传进执行器 ───────────────────────────────────────────────
_resolvers = [n.name for n in ast.walk(_tree("restaurant_ops_router.py"))
              if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
              and n.name.startswith("resolve_")]
_with_dims = [r for r in _resolvers
              if has_param("restaurant_ops_router.py", r, "dimensions")]
rec(9, "dimensions 传进执行器", len(_with_dims) > 0,
    "%d/%d 个 resolver 收 dimensions: %s" % (
        len(_with_dims), len(_resolvers), sorted(_with_dims)))

# ── 11 飞轮 B：拒答队列 ───────────────────────────────────────────────────
# 🔴 2026-08-18: 飞轮 B 已上线（PR #2834 模块 + #2838 SSE 接线）。
#    它在**自己的模块** refusal_queue.py 里，不在读路径三个文件里 ——
#    原判据只扫那三个文件 ⇒ 假阴性。
#    ⇒ 判据换成「模块存在 + 两个生产入口都接上了」（⛔ 数调用点，不数定义）。
_fb_mod = os.path.exists(os.path.join(R, "refusal_queue.py"))
_fb_wired = {}
for _api in ("gold_reads.py", "chat.py"):
    _rel = os.path.join(ROOT, "smartbi", "api", _api)
    try:
        with open(_rel, "r", encoding="utf-8", newline="") as fh:
            _t = ast.parse(fh.read())
        _fb_wired[_api] = sum(
            1 for n in ast.walk(_t)
            if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
            and n.func.id == "record_refusal")
    except OSError:
        _fb_wired[_api] = -1
rec(11, "飞轮 B（拒答队列 分类+频次）",
    _fb_mod and all(v > 0 for v in _fb_wired.values()),
    "模块存在=%s；record_refusal 调用点 %s" % (_fb_mod, _fb_wired))

# ── 13 实体命中后替换成库里规范名 + 抬头显示全名 ─────────────────────────
# 🔴 2026-08-18 订正（假阴性）: 它叫 `_canonicalize_store_mention`
#    （router，先精确查 dim_store 再模糊 LIKE，返回**库里的 name**），
#    返回值流入 store_name -> target_label -> 答案标题。
#    ⚠️ 只覆盖**门店**；菜品侧只有布尔判断 `_catalogue_says_not_a_dish`，
#       不返回规范全名 —— 那一半仍是缺口，读数里说清楚。
_norm_store = any(defines(f, "_canonicalize_store_mention") for f in FILES)
_norm_dish = any(
    defines(f, n) for f in FILES
    for n in ("_canonicalize_dish_mention", "_normalize_dish_to_catalogue"))
rec(13, "实体归一成库里规范名", _norm_store,
    "门店=%s（_canonicalize_store_mention）/ 菜品=%s ⚠️ 菜品侧仍是缺口"
    % (_norm_store, _norm_dish))

# ── 14 部分能做时给部分结果 ───────────────────────────────────────────────
# 🔴 2026-08-18 订正（假阴性）: 它叫 `partial_coverage_answer`，
#    定义在 capability_answer.py（不在读路径三文件里），
#    唯一调用点在 restaurant_intent_service.py。
#    ⇒ 判据 = 定义存在 **且** 真的被调用（⛔ 数调用点，形态 B）。
try:
    with open(os.path.join(R, "capability_answer.py"), "r",
              encoding="utf-8", newline="") as fh:
        _cap_defined = "def partial_coverage_answer" in fh.read()
except OSError:
    _cap_defined = False
_partial_calls = sum(calls_of(f, "partial_coverage_answer") for f in FILES)
rec(14, "部分能做时给部分结果", _cap_defined and _partial_calls > 0,
    "partial_coverage_answer 定义=%s / 调用点 %d 处"
    % (_cap_defined, _partial_calls))

# ── 19 计算式指代：先算再核对 ─────────────────────────────────────────────
# 🔴 2026-08-18 订正（假阴性）: 它**不在读路径三文件里**，
#    也不是「两段执行」那个形状 —— 归因线给了四步实测证据:
#      ① plan_dimensions 识别指代 + 维度
#      ② compute_store_attribution 先算出 laggard
#      ③ FactReconciler + 接地闸 在第 7 步之前核对
#      ④ LLM 拿含 laggard 的 FactBook 写决策分析
#    反证也在: 维度没确定时 attribution is False，不会硬算。
#    ⇒ 判据换成扫 synthesis_engine / factbook 里那两个函数。
_calc_fns = {}
for _f in ("factbook.py", "synthesis_engine.py"):
    _rel = os.path.join(ROOT, "smartbi", "agent", _f)
    try:
        with open(_rel, "r", encoding="utf-8", newline="") as fh:
            _txt = fh.read()
        _calc_fns[_f] = [
            n for n in ("compute_store_attribution", "plan_dimensions")
            if ("def " + n) in _txt or (n + "(") in _txt
        ]
    except OSError:
        _calc_fns[_f] = []
rec(19, "计算式指代先算再核对", any(_calc_fns.values()),
    "先算再核对的两半: %s（⚠️ 不是两段执行，是同一趟里先算出 laggard 再核对）"
    % _calc_fns)

# ── 20 答案契约认「因权限而缺」一态 ───────────────────────────────────────
try:
    ac = open(os.path.join(R, "answer_contract.py"), "r",
              encoding="utf-8", newline="").read()
except OSError:
    ac = ""
_perm = ("权限" in ac) or ("price_view" in ac) or ("can_see_money" in ac)
rec(20, "答案契约认「因权限而缺」", _perm,
    "answer_contract.py 里提到权限/价格视角 = %s（文件 %d 字节）" % (_perm, len(ac)))

# ── 10 纠正记账 + 审 Top-50 + 补 feedback/embedding/history 三列 ──────────
# 🔴 2026-08-18 上线（PR #2847）: 生产餐饮流量 100% 走 `log_template_hit`，
#    而 `log_fallback`（唯一原生补两列的写法）**从未被餐饮路径调用过** ——
#    那是结构性结论，不是「没实现」。
#    ⇒ 判据 = 补值那两半都接上了（⛔ 数调用点，不数定义）。
_fa = {}
_fa_log = os.path.join(ROOT, "smartbi", "services", "llm_fallback_logger.py")
try:
    with open(_fa_log, "r", encoding="utf-8", newline="") as fh:
        _fa_src = fh.read()
    _fa["logger 里有 update_history"] = "def update_history" in _fa_src
    _fa["logger 里有 run_capture_with_history"] = (
        "def run_capture_with_history" in _fa_src)
except OSError:
    _fa["logger 读不到"] = False
_fa["service 真的调了 run_capture_with_history"] = (
    calls_of("restaurant_intent_service.py", "run_capture_with_history") > 0)
rec(10, "飞轮 A 输出端接上（三列值 + 晋升积压告警）", all(_fa.values()),
    "%s；service 调用点 %d 处"
    % (_fa, calls_of("restaurant_intent_service.py", "run_capture_with_history")))

# ── 12 拒答原因分「域外」一类，⛔ 不混进飞轮 B ────────────────────────────
# ⇒ 判据 = 飞轮 B 的聚合**在 SQL 里**排除域外（一处判定，⛔ 不 Python 侧再滤）
try:
    with open(os.path.join(R, "refusal_queue.py"), "r",
              encoding="utf-8", newline="") as fh:
        _rq = fh.read()
    _ood = "OUT_OF_DOMAIN" in _rq
except OSError:
    _rq, _ood = "", False
rec(12, "拒答原因分「域外」一类", _ood,
    "refusal_queue.py 里认 OUT_OF_DOMAIN = %s" % _ood)

# ── 15 老板的否定写进反馈通道 ─────────────────────────────────────────────
# 🔴 2026-08-18 二次独立实测（不靠端点存在打勾, 真跑了两次端到端往返）:
#    ① 另一条线 2026-08-18 用真实 JWT + POST 打 DEMO_REST 租户
#       (docs/decisions/2026-08-18-缺口15-老板的否定写进反馈通道-实测登记.md,
#       PR #2853), user_feedback IS NOT NULL 0→1 (id=28146)。
#    ② 本条独立复测同一条链路, 换 MOCK_REST 租户(现场签 JWT, 真 POST 到
#       prod :8083, 非 mock): RLS 确认 relrowsecurity=True, 会话级
#       set_config('app.factory_id','MOCK_REST',false) 后 current_setting
#       核对生效, BEFORE user_feedback_not_null(MOCK_REST)=0, HTTP 200,
#       AFTER=1(id=28465), Δ=+1, 落库字段与请求体逐一对应。
#    ⇒ **机制没坏, 两次独立调用都写成功** —— 但这只证明「能写」, 不证明
#      「有人调」。RESET GUC 读全表(跨租户): user_feedback IS NOT NULL
#      的行**全库只有这 2 条**, 且 query 字段都带 PROBE 标记, 均为诊断探针
#      写入, 无一条是自然使用。同一时间窗口 prod 访问日志:
#      /restaurant/feedback 命中 0 次, 同窗口 /tiered-answer 命中 312 次
#      (对照)。⇒ 真实成因是**通道在、无人调**（owner 给的两个分支的第二种
#      措辞), 不是「机制在没接上」。端点文件存在不再是判据本身，只作为
#      背景证据的一部分保留。
_fb_ep = os.path.exists(os.path.join(ROOT, "smartbi", "api",
                                     "restaurant_feedback.py"))
rec(15, "否定写进反馈通道（真实调用证据, 非端点存在）", False,
    "端点存在=%s 且机制两次独立端到端验证可写(DEMO_REST id=28146→MOCK_REST "
    "id=28465, 详见脚本注释); 但全库 user_feedback IS NOT NULL 仅 2 行且均为"
    "诊断探针标记, 同窗口访问日志 /feedback=0 次 vs /tiered-answer=312 次 "
    "⇒ **通道在、无人调**（0 次真实用户调用）" % _fb_ep)

# ── 16 实体模糊命中走确认式反问 ───────────────────────────────────────────
_amb = any(defines(f, n) for f in FILES
           for n in ("partial_store_alias_question", "_ambiguous_entity_question",
                     "_confirm_entity_question"))
rec(16, "实体模糊命中走确认式反问", _amb,
    "找不到 partial_store_alias_question / _ambiguous_entity_question"
    if not _amb else "已定义")

# ── 17 空结果只报「最后记录时间 + 请排查」 ───────────────────────────────
# 🔴 2026-08-18 上线（PR #2842）: 18 个 resolver 里 14 个有 no-data 分支，
#    只改了 3 个（同一张 agg_daily 表、同一种失败语义），其余逐条登记「不适用」。
_ew = defines("restaurant_ops_router.py", "_empty_window_last_record_date")
_ew_calls = calls_of("restaurant_ops_router.py", "_empty_window_last_record_date")
rec(17, "空结果只报最后记录时间", _ew and _ew_calls >= 3,
    "_empty_window_last_record_date 定义=%s / 调用点 %d 处（期望 ≥3）"
    % (_ew, _ew_calls))

# ── 18 归因分层下钻（七步链）─────────────────────────────────────────────
# 🟡 2026-08-18 只做了第 ② 步（量价分解接上是非决策问句，PR #2849）。
#    ⚠️ 第 ⑤ 步（反问现实事件）**判据设计完成但裁定不上线** ——
#       prod 上没有能触发的样本，⛔ 不为不复现的形状建检测器。
_attr_wired = False
try:
    with open(os.path.join(ROOT, "smartbi", "agent", "synthesis_engine.py"),
              "r", encoding="utf-8", newline="") as fh:
        _se = fh.read()
    _attr_wired = "_is_yes_no_decision" in _se
except OSError:
    pass
rec(18, "归因七步链（⚠️ 只做了第②步）", _attr_wired,
    "是非决策问句接上量价分解 = %s ⚠️ 第③⑤⑥⑦步未做，见归因设计卡" % _attr_wired)

# ── 21 不支持的指标给最接近替代 ───────────────────────────────────────────
# 🔴 2026-08-18 上线（PR #2841）: 「接近」不手写 —— 声明的是「这个量在定义式里
#    由哪几个已登记指标构成」，能不能给由 capability_answer 逐租户查库回答。
#    7 项里 2 项给替代、5 项实测算不出来就不给。
try:
    with open(os.path.join(R, "capability_answer.py"), "r",
              encoding="utf-8", newline="") as fh:
        _ca = fh.read()
    _alt = "眼下最接近的是" in _ca
except OSError:
    _alt = False
rec(21, "不支持的指标给最接近替代", _alt,
    "capability_answer 里有「眼下最接近的是」= %s" % _alt)


# ── 22 反问最多两轮 + 时间给按钮 ─────────────────────────────────────────
_btn = any(("clarification_options" in _src(f)) for f in FILES)
_two = any(("反问" in _src(f) and "两轮" in _src(f)) for f in FILES)
rec(22, "反问最多两轮 + 时间给按钮", _btn and _two,
    "clarification_options 存在=%s / 「两轮」上限存在=%s" % (_btn, _two))


def main() -> int:
    print("=" * 78)
    print("阳性/阴性对照（仪器活着吗）")
    print("=" * 78)
    bad = 0
    for name, ok, ev in CONTROL:
        print("  %s %-28s %s" % ("✅" if ok else "🔴", name, ev))
        if not ok:
            bad += 1
    if bad:
        print("\nrc=2 仪器对照没过 —— 本次读数作废")
        return 2

    print()
    print("=" * 78)
    print("22 项缺口 · 逐条实测（只覆盖能机械判定的那些）")
    print("=" * 78)
    done = 0
    for no, title, ok, ev in sorted(ROWS):
        print("  %2d %s %-32s %s" % (no, "✅" if ok else "🔴", title, ev))
        done += bool(ok)
    print("\n  机械判定覆盖 %d 项，其中 ✅ %d / 🔴 %d" % (len(ROWS), done, len(ROWS) - done))
    print("\n  ⚠️ 未覆盖（判不了，需要跑行为或人读）:")
    covered = {r[0] for r in ROWS}
    for no in range(1, 23):
        if no not in covered:
            print("      %2d" % no)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
