#!/usr/bin/env python3
"""
RAG 回答质量评估 — LLM-as-Judge + 规则幻觉检测

流程: 测试查询 -> RAG 检索文档 -> LLM 生成回答 -> LLM 评审打分 + 规则检测

Usage:
  python eval_answer_quality.py --server http://localhost:8083 --output eval_answer_quality_results.json
  python eval_answer_quality.py --server http://localhost:8083 --verbose --queries 5
"""

import argparse, json, logging, os, re, sys, time
from pathlib import Path
from typing import Any, Dict, List, Optional

import httpx

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("eval_answer_quality")

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
LLM_API_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
LLM_MODEL = "qwen3.7-max-2026-06-08"  # free quota ⚠️ (qwen-max is PAID — billing incident 2026-06-11)
FALLBACK_API_KEY = "sk-da3b827e6a00404a8bc869296f8690bc"

DIMENSION_WEIGHTS = {
    "accuracy": 0.40, "hallucination": 0.20, "completeness": 0.20,
    "citation_quality": 0.10, "actionability": 0.10,
}

GB_PATTERN = re.compile(r"GB\s*/?\s*[TZ]?\s*\d[\d.\-]*")
NUMERIC_UNIT_PATTERN = re.compile(
    r"(\d+\.?\d*)\s*(g/kg|mg/kg|mg/L|CFU/[gm]L?|%|"
    r"[°℃]\s*C?|mm|cm|μg/kg|ppm|ppb|mL|kPa|MPa|min|[hH]|天|小时|分钟)"
)

TEST_QUERIES = [
    "山梨酸钾在肉制品中的最大使用量是多少",
    "GB 7718对预包装食品标签有什么要求",
    "冷库温度到底设多少度合适",
    "车间工人手上有伤口还能不能上班",
    "HACCP关键控制点CCP怎么识别",
    "婴幼儿配方奶粉中的微生物限量标准",
    "食品级不锈钢304和316有什么区别",
    "有机蔬菜和普通蔬菜农残标准有区别吗",
    "速冻水饺的储存温度要求",
    "酱油里能不能放苯甲酸钠",
    "食品生产许可SC证申请条件",
    "沙门氏菌的检测方法",
    "网上卖食品需要什么资质",
    "食品追溯系统怎么建",
    "罐头食品商业无菌检验标准",
    "中央厨房食品安全管理规范",
    "食用油酸价超标是什么原因",
    "面包保质期怎么确定",
    "水产品组胺限量标准",
    "转基因食品必须标识吗",
]

ANSWER_PROMPT = """你是食品安全领域的专业顾问。根据以下检索到的知识库文档回答用户问题。

要求：
1. 只基于提供的文档内容回答，不要编造信息
2. 引用具体的GB标准号和数值
3. 如果文档不足以回答，明确说明
4. 给出具体可操作的建议

检索文档：
{documents}

用户问题：{query}"""

JUDGE_PROMPT = """你是食品安全领域的评审专家。请对以下RAG系统的回答进行评分。

评分维度（1-5分）：
1. 准确性(40%): 事实、标准号(GB xxxx)、数值是否正确
2. 幻觉程度(20%): 是否编造了源文档中没有的标准、法规或数字（5=零幻觉，1=严重编造）
3. 完整性(20%): 是否覆盖了检索文档中的关键要点
4. 引用质量(10%): 是否引用了具体文档/标准
5. 可操作性(10%): 是否提供了具体可执行的操作建议

用户问题：{query}
检索文档：{documents}
系统回答：{answer}

请严格按以下JSON格式输出，不要添加其他内容：
{{"accuracy": X, "hallucination": X, "completeness": X, "citation_quality": X, "actionability": X, "notes": "简要评价"}}"""


# ---------------------------------------------------------------------------
# LLM + RAG helpers
# ---------------------------------------------------------------------------

def get_api_key() -> str:
    return os.environ.get("LLM_API_KEY", FALLBACK_API_KEY)


def call_llm(client: httpx.Client, prompt: str, temperature: float = 0.7, max_tokens: int = 2000) -> str:
    resp = client.post(
        f"{LLM_API_BASE}/chat/completions",
        headers={"Authorization": f"Bearer {get_api_key()}", "Content-Type": "application/json"},
        json={"model": LLM_MODEL, "messages": [{"role": "user", "content": prompt}],
              "temperature": temperature, "max_tokens": max_tokens},
        timeout=60,
    )
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"].strip()


def retrieve_documents(client: httpx.Client, server: str, query: str, top_k: int = 5) -> List[Dict]:
    resp = client.post(f"{server}/api/food-kb/query", json={"query": query, "top_k": top_k}, timeout=30)
    resp.raise_for_status()
    data = resp.json()
    if not data.get("success"):
        logger.warning("RAG failed for '%s': %s", query, data.get("error", ""))
        return []
    return data.get("data", [])


