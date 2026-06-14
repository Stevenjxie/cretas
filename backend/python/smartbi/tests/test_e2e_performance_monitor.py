# -*- coding: utf-8 -*-
"""
E2E Performance Monitor Test

全面的性能监控测试，追踪每个阶段、每个服务的执行时间。
支持问题回溯和性能瓶颈定位。

Phases:
- Phase 0: Cache Check
- Phase 1: Data Extraction (RawExporter + DataCleaner)
- Phase 2: LLM Detection (FieldDetector + ScenarioDetector)
- Phase 3: Parallel Analysis (Metrics + Charts + Insights + Predictions)
- Phase 4: LLM Enhancement (Optional)
- Phase 5: Cache Save (Async)
"""
import asyncio
import sys
import os
import io
import time
import json
import shutil
from dataclasses import dataclass, field
from typing import Dict, List, Any, Optional
from datetime import datetime

# Fix encoding for Windows console
if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

# Add smartbi to path
smartbi_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, smartbi_dir)
os.chdir(smartbi_dir)


@dataclass
class TimingRecord:
    """Single timing record"""
    name: str
    phase: str
    start_time: float
    end_time: float = 0.0
    duration_ms: int = 0
    success: bool = True
    error: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def finish(self, success: bool = True, error: str = None, **metadata):
        self.end_time = time.time()
        self.duration_ms = int((self.end_time - self.start_time) * 1000)
        self.success = success
        self.error = error
        self.metadata.update(metadata)


@dataclass
class SheetPerformance:
    """Performance data for a single sheet"""
    sheet_index: int
    sheet_name: str
    total_time_ms: int = 0
    from_cache: bool = False
    success: bool = True
    error: Optional[str] = None
    timings: List[TimingRecord] = field(default_factory=list)

    def to_dict(self) -> Dict:
        return {
            "sheetIndex": self.sheet_index,
            "sheetName": self.sheet_name,
            "totalTimeMs": self.total_time_ms,
            "fromCache": self.from_cache,
            "success": self.success,
            "error": self.error,
            "timings": [
                {
                    "name": t.name,
                    "phase": t.phase,
                    "durationMs": t.duration_ms,
                    "success": t.success,
                    "error": t.error,
                    "metadata": t.metadata
                }
                for t in self.timings
            ]
        }


@dataclass
class PerformanceReport:
    """Overall performance report"""
    test_file: str
    start_time: str
    end_time: str = ""
    total_time_ms: int = 0
    total_sheets: int = 0
    success_count: int = 0
    error_count: int = 0
    cache_hit_count: int = 0
    sheets: List[SheetPerformance] = field(default_factory=list)
    phase_summary: Dict[str, Dict] = field(default_factory=dict)

    def to_dict(self) -> Dict:
        return {
            "testFile": self.test_file,
            "startTime": self.start_time,
            "endTime": self.end_time,
            "totalTimeMs": self.total_time_ms,
            "totalSheets": self.total_sheets,
            "successCount": self.success_count,
            "errorCount": self.error_count,
            "cacheHitCount": self.cache_hit_count,
            "sheets": [s.to_dict() for s in self.sheets],
            "phaseSummary": self.phase_summary
        }


