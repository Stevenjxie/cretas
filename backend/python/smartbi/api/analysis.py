from __future__ import annotations
"""
Analysis API

Endpoints for financial and sales analysis services.
"""
import logging
from typing import Any, Optional, List, Dict

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from services.analysis import (
    FinanceAnalysisService,
    SalesAnalysisService,
    DepartmentAnalysisService,
    RegionAnalysisService,
)

logger = logging.getLogger(__name__)
router = APIRouter()

# Initialize services
finance_service = FinanceAnalysisService()
sales_service = SalesAnalysisService()
department_service = DepartmentAnalysisService()
region_service = RegionAnalysisService()


# ============================================================================
# Request Models
# ============================================================================

class BaseAnalysisRequest(BaseModel):
    """Base request model for analysis endpoints"""
    data: List[Dict[str, Any]] = Field(..., description="Data to analyze")
    fieldMapping: Optional[Dict[str, str]] = Field(
        default=None,
        description="Optional mapping from standard field names to actual column names"
    )


class FinanceOverviewRequest(BaseAnalysisRequest):
    """Request model for finance overview"""
    periodType: Optional[str] = Field(
        default="month",
        description="Period type: day, week, month, quarter, year"
    )


class ProfitTrendRequest(BaseAnalysisRequest):
    """Request model for profit trend analysis"""
    periodType: Optional[str] = Field(
        default="month",
        description="Period type: day, week, month, quarter, year"
    )
    periods: Optional[int] = Field(
        default=12,
        description="Number of periods to analyze"
    )


class CostStructureRequest(BaseAnalysisRequest):
    """Request model for cost structure analysis"""
    groupBy: Optional[str] = Field(
        default=None,
        description="Optional field to group costs by"
    )


class BudgetWaterfallRequest(BaseAnalysisRequest):
    """Request model for budget waterfall analysis"""
    budgetField: Optional[str] = Field(
        default=None,
        description="Field name for budget values"
    )
    actualField: Optional[str] = Field(
        default=None,
        description="Field name for actual values"
    )


class BudgetVsActualRequest(BaseAnalysisRequest):
    """Request model for budget vs actual comparison"""
    periodType: Optional[str] = Field(
        default="month",
        description="Period type: day, week, month, quarter, year"
    )


class YoYMoMRequest(BaseAnalysisRequest):
    """Request model for Year-over-Year and Month-over-Month analysis"""
    year1: Optional[int] = Field(
        default=None,
        description="First year for comparison"
    )
    year2: Optional[int] = Field(
        default=None,
        description="Second year for comparison"
    )
    metric: Optional[str] = Field(
        default="sales_amount",
        description="Metric to compare"
    )


class CategoryComparisonRequest(BaseAnalysisRequest):
    """Request model for category structure comparison"""
    categoryField: Optional[str] = Field(
        default=None,
        description="Field name for category"
    )
    periodField: Optional[str] = Field(
        default=None,
        description="Field name for time period"
    )


class SalesKPIsRequest(BaseAnalysisRequest):
    """Request model for sales KPIs"""
    targetData: Optional[List[Dict[str, Any]]] = Field(
        default=None,
        description="Optional target data for completion rate calculation"
    )


class RankingRequest(BaseAnalysisRequest):
    """Request model for ranking analysis"""
    topN: Optional[int] = Field(
        default=10,
        description="Number of top items to return"
    )
    rankBy: Optional[str] = Field(
        default="sales_amount",
        description="Metric to rank by"
    )


class SalesTrendRequest(BaseAnalysisRequest):
    """Request model for sales trend analysis"""
    periodType: Optional[str] = Field(
        default="month",
        description="Period type: day, week, month, quarter, year"
    )
    periods: Optional[int] = Field(
        default=12,
        description="Number of periods to analyze"
    )


class RegionDistributionRequest(BaseAnalysisRequest):
    """Request model for region distribution analysis"""
    regionField: Optional[str] = Field(
        default=None,
        description="Field name for region"
    )


class CustomerAnalysisRequest(BaseAnalysisRequest):
    """Request model for customer analysis"""
    customerField: Optional[str] = Field(
        default=None,
        description="Field name for customer identifier"
    )
    topN: Optional[int] = Field(
        default=10,
        description="Number of top customers to return"
    )


# ============================================================================
# Department Analysis Request Models
# ============================================================================

