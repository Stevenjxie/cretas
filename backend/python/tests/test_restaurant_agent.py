"""Guard rails for the compound-question agent (R28)."""
from __future__ import annotations

from smartbi.gold import restaurant_agent as agent


def test_compound_heuristic():
    assert agent.is_compound_question("这个月生意怎么样，另外米饭卖得好不好")
    assert agent.is_compound_question("先告诉我昨天的营收，再告诉我哪家店业绩最好")
    assert not agent.is_compound_question("米饭的销量是多少")
    assert not agent.is_compound_question("米饭的销量是多少；继续追问：成本如何")


def test_parse_parts_validation():
    ok = agent._parse_parts('{"parts": ["这个月生意怎么样", "米饭卖得好不好"]}')
    assert ok == ["这个月生意怎么样", "米饭卖得好不好"]
    assert agent._parse_parts('{"parts": ["只有一个问题在这里"]}') is None
    assert agent._parse_parts('{"parts": ["合法的问题在这", 123]}') is None
    assert agent._parse_parts('{"parts": ["太短"]}') is None
    assert agent._parse_parts("垃圾输出") is None
    fenced = '```json\n{"parts": ["这个月生意怎么样", "米饭卖得好不好"]}\n```'
    assert agent._parse_parts(fenced) == ["这个月生意怎么样", "米饭卖得好不好"]


def test_assemble_compound_answer():
    parts = ["这个月生意怎么样", "米饭卖得好不好"]
    results = [
        {"kind": "answer", "answer_text": "本月营收 ¥100。", "charts": [1],
         "kpis": [2], "code": "RESTAURANT_OPS_SALES_SUMMARY", "contract_pass": True},
        None,
    ]
    combined = agent.assemble_compound_answer(parts, results)
    assert combined and "1. 这个月生意怎么样" in combined["answer_text"]
    assert "本月营收 ¥100。" in combined["answer_text"]
    assert "请单独提问" in combined["answer_text"]
    assert combined["contract_pass"] is False
    assert agent.assemble_compound_answer(parts, [None, None]) is None
