"""
操作手册入库服务
Ingests operation manual HTML + markdown docs into food_kb for RAG retrieval.

Usage:
    python -m food_kb.services.manual_ingester
"""

import asyncio
import logging
import re
from pathlib import Path
from typing import List, Dict

logger = logging.getLogger(__name__)

# Project root (5 levels up from this file)
PROJECT_ROOT = Path(__file__).resolve().parents[4]

# Sources to ingest
MANUAL_SOURCES = [
    {
        "path": "docs/plans/factory-operation-manual.html",
        "title_prefix": "工厂操作手册",
        "source": "factory-operation-manual.html",
        "type": "html",
        "subcategory": "factory",
    },
    {
        "path": "docs/plans/restaurant-metrics-glossary.html",
        "title_prefix": "餐饮指数字典",
        "source": "restaurant-metrics-glossary.html",
        "type": "html",
        "subcategory": "restaurant",
    },
    {
        "path": "docs/plans/restaurant-product-manual.html",
        "title_prefix": "餐饮产品使用手册",
        "source": "restaurant-product-manual.html",
        "type": "html",
        "subcategory": "restaurant",
    },
    {
        "path": "docs/plans/factory-requisition-detailed-flow.md",
        "title_prefix": "工厂下单详细流程",
        "source": "factory-requisition-detailed-flow.md",
        "type": "markdown",
        "subcategory": "factory",
    },
    {
        "path": "docs/plans/factory-requisition-operation-guide.md",
        "title_prefix": "工厂下单操作指南",
        "source": "factory-requisition-operation-guide.md",
        "type": "markdown",
        "subcategory": "factory",
    },
    {
        "path": "docs/manual/liushanmen-production/index.html",
        "title_prefix": "六扇门生产链操作手册",
        "source": "liushanmen-production-manual.html",
        "type": "chapter_section",
        "subcategory": "factory",
    },
]


def parse_html_to_sections(html_content: str) -> List[Dict[str, str]]:
    """Parse HTML file into sections split by h2/h3 headers, with h1 chapter context.

    Chunk title format: "[chapter短标签] subsection | metric" so that LLM sees the
    full hierarchical path even though h1 itself doesn't trigger a section split.
    Solves audit W1 — previously h1 was ignored, chunk title lost chapter context.

    Examples:
    - 财务健康 | 1.2 成本结构 | 食材成本率 (Food Cost %)
    - 真实业务细颗粒 | 12.1 支付与结算 | 支付方式分布
    """
    try:
        from bs4 import BeautifulSoup
    except ImportError:
        logger.error("beautifulsoup4 not installed. Run: pip install beautifulsoup4")
        raise

    soup = BeautifulSoup(html_content, "html.parser")

    # Remove script and style tags
    for tag in soup.find_all(["script", "style"]):
        tag.decompose()

    def _shorten_chapter(h1_text: str) -> str:
        """Extract concise chapter label from h1.
        '第 1 章 · 财务健康 (Financial Health)' → '财务健康'
        '附录 · 模块入口对照表' → '附录'
        '白垩纪餐饮指数字典' (file root) → ''  (no prefix for top-level)
        """
        # Remove "第 X 章 · " prefix
        text = re.sub(r"^第\s*\d+\s*章\s*[·\-：:]\s*", "", h1_text)
        # Remove "(English Name)" suffix
        text = re.sub(r"\s*\([^)]*\)\s*$", "", text)
        # Remove " · " sub-titles (元章节)
        text = text.split(" · ")[0].strip()
        # Skip top-level title (the document name)
        if text == "白垩纪餐饮指数字典":
            return ""
        return text.strip()

    def _build_title(chapter: str, subsection: str, metric: str) -> str:
        parts = [p for p in [chapter, subsection, metric] if p]
        return " | ".join(parts) if parts else "概述"

    sections = []
    current_chapter = ""  # h1 short label
    current_subsection = ""  # h2 label
    current_metric = "概述"  # h3 label (or h2 if no h3 yet)
    current_content_parts = []

    def _flush():
        if current_content_parts:
            text = "\n".join(current_content_parts).strip()
            if text:
                title = _build_title(current_chapter, current_subsection, current_metric)
                sections.append({"title": title, "content": text})

    for element in soup.find_all(["h1", "h2", "h3", "p", "div", "ul", "ol", "table", "li"]):
        if element.name == "h1":
            _flush()
            raw = element.get_text(separator=" ", strip=True)
            current_chapter = _shorten_chapter(raw)
            current_subsection = ""
            current_metric = "概述"
            current_content_parts = []
        elif element.name == "h2":
            _flush()
            raw = element.get_text(separator=" ", strip=True)
            current_subsection = re.sub(r"\s*白垩纪默认\s*$", "", raw).strip()
            current_metric = ""  # h2 title used as metric placeholder; will be overridden by next h3
            current_content_parts = []
        elif element.name == "h3":
            _flush()
            raw = element.get_text(separator=" ", strip=True)
            current_metric = re.sub(r"\s*白垩纪默认\s*$", "", raw).strip()
            current_content_parts = []
        else:
            text = element.get_text(separator=" ", strip=True)
            if text and len(text) > 5:
                current_content_parts.append(text)

    # Last section
    _flush()

    return sections


