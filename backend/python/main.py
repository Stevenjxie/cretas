from __future__ import annotations
"""
Unified Python Services Entry Point

Consolidates all Python modules into a single FastAPI application:
- SmartBI: Data analysis, Excel parsing, field detection, forecasting
- Efficiency Recognition: Video/image analysis for worker efficiency
- Scene Intelligence: LLM-powered factory scene understanding
- Client Requirements: Customer feedback system

Port: 8083
"""
import sys
from pathlib import Path

# Add smartbi directory to path so 'services' module can be found
_smartbi_dir = Path(__file__).parent / "smartbi"
if str(_smartbi_dir) not in sys.path:
    sys.path.insert(0, str(_smartbi_dir))

import logging  # noqa: E402
import os  # noqa: E402
from contextlib import asynccontextmanager  # noqa: E402
from datetime import datetime  # noqa: E402

from fastapi import FastAPI, Request  # noqa: E402
from fastapi.middleware.cors import CORSMiddleware  # noqa: E402
from fastapi.middleware.gzip import GZipMiddleware  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402

from slowapi import Limiter, _rate_limit_exceeded_handler  # noqa: E402
from slowapi.errors import RateLimitExceeded  # noqa: E402
from slowapi.util import get_remote_address  # noqa: E402

from prometheus_fastapi_instrumentator import Instrumentator  # noqa: E402

from auth_middleware import JWTAuthMiddleware  # noqa: E402
from common.middleware.correlation import CorrelationIdMiddleware, CorrelationIdLogFilter  # noqa: E402
from common.responses import ApiException, error_response, ErrorCode  # noqa: E402

# Import settings from smartbi config
from smartbi.config import get_settings  # noqa: E402

# Import SmartBI API routers
from smartbi.api import (  # noqa: E402
    excel,
    excel_async,  # B MVP (Apr 20 2026, task #323): async upload endpoints
    field,
    metrics,
    forecast,
    insight,
    chart,
    analysis,
    ml,
    linucb,
    chat,
    db_analysis,
    classifier,
    cross_sheet,
    yoy,
    statistical,
    analysis_cache,
    ai_proxy,
    benchmark,
    finance_extract,
    data_sync,
    restaurant_analytics,
    restaurant_ops_gold,
    restaurant_ops_recipes,
    production_ai,
    financial_dashboard,
    layout,
    nl_to_sql,
    whatif,
    rfm,
    financial_ratios,
    supplier_price,
    price_anomaly,
)
from smartbi.api import restaurant_sections  # noqa: E402
from smartbi.api import restaurant_value  # noqa: E402  (#56 价值可视化回馈回路, 2026-06-04)
from smartbi.api import restaurant_cost_card  # noqa: E402  (#57 成本卡/出菜反推, 2026-06-04)
from smartbi.api import intent_analysis  # noqa: E402
from smartbi.api import revenue_report  # noqa: E402  (Phase G2, PR #516 — was unwired)
from smartbi.api import twodfire_ingest  # noqa: E402  (二维火 POS skeleton, 2026-06-04)
from smartbi.api import sales_preset  # noqa: E402  (Sprint 4 W2 S-REPORTS-PRESETS — top 5 active + 9 Sprint 5 stubs)
from smartbi.api import upload_status  # noqa: E402  (Phase IIa ops dashboard, 2026-05-14)
from smartbi.api import restaurant_health_check  # noqa: E402  (G4 AI 经营体检表, 2026-06-03)
from smartbi.api.materialized_analytics import router as materialized_analytics_router  # noqa: E402
from smartbi.capability.api import router as capability_router  # noqa: E402

# OTA (self-hosted Expo Updates v1 server) — see docs/superpowers/specs/
# 2026-05-11-self-hosted-ota-spec.md and PR #363.
from ota.api import endpoints as ota_endpoints  # noqa: E402

# Import Efficiency Recognition API routers (optional - requires opencv)
try:
    from efficiency_recognition.api import (
        router as efficiency_router,
        stream_router as efficiency_stream_router,
        photo_router as efficiency_photo_router,
        tracking_router as efficiency_tracking_router,
        cost_router as efficiency_cost_router,
        recording_router as efficiency_recording_router,
        scene_router as efficiency_scene_router,
        whisper_router as efficiency_whisper_router,
    )
    _efficiency_available = True
except ImportError as e:
    _efficiency_available = False
    import logging as _log
    _log.getLogger(__name__).warning(f"Efficiency Recognition not available: {e}")

# Import Scene Intelligence API router (optional)
try:
    from scene_intelligence import scene_router as scene_intelligence_router
    _scene_available = True
except ImportError as e:
    _scene_available = False
    import logging as _log
    _log.getLogger(__name__).warning(f"Scene Intelligence not available: {e}")

# Import Client Requirement API router (wizard_api.py is the complete router with
# legacy endpoints + wizard endpoints + gap-analysis + guide-config)
from client_requirement import wizard_api as client_requirement_routes  # noqa: E402

# Import Completeness Calculator API router (optional - requires asyncpg)
try:
    from client_requirement import completeness_calculator as completeness_routes
    _completeness_available = True
except ImportError as e:
    _completeness_available = False
    import logging as _log
    _log.getLogger(__name__).warning(f"Completeness Calculator not available: {e}")

# Import Food Knowledge Base API router (optional - requires asyncpg)
try:
    from food_kb.api import knowledge as food_kb_api
    from food_kb.api import industry_report as food_kb_industry_report_api
    from food_kb.api import manual_chat as food_kb_manual_chat_api
    _food_kb_available = True
except ImportError as e:
    _food_kb_available = False
    import logging as _log
    _log.getLogger(__name__).warning(f"Food Knowledge Base not available: {e}")

# Import Food KB Feedback API router (optional - requires asyncpg)
try:
    from food_kb.api.feedback import router as food_kb_feedback_router
    _food_kb_feedback_available = True
except ImportError as e:
    _food_kb_feedback_available = False
    import logging as _log
    _log.getLogger(__name__).warning(f"Food KB Feedback not available: {e}")

# Import Foreign Object Detection API router (optional - requires Pillow, onnxruntime)
try:
    from foreign_object_detection.api import router as fod_router
    _fod_available = True
except ImportError as e:
    _fod_available = False
    import logging as _log
    _log.getLogger(__name__).warning(f"Foreign Object Detection not available: {e}")

# Phase 2B-α (Apr 29 2026): AI intent matching layer.
# Java AIIntentService.matchIntent() proxies stages 5-8 here after stages 1-4
# + L1/L2 cache miss. See spec §5.2 + .claude/rules/ai-intent-tool-skill-architecture.md.
try:
    from ai.api import router as ai_router
    _ai_module_available = True
except ImportError as e:
    _ai_module_available = False
    import logging as _log
    _log.getLogger(__name__).warning(f"AI intent module not available: {e}")

# Import LLM API router (multi-provider with fallback chain)
try:
    from llm.api import endpoints as llm_api
    _llm_available = True
except ImportError as e:
    _llm_available = False
    import logging as _log
    _log.getLogger(__name__).error(f"LLM Router not available: {e}")

# Configure logging with rotation
_log_level = logging.DEBUG if get_settings().debug else logging.INFO
_log_format = "%(asctime)s - %(name)s - %(levelname)s - [%(correlation_id)s] - %(message)s"


class _SafeFormatter(logging.Formatter):
    """Formatter that injects a default correlation_id when missing from the record.

    During shutdown or in background threads, log records may bypass the
    CorrelationIdLogFilter on the root logger and arrive at the handler
    without a correlation_id attribute. Python 3.8's %-style formatting then
    raises KeyError('correlation_id'). This formatter patches the record with
    the contextvar default ("-") so the format string never fails."""

    def format(self, record: logging.LogRecord) -> str:
        if not hasattr(record, 'correlation_id'):
            record.correlation_id = '-'
        return super().format(record)


_log_formatter = _SafeFormatter(_log_format)

# Correlation ID log filter (injects correlation_id from contextvar into log records)
_correlation_filter = CorrelationIdLogFilter()

# Root logger setup
_root_logger = logging.getLogger()
_root_logger.setLevel(_log_level)
_root_logger.addFilter(_correlation_filter)

