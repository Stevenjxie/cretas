"""Plan C P0 — Batch recipe import + dish alias management.

P0-1: POST /api/smartbi/restaurant-ops/recipes/batch-import — upload xlsx to seed
      product_types + raw_material_types + recipes in one shot.
      Expected columns: dish_name, ingredient_name, quantity, unit, ingredient_price, is_main

P0-2: CRUD for dim_product_alias (POS xlsx dish_name → cretas product_type_id mapping).
      GET  /api/smartbi/restaurant-ops/unmatched-dishes — list POS dishes without a linked product_type
      POST /api/smartbi/restaurant-ops/aliases — bind a POS name to an existing product_type
"""
from __future__ import annotations

import io
import logging
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, File, Request, UploadFile
from pydantic import BaseModel

logger = logging.getLogger(__name__)
router = APIRouter(tags=["RestaurantOpsRecipes"])


def _get_factory_id(request: Request) -> Optional[str]:
    return getattr(request.state, "factory_id", None)


# ==========================================================================
# P0-1: Batch recipe import via Excel/CSV
# ==========================================================================

EXPECTED_COLS = ["菜品名称", "食材名称", "用量", "单位", "食材单价", "是否主料"]
ENGLISH_COLS = ["dish_name", "ingredient_name", "quantity", "unit", "ingredient_price", "is_main"]


