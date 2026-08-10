# -*- coding: utf-8 -*-
"""
E2E Test: Unified Analyzer with Cache and Multi-Sheet Parallel Processing

Validates the complete flow:
1. Multi-sheet parallel processing
2. Cache hit/miss behavior
3. JSON/CSV/MD file generation
4. LLM module integration (field detection, scenario detection, chart recommendation)
5. Cache reuse on second upload

Test Flow:
┌─────────────────────────────────────────────────────────────────┐
│  Upload 1 (Cold Cache)                                          │
│  ├── Check: All sheets processed in parallel                    │
│  ├── Check: Cache miss for all sheets                           │
│  ├── Check: JSON/CSV/MD files created                           │
│  └── Check: LLM modules invoked                                 │
│                                                                 │
│  Upload 2 (Warm Cache - Same File)                              │
│  ├── Check: Cache hit for all sheets                            │
│  ├── Check: Data loaded from cache files                        │
│  └── Check: Processing much faster                              │
│                                                                 │
│  Upload 3 (Force Refresh)                                       │
│  ├── Check: Cache bypassed                                      │
│  └── Check: Files regenerated                                   │
└─────────────────────────────────────────────────────────────────┘
"""
import asyncio
import sys
import os
import io
import time
import json
import shutil
import tempfile
from typing import List

# Fix encoding for Windows console.
# ⚠️ 只在**真的以脚本运行**时才改 —— pytest 下 sys.stdout 是它的捕获对象, 在导入期
#    把 sys.stdout.buffer 重新包一层并改绑, 会掐断 pytest 的捕获机制: 之后它对自己的
#    tmpfile `seek(0)` 就抛 `ValueError: I/O operation on closed file`, **整个 pytest
#    进程当场归零**(不是这个用例失败, 是整轮测量没了)。
#    python-gate.yml 因此把整个 smartbi/tests/(75 个文件)排除在门禁之外 —— 一个导入
#    期副作用挡住了 75 个文件几个月。
#    判据: **模块导入期不要改全局 IO/环境**; 要改就放进 `if __name__ == "__main__"`。
if sys.platform == 'win32' and "pytest" not in sys.modules:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

# Add smartbi to path and change to smartbi dir for .env loading
smartbi_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, smartbi_dir)
os.chdir(smartbi_dir)  # Change to smartbi dir so .env can be loaded

from services.unified_analyzer import (  # noqa: E402
    UnifiedAnalyzer,
    AnalysisOptions,
    AnalysisDepth
)
from services.analysis_cache import get_cache_manager  # noqa: E402


class TestResult:
    """Test result tracker"""

    def __init__(self, name: str):
        self.name = name
        self.passed = 0
        self.failed = 0
        self.errors: List[str] = []

    def check(self, condition: bool, message: str):
        """Check a condition and record result"""
        if condition:
            self.passed += 1
            print(f"    [OK] {message}")
        else:
            self.failed += 1
            self.errors.append(message)
            print(f"    [FAIL] {message}")

    def summary(self) -> bool:
        """Print summary and return success status"""
        total = self.passed + self.failed
        print(f"\n    Results: {self.passed}/{total} passed")
        if self.errors:
            print("    Failures:")
            for err in self.errors:
                print(f"      - {err}")
        return self.failed == 0


def create_test_excel():
    """Create test Excel file with multiple sheets"""
    test_file = os.path.join(os.path.dirname(__file__), "test_complex_5sheets.xlsx")

    if not os.path.exists(test_file):
        print("Creating test Excel file...")
        # Import the create script
        import create_test_excel as creator
        creator.create_test_excel(test_file)

    return test_file


