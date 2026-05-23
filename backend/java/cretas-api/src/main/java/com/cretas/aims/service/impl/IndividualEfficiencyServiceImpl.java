package com.cretas.aims.service.impl;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.cretas.aims.dto.python.PythonLeastSquaresResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ProcessingStageType;
import com.cretas.aims.entity.ml.WorkerAllocationFeedback;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkerAllocationFeedbackRepository;
import com.cretas.aims.service.IndividualEfficiencyService;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 个人效率分解服务实现
 * 使用最小二乘法从团队数据中分解出个人效率贡献
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndividualEfficiencyServiceImpl implements IndividualEfficiencyService {

    private final WorkerAllocationFeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final PythonSmartBIClient pythonClient;
    private final PythonSmartBIConfig pythonConfig;

    /**
     * Canvas-Thresholds resolver (Phase A P0-3) — overlays FALLBACK_* with per-factory config.
     * Optional injection so Mockito @InjectMocks tests without resolver still construct.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ThresholdResolverService thresholdResolver;

    // 默认效率值 fallback (当数据不足时使用)
    private static final BigDecimal FALLBACK_DEFAULT_EFFICIENCY = new BigDecimal("1.0");

    // 效率值范围 fallback
    private static final BigDecimal FALLBACK_MIN_EFFICIENCY = new BigDecimal("0.1");
    private static final BigDecimal FALLBACK_MAX_EFFICIENCY = new BigDecimal("2.0");

    private BigDecimal resolveDefaultEfficiency(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_DEFAULT_EFFICIENCY;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.EFFICIENCY_DEFAULT, FALLBACK_DEFAULT_EFFICIENCY);
    }

    private BigDecimal resolveMinEfficiency(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_MIN_EFFICIENCY;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.EFFICIENCY_MIN, FALLBACK_MIN_EFFICIENCY);
    }

    private BigDecimal resolveMaxEfficiency(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_MAX_EFFICIENCY;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.EFFICIENCY_MAX, FALLBACK_MAX_EFFICIENCY);
    }

    @Override
    public Map<Long, BigDecimal> calculateIndividualEfficiency(
            String factoryId,
            ProcessingStageType stageType,
            int minObservations) {

        log.info("开始计算个人效率: factoryId={}, stageType={}, minObservations={}",
                factoryId, stageType, minObservations);

        // 1. 查询该工艺的所有反馈记录
        List<WorkerAllocationFeedback> feedbacks = feedbackRepository
                .findByFactoryIdAndStageType(factoryId, stageType);

        if (feedbacks.size() < minObservations) {
            log.warn("数据不足: 需要 {} 条记录，实际只有 {} 条", minObservations, feedbacks.size());
            return Collections.emptyMap();
        }

        // 2. 收集所有参与的工人ID
        Set<Long> allWorkerIds = new HashSet<>();
        for (WorkerAllocationFeedback feedback : feedbacks) {
            allWorkerIds.add(feedback.getWorkerId());
            List<Long> teamIds = parseTeamComposition(feedback.getTeamComposition());
            allWorkerIds.addAll(teamIds);
        }

        if (allWorkerIds.isEmpty()) {
            log.warn("没有找到任何工人数据");
            return Collections.emptyMap();
        }

        // 3. 构建工人ID到索引的映射
        List<Long> workerIdList = new ArrayList<>(allWorkerIds);
        Map<Long, Integer> workerIdToIndex = new HashMap<>();
        for (int i = 0; i < workerIdList.size(); i++) {
            workerIdToIndex.put(workerIdList.get(i), i);
        }

        int n = workerIdList.size();  // 工人数量
        int m = feedbacks.size();     // 记录数量

        log.info("构建矩阵: {} 个工人, {} 条记录", n, m);

        // 4. 构建参与矩阵 A[m×n] 和产出向量 b[m×1]
        double[][] A = new double[m][n];
        double[] b = new double[m];

        for (int j = 0; j < m; j++) {
            WorkerAllocationFeedback feedback = feedbacks.get(j);

            // 填充参与矩阵
            // 当前工人参与
            Integer workerIdx = workerIdToIndex.get(feedback.getWorkerId());
            if (workerIdx != null) {
                A[j][workerIdx] = 1.0;
            }

            // 团队成员参与
            List<Long> teamIds = parseTeamComposition(feedback.getTeamComposition());
            for (Long teamMemberId : teamIds) {
                Integer idx = workerIdToIndex.get(teamMemberId);
                if (idx != null) {
                    A[j][idx] = 1.0;
                }
            }

            // 填充产出向量 (使用综合奖励值作为产出指标)
            BigDecimal reward = feedback.getReward();
            if (reward == null) {
                // 如果没有奖励值，使用效率
                reward = feedback.getActualEfficiency();
            }
            if (reward == null) {
                reward = resolveDefaultEfficiency(factoryId);
            }
            b[j] = reward.doubleValue();
        }

        // 5. 使用最小二乘法求解: x = (A^T * A)^(-1) * A^T * b
        double[] x = solveLeastSquares(A, b, n, m);

        if (x == null) {
            log.error("最小二乘求解失败");
            return Collections.emptyMap();
        }

        // 6. 构建结果映射
        Map<Long, BigDecimal> result = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Long workerId = workerIdList.get(i);
            double efficiency = x[i];

            // 限制效率值范围 (per-factory override via Canvas Thresholds Hub)
            double minEff = resolveMinEfficiency(factoryId).doubleValue();
            double maxEff = resolveMaxEfficiency(factoryId).doubleValue();
            efficiency = Math.max(minEff, Math.min(maxEff, efficiency));

            result.put(workerId, BigDecimal.valueOf(efficiency)
                    .setScale(4, RoundingMode.HALF_UP));
        }

        log.info("个人效率计算完成: 共 {} 个工人", result.size());
        return result;
    }

    @Override
    public Map<ProcessingStageType, Map<Long, BigDecimal>> calculateAllStageEfficiencies(
            String factoryId,
            int minObservations) {

        log.info("计算工厂 {} 的所有工艺效率", factoryId);

        Map<ProcessingStageType, Map<Long, BigDecimal>> result = new EnumMap<>(ProcessingStageType.class);

        for (ProcessingStageType stageType : ProcessingStageType.values()) {
            if (hasEnoughData(factoryId, stageType, minObservations)) {
                Map<Long, BigDecimal> efficiencies =
                        calculateIndividualEfficiency(factoryId, stageType, minObservations);
                if (!efficiencies.isEmpty()) {
                    result.put(stageType, efficiencies);
                }
            }
        }

        return result;
    }

    @Override
    public Map<ProcessingStageType, BigDecimal> getWorkerEfficiencyByStage(
            String factoryId,
            Long workerId) {

        log.info("获取工人效率: factoryId={}, workerId={}", factoryId, workerId);

        // 查询该工人参与的所有反馈记录
        List<WorkerAllocationFeedback> feedbacks = feedbackRepository
                .findByFactoryIdAndWorkerId(factoryId, workerId);

        // 按工艺类型分组计算平均效率
        Map<ProcessingStageType, BigDecimal> result = new EnumMap<>(ProcessingStageType.class);

        Map<ProcessingStageType, List<BigDecimal>> grouped = feedbacks.stream()
                .filter(f -> f.getStageType() != null && f.getActualEfficiency() != null)
                .collect(Collectors.groupingBy(
                        WorkerAllocationFeedback::getStageType,
                        Collectors.mapping(WorkerAllocationFeedback::getActualEfficiency,
                                Collectors.toList())));

        for (Map.Entry<ProcessingStageType, List<BigDecimal>> entry : grouped.entrySet()) {
            List<BigDecimal> efficiencies = entry.getValue();
            if (!efficiencies.isEmpty()) {
                BigDecimal avg = efficiencies.stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(efficiencies.size()), 4, RoundingMode.HALF_UP);
                result.put(entry.getKey(), avg);
            }
        }

        return result;
    }

    @Override
    @Transactional
    public int updateUserSkillLevels(String factoryId, ProcessingStageType stageType) {
        log.info("更新用户技能等级: factoryId={}, stageType={}", factoryId, stageType);

        // 1. 计算个人效率
        Map<Long, BigDecimal> efficiencies =
                calculateIndividualEfficiency(factoryId, stageType, 5);

        if (efficiencies.isEmpty()) {
            log.warn("没有足够数据计算效率，跳过技能更新");
            return 0;
        }

        int updatedCount = 0;

        // 2. 遍历每个工人，更新技能等级
        for (Map.Entry<Long, BigDecimal> entry : efficiencies.entrySet()) {
            Long workerId = entry.getKey();
            BigDecimal efficiency = entry.getValue();

            // 转换效率为技能等级 (1-5)
            int skillLevel = convertEfficiencyToSkillLevel(efficiency);

            // 获取用户
            Optional<User> userOpt = userRepository.findById(workerId);
            if (userOpt.isPresent()) {
                User user = userOpt.get();

                // 解析现有技能等级
                Map<String, Integer> skillLevels = parseSkillLevels(user.getSkillLevels());

                // 更新该工艺的技能等级
                String stageTypeName = stageType.getDescription();
                skillLevels.put(stageTypeName, skillLevel);

                // 序列化回JSON
                user.setSkillLevels(serializeSkillLevels(skillLevels));
                userRepository.save(user);

                updatedCount++;
                log.debug("更新工人 {} 的 {} 技能等级为 {}",
                        workerId, stageTypeName, skillLevel);
            }
        }

        log.info("技能等级更新完成: 更新了 {} 个用户", updatedCount);
        return updatedCount;
    }

    @Override
    public boolean hasEnoughData(String factoryId, ProcessingStageType stageType, int minObservations) {
        long count = feedbackRepository.countByFactoryIdAndStageType(factoryId, stageType);
        return count >= minObservations;
    }

    @Override
    public EfficiencyDataStats getDataStats(String factoryId, ProcessingStageType stageType) {
        List<WorkerAllocationFeedback> feedbacks = feedbackRepository
                .findByFactoryIdAndStageType(factoryId, stageType);

        Set<Long> uniqueWorkers = new HashSet<>();
        Set<String> uniqueTeams = new HashSet<>();

        for (WorkerAllocationFeedback feedback : feedbacks) {
            uniqueWorkers.add(feedback.getWorkerId());
            if (feedback.getTeamComposition() != null) {
                uniqueTeams.add(feedback.getTeamComposition());
            }
        }

        EfficiencyDataStats stats = new EfficiencyDataStats();
        stats.setTotalRecords(feedbacks.size());
        stats.setUniqueWorkers(uniqueWorkers.size());
        stats.setUniqueTeams(uniqueTeams.size());
        stats.setSufficientData(feedbacks.size() >= 10 && uniqueWorkers.size() >= 3);

        return stats;
    }

    // ==================== 私有方法 ====================

    /**
     * 解析团队成员ID列表
     */
    private List<Long> parseTeamComposition(String teamComposition) {
        if (teamComposition == null || teamComposition.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            return objectMapper.readValue(teamComposition, new TypeReference<List<Long>>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析团队组成失败: {}", teamComposition, e);
            return Collections.emptyList();
        }
    }

    /**
     * 解析技能等级JSON
     */
    private Map<String, Integer> parseSkillLevels(String skillLevelsJson) {
        if (skillLevelsJson == null || skillLevelsJson.isEmpty()) {
            return new HashMap<>();
        }

        try {
            return objectMapper.readValue(skillLevelsJson, new TypeReference<Map<String, Integer>>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析技能等级失败: {}", skillLevelsJson, e);
            return new HashMap<>();
        }
    }

    /**
     * 序列化技能等级为JSON
     */
    private String serializeSkillLevels(Map<String, Integer> skillLevels) {
        try {
            return objectMapper.writeValueAsString(skillLevels);
        } catch (JsonProcessingException e) {
            log.error("序列化技能等级失败", e);
            return "{}";
        }
    }

    /**
     * 将效率值转换为技能等级 (1-5)
     *
     * 效率 < 0.6  → 等级 1 (需要培训)
     * 效率 0.6-0.8 → 等级 2 (初级)
     * 效率 0.8-1.0 → 等级 3 (中级)
     * 效率 1.0-1.2 → 等级 4 (高级)
     * 效率 > 1.2  → 等级 5 (专家)
     */
    private int convertEfficiencyToSkillLevel(BigDecimal efficiency) {
        double eff = efficiency.doubleValue();

        if (eff < 0.6) return 1;
        if (eff < 0.8) return 2;
        if (eff < 1.0) return 3;
        if (eff < 1.2) return 4;
        return 5;
    }

    /**
     * 最小二乘法求解: x = (A^T * A)^(-1) * A^T * b
     *
     * 完全使用 Python 服务求解（更高效的 scipy 实现），
     * 不再有 Java fallback。
     */
    private double[] solveLeastSquares(double[][] A, double[] b, int n, int m) {
        // 正则化参数
        double lambda = 0.001;

        // 使用 Python 服务求解（无 Java fallback）
        if (!pythonConfig.isEnabled()) {
            throw new RuntimeException("Python SmartBI 服务未启用，无法进行最小二乘法计算");
        }
        if (!pythonClient.isAvailable()) {
            throw new RuntimeException("Python SmartBI 服务不可用，请确保服务已启动: " + pythonConfig.getUrl());
        }

        double[] pythonResult = solveLeastSquaresPython(A, b, lambda);
        if (pythonResult != null) {
            log.info("最小二乘法求解成功 (Python): 变量数={}", pythonResult.length);
            return pythonResult;
        }

        throw new RuntimeException("Python 最小二乘法求解失败");
    }

    /**
     * 使用 Python 服务求解最小二乘法
     *
     * @return 解向量，失败时返回 null
     */
    private double[] solveLeastSquaresPython(double[][] A, double[] b, double lambda) {
        try {
            Optional<PythonLeastSquaresResponse> responseOpt =
                    pythonClient.solveLeastSquares(A, b, lambda);

            if (responseOpt.isEmpty()) {
                return null;
            }

            PythonLeastSquaresResponse response = responseOpt.get();

            if (!response.isValidSolution()) {
                log.warn("Python 返回的解无效: success={}, error={}",
                        response.isSuccess(), response.getError());
                return null;
            }

            // 记录诊断信息
            if (response.getMetrics() != null) {
                log.debug("Python 最小二乘法诊断: RMSE={}, 条件数={}, 秩={}",
                        response.getMetrics().getRmse(),
                        response.getMetrics().getConditionNumber(),
                        response.getMetrics().getRank());
            }

            return response.getSolutionAsArray();

        } catch (Exception e) {
            log.error("Python 最小二乘法调用异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Java 实现的最小二乘法求解
     * DEPRECATED: 已不再使用，保留作为参考。所有计算由 Python 服务处理。
     *
     * 使用高斯-约旦消元法求解 (A^T * A + λI)x = A^T * b
     */
    @Deprecated
    private double[] solveLeastSquaresJava(double[][] A, double[] b, int n, int m, double lambda) {
        log.debug("使用 Java 实现求解最小二乘法: {}x{} 矩阵", m, n);

        // 1. 计算 A^T * A (n×n 矩阵)
        double[][] AtA = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double sum = 0;
                for (int k = 0; k < m; k++) {
                    sum += A[k][i] * A[k][j];
                }
                AtA[i][j] = sum;
            }
        }

        // 2. 计算 A^T * b (n×1 向量)
        double[] Atb = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int k = 0; k < m; k++) {
                sum += A[k][i] * b[k];
            }
            Atb[i] = sum;
        }

        // 3. 添加正则化项防止矩阵奇异 (岭回归)
        for (int i = 0; i < n; i++) {
            AtA[i][i] += lambda;
        }

        // 4. 增广矩阵 [AtA | Atb]
        double[][] augmented = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(AtA[i], 0, augmented[i], 0, n);
            augmented[i][n] = Atb[i];
        }

        // 5. 高斯-约旦消元
        for (int col = 0; col < n; col++) {
            // 选主元
            int maxRow = col;
            double maxVal = Math.abs(augmented[col][col]);
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(augmented[row][col]) > maxVal) {
                    maxVal = Math.abs(augmented[row][col]);
                    maxRow = row;
                }
            }

            // 交换行
            double[] temp = augmented[col];
            augmented[col] = augmented[maxRow];
            augmented[maxRow] = temp;

            // 检查主元是否为0
            if (Math.abs(augmented[col][col]) < 1e-10) {
                log.warn("矩阵接近奇异，跳过列 {}", col);
                continue;
            }

            // 归一化当前行
            double pivot = augmented[col][col];
            for (int j = col; j <= n; j++) {
                augmented[col][j] /= pivot;
            }

            // 消元
            for (int row = 0; row < n; row++) {
                if (row != col) {
                    double factor = augmented[row][col];
                    for (int j = col; j <= n; j++) {
                        augmented[row][j] -= factor * augmented[col][j];
                    }
                }
            }
        }

        // 6. 提取解向量
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = augmented[i][n];

            // 检查NaN或Inf
            if (Double.isNaN(x[i]) || Double.isInfinite(x[i])) {
                log.warn("求解结果包含无效值: index={}, value={}", i, x[i]);
                // Math NaN/Inf fallback — use system default, not per-factory (this is a solver
                // safety net, not a business config override).
                x[i] = FALLBACK_DEFAULT_EFFICIENCY.doubleValue();
            }
        }

        return x;
    }
}