@router.post("/restaurant-ops/recipes/batch-import")
async def batch_import_recipes(
    request: Request,
    file: UploadFile = File(...),
) -> Dict[str, Any]:
    """Parse uploaded xlsx/csv and insert product_types + raw_material_types + recipes.

    Returns counts per entity type + any row-level validation errors.
    Idempotent: ON CONFLICT DO NOTHING on natural keys (factory_id+code / factory_id+name).
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    try:
        import pandas as pd
    except ImportError:
        return {"success": False, "message": "pandas not installed on server"}

    content = await file.read()
    try:
        if file.filename and file.filename.lower().endswith(".csv"):
            df = pd.read_csv(io.BytesIO(content))
        else:
            df = pd.read_excel(io.BytesIO(content))
    except Exception as e:
        return {"success": False, "message": f"无法解析文件: {e}"}

    # Column normalization — support both Chinese and English headers
    col_map = {}
    for cn, en in zip(EXPECTED_COLS, ENGLISH_COLS):
        if cn in df.columns:
            col_map[cn] = en
        elif en in df.columns:
            col_map[en] = en
    df = df.rename(columns=col_map)
    missing = [en for en in ["dish_name", "ingredient_name", "quantity"] if en not in df.columns]
    if missing:
        return {"success": False, "message": f"缺少必需列: {', '.join(missing)}. 请用模板下载的格式"}

    # Connect to cretas_prod_db (where product_types/raw_material_types/recipes live)
    try:
        import asyncpg
        from config import get_settings
    except Exception as e:
        return {"success": False, "message": f"config/asyncpg error: {e}"}

    settings = get_settings()
    conn = await asyncpg.connect(settings.food_kb_db_url)
    try:
        user_id = 1550  # default factory_super_admin, TODO: extract from JWT
        dish_created = 0
        ingredient_created = 0
        recipe_created = 0
        errors: List[str] = []

        # Pass 1: upsert raw_material_types (dedupe by name)
        ingredient_price_map: Dict[str, float] = {}
        ingredient_id_map: Dict[str, str] = {}
        for _, row in df.iterrows():
            iname = str(row.get("ingredient_name", "")).strip()
            if not iname or iname == "nan":
                continue
            unit = str(row.get("unit", "kg")).strip() or "kg"
            price = row.get("ingredient_price")
            try:
                price = float(price) if price is not None and str(price) != "nan" else None
            except Exception:
                price = None
            if price is not None:
                ingredient_price_map[iname] = price

            # Look up existing or create
            existing = await conn.fetchrow(
                "SELECT id, unit_price FROM raw_material_types WHERE factory_id = $1 AND name = $2 AND deleted_at IS NULL",  # noqa: E501
                factory_id, iname,
            )
            if existing:
                ingredient_id_map[iname] = existing["id"]
                if price is not None and (existing["unit_price"] is None or float(existing["unit_price"]) != price):
                    await conn.execute(
                        "UPDATE raw_material_types SET unit_price = $1, updated_at = NOW() WHERE id = $2",
                        price, existing["id"],
                    )
            else:
                # Generate id + code
                import hashlib
                code = f"IMP_{hashlib.md5((factory_id + iname).encode()).hexdigest()[:10].upper()}"
                new_id = f"rm_imp_{hashlib.md5((factory_id + iname).encode()).hexdigest()[:16]}"
                try:
                    await conn.execute(
                        """INSERT INTO raw_material_types (id, factory_id, code, name, unit, unit_price,
                             is_active, created_by, created_at, updated_at, notes)
                           VALUES ($1, $2, $3, $4, $5, $6, true, $7, NOW(), NOW(), 'BATCH_IMPORT')""",
                        new_id, factory_id, code, iname, unit, price, user_id,
                    )
                    ingredient_id_map[iname] = new_id
                    ingredient_created += 1
                except Exception as e:
                    errors.append(f"食材 '{iname}' 插入失败: {e}")

        # Pass 2: upsert product_types (dedupe by name)
        dish_id_map: Dict[str, str] = {}
        for _, row in df.iterrows():
            dname = str(row.get("dish_name", "")).strip()
            if not dname or dname == "nan":
                continue
            if dname in dish_id_map:
                continue
            existing = await conn.fetchrow(
                "SELECT id FROM product_types WHERE factory_id = $1 AND name = $2 AND deleted_at IS NULL",
                factory_id, dname,
            )
            if existing:
                dish_id_map[dname] = existing["id"]
            else:
                import hashlib
                code = f"IMP_{hashlib.md5((factory_id + dname).encode()).hexdigest()[:10].upper()}"
                new_id = f"pt_imp_{hashlib.md5((factory_id + dname).encode()).hexdigest()[:16]}"
                try:
                    await conn.execute(
                        """INSERT INTO product_types (id, factory_id, code, name, unit, category,
                             is_active, created_by, created_at, updated_at, notes)
                           VALUES ($1, $2, $3, $4, '份', '主菜', true, $5, NOW(), NOW(), 'BATCH_IMPORT')""",
                        new_id, factory_id, code, dname, user_id,
                    )
                    dish_id_map[dname] = new_id
                    dish_created += 1
                except Exception as e:
                    errors.append(f"菜品 '{dname}' 插入失败: {e}")

        # Pass 3: insert recipes (each row = one recipe line)
        for idx, row in df.iterrows():
            dname = str(row.get("dish_name", "")).strip()
            iname = str(row.get("ingredient_name", "")).strip()
            qty_val = row.get("quantity")
            if not dname or not iname or dname == "nan" or iname == "nan":
                continue
            try:
                qty = float(qty_val)
                if qty <= 0:
                    errors.append(f"第 {idx+2} 行: 用量必须 > 0")
                    continue
            except Exception:
                errors.append(f"第 {idx+2} 行: 用量 '{qty_val}' 无法转为数字")
                continue
            pid = dish_id_map.get(dname)
            rid = ingredient_id_map.get(iname)
            if not pid or not rid:
                continue
            unit = str(row.get("unit", "kg")).strip() or "kg"
            is_main_raw = str(row.get("is_main", "否")).strip()
            is_main = is_main_raw in ("是", "true", "True", "1", "yes", "Y")

            # Check if this (product_type, raw_material) pair exists
            existing = await conn.fetchrow(
                """SELECT id FROM recipes
                    WHERE factory_id = $1 AND product_type_id = $2 AND raw_material_type_id = $3
                    AND deleted_at IS NULL""",
                factory_id, pid, rid,
            )
            if existing:
                # Update quantity
                await conn.execute(
                    "UPDATE recipes SET standard_quantity = $1, unit = $2, is_main_ingredient = $3, updated_at = NOW() WHERE id = $4",  # noqa: E501
                    qty, unit, is_main, existing["id"],
                )
            else:
                import hashlib
                new_id = f"rec_imp_{hashlib.md5((factory_id + pid + rid).encode()).hexdigest()[:16]}"
                try:
                    await conn.execute(
                        """INSERT INTO recipes (id, factory_id, product_type_id, raw_material_type_id,
                             standard_quantity, unit, is_main_ingredient, is_active, created_by,
                             created_at, updated_at, notes)
                           VALUES ($1, $2, $3, $4, $5, $6, $7, true, $8, NOW(), NOW(), 'BATCH_IMPORT')""",
                        new_id, factory_id, pid, rid, qty, unit, is_main, user_id,
                    )
                    recipe_created += 1
                except Exception as e:
                    errors.append(f"第 {idx+2} 行配方插入失败: {e}")

        return {
            "success": True,
            "data": {
                "dishesCreated": dish_created,
                "ingredientsCreated": ingredient_created,
                "recipesCreated": recipe_created,
                "errors": errors[:20],  # cap error list
                "errorCount": len(errors),
            },
        }
    finally:
        await conn.close()


@router.get("/restaurant-ops/recipes/import-template")
async def download_import_template() -> Dict[str, Any]:
    """Return a CSV template + instructions. Client downloads this for reference."""
    sample = [
        {
            "菜品名称": "招牌青花椒味(单人份)",
            "食材名称": "鲈鱼",
            "用量": 0.25,
            "单位": "kg",
            "食材单价": 45.00,
            "是否主料": "是",
        },
        {
            "菜品名称": "招牌青花椒味(单人份)",
            "食材名称": "青花椒",
            "用量": 0.03,
            "单位": "kg",
            "食材单价": 80.00,
            "是否主料": "否",
        },
        {
            "菜品名称": "米饭",
            "食材名称": "大米",
            "用量": 0.2,
            "单位": "kg",
            "食材单价": 6.00,
            "是否主料": "是",
        },
    ]
    return {
        "success": True,
        "data": {
            "columns": EXPECTED_COLS,
            "sample": sample,
            "instructions": [
                "每行 = 一个菜品的一种食材. 同一菜品多种食材请填多行.",
                "单位支持: kg / g / L / mL / 个 / 份",
                "食材单价 = 采购价 (元/单位), 同一食材多次出现以最后一行为准",
                "是否主料填 '是' 或 '否' (一菜品多主料也允许)",
                "新菜品/新食材自动创建, 已存在的按菜名/食材名匹配 (需字节完全一致)",
            ],
        },
    }


# ==========================================================================
# P0-2: Dish name alias + unmatched dishes panel
# ==========================================================================

class AliasBindRequest(BaseModel):
    pos_name: str       # POS xlsx 里的菜名 (dim_product.name)
    product_type_id: str  # cretas.product_types.id 要绑定的菜品


@router.get("/restaurant-ops/unmatched-dishes")
async def list_unmatched_dishes(
    request: Request,
) -> Dict[str, Any]:
    """List POS dishes that don't have a matched product_type (by name or alias).

    These are the dishes that show 'hasCost=false' in gross-margin because recipe lookup fails.
    Returns: [{name, revenue, qty, bills}, ...] sorted by revenue DESC.
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        return {"success": False, "message": "smartbi_db pool unavailable"}

    async with pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        pos_rows = await conn.fetch(
            """
            SELECT p.name AS dish_name, p.normalized_name,
                   SUM(i.qty)::float AS qty,
                   SUM(i.amount)::float AS revenue,
                   COUNT(DISTINCT i.transaction_id)::int AS bills
              FROM fact_pos_item i
              JOIN dim_product p ON p.product_id = i.product_id
             WHERE i.factory_id = $1
             GROUP BY p.name, p.normalized_name
             ORDER BY revenue DESC
            """,
            factory_id,
        )

    # Fetch existing product_types + aliases from cretas_db
    try:
        import asyncpg
        from config import get_settings
    except Exception as e:
        return {"success": False, "message": f"config error: {e}"}

    settings = get_settings()
    cretas = await asyncpg.connect(settings.food_kb_db_url)
    try:
        # Existing product_type names (primary match)
        rows = await cretas.fetch(
            "SELECT name FROM product_types WHERE factory_id = $1 AND deleted_at IS NULL",
            factory_id,
        )
        known_names = {r["name"] for r in rows}

        # Aliases (fuzzy match — pos_name → product_type_id)
        # Create table if not exists
        await cretas.execute("""
            CREATE TABLE IF NOT EXISTS dim_product_alias (
                id BIGSERIAL PRIMARY KEY,
                factory_id VARCHAR(100) NOT NULL,
                pos_name VARCHAR(500) NOT NULL,
                product_type_id VARCHAR(100) NOT NULL,
                created_at TIMESTAMP DEFAULT NOW(),
                UNIQUE(factory_id, pos_name)
            )
        """)
        alias_rows = await cretas.fetch(
            "SELECT pos_name FROM dim_product_alias WHERE factory_id = $1",
            factory_id,
        )
        aliased_names = {r["pos_name"] for r in alias_rows}
    finally:
        await cretas.close()

    unmatched = []
    for r in pos_rows:
        if r["dish_name"] in known_names:
            continue
        if r["dish_name"] in aliased_names:
            continue
        unmatched.append({
            "name": r["dish_name"],
            "qty": r["qty"],
            "revenue": r["revenue"],
            "bills": r["bills"],
        })

    total_rev = sum(r["revenue"] for r in pos_rows)
    unmatched_rev = sum(u["revenue"] for u in unmatched)

    return {
        "success": True,
        "data": {
            "unmatchedCount": len(unmatched),
            "totalPosDishes": len(pos_rows),
            "unmatchedRevenue": unmatched_rev,
            "totalRevenue": total_rev,
            "unmatchedRevenueRatio": unmatched_rev / total_rev if total_rev > 0 else 0,
            "dishes": unmatched[:100],  # cap to first 100
        },
    }


