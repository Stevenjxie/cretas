"""追问按钮 —— T2 补数据 / T1 下钻。产出**落回既有的 `{label, question}` 契约**。

## 🔴 2026-08-14 二次裁定: 撤掉自建契约, 写进 `suggestedFollowups`

第一版新建了 `OpsAnswer.actions`(带 `payload`)。实测**三层都断**:

  · `OpsAnswer.actions` 没有任何消费者
  · T2 的 `payload.kind = "fill_dishes"` 没有 handler
  · 前端 `normalizeFollowUpActions` 读的是 `suggestedFollowups` / `followUpSuggestions`,
    而且它只认 `{label, question}`, **`payload` 原样丢弃**

⇒ owner: 契约数量 复用=1 份, 新增=2 份(其中一份没人读)。**撤掉新建的那份。**

现在的产出形状:

    {"type": "T2"|"T1", "label": …, "question": …, "anchor": …}

`type` 是**新增的一个字段**, 不是新契约 —— 前端按 `{label, question}` 照常渲染,
`type` 给后端排序/埋点用。⚠️ 前端**会丢掉它**(normalize 只留两个键), 所以
⛔ 不许把任何**行为**挂在 `type` 上, 它只是标注。

## 三条边界（owner 2026-08-14）

1. ⛔ **按钮不许说正文没说的话。** 按钮是入口，不是新内容。
   ⇒ 每个 action 带一个 `anchor`：它指向的**正文里那句话**。
     `assert_actions_anchored()` 逐条核 anchor 确实在正文里。
   ⚠️ `anchor` 也是内部字段, 送出去之前由 `to_followups()` 剥掉。
2. ⛔ **系统故障时不出按钮。** 抑制路径必须有**阳性对照**证明它真的会抑制 ——
   否则「故障时没有按钮」在「任何时候都没有按钮」时同样成立（恒真式）。
3. 上限 4 个是**前端** `.slice(0, 4)` 截的。⇒ **后端按优先级排好再送**，
   ⛔ 不能指望前端恰好切掉该切的。

## 优先级

卡上是 `T2 > T3 > T1 > T4`。⚠️ 那是**产品优先级**（哪个对店长更有用），
不是建造顺序。今天没有 T3/T4，所以实际是「补数据」在前、「下钻」在后。

## T1 的维度从哪来

**registry 反查**：`Metric.dimensions` 是这个指标能按哪些维度分组，
减去这次回答**已经用过的**那些，剩下的就是可下钻的。

⚠️ 这里有一处**不能推**的输入：「这次回答已经渲染了哪些维度」是那段回答代码
自己的性质，登记表不知道。⇒ 由调用方声明 `used_dimensions`，
**紧挨着渲染它的代码**写。候选集合仍然是反查出来的 —— ⛔ 那一半不许手写。

## T2 按钮为什么是「列出缺成本卡的菜」而不是「去补」

owner 2026-08-14: **「补」是他去别处做的事，我们只负责说清补什么。**
⇒ 按钮发的是一个**我们答得出来的问句**, 而不是一个我们没有 handler 的动作。
"""
from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional, Sequence, Tuple

from smartbi.gold.restaurant import metric_registry as _reg

logger = logging.getLogger(__name__)

#: 类型优先级。数字小的排前面。⛔ 前端只负责截断，排序在后端。
TYPE_PRIORITY: Dict[str, int] = {"T2": 0, "T3": 1, "T1": 2, "T4": 3}

#: 前端 `.slice(0, 4)` 的那个 4。⚠️ 后端也按它截 —— 送 8 个再让前端切 4 个,
#: 等于「优先级由切的位置决定」, 而那个位置在另一个仓里。
MAX_ACTIONS = 4

#: meta 里出现任何一个就**不出按钮**。⛔ 故障/拒答时给按钮 = 请他继续点一个
#: 本来就答不出的东西。
_SUPPRESS_META_KEYS = ("no_data", "rbac_masked", "unavailable", "clarification")

#: T2 按钮发出去的问句。⚠️ 它必须是**系统真答得出来的** ——
#: 见 `restaurant_ops_router._resolve_missing_cost_cards` 与它的关键词路由。
#: ⛔ 改这句话要同时改那两处, 否则按钮变成哑弹(点下去拒答)。
T2_FILL_QUESTION = "哪些菜没有成本卡"
T2_FILL_LABEL = "列出缺成本卡的菜"

#: 维度耗尽时正文里明说的那一句(owner 2026-08-14 裁定 3)。
#: ⛔ **不加样本量阈值** —— 那条挂账。这里只回答「还能不能往下钻」。
_EXHAUSTED_NOTE = "这个数已经按能拆的维度都看过了，再往下没有更细的口径。"


