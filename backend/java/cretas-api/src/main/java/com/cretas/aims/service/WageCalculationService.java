package com.cretas.aims.service;

import com.cretas.aims.entity.HourlyRateRule;
import com.cretas.aims.entity.PayrollRecord;
import com.cretas.aims.entity.PieceRateRule;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WageCalculation;
import com.cretas.aims.entity.WagePolicy;
import com.cretas.aims.entity.WorkerDailyEfficiency;
import com.cretas.aims.entity.enums.WageMode;
import com.cretas.aims.repository.PayrollRecordRepository;
import com.cretas.aims.repository.PieceRateRuleRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WageCalculationRepository;
import com.cretas.aims.repository.WorkerDailyEfficiencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工资计算引擎服务
 * 负责计件工资计算、日效率统计、工资单生成
 *
 * 主要功能:
 * - 阶梯计件工资计算
 * - 工人日效率记录和统计
 * - 工资单生成和汇总
 * - 人力成本分析
 *
 * 效率评级标准:
 * - A: 优秀 (效率 >= 120%)
 * - B: 良好 (效率 >= 100%)
 * - C: 合格 (效率 >= 80%)
 * - D: 待提升 (效率 < 80%)
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-14
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WageCalculationService {

    private final PieceRateRuleRepository pieceRateRuleRepository;
    private final PayrollRecordRepository payrollRecordRepository;
    private final WorkerDailyEfficiencyRepository workerDailyEfficiencyRepository;
    private final UserRepository userRepository;

    /**
     * Sprint 6 Track W4-B: optional injection (向后兼容 PR #57 E — 这些 bean 是 Sprint 6 新增).
     * 通过 @Autowired(required=false) 而非构造函数, 让 PR #57 E unit test 仍然 pass.
     * 生产环境 Spring 自动注入; 测试用 ReflectionTestUtils.setField 注入 mock.
     */
    @Autowired(required = false)
    private WagePolicyService wagePolicyService;

    @Autowired(required = false)
    private WageCalculationRepository wageCalculationRepository;

    /** Sprint 6 Track W4-B: WageMonthlyScheduler 调用 calculateMonthlyForFactory 时的 worker query repo. */
    @Autowired(required = false)
    private com.cretas.aims.repository.HourlyRateRuleRepository hourlyRateRuleRepository;

    // ==================== 常量定义 ====================

    /** 工资记录状态: 待审核 */
    public static final String PAYROLL_STATUS_PENDING = "PENDING";
    /** 工资记录状态: 已审核 */
    public static final String PAYROLL_STATUS_APPROVED = "APPROVED";
    /** 工资记录状态: 已发放 */
    public static final String PAYROLL_STATUS_PAID = "PAID";

    /** 结算周期: 日结 */
    public static final String PERIOD_TYPE_DAILY = "DAILY";
    /** 结算周期: 周结 */
    public static final String PERIOD_TYPE_WEEKLY = "WEEKLY";
    /** 结算周期: 月结 */
    public static final String PERIOD_TYPE_MONTHLY = "MONTHLY";

    /** 效率趋势: 上升 */
    public static final String TREND_UP = "UP";
    /** 效率趋势: 下降 */
    public static final String TREND_DOWN = "DOWN";
    /** 效率趋势: 稳定 */
    public static final String TREND_STABLE = "STABLE";

    /** 标准工时 (小时/天) */
    private static final BigDecimal STANDARD_WORK_HOURS_PER_DAY = new BigDecimal("8");
    /** 加班工资倍率 */
    private static final BigDecimal OVERTIME_RATE_MULTIPLIER = new BigDecimal("1.5");
    /** 周末加班倍率 */
    private static final BigDecimal WEEKEND_OVERTIME_MULTIPLIER = new BigDecimal("2.0");

    // ==================== 计件工资计算 ====================

    /**
     * 计算计件工资
     *
     * @param factoryId 工厂ID
     * @param workerId 工人ID
     * @param pieceCount 完成件数
     * @param processStageType 工序类型
     * @param date 日期
     * @return 计件工资金额
     */
    public BigDecimal calculatePieceRateWage(String factoryId, Long workerId,
            int pieceCount, String processStageType, LocalDate date) {

        if (pieceCount <= 0) {
            log.debug("计件数为0，返回0工资: factoryId={}, workerId={}", factoryId, workerId);
            return BigDecimal.ZERO;
        }

        // 1. 查找适用的计件规则 (按优先级)
        Optional<PieceRateRule> ruleOpt = findApplicableRule(factoryId, processStageType, null, date);

        if (ruleOpt.isEmpty()) {
            log.warn("未找到适用的计件规则: factoryId={}, processStageType={}, date={}",
                    factoryId, processStageType, date);
            return BigDecimal.ZERO;
        }

        PieceRateRule rule = ruleOpt.get();
        log.debug("使用计件规则: id={}, name={}, tier1Rate={}",
                rule.getId(), rule.getName(), rule.getTier1Rate());

        // 2. 使用规则的 calculateWage 方法计算阶梯工资
        BigDecimal wage = rule.calculateWage(pieceCount);

        log.info("计件工资计算完成: factoryId={}, workerId={}, pieceCount={}, wage={}",
                factoryId, workerId, pieceCount, wage);

        return wage;
    }

    /**
     * 计算计件工资 (带产品类型)
     *
     * @param factoryId 工厂ID
     * @param workerId 工人ID
     * @param pieceCount 完成件数
     * @param processStageType 工序类型
     * @param productTypeId 产品类型ID
     * @param date 日期
     * @return 计件工资金额
     */
    public BigDecimal calculatePieceRateWage(String factoryId, Long workerId,
            int pieceCount, String processStageType, String productTypeId, LocalDate date) {

        if (pieceCount <= 0) {
            return BigDecimal.ZERO;
        }

        Optional<PieceRateRule> ruleOpt = findApplicableRule(factoryId, processStageType, productTypeId, date);

        if (ruleOpt.isEmpty()) {
            log.warn("未找到适用的计件规则: factoryId={}, processStageType={}, productTypeId={}",
                    factoryId, processStageType, productTypeId);
            return BigDecimal.ZERO;
        }

        return ruleOpt.get().calculateWage(pieceCount);
    }

    /**
     * 查找适用的计件规则
     */
    private Optional<PieceRateRule> findApplicableRule(String factoryId,
            String processStageType, String productTypeId, LocalDate date) {

        // 优先查找完全匹配的规则
        if (processStageType != null && productTypeId != null) {
            Optional<PieceRateRule> exactMatch = pieceRateRuleRepository
                    .findBestMatchingRule(factoryId, processStageType, productTypeId, date);
            if (exactMatch.isPresent()) {
                return exactMatch;
            }
        }

        // 其次查找工序匹配的规则
        if (processStageType != null) {
            List<PieceRateRule> processRules = pieceRateRuleRepository
                    .findEffectiveRulesByProcessStage(factoryId, processStageType, date);
            if (!processRules.isEmpty()) {
                return Optional.of(processRules.get(0));
            }
        }

        // 最后查找工厂通用规则
        List<PieceRateRule> factoryRules = pieceRateRuleRepository.findEffectiveRules(factoryId, date);
        return factoryRules.isEmpty() ? Optional.empty() : Optional.of(factoryRules.get(0));
    }

    // ==================== 日效率记录 ====================

    /**
     * 记录工人日效率
     *
     * @param factoryId 工厂ID
     * @param workerId 工人ID
     * @param workDate 工作日期
     * @param pieceCount 完成件数
     * @param workMinutes 工作时长(分钟)
     * @param processStageType 工序类型
     * @return 效率记录
     */
    @Transactional
    public WorkerDailyEfficiency recordDailyEfficiency(String factoryId, Long workerId,
            LocalDate workDate, int pieceCount, int workMinutes, String processStageType) {

        log.info("记录日效率: factoryId={}, workerId={}, date={}, pieces={}, minutes={}",
                factoryId, workerId, workDate, pieceCount, workMinutes);

        // 1. 获取工人信息
        User worker = userRepository.findById(workerId)
                .orElseThrow(() -> new RuntimeException("工人不存在: " + workerId));

        // 2. 查找或创建当日效率记录
        WorkerDailyEfficiency efficiency = workerDailyEfficiencyRepository
                .findByFactoryIdAndWorkerIdAndWorkDateAndProcessStageType(
                        factoryId, workerId, workDate, processStageType)
                .orElse(WorkerDailyEfficiency.builder()
                        .factoryId(factoryId)
                        .workerId(workerId)
                        .workerName(worker.getFullName())
                        .workDate(workDate)
                        .processStageType(processStageType)
                        .totalPieceCount(0)
                        .effectiveWorkMinutes(0)
                        .qualifiedCount(0)
                        .defectCount(0)
                        .build());

        // 3. 更新计件数据
        int newPieceCount = (efficiency.getTotalPieceCount() != null ? efficiency.getTotalPieceCount() : 0) + pieceCount;
        int newWorkMinutes = (efficiency.getEffectiveWorkMinutes() != null ? efficiency.getEffectiveWorkMinutes() : 0) + workMinutes;

        efficiency.setTotalPieceCount(newPieceCount);
        efficiency.setEffectiveWorkMinutes(newWorkMinutes);

        // 4. 假设全部合格 (可以后续调整)
        efficiency.setQualifiedCount(newPieceCount);

        // 5. 计算效率指标 (会在 @PrePersist/@PreUpdate 中自动计算)
        // piecesPerHour, averageTimePerPiece 等

        // 6. 计算效率趋势
        calculateEfficiencyTrend(efficiency, factoryId, workerId, workDate);

        // 7. 获取标准效率并设置对比基准
        setStandardEfficiency(efficiency, factoryId, processStageType);

        // 8. 保存并返回
        efficiency = workerDailyEfficiencyRepository.save(efficiency);
        log.info("日效率记录完成: id={}, piecesPerHour={}", efficiency.getId(), efficiency.getPiecesPerHour());

        return efficiency;
    }

    /**
     * 更新效率记录 (追加计件)
     */
    @Transactional
    public WorkerDailyEfficiency updateDailyEfficiency(Long efficiencyId,
            int additionalPieces, int additionalMinutes) {

        WorkerDailyEfficiency efficiency = workerDailyEfficiencyRepository.findById(efficiencyId)
                .orElseThrow(() -> new RuntimeException("效率记录不存在: " + efficiencyId));

        int newPieceCount = (efficiency.getTotalPieceCount() != null ? efficiency.getTotalPieceCount() : 0) + additionalPieces;
        int newWorkMinutes = (efficiency.getEffectiveWorkMinutes() != null ? efficiency.getEffectiveWorkMinutes() : 0) + additionalMinutes;

        efficiency.setTotalPieceCount(newPieceCount);
        efficiency.setEffectiveWorkMinutes(newWorkMinutes);
        efficiency.setQualifiedCount(newPieceCount);

        return workerDailyEfficiencyRepository.save(efficiency);
    }

    /**
     * 计算效率趋势
     */
    private void calculateEfficiencyTrend(WorkerDailyEfficiency current,
            String factoryId, Long workerId, LocalDate workDate) {

        // 获取前一天的效率
        LocalDate previousDate = workDate.minusDays(1);
        Optional<WorkerDailyEfficiency> previousOpt = workerDailyEfficiencyRepository
                .findByFactoryIdAndWorkerIdAndWorkDate(factoryId, workerId, previousDate);

        if (previousOpt.isEmpty() || previousOpt.get().getPiecesPerHour() == null) {
            current.setEfficiencyTrend(TREND_STABLE);
            return;
        }

        BigDecimal previousRate = previousOpt.get().getPiecesPerHour();
        BigDecimal currentRate = current.getPiecesPerHour();

        if (currentRate == null) {
            current.setEfficiencyTrend(TREND_STABLE);
            return;
        }

        // 计算变化百分比
        BigDecimal changePercent = currentRate.subtract(previousRate)
                .divide(previousRate, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        if (changePercent.compareTo(BigDecimal.valueOf(5)) > 0) {
            current.setEfficiencyTrend(TREND_UP);
        } else if (changePercent.compareTo(BigDecimal.valueOf(-5)) < 0) {
            current.setEfficiencyTrend(TREND_DOWN);
        } else {
            current.setEfficiencyTrend(TREND_STABLE);
        }
    }

    /**
     * 设置标准效率
     */
    private void setStandardEfficiency(WorkerDailyEfficiency efficiency,
            String factoryId, String processStageType) {

        // 获取工厂该工序的平均效率作为标准
        BigDecimal avgEfficiency = workerDailyEfficiencyRepository
                .avgPiecesPerHourByFactoryAndDate(factoryId, efficiency.getWorkDate());

        if (avgEfficiency != null) {
            efficiency.setStandardPiecesPerHour(avgEfficiency);
        } else {
            // 默认标准: 60件/小时
            efficiency.setStandardPiecesPerHour(new BigDecimal("60"));
        }
    }

    // ==================== 工资单生成 ====================

    /**
     * 生成工资单
     *
     * @param factoryId 工厂ID
     * @param workerId 工人ID
     * @param periodStart 周期开始
     * @param periodEnd 周期结束
     * @return 工资记录
     */
    @Transactional
    public PayrollRecord generatePayroll(String factoryId, Long workerId,
            LocalDate periodStart, LocalDate periodEnd) {

        log.info("生成工资单: factoryId={}, workerId={}, period={} to {}",
                factoryId, workerId, periodStart, periodEnd);

        // 检查是否已存在工资记录
        if (payrollRecordRepository.existsByFactoryIdAndWorkerIdAndPeriodStartAndPeriodEnd(
                factoryId, workerId, periodStart, periodEnd)) {
            throw new RuntimeException("该周期的工资记录已存在");
        }

        // 1. 获取工人信息
        User worker = userRepository.findById(workerId)
                .orElseThrow(() -> new RuntimeException("工人不存在: " + workerId));

        // 2. 汇总周期内的所有日效率记录
        List<WorkerDailyEfficiency> efficiencies = workerDailyEfficiencyRepository
                .findByWorkerAndDateRange(factoryId, workerId, periodStart, periodEnd);

        // 3. 计算总计件数
        int totalPieceCount = efficiencies.stream()
                .mapToInt(e -> e.getTotalPieceCount() != null ? e.getTotalPieceCount() : 0)
                .sum();

        // 4. 计算计件工资 (使用周期内最后一天的规则)
        BigDecimal pieceRateWage = BigDecimal.ZERO;
        Long pieceRuleId = null;

        if (totalPieceCount > 0) {
            // 获取主要工序类型 (出现次数最多的工序)
            String mainProcessStage = efficiencies.stream()
                    .filter(e -> e.getProcessStageType() != null)
                    .collect(Collectors.groupingBy(WorkerDailyEfficiency::getProcessStageType, Collectors.counting()))
                    .entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);

            Optional<PieceRateRule> ruleOpt = findApplicableRule(factoryId, mainProcessStage, null, periodEnd);
            if (ruleOpt.isPresent()) {
                PieceRateRule rule = ruleOpt.get();
                pieceRateWage = rule.calculateWage(totalPieceCount);
                pieceRuleId = rule.getId();
            }
        }

        // 5. 获取基本工资
        BigDecimal baseSalary = worker.getMonthlySalary();
        if (baseSalary != null) {
            // 按天数比例计算
            long totalDays = periodEnd.toEpochDay() - periodStart.toEpochDay() + 1;
            long daysInMonth = periodStart.lengthOfMonth();
            baseSalary = baseSalary.multiply(BigDecimal.valueOf(totalDays))
                    .divide(BigDecimal.valueOf(daysInMonth), 2, RoundingMode.HALF_UP);
        } else {
            baseSalary = BigDecimal.ZERO;
        }

        // 6. 计算总工作时长和加班时长
        int totalWorkMinutes = efficiencies.stream()
                .mapToInt(e -> e.getEffectiveWorkMinutes() != null ? e.getEffectiveWorkMinutes() : 0)
                .sum();
        BigDecimal totalWorkHours = BigDecimal.valueOf(totalWorkMinutes)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);

        // 计算标准工时 (工作天数 * 8小时)
        int workDays = efficiencies.size();
        BigDecimal standardHours = STANDARD_WORK_HOURS_PER_DAY.multiply(BigDecimal.valueOf(workDays));

        // 加班时长
        BigDecimal overtimeHours = BigDecimal.ZERO;
        BigDecimal overtimeWage = BigDecimal.ZERO;
        if (totalWorkHours.compareTo(standardHours) > 0) {
            overtimeHours = totalWorkHours.subtract(standardHours);
            // 计算加班工资 (使用小时工资 * 1.5)
            BigDecimal hourlyRate = worker.getHourlyRate();
            if (hourlyRate != null) {
                overtimeWage = hourlyRate.multiply(overtimeHours)
                        .multiply(OVERTIME_RATE_MULTIPLIER)
                        .setScale(2, RoundingMode.HALF_UP);
            }
        }

        // 7. 计算平均效率
        BigDecimal averageEfficiency = BigDecimal.ZERO;
        if (totalWorkMinutes > 0) {
            averageEfficiency = BigDecimal.valueOf(totalPieceCount * 60.0 / totalWorkMinutes)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // 8. 确定效率评级
        String efficiencyRating = determineEfficiencyRating(averageEfficiency, factoryId, periodStart, periodEnd);

        // 9. 确定周期类型
        String periodType = determinePeriodType(periodStart, periodEnd);

        // 10. 创建工资记录
        PayrollRecord payroll = PayrollRecord.builder()
                .factoryId(factoryId)
                .workerId(workerId)
                .workerName(worker.getFullName())
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .periodType(periodType)
                .totalPieceCount(totalPieceCount)
                .pieceRateWage(pieceRateWage)
                .pieceRuleId(pieceRuleId)
                .baseSalary(baseSalary)
                .overtimeWage(overtimeWage)
                .overtimeHours(overtimeHours)
                .bonusAmount(BigDecimal.ZERO)
                .deductionAmount(BigDecimal.ZERO)
                .averageEfficiency(averageEfficiency)
                .totalWorkHours(totalWorkHours)
                .efficiencyRating(efficiencyRating)
                .status(PAYROLL_STATUS_PENDING)
                .build();

        // totalWage 会在 @PrePersist 中自动计算
        payroll = payrollRecordRepository.save(payroll);

        log.info("工资单生成完成: id={}, totalWage={}, pieceRateWage={}",
                payroll.getId(), payroll.getTotalWage(), payroll.getPieceRateWage());

        return payroll;
    }

    /**
     * 批量生成工资单 (按工厂)
     *
     * @param factoryId 工厂ID
     * @param periodStart 周期开始
     * @param periodEnd 周期结束
     * @return 工资记录列表
     */
    @Transactional
    public List<PayrollRecord> generateFactoryPayroll(String factoryId,
            LocalDate periodStart, LocalDate periodEnd) {

        log.info("批量生成工资单: factoryId={}, period={} to {}", factoryId, periodStart, periodEnd);

        // 获取有效率记录的所有工人
        List<WorkerDailyEfficiency> allEfficiencies = workerDailyEfficiencyRepository
                .findByDateRange(factoryId, periodStart, periodEnd);

        Set<Long> workerIds = allEfficiencies.stream()
                .map(WorkerDailyEfficiency::getWorkerId)
                .collect(Collectors.toSet());

        List<PayrollRecord> payrolls = new ArrayList<>();
        int successCount = 0;
        int skipCount = 0;

        for (Long workerId : workerIds) {
            try {
                // 检查是否已存在
                if (payrollRecordRepository.existsByFactoryIdAndWorkerIdAndPeriodStartAndPeriodEnd(
                        factoryId, workerId, periodStart, periodEnd)) {
                    log.debug("工资记录已存在，跳过: workerId={}", workerId);
                    skipCount++;
                    continue;
                }

                PayrollRecord payroll = generatePayroll(factoryId, workerId, periodStart, periodEnd);
                payrolls.add(payroll);
                successCount++;
            } catch (Exception e) {
                log.error("生成工资单失败: workerId={}, error={}", workerId, e.getMessage());
            }
        }

        log.info("批量生成工资单完成: 成功={}, 跳过={}, 总计={}", successCount, skipCount, workerIds.size());

        return payrolls;
    }

    /**
     * 确定效率评级
     */
    private String determineEfficiencyRating(BigDecimal efficiency, String factoryId,
            LocalDate periodStart, LocalDate periodEnd) {

        // 获取工厂平均效率作为基准
        BigDecimal avgEfficiency = payrollRecordRepository
                .avgEfficiencyByPeriod(factoryId, periodStart, periodEnd);

        BigDecimal baseline = avgEfficiency != null ? avgEfficiency : new BigDecimal("60");

        // 计算相对效率百分比
        BigDecimal relativePercent = efficiency.divide(baseline, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        if (relativePercent.compareTo(BigDecimal.valueOf(120)) >= 0) {
            return "A"; // 优秀
        } else if (relativePercent.compareTo(BigDecimal.valueOf(100)) >= 0) {
            return "B"; // 良好
        } else if (relativePercent.compareTo(BigDecimal.valueOf(80)) >= 0) {
            return "C"; // 合格
        } else {
            return "D"; // 待提升
        }
    }

    /**
     * 确定周期类型
     */
    private String determinePeriodType(LocalDate periodStart, LocalDate periodEnd) {
        long days = periodEnd.toEpochDay() - periodStart.toEpochDay() + 1;

        if (days == 1) {
            return PERIOD_TYPE_DAILY;
        } else if (days <= 7) {
            return PERIOD_TYPE_WEEKLY;
        } else {
            return PERIOD_TYPE_MONTHLY;
        }
    }

    // ==================== 效率排名和趋势 ====================

    /**
     * 获取工人效率排名
     *
     * @param factoryId 工厂ID
     * @param date 日期
     * @return 效率排名列表
     */
    public List<WorkerDailyEfficiency> getWorkerEfficiencyRanking(String factoryId, LocalDate date) {
        List<WorkerDailyEfficiency> ranking = workerDailyEfficiencyRepository
                .findDailyRanking(factoryId, date);

        // 设置排名
        for (int i = 0; i < ranking.size(); i++) {
            ranking.get(i).setRankInTeam(i + 1);
        }

        return ranking;
    }

    /**
     * 获取工序效率排名
     */
    public List<WorkerDailyEfficiency> getProcessEfficiencyRanking(String factoryId,
            LocalDate date, String processStageType) {

        List<WorkerDailyEfficiency> ranking = workerDailyEfficiencyRepository
                .findDailyRankingByProcess(factoryId, date, processStageType);

        for (int i = 0; i < ranking.size(); i++) {
            ranking.get(i).setRankInTeam(i + 1);
        }

        return ranking;
    }

    /**
     * 获取工人效率趋势
     *
     * @param workerId 工人ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 日期 -> 效率 的映射
     */
    public Map<LocalDate, BigDecimal> getWorkerEfficiencyTrend(Long workerId,
            LocalDate startDate, LocalDate endDate) {

        List<Object[]> trendData = workerDailyEfficiencyRepository
                .getWorkerTrend(workerId, startDate, endDate);

        Map<LocalDate, BigDecimal> trend = new LinkedHashMap<>();
        for (Object[] row : trendData) {
            LocalDate date = (LocalDate) row[0];
            BigDecimal efficiency = (BigDecimal) row[1];
            trend.put(date, efficiency != null ? efficiency : BigDecimal.ZERO);
        }

        return trend;
    }

    /**
     * 获取工厂效率趋势
     */
    public List<Map<String, Object>> getFactoryEfficiencyTrend(String factoryId,
            LocalDate startDate, LocalDate endDate) {

        List<Object[]> trendData = workerDailyEfficiencyRepository
                .getDailyTrend(factoryId, startDate, endDate);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : trendData) {
            Map<String, Object> dayData = new LinkedHashMap<>();
            dayData.put("date", row[0]);
            dayData.put("avgEfficiency", row[1]);
            dayData.put("totalPieces", row[2]);
            dayData.put("workerCount", row[3]);
            result.add(dayData);
        }

        return result;
    }

    // ==================== 人力成本分析 ====================

    /**
     * 人力成本分析
     *
     * @param factoryId 工厂ID
     * @param periodStart 周期开始
     * @param periodEnd 周期结束
     * @return 分析结果
     */
    public Map<String, Object> analyzeLaborCost(String factoryId,
            LocalDate periodStart, LocalDate periodEnd) {

        log.info("人力成本分析: factoryId={}, period={} to {}", factoryId, periodStart, periodEnd);

        Map<String, Object> analysis = new LinkedHashMap<>();

        // 1. 总工资支出
        BigDecimal totalWage = payrollRecordRepository.sumTotalWageByPeriod(factoryId, periodStart, periodEnd);
        analysis.put("totalWage", totalWage != null ? totalWage : BigDecimal.ZERO);

        // 2. 计件工资总额
        BigDecimal totalPieceRateWage = payrollRecordRepository.sumPieceRateWageByPeriod(factoryId, periodStart, periodEnd);
        analysis.put("totalPieceRateWage", totalPieceRateWage != null ? totalPieceRateWage : BigDecimal.ZERO);

        // 3. 总计件数
        Integer totalPieceCount = payrollRecordRepository.sumPieceCountByPeriod(factoryId, periodStart, periodEnd);
        analysis.put("totalPieceCount", totalPieceCount != null ? totalPieceCount : 0);

        // 4. 单件人工成本
        BigDecimal costPerPiece = BigDecimal.ZERO;
        if (totalPieceCount != null && totalPieceCount > 0 && totalWage != null) {
            costPerPiece = totalWage.divide(BigDecimal.valueOf(totalPieceCount), 4, RoundingMode.HALF_UP);
        }
        analysis.put("costPerPiece", costPerPiece);

        // 5. 平均效率
        BigDecimal avgEfficiency = payrollRecordRepository.avgEfficiencyByPeriod(factoryId, periodStart, periodEnd);
        analysis.put("averageEfficiency", avgEfficiency != null ? avgEfficiency : BigDecimal.ZERO);

        // 6. 按效率评级统计
        List<Object[]> ratingStats = payrollRecordRepository.countByEfficiencyRating(factoryId, periodStart, periodEnd);
        Map<String, Map<String, Object>> byRating = new LinkedHashMap<>();
        for (Object[] row : ratingStats) {
            String rating = (String) row[0];
            if (rating != null) {
                Map<String, Object> ratingData = new LinkedHashMap<>();
                ratingData.put("count", row[1]);
                ratingData.put("totalWage", row[2]);
                byRating.put(rating, ratingData);
            }
        }
        analysis.put("byEfficiencyRating", byRating);

        // 7. 按工序统计
        List<Object[]> processStats = workerDailyEfficiencyRepository.statsByProcessStage(factoryId, periodStart, periodEnd);
        Map<String, Map<String, Object>> byProcess = new LinkedHashMap<>();
        for (Object[] row : processStats) {
            String processStage = (String) row[0];
            if (processStage != null) {
                Map<String, Object> processData = new LinkedHashMap<>();
                processData.put("avgEfficiency", row[1]);
                processData.put("totalPieces", row[2]);
                byProcess.put(processStage, processData);
            }
        }
        analysis.put("byProcessStage", byProcess);

        // 8. 工人数量
        long workerCount = workerDailyEfficiencyRepository.countWorkersByDate(factoryId, periodEnd);
        analysis.put("workerCount", workerCount);

        // 9. 人均产出
        BigDecimal avgPiecesPerWorker = BigDecimal.ZERO;
        if (workerCount > 0 && totalPieceCount != null) {
            avgPiecesPerWorker = BigDecimal.valueOf(totalPieceCount)
                    .divide(BigDecimal.valueOf(workerCount), 2, RoundingMode.HALF_UP);
        }
        analysis.put("avgPiecesPerWorker", avgPiecesPerWorker);

        // 10. 人均工资
        BigDecimal avgWagePerWorker = BigDecimal.ZERO;
        if (workerCount > 0 && totalWage != null) {
            avgWagePerWorker = totalWage.divide(BigDecimal.valueOf(workerCount), 2, RoundingMode.HALF_UP);
        }
        analysis.put("avgWagePerWorker", avgWagePerWorker);

        log.info("人力成本分析完成: totalWage={}, costPerPiece={}, avgEfficiency={}",
                totalWage, costPerPiece, avgEfficiency);

        return analysis;
    }

    // ==================== 工资单审核和发放 ====================

    /**
     * 审核工资单
     */
    @Transactional
    public PayrollRecord approvePayroll(Long payrollId, Long approverId) {
        PayrollRecord payroll = payrollRecordRepository.findById(payrollId)
                .orElseThrow(() -> new RuntimeException("工资记录不存在: " + payrollId));

        if (!PAYROLL_STATUS_PENDING.equals(payroll.getStatus())) {
            throw new RuntimeException("只能审核待审核状态的工资记录");
        }

        payroll.setStatus(PAYROLL_STATUS_APPROVED);
        payroll.setApprovedBy(approverId);
        payroll.setApprovedAt(LocalDateTime.now());

        log.info("工资单审核通过: id={}, approverId={}", payrollId, approverId);

        return payrollRecordRepository.save(payroll);
    }

    /**
     * 标记工资已发放
     */
    @Transactional
    public PayrollRecord markAsPaid(Long payrollId) {
        PayrollRecord payroll = payrollRecordRepository.findById(payrollId)
                .orElseThrow(() -> new RuntimeException("工资记录不存在: " + payrollId));

        if (!PAYROLL_STATUS_APPROVED.equals(payroll.getStatus())) {
            throw new RuntimeException("只能发放已审核的工资记录");
        }

        payroll.setStatus(PAYROLL_STATUS_PAID);
        payroll.setPaidAt(LocalDateTime.now());

        log.info("工资单已发放: id={}", payrollId);

        return payrollRecordRepository.save(payroll);
    }

    /**
     * 批量审核
     */
    @Transactional
    public int batchApprove(List<Long> payrollIds, Long approverId) {
        int count = 0;
        for (Long id : payrollIds) {
            try {
                approvePayroll(id, approverId);
                count++;
            } catch (Exception e) {
                log.error("批量审核失败: id={}, error={}", id, e.getMessage());
            }
        }
        return count;
    }

    // ==================== 查询方法 ====================

    /**
     * 获取工人工资历史
     */
    public List<PayrollRecord> getWorkerPayrollHistory(Long workerId, int limit) {
        return payrollRecordRepository.findByWorkerId(workerId).stream()
                .sorted(Comparator.comparing(PayrollRecord::getPeriodStart).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 获取待审核工资单数量
     */
    public long countPendingPayrolls(String factoryId) {
        return payrollRecordRepository.countByFactoryIdAndStatus(factoryId, PAYROLL_STATUS_PENDING);
    }

    /**
     * 获取工资排行榜
     */
    public List<PayrollRecord> getTopEarners(String factoryId, LocalDate periodStart,
            LocalDate periodEnd, int limit) {

        return payrollRecordRepository.findTopEarners(
                factoryId, periodStart, periodEnd,
                org.springframework.data.domain.PageRequest.of(0, limit));
    }

    // ==================== Sprint 6 Track W4-B: 按 mode 月度工资计算 ====================

    /**
     * Sprint 6 Track W4-B: 按 mode 计算员工月度工资.
     *
     * <p>分支逻辑:
     * <ul>
     *   <li>{@link WageMode#PIECE_RATE}: 沿用 PR #57 E PieceRateRule 阶梯计件 (仅 piece_rate_amount)</li>
     *   <li>{@link WageMode#HOURLY}: 工时 × baseHourlyRate + 加班工时 × baseHourlyRate × overtimeMultiplier</li>
     *   <li>{@link WageMode#MIXED}: HOURLY 全额 (含 OT) + PIECE_RATE 全额 (per spec §W4-B 简化求和)</li>
     * </ul>
     *
     * <p>幂等性: 同 (factoryId, employeeId, periodMonth) upsert 已有 WageCalculation 行
     * (idx_wage_calc_employee_period UNIQUE constraint 保护). 重复 call 仅 update.
     *
     * <p>BigDecimal scale=2, ROUND_HALF_UP per CN 财务标准.
     *
     * @param factoryId  工厂 ID
     * @param month      计算月份 (YearMonth, 例 YearMonth.of(2026, 5))
     * @param employeeId 员工 ID
     * @return WageCalculation 实体 (含 mode + 各分项 + totalAmount derived)
     */
    @Transactional
    public WageCalculation calculateMonthly(String factoryId, YearMonth month, Long employeeId) {
        if (wagePolicyService == null) {
            throw new IllegalStateException(
                    "WagePolicyService 未注入 — Sprint 6 W4-B feature 需要 wage_policy schema");
        }
        if (wageCalculationRepository == null) {
            throw new IllegalStateException("WageCalculationRepository 未注入");
        }

        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();

        // 1. 解析 mode
        WageMode mode = wagePolicyService.resolveModeForEmployee(factoryId, employeeId);

        // 2. 员工信息 (用于 employee_name 冗余)
        Optional<User> workerOpt = userRepository.findById(employeeId);
        String employeeName = workerOpt
                .map(u -> u.getFullName() != null ? u.getFullName() : u.getUsername())
                .orElse("未知员工");

        // 3. 取月度 efficiencies (一次查, 复用)
        List<WorkerDailyEfficiency> effs = workerDailyEfficiencyRepository
                .findByWorkerAndDateRange(factoryId, employeeId, monthStart, monthEnd);

        // 4. 月度汇总: 工时 / 件数
        int totalWorkMinutes = effs.stream()
                .mapToInt(e -> e.getEffectiveWorkMinutes() != null ? e.getEffectiveWorkMinutes() : 0)
                .sum();
        int totalPieceCount = effs.stream()
                .mapToInt(e -> e.getTotalPieceCount() != null ? e.getTotalPieceCount() : 0)
                .sum();
        BigDecimal totalHours = BigDecimal.valueOf(totalWorkMinutes)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        int workDays = effs.size();
        BigDecimal standardHours = STANDARD_WORK_HOURS_PER_DAY.multiply(BigDecimal.valueOf(workDays));
        BigDecimal overtimeHours = totalHours.compareTo(standardHours) > 0
                ? totalHours.subtract(standardHours).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal regularHours = totalHours.subtract(overtimeHours).setScale(2, RoundingMode.HALF_UP);

        // 5. 计算各分项 (按 mode)
        BigDecimal hourlyAmount = BigDecimal.ZERO;
        BigDecimal overtimeAmount = BigDecimal.ZERO;
        BigDecimal pieceRateAmount = BigDecimal.ZERO;
        Long hourlyRuleId = null;
        Long pieceRuleId = null;
        StringBuilder notesBuilder = new StringBuilder();

        boolean needHourly = (mode == WageMode.HOURLY || mode == WageMode.MIXED);
        boolean needPieceRate = (mode == WageMode.PIECE_RATE || mode == WageMode.MIXED);

        // 5a. HOURLY / MIXED: 时薪 + 加班
        if (needHourly) {
            Optional<HourlyRateRule> hRuleOpt = wagePolicyService
                    .findEffectiveHourlyRate(factoryId, employeeId, monthStart);
            if (hRuleOpt.isPresent()) {
                HourlyRateRule hRule = hRuleOpt.get();
                hourlyRuleId = hRule.getId();
                hourlyAmount = regularHours.multiply(hRule.getBaseHourlyRate())
                        .setScale(2, RoundingMode.HALF_UP);
                BigDecimal otMultiplier = hRule.getOvertimeMultiplier() != null
                        ? hRule.getOvertimeMultiplier() : OVERTIME_RATE_MULTIPLIER;
                overtimeAmount = overtimeHours.multiply(hRule.getBaseHourlyRate())
                        .multiply(otMultiplier)
                        .setScale(2, RoundingMode.HALF_UP);
            } else {
                log.warn("[calculateMonthly] factory={}, employee={}, month={}: 缺 HourlyRateRule, " +
                        "hourlyAmount=0", factoryId, employeeId, month);
                notesBuilder.append("缺 HourlyRateRule(").append(monthStart).append("); ");
            }
        }

        // 5b. PIECE_RATE / MIXED: 计件 (per process stage)
        if (needPieceRate && totalPieceCount > 0) {
            // 聚合按 process stage 分别计算 (per PR #57 generatePayroll 主路径)
            Map<String, Integer> piecesByStage = effs.stream()
                    .filter(e -> e.getProcessStageType() != null && e.getTotalPieceCount() != null
                            && e.getTotalPieceCount() > 0)
                    .collect(Collectors.groupingBy(
                            WorkerDailyEfficiency::getProcessStageType,
                            Collectors.summingInt(e -> e.getTotalPieceCount())));

            if (!piecesByStage.isEmpty()) {
                BigDecimal accumPiece = BigDecimal.ZERO;
                Long firstRuleId = null;
                for (Map.Entry<String, Integer> entry : piecesByStage.entrySet()) {
                    String stage = entry.getKey();
                    int pieces = entry.getValue();
                    Optional<PieceRateRule> ruleOpt = findApplicableRule(
                            factoryId, stage, null, monthEnd);
                    if (ruleOpt.isPresent()) {
                        PieceRateRule rule = ruleOpt.get();
                        BigDecimal stageWage = rule.calculateWage(pieces);
                        accumPiece = accumPiece.add(stageWage);
                        if (firstRuleId == null) {
                            firstRuleId = rule.getId();
                        }
                    } else {
                        log.warn("[calculateMonthly] factory={}, employee={}, stage={}: " +
                                "缺 PieceRateRule, skip", factoryId, employeeId, stage);
                        notesBuilder.append("缺 PieceRateRule(").append(stage).append("); ");
                    }
                }
                pieceRateAmount = accumPiece.setScale(2, RoundingMode.HALF_UP);
                pieceRuleId = firstRuleId;
            }
        }

        // 6. idempotent upsert
        WageCalculation calc = wageCalculationRepository
                .findByFactoryIdAndEmployeeIdAndPeriodMonth(factoryId, employeeId, monthStart)
                .orElseGet(() -> WageCalculation.builder()
                        .factoryId(factoryId)
                        .employeeId(employeeId)
                        .periodMonth(monthStart)
                        .build());

        calc.setEmployeeName(employeeName);
        calc.setMode(mode);
        calc.setHourlyAmount(hourlyAmount);
        calc.setOvertimeAmount(overtimeAmount);
        calc.setPieceRateAmount(pieceRateAmount);
        calc.setTotalHours(totalHours);
        calc.setOvertimeHours(overtimeHours);
        calc.setTotalPieceCount(totalPieceCount);
        calc.setHourlyRuleId(hourlyRuleId);
        calc.setPieceRuleId(pieceRuleId);
        if (notesBuilder.length() > 0) {
            calc.setNotes(notesBuilder.toString().trim());
        }

        WageCalculation saved = wageCalculationRepository.save(calc);
        log.info("[calculateMonthly] factory={}, employee={}, month={}, mode={}, " +
                        "total={}, hourly={}, ot={}, piece={}",
                factoryId, employeeId, month, mode,
                saved.getTotalAmount(), hourlyAmount, overtimeAmount, pieceRateAmount);
        return saved;
    }

    /**
     * Sprint 6 Track W4-B: 工厂级批量月度计算 (月底 WageMonthlyScheduler 调用).
     *
     * <p>遍历所有 (worker_daily_efficiency.worker_id) 跑 {@link #calculateMonthly}.
     * 错误不抛 — 单个员工失败不阻塞其他.
     *
     * @return Map containing successCount / failedCount / month / factoryId
     */
    @Transactional
    public Map<String, Object> calculateMonthlyForFactory(String factoryId, YearMonth month) {
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();

        List<WorkerDailyEfficiency> allEffs = workerDailyEfficiencyRepository
                .findByDateRange(factoryId, monthStart, monthEnd);
        Set<Long> workerIds = allEffs.stream()
                .map(WorkerDailyEfficiency::getWorkerId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        int successCount = 0;
        int failedCount = 0;
        List<String> failedReasons = new ArrayList<>();

        for (Long workerId : workerIds) {
            try {
                calculateMonthly(factoryId, month, workerId);
                successCount++;
            } catch (Exception e) {
                failedCount++;
                String reason = "employee=" + workerId + ": " + e.getMessage();
                failedReasons.add(reason);
                log.error("[calculateMonthlyForFactory] factory={}, employee={}, month={}: 计算失败: {}",
                        factoryId, workerId, month, e.getMessage(), e);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("factoryId", factoryId);
        result.put("month", month.toString());
        result.put("workerCount", workerIds.size());
        result.put("successCount", successCount);
        result.put("failedCount", failedCount);
        if (!failedReasons.isEmpty()) {
            result.put("failedReasons", failedReasons);
        }

        log.info("[calculateMonthlyForFactory] factory={}, month={}, workers={}, success={}, failed={}",
                factoryId, month, workerIds.size(), successCount, failedCount);
        return result;
    }
}