@router.get("/restaurant-ops/product-types")
async def list_product_types(request: Request) -> Dict[str, Any]:
    """List all product_types for the current factory (for alias binding dropdown)."""
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}
    try:
        import asyncpg
        from config import get_settings
    except Exception as e:
        return {"success": False, "message": f"config error: {e}"}
    settings = get_settings()
    conn = await asyncpg.connect(settings.food_kb_db_url)
    try:
        rows = await conn.fetch(
            """SELECT id, name FROM product_types
                WHERE factory_id = $1 AND is_active = true AND deleted_at IS NULL
                ORDER BY name""",
            factory_id,
        )
        return {"success": True, "data": {"products": [{"id": r["id"], "name": r["name"]} for r in rows]}}
    finally:
        await conn.close()


@router.post("/restaurant-ops/aliases")
async def bind_alias(request: Request, body: AliasBindRequest) -> Dict[str, Any]:
    """Bind a POS dish name to an existing product_type.

    After binding, ETL will pick up the POS dish's recipe via alias → product_type.
    User must then call ⚡立即同步 to see the effect on margin analysis.
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    try:
        import asyncpg
        from config import get_settings
    except Exception as e:
        return {"success": False, "message": f"config error: {e}"}

    settings = get_settings()
    conn = await asyncpg.connect(settings.food_kb_db_url)
    try:
        # Ensure product_type exists in this factory
        exists = await conn.fetchval(
            "SELECT 1 FROM product_types WHERE id = $1 AND factory_id = $2 AND deleted_at IS NULL",
            body.product_type_id, factory_id,
        )
        if not exists:
            return {"success": False, "message": "product_type_id 不存在或不属于当前租户"}

        await conn.execute("""
            INSERT INTO dim_product_alias (factory_id, pos_name, product_type_id)
            VALUES ($1, $2, $3)
            ON CONFLICT (factory_id, pos_name) DO UPDATE SET product_type_id = $3
        """, factory_id, body.pos_name, body.product_type_id)

        return {"success": True, "data": {"posName": body.pos_name, "productTypeId": body.product_type_id}}
    finally:
        await conn.close()


@router.get("/restaurant-ops/aliases")
async def list_aliases(request: Request) -> Dict[str, Any]:
    """List all POS→product_type aliases for current factory."""
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    try:
        import asyncpg
        from config import get_settings
    except Exception as e:
        return {"success": False, "message": f"config error: {e}"}

    settings = get_settings()
    conn = await asyncpg.connect(settings.food_kb_db_url)
    try:
        rows = await conn.fetch("""
            SELECT a.pos_name, a.product_type_id, p.name AS product_name, a.created_at
              FROM dim_product_alias a
              LEFT JOIN product_types p ON p.id = a.product_type_id
             WHERE a.factory_id = $1
             ORDER BY a.created_at DESC
        """, factory_id)
        return {
            "success": True,
            "data": {
                "aliases": [
                    {
                        "posName": r["pos_name"],
                        "productTypeId": r["product_type_id"],
                        "productName": r["product_name"],
                        "createdAt": r["created_at"].isoformat() if r["created_at"] else None,
                    }
                    for r in rows
                ],
            },
        }
    finally:
        await conn.close()


# ==========================================================================
# P1-5: Noise dish exclusion
# ==========================================================================
class ExcludeBody(BaseModel):
    pos_names: List[str]


async def _ensure_excluded_dishes_table(conn) -> None:
    await conn.execute("""
        CREATE TABLE IF NOT EXISTS dim_product_excluded (
            id BIGSERIAL PRIMARY KEY,
            factory_id VARCHAR(100) NOT NULL,
            pos_name VARCHAR(500) NOT NULL,
            reason VARCHAR(200),
            created_at TIMESTAMP DEFAULT NOW(),
            UNIQUE(factory_id, pos_name)
        )
    """)


@router.post("/restaurant-ops/excluded-dishes")
async def mark_excluded(request: Request, body: ExcludeBody) -> Dict[str, Any]:
    """Mark POS dish names as 'noise' (打包盒 / 需要餐具 / 广告文字 etc.).
    Excluded names stop counting toward coverage denominator + margin analysis.
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    try:
        import asyncpg
        from config import get_settings
    except Exception as e:
        return {"success": False, "message": f"config error: {e}"}

    settings = get_settings()
    conn = await asyncpg.connect(settings.food_kb_db_url)
    try:
        await _ensure_excluded_dishes_table(conn)
        count = 0
        for name in body.pos_names:
            try:
                await conn.execute("""
                    INSERT INTO dim_product_excluded (factory_id, pos_name, reason)
                    VALUES ($1, $2, 'user_marked')
                    ON CONFLICT (factory_id, pos_name) DO NOTHING
                """, factory_id, name)
                count += 1
            except Exception as e:
                logger.warning(f"[excluded] insert {name} failed: {e}")
        return {"success": True, "data": {"markedCount": count}}
    finally:
        await conn.close()


