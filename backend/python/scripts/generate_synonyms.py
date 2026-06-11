#!/usr/bin/env python3
"""
DashScope 批量同义短语生成脚本

用法:
    cd backend/python
    python scripts/generate_synonyms.py [--dry-run] [--limit 10]

功能:
    1. 从 IntentKnowledgeBase.java 提取种子短语 (每意图取代表性 3-5 条)
    2. 调用 DashScope (Qwen) 批量生成同义变体
    3. 输出 SQL INSERT 语句，可直接导入 ai_learned_expressions 表
    4. 同时输出 Java 代码片段，可粘贴到 initPhraseMappingsPart2()
"""

import argparse
import json
import os
import sys
import time
from pathlib import Path

# Add parent directory to path for config import
sys.path.insert(0, str(Path(__file__).parent.parent))

try:
    from openai import OpenAI
except ImportError:
    print("需要安装 openai: pip install openai")
    sys.exit(1)


# ============ 种子短语定义 ============
# 每个意图选 3-5 条最具代表性的短语作为种子
# LLM 会基于这些种子"举一反三"生成更多自然语言变体

SEED_PHRASES = {
    "PRODUCTION_PLAN_CREATE": [
        "创建生产计划",
        "排产",
        "安排生产",
        "帮我生产500公斤带鱼罐头",
    ],
    "MATERIAL_BATCH_QUERY": [
        "查询库存",
        "今天的库存",
        "还有多少库存",
        "库存多少",
    ],
    "SHIPMENT_QUERY": [
        "查询发货",
        "发货情况",
        "发货记录",
        "最近的发货",
    ],
    "QUALITY_CHECK_QUERY": [
        "质检情况",
        "查看质检结果",
        "今天的质检",
        "合格率怎么样",
    ],
    "PROCESSING_BATCH_CREATE": [
        "创建生产批次",
        "新建批次",
        "开一个新批次",
    ],
    "ORDER_NEW": [
        "下采购单",
        "采购猪肉",
        "新建采购订单",
    ],
    "EQUIPMENT_STATUS_QUERY": [
        "设备状态",
        "设备运行情况",
        "设备有没有故障",
    ],
    "HR_ATTENDANCE_QUERY": [
        "考勤记录",
        "今天出勤",
        "谁没来上班",
    ],
    "REPORT_PRODUCTION": [
        "今天的产量",
        "生产报告",
        "产量数据",
    ],
    "INVENTORY_SUMMARY_QUERY": [
        "仓库还有什么",
        "冷库库存",
        "库存盘点",
    ],
}

SYSTEM_PROMPT = """你是一个中文自然语言处理专家，专门为工厂管理系统生成用户意图的同义表达。

规则:
1. 生成的表达必须与种子短语语义完全等价（同一个操作意图）
2. 覆盖不同表达风格：正式/口语/方言/简洁/啰嗦
3. 包含带数量的变体（如"生产200箱"、"采购3吨"）
4. 包含带时间的变体（如"今天的"、"这周的"、"上个月"）
5. 包含带产品名的变体（如"带鱼罐头"、"猪肉"、"面条"）
6. 不要生成疑问句式的食品知识查询（如"罐头怎么做"、"猪肉怎么保存"是食品知识，不是工厂操作）
7. 每个变体控制在 3-20 个字
8. 不要重复种子短语本身

返回 JSON 数组格式，每个元素是一个字符串。只返回 JSON，不要其他文字。"""


def generate_synonyms(client, intent_code: str, seeds: list[str], count: int = 20) -> list[str]:
    """调用 DashScope 为一组种子短语生成同义变体"""
    user_prompt = f"""意图代码: {intent_code}
种子短语: {json.dumps(seeds, ensure_ascii=False)}

请生成 {count} 个同义表达变体。返回 JSON 数组。"""

    try:
        response = client.chat.completions.create(
            model="qwen3.7-max-2026-06-08",  # free (qwen-plus was PAID — caused 2026-06-11 bill)
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ],
            temperature=0.8,
            response_format={"type": "json_object"},
        )
        content = response.choices[0].message.content.strip()
        # Parse JSON — handle both array and object with array value
        parsed = json.loads(content)
        if isinstance(parsed, list):
            return parsed
        elif isinstance(parsed, dict):
            # Try to find the array value
            for v in parsed.values():
                if isinstance(v, list):
                    return v
        return []
    except Exception as e:
        print(f"  ❌ {intent_code} 生成失败: {e}")
        return []


