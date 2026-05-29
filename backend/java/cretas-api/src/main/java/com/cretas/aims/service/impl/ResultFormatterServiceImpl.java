package com.cretas.aims.service.impl;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.service.ResultFormatterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 结果格式化服务实现
 * 根据意图类型将 resultData 转换为自然语言文本
 *
 * @author Cretas Team
 * @version 2.0.0 (v6 enhanced)
 * @since 2026-01-18
 */
@Slf4j
@Service
public class ResultFormatterServiceImpl implements ResultFormatterService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM月dd日");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("MM月dd日 HH:mm");
    private static final DecimalFormat AMOUNT_FORMATTER = new DecimalFormat("#,##0.00");
    private static final DecimalFormat QUANTITY_FORMATTER = new DecimalFormat("#,##0.##");

    @Override
    public String format(IntentExecuteResponse response) {
        if (response == null || response.getResultData() == null) {
            return response != null ? response.getMessage() : null;
        }

        String intentCode = response.getIntentCode();
        String intentCategory = response.getIntentCategory();
        Object resultData = response.getResultData();

        try {
            // 根据意图分类选择格式化器
            String formatted = switch (intentCategory) {
                case "SHIPMENT" -> formatShipmentResult(intentCode, resultData);
                case "MATERIAL" -> formatMaterialResult(intentCode, resultData);
                case "QUALITY" -> formatQualityResult(intentCode, resultData);
                case "PRODUCTION" -> formatProductionResult(intentCode, resultData);
                case "REPORT", "ANALYSIS" -> formatReportResult(intentCode, resultData);
                case "HR" -> formatHRResult(intentCode, resultData);
                case "CRM" -> formatCRMResult(intentCode, resultData);
                case "ALERT" -> formatAlertResult(intentCode, resultData);
                case "EQUIPMENT" -> formatEquipmentResult(intentCode, resultData);
                case "FOOD_KNOWLEDGE" -> formatFoodKnowledgeResult(resultData, response.getMessage());
                default -> formatGenericResult(resultData);
            };

            // If specific formatter returned non-null, prefer it (even if short — empty-state messages are valid)
            if (formatted != null) {
                // Only upgrade to handler message if it's significantly longer and formatted is very short
                String handlerMsg = response.getMessage();
                if (formatted.length() < 15 && handlerMsg != null && handlerMsg.length() >= 30) {
                    return handlerMsg;
                }
                return formatted;
            }

            // Formatter returned null — try handler message, then generic
            String handlerMsg = response.getMessage();
            if (handlerMsg != null && handlerMsg.length() >= 20) {
                return handlerMsg;
            }
            String generic = formatGenericResult(resultData);
            if (generic != null) return generic;
            return handlerMsg;
        } catch (Exception e) {
            log.warn("格式化结果失败: intentCode={}, error={}", intentCode, e.getMessage());
            return response.getMessage();
        }
    }

    // ==================== 食品知识库 格式化 ====================

    private String formatFoodKnowledgeResult(Object resultData, String handlerMessage) {
        // 食品知识库回复: resultData 含 answer/citations，message 含完整答案
        // 直接使用 message (handler 已构建好格式化答案)，避免 generic formatter 只输出元数据
        if (handlerMessage != null && handlerMessage.length() >= 20) {
            return handlerMessage;
        }
        // fallback: 从 resultData.answer 提取
        Map<String, Object> data = asMap(resultData);
        if (data != null) {
            String answer = getString(data, "answer");
            if (answer != null && answer.length() >= 20) {
                return answer;
            }
        }
        return handlerMessage;
    }

    // ==================== 出货/发货 格式化 ====================

    private String formatShipmentResult(String intentCode, Object resultData) {
        Map<String, Object> data = asMap(resultData);
        if (data == null) return null;

        return switch (intentCode) {
            case "SHIPMENT_QUERY", "SHIPMENT_BY_DATE", "SHIPMENT_BY_CUSTOMER" -> formatShipmentList(data);
            case "SHIPMENT_CREATE" -> formatShipmentCreated(data);
            case "SHIPMENT_STATS" -> formatShipmentStats(data);
            case "TRACE_BATCH", "TRACE_FULL" -> formatTraceResult(data);
            default -> formatGenericList(data);
        };
    }

    private String formatShipmentList(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();

        List<Map<String, Object>> content = getList(data, "content");
        Integer total = getInteger(data, "totalElements");
        Integer currentPage = getInteger(data, "currentPage");
        Integer totalPages = getInteger(data, "totalPages");

        if (content == null || content.isEmpty()) {
            return "当前暂无出货记录。可在出货管理模块创建新的出货单。";
        }

        sb.append(String.format("查询到 %d 条出货记录", total != null ? total : content.size()));
        if (totalPages != null && totalPages > 1) {
            sb.append(String.format("（第 %d/%d 页）", (currentPage != null ? currentPage + 1 : 1), totalPages));
        }
        sb.append("：\n\n");

        int count = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map<String, Object> item : content) {
            if (count >= 5) {
                sb.append(String.format("\n... 还有 %d 条记录", content.size() - 5));
                break;
            }

            String shipmentNumber = getString(item, "shipmentNumber");
            String productName = getString(item, "productName");
            BigDecimal quantity = getBigDecimal(item, "quantity");
            String unit = getString(item, "unit");
            String status = translateStatus(getString(item, "status"));
            LocalDate shipmentDate = getLocalDate(item, "shipmentDate");
            BigDecimal amount = getBigDecimal(item, "totalAmount");

            sb.append(String.format("%d. %s | %s %s%s | %s",
                    ++count,
                    shipmentNumber != null ? shipmentNumber : "无单号",
                    productName != null ? productName : "未知产品",
                    quantity != null ? QUANTITY_FORMATTER.format(quantity) : "?",
                    unit != null ? unit : "",
                    status));

            if (shipmentDate != null) {
                sb.append(" | ").append(shipmentDate.format(DATE_FORMATTER));
            }
            sb.append("\n");

            if (amount != null) {
                totalAmount = totalAmount.add(amount);
            }
        }

        if (totalAmount.compareTo(BigDecimal.ZERO) > 0) {
            sb.append(String.format("\n合计金额：¥%s", AMOUNT_FORMATTER.format(totalAmount)));
        }

        return sb.toString();
    }

    private String formatShipmentCreated(Map<String, Object> data) {
        String shipmentNumber = getString(data, "shipmentNumber");
        String status = translateStatus(getString(data, "status"));
        return String.format("出货单创建成功！单号：%s，状态：%s", shipmentNumber, status);
    }

    private String formatShipmentStats(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder("出货统计：\n");

        Integer totalCount = getInteger(data, "totalCount");
        BigDecimal totalAmount = getBigDecimal(data, "totalAmount");
        BigDecimal totalQuantity = getBigDecimal(data, "totalQuantity");

        if (totalCount != null) sb.append(String.format("• 总出货单数：%d 单\n", totalCount));
        if (totalQuantity != null) sb.append(String.format("• 总出货数量：%s\n", QUANTITY_FORMATTER.format(totalQuantity)));
        if (totalAmount != null) sb.append(String.format("• 总金额：¥%s", AMOUNT_FORMATTER.format(totalAmount)));

        // 空数据兜底：避免返回仅 "出货统计：\n"
        if (totalCount == null && totalAmount == null && totalQuantity == null) {
            sb.append("当前查询周期内暂无出货记录。可在出货管理模块查看历史出货数据。");
        }

        return sb.toString();
    }

    private String formatTraceResult(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder("溯源信息：\n");
        String batchNumber = getString(data, "batchNumber");
        if (batchNumber != null) {
            sb.append(String.format("批次号：%s\n", batchNumber));
        }
        String materialName = getString(data, "materialName");
        if (materialName != null) sb.append(String.format("物料名称：%s\n", materialName));
        String status = getString(data, "status");
        if (status != null) sb.append(String.format("状态：%s\n", translateStatus(status)));
        // 空数据兜底
        if (batchNumber == null && materialName == null) {
            sb.append("请提供批次号或物料名称以查询溯源信息。示例：「溯源 BAT-20250101-001」");
        }
        return sb.toString();
    }

    // ==================== 原料/库存 格式化 ====================

    private String formatMaterialResult(String intentCode, Object resultData) {
        Map<String, Object> data = asMap(resultData);
        if (data == null) return null;

        return switch (intentCode) {
            case "MATERIAL_BATCH_QUERY", "MATERIAL_STOCK_QUERY" -> formatMaterialList(data);
            case "MATERIAL_BATCH_CREATE" -> formatMaterialCreated(data);
            case "MATERIAL_LOW_STOCK_ALERT" -> formatLowStockAlert(data);
            case "PROCESSING_BATCH_LIST" -> formatProcessingBatchList(data);
            case "PROCESSING_BATCH_DETAIL" -> formatProcessingBatchDetail(data);
            case "MATERIAL_EXPIRING_ALERT" -> formatExpiringAlert(data);
            default -> formatGenericList(data);
        };
    }

    private String formatMaterialList(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();

        List<Map<String, Object>> content = getList(data, "content");
        Integer total = getInteger(data, "totalElements");

        if (content == null || content.isEmpty()) {
            return "当前暂无原料批次记录。可通过「入库」录入新原料。";
        }

        sb.append(String.format("查询到 %d 条原料记录：\n\n", total != null ? total : content.size()));

        int count = 0;
        for (Map<String, Object> item : content) {
            if (count >= 5) {
                sb.append(String.format("\n... 还有 %d 条记录", content.size() - 5));
                break;
            }

            String batchNumber = getString(item, "batchNumber");
            String materialName = getString(item, "materialName");
            BigDecimal quantity = getBigDecimal(item, "quantity");
            String unit = getString(item, "unit");
            String status = translateStatus(getString(item, "status"));

            sb.append(String.format("%d. %s | %s %s%s | %s\n",
                    ++count,
                    batchNumber != null ? batchNumber : "无批号",
                    materialName != null ? materialName : "未知原料",
                    quantity != null ? QUANTITY_FORMATTER.format(quantity) : "?",
                    unit != null ? unit : "",
                    status));
        }

        return sb.toString();
    }

    private String formatMaterialCreated(Map<String, Object> data) {
        String batchNumber = getString(data, "batchNumber");
        return String.format("原料批次创建成功！批次号：%s", batchNumber);
    }

    private String formatLowStockAlert(Map<String, Object> data) {
        List<Map<String, Object>> alerts = getList(data, "alerts");
        if (alerts == null || alerts.isEmpty()) {
            return "库存预警检查完成：当前所有原材料库存充足，暂无低库存预警项。系统将持续监控库存水位。";
        }

        StringBuilder sb = new StringBuilder(String.format("发现 %d 项库存预警：\n\n", alerts.size()));
        for (Map<String, Object> alert : alerts) {
            String materialName = getString(alert, "materialName");
            BigDecimal currentStock = getBigDecimal(alert, "currentStock");
            BigDecimal minStock = getBigDecimal(alert, "minStock");
            sb.append(String.format("• %s：当前 %s，低于最低库存 %s\n",
                    materialName,
                    currentStock != null ? QUANTITY_FORMATTER.format(currentStock) : "?",
                    minStock != null ? QUANTITY_FORMATTER.format(minStock) : "?"));
        }
        return sb.toString();
    }

    private String formatProcessingBatchList(Map<String, Object> data) {
        List<Map<String, Object>> content = getList(data, "content");
        if (content == null) content = getList(data, "batches");
        Integer total = getInteger(data, "totalElements");
        if (total == null) total = getInteger(data, "total");

        if (content == null || content.isEmpty()) {
            return "暂无生产批次记录。可在生产管理模块创建新的生产批次。";
        }

        int totalCount = total != null ? total : content.size();
        StringBuilder sb = new StringBuilder(String.format("查询到 %d 个生产批次：\n\n", totalCount));

        int count = 0;
        for (Map<String, Object> item : content) {
            if (count >= 5) {
                sb.append(String.format("\n... 等共 %d 个批次", totalCount));
                break;
            }
            String batchNumber = getString(item, "batchNumber");
            if (batchNumber == null) batchNumber = getString(item, "batchNo");
            String statusStr = translateStatus(getString(item, "status"));
            String productName = getString(item, "productName");
            if (productName == null) productName = getString(item, "materialName");
            BigDecimal qty = getBigDecimal(item, "currentQuantity");
            if (qty == null) qty = getBigDecimal(item, "quantity");

            sb.append(String.format("%d. %s", ++count, batchNumber != null ? batchNumber : "无批号"));
            if (productName != null) sb.append(" | ").append(productName);
            if (qty != null) sb.append(" | ").append(QUANTITY_FORMATTER.format(qty));
            sb.append(" | ").append(statusStr).append("\n");
        }

        return sb.toString();
    }

    private String formatProcessingBatchDetail(Map<String, Object> data) {
        String batchNumber = getString(data, "batchNumber");
        String statusStr = translateStatus(getString(data, "status"));
        String productName = getString(data, "productName");
        BigDecimal qty = getBigDecimal(data, "currentQuantity");

        StringBuilder sb = new StringBuilder("批次详情：\n");
        if (batchNumber != null) sb.append("• 批次号: ").append(batchNumber).append("\n");
        if (productName != null) sb.append("• 产品: ").append(productName).append("\n");
        if (qty != null) sb.append("• 数量: ").append(QUANTITY_FORMATTER.format(qty)).append("\n");
        sb.append("• 状态: ").append(statusStr);
        return sb.toString();
    }

    private String formatExpiringAlert(Map<String, Object> data) {
        List<Map<String, Object>> batches = getList(data, "batches");
        if (batches == null) batches = getList(data, "content");
        if (batches == null || batches.isEmpty()) {
            return "未来7天内没有即将过期的原材料批次，库存保质期状况良好。";
        }

        StringBuilder sb = new StringBuilder(String.format("发现 %d 批即将过期原料：\n\n", batches.size()));
        int count = 0;
        for (Map<String, Object> batch : batches) {
            if (count >= 5) {
                sb.append("... 等共 ").append(batches.size()).append(" 批");
                break;
            }
            String name = getString(batch, "materialName");
            String expDate = getString(batch, "expirationDate");
            BigDecimal qty = getBigDecimal(batch, "quantity");
            sb.append(String.format("• %s：%s%s，过期日期 %s\n",
                    name != null ? name : "未知",
                    qty != null ? QUANTITY_FORMATTER.format(qty) : "?",
                    getString(batch, "unit") != null ? getString(batch, "unit") : "",
                    expDate != null ? expDate : "未知"));
            count++;
        }
        return sb.toString();
    }

    // ==================== 质量 格式化 ====================

    private String formatQualityResult(String intentCode, Object resultData) {
        Map<String, Object> data = asMap(resultData);
        if (data == null) return null;

        return switch (intentCode) {
            case "QUALITY_CHECK_QUERY" -> formatQualityCheckList(data);
            case "QUALITY_STATS" -> formatQualityStats(data);
            default -> formatGenericList(data);
        };
    }

    private String formatQualityCheckList(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();

        List<Map<String, Object>> content = getList(data, "content");
        Integer total = getInteger(data, "totalElements");
        String message = getString(data, "message");

        if (message != null) {
            sb.append(message).append("\n\n");
        } else if (content == null || content.isEmpty()) {
            return "当前暂无质检记录。可在质检管理模块创建新的质检任务。";
        } else {
            sb.append(String.format("查询到 %d 条质检记录：\n\n", total != null ? total : content.size()));
        }

        if (content != null) {
            int count = 0;
            for (Map<String, Object> item : content) {
                if (count >= 5) break;

                String result = translateQualityResult(getString(item, "result"));
                String grade = getString(item, "qualityGrade");
                Double passRate = getDouble(item, "passRate");
                LocalDate inspectionDate = getLocalDate(item, "inspectionDate");

                sb.append(String.format("%d. %s", ++count, result));
                if (grade != null) sb.append(String.format(" | 等级 %s", grade));
                if (passRate != null) sb.append(String.format(" | 合格率 %.1f%%", passRate));
                if (inspectionDate != null) sb.append(String.format(" | %s", inspectionDate.format(DATE_FORMATTER)));
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private String formatQualityStats(Map<String, Object> data) {
        Double avgPassRate = getDouble(data, "avgPassRate");
        Integer totalChecks = getInteger(data, "totalChecks");
        Integer passCount = getInteger(data, "passCount");
        Integer failCount = getInteger(data, "failCount");

        if (totalChecks == null && passCount == null && failCount == null && avgPassRate == null) {
            return "当前暂无质检统计数据。请先完成质检任务后查看统计。";
        }

        StringBuilder sb = new StringBuilder("质量统计：\n\n");
        if (totalChecks != null) sb.append(String.format("• 总检验次数：%d 次\n", totalChecks));
        if (passCount != null) sb.append(String.format("• 合格批次：%d 批\n", passCount));
        if (failCount != null) sb.append(String.format("• 不合格批次：%d 批\n", failCount));
        if (avgPassRate != null) sb.append(String.format("• 平均合格率：%.1f%%", avgPassRate));

        return sb.toString();
    }

    // ==================== 生产 格式化 ====================

    private String formatProductionResult(String intentCode, Object resultData) {
        Map<String, Object> data = asMap(resultData);
        if (data == null) return null;

        return switch (intentCode) {
            case "PRODUCTION_STATUS_QUERY" -> formatProductionStatus(data);
            case "PRODUCTION_PLAN_CREATE", "PRODUCTION_PLAN_CREATE_FULL" -> formatProductionPlanCreated(data);
            default -> formatGenericList(data);
        };
    }

    private String formatProductionPlanCreated(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder("生产计划已创建\n\n");
        String planNumber = getString(data, "planNumber");
        String productName = getString(data, "productName");
        Object quantity = data.get("plannedQuantity");
        if (quantity == null) quantity = data.get("quantity");
        String status = getString(data, "status");
        Object plannedDate = data.get("plannedDate");
        if (plannedDate == null) plannedDate = data.get("startDate");

        if (planNumber != null) sb.append("计划编号：").append(planNumber).append("\n");
        if (productName != null) sb.append("产品：").append(productName).append("\n");
        if (quantity != null) sb.append("计划产量：").append(quantity).append("\n");
        if (plannedDate != null) sb.append("计划日期：").append(plannedDate).append("\n");
        if (status != null) sb.append("状态：").append(translateStatus(status)).append("\n");

        sb.append("\n可以在「生产管理」中查看和管理此计划。");
        return sb.toString();
    }

    private String formatProductionStatus(Map<String, Object> data) {
        List<Map<String, Object>> content = getList(data, "content");
        String message = getString(data, "message");

        if (message != null && !message.isEmpty()) return message;

        if (content == null || content.isEmpty()) {
            return "今日暂无生产记录。可通过「创建批次」开始新的生产任务。";
        }

        Integer total = getInteger(data, "totalElements");
        StringBuilder sb = new StringBuilder(String.format("今日生产状态（共 %d 条记录）：\n\n", total != null ? total : content.size()));
        int count = 0;
        for (Map<String, Object> item : content) {
            if (count >= 5) break;
            String name = getString(item, "productName");
            String status = getString(item, "status");
            sb.append(String.format("%d. %s | %s\n", ++count,
                    name != null ? name : "未知产品",
                    status != null ? status : "未知状态"));
        }
        return sb.toString();
    }

    // ==================== 报表 格式化 ====================

    private String formatReportResult(String intentCode, Object resultData) {
        Map<String, Object> topLevel = asMap(resultData);
        if (topLevel == null) return null;

        // Report intents nest metrics under a "data" sub-key:
        // resultData = { reportType, period, data: { totalOutput, ... }, startDate, endDate }
        Map<String, Object> data = asMap(topLevel.get("data"));
        if (data == null) data = topLevel; // Fallback to top-level if no nested "data"

        String period = getString(topLevel, "period");
        String startDate = getString(topLevel, "startDate");
        String endDate = getString(topLevel, "endDate");
        String periodLabel = buildPeriodLabel(period, startDate, endDate);

        return switch (intentCode) {
            case "REPORT_INVENTORY" -> formatInventoryReport(data, periodLabel);
            case "REPORT_PRODUCTION" -> formatProductionReport(data, periodLabel);
            case "REPORT_FINANCE" -> formatFinanceReport(data, periodLabel);
            case "REPORT_QUALITY" -> formatQualityReport(data, periodLabel);
            case "REPORT_EFFICIENCY" -> formatEfficiencyReport(data, periodLabel);
            case "REPORT_KPI" -> formatKPIReport(data);
            case "REPORT_DASHBOARD_OVERVIEW" -> formatDashboardReport(data);
            case "REPORT_ANOMALY" -> formatAnomalyReport(data);
            case "REPORT_TRENDS" -> formatTrendsReport(data);
            case "COST_QUERY", "COST_TREND_ANALYSIS" -> formatCostQueryResult(topLevel);
            case "SCHEDULING_LIST" -> formatSchedulingListResult(topLevel);
            case "PRODUCTION_STATUS_QUERY" -> formatProductionStatus(data);
            case "INCOME_STATEMENT_QUERY" -> formatIncomeStatement(topLevel);
            case "BALANCE_SHEET_QUERY" -> formatBalanceSheet(topLevel);
            case "CASHFLOW_STATEMENT_QUERY" -> formatCashflowStatement(topLevel);
            default -> formatReportGeneric(data);
        };
    }

    // Sprint 12: finance 3-statement formatters — emit ≥80-char business-content message
    // instead of generic key/value dump (was "startYear: 2026 | startMonth: 5 ...").
    private String formatIncomeStatement(Map<String, Object> d) {
        if (d == null) return null;
        Integer startYear = getInteger(d, "startYear");
        Integer startMonth = getInteger(d, "startMonth");
        Integer endYear = getInteger(d, "endYear");
        Integer endMonth = getInteger(d, "endMonth");
        BigDecimal revenue = getBigDecimal(d, "totalRevenue");
        BigDecimal cost = getBigDecimal(d, "totalCost");
        BigDecimal grossProfit = getBigDecimal(d, "grossProfit");
        BigDecimal operatingProfit = getBigDecimal(d, "operatingProfit");
        BigDecimal netProfit = getBigDecimal(d, "netProfit");
        BigDecimal grossMargin = getBigDecimal(d, "grossMarginPercent");
        String period = (startYear != null && startMonth != null)
                ? String.format("%d-%02d 至 %d-%02d", startYear, startMonth,
                        endYear != null ? endYear : startYear,
                        endMonth != null ? endMonth : startMonth)
                : "本期";
        StringBuilder sb = new StringBuilder();
        sb.append(period).append(" 利润表: ");
        if (revenue != null) sb.append("营业收入 ¥").append(AMOUNT_FORMATTER.format(revenue)).append(" / ");
        if (cost != null) sb.append("营业成本 ¥").append(AMOUNT_FORMATTER.format(cost)).append(" / ");
        if (grossProfit != null) sb.append("毛利 ¥").append(AMOUNT_FORMATTER.format(grossProfit)).append(" / ");
        if (operatingProfit != null) sb.append("营业利润 ¥").append(AMOUNT_FORMATTER.format(operatingProfit)).append(" / ");
        if (netProfit != null) sb.append("净利润 ¥").append(AMOUNT_FORMATTER.format(netProfit));
        if (grossMargin != null) sb.append(" (毛利率 ").append(grossMargin).append("%)");
        if (revenue == null && cost == null && grossProfit == null) {
            sb.append("本月暂无利润表数据 (营业收入 / 营业成本 / 毛利 / 营业利润 / 净利润均为空)。诊断建议: 1. 检查本期记账凭证是否已全部审核入账; 2. 确认会计期间状态是否已结账; 3. 查看月度经营月报或联系财务主管复核三大报表生成情况。");
        }
        return sb.toString();
    }

    private String formatBalanceSheet(Map<String, Object> d) {
        if (d == null) return null;
        Integer year = getInteger(d, "year");
        Integer month = getInteger(d, "month");
        BigDecimal totalAssets = getBigDecimal(d, "totalAssets");
        BigDecimal totalLiabilities = getBigDecimal(d, "totalLiabilities");
        BigDecimal totalEquity = getBigDecimal(d, "totalEquity");
        BigDecimal currentAssets = getBigDecimal(d, "currentAssets");
        BigDecimal accountsPayable = getBigDecimal(d, "accountsPayable");
        String period = (year != null && month != null)
                ? String.format("%d-%02d", year, month) : "本期";
        StringBuilder sb = new StringBuilder();
        sb.append(period).append(" 资产负债表: ");
        if (totalAssets != null) sb.append("总资产 ¥").append(AMOUNT_FORMATTER.format(totalAssets)).append(" / ");
        if (totalLiabilities != null) sb.append("总负债 ¥").append(AMOUNT_FORMATTER.format(totalLiabilities)).append(" / ");
        if (totalEquity != null) sb.append("所有者权益 ¥").append(AMOUNT_FORMATTER.format(totalEquity));
        if (currentAssets != null) sb.append(" / 流动资产 ¥").append(AMOUNT_FORMATTER.format(currentAssets));
        if (accountsPayable != null) sb.append(" / 应付账款 ¥").append(AMOUNT_FORMATTER.format(accountsPayable));
        if (totalAssets == null && totalLiabilities == null) {
            sb.append("本月暂无资产负债表数据 (总资产 / 总负债 / 所有者权益均为空)。诊断建议: 1. 确认本期期初余额已录入会计科目; 2. 检查记账凭证是否已全部审核入账; 3. 联系财务主管复核账目并生成报表。");
        }
        return sb.toString();
    }

    private String formatCashflowStatement(Map<String, Object> d) {
        if (d == null) return null;
        Integer startYear = getInteger(d, "startYear");
        Integer startMonth = getInteger(d, "startMonth");
        BigDecimal operatingInflow = getBigDecimal(d, "operatingInflow");
        BigDecimal operatingOutflow = getBigDecimal(d, "operatingOutflow");
        BigDecimal investingInflow = getBigDecimal(d, "investingInflow");
        BigDecimal financingInflow = getBigDecimal(d, "financingInflow");
        BigDecimal netCashFlow = getBigDecimal(d, "netCashFlow");
        String period = (startYear != null && startMonth != null)
                ? String.format("%d-%02d", startYear, startMonth) : "本期";
        StringBuilder sb = new StringBuilder();
        sb.append(period).append(" 现金流量表: ");
        if (operatingInflow != null) sb.append("经营流入 ¥").append(AMOUNT_FORMATTER.format(operatingInflow)).append(" / ");
        if (operatingOutflow != null) sb.append("经营流出 ¥").append(AMOUNT_FORMATTER.format(operatingOutflow)).append(" / ");
        if (investingInflow != null) sb.append("投资流入 ¥").append(AMOUNT_FORMATTER.format(investingInflow)).append(" / ");
        if (financingInflow != null) sb.append("筹资流入 ¥").append(AMOUNT_FORMATTER.format(financingInflow)).append(" / ");
        if (netCashFlow != null) sb.append("现金净增加 ¥").append(AMOUNT_FORMATTER.format(netCashFlow));
        if (operatingInflow == null && operatingOutflow == null) {
            sb.append("本月暂无现金流量数据 (经营 / 投资 / 筹资活动现金流均为空)。诊断建议: 1. 确认本期收付款凭证已全部录入并审核; 2. 检查银行流水勾兑及现金账核对情况; 3. 联系出纳或财务主管复核现金账并生成现金流量表。");
        }
        return sb.toString();
    }

    private String buildPeriodLabel(String period, String startDate, String endDate) {
        if (period == null) return "";
        String periodName = switch (period) {
            case "today" -> "今日";
            case "week" -> "本周";
            case "month" -> "本月";
            case "quarter" -> "本季度";
            case "year" -> "本年";
            default -> period;
        };
        if (startDate != null && endDate != null) {
            String start = startDate.length() >= 10 ? startDate.substring(5) : startDate;
            String end = endDate.length() >= 10 ? endDate.substring(5) : endDate;
            return periodName + " (" + start + " ~ " + end + ")";
        }
        return periodName;
    }

    private String formatInventoryReport(Map<String, Object> data, String periodLabel) {
        StringBuilder sb = new StringBuilder("库存报告");
        if (!periodLabel.isEmpty()) sb.append(" (").append(periodLabel).append(")");
        sb.append("\n");
        boolean hasMetrics = false;

        BigDecimal totalValue = getBigDecimal(data, "totalValue");
        if (totalValue != null) {
            sb.append("库存总价值: ¥").append(AMOUNT_FORMATTER.format(totalValue));
            hasMetrics = true;
        }

        Object typeDist = data.get("typeDistribution");
        if (typeDist instanceof Map) {
            Map<?, ?> distMap = (Map<?, ?>) typeDist;
            if (hasMetrics) sb.append(" | ");
            sb.append("材料种类: ").append(distMap.size()).append("种");
            hasMetrics = true;
        }

        Object lowStockObj = data.get("lowStockItems");
        if (lowStockObj instanceof List) {
            List<?> lowStockList = (List<?>) lowStockObj;
            if (!lowStockList.isEmpty()) {
                sb.append("\n低库存预警: ").append(lowStockList.size()).append("项");
                hasMetrics = true;
            }
        } else {
            Integer lowStock = getInteger(data, "lowStockItems");
            if (lowStock != null && lowStock > 0) {
                sb.append("\n低库存预警: ").append(lowStock).append("项");
                hasMetrics = true;
            }
        }

        Integer expiring = getInteger(data, "expiringBatches");
        Integer expired = getInteger(data, "expiredBatches");
        if ((expiring != null && expiring > 0) || (expired != null && expired > 0)) {
            sb.append("\n");
            if (expiring != null && expiring > 0) sb.append("即将过期: ").append(expiring).append("批");
            if (expired != null && expired > 0) {
                if (expiring != null && expiring > 0) sb.append(" | ");
                sb.append("已过期: ").append(expired).append("批");
            }
            hasMetrics = true;
        }

        Integer totalBatches = getInteger(data, "totalBatches");
        if (totalBatches != null) {
            sb.append("\n原料批次总数: ").append(totalBatches);
            hasMetrics = true;
        }

        if (!hasMetrics) return null;
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String formatProductionReport(Map<String, Object> data, String periodLabel) {
        StringBuilder sb = new StringBuilder("生产报表");
        if (!periodLabel.isEmpty()) sb.append(" (").append(periodLabel).append(")");
        sb.append("\n");
        boolean hasMetrics = false;

        BigDecimal totalOutput = getBigDecimal(data, "totalOutput");
        if (totalOutput != null) {
            sb.append("总产量: ").append(QUANTITY_FORMATTER.format(totalOutput)).append(" kg");
            hasMetrics = true;
        }

        Object dailyObj = data.get("dailyOutput");
        if (dailyObj instanceof List) {
            List<Map<String, Object>> daily = (List<Map<String, Object>>) dailyObj;
            if (!daily.isEmpty()) {
                double maxOutput = 0, minOutput = Double.MAX_VALUE;
                String maxDate = null;
                for (Map<String, Object> d : daily) {
                    Double output = getDouble(d, "output");
                    if (output != null) {
                        if (output > maxOutput) { maxOutput = output; maxDate = getString(d, "date"); }
                        if (output < minOutput) minOutput = output;
                    }
                }
                if (maxOutput > 0 && maxDate != null) {
                    sb.append("\n最高产量: ").append(QUANTITY_FORMATTER.format(maxOutput))
                      .append(" kg (").append(maxDate.length() >= 10 ? maxDate.substring(5) : maxDate).append(")");
                }
                hasMetrics = true;
            }
        }

        Integer totalBatches = getInteger(data, "totalBatches");
        Integer completedBatches = getInteger(data, "completedBatches");
        if (totalBatches != null) {
            sb.append("\n批次: ").append(totalBatches);
            if (completedBatches != null) sb.append(" | 已完成: ").append(completedBatches);
            hasMetrics = true;
        }

        Double avgEfficiency = getDouble(data, "averageEfficiency");
        if (avgEfficiency == null) avgEfficiency = getDouble(data, "avgEfficiency");
        if (avgEfficiency != null) {
            sb.append("\n平均效率: ").append(String.format("%.1f%%", avgEfficiency));
            hasMetrics = true;
        }

        if (!hasMetrics) {
            sb.append("暂无生产数据");
            return sb.toString();
        }
        return sb.toString();
    }

    private String formatFinanceReport(Map<String, Object> data, String periodLabel) {
        StringBuilder sb = new StringBuilder("财务报告");
        if (!periodLabel.isEmpty()) sb.append(" (").append(periodLabel).append(")");
        sb.append("\n");
        boolean hasMetrics = false;

        BigDecimal totalRevenue = getBigDecimal(data, "totalRevenue");
        BigDecimal totalProfit = getBigDecimal(data, "totalProfit");
        Double profitMargin = getDouble(data, "profitMargin");
        BigDecimal materialCost = getBigDecimal(data, "materialCost");
        BigDecimal laborCost = getBigDecimal(data, "laborCost");
        BigDecimal equipmentCost = getBigDecimal(data, "equipmentCost");
        BigDecimal otherCost = getBigDecimal(data, "otherCost");

        if (totalRevenue != null) {
            sb.append("总收入: ¥").append(AMOUNT_FORMATTER.format(totalRevenue));
            hasMetrics = true;
        }
        if (totalProfit != null) {
            if (hasMetrics) sb.append(" | ");
            sb.append("净利润: ¥").append(AMOUNT_FORMATTER.format(totalProfit));
            hasMetrics = true;
        }
        if (profitMargin != null) {
            sb.append(" | 利润率: ").append(String.format("%.2f%%", profitMargin));
            hasMetrics = true;
        }
        if (materialCost != null || laborCost != null || equipmentCost != null) {
            sb.append("\n成本构成:");
            if (materialCost != null) sb.append(" 材料¥").append(AMOUNT_FORMATTER.format(materialCost));
            if (laborCost != null) sb.append(" | 人工¥").append(AMOUNT_FORMATTER.format(laborCost));
            if (equipmentCost != null) sb.append(" | 设备¥").append(AMOUNT_FORMATTER.format(equipmentCost));
            if (otherCost != null) sb.append(" | 其他¥").append(AMOUNT_FORMATTER.format(otherCost));
            hasMetrics = true;
        }

        if (!hasMetrics) return null;
        return sb.toString();
    }

    private String formatQualityReport(Map<String, Object> data, String periodLabel) {
        StringBuilder sb = new StringBuilder("质检报告");
        if (!periodLabel.isEmpty()) sb.append(" (").append(periodLabel).append(")");
        sb.append("\n");
        boolean hasMetrics = false;

        Double totalProduction = getDouble(data, "totalProduction");
        Double qualifiedProduction = getDouble(data, "qualifiedProduction");
        Double qualityRate = getDouble(data, "qualityRate");
        Double firstPassRate = getDouble(data, "firstPassRate");
        Double reworkRate = getDouble(data, "reworkRate");

        if (totalProduction != null) {
            sb.append("总检测量: ").append(QUANTITY_FORMATTER.format(totalProduction)).append(" kg");
            hasMetrics = true;
        }
        if (qualifiedProduction != null) {
            sb.append(" | 合格: ").append(QUANTITY_FORMATTER.format(qualifiedProduction)).append(" kg");
            hasMetrics = true;
        }
        if (firstPassRate != null) {
            sb.append("\n一次通过率: ").append(String.format("%.1f%%", firstPassRate));
            hasMetrics = true;
        }
        if (reworkRate != null) {
            sb.append(" | 返工率: ").append(String.format("%.1f%%", reworkRate));
            hasMetrics = true;
        }
        if (qualityRate != null) {
            sb.append(" | 综合合格率: ").append(String.format("%.1f%%", qualityRate));
            hasMetrics = true;
        }

        if (!hasMetrics) return null;
        return sb.toString();
    }

    private String formatEfficiencyReport(Map<String, Object> data, String periodLabel) {
        StringBuilder sb = new StringBuilder("效率分析报告");
        if (!periodLabel.isEmpty()) sb.append(" (").append(periodLabel).append(")");
        sb.append("\n");
        boolean hasMetrics = false;

        BigDecimal totalOutput = getBigDecimal(data, "totalOutput");
        Double oee = getDouble(data, "equipmentOEE");
        Double avgEff = getDouble(data, "averageEfficiency");

        if (totalOutput != null) {
            sb.append("总产量: ").append(QUANTITY_FORMATTER.format(totalOutput)).append(" kg");
            hasMetrics = true;
        }
        if (oee != null) {
            if (hasMetrics) sb.append(" | ");
            sb.append("设备综合效率(OEE): ").append(String.format("%.1f%%", oee));
            hasMetrics = true;
        }
        if (avgEff != null) {
            if (hasMetrics) sb.append(" | ");
            sb.append("平均效率: ").append(String.format("%.1f%%", avgEff));
            hasMetrics = true;
        }

        if (!hasMetrics) return null;
        return sb.toString();
    }

    private String formatKPIReport(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder("KPI指标报告\n");
        boolean hasMetrics = false;

        Double prodEff = getDouble(data, "productionEfficiency");
        Double qualityRate = getDouble(data, "qualityRate");
        Double deliveryOnTime = getDouble(data, "deliveryOnTime");
        Double oee = getDouble(data, "equipmentOEE");
        Double maintenance = getDouble(data, "maintenanceCompliance");
        Double laborProd = getDouble(data, "laborProductivity");

        if (prodEff != null) { sb.append("生产效率: ").append(String.format("%.1f%%", prodEff)); hasMetrics = true; }
        if (qualityRate != null) { sb.append(" | 质量合格率: ").append(String.format("%.1f%%", qualityRate)); hasMetrics = true; }
        if (deliveryOnTime != null) { sb.append(" | 准时交付: ").append(String.format("%.1f%%", deliveryOnTime)); hasMetrics = true; }
        if (oee != null) { sb.append("\n设备OEE: ").append(String.format("%.1f%%", oee)); hasMetrics = true; }
        if (maintenance != null) { sb.append(" | 维护达标率: ").append(String.format("%.1f%%", maintenance)); hasMetrics = true; }
        if (laborProd != null) { sb.append("\n人员生产力: ").append(String.format("%.1f%%", laborProd)); hasMetrics = true; }

        if (!hasMetrics) return null;
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String formatDashboardReport(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder("仪表盘总览\n");
        boolean hasMetrics = false;

        Object summaryObj = data.get("summary");
        if (summaryObj instanceof Map) {
            Map<String, Object> summary = (Map<String, Object>) summaryObj;
            Integer totalBatches = getInteger(summary, "totalBatches");
            Integer activeBatches = getInteger(summary, "activeBatches");
            if (totalBatches != null) {
                sb.append("生产批次: ").append(totalBatches);
                if (activeBatches != null) sb.append(" | 进行中: ").append(activeBatches);
                sb.append("\n");
                hasMetrics = true;
            }
        }
        Object todayObj = data.get("todayStats");
        if (todayObj instanceof Map) {
            Map<String, Object> today = (Map<String, Object>) todayObj;
            BigDecimal output = getBigDecimal(today, "todayOutputKg");
            if (output != null) {
                sb.append("今日产量: ").append(QUANTITY_FORMATTER.format(output)).append(" kg");
                hasMetrics = true;
            }
        }

        if (!hasMetrics) return null;
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String formatAnomalyReport(Map<String, Object> data) {
        Integer totalAnomalies = getInteger(data, "totalAnomalies");
        if (totalAnomalies == null) return null;

        StringBuilder sb = new StringBuilder("异常检测报告\n");
        if (totalAnomalies == 0) {
            sb.append("未发现异常，生产运行正常");
        } else {
            sb.append("发现 ").append(totalAnomalies).append(" 项异常");
            Object anomaliesObj = data.get("anomalies");
            if (anomaliesObj instanceof List) {
                sb.append(":\n");
                List<Map<String, Object>> anomalies = (List<Map<String, Object>>) anomaliesObj;
                for (Map<String, Object> anomaly : anomalies) {
                    String level = getString(anomaly, "level");
                    String title = getString(anomaly, "title");
                    String icon = "CRITICAL".equals(level) ? "[严重]" : "WARNING".equals(level) ? "[警告]" : "[提醒]";
                    sb.append(icon).append(" ").append(title != null ? title : "未知异常").append("\n");
                }
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String formatTrendsReport(Map<String, Object> data) {
        Object trendDataObj = data.get("trendData");
        if (trendDataObj instanceof List) {
            List<Map<String, Object>> trendData = (List<Map<String, Object>>) trendDataObj;
            if (!trendData.isEmpty()) {
                StringBuilder sb = new StringBuilder("趋势分析\n");
                sb.append("数据点: ").append(trendData.size()).append("个");
                BigDecimal first = getBigDecimal(trendData.get(0), "value");
                BigDecimal last = getBigDecimal(trendData.get(trendData.size() - 1), "value");
                if (first != null && last != null) {
                    sb.append(" | 起始: ").append(QUANTITY_FORMATTER.format(first));
                    sb.append(" | 最新: ").append(QUANTITY_FORMATTER.format(last));
                }
                return sb.toString();
            }
        }
        // Fallback: summarize whatever data keys are present
        return formatDataSummary("趋势分析报告", data);
    }

    private String formatCostQueryResult(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder("成本分析报告");
        String period = getString(data, "period");
        if (period != null) sb.append(" (").append(period).append(")");
        sb.append("\n");

        String dimension = getString(data, "dimension");
        if (dimension != null) {
            String dimLabel = switch (dimension) {
                case "overall" -> "综合分析";
                case "trend" -> "趋势分析";
                case "material" -> "材料成本";
                case "labor" -> "人工成本";
                case "equipment" -> "设备成本";
                default -> dimension;
            };
            sb.append("分析维度: ").append(dimLabel).append("\n");
        }

        Object analysis = data.get("analysis");
        if (analysis != null) {
            String text = String.valueOf(analysis).trim();
            String[] lines = text.split("\n");
            int addedLines = 0;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("---")) continue;
                trimmed = trimmed.replaceAll("\\*\\*", "");
                if (trimmed.length() > 2) {
                    sb.append(trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed).append("\n");
                    if (++addedLines >= 3) break;
                }
            }
        }
        return sb.length() > 20 ? sb.toString() : null;
    }

    private String formatSchedulingListResult(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder("排班计划");
        String startDate = getString(data, "startDate");
        String endDate = getString(data, "endDate");
        if (startDate != null && endDate != null) {
            sb.append(" (").append(startDate).append(" ~ ").append(endDate).append(")");
        }
        sb.append("\n");

        Integer total = getInteger(data, "totalElements");
        if (total != null) sb.append("共 ").append(total).append(" 个排班计划\n");

        List<Map<String, Object>> content = getList(data, "content");
        if (content != null && !content.isEmpty()) {
            int shown = 0;
            for (Map<String, Object> plan : content) {
                if (shown >= 5) break;
                String name = getString(plan, "planName");
                String date = getString(plan, "planDate");
                String status = getString(plan, "status");
                sb.append(String.format("%d. %s | %s | %s\n", ++shown,
                        name != null ? name : "-",
                        date != null ? date : "-",
                        status != null ? status : "-"));
            }
            if (content.size() > 5) sb.append("... 还有 ").append(content.size() - 5).append(" 个");
        } else {
            sb.append("当前时间段暂无排班计划。可通过「安排排班」创建新的排班计划。");
        }
        return sb.toString();
    }

    /**
     * Generic report formatter: tries to extract any numeric metrics from the data map.
     * Returns null if no meaningful data found, allowing the handler's message to pass through.
     */
    private String formatReportGeneric(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        int metricCount = 0;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof Number && metricCount < 6) {
                double dval = ((Number) val).doubleValue();
                if (dval != 0.0) {
                    if (metricCount > 0) sb.append(" | ");
                    sb.append(entry.getKey()).append(": ").append(QUANTITY_FORMATTER.format(dval));
                    metricCount++;
                }
            }
        }
        if (metricCount > 0) {
            return sb.toString();
        }
        return null;
    }

    // ==================== HR 格式化 ====================

    private String formatHRResult(String intentCode, Object resultData) {
        Map<String, Object> data = asMap(resultData);
        if (data == null) return null;

        return switch (intentCode) {
            case "ATTENDANCE_TODAY", "ATTENDANCE_QUERY", "ATTENDANCE_STATS" -> formatAttendanceResult(data);
            case "WORKER_QUERY" -> formatWorkerList(data);
            default -> formatGenericList(data);
        };
    }

    private String formatAttendanceResult(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();

        // ATTENDANCE_STATS nests data under "metrics"; ATTENDANCE_TODAY uses top-level
        Map<String, Object> metrics = asMap(data.get("metrics"));
        Map<String, Object> src = metrics != null ? metrics : data;

        Integer total = getInteger(src, "totalWorkers");
        Integer present = getInteger(src, "presentCount");
        Integer absent = getInteger(src, "absentCount");
        Integer late = getInteger(src, "lateCount");
        Double attendanceRate = getDouble(src, "attendanceRate");
        Integer workingDays = getInteger(src, "workingDays");
        Integer earlyLeave = getInteger(src, "earlyLeaveCount");
        String message = getString(data, "message");
        String startDate = getString(data, "startDate");
        String endDate = getString(data, "endDate");

        if (startDate != null && endDate != null) {
            sb.append("考勤统计 (").append(startDate).append(" ~ ").append(endDate).append(")\n\n");
        } else if (message != null) {
            sb.append(message).append("\n\n");
        } else {
            sb.append("今日出勤情况：\n\n");
        }

        if (total != null) sb.append(String.format("• 应到人数：%d 人\n", total));
        if (present != null) sb.append(String.format("• 实到人数：%d 人\n", present));
        if (absent != null && absent > 0) sb.append(String.format("• 缺勤人数：%d 人\n", absent));
        if (late != null && late > 0) sb.append(String.format("• 迟到人数：%d 人\n", late));
        if (earlyLeave != null && earlyLeave > 0) sb.append(String.format("• 早退人数：%d 人\n", earlyLeave));
        if (workingDays != null) sb.append(String.format("• 工作日数：%d 天\n", workingDays));
        if (attendanceRate != null) sb.append(String.format("• 出勤率：%.1f%%", attendanceRate));

        return sb.toString();
    }

    private String formatWorkerList(Map<String, Object> data) {
        List<Map<String, Object>> content = getList(data, "content");
        if (content == null || content.isEmpty()) {
            return "暂无匹配的员工信息。请核实查询条件后重试。";
        }

        StringBuilder sb = new StringBuilder(String.format("查询到 %d 名员工：\n\n", content.size()));
        int count = 0;
        for (Map<String, Object> worker : content) {
            if (count >= 5) break;
            String name = getString(worker, "name");
            String position = getString(worker, "position");
            String department = getString(worker, "department");
            sb.append(String.format("%d. %s | %s | %s\n", ++count,
                    name != null ? name : "未知",
                    position != null ? position : "-",
                    department != null ? department : "-"));
        }
        return sb.toString();
    }

    // ==================== CRM 格式化 ====================

    private String formatCRMResult(String intentCode, Object resultData) {
        Map<String, Object> data = asMap(resultData);
        if (data == null) return null;

        return switch (intentCode) {
            case "CUSTOMER_LIST", "CUSTOMER_ACTIVE", "CUSTOMER_QUERY", "CUSTOMER_SEARCH" -> formatCustomerList(data);
            case "ORDER_LIST", "ORDER_STATS" -> formatOrderList(data);
            case "SUPPLIER_LIST" -> formatSupplierList(data);
            case "SUPPLIER_RANKING" -> formatSupplierRanking(data);
            case "CUSTOMER_STATS" -> formatCustomerStats(data);
            case "SALES_STATS", "SALES_RANKING", "PRODUCT_SALES_RANKING" -> formatSalesStats(data);
            default -> formatGenericList(data);
        };
    }

    private String formatCustomerList(Map<String, Object> data) {
        List<Map<String, Object>> content = getList(data, "content");
        // CRM handler uses "customers" key instead of "content"
        if (content == null) content = getList(data, "customers");
        Integer total = getInteger(data, "totalElements");
        if (total == null) total = getInteger(data, "total");

        if (content == null || content.isEmpty()) {
            if (total != null && total > 0) {
                return String.format("客户列表查询完成，共 %d 位客户（详情请在客户管理页面查看）", total);
            }
            return "客户列表查询完成，暂无客户数据。请先在客户管理中添加客户信息。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("客户列表（共 %d 位客户）\n\n", total != null ? total : content.size()));

        int count = 0;
        for (Map<String, Object> customer : content) {
            if (count >= 5) break;
            String name = getString(customer, "name");
            String contact = getString(customer, "contactPerson");
            String phone = getString(customer, "phone");
            sb.append(String.format("%d. %s", ++count, name != null ? name : "未知客户"));
            if (contact != null) sb.append(String.format(" | 联系人：%s", contact));
            if (phone != null) sb.append(String.format(" | %s", phone));
            sb.append("\n");
        }
        return sb.toString();
    }

    private String formatOrderList(Map<String, Object> data) {
        List<Map<String, Object>> content = getList(data, "content");
        if (content == null) content = getList(data, "orders");
        Integer total = getInteger(data, "totalElements");
        if (total == null) total = getInteger(data, "total");

        if (content == null || content.isEmpty()) {
            return "当前暂无订单数据。可通过「创建订单」新建销售订单。";
        }

        int totalCount = total != null ? total : content.size();
        StringBuilder sb = new StringBuilder(String.format("订单列表（共 %d 个订单）\n\n", totalCount));
        int count = 0;
        for (Map<String, Object> order : content) {
            if (count >= 5) {
                sb.append(String.format("\n... 等共 %d 个订单", totalCount));
                break;
            }
            String orderNo = getString(order, "orderNumber");
            if (orderNo == null) orderNo = getString(order, "orderNo");
            String statusStr = translateStatus(getString(order, "status"));
            String customerName = getString(order, "customerName");
            BigDecimal amount = getBigDecimal(order, "totalAmount");

            sb.append(String.format("%d. %s", ++count, orderNo != null ? orderNo : "无订单号"));
            if (customerName != null) sb.append(" | ").append(customerName);
            if (amount != null) sb.append(" | ¥").append(AMOUNT_FORMATTER.format(amount));
            sb.append(" | ").append(statusStr).append("\n");
        }
        return sb.toString();
    }

    private String formatSupplierList(Map<String, Object> data) {
        List<Map<String, Object>> content = getList(data, "content");
        if (content == null) content = getList(data, "suppliers");
        Integer total = getInteger(data, "totalElements");
        if (total == null) total = getInteger(data, "total");

        if (content == null || content.isEmpty()) {
            return "供应商列表查询完成，暂无供应商数据。请先在供应商管理中添加供应商信息。";
        }

        int totalCount = total != null ? total : content.size();
        StringBuilder sb = new StringBuilder(String.format("供应商列表（共 %d 个供应商）\n\n", totalCount));
        int count = 0;
        for (Map<String, Object> supplier : content) {
            if (count >= 5) break;
            String name = getString(supplier, "name");
            if (name == null) name = getString(supplier, "supplierName");
            String contact = getString(supplier, "contactPerson");
            String level = getString(supplier, "level");

            sb.append(String.format("%d. %s", ++count, name != null ? name : "未知"));
            if (level != null) sb.append(" [").append(level).append("]");
            if (contact != null) sb.append(" | 联系人：").append(contact);
            sb.append("\n");
        }
        return sb.toString();
    }

    private String formatSupplierRanking(Map<String, Object> data) {
        List<Map<String, Object>> ranking = getList(data, "ranking");
        if (ranking == null || ranking.isEmpty()) {
            // Sprint 12: ≥80-char empty-state with diagnostic suggestions.
            return "供应商排名查询完成 — 当前没有可排名的供应商数据。诊断建议: 1. 检查本月是否已录入供应商订单信息;"
                    + " 2. 确认供应商基础资料是否齐全; 3. 联系采购部门复核供应商档案完整性。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("供应商排名 (TOP %d) — 本期评分排序: ", ranking.size()));
        int idx = 0;
        for (Map<String, Object> item : ranking) {
            if (idx >= 10) break;
            // Sprint 12: SupplierRankingTool nests supplier info under "supplier" key, not flat.
            String name = getString(item, "name");
            if (name == null) {
                Map<String, Object> sup = asMap(item.get("supplier"));
                if (sup != null) {
                    name = getString(sup, "name");
                    if (name == null) name = getString(sup, "supplierName");
                    if (name == null) name = getString(sup, "companyName");
                }
            }
            Integer totalOrders = getInteger(item, "totalOrders");
            BigDecimal totalAmount = getBigDecimal(item, "totalAmount");
            Integer rating = getInteger(item, "rating");
            if (rating == null) {
                Map<String, Object> sup = asMap(item.get("supplier"));
                if (sup != null) rating = getInteger(sup, "rating");
            }

            if (idx > 0) sb.append(" / ");
            sb.append(String.format("%d. %s", ++idx, name != null ? name : "未命名供应商"));
            if (totalAmount != null) sb.append(" 金额¥").append(AMOUNT_FORMATTER.format(totalAmount));
            if (totalOrders != null) sb.append(" 订单").append(totalOrders).append("单");
            if (rating != null) sb.append(" 评分").append(rating);
        }
        if (idx > 0) {
            sb.append(". 建议: 优先与高评分供应商续签长期合作, 跟进低评分供应商改进表现。");
        }
        return sb.toString();
    }

    private String formatCustomerStats(Map<String, Object> data) {
        Integer totalCustomers = getInteger(data, "totalCustomers");
        Integer activeCustomers = getInteger(data, "activeCustomers");
        Double totalSales = getDouble(data, "totalSales");
        String message = getString(data, "message");

        if (message != null && !message.isEmpty()) return message;

        if (totalCustomers == null && activeCustomers == null && totalSales == null) {
            return "当前暂无客户统计数据。请先在客户管理中录入客户信息。";
        }

        StringBuilder sb = new StringBuilder("客户统计：\n\n");
        if (totalCustomers != null) sb.append(String.format("• 客户总数：%d 位\n", totalCustomers));
        if (activeCustomers != null) sb.append(String.format("• 活跃客户：%d 位\n", activeCustomers));
        if (totalSales != null) sb.append(String.format("• 累计销售额：¥%s\n", AMOUNT_FORMATTER.format(totalSales)));
        return sb.toString();
    }

    private String formatSalesStats(Map<String, Object> data) {
        Double totalAmount = getDouble(data, "totalAmount");
        Integer orderCount = getInteger(data, "orderCount");
        String message = getString(data, "message");

        if (message != null && !message.isEmpty()) return message;

        if (totalAmount == null && orderCount == null) {
            List<Map<String, Object>> content = getList(data, "content");
            if (content == null || content.isEmpty()) {
                return "当前暂无销售统计数据。可在销售管理模块录入订单后查看统计分析。";
            }
            return formatGenericList(data);
        }

        StringBuilder sb = new StringBuilder("销售统计：\n\n");
        if (totalAmount != null) sb.append(String.format("• 销售总额：¥%s\n", AMOUNT_FORMATTER.format(totalAmount)));
        if (orderCount != null) sb.append(String.format("• 订单数量：%d 单\n", orderCount));
        return sb.toString();
    }

    // ==================== 告警 格式化 ====================

    @SuppressWarnings("unchecked")
    private String formatAlertResult(String intentCode, Object resultData) {
        Map<String, Object> data = asMap(resultData);
        if (data == null) return null;

        return switch (intentCode) {
            case "ALERT_STATS" -> formatAlertStats(data);
            default -> formatAlertList(data);
        };
    }

    private String formatAlertStats(Map<String, Object> data) {
        Map<String, Object> stats = asMap(data.get("statistics"));
        if (stats != null) data = stats;

        Integer total = getInteger(data, "total");
        if (total == null) return null;

        Integer critical = getInteger(data, "critical");
        Integer warning = getInteger(data, "warning");
        Integer resolved = getInteger(data, "resolved");
        int pending = total - (resolved != null ? resolved : 0);

        StringBuilder sb = new StringBuilder("告警统计\n");
        sb.append("总计: ").append(total);
        if (resolved != null) sb.append(" | 已处理: ").append(resolved);
        sb.append(" | 待处理: ").append(pending);

        if (critical != null && critical > 0) {
            sb.append("\n紧急告警: ").append(critical).append("条");
        }
        if (warning != null && warning > 0) {
            sb.append(" | 警告: ").append(warning).append("条");
        }

        if (total > 0 && resolved != null) {
            double resolveRate = (double) resolved / total * 100;
            sb.append("\n处理率: ").append(String.format("%.0f%%", resolveRate));
        }

        return sb.toString();
    }

    private String formatAlertList(Map<String, Object> data) {
        List<Map<String, Object>> alerts = getList(data, "content");
        if (alerts == null) alerts = getList(data, "alerts");
        if (alerts == null) alerts = getList(data, "activeAlerts");

        Integer total = getInteger(data, "total");
        Integer totalActive = getInteger(data, "totalActive");

        if (alerts == null || alerts.isEmpty()) {
            if (total != null && total > 0) {
                return String.format("共 %d 条告警记录（详情请在告警页面查看）", total);
            }
            return "告警检查完成：当前没有未处理的告警，所有设备运行正常。";
        }

        StringBuilder sb = new StringBuilder(String.format("发现 %d 条告警：\n\n", alerts.size()));
        int count = 0;
        for (Map<String, Object> alert : alerts) {
            if (count >= 5) break;
            String type = getString(alert, "alertType");
            String message = getString(alert, "message");
            String level = getString(alert, "level");
            sb.append(String.format("%d. [%s] %s", ++count,
                    level != null ? level : "INFO",
                    message != null ? message : type));
            sb.append("\n");
        }
        return sb.toString();
    }

    // ==================== 设备 格式化 ====================

    private String formatEquipmentResult(String intentCode, Object resultData) {
        Map<String, Object> data = asMap(resultData);
        if (data == null) return null;

        return switch (intentCode) {
            case "EQUIPMENT_STATUS_QUERY", "EQUIPMENT_STATS" -> formatEquipmentStats(data);
            case "EQUIPMENT_LIST" -> formatEquipmentList(data);
            case "EQUIPMENT_ALERT_LIST", "EQUIPMENT_ALERT_STATS" -> formatAlertList(data);
            default -> formatEquipmentStats(data);
        };
    }

    private String formatEquipmentStats(Map<String, Object> data) {
        // Handler nests data under "statistics" and "healthAssessment" sub-keys
        Map<String, Object> stats = asMap(data.get("statistics"));
        Map<String, Object> health = asMap(data.get("healthAssessment"));
        Map<String, Object> statusDist = stats != null ? asMap(stats.get("statusDistribution")) : null;

        Integer totalCount = getInteger(data, "totalCount");
        if (totalCount == null && stats != null) totalCount = getInteger(stats, "totalEquipment");
        if (totalCount == null && health != null) totalCount = getInteger(health, "totalEquipment");

        Integer runningCount = getInteger(data, "runningCount");
        if (runningCount == null && statusDist != null) runningCount = getInteger(statusDist, "running");

        Integer idleCount = statusDist != null ? getInteger(statusDist, "idle") : null;

        Integer maintenanceCount = getInteger(data, "maintenanceCount");
        if (maintenanceCount == null && statusDist != null) maintenanceCount = getInteger(statusDist, "maintenance");
        if (maintenanceCount == null && health != null) maintenanceCount = getInteger(health, "maintenanceCount");

        Integer faultCount = getInteger(data, "faultCount");
        String availabilityRate = health != null ? getString(health, "availabilityRate") : null;
        Double utilizationRate = getDouble(data, "utilizationRate");

        if (totalCount == null && runningCount == null && utilizationRate == null && availabilityRate == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("设备运行统计：\n\n");
        if (totalCount != null) sb.append(String.format("• 设备总数：%d 台\n", totalCount));
        if (runningCount != null) sb.append(String.format("• 运行中：%d 台\n", runningCount));
        if (idleCount != null) sb.append(String.format("• 空闲：%d 台\n", idleCount));
        if (maintenanceCount != null && maintenanceCount > 0) sb.append(String.format("• 维护中：%d 台\n", maintenanceCount));
        if (faultCount != null && faultCount > 0) sb.append(String.format("• 故障：%d 台\n", faultCount));
        if (utilizationRate != null) sb.append(String.format("• 利用率：%.1f%%\n", utilizationRate));
        if (availabilityRate != null) sb.append(String.format("• 可用率：%s", availabilityRate));

        return sb.toString();
    }

    private String formatEquipmentList(Map<String, Object> data) {
        List<Map<String, Object>> equipment = getList(data, "content");
        if (equipment == null) equipment = getList(data, "equipment");

        if (equipment == null || equipment.isEmpty()) {
            return "暂无匹配的设备信息。请核实设备名称或编号后重试。";
        }

        StringBuilder sb = new StringBuilder(String.format("查询到 %d 台设备：\n\n", equipment.size()));
        int count = 0;
        for (Map<String, Object> eq : equipment) {
            if (count >= 5) break;
            String name = getString(eq, "name");
            if (name == null) name = getString(eq, "equipmentName");
            String status = getString(eq, "status");
            String code = getString(eq, "equipmentCode");
            sb.append(String.format("%d. %s", ++count, name != null ? name : "未知设备"));
            if (code != null) sb.append(String.format(" (%s)", code));
            if (status != null) sb.append(String.format(" [%s]", translateEquipmentStatus(status)));
            sb.append("\n");
        }
        if (equipment.size() > 5) {
            sb.append("... 还有 ").append(equipment.size() - 5).append(" 台");
        }
        return sb.toString();
    }

    private String translateEquipmentStatus(String status) {
        if (status == null) return "未知";
        return switch (status.toLowerCase()) {
            case "running", "active" -> "运行中";
            case "idle", "inactive" -> "空闲";
            case "maintenance" -> "维护中";
            case "fault" -> "故障";
            case "offline", "scrapped" -> "离线";
            default -> status;
        };
    }

    // ==================== 通用格式化 ====================

    private String formatGenericResult(Object resultData) {
        Map<String, Object> data = asMap(resultData);
        if (data == null) return null;
        return formatGenericList(data);
    }

    private String formatGenericList(Map<String, Object> data) {
        List<Map<String, Object>> content = getList(data, "content");
        Integer total = getInteger(data, "totalElements");
        String message = getString(data, "message");

        if (message != null && message.length() >= 20) {
            return message;
        }

        if (content != null && !content.isEmpty()) {
            int count = total != null ? total : content.size();
            // 列出前几条摘要避免过短
            StringBuilder sb = new StringBuilder(String.format("查询到 %d 条记录：\n", count));
            int shown = 0;
            for (Map<String, Object> item : content) {
                if (shown >= 3) break;
                String name = getString(item, "name");
                if (name == null) name = getString(item, "title");
                if (name == null) name = getString(item, "deviceName");
                if (name == null) name = getString(item, "batchNumber");
                if (name != null) {
                    sb.append("• ").append(name).append("\n");
                    shown++;
                }
            }
            if (shown == 0) {
                sb.append("可在对应管理模块查看详细信息。");
            }
            return sb.toString().trim();
        }

        // Summarize available data keys to avoid generic "暂无数据"
        return formatDataSummary("查询完成", data);
    }

    /**
     * 从 data Map 的键值生成摘要文本，避免通用 "暂无数据" 回复
     *
     * 2026-05-22 hotfix (Steve P0 finance Workdesk): 过滤 `_*` 前缀的 internal metadata keys
     * (_toolCount / _executionOrder / _reasoning 等). 之前这些被当业务字段 dump 给用户看,
     * 出现 "查询完成 - _toolCount: 5" 这种垃圾输出. Per fool-proof-design.md — 不让 internal
     * metadata leak 到用户 UI.
     */
    private String formatDataSummary(String prefix, Map<String, Object> data) {
        if (data == null || data.isEmpty()) return prefix + "，当前暂无相关数据记录。";

        StringBuilder sb = new StringBuilder(prefix).append("\n");
        int numericCount = 0;
        int listCount = 0;
        boolean anyShown = false;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            // Skip internal metadata (_toolCount, _executionOrder, _reasoning, ...)
            if (entry.getKey() != null && entry.getKey().startsWith("_")) continue;
            Object val = entry.getValue();
            if (val instanceof Number && numericCount < 5) {
                sb.append("- ").append(humanizeKey(entry.getKey())).append(": ")
                  .append(QUANTITY_FORMATTER.format(((Number) val).doubleValue())).append("\n");
                numericCount++;
                anyShown = true;
            } else if (val instanceof List && listCount < 2) {
                sb.append("- ").append(humanizeKey(entry.getKey())).append(": ")
                  .append(((List<?>) val).size()).append("条\n");
                listCount++;
                anyShown = true;
            }
        }
        if (!anyShown) {
            // 全部是 _* metadata OR 嵌套 Map 结构 (典型: Skill orchestration 返多 Tool 结果)
            // 用 business key count 而不是 raw map size, 避免 "包含 8 项" 实际 6 项是 _* metadata
            long businessKeyCount = data.keySet().stream()
                    .filter(k -> k != null && !k.startsWith("_"))
                    .count();
            if (businessKeyCount > 0) {
                sb.append("包含 ").append(businessKeyCount).append(" 项数据指标 — 详情请查看下方数据卡片或对应报表模块。");
            } else {
                sb.append("执行完成 — 详情请查看下方数据卡片。");
            }
        }
        return sb.toString().trim();
    }

    private String humanizeKey(String key) {
        return switch (key) {
            case "totalOutput" -> "总产量";
            case "totalOrders" -> "总订单数";
            case "totalRevenue" -> "总收入";
            case "totalCost" -> "总成本";
            case "passRate" -> "合格率(%)";
            case "defectRate" -> "不良率(%)";
            case "utilizationRate" -> "利用率(%)";
            case "activeCount" -> "活跃数量";
            case "pendingCount" -> "待处理数量";
            default -> key;
        };
    }

    // ==================== 工具方法 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return null;
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Integer getInteger(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    private Double getDouble(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    private BigDecimal getBigDecimal(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        } else if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        return null;
    }

    private LocalDate getLocalDate(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        } else if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toLocalDate();
        } else if (value instanceof String) {
            try {
                return LocalDate.parse((String) value);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private String translateStatus(String status) {
        if (status == null) return "未知";
        return switch (status.toLowerCase()) {
            case "pending" -> "待处理";
            case "shipped" -> "已发货";
            case "delivered" -> "已送达";
            case "completed" -> "已完成";
            case "cancelled" -> "已取消";
            case "in_stock" -> "在库";
            case "consumed" -> "已消耗";
            case "expired" -> "已过期";
            default -> status;
        };
    }

    private String translateQualityResult(String result) {
        if (result == null) return "未检验";
        return switch (result.toLowerCase()) {
            case "pass", "passed" -> "合格";
            case "fail", "failed" -> "不合格";
            case "pending" -> "待检验";
            default -> result;
        };
    }
}