@router.get("/restaurant-ops/excluded-dishes")
async def list_excluded(request: Request) -> Dict[str, Any]:
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}
    try:
        import asyncpg
        from config import get_settings
    except Exception as e:
        return {"success": False, "message": f"config error: {e}"}
    settings = get_settings()
    conn = await asyncpg.connect(settings.food_kb_db_url)
    try:
        await _ensure_excluded_dishes_table(conn)
        rows = await conn.fetch(
            "SELECT pos_name, reason, created_at FROM dim_product_excluded WHERE factory_id = $1 ORDER BY created_at DESC",  # noqa: E501
            factory_id,
        )
        return {
            "success": True,
            "data": {
                "excluded": [
                    {"posName": r["pos_name"], "reason": r["reason"], "createdAt": r["created_at"].isoformat() if r["created_at"] else None}  # noqa: E501
                    for r in rows
                ],
            },
        }
    finally:
        await conn.close()


@router.delete("/restaurant-ops/excluded-dishes/{pos_name}")
async def unexclude_dish(request: Request, pos_name: str) -> Dict[str, Any]:
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}
    try:
        import asyncpg
        from config import get_settings
    except Exception as e:
        return {"success": False, "message": f"config error: {e}"}
    settings = get_settings()
    conn = await asyncpg.connect(settings.food_kb_db_url)
    try:
        result = await conn.execute(
            "DELETE FROM dim_product_excluded WHERE factory_id = $1 AND pos_name = $2",
            factory_id, pos_name,
        )
        return {"success": True, "data": {"deleted": result.endswith("1")}}
    finally:
        await conn.close()


