"""`_execution_mismatch` 的每个拒答理由都会**原样念给老板听**，不许是行话。

## 为什么单开一道闸（而不是靠既有的黑话闸）

既有 `test_no_internal_jargon_in_customer_text` 的盲区 #2 写得很清楚：

> 标点启发式把无标点的片段当成键名跳过 —— ⚠️ **留着**。去掉它会把
> SQL/键名全报成违例，误报会让人把闸关掉，比没有闸更糟。

而本函数的理由串**恰好都没有中文标点**，于是六条行话在它眼皮底下躺了很久：

    餐饮执行计划缺少可信语义来源 / 餐饮执行计划不完整 / 主意图与执行步骤不一致
    店菜范围与执行 resolver 不一致 / 菜品范围不能由全店汇总 resolver 代答
    门店范围不能由全店或全门店 resolver 代答

⛔ 正确的修法**不是**去放宽那个启发式（那会做出一个误报更多、迟早被关掉的闸）。
✅ 而是本仓规则给的那条路：**把面向用户的串收敛到一处，闸扫那一处。**
   这里的串**按构造**就是面向老板的（它们被拼进拒答文案的开头），
   所以这道闸不需要「这句是不是面向用户」那个代理判据 —— 它是精确的。

## 它守什么

理由串会被拼成「{理由}，所以这次我没敢算。…」念给老板。所以：
① 不含内部概念名 ② 读起来是一句人话（不是键名）。

⚠️ 2026-08-17 实测这六条**从没进过 prod 日志**（0 次，而同期规划层那句通用反问
   有 5 次）—— 它们是**潜伏**的。写这道闸正是因为下一步要把规划层那道闸下沉，
   一下沉它们就从潜伏变成活的。⛔ 不能等它们发出去了再改。
"""
import ast
import inspect
import re

import pytest

from smartbi.gold.restaurant import restaurant_intent_service as svc

# 内部概念名。⛔ 不重新发明 —— 与既有黑话闸同源的那几类：
# resolver/维度这类英文内部名 + 「意图/执行计划/语义」这类内部中文概念。
INTERNAL_TERMS = (
    "resolver", "dimension", "intent", "spec", "plan_version",
    "主意图", "执行步骤", "执行计划", "语义来源", "语义规划", "置信度",
)


def _reason_strings():
    """`_execution_mismatch` 里所有会被念出去的理由串。

    ⛔ 用 AST 数**结构**（返回语句里的字符串常量），不用正则数文本 ——
       本仓明令：闸用 AST，字符串计数量的是文本，闸要守的从来是结构。
    """
    tree = ast.parse(inspect.getsource(svc._execution_mismatch))
    out = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Return) and isinstance(node.value, ast.Constant):
            if isinstance(node.value.value, str):
                out.append(node.value.value)
    # 常量出口（`return _STORE_SCOPE_MISMATCH`）走不到上面那条
    out.append(svc._STORE_SCOPE_MISMATCH)
    return out


class TestEveryRefusalReasonIsSomethingABossCanRead:
    def test_the_gate_actually_found_the_reasons(self):
        """阳性对照：先证明这道闸**看得见东西**。

        少了这一条，下面「没有行话」的断言在 AST 取不到串时同样是绿的 ——
        那就是一道扫了空集的闸。
        """
        reasons = _reason_strings()
        assert len(reasons) >= 6, (
            f"只取到 {len(reasons)} 条理由串，`_execution_mismatch` 的出口"
            f"结构可能变了 —— 这道闸已经扫不到它要守的东西"
        )

    @pytest.mark.parametrize("term", INTERNAL_TERMS)
    def test_no_internal_term_reaches_the_boss(self, term):
        hits = [r for r in _reason_strings() if term.lower() in r.lower()]
        assert not hits, (
            f"拒答理由里出现内部概念 {term!r}，它会原样念给老板：{hits!r}"
        )

    def test_reasons_read_like_sentences_not_keys(self):
        """行话的另一种长相：像个键名而不是一句话。

        判据取**结构**特征：理由串会被拼成「{理由}，所以这次我没敢算」，
        所以它必须是一句能接下去的中文短句 —— 不含下划线、不是全 ASCII。
        """
        for reason in _reason_strings():
            assert "_" not in reason, f"理由像键名: {reason!r}"
            assert re.search(r"[一-鿿]", reason), f"理由没有中文: {reason!r}"
            assert len(reason) >= 6, f"理由短到不成句: {reason!r}"
