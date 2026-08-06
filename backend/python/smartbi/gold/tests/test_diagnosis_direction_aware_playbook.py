"""playbook 是单向的 —— 指标偏到「好」的一侧时不能原样挂上去。

## 事故

2026-08-06 prod 实测 MOCK_REST: `ingredient_waste_rate` = 3.75%,
行业区间 [8, 25], `higher_is_worse: true` → status「偏低」/ severity「info」。
**比标杆好**。但诊断里带着一整套 `ingredient_waste_rate_high` 的建议:
「削减过量采购」「减少过期报废」—— 在劝一个已经做得比同行好的人继续压这个指标。

根因: `diagnostics_engine` 挂 playbook 时只看 `playbook_id` 存不存在, 不看偏离方向。

## 为什么不是简单地不返回

对 `higher_is_worse` 的指标, **异常低往往不是管得好而是没登记**(损耗没录/报废没走
系统)。当成优秀吞掉 = 把「没采集到」渲染成「正常」。所以照常出诊断, 只是换成方向
正确的话, 并提示先核对采集完整性。
"""
from __future__ import annotations

import io
from pathlib import Path

SRC = (Path(__file__).resolve().parents[3] / "smartbi" / "shared" / "diagnostics_engine.py")


def _source() -> str:
    return io.open(SRC, encoding="utf-8", newline="").read()


def test_playbook_attach_is_gated_on_direction():
    """🔴 挂 playbook 的条件里必须带方向判断, 不能只判 playbook_id。"""
    src = _source()
    assert "deviates_favourably" in src, "方向判断没了 —— 偏到好的一侧会重新挂反向建议"
    assert "if playbook_id and not deviates_favourably:" in src, (
        "playbook 的挂载条件不再排除「偏离到好的一侧」—— "
        "损耗率低于标杆的店会重新收到「削减过量采购」这类反向建议"
    )


def test_favourable_direction_covers_both_metric_polarities():
    """higher_is_worse 与 lower_is_worse 两种指标都要覆盖。"""
    src = _source()
    block = src[src.index("deviates_favourably = ("):src.index("if playbook_id and not")]
    assert 'higher_is_worse and status in ("偏低", "严重偏低")' in block
    assert 'not higher_is_worse and status in ("偏高", "严重偏高")' in block


def _executable(block: str) -> str:
    """只留会执行的行。注释里出现的字面量不算行为 ——
    第一版断言直接扫整段, 结果匹配到了我自己写的注释「不挂 playbook_url / rx_actions」,
    测的是文本不是行为。"""
    lines = block.splitlines()
    return "\n".join(ln for ln in lines if not ln.strip().startswith("#"))


def test_favourable_branch_does_not_emit_rx_actions():
    """🔴 方向正确时不得给处方动作 —— 那些是为「指标变差」写的。"""
    src = _source()
    branch = src[src.index("elif deviates_favourably:"):]
    branch = _executable(branch[:branch.index("# 渲染描述文案")])
    assert "_extract_rx_actions" not in branch, "方向正确时挂了 rx_actions —— 给了一份不该执行的动作清单"
    assert "playbook_url" not in branch, "方向正确时挂了 playbook_url"


def test_favourable_branch_warns_about_data_completeness():
    """🔴 不能只说「你很好」—— 异常优于同行更常见的原因是漏记录。"""
    src = _source()
    branch = src[src.index("elif deviates_favourably:"):]
    branch = _executable(branch[:branch.index("# 渲染描述文案")])
    assert "采集" in branch, "没提示核对数据采集完整性 —— 会把「没采集到」说成「管得好」"


def test_healthy_and_normal_still_return_none():
    """既有行为不变: 真正健康/正常的项仍然不出诊断(减少噪音)。"""
    src = _source()
    assert 'if severity == "info" and status in ("健康", "正常"):' in src