# ==========================================================================
# P1-3: Raw material price history (read-only for now — trigger auto-snapshots)
# ==========================================================================
@router.get("/restaurant-ops/materials/{material_id}/price-history")
async def price_history(request: Request, material_id: str) -> Dict[str, Any]:
    """Return price changes over time for one raw material."""
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}
    try:
        import asyncpg
        from config import get_settings
    except Exception as e:
        return {"success": False, "message": f"config error: {e}"}
    settings = get_settings()
    conn = await asyncpg.connect(settings.food_kb_db_url)
    try:
        rows = await conn.fetch("""
            SELECT unit_price, effective_from, effective_to, change_reason, changed_by, created_at
              FROM raw_material_price_history
             WHERE factory_id = $1 AND raw_material_type_id = $2
             ORDER BY effective_from DESC
        """, factory_id, material_id)
        return {
            "success": True,
            "data": {
                "history": [
                    {
                        "unitPrice": float(r["unit_price"]),
                        "effectiveFrom": r["effective_from"].isoformat() if r["effective_from"] else None,
                        "effectiveTo": r["effective_to"].isoformat() if r["effective_to"] else None,
                        "changeReason": r["change_reason"],
                    }
                    for r in rows
                ],
            },
        }
    finally:
        await conn.close()


# ==========================================================================
# P2-7: LLM recipe draft suggestion (qwen3-max generate JSON recipe)
# ==========================================================================
class RecipeDraftRequest(BaseModel):
    dish_name: str
    hint: Optional[str] = None  # 如 "川菜 / 粤菜 / 素菜" / "用量参考人均"


