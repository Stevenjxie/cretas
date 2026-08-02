from __future__ import annotations
"""
SmartBI API Module

This module contains all API route handlers for the SmartBI service.
"""
import logging

logger = logging.getLogger(__name__)

# Core modules (always available)
from . import excel, field, metrics, forecast, insight, chart, analysis, ml, linucb, chat, db_analysis, cross_sheet, yoy, statistical, analysis_cache, ai_proxy, benchmark, finance_extract, restaurant_analytics, restaurant_staffing, production_ai, whatif  # noqa: E402,E501

__all__ = [
    "excel", "field", "metrics", "forecast", "insight", "chart",
    "analysis", "ml", "linucb", "chat", "db_analysis", "cross_sheet", "yoy", "statistical",
    "analysis_cache", "ai_proxy", "benchmark", "finance_extract", "restaurant_analytics", "restaurant_staffing",
    "production_ai", "whatif"
]

# Optional: data_sync (requires openpyxl + sqlalchemy for cretas_db)
try:
    from . import data_sync
    __all__.append("data_sync")
except ImportError as e:
    logger.warning(f"Data sync module not available: {e}")
    data_sync = None

# Optional: classifier module (requires torch + transformers)
try:
    from . import classifier
    __all__.append("classifier")
except (ImportError, TypeError) as e:
    logger.warning(f"Classifier module not available: {e}")
    classifier = None
