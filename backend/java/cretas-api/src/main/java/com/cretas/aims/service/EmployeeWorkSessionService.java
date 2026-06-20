package com.cretas.aims.service;

import com.cretas.aims.entity.EmployeeWorkSession;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.EmployeeWorkSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 员工工作会话服务层
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeWorkSessionService {

    private final EmployeeWorkSessionRepository workSessionRepository;

    /**
     * 开始工作会话
     */
    @Transactional
    public EmployeeWorkSession startSession(EmployeeWorkSession session) {
        // 检查用户是否已有活跃会话
        Optional<EmployeeWorkSession> activeSession = workSessionRepository
                .findByFactoryIdAndUserIdAndStatus(session.getFactoryId(), session.getUserId(), "active");

        if (activeSession.isPresent()) {
            throw new IllegalStateException("用户已有活跃的工作会话，请先结束当前会话");
        }

        // 设置默认值
        if (session.getStartTime() == null) {
            session.setStartTime(LocalDateTime.now());
        }
        session.setStatus("active");
        session.setBreakMinutes(0);

        log.info("开始工作会话: 工厂={}, 用户={}, 工作类型={}",
                session.getFactoryId(), session.getUserId(), session.getWorkTypeId());
        return workSessionRepository.save(session);
    }

    /**
     * 结束工作会话
     */
    /**
     * 多租户隔离守卫: 工作会话必须属于路径 factoryId, 否则 403 (审计 round3, 同 disposal)。
     * 此前 end/cancel/update/getById 用 findById(id) 不校验归属 → 跨租户可改别家会话(含人工成本)。
     */
    private void assertSameFactory(EmployeeWorkSession session, String factoryId) {
        if (session.getFactoryId() == null || !session.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该工作会话")
                    .withHint("该会话不属于当前工厂");
        }
    }

    @Transactional
    public EmployeeWorkSession endSession(String factoryId, Long sessionId, Integer breakMinutes, String notes) {
        EmployeeWorkSession session = workSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("工作会话不存在: " + sessionId));
        assertSameFactory(session, factoryId);  // 多租户隔离

        if (!"active".equals(session.getStatus())) {
            throw new IllegalStateException("会话已结束或已取消");
        }

        LocalDateTime endTime = LocalDateTime.now();
        session.setEndTime(endTime);
        session.setStatus("completed");

        if (breakMinutes != null) {
            session.setBreakMinutes(breakMinutes);
        }
        if (notes != null) {
            session.setNotes(notes);
        }

        // 计算实际工作分钟数
        long totalMinutes = Duration.between(session.getStartTime(), endTime).toMinutes();
        int actualMinutes = (int) totalMinutes - session.getBreakMinutes();
        session.setActualWorkMinutes(Math.max(0, actualMinutes));

        // 计算人工成本
        if (session.getHourlyRate() != null && actualMinutes > 0) {
            BigDecimal hours = BigDecimal.valueOf(actualMinutes).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
            BigDecimal cost = session.getHourlyRate().multiply(hours).setScale(2, RoundingMode.HALF_UP);
            session.setLaborCost(cost);
        }

        log.info("结束工作会话: id={}, 实际工时={}分钟, 人工成本={}",
                sessionId, session.getActualWorkMinutes(), session.getLaborCost());
        return workSessionRepository.save(session);
    }

    /**
     * 取消工作会话
     */
    @Transactional
    public EmployeeWorkSession cancelSession(String factoryId, Long sessionId) {
        EmployeeWorkSession session = workSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("工作会话不存在: " + sessionId));
        assertSameFactory(session, factoryId);  // 多租户隔离

        if (!"active".equals(session.getStatus())) {
            throw new IllegalStateException("只能取消活跃的会话");
        }

        session.setStatus("cancelled");
        session.setEndTime(LocalDateTime.now());
        session.setActualWorkMinutes(0);
        session.setLaborCost(BigDecimal.ZERO);

        log.info("取消工作会话: id={}", sessionId);
        return workSessionRepository.save(session);
    }

    /**
     * 根据ID获取工作会话 (按 factoryId 过滤, 跨租户返空 → 404)
     */
    public Optional<EmployeeWorkSession> getById(String factoryId, Long id) {
        return workSessionRepository.findById(id)
                .filter(s -> factoryId != null && factoryId.equals(s.getFactoryId()));
    }

    /**
     * 获取用户当前活跃会话
     */
    public Optional<EmployeeWorkSession> getActiveSession(String factoryId, Long userId) {
        return workSessionRepository.findByFactoryIdAndUserIdAndStatus(factoryId, userId, "active");
    }

    /**
     * 分页查询工厂工作会话
     */
    public Page<EmployeeWorkSession> getByFactoryId(String factoryId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return workSessionRepository.findByFactoryIdOrderByStartTimeDesc(factoryId, pageable);
    }

    /**
     * 按状态分页查询
     */
    public Page<EmployeeWorkSession> getByFactoryIdAndStatus(String factoryId, String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return workSessionRepository.findByFactoryIdAndStatusOrderByStartTimeDesc(factoryId, status, pageable);
    }

    /**
     * 查询用户工作会话
     */
    public List<EmployeeWorkSession> getByUserId(Long userId) {
        return workSessionRepository.findByUserIdOrderByStartTimeDesc(userId);
    }

    /**
     * 按时间范围查询
     */
    public List<EmployeeWorkSession> getByTimeRange(String factoryId, LocalDateTime startTime, LocalDateTime endTime) {
        return workSessionRepository.findByFactoryIdAndTimeRange(factoryId, startTime, endTime);
    }

    /**
     * 按工作类型查询
     */
    public List<EmployeeWorkSession> getByWorkType(String factoryId, Integer workTypeId) {
        return workSessionRepository.findByFactoryIdAndWorkTypeIdOrderByStartTimeDesc(factoryId, workTypeId);
    }

    /**
     * 获取工作会话统计
     */
    public Map<String, Object> getSessionStats(String factoryId, LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, Object> stats = new HashMap<>();

        // 活跃会话数
        stats.put("activeCount", workSessionRepository.countByFactoryIdAndStatus(factoryId, "active"));

        // 总人工成本
        BigDecimal totalCost = workSessionRepository.sumLaborCostByFactoryIdAndTimeRange(factoryId, startTime, endTime);
        stats.put("totalLaborCost", totalCost != null ? totalCost : BigDecimal.ZERO);

        // 按工作类型统计
        List<Object[]> byWorkType = workSessionRepository.sumWorkMinutesByWorkType(factoryId, startTime, endTime);
        stats.put("byWorkType", byWorkType);

        // 按用户统计
        List<Object[]> byUser = workSessionRepository.sumWorkMinutesByUser(factoryId, startTime, endTime);
        stats.put("byUser", byUser);

        return stats;
    }

    /**
     * 获取用户工时统计
     */
    public Map<String, Object> getUserWorkStats(Long userId, LocalDateTime startTime, LocalDateTime endTime) {
        Map<String, Object> stats = new HashMap<>();

        // 会话数
        stats.put("sessionCount", workSessionRepository.countByUserIdAndTimeRange(userId, startTime, endTime));

        // 总工作分钟数
        Integer totalMinutes = workSessionRepository.sumActualWorkMinutesByUserIdAndTimeRange(userId, startTime, endTime);
        stats.put("totalWorkMinutes", totalMinutes != null ? totalMinutes : 0);

        // 转换为小时
        stats.put("totalWorkHours", totalMinutes != null ? totalMinutes / 60.0 : 0.0);

        return stats;
    }

    /**
     * 更新工作会话
     */
    @Transactional
    public EmployeeWorkSession updateSession(String factoryId, Long id, EmployeeWorkSession updateData) {
        EmployeeWorkSession existing = workSessionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("工作会话不存在: " + id));
        assertSameFactory(existing, factoryId);  // 多租户隔离

        // 只能更新活跃的会话
        if (!"active".equals(existing.getStatus())) {
            throw new IllegalStateException("只能更新活跃的会话");
        }

        if (updateData.getWorkTypeId() != null) {
            existing.setWorkTypeId(updateData.getWorkTypeId());
        }
        if (updateData.getHourlyRate() != null) {
            existing.setHourlyRate(updateData.getHourlyRate());
        }
        if (updateData.getNotes() != null) {
            existing.setNotes(updateData.getNotes());
        }

        log.info("更新工作会话: id={}", id);
        return workSessionRepository.save(existing);
    }
}