def _dim_phrase(dim_key: str) -> str:
    """T1 按钮的 label。⚠️ 它**必须逐字出现在正文**里 —— 见 `drilldown_note`。"""
    return f"按{_reg.DIMENSIONS[dim_key].label}"


def _metric_label(metric_key: str) -> str:
    entry = _reg.METRICS.get(metric_key) or _reg.DERIVED.get(metric_key)
    return getattr(entry, "label", metric_key) if entry else metric_key


def drilldown_note(metric_key: str, used_dimensions: Sequence[str],
                   resolver_code: Optional[str] = None) -> str:
    """正文里关于「还能往下钻吗」的那一句。**两种情况都说话。**

    🔴 为什么正文也要说, 而不是只放按钮(owner 2026-08-14):
      ① 判据「每个按钮的 label 都能在正文里找到」—— 按钮是**入口不是新内容**,
         `按品牌` 这个词得先在正文里出现过。
      ② **日结推送那种形态没有按钮, 只有正文。** 只放按钮 = 那条路上的人
         永远不知道还能拆。这与「正文里那句不撤」是同一条纪律。
      ③ 耗尽时明说(裁定 3), 免得他一直点到拒答才知道没了。
    """
    drillable = _drillable_dimensions(metric_key, used_dimensions, resolver_code)
    if not drillable:
        return _EXHAUSTED_NOTE
    phrases = "、".join(_dim_phrase(d) for d in drillable[:MAX_ACTIONS])
    return f"{_metric_label(metric_key)}还能{phrases}拆开看。"


def suppressed(meta: Optional[Dict[str, Any]]) -> bool:
    """这次回答该不该出按钮。

    ⚠️ 阳性对照在测试里: 先证明**正常时出得来**, 再证明故障时不出。
       否则「故障时没按钮」在「永远没按钮」时同样成立。
    """
    if not meta:
        return False
    return any(bool(meta.get(k)) for k in _SUPPRESS_META_KEYS)


def _answerable_dimensions(resolver_code: Optional[str]) -> Optional[set]:
    """这个 resolver **真答得出**的分组维度(登记表 key)。`None` = 不限制。

    🔴🔴 2026-08-14 实测: 只按 `Metric.dimensions` 反查会给出 4 颗**哑弹** ——
       「按品牌看毛利」「按渠道看毛利」「按城市看毛利」「按菜品类别看毛利」
       点下去全是拒答:「这次是想看某道菜、某家门店，还是全店汇总？」。
       因为 `RESTAURANT_OPS_GROSS_MARGIN` 声明的能力只有 `{dish, time}`。

    ⚠️ 登记表说「这个指标能按品牌分组」是**对的**(通用执行器真算得出来),
       但**问句路由不到那一层** —— 能算 ≠ 问得到。按钮要按「问得到」来给。
    ⛔ 我在这个文件里早写过「取并集会给出一个算不出来的组合(点下去就是拒答)」,
       却只把它用在派生量的两个输入上, 没用在这一层。**同一条判据漏了一处。**

    来源用 `_RESOLVER_DIMENSIONS`(管线词汇: dish/time) + `canonical_dimensions`
    (登记表 key → 管线名), 两者都是既有的 ——
    ⛔ 不在这里另写一份「哪个 resolver 能按什么分组」, 也不手写 product→dish。

    ⚠️ **方向别搞反**: `canonical_dimensions` 是 `登记表 key → 管线名`
       (`product→dish` / `date→time`), 不是反过来。拿它去「归一化 declared」
       是空操作(dish/time 本来就是管线名), 结果**收窄成空集**、一个按钮都不给。
       实测踩过一次。
    """
    if not resolver_code:
        return None
    try:
        from smartbi.gold.restaurant.restaurant_intent_service import (
            _RESOLVER_DIMENSIONS,
        )
    except Exception:  # noqa: BLE001 —— 拿不到就不收窄, 保持旧行为
        logger.warning("[follow-up] 取不到 resolver 能力表, 本次不收窄维度",
                       exc_info=True)
        return None
    declared = _RESOLVER_DIMENSIONS.get(resolver_code)
    if declared is None:
        return None
    # 把**登记表的每个维度键**映射成管线名, 留下 declared 里有的那些。
    return {key for key in _reg.DIMENSIONS
            if _reg.canonical_dimensions((key,))[0] in declared}


