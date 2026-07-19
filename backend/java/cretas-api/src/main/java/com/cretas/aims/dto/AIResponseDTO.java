package com.cretas.aims.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI API响应DTO类
 */
public class AIResponseDTO {

    /**
     * AI成本分析响应（批次/时间范围通用）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "AI成本分析响应")
    public static class CostAnalysisResponse {
        @Schema(description = "分析报告ID")
        private Long reportId;

        @Schema(description = "会话ID（用于多轮对话）")
        private String sessionId;

        @Schema(description = "分析结果")
        private AnalysisResult analysis;

        @Schema(description = "AI响应文本")
        private String aiResponse;

        @Schema(description = "生成时间")
        private LocalDateTime timestamp;

        @Schema(description = "AI使用的token数")
        private Integer tokensUsed;

        @Schema(description = "本次分析成本（元）")
        private BigDecimal cost;
    }

    /**
     * 分析结果详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "AI分析结果详情")
    public static class AnalysisResult {
        @Schema(description = "总成本")
        private BigDecimal totalCost;

        @Schema(description = "成本分解")
        private CostBreakdown costBreakdown;

        @Schema(description = "关键发现")
        private List<String> keyFindings;

        @Schema(description = "优化建议")
        private List<OptimizationSuggestion> suggestions;

        @Schema(description = "成本趋势（如果适用）")
        private String trend;

        @Schema(description = "对比基准（如果是对比分析）")
        private Map<String, Object> benchmark;
    }

    /**
     * 成本分解
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "成本分解")
    public static class CostBreakdown {
        @Schema(description = "原材料成本")
        private BigDecimal rawMaterials;

        @Schema(description = "人工成本")
        private BigDecimal labor;

        @Schema(description = "设备成本")
        private BigDecimal equipment;

        @Schema(description = "管理费用")
        private BigDecimal overhead;

        @Schema(description = "其他成本")
        private BigDecimal other;
    }

    /**
     * 优化建议
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "AI优化建议")
    public static class OptimizationSuggestion {
        @Schema(description = "建议类型", example = "cost_reduction")
        private String type;

        @Schema(description = "优先级", example = "high", allowableValues = {"high", "medium", "low"})
        private String priority;

        @Schema(description = "建议描述")
        private String description;

        @Schema(description = "预期节省金额")
        private BigDecimal expectedSavings;

        @Schema(description = "实施难度", example = "medium", allowableValues = {"easy", "medium", "hard"})
        private String difficulty;

        @Schema(description = "实施时间范围（天）")
        private Integer implementationDays;
    }

    /**
     * AI配额信息响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "AI配额信息响应")
    public static class QuotaInfoResponse {
        @Schema(description = "工厂ID")
        private String factoryId;

        @Schema(description = "总配额（元）")
        private BigDecimal totalQuota;

        @Schema(description = "已使用配额（元）")
        private BigDecimal usedQuota;

        @Schema(description = "剩余配额（元）")
        private BigDecimal remainingQuota;

        @Schema(description = "配额使用百分比")
        private Double usagePercentage;

        @Schema(description = "本月请求次数")
        private Integer requestCount;

        @Schema(description = "配额重置日期")
        private LocalDateTime resetDate;

        @Schema(description = "配额状态", allowableValues = {"active", "warning", "exhausted", "expired"})
        private String status;

        @Schema(description = "最近使用记录")
        private List<QuotaUsageRecord> recentUsage;
    }

    /**
     * 配额使用记录
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "配额使用记录")
    public static class QuotaUsageRecord {
        @Schema(description = "使用时间")
        private LocalDateTime timestamp;

        @Schema(description = "分析类型")
        private String analysisType;

        @Schema(description = "消耗金额（元）")
        private BigDecimal cost;

        @Schema(description = "Token数")
        private Integer tokens;
    }

    /**
     * AI对话响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "AI对话响应")
    public static class ConversationResponse {
        @Schema(description = "会话ID")
        private String sessionId;

        @Schema(description = "对话历史")
        private List<ConversationMessage> messages;

        @Schema(description = "会话创建时间")
        private LocalDateTime createdAt;

        @Schema(description = "最后更新时间")
        private LocalDateTime updatedAt;

        @Schema(description = "会话状态", allowableValues = {"active", "closed"})
        private String status;

        @Schema(description = "关联批次ID（如果有）")
        private Long contextBatchId;
    }

    /**
     * 对话消息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "对话消息")
    public static class ConversationMessage {
        @Schema(description = "消息ID")
        private Long id;

        @Schema(description = "角色", allowableValues = {"user", "assistant"})
        private String role;

        @Schema(description = "消息内容")
        private String content;

        @Schema(description = "消息时间")
        private LocalDateTime timestamp;

        @Schema(description = "Token数（仅AI响应）")
        private Integer tokens;
    }

    /**
     * AI报告列表响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "AI报告列表响应")
    public static class ReportListResponse {
        @Schema(description = "报告列表")
        private List<ReportSummary> reports;

        @Schema(description = "总数")
        private Integer total;

        @Schema(description = "当前页")
        private Integer page;

        @Schema(description = "每页大小")
        private Integer pageSize;
    }

    /**
     * 报告摘要
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "AI报告摘要")
    public static class ReportSummary {
        @Schema(description = "报告ID")
        private Long reportId;

        @Schema(description = "报告类型", allowableValues = {"batch", "weekly", "monthly", "custom"})
        private String reportType;

        @Schema(description = "报告标题")
        private String title;

        @Schema(description = "生成时间")
        private LocalDateTime createdAt;

        @Schema(description = "批次ID（如果是批次报告）")
        private Long batchId;

        @Schema(description = "批次编号（如果是批次报告）")
        private String batchNumber;

        @Schema(description = "时间范围开始")
        private LocalDateTime startDate;

        @Schema(description = "时间范围结束")
        private LocalDateTime endDate;

        @Schema(description = "总成本")
        private BigDecimal totalCost;

        @Schema(description = "关键发现数量")
        private Integer keyFindingsCount;

        @Schema(description = "建议数量")
        private Integer suggestionsCount;
    }

    /**
     * AI健康检查响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "AI服务健康检查响应")
    public static class HealthCheckResponse {
        @Schema(description = "服务状态", allowableValues = {"healthy", "degraded", "unavailable"})
        private String status;

        @Schema(description = "LLM API可用性")
        private Boolean llmAvailable;

        @Schema(description = "响应时间（毫秒）")
        private Long responseTime;

        @Schema(description = "最后检查时间")
        private LocalDateTime lastCheckTime;

        @Schema(description = "错误信息（如果有）")
        private String errorMessage;
    }

    // ==================== 员工AI分析相关DTO ====================

    /**
     * 员工AI综合分析响应
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "员工AI综合分析响应")
    public static class EmployeeAnalysisResponse {
        @Schema(description = "员工ID")
        private Long employeeId;

        @Schema(description = "员工姓名")
        private String employeeName;

        @Schema(description = "部门")
        private String department;

        @Schema(description = "职位")
        private String position;

        @Schema(description = "入职时长(月)")
        private Integer tenureMonths;

        @Schema(description = "分析周期开始")
        private String periodStart;

        @Schema(description = "分析周期结束")
        private String periodEnd;

        @Schema(description = "原始记录数合计：考勤记录 + 工作会话 + 批次工作会话 + 质检记录")
        private Long dataPoints;

        @Schema(description = "综合评分(0-100)")
        private Integer overallScore;

        @Schema(description = "综合等级", allowableValues = {"A", "B", "C", "D"})
        private String overallGrade;

        @Schema(description = "环比变化百分比")
        private Double scoreChange;

        @Schema(description = "部门排名百分比(Top N%)")
        private Integer departmentRankPercent;

        @Schema(description = "考勤表现分析")
        private AttendanceAnalysis attendance;

        @Schema(description = "工时效率分析")
        private WorkHoursAnalysis workHours;

        @Schema(description = "生产贡献分析")
        private ProductionAnalysis production;

        @Schema(description = "技能分布")
        private List<SkillDistribution> skills;

        @Schema(description = "AI综合建议")
        private List<EmployeeSuggestion> suggestions;

        @Schema(description = "绩效趋势(近6个月)")
        private List<PerformanceTrend> trends;

        @Schema(description = "AI深度洞察")
        private String aiInsight;

        @Schema(description = "会话ID(用于追问)")
        private String sessionId;

        @Schema(description = "分析时间")
        private LocalDateTime analyzedAt;

        @Schema(description = "消耗Token数")
        private Integer tokensUsed;

        @Schema(description = "因缺少可信数据或配置而不可计算的响应字段路径")
        private List<String> notComputableMetrics;
    }

    /**
     * 考勤表现分析
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "考勤表现分析")
    public static class AttendanceAnalysis {
        @Schema(description = "评分(0-100)")
        private Integer score;

        @Schema(description = "出勤率(%)")
        private Double attendanceRate;

        @Schema(description = "非ABSENT状态考勤记录数")
        private Integer attendanceDays;

        @Schema(description = "考勤记录数")
        private Integer recordCount;

        @Schema(description = "持久化LATE状态记录数")
        private Integer lateCount;

        @Schema(description = "持久化EARLY_LEAVE状态记录数")
        private Integer earlyLeaveCount;

        @Schema(description = "持久化ABSENT状态记录数")
        private Integer absentDays;

        @Schema(description = "打卡记录工作分钟数合计")
        private Integer clockedWorkMinutes;

        @Schema(description = "部门平均出勤率(%)")
        private Double departmentAvgRate;

        @Schema(description = "AI洞察")
        private String insight;

        @Schema(description = "洞察类型", allowableValues = {"positive", "warning", "neutral"})
        private String insightType;
    }

    /**
     * 工时效率分析
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "工时效率分析")
    public static class WorkHoursAnalysis {
        @Schema(description = "评分(0-100)")
        private Integer score;

        @Schema(description = "工作会话实际分钟数合计")
        private Integer totalMinutes;

        @Schema(description = "工作会话数")
        private Long sessionCount;

        @Schema(description = "日均工时(小时)")
        private Double avgDailyHours;

        @Schema(description = "本月加班时长(小时)")
        private Double overtimeHours;

        @Schema(description = "工时效率(%)")
        private Double efficiency;

        @Schema(description = "参与工作类型数")
        private Integer workTypeCount;

        @Schema(description = "部门平均日工时")
        private Double departmentAvgHours;

        @Schema(description = "AI洞察")
        private String insight;

        @Schema(description = "洞察类型", allowableValues = {"positive", "warning", "neutral"})
        private String insightType;
    }

    /**
     * 生产贡献分析
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "生产贡献分析")
    public static class ProductionAnalysis {
        @Schema(description = "评分(0-100)")
        private Integer score;

        @Schema(description = "参与批次数")
        private Integer batchCount;

        @Schema(description = "批次工作会话数")
        private Long batchWorkSessionCount;

        @Schema(description = "已完成批次工作会话数")
        private Long completedBatchWorkSessionCount;

        @Schema(description = "批次工作分钟数合计")
        private Integer batchWorkMinutes;

        @Schema(description = "质检记录数")
        private Long totalInspections;

        @Schema(description = "质检通过记录数")
        private Long passedInspections;

        @Schema(description = "产量贡献(kg)")
        private Double outputQuantity;

        @Schema(description = "质检通过率(%，无质检记录时不可计算)")
        private Double qualityRate;

        @Schema(description = "人均产能(kg/h)")
        private Double productivityRate;

        @Schema(description = "部门平均产能")
        private Double departmentAvgProductivity;

        @Schema(description = "擅长产品线")
        private String topProductLine;

        @Schema(description = "AI洞察")
        private String insight;

        @Schema(description = "洞察类型", allowableValues = {"positive", "warning", "neutral"})
        private String insightType;
    }

    /**
     * 技能分布
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "技能分布")
    public static class SkillDistribution {
        @Schema(description = "技能/工序名称")
        private String skillName;

        @Schema(description = "参与占比(%)")
        private Double percentage;

        @Schema(description = "熟练程度", allowableValues = {"精通", "熟练", "学习中", "新手"})
        private String proficiency;

        @Schema(description = "工时(小时)")
        private Double hours;
    }

    /**
     * 员工建议
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "员工建议")
    public static class EmployeeSuggestion {
        @Schema(description = "建议类型", allowableValues = {"优势", "建议", "关注"})
        private String type;

        @Schema(description = "标题")
        private String title;

        @Schema(description = "详细描述")
        private String description;

        @Schema(description = "优先级", allowableValues = {"high", "medium", "low"})
        private String priority;
    }

    /**
     * 绩效趋势
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "绩效趋势")
    public static class PerformanceTrend {
        @Schema(description = "月份(yyyy-MM)")
        private String month;

        @Schema(description = "评分")
        private Integer score;

        @Schema(description = "等级")
        private String grade;
    }
}
