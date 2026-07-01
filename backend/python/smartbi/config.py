from __future__ import annotations
"""
SmartBI Service Configuration
"""
from functools import lru_cache
from typing import List, Optional
from urllib.parse import quote

try:
    from pydantic_settings import BaseSettings
except ImportError:
    from pydantic import BaseSettings


class Settings(BaseSettings):
    """Application settings loaded from environment variables"""

    # LLM Configuration - Text Models (all using free-quota models during testing)
    llm_api_key: str = ""
    llm_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    llm_model: str = "qwen3.7-max-2026-06-08"  # free (qwen3-max-2026-01-23 was PAID — caused 2026-06-11 bill)

    # LLM Configuration - Vision Model (for structure detection)
    llm_vl_model: str = "qwen3-vl-plus-2025-12-19"  # VL model — free quota (was: qwen-vl-max)
    llm_vl_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"

    # LLM Configuration - Fast Model (field detection, scenario detection)
    llm_fast_model: str = "qwen3.5-flash"  # Fast, lightweight for simple classification

    # LLM Configuration - Reasoning Model (for complex decisions)
    llm_reasoning_model: str = "qwen3.5-397b-a17b"  # Heaviest MoE for Layer 4 deep reasoning

    # LLM Configuration - Insight Model (insight generation, heaviest single consumer)
    llm_insight_model: str = "qwen3.5-flash"  # Base model supports explicit context caching

    # LLM Configuration - Chart Model (chart recommendation)
    llm_chart_model: str = "qwen3.5-27b"  # Medium model for chart type recommendation

    # LLM Configuration - Mapper Model (field mapping, data cleaning, structure analysis)
    # free quota ⚠️ (qwen3.5-122b-a10b is PAID — billing incident 2026-06-11)
    llm_mapper_model: str = "qwen3.7-max-2026-06-08"

    # KB Chat Configuration (G4 — DeepSeek for cost optimization, audit round 5)
    # KB chat (manual_chat) uses DeepSeek by default for 30-60x cost saving:
    # - Input cache miss: ¥1/1M (vs qwen ¥40/1M) — 40x
    # - Input cache hit: ¥0.02/1M — 1/50 (system prompt + chunks 重复场景受益)
    # - Output: ¥2/1M (vs qwen ¥120/1M) — 60x
    # OpenAI-compatible API. Falls back to llm_* if kb_chat_api_key empty.
    kb_chat_provider: str = "deepseek"  # "deepseek" | "qwen" | "" (use default llm_*)
    kb_chat_base_url: str = "https://api.deepseek.com"  # OpenAI-compatible, no /v1 prefix
    kb_chat_api_key: str = ""  # Set via LLM_DEEPSEEK_API_KEY env var
    kb_chat_model: str = "deepseek-chat"  # = deepseek-v4-flash alias, supports thinking + non-thinking

    # Zero-Code Configuration
    structure_detection_confidence_threshold: float = 0.7  # Minimum confidence for structure detection
    semantic_mapping_confidence_threshold: float = 0.8  # Minimum confidence for field mapping
    enable_multi_model_enhancement: bool = True  # Enable Layer 4 multi-model voting (structure_detector)
    # J2 (Apr 24 2026): Layer 4 multi-model voting now CONDITIONAL on prior layer confidence.
    # Only escalate to 3-model voting if best prior layer confidence < this threshold.
    # Avoids ~30-60% redundant LLM calls when single-model already nailed structure.
    multi_model_voting_confidence_threshold: float = 0.85
    enable_mapper_multi_model: bool = False  # D2: semantic_mapper Layer 3 (2x LLM voting) — off by default
    max_self_correction_rounds: int = 3  # Max rounds for self-correction loop
    use_llm_first: bool = True  # Use LLM as default detection method instead of rules

    # JWT Authentication (must match Java backend's cretas.jwt.secret)
    # MUST be set via JWT_SECRET env var in production
    jwt_secret: str = ""
    jwt_auth_enabled: bool = True

    # Service Configuration
    debug: bool = False
    port: int = 8083
    host: str = "0.0.0.0"

    # CORS Configuration
    cors_origins: List[str] = [
        "http://139.196.165.140:8086",
        "http://47.100.235.168:10010",
        "http://localhost:5173",
        "http://localhost:3000",
    ]

    # File Upload Configuration
    max_file_size_mb: int = 500
    allowed_extensions: List[str] = [".xlsx", ".xls", ".csv"]

    # Cache Configuration
    cache_ttl_seconds: int = 3600
    schema_cache_enabled: bool = True  # Enable schema caching for repeated files

    # ==========================================
    # PostgreSQL SmartBI Database Configuration
    # ==========================================
    # Enables Python service to directly query dynamic data stored in PostgreSQL
    postgres_enabled: bool = False
    postgres_host: str = "localhost"
    postgres_port: int = 5432
    postgres_db: str = "smartbi_db"
    postgres_user: str = "smartbi_user"
    postgres_password: str = ""
    # Multi-worker safe asyncpg pool sizing per audit 2026-05-07.
    # See docs/qa-audits/2026-05-07-uvicorn-workers-spike-rerun.md (PR #105)
    # and the follow-up audit on full PG-budget accounting (food_kb pools +
    # SQLAlchemy + JDBC). N=2 budget: 2 workers × 15 asyncpg smartbi +
    # 2 × 6 asyncpg cretas + 2 × 5 SQLAlchemy×2 engines + JDBC ~30 + food_kb
    # ~5 + transient ~5 ≈ 97 — fits 100 max_connections cap.
    # Used as `max_size` in `get_pg_pool()` below.
    postgres_pool_size: int = 15
    postgres_max_overflow: int = 10

    # ==========================================
    # Food Knowledge Base Database Configuration
    # ==========================================
    # Uses cretas_db (main DB) with pgvector for RAG
    food_kb_postgres_host: str = "localhost"
    food_kb_postgres_port: int = 5432
    food_kb_postgres_db: str = "cretas_db"
    food_kb_postgres_user: str = "cretas_user"
    food_kb_postgres_password: str = ""

    # Embedding model configuration (local Java embedding-service over gRPC)
    food_kb_embedding_model: str = "gte-base-zh"
    food_kb_embedding_dims: int = 768

    # Restaurant external signal providers. Secrets stay in env/.env only.
    qweather_api_key: str = ""
    qweather_api_host: str = ""
    damai_app_key: str = ""
    damai_app_secret: str = ""
    mall_activity_feed_urls: str = ""

    @property
    def food_kb_db_url(self) -> str:
        """Get Food KB PostgreSQL connection URL (for asyncpg)"""
        user = quote(self.food_kb_postgres_user, safe='')
        passwd = quote(self.food_kb_postgres_password, safe='')
        return f"postgresql://{user}:{passwd}@{self.food_kb_postgres_host}:{self.food_kb_postgres_port}/{self.food_kb_postgres_db}"  # noqa: E501

    @property
    def postgres_url(self) -> str:
        """Get PostgreSQL connection URL"""
        user = quote(self.postgres_user, safe='')
        passwd = quote(self.postgres_password, safe='')
        return f"postgresql://{user}:{passwd}@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"

    @property
    def postgres_async_url(self) -> str:
        """Get PostgreSQL async connection URL (for asyncpg)"""
        user = quote(self.postgres_user, safe='')
        passwd = quote(self.postgres_password, safe='')
        return f"postgresql+asyncpg://{user}:{passwd}@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"

    class Config:
        env_file = (".env", "smartbi/.env")
        env_file_encoding = "utf-8"
        extra = "ignore"


