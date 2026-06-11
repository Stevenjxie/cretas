#!/usr/bin/env python3
"""
LLM 辅助 NER 标注脚本 — 食品领域 13 类实体

使用 Qwen API (通义千问) 对输入句子进行预标注，输出 BIO 格式。

实体类型 (13类):
  ADDITIVE      食品添加剂       (柠檬酸、山梨酸钾)
  STANDARD      标准编号         (GB2760-2024)
  EQUIPMENT     设备名称         (杀菌釜、均质机)
  PROCESS_PARAM 工艺参数         (121℃/15min、pH 4.6)
  INGREDIENT    原料/配料        (大豆、脱脂奶粉)
  MICROBE       微生物           (沙门氏菌、大肠杆菌)
  HAZARD        危害物质         (黄曲霉毒素B1、重金属铅)
  TEST_METHOD   检测方法         (凯氏定氮法、GB5009.3)
  PRODUCT       产品名称         (酸奶、即食海参)
  CERT          认证/许可        (SC认证、ISO22000、HACCP)
  REGULATION    法规名称         (食品安全法、GB14881)
  NUTRIENT      营养素           (蛋白质、维生素C、钙)
  ORG           组织/机构        (国家市场监管总局、FDA)

BIO 标签简写:
  B-ADD, I-ADD, B-STD, I-STD, B-EQP, I-EQP, B-PRM, I-PRM,
  B-ING, I-ING, B-MIC, I-MIC, B-HAZ, I-HAZ, B-TST, I-TST,
  B-PRD, I-PRD, B-CRT, I-CRT, B-REG, I-REG, B-NUT, I-NUT,
  B-ORG, I-ORG, O

使用方式:
  python auto_annotate_ner.py --input sentences.txt --output ner_data.jsonl
  python auto_annotate_ner.py --input sentences.txt --output ner_data.jsonl --batch-size 5
"""

import argparse
import json
import logging
import os
import re
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# 日志配置
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("auto_annotate_ner")

# ---------------------------------------------------------------------------
# 常量
# ---------------------------------------------------------------------------
DEFAULT_BATCH_SIZE = 10
DEFAULT_MODEL = "qwen3.7-max-2026-06-08"  # free quota ⚠️ (qwen3.5-122b-a10b is PAID — billing incident 2026-06-11)
MAX_RETRIES = 3
RETRY_DELAY = 2.0

# 实体类型 → BIO 标签前缀映射
ENTITY_TYPE_MAP = {
    "ADDITIVE": "ADD",
    "STANDARD": "STD",
    "EQUIPMENT": "EQP",
    "PROCESS_PARAM": "PRM",
    "INGREDIENT": "ING",
    "MICROBE": "MIC",
    "HAZARD": "HAZ",
    "TEST_METHOD": "TST",
    "PRODUCT": "PRD",
    "CERT": "CRT",
    "REGULATION": "REG",
    "NUTRIENT": "NUT",
    "ORG": "ORG",
}

# BIO 标签全集 (含 O)
ALL_BIO_LABELS = ["O"]
for prefix in ENTITY_TYPE_MAP.values():
    ALL_BIO_LABELS.extend([f"B-{prefix}", f"I-{prefix}"])
ALL_BIO_LABELS.sort()