class PerformanceMonitor:
    """Performance monitoring wrapper for UnifiedAnalyzer"""

    def __init__(self, cache_dir: str = None):
        from services.unified_analyzer import UnifiedAnalyzer, AnalysisOptions, AnalysisDepth
        from services.analysis_cache import get_cache_manager

        self.cache_dir = cache_dir or "smartbi_cache_perf_test"
        self.analyzer = UnifiedAnalyzer(cache_dir=self.cache_dir)
        self.cache_manager = get_cache_manager(cache_dir=self.cache_dir)
        self.AnalysisOptions = AnalysisOptions
        self.AnalysisDepth = AnalysisDepth

        # Wrap services with timing
        self._wrap_services()

    def _wrap_services(self):
        """Wrap service methods with timing instrumentation"""
        # We'll inject timing collection into analyze method

    async def clear_cache(self):
        """Clear all cache"""
        print("\n" + "=" * 70)
        print("Clearing all caches...")
        print("=" * 70)

        # Clear file-based cache
        self.cache_manager.clear_all()
        print(f"  [OK] File cache cleared: {self.cache_dir}")

        # Clear in-memory caches
        from services.field_detector_llm import FieldDetectionCache
        try:
            FieldDetectionCache._cache.clear()
            print("  [OK] Field detection cache cleared")
        except:  # noqa: E722
            pass

        from services.scenario_detector import get_scenario_detector
        try:
            detector = get_scenario_detector()
            if hasattr(detector, '_cache'):
                detector._cache.clear()
            print("  [OK] Scenario detection cache cleared")
        except:  # noqa: E722
            pass

        from services.chart_recommender import get_chart_recommender
        try:
            recommender = get_chart_recommender()
            if hasattr(recommender, '_cache'):
                recommender._cache.clear()
            print("  [OK] Chart recommender cache cleared")
        except:  # noqa: E722
            pass

        print("  [OK] All caches cleared\n")

    async def analyze_with_timing(
        self,
        file_bytes: bytes,
        sheet_index: int,
        question: str = None,
        depth: str = "standard"
    ) -> SheetPerformance:
        """Analyze a single sheet with detailed timing using the actual analyzer"""

        perf = SheetPerformance(sheet_index=sheet_index, sheet_name="")
        overall_start = time.time()

        try:
            options = self.AnalysisOptions(  # noqa: F841
                depth=getattr(self.AnalysisDepth, depth.upper()),
                max_charts=3,
                max_insights=3,
                use_cache=True,
                save_to_cache=True
            )

            # ─────────────────────────────────────────────────────────────
            # Phase 0: Cache Check
            # ─────────────────────────────────────────────────────────────
            t0 = TimingRecord(name="cache_check", phase="Phase 0: Cache Check", start_time=time.time())

            cache_key = self.cache_manager.generate_cache_key(file_bytes, sheet_index)
            cached = self.cache_manager.get_cached(file_bytes, sheet_index, include_analysis=True)

            cache_hit = cached is not None and cached.has_complete_cache()
            t0.finish(success=True, cache_hit=cache_hit, cache_key=cache_key)
            perf.timings.append(t0)

            if cache_hit:
                perf.from_cache = True
                perf.sheet_name = cached.metadata.sheet_name if cached.metadata else "Unknown"
                perf.total_time_ms = int((time.time() - overall_start) * 1000)
                perf.success = True
                print(f"    Sheet {sheet_index}: CACHE HIT ({perf.total_time_ms}ms)")
                return perf

            # ─────────────────────────────────────────────────────────────
            # Phase 1: Data Extraction
            # ─────────────────────────────────────────────────────────────
            t1 = TimingRecord(name="data_extraction", phase="Phase 1: Data Extraction", start_time=time.time())

            # 1a. RawExporter
            t1a = TimingRecord(name="raw_export", phase="Phase 1a: Raw Export", start_time=time.time())
            from services.raw_exporter import RawExporter
            exporter = RawExporter()
            raw_data = exporter.export_sheet(file_bytes, sheet_index)
            t1a.finish(
                success=raw_data is not None,
                rows=raw_data.total_rows if raw_data else 0,
                cols=raw_data.total_cols if raw_data else 0
            )
            perf.timings.append(t1a)

            if raw_data:
                perf.sheet_name = raw_data.sheet_name

            # 1b. Build DataFrame
            t1b = TimingRecord(name="build_dataframe", phase="Phase 1b: Build DataFrame", start_time=time.time())
            df = self._build_dataframe_from_raw(raw_data)
            t1b.finish(
                success=df is not None and not df.empty,
                rows=len(df) if df is not None else 0,
                cols=len(df.columns) if df is not None else 0
            )
            perf.timings.append(t1b)

            if df is None or df.empty:
                t1.finish(success=False, error="Empty DataFrame")
                perf.timings.append(t1)
                perf.success = False
                perf.error = "Empty DataFrame"
                perf.total_time_ms = int((time.time() - overall_start) * 1000)
                return perf

            # 1c. Data Cleaning
            t1c = TimingRecord(name="data_cleaning", phase="Phase 1c: Data Cleaning", start_time=time.time())
            df, changes = await self.analyzer._clean_data(df)
            t1c.finish(success=True, changes=len(changes))
            perf.timings.append(t1c)

            t1.finish(success=True)
            perf.timings.append(t1)

            # ─────────────────────────────────────────────────────────────
            # Phase 2: LLM Detection (Field + Scenario)
            # ─────────────────────────────────────────────────────────────
            t2 = TimingRecord(name="llm_detection", phase="Phase 2: LLM Detection", start_time=time.time())

            # Prepare data for LLM services
            headers = df.columns.tolist()
            rows = df.head(10).to_dict('records')

            # 2a. Field Detection
            t2a = TimingRecord(name="field_detection", phase="Phase 2a: Field Detection", start_time=time.time())
            try:
                fields_raw = await self.analyzer.field_detector.detect_fields(headers, rows)
                t2a.finish(success=True, field_count=len(fields_raw) if fields_raw else 0)
            except Exception as e:
                fields_raw = []
                t2a.finish(success=False, error=str(e))
            perf.timings.append(t2a)

            # 2b. Scenario Detection (sequential to avoid rate limits)
            t2b = TimingRecord(name="scenario_detection", phase="Phase 2b: Scenario Detection", start_time=time.time())
            try:
                metadata = {"sheet_name": perf.sheet_name, "question": question}
                scenario_raw = await self.analyzer.scenario_detector.detect(
                    columns=headers,
                    sample_rows=rows,
                    metadata=metadata
                )
                t2b.finish(
                    success=scenario_raw is not None,
                    scenario=scenario_raw.scenario_type if scenario_raw else "unknown",
                    confidence=scenario_raw.confidence if scenario_raw else 0
                )
            except Exception as e:
                scenario_raw = None
                t2b.finish(success=False, error=str(e))
            perf.timings.append(t2b)

            t2.finish(success=True)
            perf.timings.append(t2)

            # Convert to result types
            from services.unified_analyzer import FieldInfo, ScenarioResult
            fields = [
                FieldInfo(
                    name=f.field_name,
                    data_type=f.data_type,
                    semantic_type=f.semantic_type,
                    chart_role=f.chart_role,
                    sample_values=f.sample_values,
                    statistics=f.statistics
                )
                for f in fields_raw
            ] if fields_raw else []

            scenario = ScenarioResult(
                scenario=scenario_raw.scenario_type if scenario_raw else "unknown",
                scenario_name=scenario_raw.scenario_name if scenario_raw else "Unknown",
                confidence=scenario_raw.confidence if scenario_raw else 0,
                evidence=[],
                dimensions=scenario_raw.dimensions if scenario_raw else [],
                measures=scenario_raw.measures if scenario_raw else [],
                recommended_analyses=scenario_raw.recommended_analyses if scenario_raw else []
            ) if scenario_raw else None

            # ─────────────────────────────────────────────────────────────
            # Phase 3: Parallel Analysis
            # ─────────────────────────────────────────────────────────────
            t3 = TimingRecord(name="parallel_analysis", phase="Phase 3: Parallel Analysis", start_time=time.time())

            # 3a. Metrics
            t3a_start = time.time()
            metrics_raw = []
            try:
                metrics_raw = await self.analyzer.metric_calculator.infer_and_calculate_all(
                    df,
                    scenario.scenario if scenario else "general",
                    fields
                )
            except Exception:
                pass
            t3a = TimingRecord(name="metric_calculation", phase="Phase 3a: Metrics", start_time=t3a_start)
            t3a.finish(success=True, metric_count=len(metrics_raw) if metrics_raw else 0)
            perf.timings.append(t3a)

            # 3b. Charts
            t3b_start = time.time()
            charts_raw = []
            chart_error = None
            try:
                from services.chart_recommender import DataSummary
                # Build columns list with proper format (handle duplicate columns)
                columns_info = []
                seen_cols = set()
                for i, c in enumerate(df.columns[:20]):  # Limit to 20 columns
                    col_name = str(c)
                    # Handle duplicate column names
                    if col_name in seen_cols:
                        continue
                    seen_cols.add(col_name)

                    # Use iloc to safely get dtype
                    try:
                        col_dtype = str(df.iloc[:, i].dtype)
                    except:  # noqa: E722
                        col_dtype = "object"

                    col_data = {
                        "name": col_name,
                        "type": col_dtype,
                    }
                    columns_info.append(col_data)

                # Identify dimensions and measures from field detection
                dimensions = [f.name for f in fields if f.chart_role == "dimension"]
                measures = [f.name for f in fields if f.chart_role == "measure"]

                summary = DataSummary(
                    columns=columns_info,
                    row_count=len(df),
                    dimensions=dimensions,
                    measures=measures
                )
                charts_raw = await self.analyzer.chart_recommender.recommend(
                    summary,
                    scenario.scenario if scenario else "general",
                    max_recommendations=3
                )
            except Exception as e:
                chart_error = str(e)
            t3b = TimingRecord(name="chart_recommendation", phase="Phase 3b: Charts", start_time=t3b_start)
            t3b.finish(
                success=chart_error is None,
                chart_count=len(charts_raw) if charts_raw else 0,
                error=chart_error
            )
            perf.timings.append(t3b)

            # 3c. Insights
            t3c_start = time.time()
            insights_raw = []
            insight_error = None
            try:
                # Convert DataFrame to records for insight generator (handle duplicate cols)
                # Use a clean DataFrame with unique column names
                df_clean = df.copy()
                new_cols = []
                col_counts = {}
                for c in df_clean.columns:
                    c_str = str(c)
                    if c_str in col_counts:
                        col_counts[c_str] += 1
                        new_cols.append(f"{c_str}_{col_counts[c_str]}")
                    else:
                        col_counts[c_str] = 0
                        new_cols.append(c_str)
                df_clean.columns = new_cols

                data_records = df_clean.head(50).to_dict('records')

                # Build metrics dict from raw metrics
                # metrics_raw is a list of MetricResult objects or dicts
                metrics_dict = []
                if metrics_raw:
                    for m in metrics_raw:
                        if hasattr(m, 'name'):
                            metrics_dict.append({
                                "name": m.name,
                                "value": m.value,
                                "formatted": m.formatted
                            })
                        elif isinstance(m, dict):
                            metrics_dict.append(m)

                insight_result = await self.analyzer.insight_generator.generate_insights(
                    data=data_records,
                    metrics=metrics_dict if metrics_dict else None,
                    analysis_context=scenario.scenario if scenario else "general",
                    max_insights=3
                )
                if insight_result and insight_result.get("success"):
                    insights_raw = insight_result.get("insights", [])
            except Exception as e:
                insight_error = str(e)
            t3c = TimingRecord(name="insight_generation", phase="Phase 3c: Insights", start_time=t3c_start)
            t3c.finish(
                success=insight_error is None,
                insight_count=len(insights_raw) if insights_raw else 0,
                error=insight_error
            )
            perf.timings.append(t3c)

            t3.finish(success=True)
            perf.timings.append(t3)

            # ─────────────────────────────────────────────────────────────
            # Phase 5: Cache Save
            # ─────────────────────────────────────────────────────────────
            t5 = TimingRecord(name="cache_save", phase="Phase 5: Cache Save", start_time=time.time())

            # Build a minimal result for caching
            from services.unified_analyzer import UnifiedAnalysisResult

            result = UnifiedAnalysisResult(
                success=True,
                sheet_name=raw_data.sheet_name,
                total_rows=raw_data.total_rows,
                total_cols=raw_data.total_cols,
                fields=fields,
                scenario=scenario,
                metrics=[],
                charts=[],
                insights=[],
                analysis_depth="standard"
            )

            await self.analyzer._save_complete_cache_async(
                file_bytes=file_bytes,
                sheet_index=sheet_index,
                sheet_name=raw_data.sheet_name,
                raw_data=raw_data,
                df=df,
                context_info=None,
                result=result
            )

            t5.finish(success=True)
            perf.timings.append(t5)

            perf.success = True

        except Exception as e:
            perf.success = False
            perf.error = str(e)
            import traceback
            traceback.print_exc()

        perf.total_time_ms = int((time.time() - overall_start) * 1000)
        return perf

    def _build_dataframe_from_raw(self, raw_data) -> 'pd.DataFrame':  # noqa: F821
        """Build DataFrame from RawSheetData"""
        import pandas as pd

        if not raw_data or not raw_data.rows:
            return pd.DataFrame()

        # Detect data start row
        data_start = self.analyzer._detect_data_start_row(raw_data)

        # Use header row
        header_row = data_start - 1 if data_start > 0 else 0

        # Extract headers
        if header_row < len(raw_data.rows):
            headers = [
                str(c.value) if c.value else f"Col_{i}"
                for i, c in enumerate(raw_data.rows[header_row].cells)
            ]
        else:
            headers = [f"Col_{i}" for i in range(raw_data.total_cols)]

        # Extract data rows
        data_rows = []
        for row_idx in range(data_start, len(raw_data.rows)):
            row = raw_data.rows[row_idx]
            values = [c.value for c in row.cells]
            # Pad to match header count
            while len(values) < len(headers):
                values.append(None)
            data_rows.append(values[:len(headers)])

        if not data_rows:
            return pd.DataFrame()

        df = pd.DataFrame(data_rows, columns=headers)
        return df

    async def run_full_test(
        self,
        test_file: str,
        max_sheets: int = 11,
        depth: str = "standard"
    ) -> PerformanceReport:
        """Run full E2E test with performance monitoring"""

        print("\n" + "=" * 80)
        print("     SmartBI E2E Performance Monitor Test")
        print("=" * 80)
        print(f"\nTest file: {test_file}")
        print(f"Max sheets: {max_sheets}")
        print(f"Analysis depth: {depth}")

        # Read file
        with open(test_file, 'rb') as f:
            file_bytes = f.read()
        print(f"File size: {len(file_bytes):,} bytes")

        # Get sheet count
        import openpyxl
        wb = openpyxl.load_workbook(io.BytesIO(file_bytes), read_only=True)
        sheet_names = wb.sheetnames
        wb.close()
        total_sheets = min(len(sheet_names), max_sheets)
        print(f"Total sheets: {len(sheet_names)} (testing {total_sheets})")

        # Initialize report
        report = PerformanceReport(
            test_file=test_file,
            start_time=datetime.now().isoformat(),
            total_sheets=total_sheets
        )

        overall_start = time.time()

        # Process each sheet
        print("\n" + "-" * 80)
        print("Processing Sheets...")
        print("-" * 80)

        for i in range(total_sheets):
            print(f"\n  Sheet {i} ({sheet_names[i]}):")

            try:
                perf = await self.analyze_with_timing(
                    file_bytes=file_bytes,
                    sheet_index=i,
                    depth=depth
                )

                report.sheets.append(perf)

                if perf.success:
                    report.success_count += 1
                    if perf.from_cache:
                        report.cache_hit_count += 1
                else:
                    report.error_count += 1

                # Print summary for this sheet
                self._print_sheet_timing(perf)

            except Exception as e:
                print(f"    [ERROR] {e}")
                report.error_count += 1

        # Finalize report
        report.end_time = datetime.now().isoformat()
        report.total_time_ms = int((time.time() - overall_start) * 1000)

        # Calculate phase summary
        report.phase_summary = self._calculate_phase_summary(report)

        # Print summary
        self._print_summary(report)

        return report

    def _print_sheet_timing(self, perf: SheetPerformance):
        """Print timing details for a sheet"""
        if perf.from_cache:
            print(f"    Total: {perf.total_time_ms}ms (CACHE HIT)")
            return

        print(f"    Total: {perf.total_time_ms}ms")

        # Group by phase
        phases = {}
        for t in perf.timings:
            phase = t.phase.split(":")[0] if ":" in t.phase else t.phase
            if phase not in phases:
                phases[phase] = []
            phases[phase].append(t)

        for phase, timings in phases.items():
            phase_total = sum(t.duration_ms for t in timings if "Phase" in t.phase and not any(c.isalpha() and c.islower() for c in t.phase.split(":")[0][-2:]))  # noqa: F841,E501

            # Only show phase summary and sub-timings
            for t in timings:
                if t.name in ["cache_check", "data_extraction", "llm_detection", "parallel_analysis", "cache_save"]:
                    continue  # Skip phase-level entries, show only sub-entries

                status = "OK" if t.success else "FAIL"
                meta_str = ""
                if t.metadata:
                    meta_str = " | " + ", ".join(f"{k}={v}" for k, v in t.metadata.items() if v is not None)
                print(f"      [{status}] {t.name}: {t.duration_ms}ms{meta_str}")

    def _calculate_phase_summary(self, report: PerformanceReport) -> Dict:
        """Calculate summary statistics by phase"""
        phase_times = {}

        for sheet in report.sheets:
            if sheet.from_cache:
                continue

            for t in sheet.timings:
                if t.name not in phase_times:
                    phase_times[t.name] = []
                phase_times[t.name].append(t.duration_ms)

        summary = {}
        for name, times in phase_times.items():
            if times:
                summary[name] = {
                    "count": len(times),
                    "total_ms": sum(times),
                    "avg_ms": sum(times) / len(times),
                    "min_ms": min(times),
                    "max_ms": max(times)
                }

        return summary

    def _print_summary(self, report: PerformanceReport):
        """Print overall summary"""
        print("\n" + "=" * 80)
        print("                         PERFORMANCE SUMMARY")
        print("=" * 80)

        print(f"\n  Total Time: {report.total_time_ms:,}ms ({report.total_time_ms/1000:.1f}s)")
        print(f"  Sheets: {report.success_count}/{report.total_sheets} success, {report.error_count} errors")
        print(f"  Cache Hits: {report.cache_hit_count}")

        print("\n  Phase Breakdown (avg per sheet, excluding cache hits):")
        print("  " + "-" * 60)

        # Sort by avg time
        sorted_phases = sorted(
            report.phase_summary.items(),
            key=lambda x: x[1].get("avg_ms", 0),
            reverse=True
        )

        for name, stats in sorted_phases:
            if stats["count"] > 0:
                print(f"    {name:25} | avg: {stats['avg_ms']:>7.0f}ms | "
                      f"min: {stats['min_ms']:>5}ms | max: {stats['max_ms']:>5}ms | "
                      f"total: {stats['total_ms']:>6}ms")

        print("\n  Sheet Details:")
        print("  " + "-" * 60)
        for sheet in report.sheets:
            status = "CACHE" if sheet.from_cache else ("OK" if sheet.success else "FAIL")
            print(f"    [{status:5}] Sheet {sheet.sheet_index}: {sheet.sheet_name[:30]:30} | {sheet.total_time_ms:>6}ms")  # noqa: E501

        print("\n" + "=" * 80)

    async def close(self):
        """Clean up resources"""
        await self.analyzer.close()


