#!/usr/bin/env python3
"""Fetch priority HJ help manual articles via curl + extract text.

Priority strategy:
- Sales (full chapter 73): top 30 most relevant (订单/出库/发票/回款/退货/送货/对账/常见问题/参数)
- Purchase (full 41): all
- Warehouse (38): all
- Finance (128, biggest): top 40 (凭证/应收应付/发票/收款/对账/参数)
- Production (78): top 25
- Engineering (25, BOM): all
- Quality (24): all
- HR (90): top 20 (工资/考勤/请假/报销)
- System (63): all (workflow/RBAC/print/参数)
- Customer (39): all
- Cloud accounting (44): top 10 (新发现)
- Skip: 委外 48 / 办公自动化 85

Target: ~350 articles fetched.
"""
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).parent
ARTICLES_DIR = ROOT / 'help-articles'
ARTICLES_DIR.mkdir(exist_ok=True)
INVENTORY = ROOT / 'help-tree-flat.json'
INDEX = ROOT / 'help-articles-index.md'

PRIORITY_CHAPTERS = {
    '销售管理': 73,    # full
    '采购管理': 41,    # full
    '仓库管理': 38,    # full
    '财务管理': 128,   # full (largest, includes 凭证/应收/应付/发票/收款/记账)
    '生产管理': 78,    # full
    '工程管理': 25,    # full (BOM 在此)
    '品质管理': 24,    # full
    '人力资源': 90,    # full
    '系统管理': 63,    # full (workflow/RBAC/print)
    '客户管理': 39,    # full
    '宏见云记账': 44,  # full (Round 11 漏)
    '软件使用': 4,     # full
}
SKIP_CHAPTERS = ['委外管理', '办公自动化']

BASE = 'https://help.hongjian.com/show/right.jsp?company=hzx&item=erp&id='

def extract_text(html: str) -> str:
    """Strip HTML to readable text, keep structure."""
    # Remove script/style
    html = re.sub(r'<script[^>]*>.*?</script>', '', html, flags=re.DOTALL | re.IGNORECASE)
    html = re.sub(r'<style[^>]*>.*?</style>', '', html, flags=re.DOTALL | re.IGNORECASE)
    # Replace common block tags with newlines
    html = re.sub(r'</(p|div|tr|li|h\d|br)[^>]*>', '\n', html, flags=re.IGNORECASE)
    html = re.sub(r'<br\s*/?>', '\n', html, flags=re.IGNORECASE)
    # Strip all remaining tags
    text = re.sub(r'<[^>]+>', '', html)
    # Decode common entities
    text = text.replace('&nbsp;', ' ').replace('&amp;', '&').replace('&lt;', '<').replace('&gt;', '>')
    text = text.replace('&quot;', '"').replace('&#39;', "'")
    # Collapse whitespace
    text = re.sub(r'\n\s*\n+', '\n\n', text)
    text = re.sub(r' +', ' ', text)
    return text.strip()

def fetch_article(article_id: str, retries: int = 2) -> str:
    """Curl-fetch + extract main text."""
    url = BASE + article_id
    for attempt in range(retries):
        try:
            r = subprocess.run(
                ['curl', '-s', '--max-time', '15', url],
                capture_output=True, timeout=20
            )
            if r.returncode != 0:
                continue
            html = r.stdout.decode('utf-8', errors='replace')
            if len(html) < 200:
                continue
            return extract_text(html)
        except Exception as e:
            if attempt == retries - 1:
                return f'ERROR: {e}'
    return 'ERROR: max retries'

def main():
    with INVENTORY.open(encoding='utf-8') as f:
        all_articles = json.load(f)

    targets = [a for a in all_articles
               if a['path'].split(' > ')[0] in PRIORITY_CHAPTERS
               and a['path'].split(' > ')[0] not in SKIP_CHAPTERS]
    print(f'Total articles in priority chapters: {len(targets)}')
    print(f'Estimated time @ 0.5s/article: {len(targets) * 0.5 / 60:.1f} min')

    success = 0
    skip = 0
    fail = 0
    index_rows = []

    for i, art in enumerate(targets):
        outfile = ARTICLES_DIR / f'{art["id"]}.md'
        if outfile.exists() and outfile.stat().st_size > 200:
            skip += 1
            continue

        text = fetch_article(art['id'])
        if text.startswith('ERROR'):
            fail += 1
            print(f'  [{i+1}/{len(targets)}] FAIL {art["id"]} {art["text"]}: {text}')
            continue

        # Write
        md = f'# {art["text"]}\n\n**Path**: {art["path"]}\n**ID**: {art["id"]}\n**URL**: {BASE}{art["id"]}\n\n---\n\n{text}\n'
        outfile.write_text(md, encoding='utf-8')
        success += 1
        if (i+1) % 20 == 0:
            print(f'  [{i+1}/{len(targets)}] OK {art["id"]} {art["text"][:30]} ({len(text)} chars)')
        time.sleep(0.1)  # be nice to HJ server

    # Write index
    for art in targets:
        outfile = ARTICLES_DIR / f'{art["id"]}.md'
        size = outfile.stat().st_size if outfile.exists() else 0
        status = 'OK' if size > 200 else 'MISSING'
        index_rows.append(f'| {art["id"]} | {art["path"]} | {size} | {status} |')

    INDEX.write_text(
        '# Help Articles Fetch Index\n\n'
        f'Fetched {success} new / {skip} cached / {fail} fail of {len(targets)} priority articles.\n\n'
        '| ID | Path | Bytes | Status |\n|---|---|---|---|\n'
        + '\n'.join(index_rows) + '\n',
        encoding='utf-8'
    )
    print(f'\nSummary: {success} ship / {skip} cached / {fail} fail')

if __name__ == '__main__':
    main()