# ---------------------------------------------------------------------------
# LLM 系统提示词
# ---------------------------------------------------------------------------
SYSTEM_PROMPT = """你是一个食品领域的命名实体识别(NER)专家标注员。

## 任务
对输入的中文句子进行命名实体标注。你需要识别句子中属于以下13类的实体：

## 实体类型定义

1. **ADDITIVE** (食品添加剂): 食品添加剂名称
   - 示例: 柠檬酸、山梨酸钾、苯甲酸钠、阿斯巴甜、卡拉胶、硝酸钠、亚硫酸钠
   - 注意: 只标注具体添加剂名称，不标注"食品添加剂"这个泛称

2. **STANDARD** (标准编号): 国家标准、行业标准编号
   - 示例: GB2760-2024、GB/T 22000、NY/T 1172、SB/T 10439
   - 注意: 必须含有标准编号格式(字母+数字)

3. **EQUIPMENT** (设备名称): 食品加工/检测设备
   - 示例: 杀菌釜、均质机、冻干机、金属探测器、X射线异物检测仪、巴氏杀菌机

4. **PROCESS_PARAM** (工艺参数): 温度、时间、压力、浓度等加工参数
   - 示例: 121℃/15min、pH 4.6、水分活度0.85、0.1MPa、-18℃以下、72℃/15s
   - 注意: 必须包含具体数值

5. **INGREDIENT** (原料/配料): 食品原料和配料
   - 示例: 大豆、脱脂奶粉、全麦粉、棕榈油、转化糖浆、鱼胶原蛋白

6. **MICROBE** (微生物): 微生物名称
   - 示例: 沙门氏菌、大肠杆菌O157:H7、金黄色葡萄球菌、李斯特菌、霉菌、酵母菌

7. **HAZARD** (危害物质): 化学性/物理性/生物性危害物
   - 示例: 黄曲霉毒素B1、重金属铅、苯并芘、丙烯酰胺、二噁英、3-MCPD

8. **TEST_METHOD** (检测方法): 具体检测方法名称
   - 示例: 凯氏定氮法、高效液相色谱法(HPLC)、PCR法、酶联免疫吸附测定法(ELISA)

9. **PRODUCT** (产品名称): 具体食品产品
   - 示例: 酸奶、即食海参、方便面、婴幼儿配方奶粉、冷冻水饺、火腿肠

10. **CERT** (认证/许可): 认证、许可、资质
    - 示例: SC认证、ISO22000、HACCP认证、绿色食品认证、有机认证、QS标志

11. **REGULATION** (法规名称): 法律法规名称
    - 示例: 食品安全法、农产品质量安全法、食品安全法实施条例

12. **NUTRIENT** (营养素): 营养成分
    - 示例: 蛋白质、维生素C、钙、膳食纤维、DHA、叶酸、铁

13. **ORG** (组织/机构): 监管机构、行业组织
    - 示例: 国家市场监管总局、FDA、WHO、中国食品工业协会、疾控中心

## 输出格式

对每个句子，输出一个JSON对象，包含：
- "entities": 数组，每个元素为 {"text": "实体文本", "type": "实体类型", "start": 起始字符位置, "end": 结束字符位置}

位置从0开始计数，end是最后一个字符的下一个位置。

## 注意事项
- 实体不重叠
- 优先标注最长匹配（"黄曲霉毒素B1"整体标注，而非拆开）
- 数值+单位组合的参数完整标注（"121℃/15min"整体）
- 标准编号完整标注，含年份（"GB2760-2024"）
- 如果句子中没有任何实体，entities为空数组

## 示例

输入: "根据GB2760-2024，酸奶中山梨酸钾的最大使用量为0.075g/kg。"
输出:
```json
{"entities": [{"text": "GB2760-2024", "type": "STANDARD", "start": 2, "end": 13}, {"text": "酸奶", "type": "PRODUCT", "start": 14, "end": 16}, {"text": "山梨酸钾", "type": "ADDITIVE", "start": 17, "end": 21}, {"text": "0.075g/kg", "type": "PROCESS_PARAM", "start": 27, "end": 36}]}
```

现在请对以下句子进行标注。每个句子一行，请对每个句子分别输出一个JSON对象，每行一个。"""


# ---------------------------------------------------------------------------
# Qwen API 调用
# ---------------------------------------------------------------------------

def create_api_client() -> Any:
    """
    创建 API 客户端。优先使用 dashscope，回退到 openai 兼容模式。
    """
    api_key = os.environ.get("DASHSCOPE_API_KEY") or os.environ.get("QWEN_API_KEY")
    if not api_key:
        logger.error(
            "未设置 API Key。请设置环境变量 DASHSCOPE_API_KEY 或 QWEN_API_KEY"
        )
        sys.exit(1)

    # 尝试 dashscope SDK
    try:
        import dashscope
        dashscope.api_key = api_key
        logger.info("使用 dashscope SDK")
        return {"type": "dashscope", "api_key": api_key}
    except ImportError:
        pass

    # 回退到 openai 兼容接口
    try:
        from openai import OpenAI
        client = OpenAI(
            api_key=api_key,
            base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
        )
        logger.info("使用 OpenAI 兼容模式")
        return {"type": "openai", "client": client}
    except ImportError:
        logger.error("请安装 dashscope 或 openai 包: pip install dashscope openai")
        sys.exit(1)