def format_documents(docs: List[Dict]) -> str:
    if not docs:
        return "(无检索结果)"
    parts = []
    for i, doc in enumerate(docs, 1):
        hdr = f"[文档{i}] {doc.get('title', '未知')}"
        if doc.get("source"):
            hdr += f" (来源: {doc['source']})"
        hdr += f" [相似度: {doc.get('similarity', 0):.2f}]"
        parts.append(f"{hdr}\n{doc.get('content', '')}")
    return "\n\n".join(parts)


# ---------------------------------------------------------------------------
# Rule-based hallucination detection
# ---------------------------------------------------------------------------

def detect_hallucinated_standards(answer: str, docs: List[Dict]) -> Dict:
    docs_text = " ".join(
        f"{d.get('title', '')} {d.get('content', '')} {d.get('source', '')}" for d in docs
    )
    norm = lambda s: re.sub(r"\s+", "", s)
    ans_stds = {norm(s): s for s in GB_PATTERN.findall(answer)}
    doc_stds = {norm(s) for s in GB_PATTERN.findall(docs_text)}
    fabricated = [orig for nrm, orig in ans_stds.items() if nrm not in doc_stds]
    return {
        "answer_standards": sorted(ans_stds.values()),
        "doc_standards": sorted(doc_stds),
        "fabricated_standards": fabricated,
        "has_fabrication": len(fabricated) > 0,
    }


def detect_hallucinated_numbers(answer: str, docs: List[Dict]) -> Dict:
    docs_text = " ".join(d.get("content", "") for d in docs)
    doc_vals = {(v, u.strip()) for v, u in NUMERIC_UNIT_PATTERN.findall(docs_text)}
    ans_nums = NUMERIC_UNIT_PATTERN.findall(answer)
    unverified = [f"{v} {u.strip()}" for v, u in ans_nums if (v, u.strip()) not in doc_vals]
    return {
        "answer_numeric_claims": [f"{v} {u}" for v, u in ans_nums],
        "unverified_numbers": unverified,
        "has_unverified": len(unverified) > 0,
    }


# ---------------------------------------------------------------------------
# Judge output parsing
# ---------------------------------------------------------------------------

def parse_judge_scores(raw: str) -> Optional[Dict]:
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        pass
    m = re.search(r"\{[^{}]+\}", raw, re.DOTALL)
    if m:
        try:
            return json.loads(m.group())
        except json.JSONDecodeError:
            pass
    logger.warning("Failed to parse judge output: %s", raw[:200])
    return None


def compute_overall(scores: Dict) -> float:
    return round(sum(scores.get(d, 3.0) * w for d, w in DIMENSION_WEIGHTS.items()), 2)


# ---------------------------------------------------------------------------
# Single query evaluation
# ---------------------------------------------------------------------------

def evaluate_query(llm: httpx.Client, rag: httpx.Client, server: str, query: str, verbose: bool) -> Dict:
    result: Dict[str, Any] = {"query": query}

    # 1. Retrieve
    logger.info("  [检索] %s", query)
    docs = retrieve_documents(rag, server, query)
    result["retrieved_docs"] = len(docs)
    docs_text = format_documents(docs)

    if not docs:
        result.update(answer="", scores={d: 1.0 for d in DIMENSION_WEIGHTS},
                      overall_score=1.0, judge_notes="检索无结果",
                      hallucination_check={"standards": {}, "numbers": {}})
        return result

    # 2. Generate answer
    logger.info("  [生成] %d docs", len(docs))
    answer = call_llm(llm, ANSWER_PROMPT.format(documents=docs_text, query=query))
    result["answer"] = answer
    if verbose:
        logger.info("  [回答] %s", answer[:150] + ("..." if len(answer) > 150 else ""))
    time.sleep(1)

    # 3. LLM judge
    logger.info("  [评审] scoring")
    judge_raw = call_llm(llm, JUDGE_PROMPT.format(query=query, documents=docs_text, answer=answer),
                         temperature=0.3, max_tokens=500)
    scores = parse_judge_scores(judge_raw)
    if scores:
        result["scores"] = {d: float(scores.get(d, 3.0)) for d in DIMENSION_WEIGHTS}
        result["judge_notes"] = scores.get("notes", "")
    else:
        result["scores"] = {d: 3.0 for d in DIMENSION_WEIGHTS}
        result["judge_notes"] = f"parse_failed: {judge_raw[:200]}"
    result["overall_score"] = compute_overall(result["scores"])
    time.sleep(1)

    # 4. Rule-based hallucination
    std_chk = detect_hallucinated_standards(answer, docs)
    num_chk = detect_hallucinated_numbers(answer, docs)
    result["hallucination_check"] = {"standards": std_chk, "numbers": num_chk}

    if std_chk["fabricated_standards"]:
        logger.warning("  [幻觉] 标准: %s", ", ".join(std_chk["fabricated_standards"]))
    if num_chk["unverified_numbers"]:
        logger.warning("  [幻觉] 数值: %s", ", ".join(num_chk["unverified_numbers"][:5]))

    s = result["scores"]
    logger.info("  [得分] %.2f (acc=%.0f hal=%.0f comp=%.0f cite=%.0f act=%.0f)",
                result["overall_score"], s["accuracy"], s["hallucination"],
                s["completeness"], s["citation_quality"], s["actionability"])
    return result