class DepartmentRankingRequest(BaseAnalysisRequest):
    """Request model for department ranking"""
    topN: Optional[int] = Field(
        default=10,
        description="Number of top departments to return"
    )
    metric: Optional[str] = Field(
        default="sales_amount",
        description="Metric to rank by (sales_amount, order_count, profit, etc.)"
    )


class DepartmentDetailRequest(BaseAnalysisRequest):
    """Request model for department detail"""
    departmentId: Optional[str] = Field(
        default=None,
        description="Department ID or name to get details for"
    )


class DepartmentCompletionRatesRequest(BaseAnalysisRequest):
    """Request model for department completion rates"""
    targetData: Optional[List[Dict[str, Any]]] = Field(
        default=None,
        description="Target data for completion rate calculation"
    )


class DepartmentEfficiencyMatrixRequest(BaseAnalysisRequest):
    """Request model for department efficiency matrix"""
    metrics: Optional[List[str]] = Field(
        default=None,
        description="List of metrics to include in matrix"
    )


class DepartmentTrendComparisonRequest(BaseAnalysisRequest):
    """Request model for department trend comparison"""
    departments: Optional[List[str]] = Field(
        default=None,
        description="List of department IDs to compare"
    )
    periodType: Optional[str] = Field(
        default="month",
        description="Period type: day, week, month, quarter, year"
    )


class DepartmentShareTrendRequest(BaseAnalysisRequest):
    """Request model for department share trend"""
    periodType: Optional[str] = Field(
        default="month",
        description="Period type: day, week, month, quarter, year"
    )
    periods: Optional[int] = Field(
        default=12,
        description="Number of periods to analyze"
    )


# ============================================================================
# Region Analysis Request Models
# ============================================================================

class RegionRankingRequest(BaseAnalysisRequest):
    """Request model for region ranking"""
    topN: Optional[int] = Field(
        default=10,
        description="Number of top regions to return"
    )
    metric: Optional[str] = Field(
        default="sales_amount",
        description="Metric to rank by"
    )
    level: Optional[str] = Field(
        default="region",
        description="Region level: region, province, city"
    )


class ProvinceRankingRequest(BaseAnalysisRequest):
    """Request model for province ranking"""
    topN: Optional[int] = Field(
        default=10,
        description="Number of top provinces to return"
    )
    metric: Optional[str] = Field(
        default="sales_amount",
        description="Metric to rank by"
    )


class CityRankingRequest(BaseAnalysisRequest):
    """Request model for city ranking"""
    topN: Optional[int] = Field(
        default=20,
        description="Number of top cities to return"
    )
    metric: Optional[str] = Field(
        default="sales_amount",
        description="Metric to rank by"
    )
    province: Optional[str] = Field(
        default=None,
        description="Filter by province"
    )


class RegionDetailRequest(BaseAnalysisRequest):
    """Request model for region detail"""
    regionId: Optional[str] = Field(
        default=None,
        description="Region ID or name to get details for"
    )


class RegionTrendRequest(BaseAnalysisRequest):
    """Request model for region trend"""
    regionId: Optional[str] = Field(
        default=None,
        description="Region ID or name"
    )
    periodType: Optional[str] = Field(
        default="month",
        description="Period type: day, week, month, quarter, year"
    )
    periods: Optional[int] = Field(
        default=12,
        description="Number of periods to analyze"
    )


class GeographicHeatmapRequest(BaseAnalysisRequest):
    """Request model for geographic heatmap"""
    metric: Optional[str] = Field(
        default="sales_amount",
        description="Metric for heatmap intensity"
    )
    level: Optional[str] = Field(
        default="province",
        description="Geographic level: province, city"
    )


class RegionOpportunityScoresRequest(BaseAnalysisRequest):
    """Request model for region opportunity scores"""
    factors: Optional[List[str]] = Field(
        default=None,
        description="Factors to include in opportunity scoring"
    )


class HierarchyDataRequest(BaseAnalysisRequest):
    """Request model for hierarchy data"""
    level: Optional[str] = Field(
        default="all",
        description="Hierarchy level to return: all, region, province, city"
    )


# ============================================================================
# Finance Analysis Additional Request Models
# ============================================================================

class ReceivableAgingRequest(BaseAnalysisRequest):
    """Request model for accounts receivable aging"""
    agingBuckets: Optional[List[int]] = Field(
        default=None,
        description="Aging bucket boundaries in days (e.g., [30, 60, 90, 180])"
    )


class ReceivableMetricsRequest(BaseAnalysisRequest):
    """Request model for receivable metrics"""


