from food_kb.services.knowledge_retriever import KnowledgeDocument, KnowledgeRetriever


def _doc(doc_id: int, title: str) -> KnowledgeDocument:
    return KnowledgeDocument(
        {
            "id": doc_id,
            "title": title,
            "content": "",
            "category": "operation_manual",
            "source": "",
            "version": "",
            "similarity": 1.0,
            "metadata": {},
        }
    )


def test_diversify_by_section_prefers_distinct_sections():
    results = [
        _doc(1, "编码规则配置"),
        _doc(2, "编码规则配置 (#1)"),
        _doc(3, "编码规则配置 (#2)"),
        _doc(4, "BOM 配方"),
        _doc(5, "销售订单创建"),
    ]

    diversified = KnowledgeRetriever._diversify_by_section(results, top_k=3)

    assert [d.id for d in diversified] == [1, 4, 5]


def test_diversify_by_section_fills_tail_with_duplicate_chunks():
    results = [
        _doc(1, "编码规则配置"),
        _doc(2, "编码规则配置 (#1)"),
        _doc(3, "BOM 配方"),
    ]

    diversified = KnowledgeRetriever._diversify_by_section(results, top_k=3)

    assert [d.id for d in diversified] == [1, 3, 2]


def test_boost_title_matches_lifts_exact_title_above_generic_hit():
    results = [
        _doc(1, "工厂操作手册 - 测试账号"),
        _doc(2, "工厂下单操作指南 - 七、测试账号"),
        _doc(3, "工厂操作手册 - 操作日志"),
    ]

    boosted = KnowledgeRetriever._boost_title_matches(
        "工厂下单操作指南 - 七、测试账号",
        results,
    )

    assert [d.id for d in boosted] == [2, 1, 3]