def parse_chapter_section_html_to_sections(html_content: str) -> List[Dict[str, str]]:
    """Parse HTML files that use <section class="chapter"> structure instead of h2/h3 headings.

    Used for liushanmen-production-manual.html which organises chapters as:
        <section class="chapter" id="chN">
          <div class="chapter-header">
            <div class="chapter-title">...</div>
            <div class="chapter-subtitle">...</div>
          </div>
          <div class="chapter-body">...content...</div>
        </section>

    Each <section class="chapter"> becomes one RAG section.  The document h1
    title is captured as a prefix so chunk titles have full context:
        "六扇门 — 生产链操作手册 | 批次详情报工（领料 + 产出两点）"
    """
    try:
        from bs4 import BeautifulSoup
    except ImportError:
        logger.error("beautifulsoup4 not installed. Run: pip install beautifulsoup4")
        raise

    soup = BeautifulSoup(html_content, "html.parser")

    # Remove script and style tags
    for tag in soup.find_all(["script", "style"]):
        tag.decompose()

    # Extract document title from h1
    h1 = soup.find("h1")
    doc_title = h1.get_text(separator=" ", strip=True) if h1 else ""

    sections = []

    for section in soup.find_all("section", class_="chapter"):
        # Extract chapter title and subtitle
        title_el = section.find(class_="chapter-title")
        subtitle_el = section.find(class_="chapter-subtitle")

        chapter_title = title_el.get_text(separator=" ", strip=True) if title_el else ""
        chapter_subtitle = subtitle_el.get_text(separator=" ", strip=True) if subtitle_el else ""

        if not chapter_title:
            continue

        # Build a rich section title
        section_title = chapter_title
        if doc_title:
            section_title = f"{doc_title} | {chapter_title}"

        # Extract all body text, skipping screenshot captions (mostly visual context)
        body = section.find(class_="chapter-body") or section
        content_parts = []

        # Add subtitle as first content line if present (contains menu path)
        if chapter_subtitle:
            content_parts.append(f"菜单路径: {chapter_subtitle}")

        for el in body.find_all(["p", "li", "td", "th", "div"]):
            # Skip decorative/nav elements
            el_classes = el.get("class", [])
            if any(c in el_classes for c in ["screenshot-caption", "screenshot-wrap", "back-top", "chapter-num", "chapter-title", "chapter-subtitle", "chapter-header"]):
                continue
            # Skip nested elements whose parent is already a text container (avoid duplicates)
            if el.name in ("p", "li") and el.find_parent(["td", "li"]):
                continue
            text = el.get_text(separator=" ", strip=True)
            if text and len(text) > 8:
                content_parts.append(text)

        content = "\n".join(content_parts).strip()
        if content:
            sections.append({"title": section_title, "content": content})

    return sections


def parse_markdown_to_sections(md_content: str) -> List[Dict[str, str]]:
    """Parse markdown into sections split by ## or ### headers."""
    sections = []
    current_title = "概述"
    current_lines = []

    for line in md_content.split("\n"):
        header_match = re.match(r"^(#{2,3})\s+(.+)", line)
        if header_match:
            if current_lines:
                text = "\n".join(current_lines).strip()
                if text:
                    sections.append({"title": current_title, "content": text})
            current_title = header_match.group(2).strip()
            current_lines = []
        else:
            current_lines.append(line)

    if current_lines:
        text = "\n".join(current_lines).strip()
        if text:
            sections.append({"title": current_title, "content": text})

    return sections