# ---------------------------------------------------------------------------
# Aggregation
# ---------------------------------------------------------------------------

def aggregate(per_query: List[Dict]) -> Dict:
    n = len(per_query)
    if n == 0:
        return {}

    avg_dims = {d: round(sum(r["scores"].get(d, 0) for r in per_query) / n, 2) for d in DIMENSION_WEIGHTS}
    avg_overall = round(sum(r["overall_score"] for r in per_query) / n, 2)

    fab_std_count = sum(
        1 for r in per_query
        if r.get("hallucination_check", {}).get("standards", {}).get("has_fabrication")
    )
    fab_details = [
        {"query": r["query"], "fabricated_standards": r["hallucination_check"]["standards"]["fabricated_standards"]}
        for r in per_query
        if r.get("hallucination_check", {}).get("standards", {}).get("fabricated_standards")
    ]
    unverified_count = sum(
        1 for r in per_query
        if r.get("hallucination_check", {}).get("numbers", {}).get("has_unverified")
    )

    return {
        "total_queries": n,
        "avg_overall_score": avg_overall,
        "avg_dimension_scores": avg_dims,
        "hallucination_flags": {
            "total_answers": n,
            "answers_with_fabricated_standards": fab_std_count,
            "answers_with_unverified_numbers": unverified_count,
            "fabricated_standard_details": fab_details,
        },
        "per_query_results": per_query,
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="RAG 回答质量评估 — LLM-as-Judge + 规则幻觉检测")
    parser.add_argument("--server", default="http://localhost:8083", help="RAG 服务地址")
    parser.add_argument("--output", default="eval_answer_quality_results.json", help="输出 JSON 路径")
    parser.add_argument("--queries", type=int, default=0, help="仅前 N 个查询 (0=全部)")
    parser.add_argument("--verbose", "-v", action="store_true")
    args = parser.parse_args()

    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)

    queries = TEST_QUERIES[:args.queries] if args.queries > 0 else TEST_QUERIES

    logger.info("=" * 60)
    logger.info("RAG 回答质量评估  queries=%d  model=%s", len(queries), LLM_MODEL)
    logger.info("  server=%s  output=%s", args.server, args.output)
    logger.info("=" * 60)

    # Health check
    with httpx.Client() as c:
        try:
            h = c.get(f"{args.server}/api/food-kb/health", timeout=10).json()
            logger.info("健康: retriever=%s", h.get("components", {}).get("knowledge_retriever"))
        except Exception as e:
            logger.error("无法连接 %s: %s", args.server, e)
            sys.exit(1)

    # Evaluate all queries
    results: List[Dict] = []
    with httpx.Client() as llm, httpx.Client() as rag:
        for i, q in enumerate(queries, 1):
            logger.info("[%d/%d] %s", i, len(queries), q)
            try:
                results.append(evaluate_query(llm, rag, args.server, q, args.verbose))
            except Exception as e:
                logger.error("  失败: %s", e, exc_info=args.verbose)
                results.append({"query": q, "error": str(e),
                                "scores": {d: 0.0 for d in DIMENSION_WEIGHTS}, "overall_score": 0.0})

    report = aggregate(results)

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    dims = report.get("avg_dimension_scores", {})
    flags = report.get("hallucination_flags", {})
    logger.info("=" * 60)
    logger.info("完成!  平均总分: %.2f / 5.00", report.get("avg_overall_score", 0))
    logger.info("  acc=%.2f  hal=%.2f  comp=%.2f  cite=%.2f  act=%.2f",
                dims.get("accuracy", 0), dims.get("hallucination", 0), dims.get("completeness", 0),
                dims.get("citation_quality", 0), dims.get("actionability", 0))
    logger.info("  编造标准: %d/%d  未验证数值: %d/%d",
                flags.get("answers_with_fabricated_standards", 0), flags.get("total_answers", 0),
                flags.get("answers_with_unverified_numbers", 0), flags.get("total_answers", 0))
    logger.info("  报告: %s", output_path.resolve())
    logger.info("=" * 60)


if __name__ == "__main__":
    main()