# Console handler (always)
_console_handler = logging.StreamHandler()
_console_handler.setFormatter(_log_formatter)
_root_logger.addHandler(_console_handler)

# File handler with rotation (production only)
if os.environ.get("PYTHON_ENV", "production") == "production":
    from logging.handlers import TimedRotatingFileHandler as _TRFH
    _log_dir = os.path.dirname(os.path.abspath(__file__))
    _file_handler = _TRFH(
        os.path.join(_log_dir, "python-service.log"),
        when="midnight", backupCount=14, encoding="utf-8"
    )
    _file_handler.setFormatter(_log_formatter)
    _root_logger.addHandler(_file_handler)

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan handler"""
    logger.info("Python Services starting up...")
    logger.info(f"Debug mode: {get_settings().debug}")
    logger.info(f"LLM Model: {get_settings().llm_model}")

    # ──────────────────────────────────────────────────────────────────
    # Leader election (PR-2 of multi-worker enablement; see PR-1 spike
    # docs/qa-audits/2026-05-07-uvicorn-workers-spike.md §5).
    #
    # Multi-worker uvicorn (--workers N) spawns N independent Python
    # processes, each running this lifespan. Without a leader gate, the
    # 5 background tasks below run N times in parallel, causing:
    #   - restaurant_etl: 4× concurrent INSERT/UPSERT → deadlock risk
    #   - narrative/chat-session/llm cache pruners: 4× wasteful DELETE
    #   - template embedding warmer: 4× idempotent but wasteful
    #
    # Strategy: the first worker to acquire an exclusive flock on a
    # per-env lock file becomes leader; followers skip the gated tasks.
    # If the leader exits, OS releases the fd → next worker spawned by
    # uvicorn master grabs the lock at startup. systemd Restart=always
    # handles the respawn loop. Single-worker deployments (--workers 1
    # default) work unchanged: only one worker, it always wins.
    #
    # Per-env naming (prod / test) avoids collision when both 8083
    # (prod) and 8084 (test) run on the same box. POSTGRES_DB derives
    # the env name — same logic the rest of the service uses.
    # ──────────────────────────────────────────────────────────────────
    # `os` is imported at module scope (L22), but this function reassigns
    # `import os` later (L260, L283) — Python's scoping rule then treats
    # `os` as local to lifespan(), making it Unbound here. Re-import to
    # bind the local before first use.
    import os as _os
    import fcntl
    import pathlib
    _leader_env_name = "prod" if _os.getenv("POSTGRES_DB") == "smartbi_prod_db" else "test"
    _leader_lock_path = pathlib.Path(f"/tmp/cretas-python-leader-{_leader_env_name}.lock")
    # _leader_fd is intentionally never closed — held for worker lifetime
    # so the kernel keeps the flock. On process exit OS releases it.
    _leader_fd = None
    try:
        _leader_fd = _os.open(_leader_lock_path, _os.O_CREAT | _os.O_RDWR, 0o644)
        fcntl.flock(_leader_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        _is_leader = True
        # Best-effort PID stamp for ops debugging; ignore failures.
        try:
            pid_bytes = f"{_os.getpid()}\n".encode()
            _os.lseek(_leader_fd, 0, _os.SEEK_SET)
            _os.write(_leader_fd, pid_bytes)
            _os.ftruncate(_leader_fd, len(pid_bytes))
        except OSError:
            pass
        logger.info(
            f"[leader] PID={_os.getpid()} env={_leader_env_name} "
            f"acquired lock {_leader_lock_path}"
        )
    except (BlockingIOError, OSError) as _lock_ex:
        _is_leader = False
        logger.info(
            f"[follower] PID={_os.getpid()} env={_leader_env_name} "
            f"another worker holds leader lock — gated background tasks skipped "
            f"({type(_lock_ex).__name__})"
        )

    # Check PostgreSQL connection if enabled
    if get_settings().postgres_enabled:
        try:
            from smartbi.database.connection import test_connection, is_postgres_enabled
            if is_postgres_enabled() and test_connection():
                logger.info("PostgreSQL connection established")
            else:
                logger.warning("PostgreSQL enabled but connection failed")
        except Exception as e:
            logger.warning(f"PostgreSQL initialization error: {e}")
    else:
        logger.info("PostgreSQL is disabled")

    # Initialize Food Knowledge Base services
    if _food_kb_available:
        try:
            settings = get_settings()
            db_url = settings.food_kb_db_url

            # Configure embedding service
            from food_kb.services.embedding import configure as configure_embedding, get_embedding, close as close_embedding  # noqa: E501
            configure_embedding(
                api_key=settings.llm_api_key,
                base_url=settings.llm_base_url,
                model=settings.food_kb_embedding_model,
                dims=settings.food_kb_embedding_dims,
            )

            # Initialize knowledge retriever
            from food_kb.services.knowledge_retriever import get_knowledge_retriever
            retriever = get_knowledge_retriever()
            await retriever.initialize(db_url, embedding_fn=get_embedding)

            # Configure DashScope Reranker (Phase 1 RAG optimization)
            import os
            from food_kb.services.reranker import get_reranker
            reranker = get_reranker()
            reranker_enabled = os.environ.get("FOOD_KB_RERANKER_ENABLED", "true").lower() == "true"
            reranker.configure(
                api_key=settings.llm_api_key,
                model=os.environ.get("FOOD_KB_RERANKER_MODEL", "gte-rerank-v2"),
                enabled=reranker_enabled,
            )

            # Initialize document ingester
            from food_kb.services.document_ingester import get_document_ingester
            ingester = get_document_ingester()
            await ingester.initialize(db_url, embedding_fn=get_embedding)

            # Load NER entity dictionary (uses retriever's pool)
            from food_kb.services.food_ner_service import get_food_ner_service
            ner_service = get_food_ner_service()
            if retriever.is_ready() and retriever._pool:
                await ner_service.load_dictionary(retriever._pool)

            # Load NER ONNX model (if available)
            import os
            ner_model_dir = os.environ.get(
                "FOOD_NER_MODEL_DIR",
                os.path.join(os.path.dirname(__file__), "models", "food-ner-onnx")
            )
            if os.path.isdir(ner_model_dir):
                ner_service.load_model(ner_model_dir)

            logger.info("Food Knowledge Base initialized successfully")
        except Exception as e:
            logger.warning(f"Food Knowledge Base initialization failed: {e}")

    # Initialize shared LLM HTTP client (connection pool for all LLM calls)
    try:
        from common.llm_client import init_llm_client
        settings = get_settings()
        await init_llm_client(settings.llm_base_url, settings.llm_api_key)
    except Exception as e:
        logger.warning(f"Shared LLM client initialization failed: {e}")

    # Enable LLM usage metrics (writes to smart_bi_llm_usage via pg pool)
    try:
        from common.llm_metrics import enable_llm_metrics
        enable_llm_metrics()
    except Exception as e:
        logger.warning(f"LLM metrics enable failed: {e}")

    # P0 数据主权: enable LLM egress audit (writes to smart_bi_llm_egress_audit via pg pool)
    try:
        from common.llm_egress_audit import enable_egress_audit
        enable_egress_audit()
    except Exception as e:
        logger.warning(f"LLM egress audit enable failed: {e}")

    # Phase 3 (Apr 23 2026): template embedding index population.
    # Populates smart_bi_template_embeddings on first boot. Re-runs are
    # idempotent (ON CONFLICT). Fires in background — no blocking.
    try:
        from smartbi.services.template_embedding_index import (
            count_embeddings, populate_all,
        )
        from smartbi.config import get_pg_pool as _get_pool_emb
        _emb_pool = await _get_pool_emb()
        if _emb_pool is not None:
            existing = await count_embeddings(_emb_pool)
            # v7 #6 (Apr 26 2026): count expected sample_queries from
            # registry; if existing < expected, run populate_all (idempotent
            # ON CONFLICT DO UPDATE) to ingest new sample_queries from any
            # template extensions deployed in this release.
            expected = 0
            try:
                from smartbi.services.materialized_analytics.templates.registry import (
                    get_registry, load_all_templates,
                )
                load_all_templates()
                for t in get_registry().all():
                    expected += len(getattr(t, 'sample_queries', []))
            except Exception:
                pass
            if existing == 0 or (expected > 0 and existing < expected):
                if _is_leader:
                    logger.info(
                        f"[startup] template embedding index {existing}/{expected} — "
                        f"populating in background (leader)"
                    )
                    import asyncio as _asyncio
                    _asyncio.create_task(populate_all(_emb_pool))
                else:
                    logger.info(
                        f"[follower] template embedding warmer skipped "
                        f"({existing}/{expected}; leader will populate)"
                    )
            else:
                logger.info(f"[startup] template embedding index has {existing} rows, skipping populate")
    except Exception as e:
        logger.warning(f"[startup] template embedding warmer failed: {e}")

    # Agent layer narrative_cache TTL pruner — hourly background task.
    # Gated on SMARTBI_AGENT_LAYER_ENABLED so disabled tenants don't touch the table.
    _narrative_pruner_task = None
    if os.getenv("SMARTBI_AGENT_LAYER_ENABLED", "").lower() in ("1", "true", "yes"):
        try:
            import asyncio as _asyncio
            from smartbi.agent.narrative_cache import NarrativeCacheService
            from smartbi.config import get_pg_pool as _get_pg_pool

            async def _prune_narrative_cache_forever():
                # First tick delayed 60s to let pool/warmers settle post-startup.
                await _asyncio.sleep(60)
                while True:
                    try:
                        pool = await _get_pg_pool()
                        svc = NarrativeCacheService(pool)
                        deleted = await svc.prune_expired()
                        if deleted > 0:
                            logger.info(f"[narrative-cache] pruned {deleted} expired rows")
                    except Exception as ex:
                        logger.warning(f"[narrative-cache] prune failed: {ex}")
                    # 1 hour between prunes — cache TTL is 24h, so max stale window is ~1h.
                    await _asyncio.sleep(3600)

            if _is_leader:
                _narrative_pruner_task = _asyncio.create_task(_prune_narrative_cache_forever())
                logger.info("[leader] narrative_cache hourly pruner armed")
            else:
                logger.info("[follower] narrative_cache pruner skipped (leader handles)")
        except Exception as e:
            logger.warning(f"[startup] narrative_cache pruner init failed: {e}")

    # Apr 24 2026 Plan C Phase 6: hourly restaurant ops ETL for all RESTAURANT
    # factories. Keeps Gold fresh without user intervention. First run 30s
    # after startup so pools/warmers settle; subsequent runs every hour.
    # Apr 28 2026 P1-11: shortened initial sleep 120s→30s for faster recovery;
    # added startup catchup tick (runs immediately if last ETL > 1.5h ago);
    # switched per-factory call to run_full_etl_with_retry (3 retries, logged).
    _restaurant_etl_task = None
    if os.getenv("RESTAURANT_OPS_ETL_ENABLED", "true").lower() in ("1", "true", "yes"):
        try:
            import asyncio as _asyncio
            import asyncpg as _asyncpg
            from config import get_settings as _get_settings
            from smartbi.config import get_pg_pool as _get_pg_pool_etl
            from smartbi.gold.restaurant_ops_etl import run_full_etl_with_retry

            async def _run_restaurant_ops_etl_forever():
                await _asyncio.sleep(30)  # post-audit P1-11: shortened for faster recovery

                # ── startup catchup tick ──────────────────────────────────────
                # If the cron worker was down 1.5h+, run once immediately so
                # Gold doesn't stay stale until the first scheduled tick.
                try:
                    smartbi_pool_catchup = await _get_pg_pool_etl()
                    if smartbi_pool_catchup is not None:
                        cretas_url_catchup = _get_settings().food_kb_db_url
                        cretas_conn_catchup = await _asyncpg.connect(cretas_url_catchup)
                        try:
                            f_rows_catchup = await cretas_conn_catchup.fetch(
                                "SELECT id FROM factories WHERE type = 'RESTAURANT'"
                            )
                            factory_ids_catchup = [r["id"] for r in f_rows_catchup]
                        finally:
                            await cretas_conn_catchup.close()

                        if factory_ids_catchup:
                            # NOTE: agg_restaurant_daily_ops has FORCE RLS on app.factory_id GUC.
                            # Query without GUC set returns NULL silently (rows filtered by RLS),
                            # which causes catchup to over-run on every startup. Acceptable cost
                            # — retry helper handles per-factory failures, and catchup runs are
                            # rare (only on cron restart). Refine in Task 1.6 if real-window
                            # verify shows a problem.
                            last_agg = await smartbi_pool_catchup.fetchval(
                                "SELECT MAX(computed_at) FROM agg_restaurant_daily_ops"
                                " WHERE factory_id = ANY($1::varchar[])",
                                factory_ids_catchup,
                            )
                            if not last_agg or (datetime.utcnow() - last_agg).total_seconds() > 5400:  # 1.5h
                                logger.info("[startup catchup] last ETL > 1.5h ago, running tick now")
                                cretas_pool_catchup = await _asyncpg.create_pool(
                                    cretas_url_catchup, min_size=1, max_size=3, command_timeout=60,
                                )
                                try:
                                    for fid in factory_ids_catchup:
                                        try:
                                            await run_full_etl_with_retry(
                                                cretas_pool_catchup, smartbi_pool_catchup, fid
                                            )
                                        except Exception as e:
                                            logger.warning(f"[startup catchup] ETL failed for {fid}: {e}")
                                finally:
                                    await cretas_pool_catchup.close()
                except Exception as e:
                    logger.warning(f"[startup catchup] check failed, skipping catchup: {e}")
                # ── end catchup tick ──────────────────────────────────────────

                while True:
                    try:
                        smartbi_pool = await _get_pg_pool_etl()
                        if smartbi_pool is None:
                            logger.warning("[restaurant-etl] smartbi pool unavailable, skipping tick")
                        else:
                            # Discover RESTAURANT factories from cretas_db.factories.type.
                            cretas_url = _get_settings().food_kb_db_url
                            cretas_conn = await _asyncpg.connect(cretas_url)
                            try:
                                f_rows = await cretas_conn.fetch(
                                    "SELECT id FROM factories WHERE type = 'RESTAURANT'"
                                )
                                factory_ids = [r["id"] for r in f_rows]
                            finally:
                                await cretas_conn.close()

                            if not factory_ids:
                                logger.info("[restaurant-etl] no RESTAURANT factories to sync")
                            else:
                                cretas_pool = await _asyncpg.create_pool(
                                    cretas_url, min_size=1, max_size=3, command_timeout=60,
                                )
                                try:
                                    summary_counts = {"total": 0, "errors": 0}
                                    for fid in factory_ids:
                                        try:
                                            stats = await run_full_etl_with_retry(
                                                cretas_pool, smartbi_pool, fid
                                            )
                                            summary_counts["total"] += 1
                                            if stats.errors:
                                                summary_counts["errors"] += 1
                                                logger.warning(
                                                    f"[restaurant-etl] {fid} errors={stats.errors}"
                                                )
                                        except Exception as fe:
                                            # already logged + persisted in run_full_etl_with_retry
                                            summary_counts["errors"] += 1
                                            logger.warning(f"[restaurant-etl] ETL final failure for {fid}: {fe}")
                                            continue  # 不阻塞下个工厂
                                    logger.info(
                                        f"[restaurant-etl] tick done: {summary_counts['total']} factories, "
                                        f"{summary_counts['errors']} errors"
                                    )
                                finally:
                                    await cretas_pool.close()
                    except Exception as ex:
                        logger.warning(f"[restaurant-etl] tick failed: {ex}")
                    # 1 hour between runs — ETL is idempotent so overlap risk is low.
                    await _asyncio.sleep(3600)

            if _is_leader:
                _restaurant_etl_task = _asyncio.create_task(_run_restaurant_ops_etl_forever())
                logger.info("[leader] restaurant-ops hourly ETL armed")
            else:
                # restaurant_etl is the deadlock-risk task per PR-1 §背景 — must be leader-only
                logger.info("[follower] restaurant-ops ETL skipped (leader handles; avoids 4× INSERT deadlock)")
        except Exception as e:
            logger.warning(f"[startup] restaurant-ops ETL init failed: {e}")

    # Apr 26 2026 v2 conversation memory: half-hourly pruner for expired chat
    # sessions (TTL 1h rolling). Cheap DELETE keyed on idx_chat_session_expires.
    _chat_session_pruner_task = None
    try:
        import asyncio as _asyncio
        from smartbi.config import get_pg_pool as _get_pg_pool_cs
        from smartbi.services.chat_session_service import ChatSessionService as _CSS

        async def _prune_chat_sessions_forever():
            await _asyncio.sleep(90)  # let pool warm
            while True:
                try:
                    from smartbi.services.smartbi_metrics import PRUNER_RUNS, PRUNER_DELETED
                    pool = await _get_pg_pool_cs()
                    if pool is not None:
                        deleted = await _CSS(pool).prune_expired()
                        PRUNER_RUNS.labels(table='chat_session', outcome='ok').inc()
                        if deleted > 0:
                            PRUNER_DELETED.labels(table='chat_session').inc(deleted)
                            logger.info(f"[chat-session] pruned {deleted} expired sessions")
                except Exception as ex:
                    try:
                        from smartbi.services.smartbi_metrics import PRUNER_RUNS
                        PRUNER_RUNS.labels(table='chat_session', outcome='error').inc()
                    except Exception:
                        pass
                    logger.warning(f"[chat-session] prune failed: {ex}")
                await _asyncio.sleep(1800)  # 30 min cadence

        if _is_leader:
            _chat_session_pruner_task = _asyncio.create_task(_prune_chat_sessions_forever())
            logger.info("[leader] chat-session 30-min pruner armed")
        else:
            logger.info("[follower] chat-session pruner skipped (leader handles)")
    except Exception as e:
        logger.warning(f"[startup] chat-session pruner init failed: {e}")

    # v4 B2-B (Apr 26 2026): hourly pruner for LLM answer cache (24h TTL).
    # Reuses smartbi pool; same pattern as chat-session pruner.
    _llm_cache_pruner_task = None
    try:
        import asyncio as _asyncio
        from smartbi.config import get_pg_pool as _get_pg_pool_lac
        from smartbi.services.llm_answer_cache import LlmAnswerCache as _LAC

        async def _prune_llm_cache_forever():
            await _asyncio.sleep(120)
            while True:
                try:
                    from smartbi.services.smartbi_metrics import PRUNER_RUNS, PRUNER_DELETED
                    pool = await _get_pg_pool_lac()
                    if pool is not None:
                        deleted = await _LAC(pool).prune_expired()
                        PRUNER_RUNS.labels(table='llm_cache', outcome='ok').inc()
                        if deleted > 0:
                            PRUNER_DELETED.labels(table='llm_cache').inc(deleted)
                            logger.info(f"[llm-cache] pruned {deleted} expired rows")
                except Exception as ex:
                    try:
                        from smartbi.services.smartbi_metrics import PRUNER_RUNS
                        PRUNER_RUNS.labels(table='llm_cache', outcome='error').inc()
                    except Exception:
                        pass
                    logger.warning(f"[llm-cache] prune failed: {ex}")
                await _asyncio.sleep(3600)  # 1h cadence

        if _is_leader:
            _llm_cache_pruner_task = _asyncio.create_task(_prune_llm_cache_forever())
            logger.info("[leader] llm-answer-cache hourly pruner armed")
        else:
            logger.info("[follower] llm-answer-cache pruner skipped (leader handles)")
    except Exception as e:
        logger.warning(f"[startup] llm-cache pruner init failed: {e}")

    # Phase 2B-α (Apr 29 2026): AI intent matching orchestrator + snapshot.
    # Wires app.state.ai_orchestrator (3 matchers) so /api/ai/intent/match resolves
    # at runtime. Snapshot loaded if pg_pool available; absent pool degrades to
    # empty visible_intents (orchestrator still runs with empty rows). See spec §6.5.
    _ai_snapshot_refresh_task = None
    _ai_learning_cron_task = None
    if _ai_module_available:
        try:
            from ai.matcher.classifier import ClassifierMatcher
            from ai.matcher.llm import LlmMatcher
            from ai.matcher.semantic import SemanticMatcher
            from ai.orchestrator import Orchestrator
            # Phase 2B-β (Apr 30 2026): wire all sub-modules built in T1-T16 (W6).
            from ai.router.semantic_router import SemanticRouter
            from ai.router.llm_tier_selector import LlmTierSelector
            from ai.scoring.calibration import Calibrator
            from ai.scoring.intent_scoring import IntentScorer
            from ai.rag.retrieval import RAGRetriever
            from ai.rag.evaluator import RAGEvaluator

            # AI matcher reads ai_intent_configs which lives in cretas_db (not smartbi_db).
            # Use get_cretas_pool (food_kb_db_url → cretas_prod_db) instead of get_pg_pool
            # (smartbi_prod_db). SemanticMatcher tolerates None pool — falls through to
            # next stage. Stash on app.state for /intent/cache/invalidate handler.
            ai_pg_pool = None
            try:
                from smartbi.config import get_cretas_pool as _get_ai_pg_pool
                ai_pg_pool = await _get_ai_pg_pool()
                app.state.pg_pool = ai_pg_pool
            except Exception as ex:
                logger.warning(f"[startup] AI snapshot pool not available: {ex}")

            # β: construct sub-modules. Cold-start safe: empty Calibrator coefs,
            # default IntentScorer weights, default RAGEvaluator thresholds.
            #
            # F6 fix-pass (I-NEW-4): LlmTierSelector dark-shipped behind env flag.
            # Disabled by default — its pre-stage-8 LLM call adds 1-3s user-visible
            # latency. Operators opt in via AI_TIER_SELECTOR_ENABLED=true once a
            # latency budget review approves it. Orchestrator already accepts None.
            _tier_selector_enabled = os.environ.get("AI_TIER_SELECTOR_ENABLED", "false").lower() == "true"
            _ai_sem_router = SemanticRouter()
            _ai_tier_selector = LlmTierSelector() if _tier_selector_enabled else None
            _ai_calibrator = Calibrator(coefs={})
            _ai_scorer = IntentScorer()
            _ai_rag_retriever = RAGRetriever(ai_pg_pool)
            _ai_rag_evaluator = RAGEvaluator()

            app.state.ai_orchestrator = Orchestrator(
                semantic_matcher=SemanticMatcher(ai_pg_pool),
                classifier_matcher=ClassifierMatcher(),
                llm_matcher=LlmMatcher(),
                semantic_router=_ai_sem_router,
                llm_tier_selector=_ai_tier_selector,
                calibrator=_ai_calibrator,
                scorer=_ai_scorer,
                rag_retriever=_ai_rag_retriever,
                rag_evaluator=_ai_rag_evaluator,
            )
            logger.info(
                "[startup] AI orchestrator wired (orchestrator + 3 matchers + β: router/calibrator/scorer/rag, "
                "tier_selector=%s)",
                "ENABLED" if _tier_selector_enabled else "disabled (set AI_TIER_SELECTOR_ENABLED=true to opt in)",
            )

            # Initial snapshot load + periodic refresh (~5min) per spec §5.4.
            if ai_pg_pool is not None:
                try:
                    from ai.config import default_config as _ai_cfg
                    from ai.db import load_snapshot as _ai_load_snapshot

                    snap = await _ai_load_snapshot(ai_pg_pool)
                    logger.info(f"[startup] ai_intent_configs snapshot loaded: {len(snap.rows)} rows")

                    refresh_seconds = getattr(_ai_cfg, "config_refresh_s", 300)

                    async def _refresh_ai_snapshot_forever():
                        import asyncio as _asyncio
                        while True:
                            await _asyncio.sleep(refresh_seconds)
                            try:
                                _snap = await _ai_load_snapshot(ai_pg_pool)
                                logger.debug(f"[ai-snapshot] refreshed: {len(_snap.rows)} rows")
                            except Exception as _ex:
                                logger.warning(f"[ai-snapshot] refresh failed: {_ex}")

                    import asyncio as _asyncio_ai
                    _ai_snapshot_refresh_task = _asyncio_ai.create_task(_refresh_ai_snapshot_forever())
                    logger.info(f"[startup] ai_intent_configs refresh task armed (every {refresh_seconds}s)")
                except Exception as ex:
                    logger.warning(f"[startup] AI snapshot initial load failed: {ex}")
            else:
                logger.info("[startup] AI snapshot skipped (no pg_pool); orchestrator runs with empty visible_intents")

            # Phase 2B-β C6 (Apr 30 2026): learning services as 5min cron.
            # Cron requires pg_pool — without DB, learners cannot read training_samples.
            if ai_pg_pool is not None:
                try:
                    import asyncio as _asyncio_learn
                    import json as _json_learn
                    from ai import db as _ai_db_for_kw
                    from ai.learning.keyword_learner import KeywordLearner
                    from ai.learning.expression_learner import ExpressionLearner

                    # Build existing_keywords map from current snapshot for KeywordLearner.
                    _kw_snapshot = _ai_db_for_kw.get_current_snapshot()
                    _existing_keywords: dict = {}
                    for _r in _kw_snapshot.rows:
                        _code = _r.get("intent_code")
                        if not _code:
                            continue
                        _raw_kw = _r.get("keywords") or "[]"
                        try:
                            _kw_list = _json_learn.loads(_raw_kw) if isinstance(_raw_kw, str) else _raw_kw
                            if isinstance(_kw_list, list):
                                _existing_keywords[_code] = _kw_list
                        except (_json_learn.JSONDecodeError, TypeError):
                            _existing_keywords[_code] = []

                    _kw_learner = KeywordLearner(ai_pg_pool, existing_keywords=_existing_keywords)
                    _expr_learner = ExpressionLearner(ai_pg_pool)

                    app.state.ai_learning_stop_event = _asyncio_learn.Event()

                    async def _ai_learning_cron():
                        # 5min cadence per spec §C6 — runs both learners each iteration.
                        # ParameterLearner removed (F1 fix-pass): no feedback data source —
                        # `ai_parameter_extraction_rules` is a config table Java reads, not
                        # a learning sink. β plan was speculative.
                        # Stop event cancellation path: wait_for raises TimeoutError on
                        # idle 300s window; loop exits on event set during shutdown.
                        while not app.state.ai_learning_stop_event.is_set():
                            try:
                                await _kw_learner.run_once(min_confidence=0.9)
                                await _expr_learner.run_once(min_confidence=0.95)
                            except Exception:
                                logger.exception("[ai-learning] cron iteration failed")
                            try:
                                await _asyncio_learn.wait_for(
                                    app.state.ai_learning_stop_event.wait(),
                                    timeout=300,
                                )
                            except _asyncio_learn.TimeoutError:
                                pass

                    _ai_learning_cron_task = _asyncio_learn.create_task(_ai_learning_cron())
                    logger.info("[startup] AI learning cron armed (KeywordLearner+ExpressionLearner, every 300s)")
                except Exception as ex:
                    logger.warning(f"[startup] AI learning cron init failed: {ex}")
            else:
                logger.info("[startup] AI learning cron skipped (no pg_pool)")
        except Exception as e:
            logger.warning(f"[startup] AI orchestrator init failed: {e}")

    yield

    # Shutdown: cancel narrative_cache pruner task
    if _narrative_pruner_task is not None:
        _narrative_pruner_task.cancel()
        try:
            await _narrative_pruner_task
        except Exception:
            pass

    # Shutdown: cancel restaurant-ops ETL task
    if _restaurant_etl_task is not None:
        _restaurant_etl_task.cancel()
        try:
            await _restaurant_etl_task
        except Exception:
            pass

    # Shutdown: cancel chat-session pruner task
    if _chat_session_pruner_task is not None:
        _chat_session_pruner_task.cancel()
        try:
            await _chat_session_pruner_task
        except Exception:
            pass

    # Shutdown: cancel llm-cache pruner task
    if _llm_cache_pruner_task is not None:
        _llm_cache_pruner_task.cancel()
        try:
            await _llm_cache_pruner_task
        except Exception:
            pass

    # Shutdown: cancel AI learning cron (β C6) — signal stop event then cancel.
    if hasattr(app.state, "ai_learning_stop_event"):
        try:
            app.state.ai_learning_stop_event.set()
        except Exception:
            pass
    if _ai_learning_cron_task is not None:
        _ai_learning_cron_task.cancel()
        try:
            await _ai_learning_cron_task
        except Exception:
            pass

    # Shutdown: cancel ai_intent_configs snapshot refresh task + close embedding channel
    if _ai_snapshot_refresh_task is not None:
        _ai_snapshot_refresh_task.cancel()
        try:
            await _ai_snapshot_refresh_task
        except Exception:
            pass
    if _ai_module_available:
        try:
            from ai.embedding import close_channel as _ai_close_channel
            await _ai_close_channel()
        except Exception as e:
            logger.warning(f"AI embedding channel close error: {e}")

    # Shutdown: close shared LLM HTTP client
    try:
        from common.llm_metrics import disable_llm_metrics
        await disable_llm_metrics()
    except Exception as e:
        logger.warning(f"LLM metrics disable error: {e}")
    try:
        from common.llm_egress_audit import disable_egress_audit
        await disable_egress_audit()
    except Exception as e:
        logger.warning(f"LLM egress audit disable error: {e}")
    try:
        from common.llm_client import close_llm_client
        await close_llm_client()
    except Exception as e:
        logger.warning(f"Shared LLM client shutdown error: {e}")

    # Shutdown: close food_kb connections
    if _food_kb_available:
        try:
            from food_kb.services.knowledge_retriever import get_knowledge_retriever
            from food_kb.services.document_ingester import get_document_ingester
            from food_kb.services.embedding import close as close_embedding  # noqa: F811,E501
            from food_kb.services.reranker import get_reranker
            await get_knowledge_retriever().close()
            await get_document_ingester().close()
            await get_reranker().close()
            await close_embedding()
            logger.info("Food Knowledge Base connections closed")
        except Exception as e:
            logger.warning(f"Food KB shutdown error: {e}")

    logger.info("Python Services shutting down...")


# Create FastAPI application
app = FastAPI(
    title="Python Services",
    description="Unified Python services for SmartBI analytics and client requirements",
    version="2.0.0",
    lifespan=lifespan,
    docs_url="/docs" if os.environ.get("PYTHON_ENV", "production") != "production" else None,
    redoc_url="/redoc" if os.environ.get("PYTHON_ENV", "production") != "production" else None
)

# Prometheus metrics instrumentation
# Exposes /metrics endpoint with request count, latency histograms, etc.
Instrumentator().instrument(app).expose(app, endpoint="/metrics")

# Rate limiting — exempt internal Java→Python calls (X-Internal-Secret header)


def _rate_limit_key(request: Request) -> str:
    if request.headers.get("X-Internal-Secret"):
        return "internal-exempt"
    return get_remote_address(request)


limiter = Limiter(
    key_func=_rate_limit_key,
    default_limits=["60/minute"],
    storage_uri="memory://",
)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# T4.2: Gzip compression — reduces chart JSON payloads by 60-75%
app.add_middleware(GZipMiddleware, minimum_size=1000)

# Configure CORS
settings = get_settings()
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# JWT Authentication Middleware
# Uses pure ASGI middleware (not BaseHTTPMiddleware) for reliable interception.
# Must be added AFTER CORS so CORS headers are set on 401 responses too.
app.add_middleware(
    JWTAuthMiddleware,
    jwt_secret=settings.jwt_secret,
    enabled=settings.jwt_auth_enabled,
)

# Correlation ID Middleware
# Added LAST so it wraps all other middleware (Starlette executes in reverse order).
# Reads X-Correlation-ID from Java→Python calls; generates UUID for direct calls.
app.add_middleware(CorrelationIdMiddleware)

# =====================================================
# SmartBI API Routes
# =====================================================
app.include_router(excel.router, prefix="/api/excel", tags=["Excel"])
# B MVP (task #323): excel_async already carries /api/smartbi/excel prefix
# internally; don't prepend /api/excel here.
app.include_router(excel_async.router, tags=["Excel Async"])
app.include_router(field.router, prefix="/api/field", tags=["Field Detection"])
app.include_router(metrics.router, prefix="/api/metrics", tags=["Metrics"])
app.include_router(forecast.router, prefix="/api/forecast", tags=["Forecast"])
app.include_router(insight.router, prefix="/api/insight", tags=["Insight"])
app.include_router(chart.router, prefix="/api/chart", tags=["Chart"])
app.include_router(analysis.router, prefix="/api/analysis", tags=["Analysis"])
app.include_router(intent_analysis.router, prefix="/api/analysis", tags=["Intent Analysis"])
app.include_router(ml.router, prefix="/api/ml", tags=["ML"])
app.include_router(linucb.router, prefix="/api/linucb", tags=["LinUCB"])
app.include_router(chat.router, prefix="/api/chat", tags=["Chat"])
app.include_router(cross_sheet.router, prefix="/api/smartbi", tags=["Cross-Sheet Analysis"])
app.include_router(yoy.router, prefix="/api/smartbi", tags=["YoY Comparison"])
app.include_router(statistical.router, prefix="/api/statistical", tags=["Statistical Analysis"])
app.include_router(analysis_cache.router, prefix="/api/smartbi", tags=["Analysis Cache"])
app.include_router(benchmark.router, prefix="/api/smartbi", tags=["Industry Benchmark"])
app.include_router(finance_extract.router, prefix="/api/finance", tags=["Finance Extract"])
app.include_router(restaurant_analytics.router, prefix="/api/smartbi", tags=["Restaurant Analytics"])
app.include_router(restaurant_ops_gold.router, prefix="/api/smartbi", tags=["Restaurant Ops Gold"])
app.include_router(supplier_price.router, prefix="/api/smartbi", tags=["Supplier Price Gold"])
app.include_router(price_anomaly.router, prefix="/api/smartbi", tags=["Price Anomaly Deterrence"])
app.include_router(restaurant_ops_recipes.router, prefix="/api/smartbi", tags=["Restaurant Ops Recipes"])
app.include_router(restaurant_health_check.router, prefix="/api/smartbi", tags=["Restaurant Health Check"])
app.include_router(restaurant_sections.router, tags=["Restaurant Sections"])
app.include_router(restaurant_value.router, prefix="/api/smartbi", tags=["Restaurant Value Feedback"])
app.include_router(restaurant_cost_card.router, prefix="/api/smartbi", tags=["Restaurant Cost Card"])
app.include_router(production_ai.router, prefix="/api/smartbi", tags=["Production AI"])
app.include_router(financial_dashboard.router, prefix="/api/smartbi/financial-dashboard", tags=["Financial Dashboard"])
app.include_router(layout.router, prefix="/api/smartbi", tags=["Dashboard Layout"])
app.include_router(nl_to_sql.router, prefix="/api/smartbi", tags=["NL2SQL"])
app.include_router(whatif.router, prefix="/api/smartbi/whatif", tags=["WhatIf Simulator"])
app.include_router(rfm.router, prefix="/api/smartbi", tags=["Customer RFM"])
app.include_router(financial_ratios.router, prefix="/api/smartbi", tags=["Financial Ratios"])
app.include_router(materialized_analytics_router, prefix="/api/smartbi", tags=["MaterializedAnalytics"])
# QHJ revenue report (Phase G2 / PR #516) — router has full prefix baked in
app.include_router(revenue_report.router, tags=["Revenue Report"])
# 二维火 POS ingest adapter SKELETON (2026-06-04) — router has full prefix baked in.
# Only the not-configured dead-end is live; configured sync path is a TODO.
app.include_router(twodfire_ingest.router, tags=["2dfire POS Ingest"])
# Sprint 4 W2 S-REPORTS-PRESETS (preset sales reports, top 5 active + 9 Sprint 5 stubs)
app.include_router(sales_preset.router, tags=["Sales Preset Reports"])
# Upload Status Dashboard (Phase IIa, 2026-05-14) — router has full prefix baked in
app.include_router(upload_status.router, tags=["Upload Status"])
app.include_router(capability_router)
app.include_router(ota_endpoints.router, prefix="/api/ota", tags=["OTA"])

# C-PRT-1 (Track C, 2026-05-15): 单据打印 PDF 5 endpoint
# Java PrintController 通过 RestTemplate 调本端点取 PDF bytes 流回客户端
from printing.api import print as printing_api  # noqa: E402
app.include_router(printing_api.router, prefix="/api/printing", tags=["Printing"])

# Gold layer reads — v1 Phase B pilot (§5). Finance-summary from agg_daily.
from smartbi.api import gold_reads  # noqa: E402
app.include_router(gold_reads.router, prefix="/api/smartbi", tags=["Gold Reads"])

# G2 餐饮目标拆分 + 达成率预警 (2026-06-03) — year/month/week/day target cascade
from smartbi.api import restaurant_targets as restaurant_targets_api  # noqa: E402
app.include_router(restaurant_targets_api.router, prefix="/api/smartbi", tags=["Restaurant Targets"])

# #58 Phase 1 (2026-06-04) — 周/日拆分 + 滚动预测 + 实时 pace 预警 (extends G2)
from smartbi.api import restaurant_targets_p1 as restaurant_targets_p1_api  # noqa: E402
app.include_router(restaurant_targets_p1_api.router, prefix="/api/smartbi", tags=["Restaurant Targets P1"])

# Chart Auto-Insight (U4 — POST /api/smartbi/chart-insight, JWT required)
# 🔒 RBAC: factoryId + role ALWAYS from JWT (request.state), never from request body.
# Path: /api/smartbi/chart-insight — does NOT match PUBLIC_PREFIXES entry "/api/smartbi/chart/"
# (that entry has a trailing slash; "chart-insight" contains a hyphen, not a slash), so
# JWTAuthMiddleware (registered on this top-level app) will enforce auth on every request.
# Registered here — on the LIVE uvicorn entrypoint — not in smartbi/main.py (dead module).
from smartbi.api.chart_insight import router as chart_insight_router  # noqa: E402
app.include_router(chart_insight_router, prefix="/api/smartbi", tags=["Chart Auto-Insight"])

# P2 综合分析 (multi-dim synthesis) — review + finance/sales → FactBook → grounded
# narrative + charts. Path A entrypoint for the Java COMPREHENSIVE_SYNTHESIS tool.
from smartbi.api import synthesis as synthesis_api  # noqa: E402
app.include_router(synthesis_api.router, prefix="/api/smartbi", tags=["Comprehensive Synthesis"])

# Sub-Project C Day 26: cell-level field_provenance audit (§6.3).
# Admin-gated read endpoint powering /audit/cell?type=&id=&field= page.
from smartbi.api import provenance_audit  # noqa: E402
app.include_router(
    provenance_audit.router,
    prefix="/api/smartbi/provenance",
    tags=["Provenance Audit"],
)

# Sub-Project C Day 27: factory-level provenance config admin (§6.4).
# Admin GET + PUT for diff_threshold / priority_overrides / industry_default_overrides.
from smartbi.api import factory_provenance_config  # noqa: E402
app.include_router(
    factory_provenance_config.router,
    prefix="/api/smartbi/factory-config",
    tags=["Factory Config"],
)

# Week 5 Agent layer — gated by env flag until verified on test.
# When flag is off, /api/smartbi/insights/custom does not exist.
if os.getenv("SMARTBI_AGENT_LAYER_ENABLED", "false").lower() == "true":
    from smartbi.agent.api import router as agent_router
    app.include_router(agent_router, prefix="/api/smartbi", tags=["Agent Insights"])
    logger.info("Week 5 Agent layer ENABLED — /api/smartbi/insights/custom registered")
else:
    logger.info("Week 5 Agent layer disabled (set SMARTBI_AGENT_LAYER_ENABLED=true to enable)")

# LLM usage admin (BUG-9 + 模型切换监控)
from smartbi.api import llm_usage_admin  # noqa: E402
app.include_router(llm_usage_admin.router, prefix="/api/smartbi/admin/llm-usage", tags=["LLM Usage Admin"])

# P0 数据主权: LLM 出境审计 admin (出境日志 + 脱敏统计 + CSV 导出, 给客户/合规演示)
from smartbi.api import llm_egress_audit_admin  # noqa: E402
app.include_router(llm_egress_audit_admin.router, prefix="/api/smartbi/admin/llm-egress", tags=["LLM Egress Audit"])

# Phase 1 (Apr 23 2026): LLM fallback query log admin + feedback write
from smartbi.api import llm_fallback_admin  # noqa: E402
app.include_router(
    llm_fallback_admin.router,
    prefix="/api/smartbi/admin/fallback-log",
    tags=["LLM Fallback Log"],
)

# J1 (Apr 24 2026): LLM router circuit breaker stats
# Optional: lives on a parallel branch (e2e/v1-framework). On branches where
# it's absent the rest of the service still starts; the admin route is just
# unavailable. Same pattern as the SmartBI compat block below.
try:
    from smartbi.api import llm_router_admin
    app.include_router(
        llm_router_admin.router,
        prefix="/api/smartbi/admin/llm-router",
        tags=["LLM Router Admin"],
    )
except ImportError as e:
    logger.warning(f"LLM Router admin routes not registered: {e}")

# Phase A A-1 Restaurant ETL admin (Apr 28 2026): trigger + status endpoints
from smartbi.api import restaurant_etl_admin  # noqa: E402
app.include_router(
    restaurant_etl_admin.router,
    prefix="/api/smartbi/restaurant/etl",
    tags=["Restaurant ETL Admin"],
)

# Restaurant POS dish-name resolution admin (#61 Phase 1, 2026-06-04)
from smartbi.api import restaurant_name_resolution_admin  # noqa: E402
app.include_router(
    restaurant_name_resolution_admin.router,
    prefix="/api/smartbi/restaurant/name-resolution",
    tags=["Restaurant POS Name Resolution"],
)

# Restaurant completeness API (餐饮 Phase A Task 2.1)
from smartbi.api import restaurant_completeness  # noqa: E402
app.include_router(
    restaurant_completeness.router,
    prefix="/api/smartbi/restaurant",
    tags=["Restaurant Completeness"],
)

# Sprint 11 MealClaw Response (2026-05-22) — composite LLM endpoint
# Strict whitelist response (summary / topItems / recommendations / evidence /
# dataAvailable) for AI chat layer. See restaurant_llm_composite.py docstring.
from smartbi.api import restaurant_llm_composite  # noqa: E402
app.include_router(
    restaurant_llm_composite.router,
    prefix="/api/smartbi",
    tags=["Restaurant LLM Composite"],
)

# Restaurant outliers API (餐饮 Phase B-1 Task 7 — outlier detection + dismissal)
from smartbi.api import restaurant_outliers  # noqa: E402
app.include_router(
    restaurant_outliers.router,
    prefix="/api/restaurant",
    tags=["RestaurantOutliers"],
)

# Phase A A-3 Data quality queue admin (餐饮 Phase A Task 3.2 — list endpoint)
# Tasks 3.3/3.4/3.6 will add resolve/reject/batch/history to the same module.
from smartbi.api import data_quality_queue_admin  # noqa: E402
app.include_router(
    data_quality_queue_admin.router,
    prefix="/api/smartbi/admin/data-quality-queue",
    tags=["Data Quality Queue Admin"],
)

# Memory diagnostic / manual trim (Apr 23 2026, investigating post-materialize RSS retention)
from smartbi.api import memory_admin  # noqa: E402
app.include_router(memory_admin.router, prefix="/api/smartbi/admin/memory", tags=["Memory Admin"])

# Optional: Data sync endpoint (auto-adaptation for system tables)
if hasattr(data_sync, 'router') and data_sync.router is not None:
    app.include_router(data_sync.router, prefix="/api/smartbi", tags=["Data Sync"])

# =====================================================
# AI Proxy Routes (called by Java backend)
# =====================================================
app.include_router(ai_proxy.router, prefix="/api/ai", tags=["AI Proxy"])

# Optional: classifier router (requires torch + transformers)
if classifier is not None:
    app.include_router(classifier.router, prefix="/api/classifier", tags=["Intent Classifier"])
else:
    logger.warning("Classifier router not registered (torch/transformers not installed)")

# Database analysis endpoints (PostgreSQL)
# Note: db_analysis.router already has /api/smartbi/analysis/db prefix
app.include_router(db_analysis.router, tags=["Database Analysis"])

# =====================================================
# Efficiency Recognition API Routes (optional)
# =====================================================
if _efficiency_available:
    app.include_router(efficiency_router, prefix="/api/efficiency", tags=["人效识别"])
    app.include_router(efficiency_stream_router, prefix="/api/efficiency/streams", tags=["多摄像头流管理"])
    app.include_router(efficiency_photo_router, prefix="/api/efficiency/photo", tags=["手动照片分析"])
    app.include_router(efficiency_tracking_router, prefix="/api/efficiency/tracking", tags=["跨摄像头追踪"])
    app.include_router(efficiency_cost_router, prefix="/api/efficiency/cost", tags=["成本监控"])
    app.include_router(efficiency_recording_router, prefix="/api/efficiency/recording", tags=["NVR录像分析"])
    app.include_router(efficiency_scene_router, prefix="/api/efficiency/scene", tags=["场景理解"])
    app.include_router(efficiency_whisper_router, prefix="/api/efficiency", tags=["Whisper ASR"])
else:
    logger.warning("Efficiency Recognition routes not registered")

# =====================================================
# Scene Intelligence API Routes (optional)
# =====================================================
if _scene_available:
    app.include_router(scene_intelligence_router, prefix="/api/scene", tags=["场景智能"])
else:
    logger.warning("Scene Intelligence routes not registered")

# =====================================================
# Food Knowledge Base API Routes (optional)
# =====================================================
if _food_kb_available:
    app.include_router(food_kb_api.router, prefix="/api/food-kb", tags=["Food Knowledge Base"])
    app.include_router(food_kb_industry_report_api.router, prefix="/api/food-kb/industry-report", tags=["Industry Report RAG"])  # noqa: E501
    app.include_router(food_kb_manual_chat_api.router, prefix="/api/food-kb", tags=["Manual Chat"])
else:
    logger.warning("Food Knowledge Base routes not registered")

if _food_kb_feedback_available:
    app.include_router(food_kb_feedback_router, prefix="/api/food-kb", tags=["FoodKB-Feedback"])
else:
    logger.warning("Food KB Feedback routes not registered")

# =====================================================
# Foreign Object Detection API Routes (optional)
# =====================================================
if _fod_available:
    app.include_router(fod_router, prefix="/api/fod", tags=["异物检测"])
else:
    logger.warning("Foreign Object Detection routes not registered")

# =====================================================
# Client Requirement API Routes
# =====================================================
app.include_router(
    client_requirement_routes.router,
    prefix="/api/client-requirement",
    tags=["Client Requirement"]
)
# Note: /api/client-requirement/ is in PUBLIC_PREFIXES (auth_middleware.py)
# Duplicate /api/public/ mount removed — was redundant

# =====================================================
# Data Completeness Calculator Routes (optional)
# =====================================================
if _completeness_available:
    app.include_router(
        completeness_routes.router,
        prefix="/api/client-requirement",
        tags=["Data Completeness"]
    )
else:
    logger.warning("Completeness Calculator routes not registered (asyncpg not available)")

# =====================================================
# Phase 2B-α AI Intent Routes (Apr 29 2026)
# =====================================================
# /api/ai/intent/match — Java AIIntentService stages 5-8 proxy.
# /api/ai/intent/cache/invalidate — admin reload trigger.
# Router carries its own prefix `/api/ai`; auth is X-Internal-Secret based,
# enforced by JWT middleware (PUBLIC_PREFIXES) + per-handler check.
if _ai_module_available:
    app.include_router(ai_router)
else:
    logger.warning("AI intent routes not registered (ai/ module import failed)")

# =====================================================
# LLM Router API Routes (multi-provider with fallback)
# =====================================================
if _llm_available:
    app.include_router(
        llm_api.router,
        prefix="/api/llm",
        tags=["LLM Router"]
    )
else:
    logger.error("LLM Router routes not registered")

# Phase 2A: SmartBI alias routes (web-admin + RN direct-to-Python)
try:
    from smartbi_compat.api import analysis as smartbi_compat_analysis
    from smartbi_compat.api import upload as smartbi_compat_upload
    from smartbi_compat.api import dashboard as smartbi_compat_dashboard
    from smartbi_compat.api import analysis_sales
    from smartbi_compat.api import analysis_finance
    from smartbi_compat.api import analysis_department
    from smartbi_compat.api import analysis_inventory
    from smartbi_compat.api import analysis_region
    from smartbi_compat.api import analysis_procurement
    from smartbi_compat.api import analysis_drilldown
    from smartbi_compat.api import analysis_production
    from smartbi_compat.api import analysis_quality
    from smartbi_compat.api import analysis_restaurant_ops
    from smartbi_compat.api import dashboard_composite
    app.include_router(smartbi_compat_analysis.router, tags=["SmartBI Compat: Analysis"])
    app.include_router(smartbi_compat_upload.router, tags=["SmartBI Compat: Upload"])
    app.include_router(smartbi_compat_dashboard.router, tags=["SmartBI Compat: Dashboard"])
    app.include_router(analysis_sales.router, tags=["smartbi-compat-sales"])
    app.include_router(analysis_finance.router, tags=["SmartBI Compat: Analysis Finance"])
    app.include_router(analysis_department.router, tags=["SmartBI Compat: Analysis Department"])
    app.include_router(analysis_inventory.router, tags=["SmartBI Compat: Analysis Inventory"])
    app.include_router(analysis_region.router, tags=["SmartBI Compat: Analysis Region"])
    app.include_router(analysis_procurement.router, tags=["SmartBI Compat: Analysis Procurement"])
    app.include_router(analysis_drilldown.router, tags=["SmartBI Compat: Analysis Drill-Down"])
    app.include_router(analysis_production.router, tags=["SmartBI Compat: Analysis Production"])
    app.include_router(analysis_quality.router, tags=["SmartBI Compat: Analysis Quality"])
    app.include_router(analysis_restaurant_ops.router, tags=["SmartBI Compat: Analysis Restaurant Ops (Phase IIb)"])
    app.include_router(dashboard_composite.router, tags=["SmartBI Compat: Dashboard Composite (Tier 2 Pilot)"])
    from smartbi_compat.api import datasource as smartbi_compat_datasource
    app.include_router(smartbi_compat_datasource.router, tags=["SmartBI Compat: Datasource"])
    from smartbi_compat.api import incentive_plan as smartbi_compat_incentive_plan
    app.include_router(smartbi_compat_incentive_plan.router, tags=["SmartBI Compat: Incentive Plan"])
    from smartbi_compat.api import query_templates_write as smartbi_compat_query_templates_write
    app.include_router(smartbi_compat_query_templates_write.router, tags=["SmartBI Compat: Query Templates Write"])
    from smartbi_compat.api import config_thresholds as smartbi_compat_config_thresholds
    app.include_router(smartbi_compat_config_thresholds.router, tags=["SmartBI Compat: Config Thresholds"])
    # PR #470 follow-up — controller-level RBAC for analysis_*. The dependency
    # raises RbacForbiddenException for non-analytics roles; handler renders
    # the 4-位一体 body Java's PermissionInterceptor emits.
    from smartbi_compat._rbac_role import (  # noqa: E402
        RbacForbiddenException,
        rbac_forbidden_handler,
    )
    app.add_exception_handler(RbacForbiddenException, rbac_forbidden_handler)
    _smartbi_compat_available = True
    logger.info("SmartBI compat routes registered (Phase 2A)")
except ImportError as e:
    _smartbi_compat_available = False
    logger.error(f"SmartBI compat routes NOT available: {e}")


@app.get("/health")
async def health_check():
    """Health check endpoint"""
    postgres_status = "disabled"

    if get_settings().postgres_enabled:
        try:
            from smartbi.database.connection import test_connection, is_postgres_enabled
            if is_postgres_enabled() and test_connection():
                postgres_status = "connected"
            else:
                postgres_status = "disconnected"
        except Exception:
            postgres_status = "error"

    return JSONResponse(
        content={
            "status": "healthy",
            "service": "python-services",
            "version": "2.0.0",
            "timestamp": datetime.now().isoformat(),
            "modules": [
                "smartbi",
                "client_requirement",
                *(["completeness_calculator"] if _completeness_available else []),
                *(["efficiency_recognition"] if _efficiency_available else []),
                *(["scene_intelligence"] if _scene_available else []),
                *(["food_knowledge_base"] if _food_kb_available else []),
                *(["food_kb_feedback"] if _food_kb_feedback_available else []),
                *(["foreign_object_detection"] if _fod_available else []),
                *(["ai_intent"] if _ai_module_available else []),
                *(["llm_router"] if _llm_available else []),
            ],
            "postgres": postgres_status
        }
    )


@app.get("/")
async def root():
    """Root endpoint with service information"""
    return {
        "service": "Python Services",
        "version": "2.0.0",
        "description": "Unified Python services for SmartBI analytics and client requirements",
        "modules": {
            "smartbi": {
                "excel": "/api/excel",
                "field": "/api/field",
                "metrics": "/api/metrics",
                "forecast": "/api/forecast",
                "insight": "/api/insight",
                "chart": "/api/chart",
                "analysis": "/api/analysis",
                "ml": "/api/ml",
                "linucb": "/api/linucb",
                "chat": "/api/chat",
                "classifier": "/api/classifier",
                "db_analysis": "/api/smartbi/analysis/db",
                "cross_sheet": "/api/smartbi/cross-sheet-analysis",
                "ai_proxy": "/api/ai",
                "benchmark": "/api/smartbi/benchmark"
            },
            "client_requirement": "/api/client-requirement",
            **({"efficiency_recognition": "/api/efficiency"} if _efficiency_available else {}),
            **({"scene_intelligence": "/api/scene"} if _scene_available else {}),
            **({"food_knowledge_base": "/api/food-kb"} if _food_kb_available else {}),
            **({"food_kb_feedback": "/api/food-kb/feedback"} if _food_kb_feedback_available else {}),
            **({"foreign_object_detection": "/api/fod"} if _fod_available else {}),
            **({"ai_intent": "/api/ai"} if _ai_module_available else {}),
            **({"llm_router": "/api/llm"} if _llm_available else {}),
        },
        "endpoints": {
            "health": "/health",
            "docs": "/docs"
        },
        "postgres_enabled": get_settings().postgres_enabled
    }


@app.exception_handler(ApiException)
async def api_exception_handler(request, exc: ApiException):
    """Handle ApiException with unified {success, data, message, code} format."""
    return JSONResponse(
        status_code=exc.status_code,
        content=error_response(exc.message, exc.code),
    )


# Bug #35 fix (Apr 18 2026): FastAPI 默认 HTTPException 响应是 {detail:"..."},
# 和项目契约 {success:false, message, code} 不一致 → 前端 interceptor 拿不到
# .message → 用户看 axios 通用 "Request failed with status code 404".
# 统一转成项目格式.
from fastapi import HTTPException as FastAPIHTTPException  # noqa: E402
from starlette.exceptions import HTTPException as StarletteHTTPException  # noqa: E402


@app.exception_handler(FastAPIHTTPException)
@app.exception_handler(StarletteHTTPException)
async def http_exception_handler(request, exc):
    """Convert FastAPI/Starlette HTTPException.detail → unified project format."""
    code_map = {
        400: ErrorCode.VALIDATION_ERROR,
        401: ErrorCode.AUTH_ERROR,
        403: ErrorCode.AUTH_ERROR,
        404: ErrorCode.NOT_FOUND,
        422: ErrorCode.VALIDATION_ERROR,
    }
    code = code_map.get(exc.status_code, ErrorCode.INTERNAL_ERROR)
    message = exc.detail if isinstance(exc.detail, str) else str(exc.detail)
    return JSONResponse(
        status_code=exc.status_code,
        content=error_response(message, code),
    )


@app.exception_handler(Exception)
async def global_exception_handler(request, exc):
    """Global exception handler — unified format for unhandled errors."""
    logger.error(f"Unhandled exception: {exc}", exc_info=True)
    return JSONResponse(
        status_code=500,
        content=error_response(
            str(exc) if settings.debug else "Internal server error",
            ErrorCode.INTERNAL_ERROR,
        ),
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=settings.host,
        port=settings.port,
        reload=settings.debug
    )