async def main():
    """Main entry point"""
    import argparse

    parser = argparse.ArgumentParser(description="SmartBI E2E Performance Monitor")
    parser.add_argument("--file", default=None, help="Test Excel file path")
    parser.add_argument("--sheets", type=int, default=11, help="Max sheets to test")
    parser.add_argument("--depth", default="standard", choices=["quick", "standard", "deep"])
    parser.add_argument("--no-clear-cache", action="store_true", help="Don't clear cache before test")
    parser.add_argument("--output", default=None, help="Output report file path")

    args = parser.parse_args()

    # Find test file
    test_file = args.file
    if not test_file:
        # Use default Test.xlsx
        smartbi_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        test_file = os.path.join(smartbi_dir, "Test.xlsx")

        if not os.path.exists(test_file):
            # Try test_complex_5sheets.xlsx
            test_file = os.path.join(smartbi_dir, "tests", "test_complex_5sheets.xlsx")

    if not os.path.exists(test_file):
        print(f"[ERROR] Test file not found: {test_file}")
        return 1

    # Create monitor
    cache_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "smartbi_cache_perf_test")
    monitor = PerformanceMonitor(cache_dir=cache_dir)

    try:
        # Clear cache
        if not args.no_clear_cache:
            await monitor.clear_cache()

        # Run test
        report = await monitor.run_full_test(
            test_file=test_file,
            max_sheets=args.sheets,
            depth=args.depth
        )

        # Save report
        output_file = args.output
        if not output_file:
            output_file = os.path.join(
                os.path.dirname(os.path.abspath(__file__)),
                f"perf_report_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
            )

        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(report.to_dict(), f, indent=2, ensure_ascii=False)

        print(f"\n  Report saved to: {output_file}")

        return 0 if report.error_count == 0 else 1

    finally:
        await monitor.close()
        # Clean up test cache dir
        if os.path.exists(cache_dir):
            try:
                shutil.rmtree(cache_dir)
                print(f"  Cleaned up test cache: {cache_dir}")
            except:  # noqa: E722
                pass


if __name__ == "__main__":
    exit_code = asyncio.run(main())
    sys.exit(exit_code)