class OverdueCustomerRankingRequest(BaseAnalysisRequest):
    """Request model for overdue customer ranking"""
    topN: Optional[int] = Field(
        default=10,
        description="Number of top overdue customers to return"
    )


class PayableAgingRequest(BaseAnalysisRequest):
    """Request model for accounts payable aging"""
    agingBuckets: Optional[List[int]] = Field(
        default=None,
        description="Aging bucket boundaries in days (e.g., [30, 60, 90, 180])"
    )


class BudgetExecutionWaterfallRequest(BaseAnalysisRequest):
    """Request model for budget execution waterfall"""
    categoryField: Optional[str] = Field(
        default=None,
        description="Field name for budget category"
    )


class BudgetAchievementByPeriodRequest(BaseAnalysisRequest):
    """Request model for budget achievement by period"""
    periodType: Optional[str] = Field(
        default="month",
        description="Period type: day, week, month, quarter, year"
    )


class MultiYearComparisonRequest(BaseAnalysisRequest):
    """Request model for multi-year comparison"""
    years: Optional[List[int]] = Field(
        default=None,
        description="List of years to compare"
    )
    metric: Optional[str] = Field(
        default="sales_amount",
        description="Metric to compare across years"
    )


# ============================================================================
# Response Models
# ============================================================================

class AnalysisResponse(BaseModel):
    """Standard analysis response model"""
    success: bool
    data: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


# ============================================================================
# Finance Analysis Endpoints
# ============================================================================