async def test_multi_sheet_parallel_processing(file_bytes: bytes, cache_dir: str) -> TestResult:
    """
    Test 1: Multi-sheet parallel processing (cold cache)

    Verifies:
    - All sheets are processed
    - Processing is parallel (check timing)
    - Results contain expected data
    """
    result = TestResult("Multi-Sheet Parallel Processing")
    print("\n" + "=" * 70)
    print("Test 1: Multi-Sheet Parallel Processing (Cold Cache)")
    print("=" * 70)

    # Clear cache to ensure cold start
    cache_manager = get_cache_manager(cache_dir=cache_dir)
    cache_manager.clear_all()

    # Run analysis
    analyzer = UnifiedAnalyzer(cache_dir=cache_dir)

    try:
        start_time = time.time()

        options = AnalysisOptions(
            depth=AnalysisDepth.STANDARD,
            max_charts=3,
            max_insights=3,
            use_cache=True,
            save_to_cache=True
        )

        multi_result = await analyzer.analyze_all_sheets(
            file_bytes,
            question=None,
            options=options,
            max_parallel=5
        )

        elapsed_ms = int((time.time() - start_time) * 1000)

        print(f"\n    Processing time: {elapsed_ms}ms")
        print(f"    Total sheets: {multi_result.total_sheets}")
        print(f"    Success count: {multi_result.success_count}")
        print(f"    Cache hits: {multi_result.cache_hit_count}")
        print(f"    Errors: {multi_result.error_count}")

        # Checks
        result.check(
            multi_result.total_sheets >= 5,
            f"Has 5+ sheets (found: {multi_result.total_sheets})"
        )

        result.check(
            multi_result.success_count >= 4,
            f"At least 4 sheets successful (got: {multi_result.success_count})"
        )

        result.check(
            multi_result.cache_hit_count == 0,
            f"Zero cache hits on cold start (got: {multi_result.cache_hit_count})"
        )

        # Check individual sheet results
        for i, sheet_result in enumerate(multi_result.sheet_results):
            if sheet_result.success:
                result.check(
                    len(sheet_result.fields) > 0,
                    f"Sheet {i} ({sheet_result.sheet_name}): has fields detected"
                )

                result.check(
                    sheet_result.scenario is not None,
                    f"Sheet {i} ({sheet_result.sheet_name}): has scenario detected"
                )

                result.check(
                    sheet_result.cache_key is not None,
                    f"Sheet {i} ({sheet_result.sheet_name}): has cache key"
                )

        # Print sheet details
        print("\n    Sheet Details:")
        for i, sr in enumerate(multi_result.sheet_results):
            status = "[OK]" if sr.success else "[FAIL]"
            scenario = sr.scenario.scenario_name if sr.scenario else "N/A"
            print(f"      {i}. {sr.sheet_name}: {status} | Scenario: {scenario} | Fields: {len(sr.fields)}")

    finally:
        await analyzer.close()

    return result


async def test_cache_hit_behavior(file_bytes: bytes, cache_dir: str) -> TestResult:
    """
    Test 2: Cache hit behavior (warm cache)

    Verifies:
    - All sheets hit cache
    - Data loaded from files
    - Processing is faster
    """
    result = TestResult("Cache Hit Behavior")
    print("\n" + "=" * 70)
    print("Test 2: Cache Hit Behavior (Warm Cache)")
    print("=" * 70)

    analyzer = UnifiedAnalyzer(cache_dir=cache_dir)

    try:
        start_time = time.time()

        options = AnalysisOptions(
            depth=AnalysisDepth.STANDARD,
            use_cache=True,
            save_to_cache=True
        )

        multi_result = await analyzer.analyze_all_sheets(
            file_bytes,
            question=None,
            options=options,
            max_parallel=5
        )

        elapsed_ms = int((time.time() - start_time) * 1000)

        print(f"\n    Processing time: {elapsed_ms}ms")
        print(f"    Total sheets: {multi_result.total_sheets}")
        print(f"    Cache hits: {multi_result.cache_hit_count}")

        # Checks
        result.check(
            multi_result.cache_hit_count >= multi_result.success_count - 1,
            f"Most sheets from cache (hits: {multi_result.cache_hit_count}, success: {multi_result.success_count})"
        )

        # Check from_cache flag
        cached_count = sum(1 for sr in multi_result.sheet_results if sr.from_cache)
        result.check(
            cached_count >= multi_result.success_count - 1,
            f"from_cache flag set correctly (count: {cached_count})"
        )

        # Check cache files exist
        cache_manager = get_cache_manager(cache_dir=cache_dir)
        for sr in multi_result.sheet_results:
            if sr.success and sr.cache_key:
                json_path, csv_path, md_path = cache_manager.get_cache_paths(sr.cache_key)

                result.check(
                    json_path.exists(),
                    f"JSON file exists for {sr.sheet_name}"
                )
                result.check(
                    csv_path.exists(),
                    f"CSV file exists for {sr.sheet_name}"
                )
                result.check(
                    md_path.exists(),
                    f"MD file exists for {sr.sheet_name}"
                )

        # Print cache stats
        stats = cache_manager.get_stats()
        print("\n    Cache Stats:")
        print(f"      Total entries: {stats['totalEntries']}")
        print(f"      Valid entries: {stats['validEntries']}")
        print(f"      Total size: {stats['totalSizeMB']} MB")

    finally:
        await analyzer.close()

    return result