class RecipeDraftBatchRequest(BaseModel):
    """P2-7 加速版: 一次性提交多个菜名, 后端并发调 LLM, 全部完成后返回.
    客户侧体验: 点一下按钮, 1-2 分钟看到所有草稿, 而不是等 10 分钟串行.
    """
    dish_names: List[str]
    hint: Optional[str] = None
    concurrency: int = 10  # 并发 LLM 调用数 (DashScope 限流不超过 60/min 即可)


def _build_recipe_prompt(dish_name: str, hint: Optional[str] = None) -> str:
    """Accelerated prompt: ask LLM for ingredients AND price suggestion in one call.
    Saves the user from manually filling unit_price per ingredient."""
    return f"""你是资深中餐厨师兼成本核算师. 给定菜品名称, 输出一份配方草稿 JSON.

菜品: {dish_name}
{f'提示: {hint}' if hint else ''}

要求:
- 3-5 种主要食材 (主料 1-2 个, 辅料 + 调味料 2-3 个)
- 每种食材给出:
  name (中文), qty (数字), unit (kg/L/个/份), is_main (true/false),
  suggested_unit_price (元/单位, 参考当前市场采购价, 例: 牛肉 100, 鸡腿 22, 食用油 12, 盐 3, 花椒 80)
- 数量按单份/单人份计算
- 食材成本率目标 25-40%
- 输出**纯 JSON** 不要任何 markdown 或注释

JSON schema:
{{
  "ingredients": [
    {{"name": "鲈鱼", "qty": 0.25, "unit": "kg", "is_main": true, "suggested_unit_price": 45}}
  ],
  "estimated_cost_ratio": 0.32,
  "notes": "川菜经典做法..."
}}"""


