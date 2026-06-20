package com.cretas.aims.service.impl;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.equipment.CreateEquipmentRequest;
import com.cretas.aims.dto.equipment.EquipmentDTO;
import com.cretas.aims.entity.BatchEquipmentUsage;
import com.cretas.aims.entity.EquipmentMaintenance;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.BatchEquipmentUsageRepository;
import com.cretas.aims.repository.EquipmentMaintenanceRepository;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.EquipmentService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 设备服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Service
@RequiredArgsConstructor
public class EquipmentServiceImpl implements EquipmentService {

    private static final Logger log = LoggerFactory.getLogger(EquipmentServiceImpl.class);

    // 设备状态常量
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_INACTIVE = "inactive";
    private static final String STATUS_MAINTENANCE = "maintenance";
    private static final String STATUS_SCRAPPED = "scrapped";

    private final EquipmentRepository equipmentRepository;
    private final EquipmentMaintenanceRepository maintenanceRepository;
    private final BatchEquipmentUsageRepository usageRepository;
    private final UserRepository userRepository;

    /** Canvas V2: DB-driven validation rules */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    private void runConfiguredValidation(String factoryId, String operation, java.util.Map<String, Object> context) {
        if (validationRuleEvaluator == null) return;
        try {
            validationRuleEvaluator.validate(factoryId, "equipment", operation, context);
        } catch (com.cretas.aims.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Canvas validation non-blocking error: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public EquipmentDTO createEquipment(String factoryId, CreateEquipmentRequest request, Long userId) {
        runConfiguredValidation(factoryId, "CREATE", java.util.Map.of(
            "equipmentName", request.getName() != null ? request.getName() : "",
            "status", request.getStatus() != null ? request.getStatus() : "active"));
        log.info("创建设备: factoryId={}, name={}", factoryId, request.getName());

        FactoryEquipment equipment = new FactoryEquipment();
        // id 现在是 Long 类型，由数据库自动生成，无需手动设置
        equipment.setFactoryId(factoryId);

        // 使用用户提供的code，如果没有则自动生成
        String code = (request.getCode() != null && !request.getCode().trim().isEmpty())
                ? request.getCode()
                : generateEquipmentCode();
        equipment.setCode(code);
        equipment.setEquipmentCode(code);
        equipment.setEquipmentName(request.getName());

        // 如果提供了category，使用category作为type；否则使用type
        String type = (request.getCategory() != null && !request.getCategory().trim().isEmpty())
                ? request.getCategory()
                : request.getType();
        equipment.setType(type);

        equipment.setModel(request.getModel());
        equipment.setManufacturer(request.getManufacturer());
        equipment.setSerialNumber(request.getSerialNumber());
        equipment.setPurchaseDate(request.getPurchaseDate());
        equipment.setPurchasePrice(request.getPurchasePrice());
        equipment.setDepreciationYears(request.getDepreciationYears());
        equipment.setHourlyCost(request.getHourlyCost());
        equipment.setPowerConsumptionKw(request.getPowerConsumptionKw());
        equipment.setLocation(request.getLocation());
        equipment.setMaintenanceIntervalHours(request.getMaintenanceIntervalHours());
        equipment.setWarrantyExpiryDate(request.getWarrantyExpiryDate());

        // 使用用户提供的status，如果没有则默认为active
        // 注意: 数据库status字段是ENUM('active','maintenance','inactive')
        String status = (request.getStatus() != null && !request.getStatus().trim().isEmpty())
                ? request.getStatus().toLowerCase()
                : "active";
        equipment.setStatus(status);

        equipment.setTotalRunningHours(0);
        equipment.setNotes(request.getNotes());
        equipment.setCreatedBy(userId);
        equipment.setCreatedAt(LocalDateTime.now());

        // 计算下次维护日期
        if (request.getMaintenanceIntervalHours() != null) {
            equipment.setNextMaintenanceDate(LocalDate.now().plusDays(30));
        }

        equipment = equipmentRepository.save(equipment);
        log.info("设备创建成功: id={}, code={}", equipment.getId(), equipment.getEquipmentCode());
        return toDTO(equipment);
    }

    @Override
    @Transactional
    public EquipmentDTO updateEquipment(String factoryId, String equipmentId, CreateEquipmentRequest request) {
        runConfiguredValidation(factoryId, "UPDATE", java.util.Map.of(
            "equipmentId", equipmentId,
            "equipmentName", request.getName() != null ? request.getName() : "",
            "status", request.getStatus() != null ? request.getStatus() : ""));
        log.info("更新设备: factoryId={}, equipmentId={}", factoryId, equipmentId);

        Long equipmentIdLong = Long.parseLong(equipmentId);
        FactoryEquipment equipment = equipmentRepository.findByIdAndFactoryId(equipmentIdLong, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));
        // Optimistic lock: explicit compare. NOTE: entity.version is Integer but DTO is Long —
        // compare via primitive longValue() so Long.equals(Integer) doesn't always return false.
        if (request.getVersion() != null
                && equipment.getVersion().longValue() != request.getVersion().longValue()) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
                FactoryEquipment.class, equipmentId);
        }