def call_llm(
    api_client: Dict,
    sentences: List[str],
    model: str = DEFAULT_MODEL,
) -> Optional[str]:
    """
    调用 LLM API 进行 NER 标注。
    """
    user_content = "\n".join(f"句子{i+1}: {s}" for i, s in enumerate(sentences))

    for attempt in range(MAX_RETRIES):
        try:
            if api_client["type"] == "dashscope":
                import dashscope
                from dashscope import Generation

                response = Generation.call(
                    model=model,
                    messages=[
                        {"role": "system", "content": SYSTEM_PROMPT},
                        {"role": "user", "content": user_content},
                    ],
                    result_format="message",
                    temperature=0.1,
                    max_tokens=4096,
                )
                if response.status_code == 200:
                    return response.output.choices[0].message.content
                else:
                    logger.warning(
                        "dashscope API 返回错误 (attempt %d/%d): %s",
                        attempt + 1, MAX_RETRIES, response.message,
                    )
            elif api_client["type"] == "openai":
                client = api_client["client"]
                response = client.chat.completions.create(
                    model=model,
                    messages=[
                        {"role": "system", "content": SYSTEM_PROMPT},
                        {"role": "user", "content": user_content},
                    ],
                    temperature=0.1,
                    max_tokens=4096,
                )
                return response.choices[0].message.content

        except Exception as exc:
            logger.warning(
                "API 调用异常 (attempt %d/%d): %s",
                attempt + 1, MAX_RETRIES, exc,
            )

        if attempt < MAX_RETRIES - 1:
            time.sleep(RETRY_DELAY * (attempt + 1))

    return None


# ---------------------------------------------------------------------------
# 标注解析与转换
# ---------------------------------------------------------------------------

def parse_llm_response(response_text: str, sentences: List[str]) -> List[Optional[Dict]]:
    """
    解析 LLM 的 JSON 响应，提取实体标注。
    返回与 sentences 等长的列表，每个元素为 entities dict 或 None（解析失败）。
    """
    results: List[Optional[Dict]] = []

    # 尝试从响应中提取多个 JSON 对象
    json_objects = []
    # 匹配 ```json ... ``` 代码块
    code_blocks = re.findall(r"```json?\s*\n?(.*?)```", response_text, re.DOTALL)
    if code_blocks:
        for block in code_blocks:
            for line in block.strip().split("\n"):
                line = line.strip()
                if line.startswith("{"):
                    try:
                        json_objects.append(json.loads(line))
                    except json.JSONDecodeError:
                        pass
    else:
        # 直接按行解析 JSON
        for line in response_text.strip().split("\n"):
            line = line.strip()
            if line.startswith("{"):
                try:
                    json_objects.append(json.loads(line))
                except json.JSONDecodeError:
                    pass

    # 如果整体是一个 JSON 数组
    if not json_objects:
        try:
            parsed = json.loads(response_text.strip())
            if isinstance(parsed, list):
                json_objects = parsed
            elif isinstance(parsed, dict):
                json_objects = [parsed]
        except json.JSONDecodeError:
            pass

    # 匹配句子
    for i in range(len(sentences)):
        if i < len(json_objects):
            results.append(json_objects[i])
        else:
            results.append(None)

    return results


def entities_to_bio(sentence: str, entities: List[Dict]) -> List[str]:
    """
    将实体列表转换为 BIO 标签序列（字级别）。

    Args:
        sentence: 原始句子
        entities: [{"text": "...", "type": "ADDITIVE", "start": 0, "end": 3}]

    Returns:
        与句子等长的 BIO 标签列表
    """
    labels = ["O"] * len(sentence)

    # 按 start 排序，确保无重叠
    sorted_entities = sorted(entities, key=lambda e: e.get("start", 0))

    occupied = set()
    for ent in sorted_entities:
        ent_type = ent.get("type", "")
        start = ent.get("start", -1)
        end = ent.get("end", -1)
        ent_text = ent.get("text", "")

        # 验证实体类型
        if ent_type not in ENTITY_TYPE_MAP:
            logger.debug("未知实体类型: %s, 跳过", ent_type)
            continue

        bio_prefix = ENTITY_TYPE_MAP[ent_type]

        # 位置修正: 如果 start/end 不匹配文本，尝试搜索
        if start >= 0 and end > start and end <= len(sentence):
            actual_text = sentence[start:end]
            if actual_text != ent_text and ent_text:
                # 搜索正确位置
                found = sentence.find(ent_text)
                if found >= 0:
                    start = found
                    end = found + len(ent_text)
                else:
                    logger.debug("实体 '%s' 在句子中未找到，跳过", ent_text)
                    continue
        elif ent_text:
            # 无位置信息，通过文本搜索
            found = sentence.find(ent_text)
            if found >= 0:
                start = found
                end = found + len(ent_text)
            else:
                logger.debug("实体 '%s' 在句子中未找到，跳过", ent_text)
                continue
        else:
            continue

        # 检查重叠
        span = set(range(start, end))
        if span & occupied:
            logger.debug("实体 '%s' 与已标注区域重叠，跳过", ent_text)
            continue

        # 写入 BIO 标签
        labels[start] = f"B-{bio_prefix}"
        for j in range(start + 1, end):
            labels[j] = f"I-{bio_prefix}"
        occupied.update(span)

    return labels