async def ingest_all():
    """Ingest all operation manual sources into the knowledge base."""
    from smartbi.config import get_settings
    from food_kb.services.document_ingester import get_document_ingester, CHUNK_CONFIG
    from food_kb.services.embedding import configure as configure_embedding, get_embedding

    # Add operation_manual chunk config
    CHUNK_CONFIG["operation_manual"] = {
        "max_chars": 1200,
        "overlap": 200,
        "split_pattern": r"\n(?=#{2,3}\s)",
    }

    settings = get_settings()

    # Configure embedding
    configure_embedding(
        api_key=settings.llm_api_key,
        base_url=settings.llm_base_url,
        model=settings.food_kb_embedding_model,
        dims=settings.food_kb_embedding_dims,
    )

    # Initialize ingester
    ingester = get_document_ingester()
    await ingester.initialize(settings.food_kb_db_url, embedding_fn=get_embedding)

    if not ingester.is_ready():
        logger.error("Document ingester failed to initialize")
        return

    total_chunks = 0
    total_docs = 0

    for source_info in MANUAL_SOURCES:
        file_path = PROJECT_ROOT / source_info["path"]
        if not file_path.exists():
            logger.warning(f"Source file not found: {file_path}")
            continue

        logger.info(f"Processing: {file_path.name}")
        content = file_path.read_text(encoding="utf-8")

        if source_info["type"] == "html":
            sections = parse_html_to_sections(content)
        elif source_info["type"] == "chapter_section":
            sections = parse_chapter_section_html_to_sections(content)
        else:
            sections = parse_markdown_to_sections(content)

        logger.info(f"  Parsed {len(sections)} sections from {file_path.name}")

        # ATOMIC SWAP PATTERN (round-3 audit A2):
        # 1. Ingest new chunks under TEMP source name (.NEW suffix)
        # 2. If any ingest fails → cleanup temp, skip swap, KB retains old chunks intact
        # 3. If all succeed → atomic transaction: DELETE old WHERE source=canonical
        #    + UPDATE source=canonical WHERE source=temp
        # This prevents the half-empty-KB risk if embedding API hangs mid-ingest.
        canonical_source = source_info["source"]
        temp_source = f"{canonical_source}.NEW"

        # Pre-cleanup any orphan .NEW from previous failed run
        # Uses public `delete_by_source` (batch-3 audit A1: replaced raw _pool access)
        await ingester.delete_by_source(temp_source)

        source_chunks = 0
        source_docs = 0
        ingest_failed = False

        for section in sections:
            title = f"{source_info['title_prefix']} - {section['title']}"
            result = await ingester.ingest_document(
                title=title,
                content=section["content"],
                category="operation_manual",
                source=temp_source,  # Ingest under temp name
                version="1.0",
                operator="manual_ingester",
                subcategory=source_info.get("subcategory"),
            )
            if result.get("success"):
                source_chunks += result.get("chunk_count", 0)
                source_docs += 1
            else:
                logger.error(
                    f"  Failed to ingest section '{title}': {result.get('error')}"
                )
                ingest_failed = True
                break  # Abort ingest for this source

        if ingest_failed:
            # Rollback: drop temp chunks. Old canonical chunks stay intact.
            await ingester.delete_by_source(temp_source)
            logger.error(
                f"  Aborted ingest for source '{canonical_source}'. "
                f"Cleaned up temp. KB retains previous chunks (no data loss)."
            )
            continue

        # All sections ingested OK. Atomic swap inside single transaction.
        # Uses public `pool` property (batch-3 audit A1: replaced raw _pool access)
        async with ingester.pool.acquire() as conn:
            async with conn.transaction():
                old_deleted = await conn.execute(
                    "DELETE FROM food_knowledge_documents WHERE source = $1",
                    canonical_source,
                )
                await conn.execute(
                    "UPDATE food_knowledge_documents SET source = $1 WHERE source = $2",
                    canonical_source,
                    temp_source,
                )
            try:
                old_count = (
                    int(old_deleted.split()[1])
                    if old_deleted and old_deleted.startswith("DELETE")
                    else 0
                )
            except (IndexError, ValueError, AttributeError, TypeError):
                old_count = 0

        logger.info(
            f"  Atomically swapped to {source_docs} sections / {source_chunks} chunks "
            f"for '{canonical_source}' (replaced {old_count} prior chunks)"
        )

        # Reviewer R2 N1: chunk budget [4, 12] warn for restaurant-product-manual chapters
        # Soft monitoring — log only, no hard block. Heuristic: avg = total_chunks / num_sections.
        # source_docs counts sections (h2/h3 splits), not strictly h1 chapters; approximation
        # good enough for warn signal.
        if canonical_source == "restaurant-product-manual.html":
            avg_chunks_per_chapter = source_chunks / max(1, source_docs)
            if avg_chunks_per_chapter > 12:
                logger.warning(
                    f"  ⚠️  chunk budget exceeded: {canonical_source} "
                    f"avg {avg_chunks_per_chapter:.1f} chunks/chapter (target ≤12). "
                    f"Consider merging h2 sections in Tier 1 chapters."
                )
            elif avg_chunks_per_chapter < 4:
                logger.warning(
                    f"  ⚠️  chunk budget under-floor: {canonical_source} "
                    f"avg {avg_chunks_per_chapter:.1f} chunks/chapter (target ≥4). "
                    f"Tier 2-5 skeleton chapters too thin."
                )

        total_docs += source_docs
        total_chunks += source_chunks

    logger.info(f"Ingestion complete: {total_docs} documents, {total_chunks} chunks")

    await ingester.close()


if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    )
    asyncio.run(ingest_all())
