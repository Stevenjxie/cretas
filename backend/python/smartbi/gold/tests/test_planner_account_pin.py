"""按租户把规划流量限制到单一账号 —— 严格 opt-in。

## 为什么

2026-08-11 实测：回归电池跑了 12 轮全量，约 2100 次调用 / 4.3M token，把免费额度
烧掉 8 个模型 —— 而 prod **真实用户流量是 0**，烧掉的全是验证本身。

更贵的是它构成**恶性循环**：额度耗尽 → 链往下掉 → 换模型 →
**前缀缓存从头开始** → 每次调用付全额 token → 耗得更快。
当天实测缓存命中 49%，而 prompt 的共同前缀是 **99%** —— 低命中不是 prompt 的问题，
是一轮之内跨了 5 个模型。

🔑 顺带解决更要紧的一件：电池分数现在由「今天哪个模型还活着」主导
（08-11 三个读数 83 / 78 / 76 互不可比）。把电池租户限制到一个账号，
分数才重新变成**代码的函数**。

## ⚠️ 代价写在这里

被钉的租户**不再走用户实际的那条链**，真实路径上的回归它看不见。
所以只该钉**电池租户**；而电池每轮打印的「取数条件」会把实际服务模型报出来，
读分数的人一眼能看到自己在看哪一种。

## 这道闸守什么

1. **不配 = 零行为变更**（真实租户绝不能被误伤）
2. 配了**只影响被点名的租户**
3. 用的是 `call_chain` **已有的** `chain` 参数，不新造机制
"""
from __future__ import annotations

import os

import pytest

from smartbi.gold.restaurant.restaurant_intent import (
    _planner_account_filter, _planner_account_pins,
)

_ENV = "RESTAURANT_PLANNER_ACCOUNT_PINS"


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    monkeypatch.delenv(_ENV, raising=False)


def test_unset_env_means_zero_behaviour_change():
    """🔴🔴 承重: 没配环境变量时**任何**租户都返回 None(= 走完整链)。

    这条一旦破，真实租户会被悄悄限制到一个账号 —— 而症状是「某些模型再也不被用到」,
    没有任何东西会报错。
    """
    assert _planner_account_filter("MOCK_REST") is None
    assert _planner_account_filter("QHJ01") is None
    assert _planner_account_filter(None) is None
    assert _planner_account_pins() == {}


def test_pin_applies_only_to_the_named_tenant(monkeypatch):
    """🔴 配了也只影响被点名的那个租户。"""
    monkeypatch.setenv(_ENV, "MOCK_REST=aliyun_c")
    assert _planner_account_filter("MOCK_REST") == ["aliyun_c"]
    assert _planner_account_filter("QHJ01") is None, "别的租户被误伤了"
    assert _planner_account_filter("") is None


def test_multiple_pins_are_independent(monkeypatch):
    monkeypatch.setenv(_ENV, "MOCK_REST=aliyun_c,DEMO_REST=aliyun_a")
    assert _planner_account_filter("MOCK_REST") == ["aliyun_c"]
    assert _planner_account_filter("DEMO_REST") == ["aliyun_a"]
    assert _planner_account_filter("QHJ01") is None


@pytest.mark.parametrize("raw", ["", "   ", "MOCK_REST", "=aliyun_c", "MOCK_REST=",
                                 ",,,", "garbage"])
def test_malformed_config_falls_back_to_no_pin(monkeypatch, raw):
    """⛔ 配错了要**退回不限制**, 不是限制到一个空账号。

    退回空账号 = `chain=[""]` = 过滤后一个模型都不剩 = 整条链失效, 而症状会是
    「所有请求都 fail-closed」—— 一个配置笔误就能把整个租户打死。
    """
    monkeypatch.setenv(_ENV, raw)
    assert _planner_account_filter("MOCK_REST") is None


def test_it_uses_call_chain_existing_account_filter():
    """⛔ 判据: 用 `call_chain` **已有的** `chain` 参数, 不新造机制。

    路由是本仓最不该多一个载体的地方。这条钉住「透传给 chain=」这件事本身。
    """
    import inspect

    from smartbi.gold.restaurant import restaurant_intent as ri

    src = inspect.getsource(ri._t3_llm_parse)
    assert "chain=account_filter" in src, (
        "没有把过滤器交给 call_chain 已有的 chain 参数 —— 要么没接上, 要么另造了机制")


def test_every_planner_call_site_passes_the_filter():
    """🔴 接缝: 四个 `_t3_llm_parse` 调用点**都**要透传, 漏一个那条路就不受控。

    ⛔ 载体要算出来 —— 数「有几处调用」而不是凭印象说「都改了」。
       (2026-08-11 当天刚为同一形状栽过: fail-closed 留痕装了 2 处漏了 2 处。)
    """
    import ast
    import inspect

    from smartbi.gold.restaurant import restaurant_intent as ri

    src = inspect.getsource(ri)
    calls = [n for n in ast.walk(ast.parse(src))
             if isinstance(n, ast.Call)
             and getattr(n.func, "id", None) == "_t3_llm_parse"]
    assert len(calls) >= 4, f"只找到 {len(calls)} 个调用点 —— 扫描写错了"
    without = [n.lineno for n in calls
               if not any(k.arg == "account_filter" for k in n.keywords)]
    assert not without, f"这些调用点没透传 account_filter(行号): {without}"