async def _call_llm_for_recipe(dish_name: str, hint: Optional[str], client=None, settings=None) -> Dict[str, Any]:
    """Single LLM call for one dish. Returns parsed dict or {success:false}.

    走 call_chain(SLOT.INSIGHTS): 免费 fallback 链 + 熔断。model 由 router 按 slot 注入。
    client/settings 参数保留兼容旧 caller 签名, 已不使用 (router 自管客户端 + key)。
    """
    if not dish_name or len(dish_name) < 2:
        return {"success": False, "dishName": dish_name, "message": "菜名过短"}
    prompt = _build_recipe_prompt(dish_name, hint)
    payload = {
        # model 由 call_chain(SLOT.INSIGHTS) 按免费链注入
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 800,
        "temperature": 0.3,
        "enable_thinking": False,
    }
    try:
        from common.llm_router import call_chain, SLOT
        from common.llm_metrics import llm_caller_context
        with llm_caller_context("ai_recipe_draft"):
            data = await call_chain(SLOT.INSIGHTS, payload, timeout=45.0)
        content = data["choices"][0]["message"]["content"].strip()
        if content.startswith("```"):
            content = content.strip("`").lstrip("json").strip()
        import json as _json
        parsed = _json.loads(content)
        return {
            "success": True,
            "dishName": dish_name,
            "ingredients": parsed.get("ingredients", []),
            "estimatedCostRatio": parsed.get("estimated_cost_ratio"),
            "notes": parsed.get("notes", ""),
        }
    except Exception as e:
        logger.warning(f"[ai-recipe-draft] {dish_name} failed: {e}")
        return {"success": False, "dishName": dish_name, "message": str(e)}


@router.post("/restaurant-ops/recipes/ai-draft")
async def ai_recipe_draft(request: Request, body: RecipeDraftRequest) -> Dict[str, Any]:
    """LLM (qwen3-max) generates a draft recipe for ONE dish, including
    suggested unit prices so user doesn't need to fill price manually.
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}
    try:
        from common.llm_client import get_llm_http_client
        from config import get_settings as _get_settings
        settings = _get_settings()
        client = get_llm_http_client()
        result = await _call_llm_for_recipe(body.dish_name, body.hint, client, settings)
        if result.get("success"):
            return {"success": True, "data": {
                "dishName": result["dishName"],
                "ingredients": result["ingredients"],
                "estimatedCostRatio": result.get("estimatedCostRatio"),
                "notes": result.get("notes", ""),
            }}
        return {"success": False, "message": result.get("message", "LLM 调用失败")}
    except Exception as e:
        logger.exception(f"[ai-recipe-draft] outer failed for {body.dish_name}")
        return {"success": False, "message": f"LLM 调用失败: {e}"}


@router.get("/restaurant-ops/recipes/auto-drafts")
async def get_auto_drafts(request: Request) -> Dict[str, Any]:
    """加速 D: read pre-generated AI recipe drafts (created in background after upload).

    Returns: { drafts: [{dishName, ingredients, estimatedCostRatio, notes, generatedAt}], count }
    Customer FE polls this endpoint when 配方管理 page opens; if drafts present,
    show "AI 已为你预生成 N 条配方草稿" banner with one-click 采纳 button.
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}
    try:
        import asyncpg
        from config import get_settings
    except Exception as e:
        return {"success": False, "message": f"config error: {e}"}
    settings = get_settings()
    conn = await asyncpg.connect(settings.food_kb_db_url)
    try:
        try:
            rows = await conn.fetch("""
                SELECT dish_name, drafts_json, estimated_cost_ratio, notes, generated_at
                  FROM auto_recipe_drafts
                 WHERE factory_id = $1 AND consumed_at IS NULL
                 ORDER BY generated_at DESC
            """, factory_id)
        except Exception as e:
            if "does not exist" in str(e):
                return {"success": True, "data": {"drafts": [], "count": 0}}
            raise
        import json as _json
        drafts = []
        for r in rows:
            payload = _json.loads(r["drafts_json"]) if isinstance(r["drafts_json"], str) else r["drafts_json"]
            drafts.append({
                "dishName": r["dish_name"],
                "ingredients": payload.get("ingredients", []),
                "estimatedCostRatio": float(r["estimated_cost_ratio"]) if r["estimated_cost_ratio"] else None,
                "notes": r["notes"] or "",
                "generatedAt": r["generated_at"].isoformat() if r["generated_at"] else None,
            })
        return {"success": True, "data": {"drafts": drafts, "count": len(drafts)}}
    finally:
        await conn.close()