        if (request.getName() != null) equipment.setEquipmentName(request.getName());
        if (request.getType() != null) equipment.setType(request.getType());
        if (request.getModel() != null) equipment.setModel(request.getModel());
        if (request.getManufacturer() != null) equipment.setManufacturer(request.getManufacturer());
        if (request.getSerialNumber() != null) equipment.setSerialNumber(request.getSerialNumber());
        if (request.getPurchaseDate() != null) equipment.setPurchaseDate(request.getPurchaseDate());
        if (request.getPurchasePrice() != null) equipment.setPurchasePrice(request.getPurchasePrice());
        if (request.getDepreciationYears() != null) equipment.setDepreciationYears(request.getDepreciationYears());
        if (request.getHourlyCost() != null) equipment.setHourlyCost(request.getHourlyCost());
        if (request.getPowerConsumptionKw() != null) equipment.setPowerConsumptionKw(request.getPowerConsumptionKw());
        if (request.getLocation() != null) equipment.setLocation(request.getLocation());
        if (request.getMaintenanceIntervalHours() != null) equipment.setMaintenanceIntervalHours(request.getMaintenanceIntervalHours());
        if (request.getWarrantyExpiryDate() != null) equipment.setWarrantyExpiryDate(request.getWarrantyExpiryDate());
        if (request.getNotes() != null) equipment.setNotes(request.getNotes());
        equipment.setUpdatedAt(LocalDateTime.now());