def _drillable_dimensions(metric_key: str,
                          used: Sequence[str],
                          resolver_code: Optional[str] = None) -> Tuple[str, ...]:
    """还没用过、**而且问得到**的维度 —— registry 反查 ∩ resolver 能力,
    ⛔ 两半都不手写。
    """
    entry = _reg.METRICS.get(metric_key)
    if entry is None:
        derived = _reg.DERIVED.get(metric_key)
        if derived is None:
            return ()
        # 派生量取两个输入的**交集** —— 只在两边都能分组的维度上下钻,
        # ⛔ 取并集会给出一个算不出来的组合(点下去就是拒答)。
        left = set(_drillable_dimensions(derived.left, (), resolver_code))
        right = set(_drillable_dimensions(derived.right, (), resolver_code))
        cand = left & right
    else:
        cand = set(entry.dimensions)
    # 🔴 收窄到 resolver **真答得出**的那些 —— 见 `_answerable_dimensions`。
    answerable = _answerable_dimensions(resolver_code)
    if answerable is not None:
        cand &= answerable
    # `all` 是「不分组」, 它不是可下钻的对象
    skip = set(used) | {"all"}
    return tuple(d for d in sorted(cand - skip) if d in _reg.DIMENSIONS)


def is_exhausted(metric_key: str, used_dimensions: Sequence[str],
                 resolver_code: Optional[str] = None) -> bool:
    """还能不能往下钻。

    ⚠️ 判据是「候选集合空了」, 与「这次答案有几行」无关 —— 后者(样本量阈值)
       是**挂账**的那条。混进来的话「拆完了」会在还能拆的时候冒出来。
    """
    return not _drillable_dimensions(metric_key, used_dimensions, resolver_code)


#: 🔴 **按钮携带自己的上下文**(owner 2026-08-15 裁定)。
#:
#: ## 为什么不是「加强 prompt」也不是「改继承机制」
#:
#: 实测那次串发生在 **T1 按钮**上:
#:
#:     「按日期看毛利」  不带 history → 最近30天(默认)
#:                       带 history   → 本月(来自更早的轮次)
#:
#: **他在一屏「最近30天」的数上点了个按钮, 得到「本月」。**
#:
#:   ❌ 加强 prompt 指令 —— 弱, 而且已经有一句「当前问题有新要求时以当前问题
#:      为准」, 它没拦住这次
#:   ❌ 改继承机制 —— 太宽, 会同时改掉「罗氏虾呢」那种**正确**的继承
#:   ✅ 按钮是**我们生成的**, 我们知道它从哪一屏长出来 —— 让它自己带上
#:
#: ## 为什么写进问句正文, 而不是加一个字段
#:
#: 前端 `normalizeFollowUpActions` 只保留 `{label, question}` ——
#: **额外字段活不过这一趟往返**。⇒ 上下文只能写进问句本身。
#: 好处是它顺带让问句**自足**: 说全了就不需要继承, 串从源头消失,
#: ⛔ 不是「降低概率」。
#:
#: 判据: **按下去到的, 必须是它长出来的那一屏。**
def contextualize(question: str, window_label: str) -> str:
    """给按钮问句补上它长出来那一屏的时间窗。

    ⚠️ 窗口词已经在问句里就不重复加 —— 「本月毛利本月多少」读起来像坏了。
    ⚠️ `window_label` 空着就原样返回: ⛔ 不编一个默认窗口塞进去,
       那等于让按钮**声称**了一个我们并不知道的范围。
    """
    q = (question or "").strip()
    w = (window_label or "").strip()
    if not q or not w or w in q:
        return q
    return f"{w}{q}"