@lru_cache()
def get_settings() -> Settings:
    """Get cached settings instance"""
    return Settings()


# ==========================================
# DataFrame numeric coercion utility
# ==========================================

def coerce_numeric_columns(df):
    """Coerce string columns that contain numeric values to numeric dtype.

    JSON data from PostgreSQL comes as strings. pd.select_dtypes(include=['number'])
    returns empty unless we explicitly convert. This fixes "No numeric measures detected".
    """
    import pandas as pd
    for col in df.columns:
        if df[col].dtype == object:
            converted = pd.to_numeric(df[col], errors='coerce')
            # Only convert if >50% of non-null values are numeric
            non_null = df[col].dropna()
            if len(non_null) > 0:
                numeric_count = converted.notna().sum()
                if numeric_count / max(len(non_null), 1) > 0.5:
                    df[col] = converted
    return df


# ==========================================
# Shared asyncpg connection pool
# ==========================================
_pg_pool = None


async def get_pg_pool():
    """Get or create shared asyncpg connection pool for SmartBI database.

    Apr 22 2026 (Week 1 Day 1 of Unified Data Layer v1 spec): every borrowed
    connection gets `app.factory_id` set from smartbi.tenant_ctx contextvar.
    Downstream RLS policies on fact/dim tables use
      USING (factory_id = current_setting('app.factory_id'))
    so queries without tenant scope return zero rows by default.
    """
    global _pg_pool
    if _pg_pool is None or _pg_pool._closed:
        import asyncpg
        from smartbi.tenant_ctx import set_pg_connection_tenant
        settings = get_settings()
        pg_url = settings.postgres_url
        if not pg_url:
            return None
        _pg_pool = await asyncpg.create_pool(
            pg_url,
            min_size=2,
            max_size=settings.postgres_pool_size or 5,
            setup=set_pg_connection_tenant,
        )
    return _pg_pool


# ==========================================
# Shared asyncpg pool for Cretas app DB (cretas_db)
# ==========================================
_cretas_pool: Optional[asyncpg.Pool] = None  # type: ignore[name-defined]  # noqa: F821
_cretas_pool_lock = None  # initialised lazily (asyncio.Lock must be created inside event loop)


async def get_cretas_pool():
    """Get cached asyncpg connection pool for the Cretas app database (cretas_db).

    Singleton — lives for the full application lifetime.  Never close the
    returned pool (it is shared across all callers).

    Uses the same food_kb_db_url setting as the per-request pools that
    restaurant_completeness.py and restaurant_etl_admin.py previously created
    (legacy name; the URL points to cretas_db, not a separate food-kb DB).

    Pool size 2-8: larger than the old per-request 1-3 because this pool is
    shared and amortises the connection overhead across all concurrent requests.
    """
    import asyncio
    import asyncpg as _asyncpg

    global _cretas_pool, _cretas_pool_lock

    # Fast path — pool already initialised
    if _cretas_pool is not None and not _cretas_pool._closed:
        return _cretas_pool

    # Lazily create the lock inside the running event loop
    if _cretas_pool_lock is None:
        _cretas_pool_lock = asyncio.Lock()

    async with _cretas_pool_lock:
        # Double-check after acquiring lock
        if _cretas_pool is not None and not _cretas_pool._closed:
            return _cretas_pool

        settings = get_settings()
        url = settings.food_kb_db_url
        if not url:
            return None

        _cretas_pool = await _asyncpg.create_pool(
            url,
            min_size=2,
            # Multi-worker safe per audit 2026-05-07; see
            # docs/qa-audits/2026-05-07-uvicorn-workers-spike-rerun.md (PR #105)
            # and follow-up audit. Mirrors smartbi pool tuning above.
            max_size=6,
            command_timeout=30,
        )

    return _cretas_pool