        equipment = equipmentRepository.save(equipment);
        log.info("设备更新成功: id={}", equipment.getId());
        return toDTO(equipment);
    }

    @Override
    @Transactional
    public void deleteEquipment(String factoryId, String equipmentId) {
        log.info("删除设备: factoryId={}, equipmentId={}", factoryId, equipmentId);

        Long equipmentIdLong = Long.parseLong(equipmentId);
        FactoryEquipment equipment = equipmentRepository.findByIdAndFactoryId(equipmentIdLong, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));

        // 检查是否有使用记录
        if (equipmentRepository.hasUsageRecords(equipmentIdLong)) {
            throw new BusinessException(409, "设备有使用记录，无法删除")
                    .withHint("请先归档或转移该设备的使用记录后再删除");
        }

        equipmentRepository.delete(equipment);
        log.info("设备删除成功: id={}", equipmentId);
    }

    @Override
    public EquipmentDTO getEquipmentById(String factoryId, String equipmentId) {
        Long equipmentIdLong = Long.parseLong(equipmentId);
        FactoryEquipment equipment = equipmentRepository.findByIdAndFactoryId(equipmentIdLong, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));
        return toDTO(equipment);
    }

    @Override
    public PageResponse<EquipmentDTO> getEquipmentList(String factoryId, PageRequest pageRequest) {
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        String keyword = pageRequest.getKeyword();
        Page<FactoryEquipment> equipmentPage;
        if (keyword != null && !keyword.trim().isEmpty()) {
            equipmentPage = equipmentRepository.searchByKeyword(factoryId,
                com.cretas.aims.util.SqlLikeEscaper.escape(keyword.trim()), pageable);
        } else {
            equipmentPage = equipmentRepository.findByFactoryId(factoryId, pageable);
        }
        List<EquipmentDTO> equipmentDTOs = equipmentPage.getContent().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());

        PageResponse<EquipmentDTO> response = new PageResponse<>();
        response.setContent(equipmentDTOs);
        response.setPage(pageRequest.getPage());
        response.setSize(pageRequest.getSize());
        response.setTotalElements(equipmentPage.getTotalElements());
        response.setTotalPages(equipmentPage.getTotalPages());
        response.setFirst(equipmentPage.isFirst());
        response.setLast(equipmentPage.isLast());

        return response;
    }

    @Override
    public List<EquipmentDTO> getEquipmentByStatus(String factoryId, String status) {
        List<FactoryEquipment> equipment = equipmentRepository.findByFactoryIdAndStatus(factoryId, status);
        return equipment.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<EquipmentDTO> getEquipmentByType(String factoryId, String type) {
        List<FactoryEquipment> equipment = equipmentRepository.findByFactoryIdAndType(factoryId, type);
        return equipment.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<EquipmentDTO> searchEquipment(String factoryId, String keyword) {
        String safeKeyword = com.cretas.aims.util.SqlLikeEscaper.escape(keyword);
        List<FactoryEquipment> equipment = equipmentRepository.searchByKeyword(factoryId, safeKeyword);
        return equipment.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * 审计 round2: 报废(scrapped)是终态设备防呆守卫。报废设备不得再启动/停止/记录使用/
     * 经通用状态接口"复活"为其它状态。scrapEquipment 是唯一进入 scrapped 的入口;
     * 退出 scrapped 需走专门的恢复流程(目前无), 不允许经常规写操作静默 un-scrap。
     */
    private void assertNotScrapped(FactoryEquipment equipment, String action) {
        if (STATUS_SCRAPPED.equals(equipment.getStatus())) {
            throw new BusinessException(409, "设备已报废，无法" + action)
                    .withHint("报废为终态; 如系误报废请联系管理员走恢复流程").withHintTarget("status");
        }
    }

    @Override
    @Transactional
    public EquipmentDTO updateEquipmentStatus(String factoryId, String equipmentId, String status) {
        log.info("更新设备状态: factoryId={}, equipmentId={}, status={}", factoryId, equipmentId, status);

        Long equipmentIdLong = Long.parseLong(equipmentId);
        FactoryEquipment equipment = equipmentRepository.findByIdAndFactoryId(equipmentIdLong, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));

        // 审计 round2: 防止经通用状态接口把报废设备静默 un-scrap (报废→其它状态)。
        if (STATUS_SCRAPPED.equals(equipment.getStatus()) && !STATUS_SCRAPPED.equals(status)) {
            throw new BusinessException(409, "设备已报废，状态不可变更")
                    .withHint("报废为终态; 如系误报废请联系管理员走恢复流程").withHintTarget("status");
        }

        equipment.setStatus(status);
        equipment.setUpdatedAt(LocalDateTime.now());
        equipment = equipmentRepository.save(equipment);

        log.info("设备状态更新成功: id={}, status={}", equipment.getId(), status);
        return toDTO(equipment);
    }

    @Override
    @Transactional
    public EquipmentDTO startEquipment(String factoryId, String equipmentId) {
        log.info("启动设备: factoryId={}, equipmentId={}", factoryId, equipmentId);

        Long equipmentIdLong = Long.parseLong(equipmentId);
        FactoryEquipment equipment = equipmentRepository.findByIdAndFactoryId(equipmentIdLong, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));

        assertNotScrapped(equipment, "启动");  // 审计 round2: 报废设备不可启动
        if ("active".equals(equipment.getStatus())) {
            // 设备已处于active状态，可以理解为已经在运行或可运行，允许重复启动
            log.warn("设备已处于active状态: id={}", equipment.getId());
        }
        if ("maintenance".equals(equipment.getStatus())) {
            throw new BusinessException(409, "设备正在维护中，无法启动")
                    .withHint("请先完成设备维护后再启动").withHintTarget("status");
        }

        equipment.setStatus("active");  // 启动设备，设置为active
        equipment.setUpdatedAt(LocalDateTime.now());
        equipment = equipmentRepository.save(equipment);

        log.info("设备启动成功: id={}", equipment.getId());
        return toDTO(equipment);
    }

    @Override
    @Transactional
    public EquipmentDTO stopEquipment(String factoryId, String equipmentId, Integer runningHours) {
        log.info("停止设备: factoryId={}, equipmentId={}, runningHours={}", factoryId, equipmentId, runningHours);

        Long equipmentIdLong = Long.parseLong(equipmentId);
        FactoryEquipment equipment = equipmentRepository.findByIdAndFactoryId(equipmentIdLong, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));

        assertNotScrapped(equipment, "停止");  // 审计 round2: 报废终态不可经停止变 inactive
        if (!"active".equals(equipment.getStatus())) {
            log.warn("设备当前状态不是active，仍允许停止: status={}", equipment.getStatus());
        }

        equipment.setStatus("inactive");  // 停止设备，设置为inactive
        if (runningHours != null) {
            // 审计 round2: totalRunningHours 可能为 null (旧数据), 兜底 0 防 NPE
            int prior = equipment.getTotalRunningHours() != null ? equipment.getTotalRunningHours() : 0;
            equipment.setTotalRunningHours(prior + runningHours);
        }
        equipment.setUpdatedAt(LocalDateTime.now());
        equipment = equipmentRepository.save(equipment);

        log.info("设备停止成功: id={}", equipment.getId());
        return toDTO(equipment);
    }

    @Override
    @Transactional
    public EquipmentDTO recordMaintenance(String factoryId, String equipmentId, LocalDate maintenanceDate,
                                         BigDecimal cost, String description) {
        log.info("记录设备维护: factoryId={}, equipmentId={}, date={}", factoryId, equipmentId, maintenanceDate);

        Long equipmentIdLong = Long.parseLong(equipmentId);
        FactoryEquipment equipment = equipmentRepository.findByIdAndFactoryId(equipmentIdLong, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));

        equipment.setLastMaintenanceDate(maintenanceDate);
        if (equipment.getMaintenanceIntervalHours() != null && equipment.getMaintenanceIntervalHours() > 0) {
            int daysInterval = equipment.getMaintenanceIntervalHours() / 24;
            equipment.setNextMaintenanceDate(maintenanceDate.plusDays(daysInterval));
        }
        equipment.setUpdatedAt(LocalDateTime.now());
        equipment = equipmentRepository.save(equipment);

        // 保存维护记录到EquipmentMaintenance表
        EquipmentMaintenance maintenance = new EquipmentMaintenance();
        maintenance.setEquipmentId(equipmentIdLong);  // 使用 Long 类型
        maintenance.setMaintenanceType("routine");
        maintenance.setMaintenanceDate(maintenanceDate);
        maintenance.setCost(cost);
        maintenance.setDescription(description);
        maintenance.setNextMaintenanceDate(equipment.getNextMaintenanceDate());
        maintenanceRepository.save(maintenance);
        log.info("维护记录已保存: equipmentId={}", equipmentId);

        log.info("设备维护记录成功: id={}", equipment.getId());
        return toDTO(equipment);
    }

    @Override
    public List<EquipmentDTO> getEquipmentNeedingMaintenance(String factoryId) {
        LocalDate checkDate = LocalDate.now().plusDays(7); // 提前7天提醒
        List<FactoryEquipment> equipment = equipmentRepository.findEquipmentNeedingMaintenance(factoryId, checkDate);
        return equipment.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<EquipmentDTO> getEquipmentWithExpiringWarranty(String factoryId, Integer daysAhead) {
        LocalDate warningDate = LocalDate.now().plusDays(daysAhead);
        List<FactoryEquipment> equipment = equipmentRepository.findEquipmentWithExpiringWarranty(factoryId, warningDate);
        return equipment.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public BigDecimal calculateDepreciatedValue(String factoryId, String equipmentId) {
        Long equipmentIdLong = Long.parseLong(equipmentId);
        FactoryEquipment equipment = equipmentRepository.findByIdAndFactoryId(equipmentIdLong, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));

        if (equipment.getPurchasePrice() == null || equipment.getDepreciationYears() == null ||
            equipment.getPurchaseDate() == null) {
            return equipment.getPurchasePrice();
        }

        long monthsUsed = ChronoUnit.MONTHS.between(equipment.getPurchaseDate(), LocalDate.now());
        long totalMonths = equipment.getDepreciationYears() * 12L;

        if (monthsUsed >= totalMonths) {
            return BigDecimal.ZERO;
        }

        BigDecimal depreciationPerMonth = equipment.getPurchasePrice().divide(
                BigDecimal.valueOf(totalMonths), 2, RoundingMode.HALF_UP);
        BigDecimal totalDepreciation = depreciationPerMonth.multiply(BigDecimal.valueOf(monthsUsed));

        return equipment.getPurchasePrice().subtract(totalDepreciation);
    }

    @Override
    public Map<String, Object> getEquipmentStatistics(String factoryId, String equipmentId) {
        Long equipmentIdLong = Long.parseLong(equipmentId);
        FactoryEquipment equipment = equipmentRepository.findByIdAndFactoryId(equipmentIdLong, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));

        Map<String, Object> statistics = new HashMap<>();
        statistics.put("equipmentId", equipment.getId());
        statistics.put("equipmentName", equipment.getEquipmentName());
        statistics.put("status", equipment.getStatus());
        statistics.put("totalRunningHours", equipment.getTotalRunningHours());
        statistics.put("purchasePrice", equipment.getPurchasePrice());
        statistics.put("currentValue", calculateDepreciatedValue(factoryId, equipmentId));

        if (equipment.getHourlyCost() != null && equipment.getTotalRunningHours() != null) {
            BigDecimal totalCost = equipment.getHourlyCost().multiply(BigDecimal.valueOf(equipment.getTotalRunningHours()));
            statistics.put("totalOperatingCost", totalCost);
        }

        statistics.put("needsMaintenance", equipment.getNextMaintenanceDate() != null &&
                equipment.getNextMaintenanceDate().isBefore(LocalDate.now().plusDays(7)));

        return statistics;
    }

    @Override
    public List<Map<String, Object>> getEquipmentUsageHistory(String factoryId, String equipmentId) {
        Long equipmentIdLong = Long.parseLong(equipmentId);
        FactoryEquipment equipment = equipmentRepository.findByIdAndFactoryId(equipmentIdLong, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));

        List<Map<String, Object>> history = new ArrayList<>();

        // equipmentId 使用 Long 类型
        List<BatchEquipmentUsage> usages = usageRepository.findByEquipmentIdOrderByStartTimeDesc(equipmentIdLong);

        for (BatchEquipmentUsage usage : usages) {
            Map<String, Object> record = new HashMap<>();
            record.put("id", usage.getId());
            record.put("batchId", usage.getBatchId());
            record.put("startTime", usage.getStartTime());
            record.put("endTime", usage.getEndTime());
            record.put("usageHours", usage.getUsageHours());
            record.put("powerConsumption", usage.getPowerConsumption());
            record.put("equipmentCost", usage.getEquipmentCost());
            history.add(record);
        }

        // 注: 移除了不必要的 try-catch，因为 equipmentId 已是 String 类型
        if (history.isEmpty()) {
            log.debug("设备使用历史为空: equipmentId={}", equipmentId);
        }

        return history;
    }

    @Override
    public List<Map<String, Object>> getEquipmentMaintenanceHistory(String factoryId, String equipmentId) {
        Long equipmentIdLong = Long.parseLong(equipmentId);
        FactoryEquipment equipment = equipmentRepository.findByIdAndFactoryId(equipmentIdLong, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));

        List<Map<String, Object>> history = new ArrayList<>();

        try {
            List<EquipmentMaintenance> maintenances = maintenanceRepository.findByEquipmentIdOrderByMaintenanceDateDesc(equipmentIdLong);

            for (EquipmentMaintenance maintenance : maintenances) {
                Map<String, Object> record = new HashMap<>();
                record.put("id", maintenance.getId());
                record.put("maintenanceType", maintenance.getMaintenanceType());
                record.put("maintenanceDate", maintenance.getMaintenanceDate());
                record.put("startTime", maintenance.getStartTime());
                record.put("endTime", maintenance.getEndTime());
                record.put("description", maintenance.getDescription());
                record.put("cost", maintenance.getCost());
                record.put("performedBy", maintenance.getPerformedBy());
                record.put("nextMaintenanceDate", maintenance.getNextMaintenanceDate());
                record.put("notes", maintenance.getNotes());
                history.add(record);
            }
        } catch (NumberFormatException e) {
            log.warn("设备ID非整数格式，无法查询维护历史: equipmentId={}", equipmentId);
        }

        return history;
    }

    @Override
    public Map<String, Object> getOverallEquipmentStatistics(String factoryId) {
        Map<String, Object> statistics = new HashMap<>();

        // 设备总数
        long totalEquipment = equipmentRepository.countByFactoryId(factoryId);
        statistics.put("totalEquipment", totalEquipment);

        // 设备总价值
        BigDecimal totalValue = equipmentRepository.calculateTotalEquipmentValue(factoryId);
        statistics.put("totalValue", totalValue != null ? totalValue : BigDecimal.ZERO);

        // 按状态统计
        List<Object[]> statusCount = equipmentRepository.countByStatus(factoryId);
        Map<String, Long> statusDistribution = new HashMap<>();
        for (Object[] row : statusCount) {
            statusDistribution.put((String) row[0], (Long) row[1]);
        }
        statistics.put("statusDistribution", statusDistribution);

        // 按类型统计
        List<Object[]> typeCount = equipmentRepository.countByType(factoryId);
        Map<String, Long> typeDistribution = new HashMap<>();
        for (Object[] row : typeCount) {
            String type = (String) row[0];
            typeDistribution.put(type != null ? type : "未分类", (Long) row[1]);
        }
        statistics.put("typeDistribution", typeDistribution);

        // 平均运行时间
        Double avgRunningHours = equipmentRepository.calculateAverageRunningHours(factoryId);
        statistics.put("averageRunningHours", avgRunningHours != null ? avgRunningHours : 0.0);

        // 总运行成本
        BigDecimal totalOperatingCost = equipmentRepository.calculateTotalOperatingCost(factoryId);
        statistics.put("totalOperatingCost", totalOperatingCost != null ? totalOperatingCost : BigDecimal.ZERO);

        return statistics;
    }

    @Override
    public Map<String, Object> getEquipmentEfficiencyReport(String factoryId, String equipmentId,
                                                           LocalDate startDate, LocalDate endDate) {
        Long equipmentIdLong = Long.parseLong(equipmentId);
        FactoryEquipment equipment = equipmentRepository.findByIdAndFactoryId(equipmentIdLong, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));

        Map<String, Object> report = new HashMap<>();
        report.put("equipmentId", equipment.getId());
        report.put("equipmentName", equipment.getEquipmentName());
        report.put("startDate", startDate);
        report.put("endDate", endDate);

        // 计算时间段内的天数
        long daysBetween = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        double plannedHours = daysBetween * 8.0; // 假设每天工作8小时

        // 计算可用率（Availability）
        double actualRunningHours = equipment.getTotalRunningHours() != null
                ? equipment.getTotalRunningHours() : 0.0;
        double availability = plannedHours > 0 ? Math.min(actualRunningHours / plannedHours, 1.0) : 0.0;

        // 计算利用率（Utilization Rate）
        double totalAvailableHours = daysBetween * 24.0;
        double utilizationRate = totalAvailableHours > 0 ? actualRunningHours / totalAvailableHours : 0.0;

        // 性能率和质量率（基础估算值，实际需要产量数据）
        double performance = 0.85; // 默认85%性能率
        double quality = 0.95;     // 默认95%质量率

        // OEE = 可用率 × 性能率 × 质量率
        double oee = availability * performance * quality;

        report.put("utilizationRate", Math.round(utilizationRate * 10000) / 100.0); // 百分比
        report.put("availability", Math.round(availability * 10000) / 100.0);
        report.put("performance", Math.round(performance * 10000) / 100.0);
        report.put("quality", Math.round(quality * 10000) / 100.0);
        report.put("oee", Math.round(oee * 10000) / 100.0);
        report.put("actualRunningHours", actualRunningHours);
        report.put("plannedHours", plannedHours);

        return report;
    }

    @Override
    @Transactional
    public List<EquipmentDTO> importEquipment(String factoryId, List<CreateEquipmentRequest> requests, Long userId) {
        log.info("批量导入设备: factoryId={}, count={}", factoryId, requests.size());

        List<EquipmentDTO> importedEquipment = new ArrayList<>();
        for (CreateEquipmentRequest request : requests) {
            try {
                EquipmentDTO equipment = createEquipment(factoryId, request, userId);
                importedEquipment.add(equipment);
            } catch (Exception e) {
                log.error("导入设备失败: name={}, error={}", request.getName(), e.getMessage());
            }
        }

        log.info("批量导入完成，成功导入 {} 个设备", importedEquipment.size());
        return importedEquipment;
    }

    @Override
    public byte[] exportEquipmentList(String factoryId) {
        log.info("导出设备列表: factoryId={}", factoryId);

        // 查询所有设备
        List<FactoryEquipment> equipment = equipmentRepository.findByFactoryId(factoryId);

        // 转换为DTO
        List<EquipmentDTO> equipmentDTOs = equipment.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());

        // 转换为Excel导出DTO
        List<com.cretas.aims.dto.equipment.EquipmentExportDTO> exportDTOs = equipmentDTOs.stream()
                .map(com.cretas.aims.dto.equipment.EquipmentExportDTO::fromEquipmentDTO)
                .collect(Collectors.toList());

        // 生成Excel文件
        com.cretas.aims.utils.ExcelUtil excelUtil = new com.cretas.aims.utils.ExcelUtil();
        byte[] excelBytes = excelUtil.exportToExcel(
                exportDTOs,
                com.cretas.aims.dto.equipment.EquipmentExportDTO.class,
                "设备列表"
        );

        log.info("设备列表导出成功: factoryId={}, count={}", factoryId, equipment.size());
        return excelBytes;
    }

    @Override
    public byte[] generateImportTemplate() {
        log.info("生成设备导入模板");

        // 使用ExcelUtil生成空模板
        com.cretas.aims.utils.ExcelUtil excelUtil = new com.cretas.aims.utils.ExcelUtil();
        byte[] templateBytes = excelUtil.generateTemplate(
                com.cretas.aims.dto.equipment.EquipmentExportDTO.class,
                "设备导入模板"
        );

        log.info("设备导入模板生成成功");
        return templateBytes;
    }

    @Override
    // 不使用@Transactional，让每个save操作独立进行，避免单行失败导致整体回滚
    public com.cretas.aims.dto.common.ImportResult<EquipmentDTO> importEquipmentFromExcel(
            String factoryId,
            java.io.InputStream inputStream) {
        log.info("开始从Excel批量导入设备: factoryId={}", factoryId);

        // 1. 解析Excel文件
        com.cretas.aims.utils.ExcelUtil excelUtil = new com.cretas.aims.utils.ExcelUtil();
        List<com.cretas.aims.dto.equipment.EquipmentExportDTO> excelData;
        try {
            excelData = excelUtil.importFromExcel(inputStream,
                    com.cretas.aims.dto.equipment.EquipmentExportDTO.class);
        } catch (Exception e) {
            log.error("Excel文件解析失败: factoryId={}", factoryId, e);
            throw new RuntimeException("Excel文件格式错误或无法解析: " + e.getMessage());
        }

        com.cretas.aims.dto.common.ImportResult<EquipmentDTO> result =
                com.cretas.aims.dto.common.ImportResult.create(excelData.size());

        // 2. 逐行验证并导入
        for (int i = 0; i < excelData.size(); i++) {
            com.cretas.aims.dto.equipment.EquipmentExportDTO exportDTO = excelData.get(i);
            int rowNumber = i + 2; // Excel行号（从2开始，1是表头）

            try {
                // 2.1 验证必填字段
                if (exportDTO.getName() == null || exportDTO.getName().trim().isEmpty()) {
                    result.addFailure(rowNumber, "设备名称不能为空", toJsonString(exportDTO));
                    continue;
                }

                // 2.2 验证编码唯一性（如果提供了编码）
                if (exportDTO.getEquipmentCode() != null && !exportDTO.getEquipmentCode().trim().isEmpty()) {
                    if (equipmentRepository.existsByFactoryIdAndEquipmentCode(factoryId, exportDTO.getEquipmentCode())) {
                        result.addFailure(rowNumber, "设备编码已存在: " + exportDTO.getEquipmentCode(),
                                toJsonString(exportDTO));
                        continue;
                    }
                }

                // 2.3 转换为Entity
                FactoryEquipment equipment = convertFromExportDTO(exportDTO, factoryId);

                // 2.4 保存
                FactoryEquipment saved = equipmentRepository.save(equipment);

                // 2.5 转换为DTO并记录成功
                EquipmentDTO dto = toDTO(saved);
                result.addSuccess(dto);

                log.debug("成功导入设备: row={}, name={}", rowNumber, exportDTO.getName());

            } catch (Exception e) {
                log.error("导入设备失败: factoryId={}, row={}, data={}", factoryId, rowNumber, exportDTO, e);
                result.addFailure(rowNumber, "保存失败: " + e.getMessage(), toJsonString(exportDTO));
            }
        }

        log.info("设备批量导入完成: factoryId={}, total={}, success={}, failure={}",
                factoryId, result.getTotalCount(), result.getSuccessCount(), result.getFailureCount());
        return result;
    }

    /**
     * 从EquipmentExportDTO转换为FactoryEquipment实体
     */
    private FactoryEquipment convertFromExportDTO(com.cretas.aims.dto.equipment.EquipmentExportDTO dto, String factoryId) {
        FactoryEquipment equipment = new FactoryEquipment();
        // id 现在是 Long 类型，由数据库自动生成
        equipment.setFactoryId(factoryId);
        equipment.setEquipmentCode(dto.getEquipmentCode() != null ? dto.getEquipmentCode() : generateEquipmentCode());
        equipment.setCode(equipment.getEquipmentCode()); // code字段使用equipmentCode
        equipment.setEquipmentName(dto.getName());
        equipment.setType(dto.getType());
        equipment.setModel(dto.getModel());
        equipment.setManufacturer(dto.getManufacturer());
        equipment.setSerialNumber(dto.getSerialNumber());

        // 解析日期字符串
        if (dto.getPurchaseDate() != null && !dto.getPurchaseDate().trim().isEmpty()) {
            try {
                equipment.setPurchaseDate(LocalDate.parse(dto.getPurchaseDate()));
            } catch (Exception e) {
                log.warn("日期解析失败: {}", dto.getPurchaseDate());
            }
        }

        equipment.setPurchasePrice(dto.getPurchasePrice());
        equipment.setHourlyCost(dto.getHourlyCost());
        equipment.setStatus(dto.getStatus() != null ? dto.getStatus().toLowerCase() : "inactive");
        equipment.setLocation(dto.getLocation());
        equipment.setTotalRunningHours(dto.getTotalRunningHours() != null ? dto.getTotalRunningHours() : 0);
        equipment.setMaintenanceIntervalHours(dto.getMaintenanceIntervalHours());

        // 解析最后维护日期
        if (dto.getLastMaintenanceDate() != null && !dto.getLastMaintenanceDate().trim().isEmpty()) {
            try {
                equipment.setLastMaintenanceDate(LocalDate.parse(dto.getLastMaintenanceDate()));
            } catch (Exception e) {
                log.warn("日期解析失败: {}", dto.getLastMaintenanceDate());
            }
        }

        // 尝试从当前登录用户获取ID，失败则使用系统默认用户
        Long currentUserId = 1L;
        try {
            Long securityUserId = com.cretas.aims.utils.SecurityUtils.getCurrentUserId();
            if (securityUserId != null) {
                currentUserId = securityUserId;
            }
        } catch (Exception e) {
            log.debug("无法获取当前用户ID，使用默认值: {}", e.getMessage());
        }
        equipment.setCreatedBy(currentUserId);
        equipment.setCreatedAt(LocalDateTime.now());
        return equipment;
    }

    /**
     * 将对象转换为JSON字符串
     */
    private String toJsonString(Object obj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    @Override
    @Transactional
    public EquipmentDTO scrapEquipment(String factoryId, String equipmentId, String reason) {
        log.info("报废设备: factoryId={}, equipmentId={}, reason={}", factoryId, equipmentId, reason);

        Long equipmentIdLong = Long.parseLong(equipmentId);
        FactoryEquipment equipment = equipmentRepository.findByIdAndFactoryId(equipmentIdLong, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));

        equipment.setStatus(STATUS_SCRAPPED);  // 报废设备标记为scrapped
        equipment.setNotes(equipment.getNotes() != null ?
                equipment.getNotes() + "\n报废原因: " + reason : "报废原因: " + reason);
        equipment.setUpdatedAt(LocalDateTime.now());
        equipment = equipmentRepository.save(equipment);

        log.info("设备报废成功: id={}, status={}", equipment.getId(), STATUS_SCRAPPED);
        return toDTO(equipment);
    }

    @Override
    public Double calculateOEE(String factoryId, String equipmentId, LocalDate startDate, LocalDate endDate) {
        Long equipmentIdLong = Long.parseLong(equipmentId);
        FactoryEquipment equipment = equipmentRepository.findByIdAndFactoryId(equipmentIdLong, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));

        // OEE = Availability × Performance × Quality

        // 计算时间段内的天数
        long daysBetween = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        double plannedHours = daysBetween * 8.0; // 假设每天计划工作8小时

        // 可用率（Availability）= 实际运行时间 / 计划运行时间
        double actualRunningHours = equipment.getTotalRunningHours() != null
                ? equipment.getTotalRunningHours() : 0.0;
        double availability = plannedHours > 0 ? Math.min(actualRunningHours / plannedHours, 1.0) : 0.0;

        // 性能率（Performance）- 基于设计产能vs实际产能
        // 暂用默认值，实际需要产量数据支持
        double performance = 0.85;

        // 质量率（Quality）= 合格品数量 / 总产量
        // 暂用默认值，实际需要质检数据支持
        double quality = 0.95;

        // OEE = A × P × Q
        double oee = availability * performance * quality;

        log.debug("OEE计算: equipmentId={}, availability={}, performance={}, quality={}, oee={}",
                equipmentId, availability, performance, quality, oee);

        return Math.round(oee * 10000) / 100.0; // 返回百分比值
    }

    /**
     * Entity转DTO
     */
    private EquipmentDTO toDTO(FactoryEquipment equipment) {
        EquipmentDTO dto = EquipmentDTO.builder()
                .id(String.valueOf(equipment.getId()))  // Long -> String 转换
                .factoryId(equipment.getFactoryId())
                .equipmentCode(equipment.getEquipmentCode())
                .name(equipment.getEquipmentName())
                .type(equipment.getType())
                .model(equipment.getModel())
                .manufacturer(equipment.getManufacturer())
                .serialNumber(equipment.getSerialNumber())
                .purchaseDate(equipment.getPurchaseDate())
                .purchasePrice(equipment.getPurchasePrice())
                .depreciationYears(equipment.getDepreciationYears())
                .status(equipment.getStatus())
                .location(equipment.getLocation())
                .hourlyCost(equipment.getHourlyCost())
                .powerConsumptionKw(equipment.getPowerConsumptionKw())
                .totalRunningHours(equipment.getTotalRunningHours())
                .maintenanceIntervalHours(equipment.getMaintenanceIntervalHours())
                .lastMaintenanceDate(equipment.getLastMaintenanceDate())
                .nextMaintenanceDate(equipment.getNextMaintenanceDate())
                .warrantyExpiryDate(equipment.getWarrantyExpiryDate())
                .notes(equipment.getNotes())
                .createdAt(equipment.getCreatedAt())
                .updatedAt(equipment.getUpdatedAt())
                .createdBy(equipment.getCreatedBy())
                .operatorId(equipment.getOperatorId())
                .version(equipment.getVersion())
                .build();

        // 填充操作员姓名
        if (equipment.getOperatorId() != null) {
            userRepository.findById(equipment.getOperatorId())
                    .ifPresent(user -> dto.setOperatorName(user.getFullName()));
        }

        // 计算当前价值
        dto.setCurrentValue(calculateDepreciatedValue(equipment.getFactoryId(), String.valueOf(equipment.getId())));

        // 计算是否需要维护
        if (equipment.getNextMaintenanceDate() != null) {
            dto.setNeedsMaintenance(equipment.getNextMaintenanceDate().isBefore(LocalDate.now().plusDays(7)));
        }

        return dto;
    }

    /**
     * 生成设备编码
     */
    private String generateEquipmentCode() {
        return "EQP-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}
