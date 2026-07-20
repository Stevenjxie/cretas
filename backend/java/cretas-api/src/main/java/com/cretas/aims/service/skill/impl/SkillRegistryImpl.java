package com.cretas.aims.service.skill.impl;

import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.entity.config.FactorySkillConfig;
import com.cretas.aims.entity.smartbi.SmartBiSkill;
import com.cretas.aims.repository.config.FactorySkillConfigRepository;
import com.cretas.aims.repository.smartbi.SmartBiSkillRepository;
import com.cretas.aims.service.skill.SkillRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Skill注册中心实现
 *
 * <p>Central registry for managing AI skill definitions. Skills can be loaded from:</p>
 * <ul>
 *   <li>Database - SmartBiSkill entities (takes precedence)</li>
 *   <li>Classpath - SKILL.md files with YAML frontmatter</li>
 *   <li>Programmatic - default skills defined in code</li>
 * </ul>
 *
 * <p>SKILL.md file format:</p>
 * <pre>
 * ---
 * name: skill-name
 * displayName: Display Name
 * description: Skill description
 * version: 1.0.0
 * triggers:
 *   - trigger1
 *   - trigger2
 * tools:
 *   - ToolName
 * contextNeeded:
 *   - factoryId
 * ---
 * # Prompt Template Content
 * {{userQuery}}
 * </pre>
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2026-01-18
 */
@Service
@Slf4j
public class SkillRegistryImpl implements SkillRegistry {

    /**
     * Skill定义映射表: skillName -> SkillDefinition
     */
    private final Map<String, SkillDefinition> skillMap = new ConcurrentHashMap<>();

    private final SmartBiSkillRepository skillRepository;
    private final ObjectMapper objectMapper;
    private final PathMatchingResourcePatternResolver resourceResolver;

    /**
     * Directory path for skill definition files
     * Default: classpath:skills/
     */
    @Value("${cretas.skills.directory:classpath:skills/}")
    private String skillsDirectory;

    @Value("${cretas.skills.min_match_score:0.1}")
    private double defaultMinMatchScore;

    /**
     * Whether to load default programmatic skills
     */
    @Value("${cretas.skills.load-defaults:true}")
    private boolean loadDefaults;

    @Autowired(required = false)
    private FactorySkillConfigRepository factorySkillConfigRepository;

    @Autowired
    public SkillRegistryImpl(SmartBiSkillRepository skillRepository, ObjectMapper objectMapper) {
        this.skillRepository = skillRepository;
        this.objectMapper = objectMapper;
        this.resourceResolver = new PathMatchingResourcePatternResolver();
    }

    @PostConstruct
    public void init() {
        log.info("Initializing Skill Registry...");
        loadSkills();
        log.info("Skill Registry initialized with {} skills", skillMap.size());
    }

    /**
     * Load all skills from multiple sources:
     * 1. Database (SmartBiSkill entities) - highest precedence
     * 2. Classpath SKILL.md files - medium precedence
     * 3. Default programmatic skills - lowest precedence
     */
    public void loadSkills() {
        int dbCount = 0;
        int fileCount = 0;
        int defaultCount = 0;

        try {
            // 1. Load default skills first (lowest precedence)
            if (loadDefaults) {
                defaultCount = initializeDefaultSkills();
            }

            // 2. Load from classpath:skills/**/SKILL.md files (overrides defaults)
            fileCount = loadSkillsFromClasspath();

            // 3. Load from database (highest precedence, overrides everything)
            dbCount = loadSkillsFromDatabase();

            log.info("Skills loaded: {} from database, {} from files, {} defaults, total: {}",
                    dbCount, fileCount, defaultCount, skillMap.size());

        } catch (Exception e) {
            log.error("Failed to load skills", e);
        }
    }