@router.post("/restaurant-ops/recipes/auto-drafts/consume")
async def consume_auto_drafts(request: Request) -> Dict[str, Any]:
    """加速 D: mark all drafts as consumed (called after customer clicks 采纳).
    Drafts stay in auto_recipe_drafts for audit but are filtered out from /auto-drafts.
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}
    try:
        import asyncpg
        from config import get_settings
    except Exception as e:
        return {"success": False, "message": f"config error: {e}"}
    settings = get_settings()
    conn = await asyncpg.connect(settings.food_kb_db_url)
    try:
        try:
            result = await conn.execute(
                "UPDATE auto_recipe_drafts SET consumed_at = NOW() WHERE factory_id = $1 AND consumed_at IS NULL",
                factory_id,
            )
            consumed = int(result.rsplit(" ", 1)[-1]) if result else 0
        except Exception as e:
            if "does not exist" in str(e):
                consumed = 0
            else:
                raise
        return {"success": True, "data": {"consumed": consumed}}
    finally:
        await conn.close()


@router.post("/restaurant-ops/recipes/ai-draft-batch")
async def ai_recipe_draft_batch(request: Request, body: RecipeDraftBatchRequest) -> Dict[str, Any]:
    """Batch AI draft: N dishes concurrent (default 10 parallel).
    Top 130 dishes go from ~10 min (serial) to ~1-2 min (10x concurrent).

    Returns per-dish result; partial success tolerated.
    """
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}
    if not body.dish_names:
        return {"success": False, "message": "dish_names 不能为空"}
    if len(body.dish_names) > 200:
        return {"success": False, "message": "单次最多 200 道菜"}

    import asyncio
    from common.llm_client import get_llm_http_client
    from config import get_settings as _get_settings
    settings = _get_settings()
    client = get_llm_http_client()
    concurrency = max(1, min(body.concurrency or 10, 20))
    semaphore = asyncio.Semaphore(concurrency)

    async def _guarded_call(name: str) -> Dict[str, Any]:
        async with semaphore:
            return await _call_llm_for_recipe(name, body.hint, client, settings)

    import time
    t0 = time.time()
    results = await asyncio.gather(*[_guarded_call(n) for n in body.dish_names], return_exceptions=False)
    elapsed = time.time() - t0

    ok_count = sum(1 for r in results if r.get("success"))
    return {
        "success": True,
        "data": {
            "requested": len(body.dish_names),
            "succeeded": ok_count,
            "failed": len(results) - ok_count,
            "elapsedSec": round(elapsed, 2),
            "concurrency": concurrency,
            "drafts": results,
        },
    }


@router.delete("/restaurant-ops/aliases/{pos_name}")
async def delete_alias(request: Request, pos_name: str) -> Dict[str, Any]:
    factory_id = _get_factory_id(request)
    if not factory_id:
        return {"success": False, "message": "missing factory context"}

    try:
        import asyncpg
        from config import get_settings
    except Exception as e:
        return {"success": False, "message": f"config error: {e}"}

    settings = get_settings()
    conn = await asyncpg.connect(settings.food_kb_db_url)
    try:
        result = await conn.execute(
            "DELETE FROM dim_product_alias WHERE factory_id = $1 AND pos_name = $2",
            factory_id, pos_name,
        )
        return {"success": True, "data": {"deleted": result.endswith("1")}}
    finally:
        await conn.close()