def validate_bio_labels(sentence: str, labels: List[str]) -> Tuple[bool, List[str]]:
    """
    验证 BIO 标签的合法性。

    检查:
      - 标签数量与句子长度匹配
      - I- 标签前必须有对应的 B- 或 I- 标签
      - 所有标签属于已知标签集

    Returns:
        (is_valid, error_messages)
    """
    errors = []

    if len(labels) != len(sentence):
        errors.append(
            f"标签数量 ({len(labels)}) 与句子长度 ({len(sentence)}) 不匹配"
        )
        return False, errors

    for i, label in enumerate(labels):
        if label not in ALL_BIO_LABELS:
            errors.append(f"位置 {i}: 未知标签 '{label}'")
            continue

        if label.startswith("I-"):
            suffix = label[2:]
            if i == 0 or (
                labels[i - 1] != f"B-{suffix}" and labels[i - 1] != f"I-{suffix}"
            ):
                errors.append(
                    f"位置 {i}: I-{suffix} 前无对应的 B-{suffix} 或 I-{suffix}"
                )

    return len(errors) == 0, errors


# ---------------------------------------------------------------------------
# 批处理
# ---------------------------------------------------------------------------

def process_batch(
    api_client: Dict,
    sentences: List[str],
    model: str,
) -> List[Optional[Dict]]:
    """
    处理一个批次的句子，返回标注结果。
    """
    response_text = call_llm(api_client, sentences, model=model)
    if response_text is None:
        logger.error("LLM 返回空响应，批次全部失败")
        return [None] * len(sentences)

    parsed = parse_llm_response(response_text, sentences)
    results = []

    for i, (sent, ent_dict) in enumerate(zip(sentences, parsed)):
        if ent_dict is None:
            logger.warning("句子 %d 解析失败: %s", i, sent[:30])
            results.append(None)
            continue

        entities = ent_dict.get("entities", [])
        labels = entities_to_bio(sent, entities)

        is_valid, errors = validate_bio_labels(sent, labels)
        if not is_valid:
            for err in errors:
                logger.warning("句子 %d 标签验证错误: %s", i, err)
            # 尝试修正: 将非法 I- 改为 B-
            for j, label in enumerate(labels):
                if label.startswith("I-"):
                    suffix = label[2:]
                    if j == 0 or (
                        labels[j - 1] != f"B-{suffix}"
                        and labels[j - 1] != f"I-{suffix}"
                    ):
                        labels[j] = f"B-{suffix}"

        results.append({
            "text": sent,
            "labels": labels,
            "entities": entities,
        })

    return results


# ---------------------------------------------------------------------------
# 统计
# ---------------------------------------------------------------------------