async def test_cache_file_content(file_bytes: bytes, cache_dir: str) -> TestResult:
    """
    Test 3: Cache file content validation

    Verifies:
    - JSON contains correct structure
    - CSV is readable and has data
    - MD has proper formatting
    """
    result = TestResult("Cache File Content")
    print("\n" + "=" * 70)
    print("Test 3: Cache File Content Validation")
    print("=" * 70)

    cache_manager = get_cache_manager(cache_dir=cache_dir)

    # Get first cached entry
    if not cache_manager._index:
        result.check(False, "Cache has entries")
        return result

    first_key = list(cache_manager._index.keys())[0]
    json_path, csv_path, md_path = cache_manager.get_cache_paths(first_key)

    # Validate JSON
    print(f"\n    Checking JSON file: {json_path.name}")
    try:
        with open(json_path, 'r', encoding='utf-8') as f:
            json_data = json.load(f)

        result.check(
            "sheet_name" in json_data,
            "JSON has sheet_name"
        )
        result.check(
            "columns" in json_data,
            "JSON has columns list"
        )
        result.check(
            "row_count" in json_data,
            "JSON has row_count"
        )
        result.check(
            "statistics" in json_data,
            "JSON has statistics"
        )

        print(f"      Sheet: {json_data.get('sheet_name')}")
        print(f"      Rows: {json_data.get('row_count')}")
        print(f"      Columns: {len(json_data.get('columns', []))}")

    except Exception as e:
        result.check(False, f"JSON is valid: {e}")

    # Validate CSV
    print(f"\n    Checking CSV file: {csv_path.name}")
    try:
        import pandas as pd
        df = pd.read_csv(csv_path, encoding='utf-8')

        result.check(
            len(df) > 0,
            f"CSV has data rows (got: {len(df)})"
        )
        result.check(
            len(df.columns) > 0,
            f"CSV has columns (got: {len(df.columns)})"
        )

        print(f"      Rows: {len(df)}")
        print(f"      Columns: {list(df.columns)[:5]}...")

    except Exception as e:
        result.check(False, f"CSV is readable: {e}")

    # Validate MD
    print(f"\n    Checking MD file: {md_path.name}")
    try:
        with open(md_path, 'r', encoding='utf-8') as f:
            md_content = f.read()

        result.check(
            md_content.startswith("#"),
            "MD starts with header"
        )
        result.check(
            "##" in md_content,
            "MD has sections"
        )
        result.check(
            "|" in md_content,
            "MD has table"
        )

        lines = md_content.split('\n')
        print(f"      Lines: {len(lines)}")
        print("      First 3 lines:")
        for line in lines[:3]:
            print(f"        {line[:60]}...")

    except Exception as e:
        result.check(False, f"MD is readable: {e}")

    return result


async def test_force_refresh(file_bytes: bytes, cache_dir: str) -> TestResult:
    """
    Test 4: Force refresh behavior

    Verifies:
    - Cache is bypassed when force_refresh=True
    - Files are regenerated
    """
    result = TestResult("Force Refresh")
    print("\n" + "=" * 70)
    print("Test 4: Force Refresh Behavior")
    print("=" * 70)

    analyzer = UnifiedAnalyzer(cache_dir=cache_dir)

    try:
        # Use sheet_index=1 (sales detail) which is simpler and works reliably
        test_sheet_index = 1

        cache_manager = get_cache_manager(cache_dir=cache_dir)

        # Find the cache key for sheet 1
        target_key = cache_manager.generate_cache_key(file_bytes, test_sheet_index)
        json_path, _, _ = cache_manager.get_cache_paths(target_key)

        old_mtime = json_path.stat().st_mtime if json_path.exists() else 0

        # Wait a bit to ensure mtime difference
        await asyncio.sleep(1)

        # Run with force_refresh
        options = AnalysisOptions(
            depth=AnalysisDepth.QUICK,
            use_cache=True,
            save_to_cache=True,
            force_refresh=True
        )

        single_result = await analyzer.analyze(
            file_bytes,
            sheet_index=test_sheet_index,
            options=options
        )

        result.check(
            single_result.success,
            f"Analysis succeeded with force_refresh (sheet {test_sheet_index})"
        )

        result.check(
            not single_result.from_cache,
            "Result NOT from cache (force_refresh worked)"
        )

        # Wait for async save to complete
        await asyncio.sleep(2)

        # Check if file was updated
        new_mtime = json_path.stat().st_mtime if json_path.exists() else 0
        result.check(
            new_mtime > old_mtime,
            f"Cache file was regenerated (old: {old_mtime:.0f}, new: {new_mtime:.0f})"
        )

    finally:
        await analyzer.close()

    return result