@router.post("/finance/overview", response_model=AnalysisResponse)
async def get_finance_overview(request: FinanceOverviewRequest):
    """
    Get financial overview with key metrics

    Returns aggregated financial metrics including:
    - Total revenue
    - Total cost
    - Gross profit and margin
    - Net profit and margin
    - Period-over-period changes

    - **data**: List of financial data records
    - **periodType**: Period type for aggregation (day, week, month, quarter, year)
    - **fieldMapping**: Optional mapping from standard field names to actual column names
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = finance_service.get_finance_overview(
            data=request.data
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Finance overview error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/finance/profit-trend", response_model=AnalysisResponse)
async def get_profit_trend(request: ProfitTrendRequest):
    """
    Analyze profit trends over time

    Returns time series data showing:
    - Revenue trend
    - Cost trend
    - Profit trend
    - Margin changes

    - **data**: List of financial data records
    - **periodType**: Period type for trend analysis
    - **periods**: Number of periods to include
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = finance_service.get_profit_trend(
            data=request.data,
            period_type=request.periodType
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Profit trend error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/finance/cost-structure", response_model=AnalysisResponse)
async def get_cost_structure(request: CostStructureRequest):
    """
    Analyze cost structure breakdown

    Returns cost breakdown by category:
    - Material costs
    - Labor costs
    - Overhead costs
    - Other costs
    - Percentage of each category

    - **data**: List of cost data records
    - **groupBy**: Optional field to group costs by
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = finance_service.get_cost_structure(
            data=request.data
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Cost structure error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/finance/budget-waterfall", response_model=AnalysisResponse)
async def get_budget_waterfall(request: BudgetWaterfallRequest):
    """
    Generate budget waterfall chart data

    Returns waterfall data showing:
    - Starting budget
    - Incremental changes by category
    - Final actual value
    - Variance explanations

    - **data**: List of budget/actual data records
    - **budgetField**: Field name for budget values
    - **actualField**: Field name for actual values
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = finance_service.get_budget_waterfall(
            data=request.data
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Budget waterfall error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/finance/budget-vs-actual", response_model=AnalysisResponse)
async def get_budget_vs_actual(request: BudgetVsActualRequest):
    """
    Compare budget vs actual values

    Returns comparison data:
    - Budget values by period/category
    - Actual values by period/category
    - Variance amount and percentage
    - Achievement rate

    - **data**: List of budget/actual data records
    - **periodType**: Period type for comparison
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = finance_service.get_budget_vs_actual(
            data=request.data
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Budget vs actual error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/finance/yoy-mom", response_model=AnalysisResponse)
async def get_yoy_mom_comparison(request: YoYMoMRequest):
    """
    Year-over-Year and Month-over-Month comparison

    Returns comparison data:
    - YoY growth rates
    - MoM growth rates
    - Period-by-period comparison
    - Trend indicators

    - **data**: List of time series data records
    - **year1**: First year for comparison
    - **year2**: Second year for comparison
    - **metric**: Metric to compare
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = finance_service.get_yoy_mom_comparison(
            data=request.data,
            period_type=request.metric or "monthly"
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"YoY/MoM comparison error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/finance/category-comparison", response_model=AnalysisResponse)
async def get_category_comparison(request: CategoryComparisonRequest):
    """
    Compare category structure across periods

    Returns comparison data:
    - Category distribution for each period
    - Changes in category proportions
    - Growth/decline by category

    - **data**: List of categorized data records
    - **categoryField**: Field name for category
    - **periodField**: Field name for time period
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = finance_service.get_category_comparison(
            data=request.data,
            year1=2024,
            year2=2025
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Category comparison error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


# ============================================================================
# Sales Analysis Endpoints
# ============================================================================

@router.post("/sales/kpis", response_model=AnalysisResponse)
async def get_sales_kpis(request: SalesKPIsRequest):
    """
    Get key sales KPIs

    Returns sales KPI metrics:
    - Total sales amount
    - Order count
    - Average order value
    - Target completion rate (if target data provided)
    - Growth indicators

    - **data**: List of sales data records
    - **targetData**: Optional target data for completion rate
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = sales_service.get_sales_kpis(
            data=request.data
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Sales KPIs error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/sales/ranking/salesperson", response_model=AnalysisResponse)
async def get_salesperson_ranking(request: RankingRequest):
    """
    Get salesperson performance ranking

    Returns ranked list of salespeople:
    - Salesperson name/ID
    - Total sales amount
    - Order count
    - Average order value
    - Rank and percentage of total

    - **data**: List of sales data records
    - **topN**: Number of top salespeople to return
    - **rankBy**: Metric to rank by (sales_amount, order_count, etc.)
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = sales_service.get_salesperson_ranking(
            data=request.data,
            top_n=request.topN or 10
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Salesperson ranking error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/sales/ranking/product", response_model=AnalysisResponse)
async def get_product_ranking(request: RankingRequest):
    """
    Get product sales ranking

    Returns ranked list of products:
    - Product name/ID
    - Total sales amount
    - Units sold
    - Average unit price
    - Rank and percentage of total

    - **data**: List of sales data records
    - **topN**: Number of top products to return
    - **rankBy**: Metric to rank by (sales_amount, quantity, etc.)
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = sales_service.get_product_ranking(
            data=request.data,
            top_n=request.topN or 10
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Product ranking error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/sales/trend", response_model=AnalysisResponse)
async def get_sales_trend(request: SalesTrendRequest):
    """
    Analyze sales trends over time

    Returns time series data:
    - Sales amount by period
    - Order count by period
    - Growth rate by period
    - Moving averages

    - **data**: List of sales data records
    - **periodType**: Period type for trend analysis
    - **periods**: Number of periods to include
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = sales_service.get_sales_trend(
            data=request.data,
            period_type=request.periodType or "daily"
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Sales trend error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/sales/region-distribution", response_model=AnalysisResponse)
async def get_region_distribution(request: RegionDistributionRequest):
    """
    Analyze sales distribution by region

    Returns regional breakdown:
    - Sales amount by region
    - Percentage of total by region
    - Order count by region
    - Regional growth rates

    - **data**: List of sales data records with region information
    - **regionField**: Field name for region
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = sales_service.get_region_distribution(
            data=request.data
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Region distribution error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/sales/customer-analysis", response_model=AnalysisResponse)
async def get_customer_analysis(request: CustomerAnalysisRequest):
    """
    Analyze customer purchasing behavior

    Returns customer analysis:
    - Top customers by sales
    - Customer count
    - Average customer value
    - Repeat purchase rate
    - Customer segmentation

    - **data**: List of sales data records with customer information
    - **customerField**: Field name for customer identifier
    - **topN**: Number of top customers to return
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = sales_service.get_customer_analysis(
            data=request.data
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Customer analysis error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


# ============================================================================
# Department Analysis Endpoints
# ============================================================================

@router.post("/department/ranking", response_model=AnalysisResponse)
async def get_department_ranking(request: DepartmentRankingRequest):
    """
    Get department performance ranking

    Returns ranked list of departments:
    - Department name/ID
    - Sales amount
    - Order count
    - Target achievement rate
    - Rank and percentage of total

    - **data**: List of sales data records with department information
    - **topN**: Number of top departments to return
    - **metric**: Metric to rank by
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = department_service.get_department_ranking(
            data=request.data,
            top_n=request.topN or 10,
            rank_by=request.metric or "sales_amount",
            field_mapping=request.fieldMapping
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Department ranking error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/department/detail", response_model=AnalysisResponse)
async def get_department_detail(request: DepartmentDetailRequest):
    """
    Get detailed analysis for a specific department

    Returns:
    - Department KPIs
    - Sales breakdown
    - Performance metrics
    - Historical trends

    - **data**: List of data records
    - **departmentId**: Department ID or name
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = department_service.get_department_detail(
            data=request.data,
            department_id=request.departmentId
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Department detail error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/department/completion-rates", response_model=AnalysisResponse)
async def get_department_completion_rates(request: DepartmentCompletionRatesRequest):
    """
    Get target completion rates by department

    Returns:
    - Department name
    - Target value
    - Actual value
    - Completion rate percentage
    - Variance

    - **data**: List of data records with department performance
    - **targetData**: Optional separate target data
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = department_service.get_department_completion_rates(
            data=request.data,
            target_data=request.targetData
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Department completion rates error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/department/efficiency-matrix", response_model=AnalysisResponse)
async def get_department_efficiency_matrix(request: DepartmentEfficiencyMatrixRequest):
    """
    Get efficiency matrix across departments

    Returns matrix data:
    - Department names
    - Multiple efficiency metrics per department
    - Normalized scores
    - Radar chart ready data

    - **data**: List of data records
    - **metrics**: List of metrics to include
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = department_service.get_department_efficiency_matrix(
            data=request.data,
            field_mapping=request.fieldMapping
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Department efficiency matrix error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/department/trend-comparison", response_model=AnalysisResponse)
async def get_department_trend_comparison(request: DepartmentTrendComparisonRequest):
    """
    Compare trends across multiple departments

    Returns:
    - Time series data for each department
    - Growth rates
    - Comparison metrics

    - **data**: List of data records
    - **departments**: List of department IDs to compare
    - **periodType**: Period type for trend
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = department_service.get_department_trend_comparison(
            data=request.data,
            departments=request.departments,
            period_type=request.periodType or "month"
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Department trend comparison error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/department/share-trend", response_model=AnalysisResponse)
async def get_department_share_trend(request: DepartmentShareTrendRequest):
    """
    Get department market share trends over time

    Returns:
    - Period labels
    - Share percentage per department per period
    - Trend indicators

    - **data**: List of data records
    - **periodType**: Period type for trend
    - **periods**: Number of periods to analyze
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = department_service.get_department_share_trend(
            data=request.data,
            period_type=request.periodType or "month",
            periods=request.periods or 12
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Department share trend error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


# ============================================================================
# Region Analysis Endpoints
# ============================================================================

@router.post("/region/ranking", response_model=AnalysisResponse)
async def get_region_ranking(request: RegionRankingRequest):
    """
    Get region performance ranking

    Returns ranked list of regions:
    - Region name
    - Sales amount
    - Order count
    - Growth rate
    - Rank and percentage

    - **data**: List of data records with region information
    - **topN**: Number of top regions to return
    - **metric**: Metric to rank by
    - **level**: Region level (region, province, city)
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = region_service.get_region_ranking(
            data=request.data,
            top_n=request.topN or 10,
            rank_by=request.metric or "sales_amount",
            field_mapping=request.fieldMapping
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Region ranking error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/region/province-ranking", response_model=AnalysisResponse)
async def get_province_ranking(request: ProvinceRankingRequest):
    """
    Get province-level performance ranking

    Returns ranked list of provinces:
    - Province name
    - Sales amount
    - Order count
    - City count
    - Rank and percentage

    - **data**: List of data records with province information
    - **topN**: Number of top provinces to return
    - **metric**: Metric to rank by
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = region_service.get_province_ranking(
            data=request.data,
            top_n=request.topN or 10,
            field_mapping=request.fieldMapping
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Province ranking error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/region/city-ranking", response_model=AnalysisResponse)
async def get_city_ranking(request: CityRankingRequest):
    """
    Get city-level performance ranking

    Returns ranked list of cities:
    - City name
    - Province
    - Sales amount
    - Order count
    - Rank and percentage

    - **data**: List of data records with city information
    - **topN**: Number of top cities to return
    - **metric**: Metric to rank by
    - **province**: Optional filter by province
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = region_service.get_city_ranking(
            data=request.data,
            province=request.province,
            top_n=request.topN or 20,
            field_mapping=request.fieldMapping
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"City ranking error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/region/detail", response_model=AnalysisResponse)
async def get_region_detail(request: RegionDetailRequest):
    """
    Get detailed analysis for a specific region

    Returns:
    - Region KPIs
    - Sub-region breakdown
    - Performance metrics
    - Historical trends

    - **data**: List of data records
    - **regionId**: Region ID or name
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = region_service.get_region_detail(
            data=request.data,
            region_id=request.regionId
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Region detail error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/region/trend", response_model=AnalysisResponse)
async def get_region_trend(request: RegionTrendRequest):
    """
    Get trend analysis for a specific region

    Returns:
    - Time series data
    - Growth rates
    - Trend indicators

    - **data**: List of data records
    - **regionId**: Region ID or name
    - **periodType**: Period type for trend
    - **periods**: Number of periods
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = region_service.get_region_trend(
            data=request.data,
            region_id=request.regionId,
            period_type=request.periodType or "month",
            periods=request.periods or 12
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Region trend error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/region/heatmap", response_model=AnalysisResponse)
async def get_geographic_heatmap_data(request: GeographicHeatmapRequest):
    """
    Get geographic heatmap data

    Returns:
    - Geographic coordinates or region codes
    - Intensity values for heatmap
    - Legend configuration

    - **data**: List of data records with geographic information
    - **metric**: Metric for heatmap intensity
    - **level**: Geographic level (province, city)
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = region_service.get_geographic_heatmap_data(
            data=request.data,
            level=request.level or "province",
            field_mapping=request.fieldMapping
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Geographic heatmap error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/region/opportunity-scores", response_model=AnalysisResponse)
async def get_region_opportunity_scores(request: RegionOpportunityScoresRequest):
    """
    Get opportunity scores by region

    Returns:
    - Region name
    - Opportunity score
    - Contributing factors
    - Recommendations

    - **data**: List of data records
    - **factors**: Factors to include in scoring
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = region_service.get_region_opportunity_scores(
            data=request.data,
            factors=request.factors
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Region opportunity scores error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/region/hierarchy", response_model=AnalysisResponse)
async def get_hierarchy_data(request: HierarchyDataRequest):
    """
    Get hierarchical region data

    Returns:
    - Tree structure of regions
    - Aggregated values at each level
    - Drill-down capabilities

    - **data**: List of data records
    - **level**: Hierarchy level to return
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = region_service.get_hierarchy_data(
            data=request.data,
            level=request.level or "all"
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Hierarchy data error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


# ============================================================================
# Finance Analysis Additional Endpoints
# ============================================================================

@router.post("/finance/ar-aging", response_model=AnalysisResponse)
async def get_receivable_aging(request: ReceivableAgingRequest):
    """
    Get accounts receivable aging analysis

    Returns:
    - Aging buckets (current, 30 days, 60 days, 90+ days)
    - Amount in each bucket
    - Percentage distribution
    - Risk indicators

    - **data**: List of receivable records
    - **agingBuckets**: Custom aging bucket boundaries
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = finance_service.get_receivable_aging(
            data=request.data,
            aging_buckets=request.agingBuckets
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Receivable aging error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/finance/ar-metrics", response_model=AnalysisResponse)
async def get_receivable_metrics(request: ReceivableMetricsRequest):
    """
    Get accounts receivable key metrics

    Returns:
    - Total receivables
    - Days Sales Outstanding (DSO)
    - Collection rate
    - Bad debt ratio

    - **data**: List of receivable records
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = finance_service.get_receivable_metrics(
            data=request.data
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Receivable metrics error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/finance/overdue-customers", response_model=AnalysisResponse)
async def get_overdue_customer_ranking(request: OverdueCustomerRankingRequest):
    """
    Get ranking of customers with overdue receivables

    Returns:
    - Customer name
    - Overdue amount
    - Days overdue
    - Payment history
    - Risk level

    - **data**: List of receivable records
    - **topN**: Number of top overdue customers
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = finance_service.get_overdue_customer_ranking(
            data=request.data,
            top_n=request.topN or 10
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Overdue customer ranking error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/finance/ap-aging", response_model=AnalysisResponse)
async def get_payable_aging(request: PayableAgingRequest):
    """
    Get accounts payable aging analysis

    Returns:
    - Aging buckets (current, 30 days, 60 days, 90+ days)
    - Amount in each bucket
    - Percentage distribution
    - Payment schedule

    - **data**: List of payable records
    - **agingBuckets**: Custom aging bucket boundaries
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = finance_service.get_payable_aging(
            data=request.data,
            aging_buckets=request.agingBuckets
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Payable aging error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/finance/budget-execution-waterfall", response_model=AnalysisResponse)
async def get_budget_execution_waterfall(request: BudgetExecutionWaterfallRequest):
    """
    Get budget execution waterfall chart data

    Returns:
    - Starting budget
    - Category-wise execution changes
    - Final executed amount
    - Variance breakdown

    - **data**: List of budget execution records
    - **categoryField**: Field name for budget category
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = finance_service.get_budget_execution_waterfall(
            data=request.data,
            category_field=request.categoryField
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Budget execution waterfall error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/finance/budget-achievement", response_model=AnalysisResponse)
async def get_budget_achievement_by_period(request: BudgetAchievementByPeriodRequest):
    """
    Get budget achievement rates by period

    Returns:
    - Period labels
    - Budget values per period
    - Actual values per period
    - Achievement rate per period
    - Cumulative achievement

    - **data**: List of budget/actual records
    - **periodType**: Period type for aggregation
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = finance_service.get_budget_achievement_by_period(
            data=request.data,
            period_type=request.periodType or "month"
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Budget achievement by period error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


@router.post("/finance/multi-year-comparison", response_model=AnalysisResponse)
async def get_multi_year_comparison(request: MultiYearComparisonRequest):
    """
    Compare financial metrics across multiple years

    Returns:
    - Year labels
    - Metric values per year
    - Year-over-year growth rates
    - CAGR (Compound Annual Growth Rate)

    - **data**: List of multi-year financial records
    - **years**: List of years to compare
    - **metric**: Metric to compare
    - **fieldMapping**: Optional field mapping
    """
    try:
        if not request.data:
            raise HTTPException(status_code=400, detail="Data is required")

        result = finance_service.get_multi_year_comparison(
            data=request.data,
            years=request.years,
            metrics=[request.metric or "sales_amount"] if request.metric else None,
            field_mapping=request.fieldMapping
        )

        return AnalysisResponse(**result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Multi-year comparison error: {e}", exc_info=True)
        return AnalysisResponse(success=False, error="处理失败，请稍后重试")


# ============================================================================
# Utility Endpoints
# ============================================================================

@router.get("/available")
async def get_available_analyses():
    """
    Get list of all available analysis types
    """
    return {
        "success": True,
        "data": {
            "finance": {
                "name": "Financial Analysis",
                "name_zh": "财务分析",
                "endpoints": [
                    {
                        "path": "/finance/overview",
                        "name": "Finance Overview",
                        "name_zh": "财务概览",
                        "description": "Key financial metrics overview"
                    },
                    {
                        "path": "/finance/profit-trend",
                        "name": "Profit Trend",
                        "name_zh": "利润趋势",
                        "description": "Profit trends over time"
                    },
                    {
                        "path": "/finance/cost-structure",
                        "name": "Cost Structure",
                        "name_zh": "成本结构",
                        "description": "Cost breakdown analysis"
                    },
                    {
                        "path": "/finance/budget-waterfall",
                        "name": "Budget Waterfall",
                        "name_zh": "预算瀑布图",
                        "description": "Budget variance waterfall"
                    },
                    {
                        "path": "/finance/budget-vs-actual",
                        "name": "Budget vs Actual",
                        "name_zh": "预算对比实际",
                        "description": "Budget vs actual comparison"
                    },
                    {
                        "path": "/finance/yoy-mom",
                        "name": "YoY/MoM Comparison",
                        "name_zh": "同比环比分析",
                        "description": "Year-over-year and month-over-month analysis"
                    },
                    {
                        "path": "/finance/category-comparison",
                        "name": "Category Comparison",
                        "name_zh": "品类对比",
                        "description": "Category structure comparison"
                    },
                    {
                        "path": "/finance/ar-aging",
                        "name": "Receivable Aging",
                        "name_zh": "应收账款账龄",
                        "description": "Accounts receivable aging analysis"
                    },
                    {
                        "path": "/finance/ar-metrics",
                        "name": "Receivable Metrics",
                        "name_zh": "应收账款指标",
                        "description": "Accounts receivable key metrics"
                    },
                    {
                        "path": "/finance/overdue-customers",
                        "name": "Overdue Customers",
                        "name_zh": "逾期客户排名",
                        "description": "Ranking of customers with overdue receivables"
                    },
                    {
                        "path": "/finance/ap-aging",
                        "name": "Payable Aging",
                        "name_zh": "应付账款账龄",
                        "description": "Accounts payable aging analysis"
                    },
                    {
                        "path": "/finance/budget-execution-waterfall",
                        "name": "Budget Execution Waterfall",
                        "name_zh": "预算执行瀑布图",
                        "description": "Budget execution waterfall chart"
                    },
                    {
                        "path": "/finance/budget-achievement",
                        "name": "Budget Achievement by Period",
                        "name_zh": "分期预算达成",
                        "description": "Budget achievement rates by period"
                    },
                    {
                        "path": "/finance/multi-year-comparison",
                        "name": "Multi-Year Comparison",
                        "name_zh": "多年对比分析",
                        "description": "Compare financial metrics across multiple years"
                    }
                ]
            },
            "sales": {
                "name": "Sales Analysis",
                "name_zh": "销售分析",
                "endpoints": [
                    {
                        "path": "/sales/kpis",
                        "name": "Sales KPIs",
                        "name_zh": "销售KPI",
                        "description": "Key sales performance indicators"
                    },
                    {
                        "path": "/sales/ranking/salesperson",
                        "name": "Salesperson Ranking",
                        "name_zh": "销售员排名",
                        "description": "Salesperson performance ranking"
                    },
                    {
                        "path": "/sales/ranking/product",
                        "name": "Product Ranking",
                        "name_zh": "产品排名",
                        "description": "Product sales ranking"
                    },
                    {
                        "path": "/sales/trend",
                        "name": "Sales Trend",
                        "name_zh": "销售趋势",
                        "description": "Sales trends over time"
                    },
                    {
                        "path": "/sales/region-distribution",
                        "name": "Region Distribution",
                        "name_zh": "区域分布",
                        "description": "Sales distribution by region"
                    },
                    {
                        "path": "/sales/customer-analysis",
                        "name": "Customer Analysis",
                        "name_zh": "客户分析",
                        "description": "Customer purchasing behavior analysis"
                    }
                ]
            },
            "department": {
                "name": "Department Analysis",
                "name_zh": "部门分析",
                "endpoints": [
                    {
                        "path": "/department/ranking",
                        "name": "Department Ranking",
                        "name_zh": "部门排名",
                        "description": "Department performance ranking"
                    },
                    {
                        "path": "/department/detail",
                        "name": "Department Detail",
                        "name_zh": "部门详情",
                        "description": "Detailed analysis for specific department"
                    },
                    {
                        "path": "/department/completion-rates",
                        "name": "Completion Rates",
                        "name_zh": "完成率分析",
                        "description": "Target completion rates by department"
                    },
                    {
                        "path": "/department/efficiency-matrix",
                        "name": "Efficiency Matrix",
                        "name_zh": "效率矩阵",
                        "description": "Efficiency matrix across departments"
                    },
                    {
                        "path": "/department/trend-comparison",
                        "name": "Trend Comparison",
                        "name_zh": "趋势对比",
                        "description": "Compare trends across departments"
                    },
                    {
                        "path": "/department/share-trend",
                        "name": "Share Trend",
                        "name_zh": "份额趋势",
                        "description": "Department market share trends"
                    }
                ]
            },
            "region": {
                "name": "Region Analysis",
                "name_zh": "区域分析",
                "endpoints": [
                    {
                        "path": "/region/ranking",
                        "name": "Region Ranking",
                        "name_zh": "区域排名",
                        "description": "Region performance ranking"
                    },
                    {
                        "path": "/region/province-ranking",
                        "name": "Province Ranking",
                        "name_zh": "省份排名",
                        "description": "Province-level performance ranking"
                    },
                    {
                        "path": "/region/city-ranking",
                        "name": "City Ranking",
                        "name_zh": "城市排名",
                        "description": "City-level performance ranking"
                    },
                    {
                        "path": "/region/detail",
                        "name": "Region Detail",
                        "name_zh": "区域详情",
                        "description": "Detailed analysis for specific region"
                    },
                    {
                        "path": "/region/trend",
                        "name": "Region Trend",
                        "name_zh": "区域趋势",
                        "description": "Trend analysis for specific region"
                    },
                    {
                        "path": "/region/heatmap",
                        "name": "Geographic Heatmap",
                        "name_zh": "地理热力图",
                        "description": "Geographic heatmap data"
                    },
                    {
                        "path": "/region/opportunity-scores",
                        "name": "Opportunity Scores",
                        "name_zh": "机会评分",
                        "description": "Opportunity scores by region"
                    },
                    {
                        "path": "/region/hierarchy",
                        "name": "Hierarchy Data",
                        "name_zh": "层级数据",
                        "description": "Hierarchical region data"
                    }
                ]
            }
        }
    }
