"""
Food Knowledge Base — Standalone Python Service

Lightweight entry point for the food_kb module only.
Designed for servers that don't have the full SmartBI stack.

Port: 8083
"""
import os
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)


# Configuration from environment
def _env(key, default=""):
    return os.environ.get(key, default)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan handler"""
    logger.info("Food KB Service starting up...")

    try:

        # Build DB URL
        db_host = _env("FOOD_KB_POSTGRES_HOST", "localhost")
        db_port = _env("FOOD_KB_POSTGRES_PORT", "5432")
        db_name = _env("FOOD_KB_POSTGRES_DB", "cretas_db")
        db_user = _env("FOOD_KB_POSTGRES_USER", "cretas_user")
        db_pass = _env("FOOD_KB_POSTGRES_PASSWORD", "")

        import urllib.parse
        encoded_pass = urllib.parse.quote(db_pass, safe="")
        db_url = f"postgresql://{db_user}:{encoded_pass}@{db_host}:{db_port}/{db_name}"

        # Embedding config: local Java embedding-service over gRPC.
        # api_key/base_url are kept for backward-compatible configure() args.
        api_key = _env("LLM_API_KEY", _env("DASHSCOPE_API_KEY", ""))
        base_url = _env("LLM_BASE_URL", "")
        embedding_model = _env("FOOD_KB_EMBEDDING_MODEL", "gte-base-zh")
        embedding_dims = int(_env("FOOD_KB_EMBEDDING_DIMS", "768"))

        # Configure embedding service
        from food_kb.services.embedding import configure as configure_embedding, get_embedding
        configure_embedding(
            api_key=api_key,
            base_url=base_url,
            model=embedding_model,
            dims=embedding_dims,
        )

        # Initialize knowledge retriever
        from food_kb.services.knowledge_retriever import get_knowledge_retriever
        retriever = get_knowledge_retriever()
        await retriever.initialize(db_url, embedding_fn=get_embedding)

        # Initialize document ingester
        from food_kb.services.document_ingester import get_document_ingester
        ingester = get_document_ingester()
        await ingester.initialize(db_url, embedding_fn=get_embedding)

        # Load NER entity dictionary
        from food_kb.services.food_ner_service import get_food_ner_service
        ner_service = get_food_ner_service()
        if retriever.is_ready() and retriever._pool:
            await ner_service.load_dictionary(retriever._pool)

        # Load NER ONNX model (if available)
        ner_model_dir = _env(
            "FOOD_NER_MODEL_DIR",
            os.path.join(os.path.dirname(__file__), "models", "food-ner-onnx")
        )
        if os.path.isdir(ner_model_dir):
            ner_service.load_model(ner_model_dir)

        logger.info("Food Knowledge Base initialized successfully")
    except Exception as e:
        logger.error(f"Food Knowledge Base initialization failed: {e}", exc_info=True)

    yield

    # Shutdown
    try:
        from food_kb.services.knowledge_retriever import get_knowledge_retriever
        from food_kb.services.document_ingester import get_document_ingester
        from food_kb.services.embedding import close as close_embedding
        await get_knowledge_retriever().close()
        await get_document_ingester().close()
        await close_embedding()
        logger.info("Food KB connections closed")
    except Exception as e:
        logger.warning(f"Food KB shutdown error: {e}")


# Create FastAPI application
app = FastAPI(
    title="Food Knowledge Base Service",
    version="1.0.0",
    lifespan=lifespan,
    docs_url="/docs",
    redoc_url="/redoc"
)

# CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Register food_kb router
try:
    from food_kb.api import knowledge as food_kb_api
    app.include_router(food_kb_api.router, prefix="/api/food-kb", tags=["Food Knowledge Base"])
    logger.info("Food KB API router registered at /api/food-kb")
except ImportError as e:
    logger.error(f"Failed to import food_kb module: {e}")


@app.get("/")
async def root():
    return {"service": "Food Knowledge Base", "version": "1.0.0"}


@app.get("/health")
async def health():
    return {"status": "ok"}