def compute_statistics(records: List[Dict]) -> Dict:
    """
    计算标注统计信息。
    """
    total_sentences = len(records)
    total_entities = 0
    type_counts: Dict[str, int] = {}
    empty_count = 0

    for rec in records:
        entities = rec.get("entities", [])
        if not entities:
            empty_count += 1
        total_entities += len(entities)
        for ent in entities:
            etype = ent.get("type", "UNKNOWN")
            type_counts[etype] = type_counts.get(etype, 0) + 1

    return {
        "total_sentences": total_sentences,
        "total_entities": total_entities,
        "avg_entities_per_sentence": (
            round(total_entities / total_sentences, 2) if total_sentences > 0 else 0
        ),
        "empty_sentences": empty_count,
        "entity_type_distribution": dict(sorted(type_counts.items(), key=lambda x: -x[1])),
    }


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="LLM 辅助食品领域 NER 标注脚本 (13类实体, BIO格式)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--input", "-i", type=str, required=True,
        help="输入文件路径 (每行一个句子)",
    )
    parser.add_argument(
        "--output", "-o", type=str, required=True,
        help="输出 JSONL 文件路径",
    )
    parser.add_argument(
        "--batch-size", type=int, default=DEFAULT_BATCH_SIZE,
        help=f"每批处理句子数 (默认: {DEFAULT_BATCH_SIZE})",
    )
    parser.add_argument(
        "--model", type=str, default=DEFAULT_MODEL,
        help=f"Qwen 模型名称 (默认: {DEFAULT_MODEL})",
    )
    parser.add_argument(
        "--max-sentences", type=int, default=0,
        help="最大处理句子数 (0=不限制)",
    )
    parser.add_argument(
        "--stats-output", type=str, default=None,
        help="统计信息输出路径 (JSON)",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true",
        help="启用详细日志",
    )
    args = parser.parse_args()

    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)

    # 读取输入句子
    input_path = Path(args.input)
    if not input_path.exists():
        logger.error("输入文件不存在: %s", input_path)
        sys.exit(1)

    sentences = []
    with open(input_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                sentences.append(line)

    if args.max_sentences > 0:
        sentences = sentences[: args.max_sentences]

    logger.info("读取 %d 个句子 (from %s)", len(sentences), input_path)

    if not sentences:
        logger.error("输入文件为空或所有行均为注释")
        sys.exit(1)

    # 初始化 API 客户端
    api_client = create_api_client()

    # 批处理
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    all_records: List[Dict] = []
    failed_count = 0
    batch_count = 0

    logger.info("=" * 60)
    logger.info("开始 NER 标注")
    logger.info("模型: %s", args.model)
    logger.info("批次大小: %d", args.batch_size)
    logger.info("总句子数: %d", len(sentences))
    logger.info("=" * 60)

    with open(output_path, "w", encoding="utf-8") as out_f:
        for start_idx in range(0, len(sentences), args.batch_size):
            batch = sentences[start_idx : start_idx + args.batch_size]
            batch_count += 1

            logger.info(
                "处理批次 %d/%d (句子 %d-%d)",
                batch_count,
                (len(sentences) + args.batch_size - 1) // args.batch_size,
                start_idx + 1,
                min(start_idx + len(batch), len(sentences)),
            )

            results = process_batch(api_client, batch, model=args.model)

            for result in results:
                if result is None:
                    failed_count += 1
                    continue

                # 输出 BIO 格式 JSONL (不含 entities 中间结果)
                output_record = {
                    "text": result["text"],
                    "labels": result["labels"],
                }
                out_f.write(json.dumps(output_record, ensure_ascii=False) + "\n")
                all_records.append(result)

            # 批次间延时 (避免 API 限流)
            if start_idx + args.batch_size < len(sentences):
                time.sleep(1.0)

    # 统计
    stats = compute_statistics(all_records)
    stats["failed_count"] = failed_count

    logger.info("=" * 60)
    logger.info("标注完成!")
    logger.info("成功: %d, 失败: %d", len(all_records), failed_count)
    logger.info("总实体数: %d", stats["total_entities"])
    logger.info("平均每句实体数: %.2f", stats["avg_entities_per_sentence"])
    logger.info("无实体句子数: %d", stats["empty_sentences"])
    logger.info("实体类型分布:")
    for etype, count in stats["entity_type_distribution"].items():
        logger.info("  %s: %d", etype, count)
    logger.info("输出文件: %s", output_path.resolve())
    logger.info("=" * 60)

    # 保存统计信息
    if args.stats_output:
        stats_path = Path(args.stats_output)
        stats_path.parent.mkdir(parents=True, exist_ok=True)
        with open(stats_path, "w", encoding="utf-8") as f:
            json.dump(stats, f, ensure_ascii=False, indent=2)
        logger.info("统计信息已保存: %s", stats_path.resolve())


if __name__ == "__main__":
    main()