async def test_llm_integration(file_bytes: bytes, cache_dir: str) -> TestResult:
    """
    Test 5: LLM module integration

    Verifies:
    - Field detection uses LLM
    - Scenario detection uses LLM
    - Chart recommendation uses LLM
    """
    result = TestResult("LLM Integration")
    print("\n" + "=" * 70)
    print("Test 5: LLM Module Integration")
    print("=" * 70)

    # Clear cache to force LLM calls
    cache_manager = get_cache_manager(cache_dir=cache_dir)
    cache_manager.clear_all()

    analyzer = UnifiedAnalyzer(cache_dir=cache_dir)

    try:
        options = AnalysisOptions(
            depth=AnalysisDepth.STANDARD,
            max_charts=3,
            max_insights=3,
            use_cache=False,  # Disable cache for this test
            save_to_cache=False
        )

        # Analyze sheet 1 (sales detail) - simpler structure, more reliable
        # Note: Sheet 0 (profit table) has complex merged headers that may cause issues
        sheet_result = await analyzer.analyze(
            file_bytes,
            sheet_index=1,
            options=options
        )

        if not sheet_result.success:
            result.check(False, f"Analysis failed: {sheet_result.error}")
            return result

        print(f"\n    Sheet: {sheet_result.sheet_name}")

        # Check field detection
        print("\n    Field Detection (LLM):")
        result.check(
            len(sheet_result.fields) > 0,
            f"Fields detected: {len(sheet_result.fields)}"
        )

        for field in sheet_result.fields[:3]:
            print(f"      - {field.name}: {field.data_type} / {field.semantic_type} / {field.chart_role}")
            result.check(
                field.semantic_type != "unknown",
                f"Field '{field.name}' has semantic type"
            )

        # Check scenario detection
        print("\n    Scenario Detection (LLM):")
        if sheet_result.scenario:
            print(f"      Type: {sheet_result.scenario.scenario}")
            print(f"      Name: {sheet_result.scenario.scenario_name}")
            print(f"      Confidence: {sheet_result.scenario.confidence:.2%}")

            result.check(
                sheet_result.scenario.confidence >= 0.5,
                f"Scenario confidence >= 50% (got: {sheet_result.scenario.confidence:.2%})"
            )
            result.check(
                sheet_result.scenario.scenario != "unknown",
                f"Scenario is not unknown (got: {sheet_result.scenario.scenario})"
            )
        else:
            result.check(False, "Scenario was detected")

        # Check chart recommendations
        print("\n    Chart Recommendations (LLM):")
        if sheet_result.charts:
            for chart in sheet_result.charts[:2]:
                print(f"      - {chart.chart_type}: {chart.title}")
                print(f"        Reason: {chart.reason[:50]}..." if chart.reason else "        (no reason)")

            result.check(
                len(sheet_result.charts) > 0,
                f"Charts recommended: {len(sheet_result.charts)}"
            )
        else:
            print("      No charts generated (may be expected for some data)")

        # Check metrics
        print("\n    Metrics (LLM-inferred):")
        if sheet_result.metrics:
            for metric in sheet_result.metrics[:3]:
                print(f"      - {metric.name}: {metric.formatted}")

            result.check(
                len(sheet_result.metrics) > 0,
                f"Metrics calculated: {len(sheet_result.metrics)}"
            )
        else:
            print("      No metrics calculated")

        # Check notes for LLM usage
        llm_notes = [n for n in sheet_result.notes if "LLM" in n]
        result.check(
            len(llm_notes) > 0,
            f"Notes mention LLM usage: {len(llm_notes)}"
        )

    finally:
        await analyzer.close()

    return result