    /**
     * 初始化默认Skill定义
     * 作为fallback，当数据库和文件中没有定义时使用
     *
     * @return number of default skills registered
     */
    private int initializeDefaultSkills() {
        int count = 0;

        // 库存分析Skill — 兼容别名；执行计划由 InventoryAnalysisWorkflow 固定治理
        registerWithSource(SkillDefinition.builder()
                .name("inventory-analysis")
                .displayName("库存分析")
                .description("确定性查询库存汇总、原料批次和过期批次")
                .version("3.0.0")
                .triggers(Arrays.asList("库存分析", "库存预警", "低库存", "过期物料", "库存量", "还有多少", "存货"))
                .tools(Arrays.asList("material_stock_summary", "material_batch_query", "material_expired_query"))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("由固定库存分析工作流执行；禁止 LLM 编排或扩展工具。")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // 生产追踪Skill — 加工批次 + 生产计划
        registerWithSource(SkillDefinition.builder()
                .name("production-tracking")
                .displayName("生产追踪")
                .description("追踪生产进度、批次状态、产能等")
                .version("2.0.0")
                .triggers(Arrays.asList("生产进度", "产量", "生产追踪", "工单", "批次状态", "加工进度"))
                .tools(Arrays.asList("processing_batch_list", "processing_batch_detail", "processing_step_query"))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("查询工厂${factoryId}的生产状态，用户问题：${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // 质量检查Skill — 质检查询 + 质检统计
        registerWithSource(SkillDefinition.builder()
                .name("quality-inspection")
                .displayName("质量检查")
                .description("查询和分析质量检查记录")
                .version("2.0.0")
                .triggers(Arrays.asList("质检记录", "质检结果", "质量统计", "不合格", "合格率", "质量检查"))
                .tools(Arrays.asList("quality_check_query", "quality_stats_query", "quality_record_query"))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("查询工厂${factoryId}的质检信息，用户问题：${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // 物料批次Skill — 批次查询 + 批次创建 (already correct)
        registerWithSource(SkillDefinition.builder()
                .name("material-batch")
                .displayName("物料批次管理")
                .description("查询和管理物料批次信息")
                .version("2.0.0")
                .triggers(Arrays.asList("物料批次", "批次查询", "创建批次", "入库登记", "批号查询"))
                .tools(Arrays.asList("material_batch_query", "material_batch_create"))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("处理工厂${factoryId}的物料批次请求，用户问题：${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // 人员排班Skill — 排班查询 + 考勤统计 + 在线人数
        registerWithSource(SkillDefinition.builder()
                .name("personnel-scheduling")
                .displayName("人员排班")
                .description("管理人员排班、调度、出勤等")
                .version("2.0.0")
                .triggers(Arrays.asList("排班", "调度", "上班", "出勤", "人员", "员工"))
                .tools(Arrays.asList("scheduling_list", "attendance_stats", "query_online_staff_count"))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("处理工厂${factoryId}的人员排班请求，用户问题：${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // 报表生成Skill — 仪表盘概览 + 生产报表 + KPI
        registerWithSource(SkillDefinition.builder()
                .name("report-generation")
                .displayName("报表生成")
                .description("生成各类业务报表")
                .version("2.0.0")
                .triggers(Arrays.asList("报表", "报告", "仪表盘", "KPI", "汇总报表", "生产报表"))
                .tools(Arrays.asList("report_dashboard_overview", "report_production", "report_kpi"))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("为工厂${factoryId}生成报表，用户问题：${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // 设备故障诊断Skill — 状态查询 + 活跃告警 + 健康诊断
        registerWithSource(SkillDefinition.builder()
                .name("equipment-diagnosis")
                .displayName("设备故障诊断")
                .description("诊断设备故障，查询设备状态、告警和健康状况")
                .version("1.0.0")
                .triggers(Arrays.asList("设备故障", "设备报警", "设备异常", "设备状态", "设备健康", "机器故障", "停机"))
                .tools(Arrays.asList("equipment_status_query", "alert_active", "equipment_health_diagnosis"))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("诊断工厂${factoryId}的设备问题，用户问题：${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // 订单发货Skill — 订单查询 + 发货创建 + 发货统计
        registerWithSource(SkillDefinition.builder()
                .name("order-fulfillment")
                .displayName("订单发货")
                .description("处理订单发货流程，包括查询订单和创建发货单")
                .version("1.0.0")
                .triggers(Arrays.asList("发货", "出货", "物流", "配送", "发运", "订单发货"))
                .tools(Arrays.asList("order_query", "sales_create_delivery", "shipment_query"))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("处理工厂${factoryId}的发货请求，用户问题：${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // 产品溯源Skill — 批次溯源 + 全链路追踪 + 加工时间线
        registerWithSource(SkillDefinition.builder()
                .name("traceability")
                .displayName("产品溯源")
                .description("追溯产品全链路信息，包括批次追踪和加工时间线")
                .version("1.0.0")
                .triggers(Arrays.asList("溯源", "追溯", "来源", "批次追踪", "食品安全", "溯源码"))
                .tools(Arrays.asList("trace_batch", "trace_full", "processing_batch_timeline"))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("追溯工厂${factoryId}的产品信息，用户问题：${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // 供应商评估Skill — 列表 + 评价 + 排名 + 采购统计
        registerWithSource(SkillDefinition.builder()
                .name("supplier-evaluation")
                .displayName("供应商评估")
                .description("评估供应商表现，包括排名和采购统计")
                .version("1.0.0")
                .triggers(Arrays.asList("供应商", "供货商", "供应商评价", "供应商排名", "采购评估"))
                .tools(Arrays.asList("supplier_list", "supplier_evaluate", "supplier_ranking"))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("评估工厂${factoryId}的供应商，用户问题：${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // 餐饮经营分析Skill — 营业额 + 菜品排名 + 客单价 + 高峰时段
        registerWithSource(SkillDefinition.builder()
                .name("restaurant-operations")
                .displayName("餐饮经营分析")
                .description("分析餐厅经营状况，包括营业额、菜品销量、客单价等")
                .version("1.0.0")
                .triggers(Arrays.asList("餐厅", "门店", "营业额", "菜品销量", "翻台率", "客单价"))
                .tools(Arrays.asList("restaurant_daily_revenue", "restaurant_dish_sales_ranking", "restaurant_avg_ticket"))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("分析${factoryId}的餐饮经营情况，用户问题：${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // 餐饮损耗管理Skill — 损耗汇总 + 异常检测 + 过期预警 + 低库存
        registerWithSource(SkillDefinition.builder()
                .name("restaurant-wastage")
                .displayName("餐饮损耗管理")
                .description("管理餐饮损耗，包括损耗统计、异常检测和食材预警")
                .version("1.0.0")
                .triggers(Arrays.asList("损耗", "浪费", "报损", "废料", "食材过期", "食材库存"))
                .tools(Arrays.asList("restaurant_wastage_summary", "restaurant_wastage_anomaly", "restaurant_ingredient_expiry_alert"))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("管理${factoryId}的餐饮损耗，用户问题：${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // Restaurant diagnostics skill (Phase 2: 深度诊断 — 11 tool 聚合)
        registerWithSource(SkillDefinition.builder()
                .name("restaurant-diagnostics")
                .displayName("餐饮经营深度诊断")
                .description("基于财务数据 + POS 销售 + 评论 + 会员, 给出 cost_rigidity / 对标 / " +
                             "时段分析 / 长尾 SKU / 评论情感 / 储值健康 多维度诊断")
                .version("1.0.0")
                .triggers(Arrays.asList("经营诊断", "深度分析", "为什么亏损", "成本结构", "刚性", "毛利",
                                        "对标", "行业基准", "评分下降", "储值卡", "单店诊断", "P&L"))
                .tools(Arrays.asList(
                        "restaurant_cost_rigidity_analysis",
                        "restaurant_benchmark_alert",
                        "restaurant_channel_margin",
                        "restaurant_dining_heatmap",
                        "restaurant_long_tail_sku",
                        "restaurant_menu_normalization",
                        "restaurant_review_analysis",
                        "restaurant_member_rfm",
                        "restaurant_stored_value",
                        "restaurant_store_pnl_one_pager",
                        "restaurant_bom_layer_status",
                        "restaurant_menu_engineering",
                        "restaurant_forecast"
                ))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("对${factoryId}进行餐饮经营深度诊断，用户问题：${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // Restaurant chain analysis skill (Phase 2: 连锁级分析)
        registerWithSource(SkillDefinition.builder()
                .name("restaurant-chain-analysis")
                .displayName("餐饮连锁分析")
                .description("多店对比 + 门店异常检测 + 月度校准历史 + 同店同比 + 跨连锁对标, " +
                             "适用于连锁品牌总部")
                .version("1.0.0")
                .triggers(Arrays.asList("连锁", "多店对比", "门店排名", "异常店", "同店同比",
                                        "跨品牌", "集团诊断", "17家店"))
                .tools(Arrays.asList(
                        "restaurant_multi_store_comparison",
                        "restaurant_calibration_history",
                        "restaurant_temporal_comparison",
                        "restaurant_cross_chain_benchmark"
                ))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("分析${factoryId}的连锁餐饮状况，用户问题：${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // 成本分析Skill — BOM成本 + 成本偏差 + 成本趋势
        registerWithSource(SkillDefinition.builder()
                .name("cost-analysis")
                .displayName("成本分析")
                .description("分析生产成本，包括BOM成本、偏差分析和趋势")
                .version("1.0.0")
                .triggers(Arrays.asList("成本", "费用", "BOM成本", "成本偏差", "成本趋势", "利润"))
                .tools(Arrays.asList("report_bom_cost", "report_cost_variance", "report_cost_trend_analysis"))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("分析工厂${factoryId}的成本，用户问题：${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // 生产人员管理Skill — 批次工人 + 工人分配 + 到岗确认
        registerWithSource(SkillDefinition.builder()
                .name("production-workforce")
                .displayName("生产人员管理")
                .description("管理生产线人员，包括工人分配、到岗确认和实时人数")
                .version("1.0.0")
                .triggers(Arrays.asList("工人", "派工", "工人到岗", "批次人员", "分配工人", "在岗人数"))
                .tools(Arrays.asList("processing_batch_workers", "processing_worker_assign", "production_confirm_workers_present"))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("管理工厂${factoryId}的生产人员，用户问题：${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // 排产规划Skill — DAG条件分支版: 库存充足→排产, 库存不足→采购建议
        registerWithSource(SkillDefinition.builder()
                .name("production-planning")
                .displayName("排产规划")
                .description("排产全流程：检查原料库存 → 库存充足则创建生产计划并分配工人，库存不足则建议采购")
                .version("2.0.0")
                .triggers(Arrays.asList("排产规划", "生产规划", "制定生产计划", "排一下生产",
                        "明天生产什么", "安排明天生产", "帮我排产", "排产计划"))
                .tools(Arrays.asList("material_stock_summary", "processing_batch_list",
                        "production_plan_create", "processing_worker_assign", "purchase_order_create"))
                .contextNeeded(Arrays.asList("factoryId"))
                .promptTemplate("为工厂${factoryId}进行排产规划。" +
                        "DAG执行：先查库存，根据结果决定排产或采购建议。" +
                        "用户问题：${userQuery}")
                .executionGraph(Arrays.asList(
                        // Step 1: 查库存 (无依赖，入口节点)
                        SkillDefinition.ExecutionNode.builder()
                                .id("check_stock")
                                .toolName("material_stock_summary")
                                .build(),
                        // Step 2a: 库存充足 → 查产线状态
                        SkillDefinition.ExecutionNode.builder()
                                .id("check_lines")
                                .toolName("processing_batch_list")
                                .dependsOn(Arrays.asList("check_stock"))
                                .condition("check_stock.success")
                                .build(),
                        // Step 3a: 产线查询成功 → 创建生产计划
                        SkillDefinition.ExecutionNode.builder()
                                .id("create_plan")
                                .toolName("production_plan_create")
                                .dependsOn(Arrays.asList("check_stock", "check_lines"))
                                .condition("check_stock.success && check_lines.success")
                                .build(),
                        // Step 4a: 计划创建成功 → 分配工人
                        SkillDefinition.ExecutionNode.builder()
                                .id("assign_workers")
                                .toolName("processing_worker_assign")
                                .dependsOn(Arrays.asList("create_plan"))
                                .condition("create_plan.success")
                                .build(),
                        // Step 2b: 库存不足 → 建议采购 (条件分支)
                        SkillDefinition.ExecutionNode.builder()
                                .id("suggest_purchase")
                                .toolName("purchase_order_create")
                                .dependsOn(Arrays.asList("check_stock"))
                                .condition("check_stock.success && !check_stock.data.hasStock")
                                .build()
                ))
                .errorStrategy(SkillDefinition.ErrorStrategy.CONTINUE_ON_ERROR)
                .source("default")
                .enabled(true)
                .build());
        count++;

        // Sprint 8 P1 — 卤味老板 Workdesk V1 (2026-05-20)
        // Skill: daily-customer-followup — 销售员每日跟进任务编排.
        // 串 5 个 Tool 给 LLM aggregate 出 "今日跟进清单" (🔴🟡🟢 三档).
        registerWithSource(SkillDefinition.builder()
                .name("daily-customer-followup")
                .displayName("今日客户跟进")
                .description("销售员/老板每日客户跟进编排 — 聚合客户优先级 + 近期微信 + missed call + 商机预警 + 收入趋势, 输出 🔴🟡🟢 三档排序清单")
                .version("1.0.0")
                .category("SALES")
                .priority(50)
                .triggers(java.util.Arrays.asList(
                        "今天跟谁", "今日跟进", "我的客户跟进", "今天该跟谁",
                        "跟进列表", "客户跟进清单", "今天打谁电话",
                        "今日要跟进哪些客户", "我的销售工作台"))
                .tools(java.util.Arrays.asList(
                        "customer_priority_query",
                        "wechat_record_recent_query",
                        "call_record_followup_pending",
                        "opportunity_stage_alert",
                        "customer_revenue_trend"))
                .contextNeeded(java.util.Arrays.asList("factoryId", "userId"))
                .promptTemplate(
                        "你是销售员/老板的工作台助手. 根据以下 5 个 Tool 输出聚合的 '今日跟进清单':\n" +
                        "- customer_priority_query: 按客户重要性 + 商机阶段排序的客户清单\n" +
                        "- wechat_record_recent_query: 近 7 天微信跟进记录\n" +
                        "- call_record_followup_pending: 近 7 天 MISSED 通话待回访\n" +
                        "- opportunity_stage_alert: 商机超 21 天未推进警告\n" +
                        "- customer_revenue_trend: 客户收入近 2 月趋势\n\n" +
                        "输出格式 (markdown):\n" +
                        "🔴 高优先 (客户 X): 理由 (VIP + 商机停滞 N 天 + missed call) — 建议: ABC\n" +
                        "🟡 中优先 (客户 Y): 理由 — 建议: XYZ\n" +
                        "🟢 低优先 (客户 Z): 理由\n\n" +
                        "末尾加: '今日共 N 个客户值得跟进, 推荐先打 [客户 X] 的电话'.\n" +
                        "工厂 ID: ${factoryId}. 用户问题: ${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // Sprint 8 P2 — 财务主管 Workdesk (2026-05-20)
        // Skill: monthly-financial-close — 月度财务复盘 + 经营摘要编排.
        // 串 7 个 read-only Tool 给 LLM aggregate 出 "月度经营摘要" (期间状态 + 三表 + 工资 +
        // 漏斗 + 应付提成 + 应收账龄).
        registerWithSource(SkillDefinition.builder()
                .name("monthly-financial-close")
                .displayName("月度财务复盘")
                .description("财务主管月末经营摘要编排 — 聚合期间状态 + 三表 + 工资成本 + 商机漏斗 + " +
                             "待付提成 + 应收账龄, 输出可执行的经营复盘报告")
                .version("1.0.0")
                .category("FINANCE")
                .priority(50)
                .triggers(java.util.Arrays.asList(
                        "本月经营", "月度复盘", "X 月经营", "经营怎么样",
                        "本月财务", "月底总结", "月度经营摘要", "财务主管工作台",
                        "经营摘要", "月度报告", "财务月报"))
                .tools(java.util.Arrays.asList(
                        "period_status_query",
                        "balance_sheet_query",
                        "income_statement_query",
                        "cashflow_statement_query",
                        "wage_cost_summary",
                        "opportunity_funnel_stats",
                        "commission_pending_total",
                        "accounts_receivable_aging"))
                .contextNeeded(java.util.Arrays.asList("factoryId", "userId"))
                .promptTemplate(
                        "你是财务主管工作台助手. 根据以下 8 个 Tool 输出聚合的 '月度经营摘要':\n" +
                        "- period_status_query: 本月期间结账状态 (OPEN/PENDING_CLOSE/CLOSED)\n" +
                        "- balance_sheet_query: 期末资产负债表 (总资产/负债/所有者权益 + balanceCheck)\n" +
                        "- income_statement_query: 本月利润表 (营业收入/成本/毛利/营业利润/净利润)\n" +
                        "- cashflow_statement_query: 本月现金流量表 (经营/投资/筹资三类活动)\n" +
                        "- wage_cost_summary: 本月工资成本 (按件/按时/混合 mode 拆分)\n" +
                        "- opportunity_funnel_stats: 商机漏斗 8 阶段统计 (活跃 + 加权预期值)\n" +
                        "- commission_pending_total: 待付提成总额 + 按销售员拆分\n" +
                        "- accounts_receivable_aging: 应收账龄 6 桶 (60+ 天高风险标记)\n\n" +
                        "输出格式 (markdown):\n" +
                        "📊 期间状态: {OPEN/PENDING_CLOSE/CLOSED} {未结账警告(若适用)}\n" +
                        "💰 营业收入: ¥X (vs 上月 ±Y%)\n" +
                        "💸 营业成本: ¥X (vs 上月 ±Y%)\n" +
                        "💵 净利润: ¥X (利润率 Y%)\n" +
                        "💼 工资成本: ¥X (按件 ¥A + 按时 ¥B + 加班 ¥C)\n" +
                        "🎯 商机漏斗: 活跃 N 个 (¥X 预估 / ¥Y 加权)\n" +
                        "📑 三表已生成 [资产负债表] [利润表] [现金流量表] (点击跳转 /finance/three-statements)\n" +
                        "🔔 警告: 应收账龄超 60 天 X 家客户 ¥Y / 待付提成 ¥Z\n" +
                        "💡 建议: {根据数据给 2-3 条 actionable 建议, 例: 紧急催收 60+ 天客户 / " +
                        "确认关账前先 POST 全部 DRAFT 凭证 / 下月控制工资成本占比}\n\n" +
                        "工厂 ID: ${factoryId}. 用户问题: ${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // Sprint 8 P3 — 质量主管 Workdesk 食品安全召回闭环 (2026-05-20)
        // ===== Skill 1: HACCP 检查点管理 =====
        // 单 Tool 流程: 用户问 "X 批次 HACCP 通过吗" → haccp_checkpoint_review →
        // LLM 输出通过/不通过 + 偏离建议
        registerWithSource(SkillDefinition.builder()
                .name("haccp-checkpoint-management")
                .displayName("HACCP 关键控制点审查")
                .description("HACCP CCP 审查 — 查指定批次的全部 CCP 监控记录, 标记 deviation 偏离 + " +
                             "提供 correctiveAction 建议. 食品安全召回前置审计步骤.")
                .version("1.0.0")
                .category("QUALITY")
                .priority(60)
                .triggers(java.util.Arrays.asList(
                        "HACCP", "CCP", "关键控制点", "危害控制",
                        "今天 CCP 监控", "X 批次 HACCP 通过吗", "HACCP 通过",
                        "查 CCP 监控", "HACCP 记录"))
                .tools(java.util.Arrays.asList("haccp_checkpoint_review"))
                .contextNeeded(java.util.Arrays.asList("factoryId"))
                .promptTemplate(
                        "你是食品质量管理助手. 基于 haccp_checkpoint_review Tool 输出, 给出 HACCP 通过判定:\n" +
                        "- 如果 records 为空: 警告 '无 HACCP 监控记录, 无法验证食品安全'\n" +
                        "- 如果 deviationCount = 0: 输出 '✅ HACCP 全部通过' + 监控项数\n" +
                        "- 如果 deviationCount > 0: 逐项列出 deviation (CCP 名称 + 测量值 + 限值范围 + " +
                        "correctiveAction 建议)\n" +
                        "- 末尾给出: 处置建议 (是否需要进一步召回 / 隔离批次 / 加强监控)\n\n" +
                        "工厂 ID: ${factoryId}. 用户问题: ${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // ===== Skill 2: 食品添加剂合规 =====
        // 用户问 "X 批次添加剂合规吗" → additive_compliance_check →
        // LLM 输出合规清单 + 超限警告 + 营养标签草稿
        registerWithSource(SkillDefinition.builder()
                .name("food-additive-compliance")
                .displayName("食品添加剂合规校验")
                .description("GB 2760-2014 食品添加剂限量校验 — 查指定批次 ingredients 的添加剂限量, " +
                             "标记超限项 + 营养标签草稿. 合规风险预警 + 客户提供合规标签.")
                .version("1.0.0")
                .category("QUALITY")
                .priority(60)
                .triggers(java.util.Arrays.asList(
                        "添加剂", "GB 2760", "添加剂合规", "添加剂限量", "亚硝酸盐",
                        "防腐剂", "X 批次添加剂超限了吗", "营养标签", "添加剂校验",
                        "合规标签", "GB 2760 合规"))
                .tools(java.util.Arrays.asList("additive_compliance_check"))
                .contextNeeded(java.util.Arrays.asList("factoryId"))
                .promptTemplate(
                        "你是食品质量合规助手. 基于 additive_compliance_check Tool 输出, " +
                        "给出 GB 2760-2014 合规判定:\n" +
                        "- 如果 mode = REFERENCE (无 ingredients 传入): 输出该食品类目的限量参考表, " +
                        "提示用户哪些添加剂可使用 + 最大限量\n" +
                        "- 如果 mode = VALIDATION 且 exceededCount = 0: 输出 '✅ 添加剂全部合规' + 合规项数\n" +
                        "- 如果 exceededCount > 0: 逐项列出超限添加剂 (名称 + 实际用量 + GB 限量 + 超限值) + " +
                        "纠偏建议 (减少用量 / 替换添加剂 / 重新生产)\n" +
                        "- 如果 unknownCount > 0: 提示 X 项 GB 2760 未收录, 需人工核对\n" +
                        "- 末尾建议: 营养标签草稿可用本批次数据 (referenceCount 或 compliant 列表)\n\n" +
                        "工厂 ID: ${factoryId}. 用户问题: ${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // ===== Skill 3: 食品安全召回闭环 (主 Skill 串 8 Tool) =====
        // 用户说 "启动召回 X 批次" / "X 客户吃了拉肚子启动召回" → 完整 6 步召回流程
        // step 1-4 顺序依赖, step 5 (freeze + notify + report) 并行, step 6 聚合损失
        // 整体输出: 召回行动方案 + 4 个一键执行按钮
        registerWithSource(SkillDefinition.builder()
                .name("food-safety-recall")
                .displayName("食品安全召回闭环")
                .description("食品召回 6 步闭环编排 (Cretas vs 竞品差异化护城河) — " +
                             "客户日期反查批次 → 完整追溯原料/影响客户 → HACCP 审计 → 添加剂合规复查 → " +
                             "(并行: 冻结库存 preview + 通知客户 preview + 监管文件生成) → 损失预估. " +
                             "1 分钟完成 HJ 30 分钟手工工作.")
                .version("1.0.0")
                .category("QUALITY")
                .priority(40)
                .triggers(java.util.Arrays.asList(
                        "启动召回", "食品召回", "拉肚子", "客户投诉", "出问题",
                        "召回 X 批次", "召回 B-", "召回流程", "食品安全事件",
                        "需要召回", "客户中毒", "客户不舒服", "食源性"))
                .tools(java.util.Arrays.asList(
                        "batch_trace_by_customer_date",
                        "batch_full_trace",
                        "haccp_checkpoint_review",
                        "additive_compliance_check",
                        "inventory_freeze",
                        "customer_notify_batch",
                        "regulatory_report_generate",
                        "recall_loss_estimate"))
                .contextNeeded(java.util.Arrays.asList("factoryId", "userId"))
                .promptTemplate(
                        "你是食品安全召回助手, 基于客户投诉触发完整召回 SOP. 串 8 Tool 输出:\n\n" +
                        "**追溯阶段** (step 1-4 顺序):\n" +
                        "- batch_trace_by_customer_date: 按客户 + 投诉日期反查 root batch_number\n" +
                        "- batch_full_trace: 完整追溯 (原料 + 供应商 + 影响客户列表)\n" +
                        "- haccp_checkpoint_review: HACCP CCP 全记录 + deviation 偏离\n" +
                        "- additive_compliance_check: GB 2760 添加剂合规复查\n\n" +
                        "**行动阶段** (step 5 并行 — 3 个 preview 同时显):\n" +
                        "- inventory_freeze (preview, BLOCKING): 冻结涉事批次库存\n" +
                        "- customer_notify_batch (preview): 群发召回通知给影响客户\n" +
                        "- regulatory_report_generate: 生成监管上报文件\n\n" +
                        "**总结阶段** (step 6):\n" +
                        "- recall_loss_estimate: 聚合冻结库存价值 + 退货 + 行政 + 品牌损失\n\n" +
                        "**输出格式 (markdown)**:\n" +
                        "🔍 **追溯**: 客户 X 在 YYYY-MM-DD 收到批次 [B-XXX], 涉及 N 家客户\n" +
                        "🔬 **HACCP**: ✅ 通过 / 🚨 N 项 deviation (列出每项 + 建议)\n" +
                        "🧪 **添加剂**: ✅ 全合规 / 🚨 N 项超 GB 2760 (列出)\n" +
                        "📦 **影响**: N 家客户, M 笔出货, K 个批次\n" +
                        "💰 **预估损失**: ¥X (冻结 + 退货 + 行政 + 品牌损失)\n\n" +
                        "**🚨 召回行动方案 (4 个一键执行按钮)**:\n" +
                        "[🔒 冻结库存 (N 批次)]  [📱 通知客户 (M 家)]  " +
                        "[📄 生成监管文件]  [✅ 关闭召回事件]\n\n" +
                        "末尾提示: '此召回基于 Cretas 食品溯源数据自动追溯, 1 分钟完成手工 30 分钟工作'\n\n" +
                        "工厂 ID: ${factoryId}. 用户问题: ${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // Sprint 9 P0.2 (2026-05-22) — 质量主管 Workdesk Skill 补全.
        // Skill: quality-chief-workdesk — 批次放行综合审查 编排.
        // 之前 V20260820_10 intent QUALITY_CHIEF_WORKDESK (tool_name=NULL) 没对应 Skill,
        // tryExplicitSkillRouteForIntent 找不到 skill → 用户看 "暂不支持此类型的意图执行: WORKDESK".
        // 此 Skill 串 4 Tool 输出 "今日待放行综合判断" (供仓管员综合参考).
        registerWithSource(SkillDefinition.builder()
                .name("quality-chief-workdesk")
                .displayName("质量主管工作台")
                .description("质量主管批次放行综合审查 — 串 4 Tool 输出 (质检合格率 + HACCP 状态 + " +
                             "添加剂合规 + 客户标准对比). 1 屏综合判断该批次能否放行.")
                .version("1.0.0")
                .category("QUALITY")
                .priority(40)
                .triggers(java.util.Arrays.asList(
                        "今天哪些批次待放行", "待放行", "质量主管工作台", "批次放行",
                        "放行 audit", "质检综合", "卤猪蹄能放行吗", "今天放行"))
                .tools(java.util.Arrays.asList(
                        "quality_check_summary",
                        "haccp_status_query",
                        "additive_compliance_check_quality",
                        "customer_quality_standard"))
                .contextNeeded(java.util.Arrays.asList("factoryId", "userId"))
                .promptTemplate(
                        "你是质量主管工作台助手. 根据 4 个 Tool 输出汇总 '今日待放行批次综合判断':\n\n" +
                        "- quality_check_summary: 批次质检综合 (合格率 / result 分布)\n" +
                        "- haccp_status_query: HACCP 状态 (整体通过 + 偏离点 + releaseHint)\n" +
                        "- additive_compliance_check_quality: 添加剂合规判定 (超限项)\n" +
                        "- customer_quality_standard: 客户质量标准对比\n\n" +
                        "输出格式 (markdown):\n" +
                        "✅/🔴 综合判断: 可以放行 / 需要拒收 / 需追加检测\n" +
                        "📊 质检: 合格率 N% (基于 X 条记录)\n" +
                        "🔬 HACCP: ✅通过 / 🚨 N 项 deviation (列出每项)\n" +
                        "🧪 添加剂: ✅合规 / 🚨 N 项超 GB 2760 (列出)\n" +
                        "📋 客户标准: 满足/不满足 (列出客户要求 vs 实际差异)\n" +
                        "💡 建议: {基于 4 项 audit 给出 1-2 条 actionable 建议}\n\n" +
                        "若 4 项数据均为空 (该批次尚未质检), 提示 '该批次暂无质检记录, 请先安排取样'.\n" +
                        "工厂 ID: ${factoryId}. 用户问题: ${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        // Sprint 10 Loop 5 — 生产经理 Workdesk (2026-05-21)
        // Skill: production-demand-analysis — 订单缺产分析 + 推荐起产 编排.
        // 单 Tool 编排 (production_demand_query): WORKDESK intent 入口 — 把 LLM
        // 路由到 Tool, 避免 buildNoToolResponse 报 "暂不支持此类型的意图执行: WORKDESK".
        // intent_code = PRODUCTION_DEMAND_ANALYSIS → kebab = production-demand-analysis.
        registerWithSource(SkillDefinition.builder()
                .name("production-demand-analysis")
                .displayName("订单缺产分析")
                .description("生产经理 Workdesk 入口 — 分析待产订单 (SO 总量 - 已发已产) > 0 的 productId 列表 + " +
                             "推荐起产量 + 推荐生产线. 输出可一键起产的产品清单.")
                .version("1.0.0")
                .category("PRODUCTION")
                .priority(50)
                .triggers(java.util.Arrays.asList(
                        "订单缺产", "今天要起产什么", "排产建议", "什么订单缺货要做",
                        "该生产什么", "今天该排产什么", "今日排产", "起产建议",
                        "缺货生产", "订单缺货要做", "缺货排产"))
                .tools(java.util.Arrays.asList(
                        "production_demand_query"))
                .contextNeeded(java.util.Arrays.asList("factoryId", "userId"))
                .promptTemplate(
                        "你是生产经理工作台助手. 根据 production_demand_query 输出 (按净缺量排序的产品清单)" +
                        "聚合 '今日排产建议':\n\n" +
                        "**输出格式 (markdown)**:\n" +
                        "🔴 高优先 (缺 ≥100): productName — 缺 X kg, 推荐起产 X kg, 推荐产线 ABC\n" +
                        "🟡 中优先 (缺 20-99): productName — ...\n" +
                        "🟢 低优先 (缺 <20): productName — ...\n\n" +
                        "末尾加: '共 N 个产品待排产, 推荐先排 [productName] (净缺 X kg)'.\n\n" +
                        "**一键起产按钮 (前端 Workdesk 渲染)**:\n" +
                        "每行附 [🚀 一键起产] button → 触发 production_batch_create Tool (WRITE + Preview).\n\n" +
                        "工厂 ID: ${factoryId}. 用户问题: ${userQuery}")
                .source("default")
                .enabled(true)
                .build());
        count++;

        return count;
    }

    /**
     * Register a skill and track its source
     */
    private void registerWithSource(SkillDefinition skill) {
        if (skill == null || skill.getName() == null) {
            return;
        }
        skillMap.put(skill.getName(), skill);
        log.debug("Registered skill from {}: {}", skill.getSource(), skill.getName());
    }

    /**
     * Load skills from database
     * Database skills take precedence over all other sources
     *
     * @return number of skills loaded from database
     */
    private int loadSkillsFromDatabase() {
        int loaded = 0;

        try {
            List<SmartBiSkill> dbSkills = skillRepository.findByEnabledTrueOrderByPriorityAsc();

            for (SmartBiSkill dbSkill : dbSkills) {
                try {
                    SkillDefinition definition = convertFromEntity(dbSkill);
                    skillMap.put(definition.getName(), definition);
                    loaded++;
                    log.debug("Loaded skill from database: {}", definition.getName());
                } catch (Exception e) {
                    log.warn("Failed to convert skill from database: {}", dbSkill.getName(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load skills from database", e);
        }

        return loaded;
    }

    /**
     * Load skills from classpath SKILL.md files
     *
     * @return number of skills loaded from files
     */
    private int loadSkillsFromClasspath() {
        int loaded = 0;

        try {
            // Build the pattern to match SKILL.md files
            String pattern = skillsDirectory.endsWith("/")
                    ? skillsDirectory + "**/SKILL.md"
                    : skillsDirectory + "/**/SKILL.md";

            Resource[] resources = resourceResolver.getResources(pattern);
            log.debug("Found {} SKILL.md files in {}", resources.length, skillsDirectory);

            for (Resource resource : resources) {
                try {
                    SkillDefinition definition = parseSkillDefinition(resource);
                    if (definition != null) {
                        skillMap.put(definition.getName(), definition);
                        loaded++;
                        log.debug("Loaded skill from file: {}", definition.getName());
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse skill file: {}", resource.getFilename(), e);
                }
            }

        } catch (IOException e) {
            log.error("Failed to scan for skill files in: {}", skillsDirectory, e);
        }

        return loaded;
    }

    /**
     * Parse a SKILL.md file with YAML frontmatter
     *
     * @param resource the resource to parse
     * @return the parsed SkillDefinition or null if parsing fails
     */
    private SkillDefinition parseSkillDefinition(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

            StringBuilder frontmatter = new StringBuilder();
            StringBuilder content = new StringBuilder();
            boolean inFrontmatter = false;
            boolean frontmatterClosed = false;

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().equals("---")) {
                    if (!inFrontmatter && !frontmatterClosed) {
                        inFrontmatter = true;
                    } else if (inFrontmatter) {
                        inFrontmatter = false;
                        frontmatterClosed = true;
                    }
                } else if (inFrontmatter) {
                    frontmatter.append(line).append("\n");
                } else if (frontmatterClosed) {
                    content.append(line).append("\n");
                }
            }

            if (frontmatter.length() == 0) {
                log.warn("No YAML frontmatter found in: {}", resource.getFilename());
                return null;
            }

            // Parse YAML frontmatter
            Map<String, Object> yaml = parseYaml(frontmatter.toString());
            if (yaml == null || !yaml.containsKey("name")) {
                log.warn("Invalid YAML frontmatter in: {}", resource.getFilename());
                return null;
            }

            // Build SkillDefinition
            SkillDefinition.SkillDefinitionBuilder builder = SkillDefinition.builder()
                    .name((String) yaml.get("name"))
                    .displayName((String) yaml.getOrDefault("displayName", yaml.get("name")))
                    .description((String) yaml.get("description"))
                    .version((String) yaml.getOrDefault("version", "1.0.0"))
                    .promptTemplate(content.toString().trim())
                    .enabled(true)
                    .source("file:" + resource.getFilename());

            // Parse triggers
            if (yaml.containsKey("triggers")) {
                Object triggers = yaml.get("triggers");
                if (triggers instanceof List) {
                    builder.triggers(((List<?>) triggers).stream()
                            .map(Object::toString)
                            .collect(Collectors.toList()));
                }
            }

            // Parse tools
            if (yaml.containsKey("tools")) {
                Object tools = yaml.get("tools");
                if (tools instanceof List) {
                    builder.tools(((List<?>) tools).stream()
                            .map(Object::toString)
                            .collect(Collectors.toList()));
                }
            }

            // Parse contextNeeded
            if (yaml.containsKey("contextNeeded")) {
                Object contextNeeded = yaml.get("contextNeeded");
                if (contextNeeded instanceof List) {
                    builder.contextNeeded(((List<?>) contextNeeded).stream()
                            .map(Object::toString)
                            .collect(Collectors.toList()));
                }
            }

            // Parse priority
            if (yaml.containsKey("priority")) {
                Object priority = yaml.get("priority");
                if (priority instanceof Number) {
                    builder.priority(((Number) priority).intValue());
                } else if (priority instanceof String) {
                    try {
                        builder.priority(Integer.parseInt((String) priority));
                    } catch (NumberFormatException e) {
                        log.warn("Invalid priority value in {}: {}", resource.getFilename(), priority);
                    }
                }
            }

            // Parse category
            if (yaml.containsKey("category")) {
                builder.category((String) yaml.get("category"));
            }

            // Parse requiredPermission
            if (yaml.containsKey("requiredPermission")) {
                builder.requiredPermission((String) yaml.get("requiredPermission"));
            }

            // Parse config
            if (yaml.containsKey("config")) {
                Object config = yaml.get("config");
                if (config instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> configMap = (Map<String, Object>) config;
                    builder.config(configMap);
                }
            }

            // Parse errorStrategy (P0)
            if (yaml.containsKey("errorStrategy")) {
                String strategyStr = yaml.get("errorStrategy").toString().toUpperCase();
                try {
                    builder.errorStrategy(SkillDefinition.ErrorStrategy.valueOf(strategyStr));
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid errorStrategy '{}' in {}, defaulting to STOP",
                            strategyStr, resource.getFilename());
                }
            }

            // Parse executionGraph (P0: DAG conditional branching)
            // Use dedicated parser since the execution block has nested list-of-maps
            List<Map<String, Object>> executionMaps = parseExecutionBlock(frontmatter.toString());
            if (!executionMaps.isEmpty()) {
                List<SkillDefinition.ExecutionNode> executionGraph =
                        parseExecutionGraph(executionMaps);
                if (!executionGraph.isEmpty()) {
                    builder.executionGraph(executionGraph);
                }
            }

            return builder.build();

        } catch (IOException e) {
            log.error("Failed to read skill file: {}", resource.getFilename(), e);
            return null;
        }
    }

    /**
     * Simple YAML parser for frontmatter
     * Supports basic key-value pairs and lists
     *
     * @param yaml the YAML string to parse
     * @return parsed map or null if parsing fails
     */
    private Map<String, Object> parseYaml(String yaml) {
        Map<String, Object> result = new LinkedHashMap<>();
        String currentKey = null;
        List<String> currentList = null;

        String[] lines = yaml.split("\n");

        for (String line : lines) {
            // Skip empty lines and comments
            if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                continue;
            }

            // Check if this is a list item
            if (line.matches("^\\s+-\\s+.*")) {
                if (currentKey != null && currentList != null) {
                    String value = line.replaceFirst("^\\s+-\\s+", "").trim();
                    // Remove quotes if present
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    currentList.add(value);
                }
                continue;
            }

            // Check if this is a key-value pair
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                // Save previous list if any
                if (currentKey != null && currentList != null) {
                    result.put(currentKey, currentList);
                }

                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();

                if (value.isEmpty()) {
                    // Start of a list
                    currentKey = key;
                    currentList = new ArrayList<>();
                } else {
                    // Simple key-value
                    // Remove quotes if present
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    result.put(key, value);
                    currentKey = null;
                    currentList = null;
                }
            }
        }

        // Save final list if any
        if (currentKey != null && currentList != null) {
            result.put(currentKey, currentList);
        }

        return result;
    }

    /**
     * Parse execution graph from a list of node definitions.
     * Each node is represented as a Map with keys: id, tool, dependsOn, condition, fallbackTool, maxRetries
     *
     * This supports the SKILL.md execution format where nodes are defined under "execution:" key.
     * Since our simple YAML parser returns list items as strings, this method also accepts
     * the raw YAML frontmatter for parsing the execution block with a dedicated sub-parser.
     */
    @SuppressWarnings("unchecked")
    private List<SkillDefinition.ExecutionNode> parseExecutionGraph(List<?> executionList) {
        List<SkillDefinition.ExecutionNode> nodes = new ArrayList<>();

        for (Object item : executionList) {
            if (item instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) item;
                SkillDefinition.ExecutionNode.ExecutionNodeBuilder nodeBuilder =
                        SkillDefinition.ExecutionNode.builder()
                                .id((String) map.get("id"))
                                .toolName((String) map.get("tool"));

                if (map.containsKey("dependsOn")) {
                    Object deps = map.get("dependsOn");
                    if (deps instanceof List) {
                        nodeBuilder.dependsOn(((List<?>) deps).stream()
                                .map(Object::toString).collect(Collectors.toList()));
                    } else if (deps instanceof String) {
                        nodeBuilder.dependsOn(List.of((String) deps));
                    }
                }

                if (map.containsKey("condition")) {
                    nodeBuilder.condition((String) map.get("condition"));
                }
                if (map.containsKey("fallbackTool")) {
                    nodeBuilder.fallbackTool((String) map.get("fallbackTool"));
                }
                if (map.containsKey("maxRetries")) {
                    Object retries = map.get("maxRetries");
                    if (retries instanceof Number) {
                        nodeBuilder.maxRetries(((Number) retries).intValue());
                    } else if (retries instanceof String) {
                        try {
                            nodeBuilder.maxRetries(Integer.parseInt((String) retries));
                        } catch (NumberFormatException ignored) {}
                    }
                }
                if (map.containsKey("params")) {
                    Object params = map.get("params");
                    if (params instanceof Map) {
                        nodeBuilder.params((Map<String, Object>) params);
                    }
                }

                SkillDefinition.ExecutionNode node = nodeBuilder.build();
                if (node.getId() != null && node.getToolName() != null) {
                    nodes.add(node);
                } else {
                    log.warn("Skipping execution node with missing id or tool: {}", map);
                }
            }
        }

        return nodes;
    }

    /**
     * Parse the execution block from raw YAML frontmatter.
     * This dedicated parser handles nested list-of-maps that the simple parseYaml() cannot.
     *
     * Expected format:
     * execution:
     *   - id: check_stock
     *     tool: material_stock_summary
     *   - id: create_plan
     *     tool: production_plan_create
     *     dependsOn: [check_stock]
     *     condition: "check_stock.success && check_stock.data.hasStock"
     */
    private List<Map<String, Object>> parseExecutionBlock(String yaml) {
        List<Map<String, Object>> result = new ArrayList<>();

        String[] lines = yaml.split("\n");
        boolean inExecution = false;
        Map<String, Object> currentNode = null;

        for (String line : lines) {
            String trimmed = line.trim();

            // Detect start of execution block
            if (trimmed.equals("execution:")) {
                inExecution = true;
                continue;
            }

            if (!inExecution) continue;

            // Detect end of execution block (next top-level key)
            if (!line.startsWith(" ") && !line.startsWith("\t") && trimmed.contains(":") && !trimmed.startsWith("-")) {
                break;
            }

            // New list item
            if (trimmed.startsWith("- ")) {
                if (currentNode != null) result.add(currentNode);
                currentNode = new LinkedHashMap<>();
                // Parse inline key-value from the "- key: value" line
                String rest = trimmed.substring(2).trim();
                parseKeyValue(rest, currentNode);
                continue;
            }

            // Continuation of current node's properties
            if (currentNode != null && trimmed.contains(":")) {
                parseKeyValue(trimmed, currentNode);
            }
        }

        if (currentNode != null) result.add(currentNode);
        return result;
    }

    /**
     * Parse a "key: value" string into a map entry.
     * Handles inline arrays like [a, b, c] for dependsOn.
     */
    private void parseKeyValue(String kv, Map<String, Object> target) {
        int colonIdx = kv.indexOf(':');
        if (colonIdx <= 0) return;

        String key = kv.substring(0, colonIdx).trim();
        String value = kv.substring(colonIdx + 1).trim();

        // Remove quotes
        if ((value.startsWith("\"") && value.endsWith("\"")) ||
            (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }

        // Parse inline array [a, b, c]
        if (value.startsWith("[") && value.endsWith("]")) {
            String inner = value.substring(1, value.length() - 1);
            List<String> list = new ArrayList<>();
            for (String item : inner.split(",")) {
                String trimItem = item.trim();
                if ((trimItem.startsWith("\"") && trimItem.endsWith("\"")) ||
                    (trimItem.startsWith("'") && trimItem.endsWith("'"))) {
                    trimItem = trimItem.substring(1, trimItem.length() - 1);
                }
                if (!trimItem.isEmpty()) list.add(trimItem);
            }
            target.put(key, list);
        } else {
            target.put(key, value);
        }
    }

    /**
     * Convert a SmartBiSkill entity to SkillDefinition DTO
     *
     * @param entity the database entity
     * @return the DTO
     */
    private SkillDefinition convertFromEntity(SmartBiSkill entity) {
        SkillDefinition.SkillDefinitionBuilder builder = SkillDefinition.builder()
                .name(entity.getName())
                .displayName(entity.getDisplayName())
                .description(entity.getDescription())
                .version(entity.getVersion())
                .promptTemplate(entity.getPromptTemplate())
                .enabled(entity.getEnabled() != null ? entity.getEnabled() : true)
                .priority(entity.getPriority() != null ? entity.getPriority() : 100)
                .category(entity.getCategory())
                .requiredPermission(entity.getRequiredPermission())
                .source("database")
                .databaseId(entity.getId());

        // Parse JSON fields
        try {
            if (entity.getTriggers() != null && !entity.getTriggers().isEmpty()) {
                List<String> triggers = objectMapper.readValue(entity.getTriggers(),
                        new TypeReference<List<String>>() {});
                builder.triggers(triggers);
            }
        } catch (Exception e) {
            log.warn("Failed to parse triggers for skill {}: {}", entity.getName(), e.getMessage());
        }

        try {
            if (entity.getTools() != null && !entity.getTools().isEmpty()) {
                List<String> tools = objectMapper.readValue(entity.getTools(),
                        new TypeReference<List<String>>() {});
                builder.tools(tools);
            }
        } catch (Exception e) {
            log.warn("Failed to parse tools for skill {}: {}", entity.getName(), e.getMessage());
        }

        try {
            if (entity.getContextNeeded() != null && !entity.getContextNeeded().isEmpty()) {
                List<String> contextNeeded = objectMapper.readValue(entity.getContextNeeded(),
                        new TypeReference<List<String>>() {});
                builder.contextNeeded(contextNeeded);
            }
        } catch (Exception e) {
            log.warn("Failed to parse contextNeeded for skill {}: {}", entity.getName(), e.getMessage());
        }

        try {
            if (entity.getConfig() != null && !entity.getConfig().isEmpty()) {
                Map<String, Object> config = objectMapper.readValue(entity.getConfig(),
                        new TypeReference<Map<String, Object>>() {});
                builder.config(config);
            }
        } catch (Exception e) {
            log.warn("Failed to parse config for skill {}: {}", entity.getName(), e.getMessage());
        }

        return builder.build();
    }

    /**
     * Reload all skills from all sources
     * Clears existing cache and reloads fresh data
     */
    public void reloadSkills() {
        log.info("Reloading all skills...");
        skillMap.clear();
        loadSkills();
    }

    @Override
    public void register(SkillDefinition skillDefinition) {
        if (skillDefinition == null || skillDefinition.getName() == null) {
            log.warn("Cannot register skill: definition or name is null");
            return;
        }

        String skillName = skillDefinition.getName();
        if (skillMap.containsKey(skillName)) {
            log.warn("Skill already exists, updating: {}", skillName);
        }

        skillMap.put(skillName, skillDefinition);
        log.info("Registered skill: name={}, displayName={}, triggers={}, enabled={}",
                skillName,
                skillDefinition.getDisplayName(),
                skillDefinition.getTriggers(),
                skillDefinition.isEnabled());
    }

    @Override
    public void registerAll(List<SkillDefinition> skillDefinitions) {
        if (skillDefinitions == null || skillDefinitions.isEmpty()) {
            return;
        }
        skillDefinitions.forEach(this::register);
    }

    @Override
    public void unregister(String skillName) {
        if (skillName == null) {
            return;
        }
        SkillDefinition removed = skillMap.remove(skillName);
        if (removed != null) {
            log.info("Unregistered skill: {}", skillName);
        }
    }

    @Override
    public Optional<SkillDefinition> getSkill(String skillName) {
        return Optional.ofNullable(skillMap.get(skillName));
    }

    @Override
    public List<SkillDefinition> getAllSkills() {
        return new ArrayList<>(skillMap.values());
    }

    @Override
    public List<SkillDefinition> getEnabledSkills() {
        return skillMap.values().stream()
                .filter(SkillDefinition::isEnabled)
                .collect(Collectors.toList());
    }

    @Override
    public List<SkillDefinition> findMatchingSkills(String userQuery) {
        return findMatchingSkills(userQuery, defaultMinMatchScore);
    }

    @Override
    public Optional<SkillDefinition> findBestMatch(String userQuery) {
        List<SkillDefinition> matches = findMatchingSkills(userQuery);
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    @Override
    public List<SkillDefinition> findMatchingSkills(String userQuery, double minScore) {
        if (userQuery == null || userQuery.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // 收集匹配的Skill及其得分
        List<Map.Entry<SkillDefinition, Double>> scoredSkills = new ArrayList<>();

        for (SkillDefinition skill : getEnabledSkills()) {
            double score = skill.calculateMatchScore(userQuery);
            if (score >= minScore) {
                scoredSkills.add(new AbstractMap.SimpleEntry<>(skill, score));
            }
        }

        // 按得分降序排序
        scoredSkills.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // 返回排序后的Skill列表
        List<SkillDefinition> result = scoredSkills.stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        log.debug("Found {} matching skills for query: '{}'", result.size(),
                userQuery.length() > 50 ? userQuery.substring(0, 50) + "..." : userQuery);

        return result;
    }

    @Override
    public boolean hasSkill(String skillName) {
        return skillMap.containsKey(skillName);
    }

    @Override
    public int getSkillCount() {
        return skillMap.size();
    }

    @Override
    public void clear() {
        skillMap.clear();
        log.warn("Skill Registry cleared");
    }

    @Override
    public void refresh() {
        reloadSkills();
    }

    // ==================== Additional Utility Methods ====================

    /**
     * Get skills by category
     *
     * @param category the category
     * @return list of skills in the category
     */
    public List<SkillDefinition> getSkillsByCategory(String category) {
        if (category == null) {
            return Collections.emptyList();
        }
        return skillMap.values().stream()
                .filter(s -> category.equals(s.getCategory()) && s.isEnabled())
                .sorted(Comparator.comparingInt(s -> s.getPriority() != null ? s.getPriority() : 100))
                .collect(Collectors.toList());
    }

    /**
     * Get all distinct categories
     *
     * @return set of category names
     */
    public Set<String> getAllCategories() {
        return skillMap.values().stream()
                .map(SkillDefinition::getCategory)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Get the count of enabled skills
     *
     * @return number of enabled skills
     */
    public int getEnabledSkillCount() {
        return (int) skillMap.values().stream().filter(SkillDefinition::isEnabled).count();
    }

    /**
     * Get skills that require a specific tool
     *
     * @param toolName the tool name
     * @return list of skills using the tool
     */
    public List<SkillDefinition> getSkillsByTool(String toolName) {
        if (toolName == null) {
            return Collections.emptyList();
        }
        return skillMap.values().stream()
                .filter(s -> s.hasTool(toolName) && s.isEnabled())
                .collect(Collectors.toList());
    }

    /**
     * Get registry statistics
     *
     * @return map of statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalSkills", skillMap.size());
        stats.put("enabledSkills", getEnabledSkillCount());
        stats.put("categories", getAllCategories().size());
        stats.put("fromDatabase", skillMap.values().stream().filter(SkillDefinition::isFromDatabase).count());
        stats.put("fromFiles", skillMap.values().stream().filter(SkillDefinition::isFromFile).count());
        stats.put("fromDefaults", skillMap.values().stream()
                .filter(s -> "default".equals(s.getSource()))
                .count());
        return stats;
    }

    /**
     * Get skills sorted by priority
     *
     * @return list of skills sorted by priority (ascending)
     */
    public List<SkillDefinition> getSkillsSortedByPriority() {
        return skillMap.values().stream()
                .filter(SkillDefinition::isEnabled)
                .sorted(Comparator.comparingInt(s -> s.getPriority() != null ? s.getPriority() : 100))
                .collect(Collectors.toList());
    }

    /**
     * Find skills by required permission
     *
     * @param permission the permission to check
     * @return list of skills requiring the permission
     */
    public List<SkillDefinition> getSkillsByPermission(String permission) {
        if (permission == null) {
            return Collections.emptyList();
        }
        return skillMap.values().stream()
                .filter(s -> permission.equals(s.getRequiredPermission()) && s.isEnabled())
                .collect(Collectors.toList());
    }

    /**
     * Check if any skill requires a specific context field
     *
     * @param contextField the context field name
     * @return list of skills requiring the context
     */
    public List<SkillDefinition> getSkillsByContextNeeded(String contextField) {
        if (contextField == null) {
            return Collections.emptyList();
        }
        return skillMap.values().stream()
                .filter(s -> s.needsContext(contextField) && s.isEnabled())
                .collect(Collectors.toList());
    }

    // ==================== Canvas V2: Factory-Level Filtering ====================

    /**
     * Canvas V2: Get skills filtered by factory config.
     */
    public List<SkillDefinition> getSkillsForFactory(String factoryId) {
        List<SkillDefinition> allSkills = new ArrayList<>(skillMap.values());
        if (factorySkillConfigRepository == null) return allSkills;

        List<FactorySkillConfig> configs = factorySkillConfigRepository.findByFactoryId(factoryId);
        if (configs.isEmpty()) return allSkills;

        Map<String, FactorySkillConfig> configMap = configs.stream()
                .collect(Collectors.toMap(FactorySkillConfig::getSkillName, c -> c));

        return allSkills.stream()
                .filter(s -> {
                    FactorySkillConfig fc = configMap.get(s.getName());
                    return fc == null || fc.getEnabled();
                })
                .collect(Collectors.toList());
    }

    /**
     * Canvas V2: Get a skill with factory-level overrides.
     */
    public SkillDefinition getSkillForFactory(String factoryId, String skillName) {
        SkillDefinition base = skillMap.get(skillName);
        if (base == null || factorySkillConfigRepository == null) return base;

        Optional<FactorySkillConfig> config = factorySkillConfigRepository
                .findByFactoryIdAndSkillName(factoryId, skillName);

        if (config.isEmpty()) return base;
        if (!config.get().getEnabled()) return null;

        FactorySkillConfig fc = config.get();
        boolean hasCustomTriggers = fc.getCustomTriggers() != null && !fc.getCustomTriggers().isEmpty();
        boolean hasCustomDag = fc.getCustomDag() != null && !fc.getCustomDag().isEmpty();

        if (hasCustomTriggers || hasCustomDag) {
            // Build overridden skill: custom triggers and/or custom DAG (tool list)
            List<String> triggers = hasCustomTriggers ? fc.getCustomTriggers() : base.getTriggers();

            @SuppressWarnings("unchecked")
            List<String> tools = hasCustomDag && fc.getCustomDag().containsKey("tools")
                    ? (List<String>) fc.getCustomDag().get("tools")
                    : base.getTools();

            String promptTemplate = hasCustomDag && fc.getCustomDag().containsKey("promptTemplate")
                    ? (String) fc.getCustomDag().get("promptTemplate")
                    : base.getPromptTemplate();

            base = SkillDefinition.builder()
                    .name(base.getName()).displayName(base.getDisplayName())
                    .description(base.getDescription()).version(base.getVersion())
                    .triggers(triggers).tools(tools).contextNeeded(base.getContextNeeded())
                    .promptTemplate(promptTemplate).source(base.getSource())
                    .enabled(true).build();
        }

        return base;
    }
}