def build_actions(
    *,
    metric_key: str,
    used_dimensions: Sequence[str],
    offers: Sequence[Dict[str, Any]],
    answer_text: str,
    meta: Optional[Dict[str, Any]] = None,
    resolver_code: Optional[str] = None,
    trace_subject: Optional[Dict[str, str]] = None,
    #: 🔴 这一屏的时间窗。按钮问句带上它 ⇒ 按下去到的就是它长出来的那一屏,
    #:    ⛔ 不去 history 里猜。见 `contextualize`。
    window_label: str = "",
) -> Tuple[Dict[str, Any], ...]:
    """这次回答该给哪些按钮。**排好序、截好断**再送。

    :param offers: `fill_offers` 的产出 —— **与正文共用同一批**,
        ⛔ 不为按钮再拼一份文案(那就是同一句话两个来源)。
    """
    if suppressed(meta):
        return ()

    actions: List[Dict[str, Any]] = []

    # T2 —— 补数据。
    #   `label` 取 offer 正文**破折号/括号之前**的那一截 ⇒ 它逐字在正文里(判据六)。
    #   `question` 是**固定的、我们答得出来的**那句 —— ⛔ 不把 label 当问句发回来:
    #   「先补这 3 道的成本卡」不是一个问句, 发回来只会拒答。
    for offer in offers or ():
        text = str(offer.get("text") or "")
        if not text or offer.get("kind") != "fill_dishes":
            continue
        label = text.split("——")[0].split("（")[0].strip().rstrip("，,")
        if not label:
            continue
        actions.append({
            "type": "T2",
            "label": label,                     # 正文里那一截, 逐字
            "question": contextualize(T2_FILL_QUESTION, window_label),
            "anchor": text,                     # 正文里那句话, 逐字
        })
        break                                   # 同一类只给一颗

    # T1 —— 下钻。
    #   `label` = 「按品牌」—— 它逐字出现在 `drilldown_note()` 那句里(判据六);
    #   `question` = 「按品牌看毛利」—— 完整问句, 裸维度词发回来路由不到。
    #   `anchor` 是**那个数字所在的那一段**(正文第一段), 下钻就是「进入这个数」。
    head = (answer_text or "").split("\n\n")[0]
    metric_label = _metric_label(metric_key)
    for dim_key in _drillable_dimensions(metric_key, used_dimensions,
                                         resolver_code):
        phrase = _dim_phrase(dim_key)
        actions.append({
            "type": "T1",
            "label": phrase,
            "question": contextualize(f"{phrase}看{metric_label}",
                                      window_label),
            "anchor": head,
        })

    # T3 —— 标事件。**只在归因走到无痕层时出**(候选下钻维度为空)。
    #   ⚠️ 它与 T1/T2 根本不同: T1/T2 点下去是**我们**再算一次,
    #      T3 点下去是**他**说一句。产出是主观数据, 有自己的 provenance。
    #   ⛔ 触发判据只有「候选为空」—— ⛔ 不掺样本量(那条挂账), 掺进来的话
    #      T3 会在**还能继续往下查**的时候冒出来, 等于用问句顶替一次查询。
    if trace_subject and is_exhausted(metric_key, used_dimensions, resolver_code):
        try:
            from smartbi.gold.restaurant.event_annotation import build_action
            actions.append(build_action(
                when=trace_subject.get("when", ""),
                subject=trace_subject.get("subject", ""),
                phenomenon=trace_subject.get("phenomenon", ""),
                anchor=head))
        except Exception:  # noqa: BLE001 —— 少一颗按钮不该让答案挂掉
            logger.warning("[follow-up] T3 按钮生成失败", exc_info=True)

    # ⛔ 排序在后端。前端只截断 —— 送一堆让它 slice, 等于优先级由别的仓决定。
    actions.sort(key=lambda a: TYPE_PRIORITY.get(a["type"], 99))
    return tuple(actions[:MAX_ACTIONS])


def to_followups(actions: Sequence[Dict[str, Any]]) -> List[Dict[str, str]]:
    """按钮 → `suggested_followups` 的元素。**剥掉 `anchor`**。

    ⚠️ `anchor` 是内部字段(整段正文), 送出去会变成一颗巨大的按钮文本 ——
       前端 `normalizeFollowUpActions` 读 `item.question ?? item.text ?? item.label`,
       多余的键它丢掉, 但没必要把整段正文塞进网络。
    ⚠️ 保留 `type`: 前端会丢, 但 Java 侧排序/埋点要看它。
    """
    out: List[Dict[str, str]] = []
    for action in actions or ():
        label = str(action.get("label") or "").strip()
        question = str(action.get("question") or "").strip()
        if not label or not question:
            continue
        out.append({"label": label, "question": question,
                    "type": str(action.get("type") or "")})
    return out


def assert_actions_anchored(actions, answer_text: str) -> None:
    """边界 1 的可执行形式。⛔ 按钮不许说正文没说的话 —— 它是入口, 不是新内容。

    两条都查, **`label` 那条是 2026-08-14 加严的**:

      · `anchor` 在正文里 —— 按钮指着正文的哪句话
      · **`label` 逐字在正文里** —— 按钮上印的那几个字, 他在正文里读得到

    ⚠️ 只查 `anchor` 不够: anchor 可以是整段正文, 那时**任何** label 都能配上
       一个「在正文里」的 anchor —— 那条断言就退化成「正文非空」。
       label 这条才真正约束按钮**说了什么**。
    """
    body = answer_text or ""
    for action in actions or ():
        anchor = str(action.get("anchor") or "")
        label = str(action.get("label") or "")
        assert anchor and anchor in body, (
            f"按钮 {label!r} 的 anchor 不在正文里 —— 它在说正文没说的话")
        assert label and label in body, (
            f"按钮上印着 {label!r}, 而正文里没有这几个字 —— "
            f"他点之前读不到这是什么。\n正文: {body[:200]!r}")