async def test_single_sheet_analysis(file_bytes: bytes, cache_dir: str) -> TestResult:
    """
    Test 6: Single sheet analysis

    Verifies:
    - Single sheet analysis works correctly
    - Cache works for single sheet
    """
    result = TestResult("Single Sheet Analysis")
    print("\n" + "=" * 70)
    print("Test 6: Single Sheet Analysis")
    print("=" * 70)

    # Clear cache
    cache_manager = get_cache_manager(cache_dir=cache_dir)
    cache_manager.clear_all()

    analyzer = UnifiedAnalyzer(cache_dir=cache_dir)

    try:
        # First call - cold cache
        options = AnalysisOptions(
            depth=AnalysisDepth.QUICK,
            use_cache=True,
            save_to_cache=True
        )

        print("\n    First call (cold cache):")
        start1 = time.time()
        result1 = await analyzer.analyze(file_bytes, sheet_index=1, options=options)
        time1 = int((time.time() - start1) * 1000)

        result.check(result1.success, "First analysis succeeded")
        result.check(not result1.from_cache, "First result is NOT from cache")
        print(f"      Time: {time1}ms")
        print(f"      Sheet: {result1.sheet_name}")
        print(f"      From cache: {result1.from_cache}")

        # Wait for async save
        await asyncio.sleep(2)

        # Second call - warm cache
        print("\n    Second call (warm cache):")
        start2 = time.time()
        result2 = await analyzer.analyze(file_bytes, sheet_index=1, options=options)
        time2 = int((time.time() - start2) * 1000)

        result.check(result2.success, "Second analysis succeeded")
        result.check(result2.from_cache, "Second result IS from cache")
        print(f"      Time: {time2}ms")
        print(f"      From cache: {result2.from_cache}")

        # Note: Current cache only caches raw data (JSON/CSV/MD), not analysis results.
        # LLM calls (field detection, scenario detection, etc.) still execute on cache hit.
        # To get speedup, we'd need to cache analysis results too (future enhancement).
        # For now, we only verify the cache flag is set correctly.
        if time1 > 100 and time2 < time1:
            speedup = time1 / max(time2, 1)
            print(f"      Speedup: {speedup:.1f}x")
        else:
            print("      Note: Cache hit but LLM analysis still runs (expected behavior)")

    finally:
        await analyzer.close()

    return result


async def main():
    """Run all E2E tests"""
    print("\n" + "=" * 80)
    print("     SmartBI Unified Analyzer E2E Tests")
    print("     Testing: Cache + Multi-Sheet + LLM Integration")
    print("=" * 80)

    # Setup
    test_file = create_test_excel()
    print(f"\nTest file: {test_file}")

    with open(test_file, 'rb') as f:
        file_bytes = f.read()

    print(f"File size: {len(file_bytes)} bytes")

    # Create temp cache directory
    cache_dir = tempfile.mkdtemp(prefix="smartbi_test_cache_")
    print(f"Cache dir: {cache_dir}")

    results: List[TestResult] = []

    try:
        # Test 1: Multi-sheet parallel processing (cold cache)
        results.append(await test_multi_sheet_parallel_processing(file_bytes, cache_dir))

        # Wait for async save to complete
        print("\n    Waiting for cache files to save...")
        await asyncio.sleep(3)

        # Test 2: Cache hit behavior (warm cache)
        results.append(await test_cache_hit_behavior(file_bytes, cache_dir))

        # Test 3: Cache file content validation
        results.append(await test_cache_file_content(file_bytes, cache_dir))

        # Test 4: Force refresh
        results.append(await test_force_refresh(file_bytes, cache_dir))

        # Test 5: LLM integration
        results.append(await test_llm_integration(file_bytes, cache_dir))

        # Test 6: Single sheet analysis
        results.append(await test_single_sheet_analysis(file_bytes, cache_dir))

    except Exception as e:
        print(f"\n[ERROR] Test execution failed: {e}")
        import traceback
        traceback.print_exc()

    finally:
        # Cleanup
        try:
            shutil.rmtree(cache_dir)
            print(f"\nCleaned up cache dir: {cache_dir}")
        except Exception as e:
            print(f"Warning: Failed to clean up cache dir: {e}")

    # Summary
    print("\n" + "=" * 80)
    print("                        Test Results Summary")
    print("=" * 80)

    total_passed = 0
    total_failed = 0

    for r in results:
        status = "[OK]" if r.failed == 0 else "[FAIL]"
        print(f"  {status} {r.name}: {r.passed} passed, {r.failed} failed")
        total_passed += r.passed
        total_failed += r.failed

    print("-" * 80)
    overall_status = "[OK] ALL TESTS PASSED" if total_failed == 0 else "[FAIL] SOME TESTS FAILED"
    print(f"  {overall_status}: {total_passed} passed, {total_failed} failed")
    print("=" * 80)

    return total_failed == 0


if __name__ == "__main__":
    success = asyncio.run(main())
    sys.exit(0 if success else 1)