def deduplicate(synonyms: list[str], seeds: list[str], existing: set[str]) -> list[str]:
    """去重: 移除种子短语本身、已存在的映射、过短/过长的"""
    seed_set = {s.lower().strip() for s in seeds}
    result = []
    seen = set()
    for s in synonyms:
        s = s.strip()
        normalized = s.lower()
        if not s or len(s) < 2 or len(s) > 25:
            continue
        if normalized in seed_set or normalized in existing or normalized in seen:
            continue
        seen.add(normalized)
        result.append(s)
    return result


def output_java(intent_code: str, synonyms: list[str]) -> str:
    """生成 Java phraseToIntentMapping.put() 代码片段"""
    lines = [f'        // DashScope 自动生成 — {intent_code}']
    for s in synonyms:
        lines.append(f'        phraseToIntentMapping.put("{s}", "{intent_code}");')
    return "\n".join(lines)


def output_sql(intent_code: str, synonyms: list[str]) -> str:
    """生成 SQL INSERT 语句 (ai_learned_expressions)"""
    lines = []
    for s in synonyms:
        escaped = s.replace("'", "''")
        lines.append(
            f"INSERT INTO ai_learned_expressions (expression, intent_code, source, status, match_count, created_at) "
            f"VALUES ('{escaped}', '{intent_code}', 'DASHSCOPE_BATCH', 'APPROVED', 0, NOW()) "
            f"ON CONFLICT DO NOTHING;"
        )
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="DashScope 批量同义短语生成")
    parser.add_argument("--dry-run", action="store_true", help="仅显示种子短语，不调用 API")
    parser.add_argument("--limit", type=int, default=0, help="限制处理的意图数量 (0=全部)")
    parser.add_argument("--count", type=int, default=20, help="每个意图生成的变体数量")
    parser.add_argument("--output", type=str, default="synonyms_output", help="输出文件前缀")
    args = parser.parse_args()

    # DashScope API setup
    api_key = os.environ.get("DASHSCOPE_API_KEY")
    if not api_key and not args.dry_run:
        # Try loading from .env
        env_path = Path(__file__).parent.parent / ".env"
        if env_path.exists():
            for line in env_path.read_text().splitlines():
                if line.startswith("DASHSCOPE_API_KEY="):
                    api_key = line.split("=", 1)[1].strip().strip('"')
                    break

    if not api_key and not args.dry_run:
        print("❌ 需要设置 DASHSCOPE_API_KEY 环境变量或在 .env 中配置")
        sys.exit(1)

    client = None
    if not args.dry_run:
        client = OpenAI(
            api_key=api_key,
            base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
        )

    intents = list(SEED_PHRASES.items())
    if args.limit > 0:
        intents = intents[:args.limit]

    print(f"📋 待处理: {len(intents)} 个意图, 每个生成 {args.count} 个变体")
    print(f"{'=' * 60}")

    all_java = []
    all_sql = []
    total_generated = 0

    for intent_code, seeds in intents:
        print(f"\n🔄 {intent_code} (种子: {len(seeds)} 条)")
        for s in seeds:
            print(f"   📌 {s}")

        if args.dry_run:
            print("   ⏭️  dry-run, 跳过 API 调用")
            continue

        synonyms = generate_synonyms(client, intent_code, seeds, args.count)
        synonyms = deduplicate(synonyms, seeds, set())
        total_generated += len(synonyms)

        print(f"   ✅ 生成 {len(synonyms)} 个变体:")
        for s in synonyms[:5]:
            print(f"      • {s}")
        if len(synonyms) > 5:
            print(f"      ... 等共 {len(synonyms)} 个")

        all_java.append(output_java(intent_code, synonyms))
        all_sql.append(output_sql(intent_code, synonyms))

        # Rate limit
        time.sleep(1)

    if not args.dry_run and total_generated > 0:
        # Write output files
        output_dir = Path(__file__).parent.parent / "scripts"

        java_path = output_dir / f"{args.output}.java"
        java_path.write_text("\n\n".join(all_java), encoding="utf-8")
        print(f"\n📄 Java 代码片段: {java_path}")

        sql_path = output_dir / f"{args.output}.sql"
        sql_path.write_text("\n".join(all_sql), encoding="utf-8")
        print(f"📄 SQL 语句: {sql_path}")

        print(f"\n{'=' * 60}")
        print(f"✅ 共生成 {total_generated} 个同义变体")
        print("\n使用方式:")
        print(f"  1. 将 {java_path.name} 中的代码粘贴到 IntentKnowledgeBase.initPhraseMappingsPart2()")
        print(f"  2. 或执行 {sql_path.name} 写入数据库")


if __name__ == "__main__":
    main()
